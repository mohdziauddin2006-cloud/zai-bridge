// =============================================================================
//  egl_swap_hook.cpp   (Zai-Bridge: Java EGL / GLES boundary)
//
//  wrapper.cpp sees eglSwapBuffers only when the *guest payload* imports it,
//  i.e. for a pure NDK game. A game with a Java GLSurfaceView never enters this
//  library: its swap happens inside the framework's native EGL methods,
//
//      com.google.android.gles_jni.EGLImpl.eglSwapBuffers(...)     (EGL10)
//      android.opengl.EGL14.eglSwapBuffers(...)                    (EGL14)
//
//  Both are declared `native`, so RegisterNatives() replaces their entry point
//  for the process. The replacements run the GPU compositor (upscaling and
//  frame generation), then call the real libEGL.so eglSwapBuffers() with the
//  same handles.
//
//  The same mechanism gives the Java rendering path the rest of the
//  compatibility layer, without the guest's cooperation:
//
//    * EGL14.eglChooseConfig is re-registered so a request that matches zero
//      configs (RGB565 + legacy attributes on a modern driver) is retried with
//      an RGBA8888/depth fallback instead of failing with "No config chosen".
//    * GLES20/GLES30.glShaderSource is re-registered so every shader is
//      normalised (precision header, desktop version header, legacy syntax on
//      an ES3 context) before it reaches the driver.
//    * GLES20/GLES30.glGetString extends GL_EXTENSIONS with the standard
//      OES/EXT set that every supported mobile GPU class implements.
//
//  Every hook degrades to the untouched framework method when its real symbol
//  cannot be resolved, so an unsupported device keeps rendering.
// =============================================================================

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include "cpu_compat.h"
#include "egl_config_fallback.h"
#include "gl_shader_patcher.h"
#include "gpu_compositor.h"

#include <atomic>
#include <chrono>
#include <cinttypes>
#include <cstdio>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "ARM64_WRAPPER"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using SwapFn = EGLBoolean (*)(EGLDisplay, EGLSurface);
using CurrentDisplayFn = EGLDisplay (*)();
using CurrentSurfaceFn = EGLSurface (*)(EGLint);
using ChooseConfigFn = EGLBoolean (*)(EGLDisplay, const EGLint*, EGLConfig*,
                                      EGLint, EGLint*);
using ShaderSourceFn = void (*)(GLuint, GLsizei, const GLchar* const*, const GLint*);
using GetStringFn = const GLubyte* (*)(GLenum);
using GetShaderivFn = void (*)(GLuint, GLenum, GLint*);

std::once_flag g_resolve_once;
SwapFn g_egl_swap = nullptr;
CurrentDisplayFn g_egl_current_display = nullptr;
CurrentSurfaceFn g_egl_current_surface = nullptr;
ChooseConfigFn g_egl_choose_config = nullptr;

ShaderSourceFn g_gl_shader_source = nullptr;
GetStringFn g_gl_get_string = nullptr;
GetShaderivFn g_gl_get_shaderiv = nullptr;
int g_gl_major = 0;

std::atomic<uint64_t> g_swaps{0};        // guest frames presented
std::atomic<uint64_t> g_egl10{0};
std::atomic<uint64_t> g_egl14{0};
std::atomic<uint64_t> g_failures{0};     // driver refused the swap
std::atomic<uint64_t> g_unresolved{0};   // libEGL.so had no eglSwapBuffers
std::atomic<uint64_t> g_current_fallback{0};  // handles taken from the current context
std::atomic<uint64_t> g_generated{0};    // intermediate frames presented
std::atomic<uint64_t> g_config_fallbacks{0};
std::atomic<uint64_t> g_shaders_patched{0};

const std::chrono::steady_clock::time_point g_started = std::chrono::steady_clock::now();

void resolve_graphics() {
    void* egl = dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL);
    if (egl != nullptr) {
        g_egl_swap = reinterpret_cast<SwapFn>(dlsym(egl, "eglSwapBuffers"));
        g_egl_current_display =
                reinterpret_cast<CurrentDisplayFn>(dlsym(egl, "eglGetCurrentDisplay"));
        g_egl_current_surface =
                reinterpret_cast<CurrentSurfaceFn>(dlsym(egl, "eglGetCurrentSurface"));
        g_egl_choose_config =
                reinterpret_cast<ChooseConfigFn>(dlsym(egl, "eglChooseConfig"));
    }
    void* gles = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
    if (gles != nullptr) {
        g_gl_shader_source =
                reinterpret_cast<ShaderSourceFn>(dlsym(gles, "glShaderSource"));
        g_gl_get_string = reinterpret_cast<GetStringFn>(dlsym(gles, "glGetString"));
        g_gl_get_shaderiv =
                reinterpret_cast<GetShaderivFn>(dlsym(gles, "glGetShaderiv"));
    }
    LOGI("EGL swap hook: libEGL eglSwapBuffers=%p chooseConfig=%p GLESv2 shaderSource=%p",
         reinterpret_cast<void*>(g_egl_swap),
         reinterpret_cast<void*>(g_egl_choose_config),
         reinterpret_cast<void*>(g_gl_shader_source));
}

// Reads a native handle out of one of the framework's EGL wrapper objects.
// The field is a plain long (J) on current releases; the int layout is kept
// for older system images.
bool read_handle_field(JNIEnv* env, jobject object, const char* name, jlong* out) {
    jclass cls = env->GetObjectClass(object);
    if (cls == nullptr) return false;

    jfieldID field = env->GetFieldID(cls, name, "J");
    bool ok = false;
    if (field != nullptr) {
        *out = env->GetLongField(object, field);
        ok = true;
    } else {
        env->ExceptionClear();
        field = env->GetFieldID(cls, name, "I");
        if (field != nullptr) {
            *out = static_cast<jlong>(env->GetIntField(object, field));
            ok = true;
        } else {
            env->ExceptionClear();
        }
    }
    env->DeleteLocalRef(cls);
    return ok;
}

bool read_handle_getter(JNIEnv* env, jobject object, jlong* out) {
    jclass cls = env->GetObjectClass(object);
    if (cls == nullptr) return false;
    jmethodID getter = env->GetMethodID(cls, "getHandle", "()J");
    if (getter == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(cls);
        return false;
    }
    *out = env->CallLongMethod(object, getter);
    const bool ok = !env->ExceptionCheck();
    if (!ok) env->ExceptionClear();
    env->DeleteLocalRef(cls);
    return ok;
}

// EGL10: EGLDisplayImpl.mEGLDisplay / EGLSurfaceImpl.mEGLSurface
// EGL14: EGLObjectHandle.mHandle
// Historic: mNativeDisplay / mNativeSurface
bool egl_handle(JNIEnv* env, jobject object, jlong* out) {
    if (object == nullptr) return false;
    static const char* const kFields[] = {
            "mEGLDisplay", "mEGLSurface", "mEGLContext",
            "mHandle",
            "mNativeDisplay", "mNativeSurface", "mNativeContext",
    };
    for (const char* name : kFields) {
        if (read_handle_field(env, object, name, out)) return true;
    }
    return read_handle_getter(env, object, out);
}

int host_gles_major() {
    if (g_gl_major != 0) return g_gl_major;
    if (g_gl_get_string == nullptr) return 2;
    const GLubyte* version = g_gl_get_string(GL_VERSION);
    if (version == nullptr) return 2;
    const char first = reinterpret_cast<const char*>(version)[0];
    g_gl_major = (first >= '0' && first <= '9') ? first - '0' : 2;
    return g_gl_major;
}

// ---------------------------------------------------------------------------
// Compositor at the swap boundary
// ---------------------------------------------------------------------------
bool present_frame(EGLDisplay dpy, EGLSurface surf) {
    zai::GpuCompositor& compositor = zai::GpuCompositor::instance();
    // A 64-bit guest that binds framebuffer 0 through its own GOT (see
    // native_egl_hook.cpp) renders into the scaled target, not the window.
    const bool guest_internal = compositor.guest_drew_internal();
    if (!compositor.begin(dpy, surf, guest_internal)) return false;

    // Emit 0..15 synthetic frames ahead of the real one, evenly spaced by the
    // measured guest interval.
    const int extras = compositor.synthetic_count();
    for (int i = 0; i < extras; ++i) {
        if (!compositor.present_synthetic(i)) break;
        if (g_egl_swap(dpy, surf) == EGL_TRUE) ++g_generated;
        compositor.wait_subframe();
    }
    compositor.present_current();
    compositor.end_frame();
    return extras > 0;
}

// One clock for every swap the bridge can observe from Java.
jboolean forward_swap(JNIEnv* env, jobject display, jobject surface,
                      const char* api, bool is_egl10) {
    std::call_once(g_resolve_once, resolve_graphics);
    if (g_egl_swap == nullptr) {
        if (g_unresolved++ == 0) {
            LOGE("EGL swap hook: libEGL.so has no eglSwapBuffers; frames are not counted");
        }
        return JNI_FALSE;
    }

    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLSurface surf = EGL_NO_SURFACE;

    jlong dpy_handle = 0;
    jlong surf_handle = 0;
    if (egl_handle(env, display, &dpy_handle) && egl_handle(env, surface, &surf_handle)) {
        dpy = reinterpret_cast<EGLDisplay>(static_cast<intptr_t>(dpy_handle));
        surf = reinterpret_cast<EGLSurface>(static_cast<intptr_t>(surf_handle));
    } else if (g_egl_current_display != nullptr && g_egl_current_surface != nullptr) {
        // Android 9+ refuses JNI reads of the framework's private handle fields
        // unless the process is hidden-API exempt (which is not possible on
        // every release). A GLSurfaceView swaps the surface it just made
        // current, so the current context is the same surface in practice.
        if (g_current_fallback++ == 0) {
            LOGI("EGL swap hook: EGL handle fields are hidden; using the current "
                 "EGL draw surface for frame accounting");
        }
        dpy = g_egl_current_display();
        surf = g_egl_current_surface(EGL_DRAW);
    } else {
        if (g_failures++ == 0) {
            LOGE("EGL swap hook: unreadable EGL handles (display=%p surface=%p)",
                 display, surface);
        }
        return JNI_FALSE;
    }

    present_frame(dpy, surf);

    const EGLBoolean ok = g_egl_swap(dpy, surf);

    if (ok != EGL_TRUE) {
        if (g_failures++ == 0) {
            LOGE("EGL swap hook: eglSwapBuffers(%p, %p) -> %d",
                 dpy, surf, static_cast<int>(ok));
        }
        return JNI_FALSE;
    }

    const uint64_t frames = ++g_swaps;
    if (is_egl10) {
        ++g_egl10;
    } else {
        ++g_egl14;
    }

    // Same cadence as the payload-side counter in wrapper.cpp: the first few
    // swaps prove the surface is live, then one line per second of 60 Hz video.
    if (frames <= 3 || frames % 60 == 0) {
        const double seconds = std::chrono::duration<double>(
                std::chrono::steady_clock::now() - g_started).count();
        const double fps = seconds > 0.0 ? static_cast<double>(frames) / seconds : 0.0;
        LOGI("eglSwapBuffers[java/%s]: frame %" PRIu64 " presented (%.1f fps avg, "
             "%" PRIu64 " generated, %" PRIu64 " EGL10 / %" PRIu64 " EGL14)",
             api, frames, fps, g_generated.load(), g_egl10.load(), g_egl14.load());
    }
    return JNI_TRUE;
}

// com.google.android.gles_jni.EGLImpl.eglSwapBuffers(EGLDisplay, EGLSurface)
jboolean egl10_swap(JNIEnv* env, jobject /*thiz*/, jobject display, jobject surface) {
    return forward_swap(env, display, surface, "EGL10", true);
}

// android.opengl.EGL14.eglSwapBuffers(EGLDisplay, EGLSurface)
jboolean egl14_swap(JNIEnv* env, jclass /*clazz*/, jobject display, jobject surface) {
    return forward_swap(env, display, surface, "EGL14", false);
}

// ---------------------------------------------------------------------------
// EGL config negotiation (android.opengl.EGL14.eglChooseConfig)
// ---------------------------------------------------------------------------
jboolean egl14_choose_config(JNIEnv* env, jclass /*clazz*/, jobject display,
                             jintArray attribs_array, jobjectArray configs_array,
                             jint config_size, jintArray num_array) {
    std::call_once(g_resolve_once, resolve_graphics);
    if (g_egl_choose_config == nullptr) return JNI_FALSE;

    EGLDisplay dpy = EGL_NO_DISPLAY;
    jlong handle = 0;
    if (egl_handle(env, display, &handle)) {
        dpy = reinterpret_cast<EGLDisplay>(static_cast<intptr_t>(handle));
    } else {
        dpy = g_egl_current_display != nullptr ? g_egl_current_display()
                                               : EGL_NO_DISPLAY;
    }

    std::vector<EGLint> attribs;
    if (attribs_array != nullptr) {
        const jsize count = env->GetArrayLength(attribs_array);
        attribs.resize(static_cast<size_t>(count > 0 ? count : 0));
        if (count > 0) {
            env->GetIntArrayRegion(attribs_array, 0, count, attribs.data());
        }
    }

    const jsize capacity = config_size > 0 ? config_size : 0;
    std::vector<EGLConfig> found(static_cast<size_t>(capacity > 0 ? capacity : 1), nullptr);
    EGLint num = 0;
    int strategy = 0;
    const EGLBoolean ok = zai::eglc::choose_config(
            g_egl_choose_config, dpy, attribs, found.data(), capacity, &num, &strategy);

    if (num_array != nullptr && env->GetArrayLength(num_array) > 0) {
        const jint value = static_cast<jint>(num);
        env->SetIntArrayRegion(num_array, 0, 1, &value);
    }

    if (ok != EGL_TRUE || num <= 0) {
        if (g_config_fallbacks.fetch_add(1) == 0) {
            LOGE("eglChooseConfig: no usable config (%s)", zai::eglc::strategy_name(strategy));
        }
        return JNI_FALSE;
    }

    if (strategy != 0) {
        const uint64_t seen = ++g_config_fallbacks;
        if (seen <= 3) {
            LOGI("eglChooseConfig: request matched 0 configs; %s supplied %d "
                 "(fallback #%" PRIu64 ")",
                 zai::eglc::strategy_name(strategy), num, seen);
        }
    }

    if (configs_array == nullptr || capacity <= 0) return JNI_TRUE;

    // EGLConfig's constructor is private; JNI may still call it, which is how
    // a fallback config can be handed back to the guest without reflection.
    jclass config_cls = env->FindClass("android/opengl/EGLConfig");
    if (config_cls == nullptr) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    jmethodID ctor = env->GetMethodID(config_cls, "<init>", "(J)V");
    if (ctor == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(config_cls);
        LOGI("eglChooseConfig: EGLConfig(J) is unavailable; keeping the driver result");
        return JNI_FALSE;
    }

    const jsize limit = num < capacity ? num : capacity;
    for (jsize i = 0; i < limit; ++i) {
        jobject object = env->NewObject(config_cls, ctor,
                                        static_cast<jlong>(reinterpret_cast<intptr_t>(
                                                found[static_cast<size_t>(i)])));
        if (object == nullptr) {
            env->ExceptionClear();
            break;
        }
        env->SetObjectArrayElement(configs_array, i, object);
        env->DeleteLocalRef(object);
    }
    env->DeleteLocalRef(config_cls);
    return JNI_TRUE;
}

// ---------------------------------------------------------------------------
// GLES shader / extension negotiation
// ---------------------------------------------------------------------------
void deliver_shader_source(GLuint shader, const std::string& source) {
    if (g_gl_shader_source == nullptr) return;
    zai::glsl::Stage stage = zai::glsl::Stage::Fragment;
    if (g_gl_get_shaderiv != nullptr) {
        GLint type = GL_FRAGMENT_SHADER;
        g_gl_get_shaderiv(shader, GL_SHADER_TYPE, &type);
        if (type == GL_VERTEX_SHADER) stage = zai::glsl::Stage::Vertex;
    }
    const zai::glsl::Patch patch =
            zai::glsl::patch_shader_source(source, stage, host_gles_major());
    if (patch.changed && g_shaders_patched.fetch_add(1) == 0) {
        LOGI("glShaderSource: normalising legacy GLSL "
             "(precision=%d es3=%d desktop=%d)",
             patch.added_precision ? 1 : 0, patch.translated_es3 ? 1 : 0,
             patch.stripped_desktop_version ? 1 : 0);
    }
    const GLchar* text = patch.source.c_str();
    g_gl_shader_source(shader, 1, &text, nullptr);
}

// GLES20.glShaderSource(int shader, String source)
void gles_shader_source(JNIEnv* env, jclass /*clazz*/, jint shader, jstring source) {
    if (source == nullptr) {
        if (g_gl_shader_source != nullptr) {
            const GLchar* empty = "";
            g_gl_shader_source(static_cast<GLuint>(shader), 1, &empty, nullptr);
        }
        return;
    }
    const char* chars = env->GetStringUTFChars(source, nullptr);
    std::string text = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(source, chars);
    deliver_shader_source(static_cast<GLuint>(shader), text);
}

// GLES20.glGetString(int name)
jstring gles_get_string(JNIEnv* env, jclass /*clazz*/, jint name) {
    if (g_gl_get_string == nullptr) return nullptr;
    const char* text = reinterpret_cast<const char*>(
            g_gl_get_string(static_cast<GLenum>(name)));
    if (text == nullptr) return nullptr;
    if (static_cast<GLenum>(name) == GL_EXTENSIONS) {
        const std::string merged = zai::glsl::merge_extensions(text);
        return env->NewStringUTF(merged.c_str());
    }
    return env->NewStringUTF(text);
}

// ---------------------------------------------------------------------------
// Registration
// ---------------------------------------------------------------------------
bool register_native(JNIEnv* env, const char* class_name, const char* method,
                     const char* signature, void* fn) {
    // FindClass takes the internal form; only the reflection APIs take dots.
    // CheckJNI aborts on the dotted spelling, so convert before looking up.
    char internal[128];
    size_t i = 0;
    for (; class_name[i] != '\0' && i + 1 < sizeof internal; ++i) {
        internal[i] = class_name[i] == '.' ? '/' : class_name[i];
    }
    internal[i] = '\0';

    jclass cls = env->FindClass(internal);
    if (cls == nullptr) {
        env->ExceptionClear();
        LOGE("EGL swap hook: %s is not on this system", class_name);
        return false;
    }

    JNINativeMethod entry;
    entry.name = const_cast<char*>(method);
    entry.signature = const_cast<char*>(signature);
    entry.fnPtr = fn;

    const jint rc = env->RegisterNatives(cls, &entry, 1);
    const bool ok = rc == JNI_OK && !env->ExceptionCheck();
    if (!ok) env->ExceptionClear();
    env->DeleteLocalRef(cls);
    return ok;
}

}   // namespace

// Installs the hooks. Called once, from EglSwapHook's static initialiser.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ziauddin_zaibridge_core_EglSwapHook_nativeInstall(JNIEnv* env, jclass /*clazz*/) {
    std::call_once(g_resolve_once, resolve_graphics);

    const bool egl10 = register_native(env,
            "com.google.android.gles_jni.EGLImpl", "eglSwapBuffers",
            "(Ljavax/microedition/khronos/egl/EGLDisplay;"
            "Ljavax/microedition/khronos/egl/EGLSurface;)Z",
            reinterpret_cast<void*>(egl10_swap));

    const bool egl14 = register_native(env,
            "android.opengl.EGL14", "eglSwapBuffers",
            "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;)Z",
            reinterpret_cast<void*>(egl14_swap));

    bool config_hook = false;
    if (g_egl_choose_config != nullptr) {
        config_hook = register_native(env, "android.opengl.EGL14", "eglChooseConfig",
                "(Landroid/opengl/EGLDisplay;[I[Landroid/opengl/EGLConfig;I[I)Z",
                reinterpret_cast<void*>(egl14_choose_config));
    }

    bool shader_hooks = false;
    bool extension_hooks = false;
    if (g_gl_shader_source != nullptr) {
        shader_hooks =
                register_native(env, "android.opengl.GLES20", "glShaderSource",
                                "(ILjava/lang/String;)V",
                                reinterpret_cast<void*>(gles_shader_source)) ||
                register_native(env, "android.opengl.GLES30", "glShaderSource",
                                "(ILjava/lang/String;)V",
                                reinterpret_cast<void*>(gles_shader_source));
    }
    if (g_gl_get_string != nullptr) {
        extension_hooks =
                register_native(env, "android.opengl.GLES20", "glGetString",
                                "(I)Ljava/lang/String;",
                                reinterpret_cast<void*>(gles_get_string)) ||
                register_native(env, "android.opengl.GLES30", "glGetString",
                                "(I)Ljava/lang/String;",
                                reinterpret_cast<void*>(gles_get_string));
    }

    LOGI("EGL/GL hooks: swap10=%s swap14=%s configFallback=%s glslPatch=%s "
         "extensions=%s (Java GLSurfaceView and NDK swaps share one frame clock)",
         egl10 ? "yes" : "no", egl14 ? "yes" : "no", config_hook ? "yes" : "no",
         shader_hooks ? "yes" : "no", extension_hooks ? "yes" : "no");
    return (egl10 || egl14) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ziauddin_zaibridge_core_EglSwapHook_nativeStats(JNIEnv* env, jclass /*clazz*/) {
    const double seconds = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - g_started).count();
    const uint64_t frames = g_swaps.load();
    char buf[384];
    snprintf(buf, sizeof buf,
             "java_swaps=%" PRIu64 " egl10=%" PRIu64 " egl14=%" PRIu64
             " generated=%" PRIu64 " configFallbacks=%" PRIu64
             " shadersPatched=%" PRIu64 " current_context=%" PRIu64
             " failures=%" PRIu64 " unresolved=%" PRIu64 " fps=%.1f",
             frames, g_egl10.load(), g_egl14.load(), g_generated.load(),
             g_config_fallbacks.load(), g_shaders_patched.load(),
             g_current_fallback.load(), g_failures.load(), g_unresolved.load(),
             seconds > 0.0 ? static_cast<double>(frames) / seconds : 0.0);
    return env->NewStringUTF(buf);
}

// Applies the per-game / global graphics configuration. Safe to call from any
// thread; the compositor reads it on the guest's GL thread.
extern "C" JNIEXPORT void JNICALL
Java_com_ziauddin_zaibridge_core_EglSwapHook_nativeConfigure(
        JNIEnv* /*env*/, jclass /*clazz*/, jint upscaler, jint scale_percent,
        jint sharpness_percent, jint neural_percent, jint frame_gen,
        jint temporal, jint auto_mfg, jint preset) {
    zai::GraphicsConfig config;
    config.preset = preset;
    config.upscaler = upscaler;
    config.scale_percent = scale_percent;
    config.sharpness_percent = sharpness_percent;
    config.neural_percent = neural_percent;
    config.frame_gen = frame_gen;
    config.temporal = temporal;
    config.auto_mfg = auto_mfg;
    zai::GpuCompositor::instance().configure(config);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ziauddin_zaibridge_core_EglSwapHook_nativeSetCompare(
        JNIEnv* /*env*/, jclass /*clazz*/, jfloat split) {
    zai::GpuCompositor::instance().set_compare(split);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ziauddin_zaibridge_core_EglSwapHook_nativeCompositorStats(
        JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF(zai::GpuCompositor::instance().stats().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ziauddin_zaibridge_core_EglSwapHook_nativeGraphicsInfo(
        JNIEnv* env, jclass /*clazz*/) {
    zai::GpuCompositor& compositor = zai::GpuCompositor::instance();
    const std::string text = std::string("gpu=") + compositor.gpu_description() +
                             " cpu=" + zai::cpu::summary();
    return env->NewStringUTF(text.c_str());
}

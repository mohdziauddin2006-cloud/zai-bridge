// =============================================================================
//  egl_swap_hook.cpp   (Zai-Bridge: frame accounting at the Java EGL boundary)
//
//  wrapper.cpp sees eglSwapBuffers only when the *guest payload* imports it,
//  i.e. for a pure NDK game. A game with a Java GLSurfaceView never enters this
//  library: its swap happens inside the framework's native EGL methods,
//
//      com.google.android.gles_jni.EGLImpl.eglSwapBuffers(...)     (EGL10)
//      android.opengl.EGL14.eglSwapBuffers(...)                    (EGL14)
//
//  Both are declared `native`, so RegisterNatives() replaces their entry point
//  for the process. The replacements count the frame, then call the real
//  libEGL.so eglSwapBuffers() with the same handles, which keeps the driver's
//  vsync pacing exactly as the game asked for it.
//
//  The EGL object layouts are resolved from the objects themselves: the EGL10
//  implementations (EGLDisplayImpl / EGLSurfaceImpl) hold mEGLDisplay and
//  mEGLSurface, and the EGL14 wrappers derive from EGLObjectHandle and hold
//  mHandle. Older layouts (mNativeDisplay / mNativeSurface, int-typed handles,
//  getHandle()) are tried as fallbacks, so the hook degrades instead of
//  guessing wrong.
//
//  This file is compiled into both the translating and the passthrough
//  backends, so a 32-bit phone counts frames the same way a 64-bit one does.
// =============================================================================

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <EGL/egl.h>

#include <atomic>
#include <chrono>
#include <cinttypes>
#include <cstdio>
#include <cstdint>
#include <mutex>

#define LOG_TAG "ARM64_WRAPPER"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using SwapFn = EGLBoolean (*)(EGLDisplay, EGLSurface);
using CurrentDisplayFn = EGLDisplay (*)();
using CurrentSurfaceFn = EGLSurface (*)(EGLint);

std::once_flag g_resolve_once;
SwapFn g_egl_swap = nullptr;
CurrentDisplayFn g_egl_current_display = nullptr;
CurrentSurfaceFn g_egl_current_surface = nullptr;

std::atomic<uint64_t> g_swaps{0};        // swaps presented through this hook
std::atomic<uint64_t> g_egl10{0};
std::atomic<uint64_t> g_egl14{0};
std::atomic<uint64_t> g_failures{0};     // driver refused the swap
std::atomic<uint64_t> g_unresolved{0};   // libEGL.so had no eglSwapBuffers
std::atomic<uint64_t> g_current_fallback{0};  // handles taken from the current context

const std::chrono::steady_clock::time_point g_started = std::chrono::steady_clock::now();

void resolve_swap() {
    void* lib = dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL);
    if (lib != nullptr) {
        g_egl_swap = reinterpret_cast<SwapFn>(dlsym(lib, "eglSwapBuffers"));
        g_egl_current_display =
                reinterpret_cast<CurrentDisplayFn>(dlsym(lib, "eglGetCurrentDisplay"));
        g_egl_current_surface =
                reinterpret_cast<CurrentSurfaceFn>(dlsym(lib, "eglGetCurrentSurface"));
    }
    LOGI("EGL swap hook: libEGL eglSwapBuffers=%p", reinterpret_cast<void*>(g_egl_swap));
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

// One clock for every swap the bridge can observe from Java.
jboolean forward_swap(JNIEnv* env, jobject display, jobject surface,
                     const char* api, bool is_egl10) {
    std::call_once(g_resolve_once, resolve_swap);
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
             "%" PRIu64 " EGL10 / %" PRIu64 " EGL14)",
             api, frames, fps, g_egl10.load(), g_egl14.load());
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
    const bool egl10 = register_native(env,
            "com.google.android.gles_jni.EGLImpl", "eglSwapBuffers",
            "(Ljavax/microedition/khronos/egl/EGLDisplay;"
            "Ljavax/microedition/khronos/egl/EGLSurface;)Z",
            reinterpret_cast<void*>(egl10_swap));

    const bool egl14 = register_native(env,
            "android.opengl.EGL14", "eglSwapBuffers",
            "(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;)Z",
            reinterpret_cast<void*>(egl14_swap));

    LOGI("EGL swap hooks: EGL10=%s EGL14=%s (Java GLSurfaceView and NDK swaps "
         "now share one frame clock)", egl10 ? "yes" : "no", egl14 ? "yes" : "no");
    return (egl10 || egl14) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ziauddin_zaibridge_core_EglSwapHook_nativeStats(JNIEnv* env, jclass /*clazz*/) {
    const double seconds = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - g_started).count();
    const uint64_t frames = g_swaps.load();
    char buf[256];
    snprintf(buf, sizeof buf,
             "java_swaps=%" PRIu64 " egl10=%" PRIu64 " egl14=%" PRIu64
             " current_context=%" PRIu64 " failures=%" PRIu64
             " unresolved=%" PRIu64 " fps=%.1f",
             frames, g_egl10.load(), g_egl14.load(), g_current_fallback.load(),
             g_failures.load(), g_unresolved.load(),
             seconds > 0.0 ? static_cast<double>(frames) / seconds : 0.0);
    return env->NewStringUTF(buf);
}

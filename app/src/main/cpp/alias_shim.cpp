// =============================================================================
// libbridge_alias_shim.so — the guest's native library alias.
//
// The guest APK calls System.loadLibrary("main"), System.loadLibrary("unity"),
// and so on. Its dex loader answers those calls with a file it stages itself,
// because ART refuses to open one path from two class loaders and the payload
// the APK ships is the ARM32 guest, not a host library.
//
// That staging used to be a byte copy of libwrapper.so. It satisfied ART but
// created a second, independent copy of the runtime: the framework's
// ANativeActivity_onCreate then ran in an instance whose g_bridge / g_emulator
// were still null ("bridge or emulator not initialised"), so a NativeActivity
// title never reached its native entry point and Unity quit before resuming.
//
// This shim is the fix. It is a few kilobytes with no dependency on the host
// C++ runtime, and every entry point it exports forwards to the one live
// libwrapper.so that UnicornBridge already loaded into the process. The alias
// files are copies of this shim, so ART still sees distinct paths while the
// session state stays a single instance.
// =============================================================================

#include <android/log.h>
#include <android/native_activity.h>
#include <dlfcn.h>
#include <jni.h>

#include <cstdio>
#include <cstring>

#define LOG_TAG "ZaiAliasShim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

void* g_runtime = nullptr;

// Last resort if the linker will not resolve the soname in this namespace: the
// guest class loader gets its own library search path, so the wrapper may not be
// on it even though it is mapped. /proc/self/maps always names the live file.
bool runtime_path_from_maps(char* out, size_t capacity) {
    FILE* maps = fopen("/proc/self/maps", "r");
    if (!maps) return false;
    char line[1024];
    bool found = false;
    while (fgets(line, sizeof line, maps)) {
        if (!strstr(line, "/libwrapper.so")) continue;
        const char* path = strchr(line, '/');
        if (!path) continue;
        size_t length = strlen(path);
        while (length > 0 && (path[length - 1] == '\n' || path[length - 1] == '\r' ||
                              path[length - 1] == ' ')) {
            --length;
        }
        if (length == 0 || length >= capacity) continue;
        memcpy(out, path, length);
        out[length] = '\0';
        found = true;
        break;
    }
    fclose(maps);
    return found;
}

void* runtime() {
    if (g_runtime) return g_runtime;

    // Reuse the resident instance without a second mapping.
#ifdef RTLD_NOLOAD
    g_runtime = dlopen("libwrapper.so", RTLD_NOW | RTLD_NOLOAD);
#endif
    if (!g_runtime) g_runtime = dlopen("libwrapper.so", RTLD_NOW);
    if (!g_runtime) {
        char path[768];
        if (runtime_path_from_maps(path, sizeof path)) {
            g_runtime = dlopen(path, RTLD_NOW);
        }
    }

    if (g_runtime) {
        Dl_info info{};
        if (dladdr(dlsym(g_runtime, "ANativeActivity_onCreate"), &info) && info.dli_fname) {
            LOGI("alias shim forwarding to the live runtime %s (base %p)",
                 info.dli_fname, info.dli_fbase);
        } else {
            LOGI("alias shim forwarding to the live libwrapper.so instance");
        }
    } else {
        LOGE("libwrapper.so is not reachable: %s", dlerror());
    }
    return g_runtime;
}

template <typename Fn>
Fn runtime_symbol(const char* name) {
    void* handle = runtime();
    if (!handle) return nullptr;
    void* symbol = dlsym(handle, name);
    if (!symbol) LOGE("libwrapper.so does not export %s", name);
    return reinterpret_cast<Fn>(symbol);
}

}  // namespace

// -----------------------------------------------------------------------------
// JNI lifecycle
// -----------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    using Fn = jint (*)(JavaVM*, void*);
    // The wrapper is normally resident already and its JNI_OnLoad is idempotent
    // (it only records the JavaVM), so forwarding is safe in either order.
    Fn onload = runtime_symbol<Fn>("JNI_OnLoad");
    if (!onload) return JNI_VERSION_1_6;
    return onload(vm, reserved);
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    using Fn = void (*)(JavaVM*, void*);
    Fn onunload = runtime_symbol<Fn>("JNI_OnUnload");
    if (onunload) onunload(vm, reserved);
}

// -----------------------------------------------------------------------------
// NativeActivity entry point
//
// This is the symbol the framework resolves for a manifest's
// android.app.lib_name. Forwarding it is what makes the guest's native activity
// reach the same session the bridge opened.
// -----------------------------------------------------------------------------
extern "C" JNIEXPORT void ANativeActivity_onCreate(
        ANativeActivity* activity, void* savedState, size_t savedStateSize) {
    using Fn = void (*)(ANativeActivity*, void*, size_t);
    Fn on_create = runtime_symbol<Fn>("ANativeActivity_onCreate");
    if (!on_create) {
        LOGE("no ANativeActivity_onCreate in the live runtime; guest will not start");
        return;
    }
    LOGI("forwarding ANativeActivity_onCreate(%p)", static_cast<void*>(activity));
    on_create(activity, savedState, savedStateSize);
}

// -----------------------------------------------------------------------------
// Unity NativeLoader thunks
// -----------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_unity3d_player_NativeLoader_load(JNIEnv* env, jclass clazz, jstring libName) {
    using Fn = jboolean (*)(JNIEnv*, jclass, jstring);
    Fn fn = runtime_symbol<Fn>("Java_com_unity3d_player_NativeLoader_load");
    return fn ? fn(env, clazz, libName) : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_unity3d_player_NativeLoader_load__Ljava_lang_String_2(
        JNIEnv* env, jclass clazz, jstring libName) {
    using Fn = jboolean (*)(JNIEnv*, jclass, jstring);
    Fn fn = runtime_symbol<Fn>("Java_com_unity3d_player_NativeLoader_load__Ljava_lang_String_2");
    if (fn) return fn(env, clazz, libName);
    fn = runtime_symbol<Fn>("Java_com_unity3d_player_NativeLoader_load");
    return fn ? fn(env, clazz, libName) : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_unity3d_player_NativeLoader_unload(JNIEnv* env, jclass clazz) {
    using Fn = jboolean (*)(JNIEnv*, jclass);
    Fn fn = runtime_symbol<Fn>("Java_com_unity3d_player_NativeLoader_unload");
    return fn ? fn(env, clazz) : JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_unity3d_player_NativeLoader_unload__Ljava_lang_String_2(
        JNIEnv* env, jclass clazz, jstring libName) {
    using NamedFn = jboolean (*)(JNIEnv*, jclass, jstring);
    NamedFn named = runtime_symbol<NamedFn>(
            "Java_com_unity3d_player_NativeLoader_unload__Ljava_lang_String_2");
    if (named) return named(env, clazz, libName);
    // The 0-argument unload is all the caller needs if the named overload is absent.
    using PlainFn = jboolean (*)(JNIEnv*, jclass);
    PlainFn plain = runtime_symbol<PlainFn>("Java_com_unity3d_player_NativeLoader_unload");
    return plain ? plain(env, clazz) : JNI_TRUE;
}

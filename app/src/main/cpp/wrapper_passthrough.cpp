// =============================================================================
//  wrapper_passthrough.cpp   (Zai-Bridge 32-bit host backend)
//
//  On an armeabi-v7a or x86 device the guest payload does not need translating:
//  it is the same architecture as the process. This library keeps the Java
//  facade (UnicornBridge) identical across all four shipped ABIs while telling
//  it that the native payload can be loaded directly.
//
//  It deliberately exports the same JNI surface as the Unicorn core, so no Java
//  code has to branch on the host ABI at the call site.
// =============================================================================

#include <jni.h>
#include <android/log.h>

#include <cstdio>

#define LOG_TAG "ARM64_WRAPPER"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    LOGI("==============================================================");
    LOGI(" Zai-Bridge 32-bit passthrough backend");
    LOGI(" guest payloads run natively on this host ABI");
    LOGI("==============================================================");
    return JNI_VERSION_1_6;
}

// The 32-bit host runs the guest libraries itself; the session is a no-op that
// reports SESSION_PASSTHROUGH (-4) to the Java side.
extern "C" JNIEXPORT jint JNICALL
Java_com_ziauddin_zaibridge_core_UnicornBridge_nativeStartSession(
        JNIEnv* env, jclass /*clazz*/, jstring gameDir, jstring obbPath,
        jstring packageName, jobject guestLoader, jobject guestActivity) {
    const char* dir = gameDir ? env->GetStringUTFChars(gameDir, nullptr) : nullptr;
    LOGI("passthrough session: dir=%s (no translation needed)", dir ? dir : "?");
    if (dir) env->ReleaseStringUTFChars(gameDir, dir);
    (void)obbPath; (void)packageName; (void)guestLoader; (void)guestActivity;
    return -4;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ziauddin_zaibridge_core_UnicornBridge_nativeSetSurface(
        JNIEnv* /*env*/, jclass /*clazz*/, jobject /*surface*/) {
    // The guest's own GLSurfaceView owns the window on a native 32-bit host.
}

extern "C" JNIEXPORT void JNICALL
Java_com_ziauddin_zaibridge_core_UnicornBridge_nativeSetRuntimeSysroot(
        JNIEnv* /*env*/, jclass /*clazz*/, jstring /*path*/) {
}

extern "C" JNIEXPORT void JNICALL
Java_com_ziauddin_zaibridge_core_UnicornBridge_nativeSetGuestActivity(
        JNIEnv* /*env*/, jclass /*clazz*/, jobject /*activity*/) {
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ziauddin_zaibridge_core_UnicornBridge_nativeIsSessionActive(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ziauddin_zaibridge_core_UnicornBridge_nativeVersion(
        JNIEnv* env, jclass /*clazz*/) {
#if defined(__arm__)
    const char* abi = "armeabi-v7a";
#elif defined(__i386__)
    const char* abi = "x86";
#else
    const char* abi = "32-bit";
#endif
    char buf[128];
    snprintf(buf, sizeof buf, "passthrough - ARM32 guest native on %s host", abi);
    return env->NewStringUTF(buf);
}

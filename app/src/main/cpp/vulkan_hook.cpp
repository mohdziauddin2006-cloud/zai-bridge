// =============================================================================
//  vulkan_hook.cpp  (Zai-Bridge: Vulkan present passthrough + live telemetry)
//
//  A Vulkan game never touches EGL: it asks libvulkan.so for
//  vkQueuePresentKHR through vkGetDeviceProcAddr (or vkGetInstanceProcAddr),
//  which is why a GOT slot for vkQueuePresentKHR alone would never fire.
//
//  Both proc-address resolvers are interposed with the same PLT/GOT engine as
//  native_egl_hook.cpp. The bridge answers the "vkQueuePresentKHR" query with
//  its own passthrough, so every presented image is counted and timed while the
//  real driver call is forwarded unchanged. Nothing about the swapchain, the
//  queue or the image layout is modified: this is observing, not compositing.
//
//  The counters land in the same telemetry pill and settings sheet as the GL
//  path, so a Vulkan title shows "vulkan=N fps=N" instead of silence.
// =============================================================================

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>

#include "native_egl_hook.h"

#define LOG_TAG "ARM64_WRAPPER"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Opaque Vulkan handles: the hook only forwards them.
using VkInstance = void*;
using VkDevice = void*;
using VkQueue = void*;
using VkResult = int32_t;

// The prefix of VkPresentInfoKHR needed to read scene telemetry. The driver
// receives the same pointer, so the layout must match the Vulkan headers.
struct PresentInfoPrefix {
    int32_t s_type;
    const void* next;
    uint32_t wait_semaphore_count;
    const void* wait_semaphores;
    uint32_t swapchain_count;
};

using GetInstanceProcAddrFn = void* (*)(VkInstance, const char*);
using GetDeviceProcAddrFn = void* (*)(VkDevice, const char*);
using QueuePresentFn = VkResult (*)(VkQueue, const void*);

std::once_flag g_vulkan_once;
GetInstanceProcAddrFn g_real_instance_proc = nullptr;
GetDeviceProcAddrFn g_real_device_proc = nullptr;
QueuePresentFn g_real_queue_present = nullptr;

std::atomic<uint64_t> g_presents{0};
std::atomic<uint64_t> g_images{0};
std::atomic<uint64_t> g_present_failures{0};
std::atomic<bool> g_available{false};

const std::chrono::steady_clock::time_point g_started = std::chrono::steady_clock::now();

double g_last_present_ms = 0.0;
std::chrono::steady_clock::time_point g_last_present{};

void resolve_vulkan_once() {
    void* vulkan = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (vulkan == nullptr) {
        LOGI("Vulkan hook: this device exposes no libvulkan.so");
        return;
    }
    g_real_instance_proc = reinterpret_cast<GetInstanceProcAddrFn>(
            dlsym(vulkan, "vkGetInstanceProcAddr"));
    g_real_device_proc = reinterpret_cast<GetDeviceProcAddrFn>(
            dlsym(vulkan, "vkGetDeviceProcAddr"));
    g_real_queue_present = reinterpret_cast<QueuePresentFn>(
            dlsym(vulkan, "vkQueuePresentKHR"));
    g_available.store(g_real_queue_present != nullptr);
    LOGI("Vulkan hook: vkQueuePresentKHR=%p instanceProc=%p deviceProc=%p",
         reinterpret_cast<void*>(g_real_queue_present),
         reinterpret_cast<void*>(g_real_instance_proc),
         reinterpret_cast<void*>(g_real_device_proc));
}

VkResult trampoline_queue_present(VkQueue queue, const void* present_info) {
    const auto now = std::chrono::steady_clock::now();
    if (g_last_present.time_since_epoch().count() != 0) {
        g_last_present_ms = std::chrono::duration<double, std::milli>(
                now - g_last_present).count();
    }
    g_last_present = now;
    ++g_presents;

    // swapchainCount is read for telemetry only; the pointer is untouched.
    if (present_info != nullptr) {
        const auto* info = reinterpret_cast<const PresentInfoPrefix*>(present_info);
        g_images += info->swapchain_count > 0 && info->swapchain_count < 16
                            ? info->swapchain_count : 1;
    } else {
        ++g_images;
    }

    if (g_real_queue_present == nullptr) return static_cast<VkResult>(-9);  // VK_ERROR_OUT_OF_DATE_KHR
    const VkResult result = g_real_queue_present(queue, present_info);
    if (result != 0 && result != 1) ++g_present_failures;  // not SUCCESS / SUBOPTIMAL
    return result;
}

void* delegate_proc(const char* name) {
    if (name == nullptr) return nullptr;
    if (std::strcmp(name, "vkQueuePresentKHR") == 0) {
        return reinterpret_cast<void*>(&trampoline_queue_present);
    }
    return nullptr;
}

void* trampoline_instance_proc(VkInstance instance, const char* name) {
    void* hooked = delegate_proc(name);
    if (hooked != nullptr) return hooked;
    if (g_real_instance_proc == nullptr) return nullptr;
    return g_real_instance_proc(instance, name);
}

void* trampoline_device_proc(VkDevice device, const char* name) {
    void* hooked = delegate_proc(name);
    if (hooked != nullptr) return hooked;
    if (g_real_device_proc == nullptr) return nullptr;
    return g_real_device_proc(device, name);
}

}  // namespace

// ---------------------------------------------------------------------------
// JNI: called from EglSwapHook.install() alongside the GL hooks
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ziauddin_zaibridge_core_EglSwapHook_nativeInstallVulkanHooks(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    std::call_once(g_vulkan_once, resolve_vulkan_once);
    if (!g_available.load()) return JNI_FALSE;

    static zai::plthook::Target kTargets[] = {
            {"vkGetInstanceProcAddr", reinterpret_cast<void*>(&trampoline_instance_proc)},
            {"vkGetDeviceProcAddr", reinterpret_cast<void*>(&trampoline_device_proc)},
            {"vkQueuePresentKHR", reinterpret_cast<void*>(&trampoline_queue_present)},
    };
    const int patched = zai::plthook::patch(kTargets, 3);
    LOGI("Vulkan hook: queue-present passthrough %s (%d slot(s) patched)",
         patched > 0 ? "live" : "armed for the next dlopen", patched);
    return patched > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ziauddin_zaibridge_core_EglSwapHook_nativeVulkanStats(
        JNIEnv* env, jclass /*clazz*/) {
    const double seconds = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - g_started).count();
    const uint64_t presents = g_presents.load();
    char buf[256];
    std::snprintf(buf, sizeof buf,
                  "vulkan=%s presents=%llu images=%llu failures=%llu "
                  "presentMs=%.2f fps=%.1f",
                  g_available.load() ? "ready" : "absent",
                  static_cast<unsigned long long>(presents),
                  static_cast<unsigned long long>(g_images.load()),
                  static_cast<unsigned long long>(g_present_failures.load()),
                  g_last_present_ms,
                  seconds > 0.0 ? static_cast<double>(presents) / seconds : 0.0);
    return env->NewStringUTF(buf);
}

// =============================================================================
//  vulkan_hook.h  (Zai-Bridge v3.0.0: Universal Vulkan Present Hook)
//
//  Intercepts vkQueuePresentKHR (both directly via PLT/GOT and dynamically via
//  vkGetDeviceProcAddr / vkGetInstanceProcAddr) so Vulkan-based normal 64-bit
//  games run cleanly inside Zai-Bridge's container with live FPS telemetry and
//  the floating quick-settings drawer active without crashing.
// =============================================================================
#pragma once

#include <string>

namespace zai {
namespace vulkan_hook {

// Scans loaded guest libraries via dl_iterate_phdr() and hooks Vulkan loader
// entry points. Safe to call repeatedly as games dlopen() engine libraries.
int install_or_refresh();

// Returns live Vulkan frame-rate and present counters.
std::string stats();

// Returns the measured Vulkan guest FPS (0.0 when no Vulkan presents occurred).
double current_fps();

}  // namespace vulkan_hook
}  // namespace zai

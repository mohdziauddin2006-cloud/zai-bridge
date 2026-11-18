// =============================================================================
//  egl_config_fallback.h  (Zai-Bridge: driver-agnostic EGL config negotiation)
//
//  An old game asks for RGB565 with an 8-bit alpha, a 16-bit depth buffer and
//  no multisampling. Adreno, Mali, PowerVR and Xclipse drivers are free to
//  return zero matching configs for that request, and the game then dies with
//  `IllegalArgumentException: No config chosen`.
//
//  choose_config() retries the request with progressively relaxed attributes:
//    1. upgrade the colour buffer to RGBA8888 (still matching depth/stencil)
//    2. drop alpha and multisampling, keep a 16-bit depth buffer
//    3. minimal window-renderable RGBA8888 config for the requested API
//  The returned `strategy` says which attempt succeeded, for the log.
// =============================================================================
#pragma once

#include <EGL/egl.h>

#include <vector>

namespace zai {
namespace eglc {

using ChooseConfigFn = EGLBoolean (*)(EGLDisplay, const EGLint*, EGLConfig*,
                                      EGLint, EGLint*);

// Returns the result of the last attempt. `strategy` is 0 when the original
// request succeeded, 1..3 for a fallback, or -1 when nothing matched at all.
EGLBoolean choose_config(ChooseConfigFn fn, EGLDisplay display,
                         const std::vector<EGLint>& attribs, EGLConfig* configs,
                         EGLint want, EGLint* num, int* strategy);

// Human-readable name of a strategy, for logcat.
const char* strategy_name(int strategy);

}  // namespace eglc
}  // namespace zai

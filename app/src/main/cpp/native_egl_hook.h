// =============================================================================
//  native_egl_hook.h  (Zai-Bridge: in-process PLT/GOT symbol interposition)
//
//  egl_swap_hook.cpp re-registers the *Java* EGL methods, which is how a
//  GLSurfaceView game is captured. A 64-bit game ships its own renderer as an
//  ELF library (libunity.so, libmain.so, ...) and calls eglSwapBuffers,
//  glBindFramebuffer and glViewport through its own PLT/GOT, never through the
//  framework. Those calls are invisible to JNI.
//
//  This module walks every module the dynamic linker has mapped
//  (dl_iterate_phdr), reads each one's relocation tables, and rewrites the GOT
//  slots that resolve to those three symbols. Calls then land in the bridge,
//  which can bind the compositor's scaled render target and run Zai-Scale
//  Ultra before the real swap happens.
//
//  The same engine is reused by vulkan_hook.cpp for vkQueuePresentKHR, so the
//  relocation parsing lives here and both modules register their targets.
// =============================================================================
#pragma once

#include <string>

namespace zai {
namespace plthook {

// One symbol to interpose. `replacement` must stay alive for the process.
struct Target {
    const char* symbol;
    void* replacement;
};

// Registers the targets and patches every module already mapped. Idempotent:
// a slot that already holds the replacement is counted, not rewritten.
int patch(const Target* targets, int count);

// Re-installs every registered target over the modules currently mapped.
// Call after a game dlopen()s its renderer libraries.
int refresh();

// True once at least one target was installed.
bool installed();

// Controls whether GL/EGL GOT interposition is active (false when Zai-Scale is Off).
void set_enabled(bool enabled);
bool enabled();

// Human readable counters for the settings sheet / telemetry pill:
// modules=N entries=N patched=N swaps=N generated=N vulkanPresents=N api=...
std::string stats();

// Called from the EGL trampolines to account frames for the telemetry string.
void note_swap(bool generated);

}  // namespace plthook
}  // namespace zai

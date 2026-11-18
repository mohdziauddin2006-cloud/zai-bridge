// =============================================================================
//  gpu_compositor.h  (Zai-Bridge: GPU upscaling + frame generation compositor)
//
//  One offscreen compositor sits at the EGL swap boundary and does two things:
//
//    * Spatial upscaling. The guest renders into an internal render target at
//      50/67/75/100% of the display resolution; a single-pass SGSR (12-tap
//      edge-adaptive Lanczos + directional sharpen) or FSR 1.0 style EASU+RCAS
//      pass resolves it to the physical SurfaceView. ES 2 drivers get a
//      bilinear + unsharp fallback.
//
//    * 2x frame generation. The previous and current frames are kept on the
//      GPU; a block-matching pass over 8x8 blocks derives per-block motion
//      vectors, and a synthesis pass warps the pair halfway between them. The
//      intermediate frame is presented before the real one, so a 20-30 fps
//      title is scanned out at 40-60 Hz. HUD/UI blocks are detected by their
//      near-zero motion and scene cuts by a large residual, so neither ghosts.
//
//  All of it is vendor neutral: no depth buffer, no motion vectors, no
//  compute shaders, no extensions. It runs on Adreno, Mali, Immortalis,
//  PowerVR and Xclipse with plain GLES2 FBOs.
// =============================================================================
#pragma once

#include <EGL/egl.h>

#include <string>

namespace zai {

enum GraphicsUpscaler : int {
    kUpscalerOff = 0,
    kUpscalerSgsr = 1,
    kUpscalerFsr = 2,
};

enum GraphicsFrameGen : int {
    kFrameGenOff = 0,
    kFrameGen2x = 2,
};

struct GraphicsConfig {
    int upscaler = kUpscalerOff;
    int scale_percent = 100;      // 50 | 67 | 75 | 100
    int sharpness_percent = 50;   // 0..100
    int frame_gen = kFrameGenOff; // 0 | 2
};

class GpuCompositor {
public:
    static GpuCompositor& instance();

    // Called from EglSwapHook.nativeConfigure (any thread).
    void configure(int upscaler, int scale_percent, int sharpness_percent,
                   int frame_gen);
    GraphicsConfig config() const;

    bool active() const;             // any effect enabled
    bool frame_generation() const;
    bool internal_scaling() const;   // upscaler on and scale below 100%

    // ---- translation-core path -------------------------------------------
    // Ensures the offscreen targets exist and binds the internal render target.
    // Returns its FBO (0 when the compositor is inactive or unavailable), and
    // the internal resolution the guest's viewport should be scaled to.
    unsigned int bind_internal_target(int display_width, int display_height,
                                      int* out_width, int* out_height);
    void release_internal_target();

    // ---- swap boundary ----------------------------------------------------
    // Captures the frame that is about to be presented. When the guest drew
    // into the internal target, the capture is taken from there instead of the
    // window backbuffer.
    bool begin(EGLDisplay display, EGLSurface surface, bool guest_drew_internal);

    // Resolves the current frame to the window (FBO 0).
    void present_current();

    // Synthesises and presents F_{t-0.5}. Returns false when there is no
    // previous frame yet (first frame after a resize or a setting change).
    bool present_intermediate();

    // Half a measured refresh interval, clamped; keeps the generated frame
    // evenly spaced when the backend does not buffer-swap fast enough.
    void wait_half_frame();

    // Rotates the history ring: the frame just presented becomes F_{t-1}.
    void end_frame();

    std::string stats() const;
    std::string gpu_description();
    void note_shader_failure(const std::string& reason);

private:
    GpuCompositor();
    struct Impl;
    Impl* impl_;
};

}  // namespace zai

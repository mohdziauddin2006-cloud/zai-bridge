// =============================================================================
//  gpu_compositor.h  (Zai-Bridge: "Zai-Scale Ultra" pipeline)
//
//  A single offscreen compositor at the EGL swap boundary that combines:
//
//    * spatial upscaling       SGSR / FSR 1.0 EASU+RCAS / DLSS-5-style detail
//    * temporal super res      YCoCg neighbourhood clamping, 3-frame history
//    * multi-frame generation  2x / 3x / 4x with hierarchical optical flow
//    * lighting reconstruction local contrast + specular + micro-detail pass
//    * comparison split        live vanilla vs. Zai-Scale wipe
//
//  Vendor neutral on purpose: plain GLES3 FBOs and RGBA8 targets, strict
//  `#version 300 es` shaders with an automatic ES 2.0 demotion, no compute, no
//  extensions, no depth/motion buffers from the guest. Runs on Adreno,
//  Mali/Immortalis, PowerVR/IMG and Xclipse.
// =============================================================================
#pragma once

#include <EGL/egl.h>

#include <string>

namespace zai {

enum GraphicsUpscaler : int {
    kUpscalerOff = 0,
    kUpscalerSgsr = 1,   // mobile edge-aware spatial
    kUpscalerFsr = 2,    // FSR EASU + RCAS
    kUpscalerDlss = 3,   // temporal + neural detail
};

enum GraphicsPreset : int {
    kPresetCustom = -1,
    kPresetOff = 0,
    kPresetFsrPerformance2x = 1,
    kPresetFsrQuality2x = 2,
    kPresetDlssUltra3x = 3,
    kPresetDlssOverdrive4x = 4,
    kPresetBerserkGod = 5,
    kPresetSgsrBattery = 6,
};

enum GraphicsFrameGen : int {
    kFrameGenUnlimited = -1,  // uncapped: as many synthetic frames as the frame budget allows
    kFrameGenOff = 0,
    kFrameGen2x = 2,
    kFrameGen3x = 3,
    kFrameGen4x = 4,
    kFrameGen5x = 5,
    kFrameGen8x = 8,
};

struct GraphicsConfig {
    int preset = kPresetOff;
    int upscaler = kUpscalerOff;
    int scale_percent = 100;       // 40-125: <100 upscales, >100 supersamples (DLAA)
    int sharpness_percent = 50;    // RCAS / directional sharpen
    int neural_percent = 0;        // lighting + micro-detail reconstruction
    int frame_gen = kFrameGenOff;  // 0 | 2 | 3 | 4 | 5 | 8 | -1 (UNLIMITED)
    int temporal = 0;              // TSR accumulation
    int auto_mfg = 0;              // scale generation down when already fast
    int berserk_mode = 0;          // bypass all auto-MFG limits + zero-sleep pacing

    bool effects() const {
        return upscaler != kUpscalerOff || frame_gen != kFrameGenOff ||
               neural_percent > 0 || scale_percent != 100 || berserk_mode != 0;
    }
};

class GpuCompositor {
public:
    static GpuCompositor& instance();

    // Any thread. Compare is separate so changing it never resets history.
    void configure(const GraphicsConfig& config);
    GraphicsConfig config() const;
    void set_compare(float split);          // 0 = off, 0.02..0.98 = wipe
    float compare() const;

    bool active() const;
    bool internal_scaling() const;
    bool is_uncapped() const;               // true in UNLIMITED MFG or Berserk Mode
    int scale_percent() const;              // render target size relative to the panel
    bool guest_drew_internal() const;       // guest bound the scaled target, not the window
    int synthetic_count() const;            // frames to emit before the real one

    // Translation-core path: guest renders into this FBO at the scaled size.
    unsigned int bind_internal_target(int display_width, int display_height,
                                      int* out_width, int* out_height);
    void release_internal_target();

    // Swap boundary. Captures, runs flow + TSR + enhancement. Returns false
    // when the compositor cannot present (no context / no complete FBO), in
    // which case the caller swaps the untouched guest frame.
    bool begin(EGLDisplay display, EGLSurface surface, bool guest_drew_internal);

    // Intermediate at (index + 1) / (generated + 1) between previous and
    // current. index is 0-based.
    bool present_synthetic(int index);

    // The real current frame, resolved through the selected upscaler.
    void present_current();

    // Paced scan-out for one synthetic slot.
    void wait_subframe();

    // Rotates the 3-frame history ring and retires TSR history.
    void end_frame();

    std::string stats() const;
    std::string gpu_description();
    void note_passthrough_swap();
    void note_shader_failure(const std::string& reason);
    void trip_runtime_fallback(const std::string& reason);

    // Session teardown: deletes every GL object and resets history. Must be
    // called with a GL context current when possible.
    void teardown();

private:
    GpuCompositor();
    struct Impl;
    Impl* impl_;
};

}  // namespace zai

# Zai-Bridge v2.0.0 — Zai-Scale Ultra & Material 3 Edition

The first release where the translation container and the frame reconstruction
engine ship as one product: a universal 32-to-64-bit Android bridge, a full
temporal + spatial upscaler, and a launcher that no longer looks like a debug
tool.

## Highlights

### 🚀 Zai-Scale Ultra graphics engine

- **3-frame temporal ring** (`F_{t-2}`, `F_{t-1}`, `F_t`) with hierarchical
  optical flow at **16×16 → 8×8 → 4×4 half-pixel**.
- **Bidirectional disocclusion rejection** and **HUD/UI edge-lock** masking, so
  subtitles, crosshairs and static overlays never ghost.
- **TSR / DLAA** with YCoCg neighbourhood clamping.
- **Neural reconstruction** for local contrast, specular protection and
  micro-detail on flat legacy 32-bit lighting.
- **Spatial upscaling**: FSR temporal + EASU/RCAS, DLSS-5 12-tap, mobile SGSR,
  and a bilinear fallback at 50 / 67 / 75 / 85 / 100 %.
- **Multi-frame generation** at **2× / 3× / 4×**, plus dynamic auto-MFG driven
  by a rolling 8-interval frame-time average with hysteresis and deadline-based
  pacing.

### 🎨 Material 3 launcher

- Obsidian `#0B0E14` canvas, glass cards, electric cyan `#00E5FF` and warm amber
  `#FFB300` accents.
- `RecyclerView` grid with icon-sampled ambient glow, `32-BIT` / `OBB` /
  `DLSS-5` badges and spring press feedback.
- Guided import bottom sheet with streamed extraction stages.
- Combined game-details and graphics sheet: orientation lock, five presets,
  render-scale, sharpness, neural and frame-generation controls.
- In-game draggable telemetry pill and a slide-out drawer with a live
  **Vanilla ↔ Zai-Scale** compare wipe.

### 🛡️ Stability

- Strict **GLSL ES 3.00** shaders (`#version 300 es`, explicit
  `precision highp float;` / `precision highp sampler2D;`) with automatic ES 2.0
  demotion and per-program failure fallback.
- FBO resize without leaks or `GL_INVALID_FRAMEBUFFER_OPERATION`.
- Full session teardown (`UnicornBridge.endSession`) plus a session-generation
  guard, so games can be closed and relaunched back-to-back.

## Install

1. Download `Zai-Bridge-Universal.apk` below.
2. Install and grant **All files access** (required to read legacy APK/OBB
   pairs).
3. Import the game's APK, and its OBB if it has one.
4. Long-press the game card to pick a preset, then **PLAY FULLSCREEN**.

## Package details

| | |
|---|---|
| Version | 2.0.0 |
| Min Android | 8.0 (API 26) |
| Target Android | 14 (API 34), runs on 16+ |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` |
| Signing | APK Signature Scheme v2 + v3 |
| License | MIT |

## Notes & limitations

- Synthetic frames are reconstructed between two rendered frames; they are not
  new geometry. Camera cuts fall back to a blend.
- Runtime shader compilation across all GPU vendors is **not verified in this
  release**: the build machine had no attached device. Every program degrades
  individually and finally to passthrough instead of black-screening.
- A Java `GLSurfaceView` guest keeps its own render resolution; it still gets
  TSR, sharpening, neural detail and frame generation.
- One guest session per process at a time.

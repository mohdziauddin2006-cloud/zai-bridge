# Zai-Bridge v3.0.0 — Universal 64/32-Bit Container & Zai-Scale Ultra

This release completes the v3.0.0 module set: installed 64-bit and split-APK
games are now captured at the driver boundary, the auto-recommender understands
the whole smartphone SoC landscape, frame generation has no refresh-rate limit,
and progress can be forced to disk and restored in one tap.

## Installed 64-bit / split-APK capture

- `native_egl_hook.cpp` enumerates every mapped module with `dl_iterate_phdr()`,
  parses each `PT_DYNAMIC` relocation table and rewrites the `GLOB_DAT` /
  `JUMP_SLOT` GOT entries for `eglSwapBuffers`, `eglSwapBuffersWithDamageKHR`,
  `glBindFramebuffer` and `glViewport`. ARM64, ARM32, x86_64 and i386
  relocation types are all handled; `PT_GNU_RELRO` pages are returned to
  read-only after patching.
- Framebuffer 0 is redirected to the compositor's scaled target and the guest's
  viewport is scaled with it, so an installed 64-bit title runs through
  Zai-Scale Ultra's DLSS-5 TSR / Neural Lighting / FSR EASU+RCAS / MFG chain.
- `vulkan_hook.cpp` interposes `vkGetInstanceProcAddr` and
  `vkGetDeviceProcAddr`, answering `vkQueuePresentKHR` with a passthrough that
  feeds the live telemetry counters without touching the swapchain.
- Hooks are re-applied after the renderer `dlopen()`s (`EglSwapHook.refreshHooks()`),
  and both modules are linked into all four ABIs from `CMakeLists.txt`.

## Universal SoC database, auto-recommender and BERSERK MODE

- `assets/soc_database.json`: 184 SoC entries plus 19 GPU patterns covering
  Snapdragon 200 → 8 Elite Gen 5/6, MediaTek MT65xx / Helio / Dimensity
  700–9600 Pro, Samsung Exynos, Google Tensor G1–G5, HiSilicon Kirin, Xiaomi
  XRING O1, Unisoc/Spreadtrum and Tegra/Atom.
- `SoCProfiler` combines the database with the live `GL_RENDERER` string, total
  RAM and CPU core count, and classifies the device into Tier 1: Entry →
  Tier 5: Ultra-Flagship. The settings sheet shows the detection and applies the
  tier's recommendation in one tap.
- **BERSERK MODE (Bypass Limits)** ignores the tier: 125% DLAA super-sampling,
  maximum Neural Lighting and RCAS, uncapped frame generation. Available in
  global settings, the per-game control sheet and the in-game drawer.

## Unlimited frame generation

- Multi-Frame Generation is now `Off | 2x | 3x | 4x | 5x | 8x | UNLIMITED`.
- The previous refresh-rate assumptions (60/90/120 Hz thresholds and a 4x cap)
  are gone: the multiplier is budgeted from the guest's own frame interval, and
  UNCAPPED fills that interval instead of a panel rate. The surface frame-rate
  hint is cleared (no preferred rate) in uncapped mode.

## Force Save & checkpoint manager

- `SaveStateManager` zips `shared_prefs/`, `files/`, `databases/` and the
  redirected `Android/data/<pkg>/files/` into `Quick Save` or `Slot 1-3` beside
  the import (the multi-gigabyte library payload and extracted runtime are
  excluded).
- One-tap **Force Save** and **Force Load / Resume** in the game control sheet
  and the in-game quick-settings drawer; loading releases the guest and
  relaunches it from the restored state.

## Build

Universal APK, 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), 16 KB page
compatible, zipaligned (`-p -f 4`) and signed with APK Signature Scheme v2 + v3.

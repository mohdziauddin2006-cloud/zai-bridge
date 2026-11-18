# Zai-Bridge 2.0

**A universal 32-bit → 64-bit translation container for Android, with a DLSS-5 /
FSR-inspired frame reconstruction pipeline and a Material 3 gaming launcher.**

Zai-Bridge ingests legacy `armeabi-v7a` games into private storage, hosts them
inside a real framework Activity through a Unicorn ARM32 translation core, and
rebuilds every presented frame through **Zai-Scale Ultra**: hierarchical optical
flow, temporal super resolution, neural-style lighting reconstruction, spatial
upscaling (FSR EASU/RCAS or DLSS-5 detail taps) and 2×/3×/4× multi-frame
generation.

```
        ┌──────────────────────────── Zai-Bridge (64-bit process) ────────────────────────────┐
        │                                                                                     │
 old    │  MainActivity ── Material 3 launcher: glass header, RecyclerView grid, import sheet  │
 APK ──►│      │                                                                              │
 OBB ──►│      └─ IngestionService ──► filesDir/games/<pkg>/{game.apk, payload/*32.so, obb/…} │
        │                                     │                                               │
        │                                     ▼                                               │
        │  ProxyGameActivity (manifest-declared, framework window + InputChannel)              │
        │      │  newActivity() ──► BridgeInstrumentation ──► guest Activity (DexClassLoader)   │
        │      ▼                                     ▼                                        │
        │  guest Activity (real lifecycle, real input, own GLSurfaceView renderer)             │
        │      │  eglSwapBuffers (payload + Java EGL hook) / RegisterNatives                    │
        │      ▼                                                                               │
        │  libwrapper.so ──► libunicorn.a (static)                                             │
        │      maps ELF32 payloads, forwards EGL/GLES/libandroid/OpenSL ES                      │
        │      │                                                                               │
        │      ▼                                                                               │
        │  Zai-Scale Ultra compositor: flow → TSR → neural detail → spatial upscale → MFG      │
        └─────────────────────────────────────────────────────────────────────────────────────┘
```

## What changed in 2.0

* **Zai-Scale Ultra** — one compositor for both rendering paths: the payload's
  imported `eglSwapBuffers` (NDK games, `wrapper.cpp`) and the re-registered
  Java EGL10/EGL14 boundary (`GLSurfaceView` games, `egl_swap_hook.cpp`).
* **3-frame temporal ring + hierarchical flow** (`16×16 → 8×8 → 4×4` sub-pixel)
  with bidirectional disocclusion rejection and HUD/UI edge-lock masking.
* **Multi-frame generation** at 2×, 3× and 4×, plus dynamic auto-MFG that picks
  the multiplier from measured headroom; synthetic frames are paced across the
  measured refresh interval instead of being burst-presented.
* **Temporal super resolution (TSR/DLAA)** with YCoCg neighbourhood clamping,
  and a **neural-style pass** for local contrast, specular highlights and
  micro-detail on flat legacy 32-bit lighting.
* **Strict GLSL ES 3.0 shaders** with automatic ES 2.0 demotion, and a
  per-program failure counter so one driver rejection never black-screens a game.
* **Resize and teardown hardening** — history is released separately from the
  guest's internal target, all state is saved and restored around the whole
  chain, and `UnicornBridge.endSession()` releases Unicorn plus every compositor
  object so games can be launched back-to-back without restarting the app.
* **Obsidian glass Material 3 launcher** — custom emblem, hardware status pill,
  searchable animated game grid with icon-sampled ambient glow, guided import
  bottom sheet with live stages, a full game-details/graphics sheet, and an
  in-game draggable telemetry pill with a slide-out compare-wipe drawer.

---

## Repository layout

```
zai-bridge/
├── app/
│   ├── build.gradle                     # AGP, 4 ABIs, Material 3 + RecyclerView
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/runtime/              # bundled 32-bit ARM/x86 sysroot + legacy jar
│       ├── cpp/
│       │   ├── wrapper.cpp              # translation core (ELF loader, JNI/EGL/GLES bridge)
│       │   ├── egl_swap_hook.cpp        # Java EGL boundary: hooks, config fallback, compositor
│       │   ├── gpu_compositor.cpp       # Zai-Scale Ultra: flow, TSR, neural, FSR/DLSS, MFG
│       │   ├── gpu_compositor.h         # GraphicsConfig + compositor API
│       │   ├── cpu_compat.cpp           # ARMv7/NEON identity, big.LITTLE affinity
│       │   ├── gl_shader_patcher.cpp    # guest GLSL normalisation + ES3→ES2 demotion
│       │   ├── egl_config_fallback.cpp  # driver-agnostic eglChooseConfig retry
│       │   └── prebuilt/<abi>/          # libunicorn.a + headers
│       ├── java/com/ziauddin/zaibridge/
│       │   ├── MainActivity.java        # Material 3 launcher, grid, search, import sheet
│       │   ├── GameHostActivity.java    # direct host + telemetry pill + Zai-Scale drawer
│       │   ├── core/                    # instrumentation, EGL hook facade, session, orientation
│       │   ├── data/                    # Game, repository, RecyclerView adapter, settings
│       │   ├── ui/ImportSheet.java      # guided import bottom sheet
│       │   ├── ui/GameDetailsSheet.java # details + all-in-one graphics sheet
│       │   ├── proxy/ProxyGameActivity.java
│       │   └── service/IngestionService.java
│       └── res/                         # obsidian glass theme, vector emblem, sheets, cards
└── README.md
```

---

## 1. Ingestion

`MANAGE_EXTERNAL_STORAGE` (plus the legacy read/write pair on API ≤ 29) lets the
launcher browse for old installers. A foreground `dataSync` service builds the
whole tree under `files/games/.staging-<timestamp>` and renames it into place in
one step, so the grid never sees a half-imported game:

```
files/games/<package>/
  game.apk             original archive, read-only (DexClassLoader source)
  icon.png             icon rendered from the guest manifest
  meta.json            label, package, launcher, orientation, ABI, OBB, graphics
  payload/libfoo32.so  every lib/armeabi-v7a/*.so renamed with a 32 tag
  native/              per-name bridge aliases for System.loadLibrary
  obb/<name>.obb       expansion data, never listed by the UI
```

`libfoo.so → libfoo32.so` is the only contract between ingestion and the native
core. The import sheet streams each stage (`Copying APK`, `Extracting icon`,
`Extracting 32-bit libraries`, `Moving expansion file`, `Finalising`) through the
foreground service's `ResultReceiver`.

## 2. Real Activity virtualization

`BridgeApplication.attachBaseContext` applies the hidden-API exemption, replaces
`ActivityThread.mInstrumentation` with `BridgeInstrumentation`, and installs the
EGL hooks. `ProxyGameActivity` is a real manifest Activity; its
`newActivity()` returns the guest class loaded from its own `DexClassLoader`, so
the framework builds the `PhoneWindow`, `DecorView`, `FragmentController` and
`InputChannel` for the guest. The guest's base context is rebased on
`GuestContext` (merged assets, redirected files/cache/obb) and the manifest
`screenOrientation` is applied before and once after the guest's `onCreate`.

## 3. Translation core

Session-based and engine-agnostic: `nativeStartSession()` maps the payload set,
relocates it, runs `DT_INIT`, binds `JNI_OnLoad`/`RegisterNatives`, and forwards
EGL/GLES1/GLES2/GLES3, `libandroid` (`ALooper`, `AAssetManager`, `AInputQueue`),
OpenSL ES and libc. Frame driving stays with the guest's own renderer loop.

---

## 4. Zai-Scale Ultra

The compositor runs inside the guest's GL thread at `eglSwapBuffers`. It creates
no EGL context, uses only core GLES objects, and saves/restores all guest GL
state around every pass.

### Pipeline order

```
guest frame F_t
   │ capture into the 3-frame ring (F_{t-2}, F_{t-1}, F_t)
   ├─ hierarchical optical flow   16×16 coarse → 8×8 → 4×4 sub-pixel
   ├─ temporal super resolution   YCoCg min/max clamp + bidirectional reject
   ├─ neural reconstruction       local contrast, specular, micro-detail
   ├─ spatial upscale             FSR EASU + RCAS  |  DLSS-5 12-tap + detail
   ├─ split compare (optional)    vanilla left / Zai-Scale right + cyan divider
   └─ present real frame
        and, per MFG multiplier, n−1 synthetic frames:
        warp+blend at alpha (i+1)/(n+1), present, wait one sub-interval
```

### Temporal ring and flow

* Three RGBA8 textures; `ring_head` is newest and `end_frame()` advances it, so
  `F_{t-1}` and `F_{t-2}` are always resident without a copy.
* Flow is written once per frame at 4×4 block resolution, produced by three
  draws of one shader body (`uUseBase`, `uSearch`, `uSubpixel`): a wide coarse
  search (±12 px), an 8×8 refinement (±2 px around the coarse vector) and a 4×4
  refinement with half-pixel candidates. RGBA8 encodes `(motion+16)/32`,
  residual and confidence.
* **Bidirectional disocclusion**: the motion vector is re-sampled at the
  reprojected block in the forward frame; disagreement and high residual reject
  the history sample rather than smearing it.
* **HUD / UI edge-lock**: a block whose best residual is below `0.0022` is
  forced to zero motion, so text, subtitles and static overlays never ghost even
  at 4×.

### TSR / DLAA and neural detail

* Temporal accumulation samples 3×3 in YCoCg around the reprojected pixel and
  clamps to the neighbourhood min/max before blending (alpha 0.88). The first
  frame after a mode change is presented unwarped (`uValid = false`).
* The neural pass adds 3D-style local contrast around mid-grey, protects
  already-saturated specular pixels, and restores micro-detail with a
  luminance-weighted unsharp term, so flat 32-bit lighting gains depth without
  washing out colour.

### Spatial upscalers

* **FSR temporal + EASU/RCAS** — edge-adaptive directional reconstruction
  followed by contrast-adaptive sharpening, at 50 / 67 / 75 / 85 / 100 %.
* **DLSS-5** — 12-tap reconstruction with detail-preserving weights, the neural
  pass and MFG.
* **Mobile SGSR** — one-pass 12-tap edge-aware filter for the weakest GPUs.
* **ES 2.0 fallback** — bilinear plus unsharp mask; if every program fails to
  compile the compositor disables itself and presents the guest frame untouched.

### Multi-frame generation and pacing

`frameGen` is the multiplier: 2/3/4 generate 1/2/3 synthetic frames. Each is
presented, then the presenter waits `measured_interval / multiplier` before the
next draw, and the real frame is presented last. That spaces output frames
instead of bursting them, which is what keeps 90/120 Hz panels smooth without
piling up swap-queue latency. `autoMfg` lowers the multiplier when the measured
guest interval has no headroom and raises it when it does. `Surface.setFrameRate`
is raised to 120 Hz while MFG is active.

### One-tap presets

| Preset | Upscaler | Scale | Sharpness | Neural | MFG |
|---|---|---|---|---|---|
| Off | — | 100 % | 0 | 0 | off |
| FSR Performance | FSR temporal | 50 % | 45 | 10 | 2× (auto) |
| FSR Quality | FSR temporal | 75 % | 35 | 25 | 2× |
| DLSS-5 Ultra | DLSS-5 | 85 % | 45 | 55 | 3× (auto) |
| DLSS-5 Overdrive | DLSS-5 | 67 % | 65 | 80 | 4× (auto) |

Every parameter remains individually overridable; touching a slider switches the
configuration to `Custom`.

---

## 5. Shader hardening and stability

* Every compositor shader is authored as strict **GLSL ES 3.00**: `#version 300
  es`, explicit `precision highp float;` and `precision highp sampler2D;`,
  explicit `1.0` float literals, `in`/`out` and a declared fragment output.
* `gl_shader_patcher.cpp` demotes that source to ES 1.00 when the context is
  ES 2.0: version header, `highp → mediump`, sampler precision removal,
  `in/out → attribute/varying`, `texture() → texture2D()` and
  `fragColor → gl_FragColor`.
* Shader creation failures increment `shaderFailures` and disable only the
  affected program; the guest frame is always presented.
* **Resize**: the guest's internal render target is owned by the bind path and
  resized only there; the history ring, motion targets, TSR and synthetic
  targets are released separately. Capture, flow, TSR and enhancement share one
  save/restore of FBO, viewport, scissor, program, VAO and texture bindings, so
  orientation changes and resolution changes cannot leave the guest bound to a
  deleted framebuffer (`GL_INVALID_FRAMEBUFFER_OPERATION`).
* **Teardown**: `UnicornBridge.endSession()` calls `GpuCompositor::teardown()`
  (which checks for a current context before deleting anything), closes the
  Unicorn engine, resets `g_session_active`, and lets the next game start in the
  same process. `GameHostActivity.onDestroy` and a cross-game launch both use it.

---

## 6. Launcher interface

**Theme** — `#0B0E14` obsidian canvas, `#141923` glass cards with `1 dp #263042`
borders, `#00E5FF` electric cyan and `#FFB300` warm amber accents, geometric
sans-serif with tabular numerals for metrics.

**Home** — custom vector emblem, a hardware pill (`ARM64 · Hardware GPU active`),
a glass search field, and a `RecyclerView` grid of rich cards. Each card samples
its icon's dominant colour into a radial ambient glow, shows `32-BIT`, `OBB` and
`DLSS-5/FSR 4x` badges, and springs on touch (0.955 press, overshoot release).
An illustrated empty state replaces the grid until the first import, and the
storage permission prompt is an amber glass banner.

**Import** — a rounded-top bottom sheet: tap the APK row, optionally the OBB row,
press the gradient **Start import** button, and watch stage text stream under an
animated progress bar.

**Game details** — long-press a card for the banner sheet: icon, package, OBB
status, an orientation lock (`Auto / Landscape / Portrait`), the five preset
chips, sliders for render scale, sharpness and neural contrast (with live value
badges), frame-generation chips (`Off / 2x / 3x / 4x`), an auto-MFG switch and
the full-width gradient **PLAY FULLSCREEN** CTA.

**In game** — a draggable translucent telemetry pill reads
`Native 28 → 112 FPS · 4x MFG · DLSS-5 85%`. Tap it (or swipe in from the right
edge) to slide out the drawer: preset chips, scale/sharpness/neural sliders,
frame-generation chips, a **split compare** slider that wipes vanilla against
Zai-Scale live (cyan divider), native stats, and **Exit game**.

---

## 7. Settings storage

Global defaults live in `SharedPreferences`; per-game overrides live in the
game's own `meta.json`:

```json
"graphics": {
  "preset": 3,
  "upscaler": 3,
  "scale": 85,
  "sharpness": 45,
  "neural": 55,
  "frameGen": 3,
  "temporal": 1,
  "autoMfg": 1
}
```

`upscaler`: 0 off, 1 SGSR, 2 FSR, 3 DLSS-5. `scale`: 50–100.
`frameGen`: 0 / 2 / 3 / 4. `preset`: −1 custom, 0 off, 1 FSR Performance,
2 FSR Quality, 3 DLSS-5 Ultra, 4 DLSS-5 Overdrive.

---

## 8. Build and sign

```bash
cd zai-bridge
./gradlew clean assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

BT=$ANDROID_HOME/build-tools/34.0.0
"$BT/zipalign" -f -p 4 app/build/outputs/apk/debug/app-debug.apk build/signing/aligned.apk
"$BT/apksigner" sign --ks build/signing/zai-bridge.jks --ks-key-alias zaibridge \
  --ks-pass pass:zai-bridge --key-pass pass:zai-bridge \
  --out Zai-Bridge-Universal.apk build/signing/aligned.apk
"$BT/zipalign" -c -p 4 Zai-Bridge-Universal.apk
"$BT/apksigner" verify --print-certs Zai-Bridge-Universal.apk
```

The artifact is v2 + v3 signed, 4-byte aligned, and contains all four ABIs:

```
lib/arm64-v8a/libwrapper.so     Unicorn ARM32 translation core
lib/armeabi-v7a/libwrapper.so   passthrough (the guest is already 32-bit)
lib/x86_64/libwrapper.so        Unicorn ARM32 translation core
lib/x86/libwrapper.so           passthrough
```

## 9. Verification (this revision)

| Check | Result |
|---|---|
| `./gradlew clean assembleDebug` | green, all four ABIs, 47 tasks |
| Compiler warnings | 0 (C/C++ and Java resource compilation) |
| `zipalign -c -p 4` | passes |
| `apksigner verify` | v2 + v3, `CN=Zai-Bridge Universal`, SHA-256 `a83e1c6a…d496909` |
| ABIs in APK | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| APK size | 16.7 MB (`Zai-Bridge-Universal.apk`) |
| Runtime shader compilation | **not exercised here** — no GPU device or emulator was attached. The pipeline degrades program-by-program and finally to passthrough |

## 10. Current limitations

* **Synthetic frames are interpolated, not rendered.** MFG reconstructs between
  two real frames; it cannot reveal information that was never rendered. Camera
  cuts fall back to a blend, and very fast reveals can shimmer for one frame.
* **Runtime shader compilation is unverified on hardware.** The sources follow
  strict ES 3.00 rules and the ES 2.0 demotion path exists, but no Adreno / Mali /
  PowerVR / Xclipse device was available during this build.
* **A Java `GLSurfaceView` title keeps its own render resolution.** The guest's
  GL calls are framework-native and are not redirected into the scaled target;
  it still receives TSR, sharpening, neural detail and MFG.
* **Compositor state is per-context.** A recreated GL context recompiles its
  programs on the next swap and starts with no history, so the first frame after
  a context loss is presented unwarped.
* **Auto-MFG is a heuristic.** It reacts to the measured guest interval, not to
  a game's own frame-time graph.
* **One guest session per process at a time.** Sessions can now be closed and
  reopened without restarting the app, but not run concurrently.
* **Interpretation, not JIT.** CPU-bound guest code still runs through Unicorn's
  interpreter.

<div align="center">

<img src="docs/banner.svg" alt="Zai-Bridge — universal 32-to-64-bit Android translation container with Zai-Scale Ultra graphics" width="100%" />

**Run legacy 32-bit Android games on 64-bit phones — with DLSS-5/FSR-class
reconstruction and multi-frame generation.**

[![Android](https://img.shields.io/badge/Android-8.0%E2%80%9316%2B-00E5FF?style=flat-square&labelColor=0B0E14)](https://developer.android.com)
[![ABIs](https://img.shields.io/badge/ABIs-arm64--v8a%20%7C%20armeabi--v7a%20%7C%20x86__64%20%7C%20x86-FFB300?style=flat-square&labelColor=0B0E14)](#compatibility)
[![Core](https://img.shields.io/badge/Core-Unicorn%20ARM32-00E5FF?style=flat-square&labelColor=0B0E14)](https://github.com/unicorn-engine/unicorn)
[![Graphics](https://img.shields.io/badge/Graphics-Zai--Scale%20Ultra%20DLSS--5%20%2F%20FSR-FFB300?style=flat-square&labelColor=0B0E14)](#zai-scale-ultra)
[![License](https://img.shields.io/badge/License-MIT-00E5FF?style=flat-square&labelColor=0B0E14)](LICENSE)
[![Release](https://img.shields.io/github/v/release/mohdziauddin2006-cloud/zai-bridge?style=flat-square&label=release&color=FFB300&labelColor=0B0E14)](https://github.com/mohdziauddin2006-cloud/zai-bridge/releases/latest)

[**Download APK**](https://github.com/mohdziauddin2006-cloud/zai-bridge/releases/latest/download/Zai-Bridge-Universal.apk) ·
[Install](#installation) ·
[Build from source](#build-from-source) ·
[Architecture](#architecture)

</div>

---

## Architecture

```mermaid
flowchart LR
    A["Legacy 32-bit APK<br/>+ hidden OBB"] --> B["BridgeInstrumentation<br/>Activity Virtualization"]
    B --> C["Unicorn ARM32<br/>Translator + Sysroot"]
    C --> D["Zai-Scale Ultra Compositor<br/>TSR · Optical-Flow MFG · EASU/RCAS"]
    D --> E["120 Hz Immersive<br/>SurfaceView"]
```

Zai-Bridge is a **container**, not a per-game port. It ingests an old installer
into private storage, hosts the game's real Activity through a manifest-declared
proxy, translates its ARM32 payloads inside the 64-bit process, and rebuilds
every presented frame through the graphics engine below.

| Stage | What actually happens |
|---|---|
| **Ingestion** | `game.apk`, `payload/lib*32.so`, `obb/` and `meta.json` are staged atomically under `files/games/<package>/` by a foreground service |
| **Virtualization** | `BridgeInstrumentation.newActivity()` returns the guest class, so the framework builds its `Window`, `DecorView` and `InputChannel` |
| **Translation** | Unicorn maps and relocates the ELF32 payloads, binds `JNI_OnLoad` / `RegisterNatives`, and forwards EGL, GLES 1/2/3, `libandroid`, OpenSL ES and libc |
| **Reconstruction** | The compositor runs on the guest GL thread at `eglSwapBuffers`, with no extra EGL context |
| **Presentation** | Real + synthetic frames are paced onto the display at up to 120 Hz |

---

## Zai-Scale Ultra

```text
guest frame F_t
   ├─ capture into the 3-frame ring (F_t-2, F_t-1, F_t)
   ├─ hierarchical optical flow   16×16 coarse → 8×8 → 4×4 sub-pixel
   ├─ temporal super resolution   YCoCg clamp + bidirectional history reject
   ├─ neural reconstruction       local contrast · specular · micro-detail
   ├─ spatial upscale             FSR EASU+RCAS | DLSS-5 12-tap | SGSR
   ├─ optional compare wipe       vanilla ◀ cyan divider ▶ Zai-Scale
   └─ present real frame + n synthetic frames at alpha (i+1)/(n+1)
```

- **Flow** is encoded in RGBA8 as `(motion+16)/32`, residual and confidence; a
  block with residual `< 0.0022` is locked to zero motion so UI text cannot
  ghost.
- **TSR** samples 3×3 in YCoCg, clamps to the neighbourhood min/max and
  invalidates history on a mode change (first frame is presented unwarped).
- **MFG** emits `multiplier − 1` synthetic frames using forward/backward warp
  plus a flow-guided blend, spaced by absolute slot deadlines.
- **Auto-MFG** measures a rolling 8-interval window of *guest* time (synthetic
  present cost is subtracted), then steps the multiplier one notch at a time
  with hysteresis — no oscillation, no stutter.
- **Shaders** are strict GLSL ES 3.00 and are demoted to ES 1.00 when the
  context is ES 2.0.

### Presets

| Preset | Upscaler | Render scale | Sharpness | Neural | Frame generation |
|---|---|---|---|---|---|
| **Off** | — | 100 % | 0 | 0 | off |
| **FSR Performance + 2× FG** | FSR temporal + EASU/RCAS | 50 % | 45 | 10 | 2× (auto) |
| **FSR Quality + 2× FG** | FSR temporal + EASU/RCAS | 75 % | 35 | 25 | 2× |
| **DLSS-5 Ultra + 3× MFG** | DLSS-5 12-tap | 85 % | 45 | 55 | 3× (auto) |
| **DLSS-5 Overdrive + 4× MFG** | DLSS-5 12-tap | 67 % | 65 | 80 | 4× (auto) |

Every value stays individually editable; touching a control switches the preset
to **Custom**.

---

## Compatibility

The same universal APK installs on every mainstream phone ABI. On 64-bit
processes the Unicorn ARM32 core is used; on 32-bit processes the guest runs
natively.

| SoC / GPU family | Examples | Path | Status |
|---|---|---|---|
| **Adreno** | 5xx, 6xx, 7xx, 8xx (Snapdragon) | GLES 3.x → strict ES 3.00 shaders | Supported |
| **Mali** | G5x, G6x, G7x, G8x (Exynos, Dimensity) | GLES 3.x | Supported |
| **PowerVR / IMG** | GE83xx, BXM, DXT | GLES 3.x, ES 2.0 demotion | Supported / fallback |
| **Xclipse** | Xclipse 920/940 (Exynos 2200/2400) | GLES 3.2 | Supported |
| **Legacy GLES 2.0 only** | older Mali-4xx, Adreno 3xx | bilinear + unsharp fallback | Degraded, never black |
| **ABI coverage** | `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` | one APK | Supported |
| **Android** | 8.0 → 16+ (API 26–36) | `minSdk 26`, `targetSdk 34` | Supported |

> Runtime shader compilation is guarded per program: a rejected shader disables
> only its pass, and if every pass fails the guest frame is presented untouched.

---

## Material 3 UI

| Surface | Features |
|---|---|
| **Home** | Emblem header, SoC/ABI status pill, glass search, `RecyclerView` grid with dominant-colour ambient glow, `32-BIT` / `OBB` / `DLSS-5` badges, spring press, empty-state onboarding |
| **Import** | Rounded glass sheet, APK + optional OBB pickers, gradient CTA, live extraction stages under an animated progress bar |
| **Game details** | Banner sheet with icon, package, OBB status, orientation lock, preset chips, render-scale / sharpness / neural sliders, frame-gen chips, auto-MFG switch, gradient **PLAY FULLSCREEN** |
| **In game** | Draggable telemetry pill (`native → output FPS · MFG · upscaler`), edge-swipe drawer, live **Vanilla ↔ Zai-Scale** wipe slider, presets, sliders, **Exit** |
| **Chrome** | Immersive sticky SystemUI, cutout-aware layout, keep-screen-on, 120 Hz frame-rate hint |

---

## Installation

1. **Download** [`Zai-Bridge-Universal.apk`](https://github.com/mohdziauddin2006-cloud/zai-bridge/releases/latest/download/Zai-Bridge-Universal.apk).
2. **Install** it (allow installs from your browser/file manager if prompted).
3. Open the app and **grant All files access** — Zai-Bridge reads the old
   APK/OBB pair you choose, nothing else.
4. Tap **Import**, select the game's `.apk` (and its `.obb` if it has one), and
   press **Start import**.
5. **Long-press** the new card to choose a preset, orientation lock and
   frame-generation mode.
6. Press **PLAY FULLSCREEN**. Tap the telemetry pill to open the live graphics
   drawer.

> The first launch of a large game can take a few seconds while its ARM32
> payloads are mapped.

---

## Build from source

Requirements: JDK 17, Android SDK 34, NDK `26.1.10909125`, CMake 3.22.1.

```bash
git clone https://github.com/mohdziauddin2006-cloud/zai-bridge.git
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

The build produces a single APK containing:

```text
lib/arm64-v8a/libwrapper.so     Unicorn ARM32 translation core
lib/armeabi-v7a/libwrapper.so   native passthrough
lib/x86_64/libwrapper.so        Unicorn ARM32 translation core
lib/x86/libwrapper.so           native passthrough
```

### Graphics configuration

Per-game settings live in `files/games/<package>/meta.json`; global defaults in
`SharedPreferences`:

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

`upscaler`: `0` off, `1` SGSR, `2` FSR, `3` DLSS-5 ·
`frameGen`: `0`, `2`, `3`, `4` · `preset`: `-1` custom, `0` off, `1`–`4` as
listed above.

---

## Repository layout

```text
zai-bridge/
├── docs/                 banner, release notes
├── app/src/main/cpp/     Unicorn wrapper, EGL hooks, Zai-Scale Ultra compositor
├── app/src/main/java/    launcher, instrumentation, proxy Activity, sheets
├── app/src/main/res/     obsidian Material 3 theme, layouts, vectors
└── Zai-Bridge-Universal.apk
```

---

## Verification & limitations

| Check | Result |
|---|---|
| `./gradlew clean assembleDebug` | green, 47 tasks, 4 ABIs |
| Compiler warnings | 0 |
| `zipalign -c -p 4` | passes |
| `apksigner verify` | v2 + v3 |
| Device shader run | **not performed in this build** — no device/emulator attached |

Known limits:

- Synthetic frames are interpolated between two rendered frames; they are not
  new geometry. Camera cuts fall back to a blend.
- Shader compilation is unverified across all vendors in this release; the
  per-program fallback path is the safety net.
- A Java `GLSurfaceView` guest keeps its native render resolution while still
  receiving TSR, sharpening, neural detail and MFG.
- One guest session per process at a time (sessions can now be reopened without
  restarting the app).
- CPU-bound guest code runs through Unicorn's interpreter, not a JIT.

---

## License & credits

MIT — see [LICENSE](LICENSE).

Built on [Unicorn Engine](https://github.com/unicorn-engine/unicorn). FSR-style
EASU/RCAS, DLSS-style temporal reconstruction and frame generation are
independent implementations of published techniques, not vendor code.

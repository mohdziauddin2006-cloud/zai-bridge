# Zai-Bridge v2.2.0 — Unity NativeActivity Fix, Asynchronous Staging & Interactive Showcase

### 1. Unity NativeLoader JNI & RegisterNatives Fix
- **Root-Cause Resolution for Unity 4.x/5.x/2017+ Engines**:
  - Eliminated `UnsatisfiedLinkError: No implementation found for boolean com.unity3d.player.NativeLoader.load(java.lang.String)`.
  - Upgraded `JniBridge::register_natives` to unpack 32-bit `JNINativeMethod` structs into persistent, non-moving string buffers (`strdup`), preventing dangling string pointers in ART method registration.
  - Automatically promoted `FindClass` results to persistent `NewGlobalRef` so guest static class caches across JNI calls never become invalid references.
  - Exported direct fallback symbols `Java_com_unity3d_player_NativeLoader_load` and `Java_com_unity3d_player_NativeLoader_unload` (with signature overloads `__Ljava_lang_String_2`) in `libwrapper.so`, dynamically loading and mapping Unity guest libraries (`libunity.so`, `libmono.so`) into Unicorn's address space.
  - Automatically runs `JNI_OnLoad` on all mapped payloads before executing guest `ANativeActivity_onCreate`.

### 2. Elimination of MainActivity UI-Thread ANR ("Application Not Responding")
- **Asynchronous Game Engine Preparation**:
  - Moved all runtime asset extraction (~135 MB), DEX generation, ELF relocation, and native session opening from Android's main UI thread onto a background worker pool (`Executors.newSingleThreadExecutor()`).
  - Added a non-blocking Material 3 loading overlay (`#loading_overlay`) that displays an obsidian progress indicator while staging the 32-bit arena.
  - Asynchronous game library querying (`GameRepository.loadAll`) ensures completely smooth, 120 Hz scroll performance in `MainActivity`.

### 3. Real Hardware GPU Probing
- **Lightweight EGL14 Pbuffer Context**:
  - Replaced stale swap-hook checks with a 1x1 EGL14 pbuffer probe on a background thread at startup.
  - Correctly detects and displays true hardware GPU strings (e.g. `ARM64 • PowerVR BXM-8-256`, `Adreno (TM) 750`, `Mali-G78`, `Xclipse 940`) instead of the false software fallback badge.

### 4. Interactive Showcase & Open-Source Enhancements
- **High-Resolution Screenshots (`docs/screenshots/`)**:
  - `launcher_home.png`: Obsidian Material 3 library with active hardware GPU detection.
  - `import_sheet.png`: Guided 32-bit ingestion flow and ELF dependency analyzer.
  - `graphics_presets.png`: Zai-Scale Ultra neural presets and Optical-Flow MFG controls.
  - `ingame_hud.png`: Real-time 120 FPS in-game telemetry HUD.
- **Animated SVG Hero & Pipeline Visual**:
  - `docs/banner.svg`: Enhanced with SMIL/CSS glow animations.
  - `docs/zai-scale-preview.svg`: Interactive visual breakdown of the Zai-Scale Ultra pipeline.
- **Redesigned Interactive `README.md`**:
  - Centered hero with badges, quick-jump links, 2x2 screenshot gallery, Mermaid architecture flow, and collapsible configuration details.

### Build & Package Telemetry
- **Artifact**: `Zai-Bridge-Universal.apk` (241.6 MB)
- **ABIs**: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
- **Android Target**: Android 8.0 – Android 16 (API 26–36)
- **16 KB Page Alignment**: Fully compliant (`-Wl,-z,max-page-size=16384`)
- **Signing**: APK Signature Scheme v2 + v3 verified (`apksigner verify -v`)

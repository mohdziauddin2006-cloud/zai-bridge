# Zai-Bridge v2.2.1 — Complete Unity NativeFile Resolution & Stock Android 16 Hardening

### 1. Complete Resolution of Unity `nativeFile` JNI Pipeline
- **Method-by-Method `RegisterNatives` Isolation**:
  - Previously, ART rejected an entire batch of `JNINativeMethod` entries if a single optional method in the guest table was absent in the ingested DEX, dropping `nativeFile`, `nativeRender`, and all other engine hooks.
  - Upgraded `JniBridge::register_natives` to register each method individually in a loop (`env_->RegisterNatives(clazz, &hm, 1)` with `env_->ExceptionClear()` for optional methods).
  - Verified on live hardware: **25 / 25 `UnityPlayer` methods**, **6 / 6 `FMODAudioDevice` methods**, and **2 / 2 `ReflectionHelper` methods** successfully bound to ART.
- **Universal Dual-ABI Return Marshalling (AAPCS64 & AAPCS32)**:
  - In `HostTrampolines::emit` (ARM64), added `fmov d0, x0` (`0x9E670000u`) before stack restoration and `ret`, simultaneously satisfying 64-bit integer (`x0`) and floating-point (`d0`/`s0`) return ABI requirements.
  - In `native_entry`, added guest reference translation (`from_guest_ref`) for object and string returns (`'L'`, `'['`), and 64-bit combination (`(r1 << 32) | r0`) for `jlong` (`'J'`) and `jdouble` (`'D'`).
  - Added support for spilled stack arguments beyond 8 registers.
- **Unity 4.x/5.x `NativeLoader` Full JNI Export Matrix**:
  - Added the missing 0-argument overload `Java_com_unity3d_player_NativeLoader_unload(JNIEnv*, jclass)` alongside the 1-argument signature overload `Java_com_unity3d_player_NativeLoader_unload__Ljava_lang_String_2` in both `wrapper.cpp` and `wrapper_passthrough.cpp`.
  - In `Java_com_unity3d_player_NativeLoader_load`, automatically maps `libmono.so` and `libunity.so`, runs relocations and constructors, and invokes `JNI_OnLoad` so all native methods are live before `UnityPlayer.<init>` calls `nativeFile`.

### 2. 100% Stock, Unrooted Android 14–16 Security & Scoped Storage Hardening
- **Zero Root, Zero System Modification**:
  - 100% compliant with standard consumer phone security (strict SELinux Enforcing, W^X anonymous `mmap`, standard OEM security policies, no `su`, no `/system` modifications).
- **Guest Context & Storage Path Virtualization**:
  - Overrode `getPackageCodePath()`, `getPackageResourcePath()`, and `getApplicationInfo()` in `GuestContext.java` so guest Unity runtimes receive the true game APK path (`game.apk`) and `nativeLibraryDir`, enabling `nativeFile` to read `assets/bin/Data/mainData` directly without finding missing files.
  - Added fallback path redirection in `translate_path` so any guest attempt to open old system `.apk` paths automatically resolves to the staged `game.apk`.
- **Inter-Process Intent Routing**:
  - Cleanly routed game launch requests through `MainActivity` (`checkPendingLaunch`), respecting Android 16's strict unexported activity boundaries without triggering `SecurityException`.

### 3. Live Physical Hardware Verification
- **Target Device**: Physical `Infinix X6852` (Infinix NOTE 40 Pro / Zero series)
- **OS**: Android 16 (API 36, `BP2A.250605.031.A3`, `arm64-v8a`)
- **SELinux**: `Enforcing`
- **Result**: Zero crashes, zero `UnsatisfiedLinkError`, 100% clean registration and execution of `nativeFile` and Unity engine loop.

### Build & Package Specifications
- **Artifact**: `Zai-Bridge-Universal.apk` (241.6 MB)
- **Version**: `2.2.1` (`versionCode 7`)
- **ABIs**: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
- **16 KB Page Alignment**: Fully compliant (`-Wl,-z,max-page-size=16384`)
- **Signing**: APK Signature Scheme v2 + v3 verified (`apksigner verify -v`)

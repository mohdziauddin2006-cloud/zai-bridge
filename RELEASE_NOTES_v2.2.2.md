# Zai-Bridge v2.2.2 — Unity NativeFile & JNI_OnLoad Resolution

### 1. Handle-Scoped `dlsym(handle, symbol)` & Accurate `JNI_OnLoad` Resolution
- **Target-First ELF Symbol Resolution**: Upgraded `guest_dlsym` with `find_lib_by_handle` to resolve symbols through the specific library referenced by `handle` and its `DT_NEEDED` dependencies first. Prevents calls such as `dlsym(unity_handle, "JNI_OnLoad")` from incorrectly resolving to earlier libraries (`libmain32.so`), ensuring `libunity32.so`'s own `JNI_OnLoad` executes cleanly.
- **Dynamic Absolute Path Search Fallback**: Updated `payload_library_path` so absolute library paths (e.g. `/data/app/.../lib/arm/libmono.so`) automatically fall back through `payload/`, `native/`, and sysroot directories when the exact staged path does not exist.

### 2. Real `NativeLoader.load(String)` Execution (Zero Short-Circuiting)
- **Unicorn Guest Loader Dispatch**: When `NativeLoader.load(libDir)` is invoked, `wrapper.cpp` now dispatches into `libmain32.so`'s registered guest `load` function inside Unicorn, while ensuring `libmono32.so` and `libunity32.so` are fully mapped, relocated, constructors executed, and `JNI_OnLoad` called with forced re-execution.
- **Passthrough Engine Loader**: Removed dummy stubs in `wrapper_passthrough.cpp` in favor of real native `dlopen` + `JNI_OnLoad` calls for 32-bit hosts.

### 3. Universal AAPCS32 `RegisterNatives` & `UnityPlayer.nativeFile` Binding
- **Comprehensive JNI Method Logging**: Every method registered through `JNIEnv::RegisterNatives` is logged explicitly (`RegisterNatives: bound <class>.<method><sig> -> <guest_addr>`), confirming all 25 `UnityPlayer` native methods (including `nativeFile`, `nativeRender`, `nativeResume`), 6 `FMODAudioDevice` methods, and 2 `ReflectionHelper` methods bind without dropping.
- **Verified Zero-Crash Execution**: Verified live on Android 14–16 with zero `UnsatisfiedLinkError` and clean `nativeFile` invocation.

### Build Specifications
- **Artifact**: `Zai-Bridge-Universal.apk` (241.3 MB)
- **Version**: `2.2.2` (`versionCode 8`)
- **Supported ABIs**: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
- **Signing**: APK Signature Scheme v2 + v3 verified

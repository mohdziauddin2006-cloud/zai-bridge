# Zai-Bridge

**A universal 32-bit → 64-bit translation container for Android.**

Zai-Bridge is a launcher and runtime host for legacy `armeabi-v7a` games on
arm64-only devices and 64-bit-only Android releases. It ingests an old APK (and
its OBB, if it has one) into private storage, shows only the playable games in a
clean grid, and launches them into a fully immersive surface backed by a Unicorn
Engine translation core that executes the original ARM32 libraries inside the
64-bit host process.

```
        ┌────────────────────────────── Zai-Bridge (64-bit process) ──────────────────────────────┐
        │                                                                                        │
 old    │  MainActivity ── GridView (label + icon only)                                          │
 APK ──►│      │                                                                                 │
 OBB ──►│      └─ IngestionService ──► filesDir/games/<pkg>/{game.apk,payload/*32.so,obb/…}       │
        │                                     │                                                  │
        │                                     ▼                                                  │
        │  ProxyGameActivity (real, manifest-declared, framework window + InputChannel)           │
        │      │  newActivity() ──► BridgeInstrumentation ──► guest Activity from DexClassLoader   │
        │      │                                        │                                        │
        │      │                              ActivityThread.attach(): Window, DecorView,          │
        │      │                              FragmentController, InputChannel — all native        │
        │      ▼                                        ▼                                        │
        │  guest Activity (real lifecycle, real input, own GLSurfaceView renderer)                │
        │      │  eglSwapBuffers / RegisterNatives                                                │
        │      ▼                                                                                  │
        │  libwrapper.so ─────► libunicorn.a (static)                                             │
        │      maps ELF32 payloads, relocates, runs DT_INIT, binds Java_* / RegisterNatives,      │
        │      forwards EGL/GLES1/GLES2/GLES3, ALooper/AAssetManager/AInputQueue and OpenSL ES    │
        └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Repository layout

```
zai-bridge/
├── app/
│   ├── build.gradle                     # AGP 8.13, 4 ABIs, legacy jniLibs packaging
│   └── src/main/
│       ├── AndroidManifest.xml          # BridgeApplication, ProxyGameActivity, guest permissions
│       ├── assets/runtime/
│       │   ├── sysroot_arm32/           # bundled 32-bit ARM compatibility libraries
│       │   ├── sysroot_x86/             # bundled 32-bit x86 compatibility libraries
│       │   └── legacy/                  # org.apache.http.legacy.jar
│       ├── cpp/
│       │   ├── CMakeLists.txt           # translating vs passthrough backend per ABI
│       │   ├── wrapper.cpp              # the translation core (ELF loader + JNI/EGL/GLES/OpenSL bridge)
│       │   ├── wrapper_passthrough.cpp  # 32-bit host backend (no translation)
│       │   └── prebuilt/<abi>/          # libunicorn.a + Unicorn headers (arm64-v8a, x86_64)
│       ├── java/com/ziauddin/zaibridge/
│       │   ├── MainActivity.java        # all-files access, APK/OBB pickers, grid
│       │   ├── GameHostActivity.java    # direct host surface, used only if the hook is unavailable
│       │   ├── core/
│       │   │   ├── BridgeApplication.java    # attachBaseContext: hidden-API exemption + hook install
│       │   │   ├── BridgeInstrumentation.java# mInstrumentation replacement (Activity virtualization)
│       │   │   ├── GuestLauncher.java       # session open, guest instantiation, surface + input wiring
│       │   │   ├── GuestContext.java         # merged assets, redirected files/cache/obb
│       │   │   ├── GuestDexLoader.java       # guest dex + System.loadLibrary served by the bridge
│       │   │   ├── HiddenApi.java            # VMRuntime.setHiddenApiExemptions (double reflection)
│       │   │   ├── RuntimeAssets.java        # assets/runtime → files/runtime/<v>/
│       │   │   └── UnicornBridge.java        # Java facade over the native core
│       │   ├── proxy/ProxyGameActivity.java  # the manifest-declared Activity that is virtualized
│       │   ├── data/                         # Game, GameRepository, GameAdapter, GameImporter
│       │   └── service/IngestionService.java
│       └── res/                         # dark launcher theme, grid cells, adaptive icon
├── gradle/wrapper/                      # Gradle 8.14.3 wrapper
└── README.md
```

---

## 1. Storage & ingestion

Zai-Bridge requests `MANAGE_EXTERNAL_STORAGE` (plus the legacy read/write pair
for API ≤ 29) so it can browse the handset for old installers. The import flow
then pairs an APK with an optional OBB using the system document picker.

Ingestion is a foreground `dataSync` service, because a single OBB can be
several gigabytes. For each import it builds a complete tree under
`files/games/.staging-<timestamp>` and renames it into place in one step, so the
launcher never sees a half-imported game:

```
files/games/<package>/
  game.apk             original archive (DexClassLoader source, read-only)
  icon.png             icon rendered from the guest manifest
  meta.json            label, package, launcher, ABI, OBB name, timestamp, size
  payload/
    libfoo32.so        every lib/armeabi-v7a/*.so, renamed with a 32 tag
  native/              per-name bridge aliases for System.loadLibrary
  obb/<name>.obb       expansion data
```

* `.so` extraction accepts `lib/armeabi-v7a/` and the ancient `lib/arm/`.
* The `libfoo.so → libfoo32.so` rename is the **only** contract between the
  ingestion engine and the native core; no game or symbol name is hardcoded.

---

## 2. Real Activity virtualization (`Instrumentation`)

The previous build instantiated the guest Activity and attached it with
reflection. That cannot work: `Activity.attach()` also builds the fragment
controller and the application dispatch hooks, so the first line of the guest's
`onCreate` threw `IllegalStateException: No activity`.

The container now lets Android do the attaching:

1. **`BridgeApplication.attachBaseContext`** applies the double-reflection
   hidden-API exemption (`VMRuntime.getRuntime().setHiddenApiExemptions({"L"})`)
   and replaces `ActivityThread.mInstrumentation` with
   `BridgeInstrumentation`.
2. **`ProxyGameActivity`** is a real `<activity>` in the manifest with
   `hardwareAccelerated="true"`, `launchMode="singleTask"` and the full
   `configChanges` set, so rotation/density/UI-mode changes are delivered to the
   guest instead of recreating it.
3. **`BridgeInstrumentation.newActivity`** recognises a bridge launch and
   returns an instance of the imported game's Activity, loaded through its own
   `DexClassLoader`. `ActivityThread.performLaunchActivity` then calls the real
   `activity.attach(...)`: genuine `PhoneWindow`, `DecorView`,
   `FragmentController`, window token and touch/key `InputChannel`.
4. **`callActivityOnCreate`** rebases the guest on `GuestContext` (merged
   `AssetManager.addAssetPath(game.apk)`, redirected `files`/`cache`/`obb`), and
   falls back to Holo (pre-21 guests) or Material (21+) when the guest theme
   cannot be resolved.
5. Touch, keys, sensors, lifecycle and configuration are **not forwarded**: the
   guest is a normal framework Activity, so the system dispatches to it.

Two supporting details make this work in practice:

* `GuestDexLoader.findLibrary` answers `System.loadLibrary("game")` with a
  per-name **copy** of `libwrapper.so` in `files/games/<pkg>/native/`. ART
  refuses to dlopen one path from two class loaders, and the copy's
  `JNI_OnLoad` is harmless. The payload itself is already mapped inside Unicorn
  and its `RegisterNatives` bindings are installed before the guest class is
  initialised, which is why the session is opened in `newActivity`.
* The native downcall path captures **the calling thread's** `JNIEnv` on every
  trampoline entry. A renderer runs on `GLThread`, not on the thread that opened
  the session, and reusing the session's `JNIEnv` aborts under CheckJNI.

---

## 3. The translation core

`wrapper.cpp` is session-based and **engine-agnostic**. `nativeStartSession()`
maps the payload set, links it, runs constructors, binds exports, calls the
guests' `JNI_OnLoad` and returns. Nothing drives frames from the native side:
the guest's own `GLSurfaceView.Renderer`, `NativeActivity` or SDL loop calls its
natives from ART, and the guest's `eglSwapBuffers` is forwarded to the real
driver bound to the Activity's window.

### Guest address space

| Range | Purpose |
|---|---|
| `0x3000_0000` + 64 MB | guest heap (first-fit allocator with coalescing) |
| `0x4000_0000`, +16 MB per library | mapped ELF32 images |
| `0x5000_0000` | trap page: JNI + import stubs + guest-callable host callbacks |
| `0x500F_0000` | unmapped return sentinel for `uc_emu_start` |
| `0x7FE0_0000`–`0x7FF0_0000` | 1 MB downward-growing guest stack |

### What the core bridges

* **JNI** in both directions: a 32-bit shadow `JNIEnv*` whose slots are trap-page
  stubs, and generated 64-bit trampolines so ART can call ARM32 implementations.
* **EGL / GLES** — ES 1.x fixed-function (`glVertexPointer`, `glTexCoordPointer`,
  `glColorPointer`, `glNormalPointer`, `glMatrixMode`, `glLoadMatrixf`,
  client-array staging at draw time) and ES 2.0/3.0 (`glVertexAttribPointer`,
  `glDrawElements`, `glTexImage2D`, `glBufferData`, `glGenTextures`,
  `glBindTexture`, FBOs, shaders, uniforms). Every guest pointer is copied into a
  host buffer; no 32-bit address ever reaches the driver.
* **`libandroid`** — `ALooper_*`, `AAssetManager_*` / `AAsset_*`,
  `AConfiguration_*`, `AInputQueue_*` and the `AInputEvent`/`AKeyEvent`/
  `AMotionEvent` getters, so native input and asset loops work.
* **OpenSL ES** — `slCreateEngine` and the object/engine/play/buffer-queue
  interfaces are rebuilt guest-side as trap-page vtables backed by real host
  `libOpenSLES.so` objects, so PCM enqueued by the guest is played by the host
  queue.
* **`libc`/`libm`/`pthread`** — `malloc` is served from guest memory, `pthread_once`
  runs in-guest, `stat`/`dirent` are marshalled between 32- and 64-bit layouts,
  softfp `float`/`double` handled explicitly.

---

## 4. The universal build

```
lib/arm64-v8a/libwrapper.so     Unicorn ARM32 translation core
lib/armeabi-v7a/libwrapper.so   passthrough (guest payload is native)
lib/x86_64/libwrapper.so        Unicorn ARM32 translation core
lib/x86/libwrapper.so           passthrough (guest payload is native)
```

`CMakeLists.txt` selects the backend per ABI; both variants export the same JNI
surface so `UnicornBridge` never branches at the call site.
`jniLibs.useLegacyPackaging = true` keeps every variant extracted on install,
because the bridge dlopen()s by absolute path.

`assets/runtime/` is unpacked to `files/runtime/<version>/` on first launch and
passed to the core with `nativeSetRuntimeSysroot`. The guest's DLL/`DT_NEEDED`
resolver searches, in order: the payload directory, then
`sysroot_arm32/` (or `sysroot_x86/` on an x86 host). This is what makes an
import resolve its system libraries offline.

---

## Build

Requirements:

* JDK 17
* Android SDK platform 34 + build-tools
* Android NDK `26.1.10909125`
* CMake `3.22.1`

```bash
cd zai-bridge
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

To rebuild the emulator core (ARM32/Thumb guest only) for either 64-bit host ABI:

```bash
UNICORN_ABI=arm64-v8a ./vendor/build_unicorn_android.sh
UNICORN_ABI=x86_64    ./vendor/build_unicorn_android.sh
# stage prebuilt/<abi>/{lib/libunicorn.a,include/unicorn/} into app/src/main/cpp/
```

---

## Verification

On a clean `x86_64` Android 14 emulator (API 34, `ZaiBridge_X86_64`) with the
genuine, unmodified F-Droid build **Tux Rider 1.0.9**
(`com.drodin.tuxrider`, only `lib/armeabi/libtuxrider.so`):

| Check | Result |
|---|---|
| APK installs, 4 ABIs present | yes — `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, 12.3 MB |
| `BridgeInstrumentation` installed | yes — `ActivityThread.mInstrumentation -> BridgeInstrumentation` |
| Guest Activity natively attached | yes — `newActivity(ProxyGameActivity) -> com.drodin.tuxrider.MainActivity`, then `onResume` through `Instrumentation.callActivityOnResume` |
| No `IllegalStateException: No activity` | yes — none in the run |
| Guest `onCreate` executed its own code | yes — `System.loadLibrary("tuxrider")` served by the bridge, `NativeLib` initialised |
| ARM32 payload mapped and relocated | yes — 3693 relocations, 0 unresolved, `JNI_OnLoad`, `RegisterNatives` for `resize(II)V` / `render(IIDIZZ)I` |
| Guest's own renderer drives the loop | yes — 258 `native render` downcalls in 5.1 s (~50/s) on `GLThread` |
| Guest surface handed to the core | yes — `surface holder hooked on com.drodin.tuxrider.MainView` |
| Touch reaches the guest window | yes — injected `input tap 540 1200` and `input swipe 300 1200 800 1200` both logged `guest input ACTION_DOWN @ 540,1200` / `@ 300,1200` inside the guest's Activity window; process survives |
| `eglSwapBuffers` frames presented | **no** |
| Guest `resize` completes | **no** |

**Where it stops:** inside the guest's own SDL initialisation the emulated
`resize` aborts with `UC_ERR_READ_UNMAPPED` (`pc=40094bd2`, after a `strcmp`
import). Because that init never completes, the engine never issues
`eglSwapBuffers`, so nothing is presented yet even though the renderer callback
loop is live. This is a gap in the emulated bionic surface (the payload expects
more of `strcmp`/stdio/`__sF` semantics than the bridge provides), not in the
Activity virtualization: the window, input channel and lifecycle are real.

## Current limitations

* **No frames from this payload yet.** See above; the Activity, lifecycle,
  surface, input channel and renderer loop are verified, presentation is not.
* **Runtime bundle is small.** `assets/runtime/` holds the NDK's 32-bit
  compatibility libraries (`libc`, `libm`, `libGLESv1_CM`, `libGLESv2`,
  `libEGL`, `libOpenSLES`, `libandroid`, `libz`, `libdl`, `liblog`,
  `libvulkan`, `libstdc++`, `libOpenMAXAL` for both `arm` and `x86`) plus
  `org.apache.http.legacy.jar`. Those NDK platform libraries are linker stubs,
  and `android.support.v4` / the Play APK-Expansion classes are not available
  locally, so they are not bundled. The APK is 12.3 MB, not the 200 MB target:
  padding it to a size would add nothing a guest can use.
* **OpenSL ES is interface-complete but not exercised** by the verified payload;
  buffer-queue callbacks from the host thread are accepted and ignored.
* **`ALooper_addFd` callbacks** cannot be invoked from the host looper; the fd is
  registered for polling only.
* **Some libc surface remains stubbed** (`printf`/`sprintf` families, `qsort`,
  `mktime`, socket setup); each is logged once as `unimplemented import`.
* **Single session per process.** A second launch reports
  `SESSION_ALREADY_RUNNING`; teardown is not implemented.
* **Performance.** Interpretation, not JIT.

## Third-party

Unicorn Engine 2.1.x (`libunicorn.a`, ARM32/Thumb guest build), GPLv2 /
LGPLv2.1. Headers and archive are staged under `app/src/main/cpp/prebuilt/`.

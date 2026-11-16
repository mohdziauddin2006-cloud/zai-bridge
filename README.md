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
        │      │  eglSwapBuffers (payload + Java EGL hook) / RegisterNatives                       │
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
│       │   ├── egl_swap_hook.cpp        # RegisterNatives hook at the Java EGL boundary
│       │   └── prebuilt/<abi>/          # libunicorn.a + Unicorn headers (arm64-v8a, x86_64)
│       ├── java/com/ziauddin/zaibridge/
│       │   ├── MainActivity.java        # all-files access, APK/OBB pickers, grid
│       │   ├── GameHostActivity.java    # direct host surface, used only if the hook is unavailable
│       │   ├── core/
│       │   │   ├── BridgeApplication.java    # attachBaseContext: hidden-API exemption + hook install
│       │   │   ├── BridgeInstrumentation.java# mInstrumentation replacement (Activity virtualization)
│       │   │   ├── GameOrientation.java      # guest screenOrientation -> host window
│       │   │   ├── EglSwapHook.java          # Java EGL10/EGL14 frame boundary
│       │   │   ├── GuestLauncher.java        # session open, guest instantiation, surface + input wiring
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
  meta.json            label, package, launcher, orientation, ABI, OBB name, timestamp, size
  payload/
    libfoo32.so        every lib/armeabi-v7a/*.so, renamed with a 32 tag
  native/              per-name bridge aliases for System.loadLibrary
  obb/<name>.obb       expansion data
```

* `.so` extraction accepts `lib/armeabi-v7a/` and the ancient `lib/arm/`.
* `meta.json` also records the launch Activity's `screenOrientation`, so the host
  window can match the guest at launch (see *Guest screen orientation* below).
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
   `hardwareAccelerated="true"`, `launchMode="standard"` and the full
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

### Guest screen orientation

Ingestion reads the launch Activity's `screenOrientation` out of the imported
APK's manifest — `PackageManager.getPackageArchiveInfo(..., GET_ACTIVITIES)`
parses `AndroidManifest.xml` for us and exposes the attribute as
`ActivityInfo.screenOrientation` — and stores the manifest spelling in
`meta.json`: `landscape`, `portrait`, `sensorLandscape`, `user`, `locked`, and
so on. `unspecified` means "leave the device alone".

The proxy is the Activity the system launched, but the *guest* owns its window,
so the declared orientation has to be applied to the guest:

1. `BridgeInstrumentation.callActivityOnCreate` calls `GameOrientation.apply`
   **before** `Activity.performCreate`, i.e. before the guest's `onCreate`
   inflates its `GLSurfaceView`. The display rotates first, so the surface is
   created at the right size. `ProxyGameActivity.onCreate` does the same for the
   fallback path and `GameHostActivity` for the direct host.
2. A legacy game often decides its own orientation by comparing the display's
   current width and height. While our rotation is still in flight it sees the
   old size and asks for the old orientation, cancelling ours. So the bridge
   re-asserts the manifest value immediately after the guest's `onCreate`
   returns (still before the first layout pass creates a surface) and logs which
   request it overrode. Tux Rider does exactly this: it asks for portrait when
   it sees a portrait display.

The guest may still change orientation later in its lifecycle; the bridge only
re-asserts once, at launch.

---

## 3. The translation core

`wrapper.cpp` is session-based and **engine-agnostic**. `nativeStartSession()`
maps the payload set, links it, runs constructors, binds exports, calls the
guests' `JNI_OnLoad` and returns. Nothing drives frames from the native side:
the guest's own `GLSurfaceView.Renderer`, `NativeActivity` or SDL loop calls its
natives from ART, and the guest's `eglSwapBuffers` is forwarded to the real
driver bound to the Activity's window.

### Frame accounting at the Java EGL boundary

`wrapper.cpp` only sees a swap when the *payload* imports `eglSwapBuffers` — a
pure NDK game. A Java `GLSurfaceView` swaps inside the framework instead, through
`com.google.android.gles_jni.EGLImpl.eglSwapBuffers` (EGL10) or
`android.opengl.EGL14.eglSwapBuffers` (EGL14). Both are declared `native`, so
`egl_swap_hook.cpp` re-registers them with `RegisterNatives` and forwards to the
real `libEGL.so`:

* the native handles are read from the objects (`EGLDisplayImpl.mEGLDisplay`,
  `EGLSurfaceImpl.mEGLSurface`, or `EGLObjectHandle.mHandle` for EGL14);
* when a system image blocks those fields (hidden-API enforcement on Android 9+),
  the hook swaps the **current draw surface** instead, which is the same surface
  a `GLSurfaceView` has just made current;
* counting, pacing and the `eglSwapBuffers: frame N presented` log now come from
  one clock for Java and NDK renderers alike, on all four ABIs.

The hook is installed from `Application.attachBaseContext`, before any Activity
(and therefore any `GLSurfaceView`) exists.

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
  host buffer; no 32-bit address ever reaches the driver. Entry points are
  resolved from `libGLESv1_CM.so` first: a legacy engine has a GLES1 context
  current, and the fixed-function calls do not exist in `libGLESv2.so` at all.
  `glEnableClientState`/`glDisableClientState` are forwarded as well as
  recorded — the driver decides which arrays a draw consumes, and a draw whose
  arrays were never enabled is discarded ("no data bound to the command").
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

### Signed universal APK

The artifact handed to a phone is the same four-ABI APK, aligned and signed with
a locally generated 4096-bit RSA key (the keystore stays in `build/`, which is
not tracked):

```bash
keytool -genkeypair -keystore build/signing/zai-bridge.jks -alias zaibridge \
  -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass zai-bridge -keypass zai-bridge \
  -dname "CN=Zai-Bridge Universal, O=Zai-Bridge, C=US"

BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0
"$BUILD_TOOLS/zipalign" -f -p 4 app/build/outputs/apk/debug/app-debug.apk \
  build/signing/aligned.apk
"$BUILD_TOOLS/apksigner" sign --ks build/signing/zai-bridge.jks \
  --ks-key-alias zaibridge --ks-pass pass:zai-bridge \
  --key-pass pass:zai-bridge --out Zai-Bridge-Universal.apk \
  build/signing/aligned.apk
"$BUILD_TOOLS/apksigner" verify --print-certs Zai-Bridge-Universal.apk
```

`zai-bridge/Zai-Bridge-Universal.apk` is therefore v2 + v3 signed, 4-byte
aligned, and contains all four ABIs plus the bundled 32-bit runtime sysroot.

## Install on a phone (Android 8 – 16+)

1. Copy `Zai-Bridge-Universal.apk` to the phone and open it. Allow "install
   unknown apps" for the file manager or browser when prompted; the APK carries
   a self-signed certificate, so Play Protect may ask you to confirm. If a
   differently signed Zai-Bridge build is already installed (for example the
   debug APK), uninstall it first — Android refuses to replace an app with a
   different signing key.
2. Launch Zai-Bridge and tap **Grant access**. The launcher needs *All files
   access* (`MANAGE_EXTERNAL_STORAGE`) to browse the phone for old APK/OBB pairs.
3. Tap **Import game** and pick the APK. If the game ships an expansion file,
   attach it at the next prompt; it is copied into private storage and hidden
   from the grid.
4. Tap the game card. The proxy Activity opens full-screen in the orientation
   the imported manifest declares.
5. Nothing has to be placed in `/sdcard/Android/obb`: the import keeps its OBB
   under `files/games/<package>/obb/` and the guest sees it through its
   redirected `obb` directory.

Uninstalling Zai-Bridge removes the imports with it (`allowBackup=false`), so
keep the original APK/OBB pair if you want to re-import later.

---

## Verification

On a clean `x86_64` Android 14 emulator (API 34, `ZaiBridge_X86_64`) with the
genuine, unmodified F-Droid build **Tux Rider 1.0.9**
(`com.drodin.tuxrider`, only `lib/armeabi/libtuxrider.so`):

| Check | Result |
|---|---|
| APK installs, 4 ABIs present | yes — `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`; 10.8 MB, v2 + v3 signed, 4-byte aligned |
| `BridgeInstrumentation` installed | yes — `ActivityThread.mInstrumentation -> BridgeInstrumentation` |
| Guest Activity natively attached | yes — `newActivity(ProxyGameActivity) -> com.drodin.tuxrider.MainActivity`, then `onResume` through `Instrumentation.callActivityOnResume` |
| No `IllegalStateException: No activity` | yes — none in the run |
| Guest `onCreate` executed its own code | yes — `System.loadLibrary("tuxrider")` served by the bridge, `NativeLib` initialised |
| ARM32 payload mapped and relocated | yes — 3693 relocations, 0 unresolved, `JNI_OnLoad`, `RegisterNatives` for `resize(II)V` / `render(IIDIZZ)I` |
| Guest's own renderer drives the loop | yes — a continuous `NativeLib.render` call chain on `GLThread`, ~17 frames/s |
| Guest surface handed to the core | yes — `surface holder hooked on com.drodin.tuxrider.MainView` |
| Touch reaches the guest window | yes — injected `input tap 540 1200` and `input swipe 300 1200 800 1200` both logged `guest input ACTION_DOWN @ 540,1200` / `@ 300,1200` inside the guest's Activity window; process survives |
| Guest `resize` completes | yes — returns after loading every course, font and HUD texture; no abort |
| Guest orientation read from the manifest | yes — importer logged `launcher=com.drodin.tuxrider.MainActivity screenOrientation=0 -> landscape` and wrote `"orientation": "landscape"` to `meta.json` |
| Orientation applied before the surface | yes — `guest orientation: manifest landscape -> setRequestedOrientation(0)` before the guest's `onCreate`; the guest's own `setRequestedOrientation(1)` was then overridden (`re-asserting manifest landscape over the guest's own request 1`); window and EGL surface came up 2204×948 |
| `eglSwapBuffers` counted at the Java EGL10 boundary | yes — `EGL swap hooks: EGL10=yes EGL14=yes`; `eglSwapBuffers[java/EGL10]: frame 240 presented (…, 240 EGL10 / 0 EGL14)`; rendering continued with no guest fault |
| Guest faults | 0 `UC_ERR_*_UNMAPPED`, 0 dropped draws (`no data bound`) |
| Menu screenshot | yes — `build/screen_menu.png`: full landscape Tux Rider menu (logo, Training, Challenge, Preference, Credits, Help, Quit), no cropping |
| 3D race screenshot | **not captured** in this session — see *Verification gap* |

**Four defects stood between the guest and a presented frame**, all fixed in
`wrapper.cpp`:

1. **Instruction-set state lost on every chunk restart.** `uc_emu_start()` (and
   `uc_reg_write(PC, …)`) derives ARM/Thumb from bit 0 of the address
   (`arm_cpu_set_pc`). The interpreter restarts its `uc_emu_start` in 4 M
   instruction chunks at an even PC, so each restart silently dropped a Thumb
   guest into ARM state and the next block decoded as an invalid ARM
   instruction. The restart now carries CPSR.T in bit 0.
2. **GLES1 entry points were resolved from `libGLESv2.so`.** They are not
   exported there, so `glVertexPointer`, `glMatrixMode`, `glOrthof`,
   `glAlphaFunc` … were NULL and every fixed-function call vanished. They now
   come from `libGLESv1_CM.so`.
3. **`glEnableClientState` was recorded but never forwarded**, so draws were
   issued with no enabled array and the driver dropped them.
4. **`glTexImage2D` was marshalled one argument off** (width read as height,
   height read as border = 0), so every texture upload was rejected with
   `GL_INVALID_VALUE`.

Getting `resize` to finish also required publishing host `errno` into the
guest cell on failed `stat`/`access`/`fopen`: the engine probes for its
optional `tuxracerConfig` with `stat` and treats any `errno != ENOENT` as fatal,
so a missing file (errno left at 0) aborted initialisation through
`handle_system_error` → `winsys_exit`.

**The frame loop is the guest's own:** its `MainView$MainRenderer.onDrawFrame`
calls `NativeLib.render(IIDIZZ)I` on the `GLThread`, and the guest's own
`javax/microedition/khronos/egl` helper (its `MyGLSurfaceView`, not the
framework's) swaps immediately afterwards. The payload imports no EGL symbols at
all, so the payload-import counter in `wrapper.cpp` never fires for this game;
the swap is counted at the Java boundary instead
(`eglSwapBuffers[java/EGL10]: frame N presented`), which is the path the new
hook adds.

### Verification gap

The 3D race screenshot (`build/screen_gameplay.png`) was **not** obtained. The
guest's `MyGLSurfaceView.surfaceChanged` blocks its main thread until `GLThread`
finishes the engine's `resize` (course, font and HUD loading inside Unicorn); on
the software-GL emulator this exceeded the 5 s input-dispatch timeout, so the
system raised an ANR on every launch. The menu nevertheless rendered and swapped
240+ frames, but the run could not be driven past it: the test host is shared
with another automation that repeatedly reinstalled and foregrounded an
unrelated app, disabled this package, and closed ANR dialogs, removing the
window in which the `Training` tap had to land.

Everything above the gap — manifest orientation, pre-surface rotation, and the
Java/native swap accounting — is verified on the device. The interactive race
run needs hardware the harness owns exclusively, or a GPU-accelerated device.

## Current limitations

* **The 3D race path is unverified.** The menu now renders in the orientation
  its manifest declares, but selecting a course has not been driven on a device
  the harness controls exclusively; see *Verification gap*.
* **A guest's later orientation changes win.** The manifest orientation is
  applied before `onCreate` and re-asserted once afterwards. A game that flips
  the screen later in its lifecycle keeps its own choice.
* **Runtime bundle is small.** `assets/runtime/` holds the NDK's 32-bit
  compatibility libraries (`libc`, `libm`, `libGLESv1_CM`, `libGLESv2`,
  `libEGL`, `libOpenSLES`, `libandroid`, `libz`, `libdl`, `liblog`,
  `libvulkan`, `libstdc++`, `libOpenMAXAL` for both `arm` and `x86`) plus
  `org.apache.http.legacy.jar`. Those NDK platform libraries are linker stubs,
  and `android.support.v4` / the Play APK-Expansion classes are not available
  locally, so they are not bundled. The APK is 10.8 MB, not the 200 MB target:
  padding it to a size would add nothing a guest can use.
* **OpenSL ES is interface-complete but not exercised** by the verified payload;
  buffer-queue callbacks from the host thread are accepted and ignored.
* **`ALooper_addFd` callbacks** cannot be invoked from the host looper; the fd is
  registered for polling only.
* **Some libc surface remains stubbed** (`qsort`, `mktime`, `uname`,
  `realpath`, `getpwuid`, socket setup); each is logged once as
  `unimplemented import`. `srand48`/`lrand48` and `glClearStencil` are
  implemented because this payload uses them.
* **Single session per process.** A second launch reports
  `SESSION_ALREADY_RUNNING`; teardown is not implemented.
* **Performance.** Interpretation, not JIT.

## Third-party

Unicorn Engine 2.1.x (`libunicorn.a`, ARM32/Thumb guest build), GPLv2 /
LGPLv2.1. Headers and archive are staged under `app/src/main/cpp/prebuilt/`.

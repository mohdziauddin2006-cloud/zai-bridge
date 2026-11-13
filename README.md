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
        │  GameHostActivity (NoTitleBar.Fullscreen + IMMERSIVE_STICKY)                            │
        │      │  DexClassLoader(game.apk)              │  UnicornBridge.nativeStartSession()      │
        │      │                                        ▼                                        │
        │      │                              wrapper_arm64.so ──► libunicorn.a (static)          │
        │      │                                        │                                        │
        │      │                    maps ELF32 payloads, relocates, runs DT_INIT,                 │
        │      │                    binds Java_* exports to 64-bit trampolines,                   │
        │      └──────────────────► feeds guest JNI through a shadow JNIEnv                       │
        └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Repository layout

```
zai-bridge/
├── app/
│   ├── build.gradle                     # AGP 8.13, compileSdk/targetSdk 34, arm64-v8a only
│   └── src/main/
│       ├── AndroidManifest.xml          # MANAGE_EXTERNAL_STORAGE, FGS, GameHost theme
│       ├── cpp/
│       │   ├── CMakeLists.txt           # links libunicorn.a statically
│       │   ├── wrapper_arm64.cpp        # the translation core (ELF loader + JNI bridge)
│       │   └── prebuilt/arm64-v8a/      # libunicorn.a + Unicorn headers
│       ├── java/com/ziauddin/zaibridge/
│       │   ├── MainActivity.java        # all-files access, APK/OBB pickers, grid
│       │   ├── GameHostActivity.java    # immersive host, DexClassLoader, session start
│       │   ├── core/UnicornBridge.java  # Java facade over the native core
│       │   ├── data/                    # Game, GameRepository, GameAdapter, GameImporter
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
  game.apk             original archive (DexClassLoader source)
  icon.png             icon rendered from the guest manifest
  meta.json            label, package, launcher, ABI, OBB name, timestamp, size
  payload/
    libfoo32.so        every lib/armeabi-v7a/*.so, renamed with a 32 tag
    dex/classes*.dex   extracted dex (archived for inspection / future use)
  obb/<name>.obb       expansion data
```

* `.so` extraction accepts `lib/armeabi-v7a/` and the ancient `lib/arm/`.
* The `libfoo.so → libfoo32.so` rename is the **only** contract between the
  ingestion engine and the native core; no game or symbol name is hardcoded.
* `meta.json` is the sole source of what the grid renders. The OBB directory is
  never read by the UI and lives in app-private storage, so it is invisible both
  to the launcher and to other apps.

---

## 2. The launcher

`MainActivity` is a framework-only Material dark screen: name, tagline, a
permission banner, a `GridView` with an auto-fit column width, and an import
button. Each cell shows the icon and the manifest-derived label plus a
`32-bit · OBB` badge — nothing else about the imported files is exposed.

---

## 3. Immersive execution

`GameHostActivity` is declared with `@style/Theme.ZaiBridge.GameHost`, whose
parent is `@android:style/Theme.NoTitleBar.Fullscreen`; it additionally sets
`Window.FEATURE_NO_TITLE`, `FLAG_FULLSCREEN`, `FLAG_KEEP_SCREEN_ON`, and the
legacy immersive flags:

```
SYSTEM_UI_FLAG_IMMERSIVE_STICKY | FULLSCREEN | HIDE_NAVIGATION |
LAYOUT_STABLE | LAYOUT_FULLSCREEN | LAYOUT_HIDE_NAVIGATION
```

On API 30+ it also calls `Window.setDecorFitsSystemWindows(false)` and hides both
insets through `WindowInsetsController` with transient-bars-by-swipe behaviour.
The flags are re-applied in `onResume` and whenever the window regains focus, so
a swiped-in bar cannot leave the game letterboxed.

The guest dex is loaded with

```java
new DexClassLoader(game.apk, codeCacheDir/guest-<pkg>, payload/, hostClassLoader)
```

and installed as the thread's context class loader, so the native core can bind
the APK's `Java_*` exports by their real names instead of falling back to the
host application's loader.

---

## 4. The translation core

`wrapper_arm64.cpp` (migrated from the earlier single-APK harness) is now
session-based. `JNI_OnLoad` only captures the `JavaVM`; the payload set is
opened on demand by:

```
Java_com_ziauddin_zaibridge_core_UnicornBridge_nativeStartSession(
        JNIEnv*, jclass, jstring gameDir, jstring obbPath, jobject guestLoader)
```

which maps `<gameDir>/payload/lib*32.so`, links them, runs constructors, binds
exports, and calls the guest `JNI_OnLoad`. It returns `0` on success, `1` if a
session is already live, and negative codes for init/payload/map failures.

### Guest address space

| Range | Purpose |
|---|---|
| `0x3000_0000` + 64 MB | guest heap (first-fit allocator with coalescing) |
| `0x4000_0000`, +16 MB per library | mapped ELF32 images |
| `0x5000_0000` | trap page: JNI + import stubs |
| `0x500F_0000` | unmapped return sentinel for `uc_emu_start` |
| `0x7FE0_0000`–`0x7FF0_0000` | 1 MB downward-growing guest stack |

### What the core maintains

* **FPU bring-up** — CPACR grants CP10/CP11 and FPEXC.EN is set before any guest
  code runs, because Unicorn leaves VFP disabled and every Android ARMv7 binary
  assumes the kernel enabled it.
* **JNI shadow pointers** — the guest sees a 32-bit `JNIEnv*` whose only word
  points at a table of 32-bit `bx lr` stubs. A `UC_HOOK_CODE` hook over the trap
  page turns each stub into the real 64-bit JNI call and returns through `LR`.
* **Reverse direction** — each `Java_*` method registered with ART gets a
  generated ARM64 trampoline that spills `x0–x7`, calls back into C++, and
  re-enters the emulator at the guest function.
* **Host routing table** — `libc`/`libm`/`pthread` imports are forwarded to the
  host by name (`malloc` served from guest memory, `pthread_once` run in-guest,
  `stat`/`dirent` marshalled between 32- and 64-bit layouts, softfp `float`/
  `double` ABI handled explicitly). Unimplemented imports resolve to logging
  stubs instead of jumping into garbage.

---

## 5. Icon

The adaptive icon is an infinity loop split down the middle: the left lobe is
legacy amber (`#FFB454`), the right lobe is modern cyan (`#3BE0F2`), and a thin
deck with two pylons underneath turns the loop into a bridge between the two
architectures. It exists as an adaptive icon (`mipmap-anydpi-v26`) and a
flattened vector fallback (`mipmap-anydpi`) for API 24/25.

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

The APK contains exactly two native libraries, both `arm64-v8a`:
`libwrapper_arm64.so` (bridge + statically linked Unicorn) and
`libc++_shared.so`. Unicorn's TCG/softmmu symbols are excluded from the dynamic
symbol table with `-Wl,--exclude-libs,ALL`.

To rebuild the emulator core (ARM32/Thumb guest only):

```bash
./vendor/build_unicorn_android.sh     # stages vendor/unicorn/android/<abi>/
# then copy <abi>/{include,lib} into app/src/main/cpp/prebuilt/<abi>/
```

---

## Current limitations

This repository is the container scaffold: ingestion, storage isolation, the
launcher, immersive hosting, dex loading and the native session lifecycle are
implemented and build cleanly. The remaining work is inside the bridge and the
presentation path, and it is deliberately called out rather than hidden:

* **Guest Activity lifecycle / rendering.** The guest's dex is loaded and its
  launcher class is resolved, but its `Activity` is not yet instantiated inside
  the host, and EGL/`ANativeWindow` calls from the guest are forwarded only as
  far as the logging stubs. A game's own frames therefore do not reach the host
  `SurfaceView` yet; that needs a guest-accelerated `Surface`/EGL bridge.
* **Single session per process.** The guest address space is fixed, so a second
  game launch in the same process reports `SESSION_ALREADY_RUNNING`. Teardown
  (`uc_close`, freeing the trampoline pool and guest loader) is still to come.
* **OBB redirection.** The expansion file is hidden and tracked, but the guest
  is not yet given a redirected `getObbDir()`/`/sdcard/Android/obb` view.
* **Syscall coverage.** Only the libc memory/string/stdio/file/time/pthread
  surface is forwarded; `mmap`, `mprotect`, `dlopen`, timers and EGL still fall
  through to the report-only stubs.
* **JNI ABI gaps.** 64-bit (`long`/`double`) arguments and float/double returns
  across the boundary, ARM32 TLS and exception unwinding remain unimplemented.
* **Performance.** Interpretation, not JIT: expect a frame-rate ceiling well
  below native, and no multi-threaded guest scheduling.

---

## Third-party

Unicorn Engine 2.1.x (`libunicorn.a`, ARM32/Thumb guest build), GPLv2 /
LGPLv2.1. Headers and archive are staged under `app/src/main/cpp/prebuilt/`.

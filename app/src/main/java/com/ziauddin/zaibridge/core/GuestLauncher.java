package com.ziauddin.zaibridge.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import com.ziauddin.zaibridge.data.Game;
import com.ziauddin.zaibridge.data.GameRepository;
import com.ziauddin.zaibridge.data.GraphicsSettings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Bridge between the framework-launched proxy Activity and the imported game.
 *
 * <p>Supports two execution modes seamlessly:
 * <ul>
 *   <li><b>{@code native64} (Installed 64-bit / Split-APK games):</b> combines
 *       {@code game.apk} + {@code splits/*.apk} into the DEX classpath, points
 *       the native library search path directly at {@code native64/}, skips
 *       Unicorn CPU translation completely, and hooks EGL/GLES/Vulkan symbols
 *       via {@link EglSwapHook#refreshNativeHooks()} for Zai-Scale Ultra
 *       upscaling, DLSS-5/FSR shaders, and Unlimited Multi-Frame Generation.</li>
 *   <li><b>{@code unicorn32} (Legacy 32-bit ARM games):</b> opens the Unicorn
 *       ARM32-to-ARM64 translation session before loading guest classes so
 *       static {@code System.loadLibrary} initialisers resolve cleanly.</li>
 * </ul>
 */
public final class GuestLauncher {

    private static final String TAG = "ZaiBridge";

    public static final String EXTRA_GAME_DIR = "com.ziauddin.zaibridge.extra.GAME_DIR";
    public static final String EXTRA_GAME_LABEL = "com.ziauddin.zaibridge.extra.GAME_LABEL";
    public static final String EXTRA_GUEST_ACTIVITY =
            "com.ziauddin.zaibridge.extra.GUEST_ACTIVITY_CLASS";

    /** One session per process; a second game replaces the current one. */
    private static volatile String sessionGameDir;
    private static volatile ClassLoader guestLoader;
    private static volatile File guestLoaderDir;
    private static volatile boolean guestLoaderNative64;
    private static volatile File guestGameDir;
    private static volatile String guestPackage;
    private static volatile String guestClass;
    private static volatile boolean guestNative64;
    private static volatile GuestContext cachedGuestContext;
    private static volatile File cachedGuestContextDir;
    private static volatile String cachedGuestContextActivity;
    private static volatile String lastLaunchError;

    private static final ExecutorService SURFACE_QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zai-bridge-surface");
        t.setDaemon(true);
        return t;
    });

    private GuestLauncher() {
    }

    public static String getLastLaunchError() {
        return lastLaunchError;
    }

    public static void clearLastLaunchError() {
        lastLaunchError = null;
    }

    public static void recordLaunchError(String summary, Throwable t) {
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isEmpty()) {
            sb.append(summary);
        }
        if (t != null) {
            if (sb.length() > 0) sb.append("\n\n");
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            sb.append(sw.toString());
        }
        lastLaunchError = sb.toString();
    }

    // ------------------------------------------------------------------
    // Framework path (called from BridgeInstrumentation)
    // ------------------------------------------------------------------

    public static Activity createGuest(Context ctx, Intent intent) {
        clearLastLaunchError();
        BridgeRuntime.ensureInitialized(ctx);
        final String dir = intent == null ? null : intent.getStringExtra(EXTRA_GAME_DIR);
        if (dir == null || dir.isEmpty()) {
            String msg = "Proxy launch invoked without EXTRA_GAME_DIR";
            Log.w(TAG, msg);
            recordLaunchError(msg, null);
            return null;
        }
        final File gameDir = new File(dir);
        final File apk = new File(gameDir, "game.apk");
        if (!apk.isFile()) {
            String msg = "Incomplete import: missing base game.apk at " + apk.getAbsolutePath();
            Log.e(TAG, msg);
            recordLaunchError(msg, null);
            return null;
        }

        try {
            Game game = GameRepository.load(gameDir);
            final String pkg = game != null && game.packageName != null && !game.packageName.isEmpty()
                    ? game.packageName : gameDir.getName();

            BinaryXmlManifestReader.ParsedManifest manifest =
                    BinaryXmlManifestReader.readFromApk(apk.getAbsolutePath());

            String name = intent.getStringExtra(EXTRA_GUEST_ACTIVITY);
            if (name == null || name.isEmpty()) {
                name = game != null && game.launcherActivity != null
                        ? game.launcherActivity : "";
            }
            if (name.isEmpty()) {
                name = manifest.launcherActivity;
            }
            name = BinaryXmlManifestReader.normalizeClassName(pkg, name);
            name = manifest.resolveActivityTarget(name);

            if (name.isEmpty()) {
                String msg = "Guest AndroidManifest.xml declares no launchable Activity (" + pkg + ")";
                Log.e(TAG, msg);
                recordLaunchError(msg, null);
                return null;
            }

            final boolean isNative64 = isNative64Game(game, gameDir);
            guestPackage = pkg;
            guestGameDir = gameDir;
            guestClass = name;
            guestNative64 = isNative64;

            // Install & configure Zai-Scale Ultra compositor before guest GL init.
            EglSwapHook.install();
            GraphicsSettings graphics = GraphicsSettings.forGame(ctx, gameDir);
            EglSwapHook.configure(graphics);

            // NOTE: Do not run full asset zip extraction on the UI thread if prepareGame()
            // already unpacked; unpackBundledAssets fast-returns when stamp exists.
            if (!new File(gameDir, "data/.zai-assets-unpacked").isFile() && !isNative64) {
                unpackBundledAssets(apk, gameDir);
            }

            ClassLoader loader = ensureGuestLoader(ctx, gameDir, pkg, isNative64);
            Thread.currentThread().setContextClassLoader(loader);

            // Warm GuestContext right away if prepareGame didn't pre-warm it yet.
            getOrCreateGuestContext(ctx.getApplicationContext(), gameDir, pkg, name, isNative64, loader);

            if (isNative64) {
                // Close any lingering Unicorn session from an earlier 32-bit game.
                if (UnicornBridge.isSessionActive()) {
                    UnicornBridge.endSession();
                    sessionGameDir = null;
                }
                Log.i(TAG, "native64 mode active for " + pkg + " (Unicorn bypassed)");
            } else {
                final boolean hasPayload = hasNativePayload(new File(gameDir, "payload"));
                if (hasPayload) {
                    openNativeSession(ctx, gameDir, game, pkg, loader);
                } else {
                    Log.i(TAG, "pure-Java import: no ARM32 payload, Unicorn skipped");
                }
            }

            Class<?> cls = Class.forName(name, true, loader);
            if (!Activity.class.isAssignableFrom(cls)) {
                String msg = name + " does not extend android.app.Activity (resolved for " + pkg + ")";
                Log.e(TAG, msg);
                recordLaunchError(msg, null);
                return null;
            }
            java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            Activity guest = (Activity) ctor.newInstance();

            // Refresh PLT/GOT hooks in case static initializers just dlopen'd guest .so libs.
            EglSwapHook.refreshNativeHooks();

            Log.i(TAG, "guest Activity " + name + " instantiated from " + apk.getName()
                    + " (mode=" + (isNative64 ? "native64" : "unicorn32") + ")");
            return guest;
        } catch (Throwable t) {
            Log.e(TAG, "guest Activity could not be created", t);
            recordLaunchError("Failed instantiating guest Activity ("
                    + t.getClass().getName() + ": " + t.getMessage() + ")", t);
            return null;
        }
    }

    /**
     * Prepares runtime extraction, guest dex loading, manifest resolution,
     * {@link GuestContext} pre-binding, and initializes the native session off the UI thread
     * before launching the Proxy Activity.
     */
    public static void prepareGame(Context ctx, File gameDir, Game game) {
        clearLastLaunchError();
        BridgeRuntime.ensureInitialized(ctx);
        if (gameDir == null || !gameDir.isDirectory()) return;

        final boolean isNative64 = isNative64Game(game, gameDir);
        guestGameDir = gameDir;
        String pkg = game != null && game.packageName != null ? game.packageName : gameDir.getName();
        guestPackage = pkg;
        guestNative64 = isNative64;

        File apk = new File(gameDir, "game.apk");
        if (!apk.isFile()) return;

        unpackBundledAssets(apk, gameDir);

        EglSwapHook.install();
        EglSwapHook.configure(GraphicsSettings.forGame(ctx, gameDir));

        ClassLoader loader = ensureGuestLoader(ctx, gameDir, pkg, isNative64);
        String launchClass = game != null && game.launcherActivity != null ? game.launcherActivity : "";
        BinaryXmlManifestReader.ParsedManifest manifest =
                BinaryXmlManifestReader.readFromApk(apk.getAbsolutePath());
        if (launchClass.isEmpty()) {
            launchClass = manifest.launcherActivity;
        }
        launchClass = BinaryXmlManifestReader.normalizeClassName(pkg, launchClass);
        launchClass = manifest.resolveActivityTarget(launchClass);
        guestClass = launchClass;

        // Pre-warm GuestContext & Resources/AssetManager off UI thread so launch is instant.
        getOrCreateGuestContext(ctx.getApplicationContext(), gameDir, pkg, launchClass, isNative64, loader);

        if (!isNative64) {
            final boolean hasPayload = hasNativePayload(new File(gameDir, "payload"));
            if (hasPayload) {
                openNativeSession(ctx, gameDir, game, pkg, loader);
            }
        } else if (UnicornBridge.isSessionActive()) {
            UnicornBridge.endSession();
            sessionGameDir = null;
        }
    }

    private static synchronized ClassLoader ensureGuestLoader(Context ctx, File gameDir,
                                                              String pkg, boolean isNative64) {
        if (guestLoader != null && gameDir.equals(guestLoaderDir)
                && guestLoaderNative64 == isNative64) {
            return guestLoader;
        }

        File optimized = new File(ctx.getCodeCacheDir(), "guest-" + gameDir.getName());
        if (!optimized.isDirectory() && !optimized.mkdirs()) {
            optimized = ctx.getCodeCacheDir();
        }

        String dexPath = buildCombinedDexPath(gameDir);

        File nativeDir;
        String hostLibrary;
        String searchPath;
        if (isNative64) {
            File dir64 = new File(gameDir, "native64");
            nativeDir = dir64.isDirectory() ? dir64 : new File(gameDir, "native");
            // Passing hostLibrary = null lets GuestDexLoader load guest native 64-bit .so libs
            // child-first and directly from staged native64/, installed native dir, or split APKs.
            hostLibrary = null;
            searchPath = buildNativeLibrarySearchPath(ctx, gameDir, pkg, true);
        } else {
            nativeDir = new File(gameDir, "native");
            hostLibrary = UnicornBridge.isPassthrough()
                    ? null : GuestDexLoader.hostBridgeLibrary(ctx);
            searchPath = buildNativeLibrarySearchPath(ctx, gameDir, pkg, false);
        }

        ClassLoader parent = isNative64
                ? GuestLauncher.class.getClassLoader()
                : GuestDexLoader.legacyParent(ctx, GuestLauncher.class.getClassLoader());

        ClassLoader loader = new GuestDexLoader(dexPath,
                optimized.getAbsolutePath(), searchPath,
                parent, hostLibrary, nativeDir);
        guestLoader = loader;
        guestLoaderDir = gameDir;
        guestLoaderNative64 = isNative64;
        UnicornBridge.setGuestLoader(loader);
        Log.i(TAG, "guest ClassLoader ready (mode=" + (isNative64 ? "native64" : "unicorn32")
                + ", searchPath=" + searchPath + ")");
        return loader;
    }

    public static String buildNativeLibrarySearchPath(Context ctx, File gameDir,
                                                      String pkg, boolean isNative64) {
        java.util.LinkedHashSet<String> entries = new java.util.LinkedHashSet<>();
        if (isNative64) {
            File dir64 = new File(gameDir, "native64");
            if (dir64.isDirectory()) entries.add(dir64.getAbsolutePath());
            File dir32 = new File(gameDir, "native");
            if (dir32.isDirectory()) entries.add(dir32.getAbsolutePath());
        } else {
            File dir32 = new File(gameDir, "native");
            if (dir32.isDirectory()) entries.add(dir32.getAbsolutePath());
            File payload = new File(gameDir, "payload");
            if (payload.isDirectory()) entries.add(payload.getAbsolutePath());
        }

        // If the package is currently installed on the device, include its nativeLibraryDir
        // and any APK splits (handles extractNativeLibs=false AAB bundles).
        String primaryAbi = isNative64
                ? (Build.SUPPORTED_64_BIT_ABIS.length > 0 ? Build.SUPPORTED_64_BIT_ABIS[0] : "arm64-v8a")
                : (Build.SUPPORTED_32_BIT_ABIS.length > 0 ? Build.SUPPORTED_32_BIT_ABIS[0] : "armeabi-v7a");

        if (ctx != null && pkg != null && !pkg.isEmpty()) {
            try {
                android.content.pm.ApplicationInfo ai =
                        ctx.getPackageManager().getApplicationInfo(pkg, 0);
                if (ai != null) {
                    if (ai.nativeLibraryDir != null && !ai.nativeLibraryDir.isEmpty()) {
                        entries.add(ai.nativeLibraryDir);
                    }
                    if (ai.sourceDir != null && !ai.sourceDir.isEmpty()) {
                        entries.add(ai.sourceDir + "!/lib/" + primaryAbi);
                    }
                    if (ai.splitSourceDirs != null) {
                        for (String s : ai.splitSourceDirs) {
                            if (s != null && !s.isEmpty()) {
                                entries.add(s + "!/lib/" + primaryAbi);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        File baseApk = new File(gameDir, "game.apk");
        if (baseApk.isFile()) {
            entries.add(baseApk.getAbsolutePath() + "!/lib/" + primaryAbi);
        }
        File splitsDir = new File(gameDir, "splits");
        if (splitsDir.isDirectory()) {
            File[] splits = splitsDir.listFiles((d, n) -> n.endsWith(".apk"));
            if (splits != null) {
                Arrays.sort(splits);
                for (File split : splits) {
                    if (split.isFile()) {
                        entries.add(split.getAbsolutePath() + "!/lib/" + primaryAbi);
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String e : entries) {
            if (sb.length() > 0) sb.append(File.pathSeparatorChar);
            sb.append(e);
        }
        return sb.toString();
    }

    public static synchronized GuestContext getOrCreateGuestContext(
            Context hostAppCtx, File gameDir, String pkg, String activityClass,
            boolean isNative64, ClassLoader loader) {
        if (cachedGuestContext != null
                && gameDir != null
                && gameDir.equals(cachedGuestContextDir)
                && java.util.Objects.equals(activityClass, cachedGuestContextActivity)) {
            return cachedGuestContext;
        }
        GuestContext gc = new GuestContext(
                hostAppCtx, gameDir, pkg, activityClass, isNative64, loader);
        cachedGuestContext = gc;
        cachedGuestContextDir = gameDir;
        cachedGuestContextActivity = activityClass;
        return gc;
    }

    /**
     * Combines {@code game.apk} and any {@code splits/*.apk} into one
     * {@link File#pathSeparator}-delimited DEX path, enforcing read-only
     * permissions on every archive for Android 14+ ART security rules.
     */
    public static String buildCombinedDexPath(File gameDir) {
        StringBuilder path = new StringBuilder();
        File baseApk = new File(gameDir, "game.apk");
        if (baseApk.isFile()) {
            ensureReadOnly(baseApk);
            path.append(baseApk.getAbsolutePath());
        }
        File splitsDir = new File(gameDir, "splits");
        if (splitsDir.isDirectory()) {
            File[] splits = splitsDir.listFiles((d, n) -> n.endsWith(".apk"));
            if (splits != null && splits.length > 0) {
                Arrays.sort(splits);
                for (File split : splits) {
                    if (!split.isFile()) continue;
                    ensureReadOnly(split);
                    if (path.length() > 0) path.append(File.pathSeparatorChar);
                    path.append(split.getAbsolutePath());
                }
            }
        }
        return path.toString();
    }

    private static void ensureReadOnly(File file) {
        if (file.canWrite() && !file.setReadOnly()) {
            Log.w(TAG, file.getName() + " could not be made read-only; ART may refuse it");
        }
    }

    public static boolean isNative64Game(Game game, File gameDir) {
        if (game != null && game.isNative64()) return true;
        File dir64 = new File(gameDir, "native64");
        if (dir64.isDirectory()) {
            File[] libs = dir64.listFiles((d, name) -> name.endsWith(".so"));
            return libs != null && libs.length > 0;
        }
        return false;
    }

    public static void applyGuestOrientation(Activity guest) {
        GameOrientation.apply(guest, guestGameDir);
    }

    public static void confirmGuestOrientation(Activity guest) {
        Game game = guestGameDir == null ? null : GameRepository.load(guestGameDir);
        if (game == null) return;
        int wanted = GameOrientation.constantOf(game.orientation);
        if (wanted == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return;
        if (guest.getRequestedOrientation() == wanted) return;
        Log.i(TAG, "guest orientation: re-asserting manifest " + game.orientation
                + " over the guest's own request " + guest.getRequestedOrientation());
        GameOrientation.apply(guest, game.orientation, game.label);
    }

    private static void setFieldQuiet(Class<?> stopClass, Object target, String fieldName, Object value) {
        if (target == null) return;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                if (c == stopClass) break;
            } catch (Throwable t) {
                Log.w(TAG, "setFieldQuiet(" + fieldName + ") skipped: " + t.getMessage());
                return;
            }
        }
    }

    public static void attachGuestContext(Activity guest, Context ctx) {
        final File gameDir = guestGameDir;
        if (gameDir == null) return;
        try {
            GuestContext guestContext = getOrCreateGuestContext(
                    ctx.getApplicationContext(),
                    gameDir,
                    guestPackage,
                    guestClass,
                    guestNative64,
                    guestLoader);

            setFieldQuiet(android.content.ContextWrapper.class, guest, "mBase", guestContext);
            setFieldQuiet(android.view.ContextThemeWrapper.class, guest, "mResources",
                    guestContext.getResources());
            setFieldQuiet(android.view.ContextThemeWrapper.class, guest, "mTheme",
                    guestContext.getTheme());
            setFieldQuiet(android.view.ContextThemeWrapper.class, guest, "mInflater", null);
            setFieldQuiet(Activity.class, guest, "mApplication",
                    guestContext.getGuestApplication());

            android.content.ComponentName comp = new android.content.ComponentName(
                    guestPackage != null ? guestPackage : guest.getPackageName(),
                    guestClass != null ? guestClass : guest.getClass().getName());
            android.content.pm.ActivityInfo guestAi =
                    guestContext.resolveGuestActivityInfo(comp);
            if (guestAi != null) {
                setFieldQuiet(Activity.class, guest, "mActivityInfo", guestAi);
            }

            Log.i(TAG, "guest base context + Resources/PM/Application injected (files="
                    + guestContext.getFilesDir() + ", obb=" + guestContext.getObbDir() + ")");
            guestContext.mergeInto(guest.getAssets());
        } catch (Throwable t) {
            Log.w(TAG, "guest context injection failed: " + t);
        }

        if (!guestNative64) {
            applyLegacyTheme(guest);
        }
    }

    /**
     * Called after the guest Activity's {@code onCreate}: re-scans ELF PLT/GOT
     * tables on a worker thread for late-loaded 64-bit engines (Unity/Unreal/Cocos),
     * unlocks peak display refresh rate for uncapped MFG, and attaches the floating
     * HUD + Force-Save overlay onto the guest's window.
     */
    public static void attachOverlayAndRefreshHooks(Activity guest, Context hostCtx) {
        if (guest == null) return;
        SURFACE_QUEUE.execute(EglSwapHook::refreshNativeHooks);

        if (guestGameDir != null && hostCtx != null) {
            GraphicsSettings settings = GraphicsSettings.forGame(hostCtx, guestGameDir);
            applyDisplayFrameRate(guest, settings);
            InGameOverlay.attach(guest, hostCtx, guestGameDir, guestPackage, guestClass);
        }

        // Schedule delayed hook refreshes OFF the Main UI thread so dl_iterate_phdr never stalls UI.
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(() -> SURFACE_QUEUE.execute(EglSwapHook::refreshNativeHooks), 800);
        h.postDelayed(() -> SURFACE_QUEUE.execute(EglSwapHook::refreshNativeHooks), 2500);
    }

    /**
     * Unlocks the highest hardware display mode on Android 6+ and avoids
     * clamping {@code Surface.setFrameRate} to 60/120 Hz when Unlimited MFG or
     * Berserk Mode is enabled.
     */
    public static void applyDisplayFrameRate(Activity activity, GraphicsSettings settings) {
        if (activity == null || settings == null) return;
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            WindowManager.LayoutParams lp = window.getAttributes();
            if (Build.VERSION.SDK_INT >= 23 && activity.getWindowManager() != null) {
                Display display = activity.getWindowManager().getDefaultDisplay();
                if (display != null) {
                    Display.Mode[] modes = display.getSupportedModes();
                    if (modes != null && modes.length > 0) {
                        Display.Mode fastest = modes[0];
                        for (Display.Mode m : modes) {
                            if (m.getRefreshRate() > fastest.getRefreshRate()) {
                                fastest = m;
                            }
                        }
                        if (settings.isUncapped()
                                || settings.frameGen != GraphicsSettings.FRAME_GEN_OFF) {
                            lp.preferredDisplayModeId = fastest.getModeId();
                            if (Build.VERSION.SDK_INT >= 30) {
                                lp.preferredRefreshRate = settings.isUncapped()
                                        ? 0f : fastest.getRefreshRate();
                            }
                            window.setAttributes(lp);
                            Log.i(TAG, "display refresh unlocked: mode=" + fastest.getModeId()
                                    + " (" + fastest.getRefreshRate() + " Hz, uncapped="
                                    + settings.isUncapped() + ")");
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "display refresh rate hint skipped: " + t);
        }
    }

    private static void applyLegacyTheme(Activity guest) {
        try {
            int target = guest.getApplicationInfo().targetSdkVersion;
            int theme = target < 21
                    ? android.R.style.Theme_Holo_NoActionBar
                    : android.R.style.Theme_Material_NoActionBar;
            guest.setTheme(theme);
            Log.i(TAG, "guest theme fallback: " + (target < 21 ? "Holo" : "Material")
                    + " (targetSdk=" + target + ")");
        } catch (Throwable t) {
            Log.w(TAG, "guest theme fallback failed: " + t);
        }
    }

    private static void openNativeSession(Context ctx, File gameDir, Game game,
                                          String pkg, ClassLoader loader) {
        if (gameDir.getAbsolutePath().equals(sessionGameDir)
                && UnicornBridge.isSessionActive()) {
            Log.i(TAG, "native session already live for " + pkg);
            return;
        }
        if (UnicornBridge.isSessionActive()) {
            Log.i(TAG, "closing previous guest session before " + pkg);
            UnicornBridge.endSession();
            sessionGameDir = null;
        }
        if (!UnicornBridge.isCoreLoaded()) {
            if (UnicornBridge.isPassthrough()) {
                Log.i(TAG, "32-bit host: guest payload runs natively");
            } else {
                Log.e(TAG, "native core unavailable on this device");
            }
            return;
        }

        File runtime = RuntimeAssets.ensure(ctx);
        UnicornBridge.setRuntimeSysroot(runtime.getAbsolutePath());
        if (game != null) {
            UnicornBridge.setNativeActivityInfo(game.funcName, game.libName);
        }

        String obb = findObb(gameDir);
        int result = UnicornBridge.startSession(gameDir.getAbsolutePath(), obb, pkg,
                loader, null);
        sessionGameDir = gameDir.getAbsolutePath();
        Log.i(TAG, "native session start -> " + result);
    }

    // ------------------------------------------------------------------
    // Surface hand-off
    // ------------------------------------------------------------------

    public static void probeInput(Activity guest) {
        Window window = guest.getWindow();
        if (window == null) return;
        final View decor = window.getDecorView();
        if (!(decor instanceof ViewGroup)) return;

        View probe = new View(guest) {
            @Override
            public boolean onTouchEvent(android.view.MotionEvent event) {
                return false;
            }
        };
        probe.setClickable(false);
        probe.setFocusable(false);
        probe.setFocusableInTouchMode(false);
        probe.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ((ViewGroup) decor).addView(probe, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public static void hookSurface(Activity guest) {
        if (guestNative64) {
            // Native 64-bit games own their own EGL/Vulkan surface directly;
            // PLT/GOT hooks intercept swaps at the libEGL/libvulkan boundary.
            EglSwapHook.refreshNativeHooks();
            return;
        }
        final Window window = guest.getWindow();
        if (window == null) return;
        final View decor = window.getDecorView();
        final Handler handler = new Handler(Looper.getMainLooper());
        final int[] attempts = {0};

        final Runnable scan = new Runnable() {
            @Override
            public void run() {
                SurfaceView view = findSurfaceView(decor);
                if (view == null) {
                    if (++attempts[0] < 60) {
                        handler.postDelayed(this, 100);
                    } else {
                        Log.w(TAG, "no SurfaceView in the guest view tree; "
                                + "native window stays detached");
                    }
                    return;
                }
                final String name = view.getClass().getName();
                view.getHolder().addCallback(new SurfaceHolder.Callback() {
                    @Override
                    public void surfaceCreated(SurfaceHolder holder) {
                        Log.i(TAG, "guest " + name + " surface created");
                        dispatchSurface(holder.getSurface(),
                                holder.getSurfaceFrame().width(),
                                holder.getSurfaceFrame().height());
                    }

                    @Override
                    public void surfaceChanged(SurfaceHolder holder, int format,
                                               int width, int height) {
                        Log.i(TAG, "guest surface " + width + "x" + height);
                        dispatchSurface(holder.getSurface(), width, height);
                    }

                    @Override
                    public void surfaceDestroyed(SurfaceHolder holder) {
                        SURFACE_QUEUE.execute(() -> UnicornBridge.setSurface(null));
                    }
                });
                Log.i(TAG, "surface holder hooked on " + name);
            }
        };
        handler.post(scan);
    }

    private static void dispatchSurface(final Surface surface, final int width,
                                        final int height) {
        if (surface == null || !surface.isValid()) return;
        SURFACE_QUEUE.execute(() -> {
            Log.i(TAG, "surface handed to the core off the UI thread: "
                    + width + "x" + height);
            UnicornBridge.setSurface(surface);
        });
    }

    private static SurfaceView findSurfaceView(View root) {
        if (root instanceof SurfaceView) return (SurfaceView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); ++i) {
                SurfaceView found = findSurfaceView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void unpackBundledAssets(File apk, File gameDir) {
        final File data = new File(gameDir, "data");
        final File stamp = new File(data, ".zai-assets-unpacked");
        if (stamp.isFile()) return;
        if (!data.isDirectory() && !data.mkdirs()) {
            Log.w(TAG, "asset unpack: cannot create " + data);
            return;
        }

        try (ZipFile apkZip = new ZipFile(apk)) {
            final ZipEntry bundle = apkZip.getEntry("assets/files.zip");
            if (bundle == null) return;

            int files = 0;
            try (InputStream raw = apkZip.getInputStream(bundle);
                 ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                byte[] buf = new byte[64 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    final String name = entry.getName();
                    final File out = new File(data, name);
                    if (!out.getCanonicalPath().startsWith(data.getCanonicalPath() + File.separator)) {
                        Log.w(TAG, "asset unpack: rejected " + name);
                        continue;
                    }
                    if (entry.isDirectory()) {
                        out.mkdirs();
                        continue;
                    }
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) continue;
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = zip.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    ++files;
                }
            }
            if (files > 0) {
                new FileOutputStream(stamp).close();
                Log.i(TAG, "asset bundle unpacked: " + files + " files into " + data);
            }
        } catch (Throwable t) {
            Log.e(TAG, "asset bundle unpack failed", t);
        }
    }

    private static boolean hasNativePayload(File payload) {
        if (!payload.isDirectory()) return false;
        File[] libs = payload.listFiles((dir, name) -> name.endsWith("32.so"));
        return libs != null && libs.length > 0;
    }

    private static String findObb(File gameDir) {
        File[] files = new File(gameDir, "obb").listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    public static String describe() {
        return guestClass == null ? "<none>"
                : guestClass + " (" + guestPackage + ")";
    }

    public static String getGuestPackage() {
        return guestPackage;
    }

    public static String getGameDir() {
        return guestGameDir == null ? null : guestGameDir.getAbsolutePath();
    }

    public static boolean isCurrentGuestNative64() {
        return guestNative64;
    }
}

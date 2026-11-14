package com.ziauddin.zaibridge.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import com.ziauddin.zaibridge.data.Game;
import com.ziauddin.zaibridge.data.GameRepository;

import java.io.File;

/**
 * Bridge between the framework-launched proxy Activity and the imported game.
 *
 * <p>{@link #createGuest(Intent)} runs <em>inside</em> {@code
 * ActivityThread.performLaunchActivity}, as the return value of
 * {@code Instrumentation.newActivity}. The Activity it returns therefore goes
 * through the framework's own {@code attach()}: a real {@code PhoneWindow}, a
 * real {@code DecorView}, a real input channel and a real token. That is the
 * difference between a game that renders and touches, and one that throws
 * {@code IllegalStateException: No activity}.
 *
 * <p>Ordering is deliberate: the Unicorn session is opened <b>before</b> the
 * guest class is initialised, because legacy Activities call
 * {@code System.loadLibrary} from a static initialiser and
 * {@code RegisterNatives} has to have already published their trampolines.
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
    private static volatile File guestGameDir;
    private static volatile String guestPackage;
    private static volatile String guestClass;

    private GuestLauncher() {
    }

    // ------------------------------------------------------------------
    // Framework path (called from BridgeInstrumentation)
    // ------------------------------------------------------------------

    /**
     * Loads the guest class through its own {@code DexClassLoader} and returns
     * a fresh instance for the framework to attach.
     *
     * @return the guest Activity, or null when the import cannot be hosted (the
     *         proxy Activity is then used and shows the failure)
     */
    public static Activity createGuest(Context ctx, Intent intent) {
        final String dir = intent.getStringExtra(EXTRA_GAME_DIR);
        if (dir == null || dir.isEmpty()) {
            Log.w(TAG, "proxy launch without a game directory");
            return null;
        }
        final File gameDir = new File(dir);
        final File apk = new File(gameDir, "game.apk");
        if (!apk.isFile()) {
            Log.e(TAG, "incomplete import: " + dir);
            return null;
        }

        try {
            Game game = GameRepository.load(gameDir);
            String name = intent.getStringExtra(EXTRA_GUEST_ACTIVITY);
            if (name == null || name.isEmpty()) {
                name = game != null && game.launcherActivity != null
                        ? game.launcherActivity : "";
            }
            if (name.isEmpty()) {
                Log.e(TAG, "guest manifest declares no launchable Activity");
                return null;
            }

            final String pkg = game != null ? game.packageName : gameDir.getName();
            guestPackage = pkg;
            guestGameDir = gameDir;
            guestClass = name;

            File optimized = new File(ctx.getCodeCacheDir(), "guest-" + gameDir.getName());
            if (!optimized.isDirectory() && !optimized.mkdirs()) {
                optimized = ctx.getCodeCacheDir();
            }

            // ART on Android 14+ refuses to open a dex archive that is still
            // writable (SecurityException: "Writable dex file ... is not
            // allowed"). The importer already stores it read-only; this is the
            // defensive path for copies staged by other means.
            if (apk.canWrite() && !apk.setReadOnly()) {
                Log.w(TAG, "game.apk could not be made read-only; ART may refuse it");
            }

            // On a 32-bit host the payload is a real loadable .so, so the class
            // loader must answer System.loadLibrary normally. Only the 64-bit
            // translation builds redirect it to the bridge runtime.
            final String hostLibrary = UnicornBridge.isPassthrough()
                    ? null : GuestDexLoader.hostBridgeLibrary(ctx);
            // The alias directory is also the loader's native library search
            // path: Android resolves a library's DT_NEEDED entries inside the
            // class loader's namespace, so libc++_shared.so has to live there.
            final File nativeDir = new File(gameDir, "native");
            ClassLoader loader = new GuestDexLoader(apk.getAbsolutePath(),
                    optimized.getAbsolutePath(), nativeDir.getAbsolutePath(),
                    GuestLauncher.class.getClassLoader(),
                    hostLibrary, nativeDir);
            guestLoader = loader;
            Thread.currentThread().setContextClassLoader(loader);

            final boolean hasPayload = hasNativePayload(new File(gameDir, "payload"));
            if (hasPayload) {
                openNativeSession(ctx, gameDir, game, pkg, loader);
            } else {
                Log.i(TAG, "pure-Java import: no ARM32 payload, Unicorn skipped");
            }

            Class<?> cls = Class.forName(name, true, loader);
            if (!Activity.class.isAssignableFrom(cls)) {
                Log.e(TAG, name + " is not an android.app.Activity");
                return null;
            }
            Activity guest = (Activity) cls.getDeclaredConstructor().newInstance();
            Log.i(TAG, "guest Activity " + name + " instantiated from " + apk.getName()
                    + " (payload=" + hasPayload + ")");
            return guest;
        } catch (Throwable t) {
            Log.e(TAG, "guest Activity could not be created", t);
            return null;
        }
    }

    /**
     * Runs just before {@code Activity.performCreate}: rebases the guest on
     * {@link GuestContext} (merged assets, redirected
     * files/cache/obb), applies a legacy theme fallback and hands the
     * framework-built window's surface to the native core.
     */
    public static void attachGuestContext(Activity guest, Context ctx) {
        final File gameDir = guestGameDir;
        if (gameDir == null) return;
        try {
            GuestContext guestContext = new GuestContext(ctx.getApplicationContext(),
                    gameDir, guestPackage, guestLoader);
            java.lang.reflect.Field base =
                    android.content.ContextWrapper.class.getDeclaredField("mBase");
            base.setAccessible(true);
            base.set(guest, guestContext);
            Log.i(TAG, "guest base context installed (files=" + guestContext.getFilesDir()
                    + ", obb=" + guestContext.getObbDir() + ")");
        } catch (Throwable t) {
            Log.w(TAG, "guest context injection failed: " + t);
        }

        applyLegacyTheme(guest);
    }

    /**
     * Legacy APKs assume the platform default of their target release. A guest
     * theme id cannot be resolved from the host table with certainty, so Holo
     * (pre-21 guests) or Material (21+) is applied as a fallback; the guest may
     * still call {@code setTheme} itself in {@code onCreate}.
     */
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

    /** Starts the translation core for this import, once. */
    private static void openNativeSession(Context ctx, File gameDir, Game game,
                                          String pkg, ClassLoader loader) {
        if (gameDir.getAbsolutePath().equals(sessionGameDir)
                && UnicornBridge.isSessionActive()) {
            Log.i(TAG, "native session already live for " + pkg);
            return;
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

        String obb = findObb(gameDir);
        int result = UnicornBridge.startSession(gameDir.getAbsolutePath(), obb, pkg,
                loader, null);
        sessionGameDir = gameDir.getAbsolutePath();
        Log.i(TAG, "native session start -> " + result);
    }

    // ------------------------------------------------------------------
    // Surface hand-off
    // ------------------------------------------------------------------

    /**
     * Proves the input path end to end: events arrive from the framework's
     * InputChannel for this Activity's window, are dispatched to the guest's
     * own view tree, and are not consumed by the bridge.
     */
    public static void probeInput(Activity guest) {
        Window window = guest.getWindow();
        if (window == null) return;
        final View decor = window.getDecorView();
        if (!(decor instanceof ViewGroup)) return;

        // A transparent sibling above the guest's content. It is asked first
        // (children are offered the event in reverse z-order), logs, and returns
        // false so the guest's own views still receive the same stream.
        View probe = new View(guest) {
            @Override
            public boolean onTouchEvent(android.view.MotionEvent event) {
                String action;
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN: action = "DOWN"; break;
                    case android.view.MotionEvent.ACTION_MOVE: action = "MOVE"; break;
                    case android.view.MotionEvent.ACTION_UP: action = "UP"; break;
                    case android.view.MotionEvent.ACTION_CANCEL: action = "CANCEL"; break;
                    default: action = "action" + event.getActionMasked();
                }
                Log.i(TAG, "guest input ACTION_" + action + " @ "
                        + (int) event.getX() + "," + (int) event.getY()
                        + " (pointers " + event.getPointerCount() + ")");
                return false;   // never consume: the guest keeps the stream
            }
        };
        ((ViewGroup) decor).addView(probe, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Log.i(TAG, "input probe attached above the guest view tree");
    }

    /**
     * The guest owns its {@code GLSurfaceView}; the core needs that ANativeWindow
     * to bind {@code eglCreateWindowSurface}. The view tree does not exist yet
     * when {@code onCreate} returns, so the decor is polled for a few seconds.
     */
    public static void hookSurface(Activity guest) {
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
                        UnicornBridge.setSurface(holder.getSurface());
                    }

                    @Override
                    public void surfaceChanged(SurfaceHolder holder, int format,
                                               int width, int height) {
                        Log.i(TAG, "guest surface " + width + "x" + height);
                        UnicornBridge.setSurface(holder.getSurface());
                    }

                    @Override
                    public void surfaceDestroyed(SurfaceHolder holder) {
                        UnicornBridge.setSurface(null);
                    }
                });
                Log.i(TAG, "surface holder hooked on " + name);
            }
        };
        handler.post(scan);
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
}

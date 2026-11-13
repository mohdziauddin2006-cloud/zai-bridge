package com.ziauddin.zaibridge;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.ziauddin.zaibridge.core.GuestActivityHost;
import com.ziauddin.zaibridge.core.UnicornBridge;
import com.ziauddin.zaibridge.data.Game;
import com.ziauddin.zaibridge.data.GameRepository;

import java.io.File;

import dalvik.system.DexClassLoader;

/**
 * The execution surface.
 *
 * <p>Nothing about this activity is windowed: it uses the NoTitleBar.Fullscreen
 * theme, clears the decor, and asks the SystemUI for
 * {@link View#SYSTEM_UI_FLAG_IMMERSIVE_STICKY}. The game's dex is loaded through
 * a {@link DexClassLoader}; the guest Activity is instantiated from it and the
 * lifecycle is delegated where the framework permits. The guest's ARM32 payload
 * is mapped from private storage, and its EGL calls are forwarded to the host's
 * real EGL/GLES drivers bound to this activity's SurfaceView window.
 */
public class GameHostActivity extends Activity {

    private static final String TAG = "ZaiBridge";

    public static final String EXTRA_GAME_DIR = "com.ziauddin.zaibridge.extra.GAME_DIR";
    public static final String EXTRA_GAME_LABEL = "com.ziauddin.zaibridge.extra.GAME_LABEL";

    private View statusWrap;
    private TextView status;
    private ProgressBar progress;
    private SurfaceView surfaceView;

    private File gameDir;
    private Game game;
    private GuestActivityHost guestHost;

    private volatile ClassLoader guestLoader;
    private volatile boolean guestPrepared;
    private volatile boolean surfaceReady;
    private boolean sessionDispatched;
    private volatile boolean pureJavaMode;

    private final Object startLock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Must be requested before the content view is attached.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_game_host);

        statusWrap = findViewById(R.id.status_wrap);
        status = findViewById(R.id.tv_status);
        progress = findViewById(R.id.progress);
        surfaceView = findViewById(R.id.game_surface);

        // Legacy titles are 4:3 or 16:9; keep their ratio on tall panels.
        AspectRatioFrameLayout aspect = findViewById(R.id.game_aspect);
        boolean portrait = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        aspect.setAspectRatio(portrait ? 3f / 4f : 16f / 9f);

        // Draw into the cutout instead of letterboxing below it.
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }

        enterImmersive();

        String dir = getIntent().getStringExtra(EXTRA_GAME_DIR);
        if (dir == null) {
            finish();
            return;
        }
        gameDir = new File(dir);
        game = GameRepository.load(gameDir);

        String label = getIntent().getStringExtra(EXTRA_GAME_LABEL);
        if (label != null && !label.isEmpty()) {
            status.setText(label + "\n" + getString(R.string.bridge_starting));
        }

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                onSurface(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                onSurface(holder.getSurface());
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                status("surface destroyed", false);
                surfaceReady = false;
                UnicornBridge.setSurface(null);
            }
        });

        prepareGuest();
    }

    private void onSurface(Surface surface) {
        if (surface == null || !surface.isValid()) return;
        UnicornBridge.setSurface(surface);
        surfaceReady = true;
        dispatchSessionIfReady();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersive();
        if (guestHost != null) guestHost.onResume();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (guestHost != null) guestHost.onStart();
    }

    @Override
    protected void onPause() {
        if (guestHost != null) guestHost.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (guestHost != null) guestHost.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (guestHost != null) guestHost.onDestroy();
        UnicornBridge.setSurface(null);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
        if (guestHost != null) guestHost.onWindowFocusChanged(hasFocus);
    }

    private void enterImmersive() {
        Window window = getWindow();
        View decor = window.getDecorView();

        int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        decor.setSystemUiVisibility(flags);

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Guest runtime
    // ---------------------------------------------------------------------

    private void prepareGuest() {
        new Thread(() -> {
            try {
                File apk = new File(gameDir, "game.apk");
                File payload = new File(gameDir, "payload");
                if (!apk.isFile()) {
                    Log.e(TAG, "incomplete import: " + gameDir.getAbsolutePath());
                    reportSession(UnicornBridge.SESSION_NO_PAYLOAD);
                    return;
                }

                final String pkgName = game != null ? game.packageName : gameDir.getName();
                final String launcher = game != null && !game.launcherActivity.isEmpty()
                        ? game.launcherActivity : "";
                final boolean hasNativePayload = hasNativePayload(payload);

                File optimized = new File(getCodeCacheDir(), "guest-" + gameDir.getName());
                if (!optimized.exists() && !optimized.mkdirs()) {
                    optimized = getCodeCacheDir();
                }

                // A dex failure (for example, an archive ART still considers
                // writable) must not stop the ARM32 bridge: the native session
                // and its EGL surface do not depend on the guest class loader.
                ClassLoader loader = null;
                try {
                    loader = new DexClassLoader(apk.getAbsolutePath(),
                            optimized.getAbsolutePath(), payload.getAbsolutePath(),
                            getClassLoader());
                    Thread.currentThread().setContextClassLoader(loader);
                } catch (Throwable t) {
                    Log.e(TAG, "guest dex unavailable; starting native payload only", t);
                }
                guestLoader = loader;

                // Pure Dalvik/Java imports have no ARM32 libraries: nothing to
                // translate, so the guest Activity is hosted directly.
                if (loader != null && !hasNativePayload) {
                    pureJavaMode = true;
                    launchPureJava(loader, pkgName, launcher);
                    guestPrepared = true;
                    return;
                }

                if (loader != null) {
                    String pkg = game != null ? game.packageName : gameDir.getName();
                    final GuestActivityHost host =
                            new GuestActivityHost(this, gameDir, pkg, loader);
                    guestHost = host;
                    // The engine's native half calls back into the Activity it was
                    // started with, so an instance is required even when the
                    // import metadata carries no launcher Activity.
                    // Activity's constructor allocates a Handler, so the instance
                    // can only be built on a thread that has a Looper: the UI
                    // thread. The session thread then waits for it.
                    String launch = game != null && !game.launcherActivity.isEmpty()
                            ? game.launcherActivity : "";
                    final String launchName = launch;
                    final java.util.concurrent.CountDownLatch created =
                            new java.util.concurrent.CountDownLatch(1);
                    runOnUiThread(() -> {
                        try {
                            host.create(launchName);
                            if (host.isAttached()) {
                                host.attachToFramework();
                                host.onCreate();
                                host.onStart();
                                host.onResume();
                            }
                        } finally {
                            created.countDown();
                        }
                    });
                    created.await(10, java.util.concurrent.TimeUnit.SECONDS);
                }

                guestPrepared = true;
                dispatchSessionIfReady();
            } catch (Throwable t) {
                Log.e(TAG, "guest runtime failed to prepare", t);
                reportSession(UnicornBridge.SESSION_INIT_FAILED);
            }
        }, "zai-bridge-prepare").start();
    }

    /**
     * Starts the native session only once both prerequisites exist: the guest
     * loader/dex and a live host Surface. EGL then binds guest window surfaces
     * to the SurfaceView's ANativeWindow.
     */
    private static boolean hasNativePayload(File payload) {
        if (!payload.isDirectory()) return false;
        File[] libs = payload.listFiles((dir, name) -> name.endsWith("32.so"));
        return libs != null && libs.length > 0;
    }

    /**
     * Legacy Java-only APKs run as guest dex on the host runtime: the loader is
     * positioned on the APK, the Activity is created inside the guest context and
     * its decor view (when the framework produces one) becomes the content view.
     */
    private void launchPureJava(ClassLoader loader, String pkg, String launcher) {
        if (launcher.isEmpty()) {
            Log.w(TAG, "pure-Java import declares no launchable Activity");
            return;
        }
        final String launch = launcher;
        new Thread(() -> {
            GuestActivityHost host = new GuestActivityHost(this, gameDir, pkg, loader);
            guestHost = host;
            runOnUiThread(() -> {
                host.create(launch);
                host.attachToFramework();
                host.onCreate();
                host.onStart();
                host.onResume();
                View decor = host.getDecorView();
                if (decor != null) {
                    setContentView(decor);
                    Log.i(TAG, "pure-Java guest decor attached to the host window");
                } else {
                    status("pure-Java app launched without a window", false);
                }
            });
            Log.i(TAG, "pure-Java import: no ARM32 payload, Unicorn skipped");
        }, "zai-bridge-pure-java").start();
    }

    private void dispatchSessionIfReady() {
        if (pureJavaMode) return;
        synchronized (startLock) {
            if (sessionDispatched || !surfaceReady || !guestPrepared) return;
            sessionDispatched = true;
        }
        new Thread(this::runSession, "zai-bridge-session").start();
    }

    private void runSession() {
        int result = UnicornBridge.SESSION_INIT_FAILED;
        try {
            if (!UnicornBridge.isCoreLoaded()) {
                reportSession(result);
                return;
            }
            String obb = findObb(gameDir);
            String pkg = game != null ? game.packageName : gameDir.getName();
            Log.i(TAG, "starting bridge: dir=" + gameDir.getName()
                    + " package=" + pkg
                    + " obb=" + (obb == null ? "<none>" : new File(obb).getName())
                    + " loader=" + guestLoader);
            Object guestActivity = guestHost != null ? guestHost.getGuest() : null;
            result = UnicornBridge.startSession(gameDir.getAbsolutePath(), obb, pkg,
                    guestLoader, guestActivity);
        } catch (Throwable t) {
            Log.e(TAG, "native session crashed", t);
        }
        reportSession(result);
    }

    /** OBBs are discovered by the host only; they are never shown in the grid. */
    private static String findObb(File gameDir) {
        File obbDir = new File(gameDir, "obb");
        File[] files = obbDir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    private void reportSession(int result) {
        runOnUiThread(() -> onSessionStarted(result));
    }

    private void onSessionStarted(int result) {
        progress.setVisibility(View.GONE);
        if (result == UnicornBridge.SESSION_OK
                || result == UnicornBridge.SESSION_ALREADY_RUNNING
                || result == UnicornBridge.SESSION_PASSTHROUGH) {
            status.setText(R.string.bridge_ready);
            statusWrap.postDelayed(this::fadeStatus, 1500);
        } else {
            status.setText(getString(R.string.bridge_failed, result));
            Log.e(TAG, "bridge refused to start: " + result);
        }
    }

    private void status(String text, boolean keepVisible) {
        if (status == null) return;
        status.setText(text);
        if (!keepVisible) {
            statusWrap.postDelayed(this::fadeStatus, 2500);
        }
    }

    private void fadeStatus() {
        statusWrap.animate()
                .alpha(0f)
                .setDuration(450)
                .withEndAction(() -> statusWrap.setVisibility(View.GONE))
                .start();
    }
}

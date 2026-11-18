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
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import com.ziauddin.zaibridge.core.EglSwapHook;
import com.ziauddin.zaibridge.core.GameOrientation;
import com.ziauddin.zaibridge.core.GuestActivityHost;
import com.ziauddin.zaibridge.core.UnicornBridge;
import com.ziauddin.zaibridge.data.Game;
import com.ziauddin.zaibridge.data.GameRepository;
import com.ziauddin.zaibridge.data.GraphicsSettings;
import com.ziauddin.zaibridge.ui.GraphicsSettingsSheet;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private GraphicsSettings graphics;
    private View quickMenu;
    private TextView quickStats;

    private final Object startLock = new Object();

    /**
     * Surface callbacks are handed to the core from this single background
     * thread, never from the main looper.
     *
     * <p>{@code surfaceChanged} is delivered inside the framework's traversal,
     * and a legacy guest answers it by waiting for its own {@code GLThread} to
     * finish the engine's {@code resize}. Everything that call can touch lives
     * inside the emulator, so doing it here would hold the input dispatcher for
     * as long as the engine takes to load its course, font and HUD data. One
     * ordered queue keeps created -> changed -> destroyed in the right order
     * without any of it running on the UI thread.
     */
    private static final ExecutorService SURFACE_QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zai-bridge-surface");
        t.setDaemon(true);
        return t;
    });

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
        // The direct host has no guest Activity to inherit the manifest from.
        GameOrientation.apply(this, gameDir);

        // Apply this game's upscaler / frame-generation configuration before
        // the first surface exists, so the very first swapped frame already
        // goes through the compositor.
        graphics = GraphicsSettings.forGame(this, gameDir);
        EglSwapHook.configure(graphics.upscaler, graphics.scalePercent,
                graphics.sharpnessPercent, graphics.frameGen);
        installEdgeMenu();
        applyFrameRateHint(graphics);

        String label = getIntent().getStringExtra(EXTRA_GAME_LABEL);
        if (label != null && !label.isEmpty()) {
            status.setText(label + "\n" + getString(R.string.bridge_starting));
        }

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                onSurface(holder.getSurface(), holder.getSurfaceFrame().width(),
                        holder.getSurfaceFrame().height());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                onSurface(holder.getSurface(), width, height);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                status("surface destroyed", false);
                surfaceReady = false;
                SURFACE_QUEUE.execute(() -> UnicornBridge.setSurface(null));
            }
        });

        prepareGuest();
    }

    /**
     * Publishes a surface (and its size) to the core without blocking the UI
     * thread.
     *
     * <p>{@link UnicornBridge#setSurface} only stores the ANativeWindow, but the
     * guest's surface change that follows it is a guest call: the engine's
     * {@code resize} loads every course, font and HUD texture and can take
     * seconds on software GL. Queuing it means the main looper is free within
     * microseconds of the callback, so the input dispatcher can never time out
     * waiting for the engine.
     */
    private void onSurface(final Surface surface, final int width, final int height) {
        if (surface == null || !surface.isValid()) return;
        surfaceReady = true;
        SURFACE_QUEUE.execute(() -> {
            Log.i(TAG, "surface handed to the core off the UI thread: "
                    + width + "x" + height);
            UnicornBridge.setSurface(surface);
            dispatchSessionIfReady();
        });
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
        surfaceReady = false;
        SURFACE_QUEUE.execute(() -> UnicornBridge.setSurface(null));
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

    // ---------------------------------------------------------------------
    // Graphics: live quick menu and per-game configuration
    // ---------------------------------------------------------------------

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * A transparent 18 dp strip on the right edge. Swiping left from it opens
     * the quick graphics panel; only that strip is consumed, so the rest of the
     * screen keeps delivering input to the guest untouched.
     */
    private void installEdgeMenu() {
        FrameLayout root = findViewById(android.R.id.content);
        if (root == null) return;
        View handle = new View(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(18), FrameLayout.LayoutParams.MATCH_PARENT);
        params.gravity = Gravity.END;
        handle.setLayoutParams(params);
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (downX - event.getX() > dp(22)
                                && Math.abs(event.getY() - downY) < dp(90)) {
                            toggleQuickMenu();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        root.addView(handle);
    }

    private void toggleQuickMenu() {
        if (quickMenu == null) quickMenu = buildQuickMenu();
        final boolean show = quickMenu.getVisibility() != View.VISIBLE;
        quickMenu.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) refreshQuickStats();
    }

    private void rebuildQuickMenu() {
        FrameLayout root = findViewById(android.R.id.content);
        if (quickMenu != null && root != null) root.removeView(quickMenu);
        quickMenu = null;
        quickStats = null;
    }

    private View buildQuickMenu() {
        FrameLayout root = findViewById(android.R.id.content);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(12));

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE6111A26);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), 0xFF22303F);
        panel.setBackground(background);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(292), FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.setMargins(0, 0, dp(24), 0);
        panel.setLayoutParams(params);

        TextView title = new TextView(this);
        title.setText(R.string.graphics_quick);
        title.setTextColor(0xFFEAF2FA);
        title.setTextSize(14f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        panel.addView(title);

        panel.addView(quickLabel(R.string.graphics_upscaler));
        RadioGroup upscaler = new RadioGroup(this);
        upscaler.setOrientation(RadioGroup.HORIZONTAL);
        upscaler.addView(quickRadio(getString(R.string.graphics_off),
                graphics.upscaler == GraphicsSettings.UPSCALER_OFF, () -> {
                    graphics.upscaler = GraphicsSettings.UPSCALER_OFF;
                    applyGraphics();
                }));
        upscaler.addView(quickRadio("SGSR",
                graphics.upscaler == GraphicsSettings.UPSCALER_SGSR, () -> {
                    graphics.upscaler = GraphicsSettings.UPSCALER_SGSR;
                    applyGraphics();
                }));
        upscaler.addView(quickRadio("FSR",
                graphics.upscaler == GraphicsSettings.UPSCALER_FSR, () -> {
                    graphics.upscaler = GraphicsSettings.UPSCALER_FSR;
                    applyGraphics();
                }));
        panel.addView(upscaler);

        panel.addView(quickLabel(R.string.graphics_scale));
        RadioGroup scale = new RadioGroup(this);
        scale.setOrientation(RadioGroup.HORIZONTAL);
        scale.addView(quickRadio("50%",
                graphics.scalePercent == GraphicsSettings.SCALE_PERFORMANCE, () -> {
                    graphics.scalePercent = GraphicsSettings.SCALE_PERFORMANCE;
                    applyGraphics();
                }));
        scale.addView(quickRadio("67%",
                graphics.scalePercent == GraphicsSettings.SCALE_BALANCED, () -> {
                    graphics.scalePercent = GraphicsSettings.SCALE_BALANCED;
                    applyGraphics();
                }));
        scale.addView(quickRadio("75%",
                graphics.scalePercent == GraphicsSettings.SCALE_QUALITY, () -> {
                    graphics.scalePercent = GraphicsSettings.SCALE_QUALITY;
                    applyGraphics();
                }));
        scale.addView(quickRadio("100%",
                graphics.scalePercent == GraphicsSettings.SCALE_NATIVE, () -> {
                    graphics.scalePercent = GraphicsSettings.SCALE_NATIVE;
                    applyGraphics();
                }));
        panel.addView(scale);

        panel.addView(quickLabel(R.string.graphics_sharpness));
        SeekBar sharpness = new SeekBar(this);
        sharpness.setMax(100);
        sharpness.setProgress(graphics.sharpnessPercent);
        sharpness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) graphics.sharpnessPercent = progress;
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                applyGraphics();
            }
        });
        panel.addView(sharpness);

        CheckBox frameGen = new CheckBox(this);
        frameGen.setText(R.string.graphics_frame_gen_2x);
        frameGen.setTextSize(11.5f);
        frameGen.setTextColor(0xFFEAF2FA);
        frameGen.setChecked(graphics.frameGen == GraphicsSettings.FRAME_GEN_2X);
        frameGen.setButtonTintList(
                android.content.res.ColorStateList.valueOf(0xFF3BE0F2));
        frameGen.setOnCheckedChangeListener((button, checked) -> {
            graphics.frameGen = checked ? GraphicsSettings.FRAME_GEN_2X
                    : GraphicsSettings.FRAME_GEN_OFF;
            applyGraphics();
        });
        panel.addView(frameGen);

        Button more = new Button(this);
        more.setText(R.string.graphics_all);
        more.setAllCaps(false);
        more.setTextSize(11f);
        more.setOnClickListener(v -> openFullGraphicsSheet());
        panel.addView(more);

        quickStats = new TextView(this);
        quickStats.setTextSize(9.5f);
        quickStats.setTextColor(0xFF8FA3B8);
        quickStats.setMaxLines(3);
        panel.addView(quickStats);

        if (root != null) root.addView(panel);
        return panel;
    }

    private TextView quickLabel(int textRes) {
        TextView label = new TextView(this);
        label.setText(textRes);
        label.setTextSize(9.5f);
        label.setAllCaps(true);
        label.setTextColor(0xFF8FA3B8);
        label.setPadding(0, dp(9), 0, dp(1));
        return label;
    }

    private RadioButton quickRadio(String text, boolean checked, Runnable onPick) {
        RadioButton button = new RadioButton(this);
        button.setText(text);
        button.setTextSize(10.5f);
        button.setTextColor(0xFFEAF2FA);
        button.setChecked(checked);
        button.setButtonTintList(
                android.content.res.ColorStateList.valueOf(0xFF3BE0F2));
        button.setPadding(0, 0, dp(4), 0);
        button.setOnClickListener(v -> onPick.run());
        return button;
    }

    private void applyGraphics() {
        EglSwapHook.configure(graphics.upscaler, graphics.scalePercent,
                graphics.sharpnessPercent, graphics.frameGen);
        applyFrameRateHint(graphics);
        try {
            GraphicsSettings.saveGame(gameDir, graphics);
        } catch (Exception e) {
            Log.w(TAG, "graphics settings were not saved", e);
        }
        refreshQuickStats();
    }

    private void openFullGraphicsSheet() {
        GraphicsSettingsSheet.show(this,
                (game != null ? game.label + " — " : "")
                        + getString(R.string.graphics_title),
                graphics, true, (settings, useGlobal) -> {
                    if (useGlobal) {
                        GraphicsSettings.clearGame(gameDir);
                        graphics = GraphicsSettings.global(this);
                    } else {
                        graphics = settings.copy();
                    }
                    applyGraphics();
                    rebuildQuickMenu();
                });
    }

    private void refreshQuickStats() {
        if (quickStats == null) return;
        String stats = EglSwapHook.stats();
        if (stats.length() > 260) stats = stats.substring(0, 260) + "…";
        quickStats.setText(stats);
    }

    /**
     * Asks the display to run at 120 Hz for 2x frame generation (and 60 Hz
     * otherwise) where the panel and the framework support it. Frames are still
     * presented at the guest's own rate; this only unlocks the scan-out rate.
     */
    private void applyFrameRateHint(GraphicsSettings settings) {
        if (surfaceView == null || settings == null || Build.VERSION.SDK_INT < 30) return;
        final float rate = settings.frameGen == GraphicsSettings.FRAME_GEN_2X ? 120f : 60f;
        try {
            Surface surface = surfaceView.getHolder().getSurface();
            if (surface != null && surface.isValid()) {
                surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                Log.i(TAG, "display pacing hint: " + rate + " Hz");
            }
        } catch (Throwable t) {
            Log.w(TAG, "setFrameRate not honoured on this display: " + t);
        }
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
            applyFrameRateHint(graphics);
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

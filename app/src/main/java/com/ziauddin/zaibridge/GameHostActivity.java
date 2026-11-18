package com.ziauddin.zaibridge;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.ziauddin.zaibridge.core.BridgeRuntime;
import com.ziauddin.zaibridge.core.EglSwapHook;
import com.ziauddin.zaibridge.core.GameOrientation;
import com.ziauddin.zaibridge.core.GuestActivityHost;
import com.ziauddin.zaibridge.core.UnicornBridge;
import com.ziauddin.zaibridge.data.Game;
import com.ziauddin.zaibridge.data.GameRepository;
import com.ziauddin.zaibridge.data.GraphicsSettings;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dalvik.system.DexClassLoader;

/**
 * The execution surface.
 *
 * <p>Nothing about this activity is windowed: it uses the NoTitleBar.Fullscreen
 * theme, clears the decor, and asks the SystemUI for
 * {@link View#SYSTEM_UI_FLAG_IMMERSIVE_STICKY}. On top of the guest surface it
 * hosts two pieces of bridge chrome: a draggable telemetry pill and a
 * slide-out Zai-Scale drawer with a live vanilla/Zai-Scale compare wipe.
 */
public class GameHostActivity extends Activity {

    private static final String TAG = "ZaiBridge";

    public static final String EXTRA_GAME_DIR = "com.ziauddin.zaibridge.extra.GAME_DIR";
    public static final String EXTRA_GAME_LABEL = "com.ziauddin.zaibridge.extra.GAME_LABEL";

    private View statusWrap;
    private TextView status;
    private ProgressBar progress;
    private SurfaceView surfaceView;
    private TextView telemetry;

    private File gameDir;
    private Game game;
    private GuestActivityHost guestHost;

    private volatile ClassLoader guestLoader;
    private volatile boolean guestPrepared;
    private volatile boolean surfaceReady;
    private boolean sessionDispatched;
    private volatile boolean pureJavaMode;
    private volatile int sessionGeneration = -1;

    private GraphicsSettings graphics;

    private View drawer;
    private TextView drawerStats;
    private int drawerWidth;
    private boolean drawerOpen;

    private final Handler telemetryHandler = new Handler(Looper.getMainLooper());
    private final Runnable telemetryTick = new Runnable() {
        @Override
        public void run() {
            updateTelemetry();
            telemetryHandler.postDelayed(this, 800);
        }
    };

    private final Object startLock = new Object();

    /**
     * Surface callbacks are handed to the core from this single background
     * thread, never from the main looper.
     */
    private static final ExecutorService SURFACE_QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zai-bridge-surface");
        t.setDaemon(true);
        return t;
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        BridgeRuntime.ensureInitialized(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_game_host);

        statusWrap = findViewById(R.id.status_wrap);
        status = findViewById(R.id.tv_status);
        progress = findViewById(R.id.progress);
        surfaceView = findViewById(R.id.game_surface);
        telemetry = findViewById(R.id.tv_telemetry);

        AspectRatioFrameLayout aspect = findViewById(R.id.game_aspect);
        boolean portrait = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        aspect.setAspectRatio(portrait ? 3f / 4f : 16f / 9f);

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
        GameOrientation.apply(this, gameDir);

        graphics = GraphicsSettings.forGame(this, gameDir);
        EglSwapHook.configure(graphics);
        installOverlay();
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
        if (telemetry.getVisibility() == View.VISIBLE) {
            telemetryHandler.removeCallbacks(telemetryTick);
            telemetryHandler.post(telemetryTick);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (guestHost != null) guestHost.onStart();
    }

    @Override
    protected void onPause() {
        telemetryHandler.removeCallbacks(telemetryTick);
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
        telemetryHandler.removeCallbacks(telemetryTick);
        if (guestHost != null) guestHost.onDestroy();
        surfaceReady = false;
        // The guest is gone; release its EGL surface, the compositor textures
        // and the Unicorn address space so the next game can start cleanly.
        final int generation = sessionGeneration;
        SURFACE_QUEUE.execute(() -> {
            UnicornBridge.setSurface(null);
            // Never tear down a session another host Activity claimed while
            // this one was finishing.
            if (generation >= 0) UnicornBridge.endSession(generation);
        });
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
        if (guestHost != null) guestHost.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        AspectRatioFrameLayout aspect = findViewById(R.id.game_aspect);
        if (aspect != null) {
            boolean portrait = config.orientation
                    == android.content.res.Configuration.ORIENTATION_PORTRAIT;
            aspect.setAspectRatio(portrait ? 3f / 4f : 16f / 9f);
        }
        // The drawer's width is derived from the old window; rebuild on demand
        // instead of animating a stale panel.
        if (drawerOpen) closeDrawer();
        applyFrameRateHint(graphics);
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        super.onBackPressed();
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

                if (loader != null && !hasNativePayload) {
                    pureJavaMode = true;
                    launchPureJava(loader, pkgName, launcher);
                    guestPrepared = true;
                    return;
                }

                if (loader != null) {
                    final GuestActivityHost host =
                            new GuestActivityHost(this, gameDir, pkgName, loader);
                    guestHost = host;
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

    private static boolean hasNativePayload(File payload) {
        if (!payload.isDirectory()) return false;
        File[] libs = payload.listFiles((dir, name) -> name.endsWith("32.so"));
        return libs != null && libs.length > 0;
    }

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
            sessionGeneration = UnicornBridge.sessionGeneration();
        } catch (Throwable t) {
            Log.e(TAG, "native session crashed", t);
        }
        reportSession(result);
    }

    // ---------------------------------------------------------------------
    // Telemetry pill and slide-out drawer
    // ---------------------------------------------------------------------

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void installOverlay() {
        FrameLayout root = findViewById(android.R.id.content);
        if (root == null) return;

        telemetry.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int startLeft;
            private int startTop;
            private long downAt;
            private boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                FrameLayout.LayoutParams params =
                        (FrameLayout.LayoutParams) view.getLayoutParams();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startLeft = params.leftMargin;
                        startTop = params.topMargin;
                        downAt = System.currentTimeMillis();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        int maxLeft = Math.max(0, root.getWidth() - view.getWidth() - dp(8));
                        int maxTop = Math.max(0, root.getHeight() - view.getHeight() - dp(8));
                        params.leftMargin = clampInt(startLeft + (int) dx, dp(4), maxLeft);
                        params.topMargin = clampInt(startTop + (int) dy, dp(4), maxTop);
                        view.setLayoutParams(params);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved && System.currentTimeMillis() - downAt < 280) {
                            toggleDrawer();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });

        View handle = findViewById(R.id.view_edge_handle);
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downRawX = event.getRawX();
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (downRawX - event.getRawX() > dp(22)) openDrawer();
                    return true;
                }
                return true;
            }
        });
    }

    private void updateTelemetry() {
        if (telemetry == null || graphics == null) return;
        String stats = EglSwapHook.compositorStats();
        int guestFps = (int) Math.round(number(stats, "guestFps"));
        int outputFps = (int) Math.round(number(stats, "outputFps"));
        String text = guestFps > 0
                ? String.format(Locale.US, "%d → %d FPS · %s MFG · %s %d%%",
                guestFps, outputFps, graphics.frameGenName(),
                graphics.upscalerName(), graphics.scalePercent)
                : String.format(Locale.US, "%s MFG · %s %d%%",
                graphics.frameGenName(), graphics.upscalerName(), graphics.scalePercent);
        telemetry.setText(text);
        if (drawerStats != null) drawerStats.setText(stats);
    }

    private static double number(String stats, String key) {
        int at = stats.indexOf(key + "=");
        if (at < 0) return 0;
        int start = at + key.length() + 1;
        int end = start;
        while (end < stats.length()) {
            char c = stats.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.') ++end;
            else break;
        }
        try {
            return Double.parseDouble(stats.substring(start, end));
        } catch (Exception e) {
            return 0;
        }
    }

    private void toggleDrawer() {
        if (drawerOpen) closeDrawer();
        else openDrawer();
    }

    private void openDrawer() {
        if (graphics == null) return;
        if (drawer == null) drawer = buildDrawer();
        if (drawer.getParent() == null) {
            FrameLayout root = findViewById(android.R.id.content);
            if (root == null) return;
            root.addView(drawer);
        }
        drawerOpen = true;
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(drawerWidth);
        drawer.animate().translationX(0f).setDuration(230)
                .setInterpolator(new DecelerateInterpolator()).start();
        // Show the split comparison while the drawer is up.
        EglSwapHook.setCompare(0.5f);
        updateTelemetry();
    }

    private void closeDrawer() {
        if (drawer == null) return;
        drawerOpen = false;
        EglSwapHook.setCompare(0f);
        drawer.animate().translationX(drawerWidth).setDuration(190)
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    private View buildDrawer() {
        FrameLayout root = findViewById(android.R.id.content);
        drawerWidth = Math.min(dp(330), root == null ? dp(330) : root.getWidth() - dp(24));

        FrameLayout panel = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                drawerWidth, FrameLayout.LayoutParams.MATCH_PARENT);
        params.gravity = Gravity.END;
        panel.setLayoutParams(params);
        panel.setBackgroundResource(R.drawable.bg_drawer);
        panel.setClickable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        panel.addView(scroll);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(18), dp(18), dp(22));
        scroll.addView(body);

        // Title row ------------------------------------------------------
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.graphics_quick);
        title.setTextColor(0xFFE8EEF7);
        title.setTextSize(15f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(title);
        ImageButton close = new ImageButton(this);
        close.setBackgroundResource(R.drawable.bg_icon_button);
        close.setImageResource(R.drawable.ic_close);
        close.setContentDescription(getString(R.string.close));
        close.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams closeParams =
                new LinearLayout.LayoutParams(dp(34), dp(34));
        close.setLayoutParams(closeParams);
        close.setOnClickListener(v -> closeDrawer());
        titleRow.addView(close);
        body.addView(titleRow);

        // Preset chips ---------------------------------------------------
        body.addView(sectionLabel(R.string.preset));
        body.addView(chipRow(new String[][]{
                {getString(R.string.preset_off), "0"},
                {getString(R.string.preset_fsr_perf), "1"},
                {getString(R.string.preset_fsr_quality), "2"},
                {getString(R.string.preset_dlss_ultra), "3"},
                {getString(R.string.preset_dlss_overdrive), "4"}
        }, String.valueOf(graphics.preset), value -> {
            int preset = Integer.parseInt(value);
            GraphicsSettings next = GraphicsSettings.ofPreset(preset);
            graphics = next;
            applyGraphics();
            rebuildDrawer();
        }));

        // Render scale ---------------------------------------------------
        body.addView(sliderRow(R.string.graphics_scale, 50, 100,
                graphics.scalePercent, value -> {
                    graphics.scalePercent = value;
                    graphics.markCustom();
                    applyGraphics();
                }));

        body.addView(sliderRow(R.string.graphics_sharpness, 0, 100,
                graphics.sharpnessPercent, value -> {
                    graphics.sharpnessPercent = value;
                    graphics.markCustom();
                    applyGraphics();
                }));

        body.addView(sliderRow(R.string.graphics_neural, 0, 100,
                graphics.neuralPercent, value -> {
                    graphics.neuralPercent = value;
                    graphics.markCustom();
                    applyGraphics();
                }));

        // Frame generation ----------------------------------------------
        body.addView(sectionLabel(R.string.graphics_frame_gen));
        body.addView(chipRow(new String[][]{
                {getString(R.string.graphics_off), "0"},
                {"2x", "2"},
                {"3x", "3"},
                {"4x", "4"}
        }, String.valueOf(graphics.frameGen), value -> {
            graphics.frameGen = Integer.parseInt(value);
            if (graphics.frameGen != GraphicsSettings.FRAME_GEN_OFF
                    && graphics.upscaler == GraphicsSettings.UPSCALER_OFF) {
                graphics.upscaler = GraphicsSettings.UPSCALER_DLSS;
                graphics.temporal = 1;
            }
            graphics.markCustom();
            applyGraphics();
            rebuildDrawer();
        }));

        // Compare wipe ---------------------------------------------------
        body.addView(sectionLabel(R.string.compare_wipe));
        LinearLayout compareLabels = new LinearLayout(this);
        compareLabels.setOrientation(LinearLayout.HORIZONTAL);
        TextView vanilla = new TextView(this);
        vanilla.setText(R.string.compare_vanilla);
        vanilla.setTextColor(0xFF8A99AD);
        vanilla.setTextSize(10f);
        vanilla.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView zaiscale = new TextView(this);
        zaiscale.setText(R.string.compare_zaiscale);
        zaiscale.setTextColor(0xFF00E5FF);
        zaiscale.setTextSize(10f);
        zaiscale.setGravity(Gravity.END);
        zaiscale.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        compareLabels.addView(vanilla);
        compareLabels.addView(zaiscale);
        body.addView(compareLabels);

        SeekBar compare = new SeekBar(this);
        compare.setMax(100);
        compare.setProgress(50);
        compare.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) {
                    EglSwapHook.setCompare(value <= 2 ? 0f
                            : Math.min(0.98f, value / 100f));
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { }

            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        body.addView(compare);

        // Live counters --------------------------------------------------
        drawerStats = new TextView(this);
        drawerStats.setTextSize(8.5f);
        drawerStats.setTextColor(0xFF5F6E80);
        drawerStats.setMaxLines(8);
        drawerStats.setPadding(0, dp(12), 0, dp(10));
        drawerStats.setText(EglSwapHook.compositorStats());
        body.addView(drawerStats);

        Button exit = new Button(this);
        exit.setText(R.string.exit_game);
        exit.setAllCaps(false);
        exit.setTextSize(13f);
        exit.setTextColor(0xFFFFB300);
        GradientDrawable exitBackground = new GradientDrawable();
        exitBackground.setColor(0x1AFFB300);
        exitBackground.setCornerRadius(dp(14));
        exitBackground.setStroke(dp(1), 0x66FFB300);
        exit.setBackground(exitBackground);
        exit.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
        exit.setOnClickListener(v -> finish());
        body.addView(exit);

        panel.addView(scroll);
        return panel;
    }

    private void rebuildDrawer() {
        FrameLayout root = findViewById(android.R.id.content);
        if (root == null) return;
        if (drawer != null) root.removeView(drawer);
        drawer = buildDrawer();
        drawer.setVisibility(View.VISIBLE);
        root.addView(drawer);
        updateTelemetry();
    }

    private interface ValueSink {
        void accept(String value);
    }

    private interface IntSink {
        void accept(int value);
    }

    private TextView sectionLabel(int textRes) {
        TextView label = new TextView(this);
        label.setText(textRes);
        label.setTextSize(9f);
        label.setAllCaps(true);
        label.setLetterSpacing(0.12f);
        label.setTextColor(0xFF8A99AD);
        label.setPadding(0, dp(16), 0, dp(6));
        return label;
    }

    private View chipRow(String[][] chips, String selected, ValueSink sink) {
        ScrollView scroll = new ScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] chip : chips) {
            TextView view = new TextView(this);
            view.setText(chip[0]);
            view.setTextSize(10.5f);
            boolean on = chip[1].equals(selected);
            view.setTextColor(on ? 0xFF00E5FF : 0xFF8A99AD);
            GradientDrawable background = new GradientDrawable();
            background.setColor(on ? 0x2600E5FF : 0x141A24);
            background.setCornerRadius(dp(13));
            background.setStroke(dp(1), on ? 0x9900E5FF : 0xFF263042);
            view.setBackground(background);
            view.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dp(7));
            view.setLayoutParams(params);
            view.setOnClickListener(v -> sink.accept(chip[1]));
            row.addView(view);
        }
        scroll.addView(row);
        return scroll;
    }

    private View sliderRow(int labelRes, int min, int max, int value, IntSink sink) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(9f);
        label.setAllCaps(true);
        label.setLetterSpacing(0.12f);
        label.setTextColor(0xFF8A99AD);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView badge = new TextView(this);
        badge.setText(String.format(Locale.US, "%d%%", value));
        badge.setTextSize(10.5f);
        badge.setTextColor(0xFF00E5FF);
        badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(label);
        header.addView(badge);
        wrap.addView(header);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, value - min));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                badge.setText(String.format(Locale.US, "%d%%", progress + min));
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                sink.accept(seekBar.getProgress() + min);
            }
        });
        wrap.addView(bar);
        return wrap;
    }

    private void applyGraphics() {
        EglSwapHook.configure(graphics);
        applyFrameRateHint(graphics);
        try {
            GraphicsSettings.saveGame(gameDir, graphics);
        } catch (Exception e) {
            Log.w(TAG, "graphics settings were not saved", e);
        }
    }

    private void applyFrameRateHint(GraphicsSettings settings) {
        if (surfaceView == null || settings == null || Build.VERSION.SDK_INT < 30) return;
        final float rate = settings.frameGen != GraphicsSettings.FRAME_GEN_OFF ? 120f : 60f;
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

    private static int clampInt(int value, int lo, int hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }

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
            telemetry.setVisibility(View.VISIBLE);
            updateTelemetry();
            telemetryHandler.removeCallbacks(telemetryTick);
            telemetryHandler.post(telemetryTick);
        } else {
            status.setText(getString(R.string.bridge_failed, result));
            Log.e(TAG, "bridge refused to start: " + result);
        }
    }

    private void status(String text, boolean keepVisible) {
        if (status == null) return;
        status.setText(text);
        if (!keepVisible) statusWrap.postDelayed(this::fadeStatus, 2500);
    }

    private void fadeStatus() {
        statusWrap.animate()
                .alpha(0f)
                .setDuration(450)
                .withEndAction(() -> statusWrap.setVisibility(View.GONE))
                .start();
    }
}

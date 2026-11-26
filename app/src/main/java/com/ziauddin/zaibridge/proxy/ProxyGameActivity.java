package com.ziauddin.zaibridge.proxy;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ziauddin.zaibridge.BuildConfig;
import com.ziauddin.zaibridge.core.AnrWatchdog;
import com.ziauddin.zaibridge.core.BridgeInstrumentation;
import com.ziauddin.zaibridge.core.BridgeRuntime;
import com.ziauddin.zaibridge.core.CrashGuard;
import com.ziauddin.zaibridge.core.GameOrientation;
import com.ziauddin.zaibridge.core.GuestActivityHost;
import com.ziauddin.zaibridge.core.GuestContext;
import com.ziauddin.zaibridge.core.GuestLauncher;
import com.ziauddin.zaibridge.core.InGameOverlay;
import com.ziauddin.zaibridge.data.Game;
import com.ziauddin.zaibridge.data.GameRepository;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The `:guest`-process launch Activity.
 *
 * <p>Operates in two non-blocking phases inside the isolated `:guest` process:
 * <ol>
 *   <li><b>Async Bootstrap Splash (`EXTRA_BOOTSTRAP_READY == false`):</b>
 *       Renders a dark loading view immediately (&lt; 16 ms), runs all heavy APK/dex,
 *       manifest/resource, and Unicorn ARM32 ELF/constructor setup off the Main UI Thread,
 *       initializes manifest {@code ContentProvider}s + custom {@code Application}, and
 *       transitions with zero animation to phase 2.</li>
 *   <li><b>Real Guest Activity (`EXTRA_BOOTSTRAP_READY == true`):</b>
 *       {@link BridgeInstrumentation#newActivity} substitutes the imported game's real
 *       {@link Activity}. If OEM restrictions ever prevent instrumentation substitution,
 *       this class tries direct {@link GuestActivityHost} lifecycle &amp; input forwarding
 *       before presenting a diagnostic launch report.</li>
 * </ol>
 */
public class ProxyGameActivity extends Activity {

    private static final String TAG = "ZaiBridge";
    private static final ExecutorService BOOTSTRAP_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zai-guest-bootstrap");
        t.setDaemon(true);
        return t;
    });

    private GuestContext boundGuestContext;
    private boolean initializingGuestContext;
    private GuestActivityHost fallbackHost;
    private TextView splashStatusView;
    private volatile boolean destroyedOrFinishing;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
    }

    private GuestContext ensureGuestContext() {
        if (boundGuestContext != null) return boundGuestContext;
        if (initializingGuestContext) return null;
        Intent intent = getIntent();
        String dir = intent == null ? null : intent.getStringExtra(GuestLauncher.EXTRA_GAME_DIR);
        if (dir == null || dir.isEmpty()) return null;
        File gameDir = new File(dir);
        if (!gameDir.isDirectory()) return null;

        initializingGuestContext = true;
        try {
            Game game = GameRepository.load(gameDir);
            String pkg = game != null && game.packageName != null ? game.packageName : gameDir.getName();
            String cls = intent.getStringExtra(GuestLauncher.EXTRA_GUEST_ACTIVITY);
            if ((cls == null || cls.isEmpty()) && game != null) {
                cls = game.launcherActivity;
            }
            boolean isNative64 = GuestLauncher.isNative64Game(game, gameDir);
            boundGuestContext = GuestLauncher.getOrCreateGuestContext(
                    super.getApplicationContext(), gameDir, pkg, cls, isNative64, super.getClassLoader());
            return boundGuestContext;
        } catch (Throwable t) {
            Log.w(TAG, "ProxyGameActivity could not pre-bind GuestContext: " + t.getMessage());
            return null;
        } finally {
            initializingGuestContext = false;
        }
    }

    @Override
    public Resources getResources() {
        GuestContext gc = boundGuestContext;
        return gc != null ? gc.getResources() : super.getResources();
    }

    @Override
    public AssetManager getAssets() {
        GuestContext gc = boundGuestContext;
        return gc != null ? gc.getAssets() : super.getAssets();
    }

    @Override
    public Resources.Theme getTheme() {
        GuestContext gc = boundGuestContext;
        return gc != null ? gc.getTheme() : super.getTheme();
    }

    @Override
    public ClassLoader getClassLoader() {
        GuestContext gc = boundGuestContext;
        return gc != null ? gc.getClassLoader() : super.getClassLoader();
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        GuestContext gc = boundGuestContext;
        return gc != null ? gc.getApplicationInfo() : super.getApplicationInfo();
    }

    @Override
    public PackageManager getPackageManager() {
        GuestContext gc = boundGuestContext;
        return gc != null ? gc.getPackageManager() : super.getPackageManager();
    }

    @Override
    public File getFilesDir() {
        GuestContext gc = boundGuestContext;
        return gc != null ? gc.getFilesDir() : super.getFilesDir();
    }

    @Override
    public File getCacheDir() {
        GuestContext gc = boundGuestContext;
        return gc != null ? gc.getCacheDir() : super.getCacheDir();
    }

    @Override
    public File getExternalFilesDir(String type) {
        GuestContext gc = boundGuestContext;
        return gc != null ? gc.getExternalFilesDir(type) : super.getExternalFilesDir(type);
    }

    @Override
    public File getObbDir() {
        GuestContext gc = boundGuestContext;
        return gc != null ? gc.getObbDir() : super.getObbDir();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        final Context appCtx = super.getApplicationContext();
        AnrWatchdog.install(appCtx);
        AnrWatchdog.setForeground(true);
        CrashGuard.installMainLooperShield(appCtx);
        final Intent intent = getIntent();
        final boolean bootstrapReady = intent != null
                && intent.getBooleanExtra(GuestLauncher.EXTRA_BOOTSTRAP_READY, false);
        if (bootstrapReady) {
            ensureGuestContext();
        }
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        final String dir = intent == null ? null : intent.getStringExtra(GuestLauncher.EXTRA_GAME_DIR);
        final String label = intent == null ? null : intent.getStringExtra(GuestLauncher.EXTRA_GAME_LABEL);

        if (dir == null || dir.isEmpty()) {
            showErrorReport(label, dir, "Proxy launch started without EXTRA_GAME_DIR");
            return;
        }

        // Apply guest orientation ONLY inside the :guest process; MainActivity never rotates.
        GameOrientation.apply(this, new File(dir));

        if (!bootstrapReady) {
            showAsyncBootstrapSplash(label != null && !label.isEmpty() ? label : new File(dir).getName());
            runAsyncBootstrap(intent, label, dir);
            return;
        }

        // Phase 2 fallback: Instrumentation was unavailable or failed to substitute the Activity.
        if (tryHostViaGuestActivityHost(dir, label)) {
            return;
        }

        String reason = GuestLauncher.getLastLaunchError();
        if (reason == null || reason.isEmpty()) {
            reason = "BridgeInstrumentation.isInstalled=" + BridgeInstrumentation.isInstalled()
                    + "\nGuest summary: " + GuestLauncher.describe()
                    + "\nHost ABI: " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");
        }
        showErrorReport(label, dir, reason);
    }

    private void showAsyncBootstrapSplash(String titleLabel) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF07090E);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        root.addView(spinner, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView title = new TextView(this);
        title.setText(titleLabel);
        title.setTextColor(0xFFE8EEF7);
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(18);
        root.addView(title, tLp);

        splashStatusView = new TextView(this);
        splashStatusView.setText("Preparing isolated guest container…");
        splashStatusView.setTextColor(0xFF00E5FF);
        splashStatusView.setTextSize(12.5f);
        splashStatusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(8);
        root.addView(splashStatusView, sLp);

        setContentView(root);
    }

    private void updateSplashStatus(String msg) {
        if (destroyedOrFinishing || splashStatusView == null || msg == null) return;
        runOnUiThread(() -> {
            if (!destroyedOrFinishing && !isFinishing() && !isDestroyed() && splashStatusView != null) {
                splashStatusView.setText(msg);
            }
        });
    }

    private void runAsyncBootstrap(Intent origIntent, String label, String dir) {
        final Context appCtx = super.getApplicationContext();
        BOOTSTRAP_POOL.execute(() -> {
            if (!BridgeInstrumentation.isInstalled()) {
                BridgeInstrumentation.install(appCtx);
            }
            boolean ok = GuestLauncher.bootstrapOffUiThread(
                    appCtx, origIntent, this::updateSplashStatus);
            runOnUiThread(() -> {
                if (destroyedOrFinishing || isFinishing() || isDestroyed()) return;
                if (!ok) {
                    showErrorReport(label, dir, GuestLauncher.getLastLaunchError());
                    return;
                }
                if (splashStatusView != null) {
                    splashStatusView.setText("Starting application components…");
                }
                try {
                    GuestLauncher.ensureGuestProvidersAndApplication(appCtx);
                } catch (Throwable t) {
                    Log.w(TAG, "Guest provider/app pre-init warning: " + t.getMessage());
                }

                Intent launchReady = new Intent(origIntent);
                launchReady.setComponent(new ComponentName(appCtx.getPackageName(),
                        ProxyGameActivity.class.getName()));
                launchReady.putExtra(GuestLauncher.EXTRA_BOOTSTRAP_READY, true);
                launchReady.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(launchReady);
                finish();
                overridePendingTransition(0, 0);
            });
        });
    }

    private boolean tryHostViaGuestActivityHost(String dir, String label) {
        ClassLoader loader = GuestLauncher.getGuestLoader();
        String cls = GuestLauncher.getGuestClass();
        String pkg = GuestLauncher.getGuestPackage();
        if (loader == null || cls == null || cls.isEmpty() || pkg == null || pkg.isEmpty()) {
            return false;
        }
        try {
            GuestActivityHost host = new GuestActivityHost(this, new File(dir), pkg, loader);
            if (!host.create(cls) || !host.isAttached()) {
                return false;
            }
            host.attachToFramework();
            host.onCreate();
            View decor = host.getDecorView();
            if (decor == null) {
                return false;
            }
            fallbackHost = host;
            setContentView(decor);
            decor.setBackgroundColor(Color.TRANSPARENT);
            InGameOverlay.attach(this, super.getApplicationContext(), new File(dir), pkg, cls);
            host.onStart();
            host.onResume();
            host.onWindowFocusChanged(hasWindowFocus());
            Log.i(TAG, "ProxyGameActivity direct host fallback active for " + (label != null ? label : pkg));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Direct GuestActivityHost fallback unavailable: " + t.getMessage());
            fallbackHost = null;
            return false;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (fallbackHost != null) fallbackHost.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AnrWatchdog.setForeground(true);
        if (fallbackHost != null) fallbackHost.onResume();
    }

    @Override
    protected void onPause() {
        if (fallbackHost != null) fallbackHost.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (fallbackHost != null) fallbackHost.onStop();
        super.onStop();
        if (!isFinishing()) {
            AnrWatchdog.setForeground(false);
        }
    }

    @Override
    protected void onDestroy() {
        destroyedOrFinishing = true;
        if (fallbackHost != null) {
            fallbackHost.onDestroy();
            fallbackHost = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (fallbackHost != null) fallbackHost.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (fallbackHost != null) fallbackHost.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (fallbackHost != null && fallbackHost.dispatchKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (fallbackHost != null && fallbackHost.dispatchTouchEvent(ev)) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void showErrorReport(String label, String dir, String reason) {
        final String body = (reason != null && !reason.isEmpty())
                ? reason : "Unknown guest initialization failure";
        final String fullReport = "Zai-Bridge v" + BuildConfig.VERSION_NAME + " Launch Failure Report\n"
                + "Game: " + (label != null ? label : "<unknown>") + "\n"
                + "Dir: " + (dir != null ? dir : "<none>") + "\n"
                + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Instrumentation: " + BridgeInstrumentation.isInstalled() + "\n\n"
                + body;

        Log.e(TAG, "proxy Activity visible: " + fullReport);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A0C10);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Zai-Bridge could not launch this game");
        title.setTextColor(0xFFFFB454);
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = pad / 2;
        scrollLp.bottomMargin = pad / 2;

        TextView detail = new TextView(this);
        detail.setTextColor(0xFFE2E8F0);
        detail.setTextSize(12f);
        detail.setTypeface(Typeface.MONOSPACE);
        detail.setTextIsSelectable(true);
        detail.setText(fullReport);
        scroll.addView(detail);
        root.addView(scroll, scrollLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);

        Button copyBtn = new Button(this);
        copyBtn.setText("Copy Error Report");
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Zai-Bridge Error Report", fullReport));
                Toast.makeText(this, "Error report copied", Toast.LENGTH_SHORT).show();
            }
        });
        buttons.addView(copyBtn);

        Button closeBtn = new Button(this);
        closeBtn.setText("Exit Game");
        closeBtn.setOnClickListener(v -> GuestLauncher.exitGuestProcess(this));
        buttons.addView(closeBtn);

        root.addView(buttons);
        setContentView(root);
    }

    private int dp(float dip) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dip, super.getResources().getDisplayMetrics()));
    }
}

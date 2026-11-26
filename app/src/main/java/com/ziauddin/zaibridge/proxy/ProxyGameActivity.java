package com.ziauddin.zaibridge.proxy;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ziauddin.zaibridge.core.BridgeInstrumentation;
import com.ziauddin.zaibridge.core.BridgeRuntime;
import com.ziauddin.zaibridge.core.GameOrientation;
import com.ziauddin.zaibridge.core.GuestContext;
import com.ziauddin.zaibridge.core.GuestLauncher;
import com.ziauddin.zaibridge.data.Game;
import com.ziauddin.zaibridge.data.GameRepository;

import java.io.File;

/**
 * The real, manifest-declared Activity that a bridge launch starts.
 *
 * <p>Normally this class is never instantiated: {@code
 * BridgeInstrumentation.newActivity} recognises the intent, loads the imported
 * game's Activity and returns that instead, so the framework attaches and
 * displays the guest with a genuine window and input channel.
 *
 * <p>It ALSO overrides {@link #getResources()}, {@link #getAssets()}, {@link #getTheme()},
 * {@link #getPackageManager()}, and storage methods so even fallback paths never crash
 * with {@code Resources$NotFoundException: #0x0}, retries once if the process restarted
 * without {@link BridgeInstrumentation} installed, and presents a detailed copyable report
 * if hosting genuinely failed.
 */
public class ProxyGameActivity extends Activity {

    private static final String TAG = "ZaiBridge";
    private static final String EXTRA_PROXY_RETRY =
            "com.ziauddin.zaibridge.extra.PROXY_RETRY";

    private GuestContext boundGuestContext;
    private boolean initializingGuestContext;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
    }

    @NullableContext
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

    @interface NullableContext {}

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
        ensureGuestContext();
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        final Intent intent = getIntent();
        final String dir = intent == null ? null
                : intent.getStringExtra(GuestLauncher.EXTRA_GAME_DIR);
        final String label = intent == null ? null
                : intent.getStringExtra(GuestLauncher.EXTRA_GAME_LABEL);

        // If the OS recreated this task cleanly without going through BridgeApplication's
        // normal attach path or before instrumentation took hold, install instrumentation
        // and re-launch once automatically so the guest Activity class is substituted.
        if (dir != null && !dir.isEmpty()
                && !BridgeInstrumentation.isInstalled()
                && !intent.getBooleanExtra(EXTRA_PROXY_RETRY, false)) {
            BridgeInstrumentation.install(getApplicationContext());
            BridgeRuntime.ensureInitialized(getApplicationContext());
            if (BridgeInstrumentation.isInstalled()) {
                Intent retry = new Intent(intent);
                retry.putExtra(EXTRA_PROXY_RETRY, true);
                startActivity(retry);
                finish();
                return;
            }
        }

        // The guest's declared orientation has to be in effect before displaying error UI.
        GameOrientation.apply(this, dir == null ? null : new File(dir));

        String reason = GuestLauncher.getLastLaunchError();
        if (reason == null || reason.isEmpty()) {
            reason = "BridgeInstrumentation.isInstalled=" + BridgeInstrumentation.isInstalled()
                    + "\nGuest summary: " + GuestLauncher.describe()
                    + "\nHost ABI: " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");
        }
        final String fullReport = "Zai-Bridge Launch Failure Report\n"
                + "Game: " + (label != null ? label : "<unknown>") + "\n"
                + "Dir: " + (dir != null ? dir : "<none>") + "\n"
                + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Instrumentation: " + BridgeInstrumentation.isInstalled() + "\n\n"
                + reason;

        Log.e(TAG, "proxy Activity visible: " + fullReport);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A0C10);
        int pad = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 20, getResources().getDisplayMetrics()));
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
        closeBtn.setText("Close");
        closeBtn.setOnClickListener(v -> finish());
        buttons.addView(closeBtn);

        root.addView(buttons);
        setContentView(root);
    }
}

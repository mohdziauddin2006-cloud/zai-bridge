package com.ziauddin.zaibridge;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

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
 * {@link View#SYSTEM_UI_FLAG_IMMERSIVE_STICKY} so the bars stay gone until the
 * user deliberately summons them. The game's dex is loaded through a
 * {@link DexClassLoader} positioned on the ingested APK, and the ARM32 payload
 * is handed to the Unicorn core, which maps it from private storage.
 */
public class GameHostActivity extends Activity {

    private static final String TAG = "ZaiBridge";

    public static final String EXTRA_GAME_DIR = "com.ziauddin.zaibridge.extra.GAME_DIR";
    public static final String EXTRA_GAME_LABEL = "com.ziauddin.zaibridge.extra.GAME_LABEL";

    private View statusWrap;
    private TextView status;
    private ProgressBar progress;

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
        enterImmersive();

        String dir = getIntent().getStringExtra(EXTRA_GAME_DIR);
        if (dir == null) {
            finish();
            return;
        }

        String label = getIntent().getStringExtra(EXTRA_GAME_LABEL);
        if (label != null && !label.isEmpty()) {
            status.setText(label + "\n" + getString(R.string.bridge_starting));
        }

        startGuest(new File(dir));
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Any transient bar the user swipes in must be evicted as soon as the
        // window regains focus, otherwise the game stays letterboxed.
        if (hasFocus) enterImmersive();
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

    private void startGuest(File gameDir) {
        new Thread(() -> {
            int result;
            try {
                result = openRuntime(gameDir);
            } catch (Throwable t) {
                Log.e(TAG, "guest runtime failed to open", t);
                result = UnicornBridge.SESSION_INIT_FAILED;
            }
            final int code = result;
            runOnUiThread(() -> onSessionStarted(code));
        }, "zai-bridge-session").start();
    }

    private int openRuntime(File gameDir) {
        if (!UnicornBridge.isCoreLoaded()) {
            return UnicornBridge.SESSION_INIT_FAILED;
        }

        File apk = new File(gameDir, "game.apk");
        File payload = new File(gameDir, "payload");
        if (!apk.isFile() || !payload.isDirectory()) {
            Log.e(TAG, "incomplete import: " + gameDir.getAbsolutePath());
            return UnicornBridge.SESSION_NO_PAYLOAD;
        }

        File optimized = new File(getCodeCacheDir(), "guest-" + gameDir.getName());
        if (!optimized.exists() && !optimized.mkdirs()) {
            optimized = getCodeCacheDir();
        }

        // The guest dex runs on this process's ART; only its native half needs
        // the translation core. The payload directory doubles as the guest's
        // native library path so libraries can locate each other.
        DexClassLoader loader = new DexClassLoader(apk.getAbsolutePath(),
                optimized.getAbsolutePath(), payload.getAbsolutePath(), getClassLoader());
        Thread.currentThread().setContextClassLoader(loader);

        Game game = GameRepository.load(gameDir);
        if (game != null && !game.launcherActivity.isEmpty()) {
            try {
                Class<?> cls = Class.forName(game.launcherActivity, false, loader);
                Log.i(TAG, "guest launcher class resolved: " + cls.getName());
            } catch (Throwable t) {
                // Not fatal at this stage: the class usually becomes
                // resolvable once the guest application initialises.
                Log.w(TAG, "guest launcher class not resolvable yet", t);
            }
        }

        String obb = findObb(gameDir);
        Log.i(TAG, "starting bridge for " + gameDir.getName()
                + (obb == null ? " (no OBB)" : " with " + new File(obb).getName()));
        return UnicornBridge.startSession(gameDir.getAbsolutePath(), obb, loader);
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

    private void onSessionStarted(int result) {
        progress.setVisibility(View.GONE);
        if (result == UnicornBridge.SESSION_OK
                || result == UnicornBridge.SESSION_ALREADY_RUNNING) {
            status.setText(R.string.bridge_ready);
            statusWrap.postDelayed(this::fadeStatus, 1500);
        } else {
            status.setText(getString(R.string.bridge_failed, result));
            Log.e(TAG, "bridge refused to start: " + result);
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

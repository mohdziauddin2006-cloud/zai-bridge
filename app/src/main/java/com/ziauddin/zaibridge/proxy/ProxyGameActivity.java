package com.ziauddin.zaibridge.proxy;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.ziauddin.zaibridge.core.GameOrientation;
import com.ziauddin.zaibridge.core.GuestLauncher;

import java.io.File;

/**
 * The real, manifest-declared Activity that a bridge launch starts.
 *
 * <p>Normally this class is never instantiated: {@code
 * BridgeInstrumentation.newActivity} recognises the intent, loads the imported
 * game's Activity and returns that instead, so the framework attaches and
 * displays the guest with a genuine window and input channel.
 *
 * <p>It only becomes visible when the guest cannot be hosted (missing dex,
 * unreadable manifest, emulation core unavailable). Showing the reason beats a
 * silent black screen, so the fallback UI lives here rather than in the hook.
 */
public class ProxyGameActivity extends Activity {

    private static final String TAG = "ZaiBridge";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        String dir = getIntent() == null ? null
                : getIntent().getStringExtra(GuestLauncher.EXTRA_GAME_DIR);
        String label = getIntent() == null ? null
                : getIntent().getStringExtra(GuestLauncher.EXTRA_GAME_LABEL);

        // The guest's declared orientation has to be in effect before this
        // window's surface is created, or a landscape game is letterboxed into
        // a portrait surface for its whole lifetime.
        GameOrientation.apply(this, dir == null ? null : new File(dir));

        Log.e(TAG, "proxy Activity is visible: guest was not attached (dir=" + dir + ")");

        TextView view = new TextView(this);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(0xFFFFB454);
        view.setTextSize(16f);
        view.setPadding(64, 64, 64, 64);
        view.setText("Zai-Bridge could not host this import.\n\n"
                + (label == null ? "" : label + "\n")
                + (dir == null ? "no game directory" : new File(dir).getName())
                + "\n\nSee logcat tag ZaiBridge / ARM64_WRAPPER.");
        setContentView(view);
    }
}

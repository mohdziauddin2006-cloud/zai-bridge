package com.ziauddin.zaibridge.core;

import android.app.Application;
import android.content.Context;
import android.util.Log;

/**
 * Process entry point.
 *
 * <p>{@code attachBaseContext} is kept completely lightweight, fast, and crash-proof.
 * No native libraries are loaded, no internal hidden API reflections are forced,
 * and no framework instrumentation is mutated here.
 *
 * <p>All heavy hooks and translations are deferred to {@link BridgeRuntime#ensureInitialized(Context)},
 * which is called defensively only when a guest game is actually launched.
 */
public class BridgeApplication extends Application {

    private static final String TAG = "ZaiBridge";

    private static Context instance;

    public static Context get() {
        return instance;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        instance = base;

        // 1. Install Crash Guard immediately to protect against any unexpected
        //    Java or JNI aborts across all OEM variants (Android 8–16).
        CrashGuard.install(base);

        Log.i(TAG, "BridgeApplication initialized cleanly; native hooks decoupled from startup.");
    }
}

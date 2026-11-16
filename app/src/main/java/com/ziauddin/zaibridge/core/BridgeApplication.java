package com.ziauddin.zaibridge.core;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;

/**
 * Process entry point.
 *
 * <p>{@code attachBaseContext} is the earliest point where framework state can
 * be touched, and it runs before any Activity exists — exactly where the hidden
 * API exemption and the {@code Instrumentation} substitution have to happen.
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

        // 1. Android 9+ filters non-SDK access; lift it before touching
        //    ActivityThread or AssetManager internals.
        HiddenApi.exemptAll();

        // 2. Replace ActivityThread.mInstrumentation with the bridge hook, so
        //    every proxy launch is redirected to the guest Activity.
        BridgeInstrumentation.install(base);

        // 3. Put the frame counter on the Java EGL boundary as well, so a
        //    GLSurfaceView game and a pure NDK game are accounted the same way.
        EglSwapHook.install();

        // 4. Unpack the bundled 32-bit runtime on a worker so first launch is
        //    not blocked by ~200 MB of asset I/O.
        new Thread(() -> {
            File runtime = RuntimeAssets.ensure(base);
            UnicornBridge.setRuntimeSysroot(runtime.getAbsolutePath());
            Log.i(TAG, "runtime bundle ready at " + runtime
                    + " (host ABI " + Build.SUPPORTED_ABIS[0]
                    + ", guest " + UnicornBridge.GUEST_ABI + ")");
        }, "zai-runtime-assets").start();
    }
}

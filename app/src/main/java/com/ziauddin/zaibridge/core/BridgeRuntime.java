package com.ziauddin.zaibridge.core;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;

/**
 * Lazy, crash-proof virtualization and compatibility runtime initialization.
 *
 * <p>Decoupled from {@code Application.attachBaseContext} so that the main
 * launcher and library browser open immediately without touching internal
 * framework fields or native drivers on restrictive OEM ROMs (XOS, OneUI,
 * HyperOS, ColorOS, etc.).
 */
public final class BridgeRuntime {

    private static final String TAG = "BridgeRuntime";

    private static volatile boolean initialized = false;
    private static boolean instrumentationInstalled = false;
    private static boolean eglHooksInstalled = false;

    private BridgeRuntime() {
    }

    /**
     * Lazily initializes all virtualization hooks and unpacked runtime libraries.
     * Safe to call multiple times from any thread.
     */
    public static synchronized void ensureInitialized(Context context) {
        if (initialized) return;
        final Context appContext = context.getApplicationContext();

        Log.i(TAG, "Initializing bridge compatibility runtime (lazy entry)...");

        // 1. Android 9+ hidden API exemptions (defensive)
        try {
            boolean exempt = HiddenApi.exemptAll();
            Log.i(TAG, "Hidden API exemptions status: " + exempt);
        } catch (Throwable t) {
            Log.w(TAG, "Hidden API exemption failed or restricted by OEM: " + t);
        }

        // 2. ActivityThread.mInstrumentation virtualization hook (isolated to :guest)
        if (BridgeApplication.isGuestProcess(appContext)) {
            try {
                instrumentationInstalled = BridgeInstrumentation.install(appContext);
                Log.i(TAG, "ActivityThread.mInstrumentation hook: "
                        + (instrumentationInstalled ? "ACTIVE" : "FALLBACK"));
            } catch (Throwable t) {
                Log.w(TAG, "ActivityThread.mInstrumentation hook blocked by OEM ROM: " + t);
                instrumentationInstalled = false;
            }
        } else {
            Log.i(TAG, "ActivityThread.mInstrumentation skipped in main launcher process");
        }

        // 3. EGL10 / EGL14 native swap hooks & Zai-Scale compositor (defensive)
        try {
            eglHooksInstalled = EglSwapHook.install();
            Log.i(TAG, "EGL10/EGL14 swap hooks: " + (eglHooksInstalled ? "ACTIVE" : "STANDBY"));
        } catch (Throwable t) {
            Log.w(TAG, "EGL swap hooks registration deferred/unavailable: " + t);
            eglHooksInstalled = false;
        }

        // 4. Ensure multi-ABI sysroot runtime bundle is ready
        try {
            File runtime = RuntimeAssets.ensure(appContext);
            UnicornBridge.setRuntimeSysroot(runtime.getAbsolutePath());
            Log.i(TAG, "Bundled sysroot staged at: " + runtime
                    + " (host " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown")
                    + ", guest " + UnicornBridge.GUEST_ABI + ")");
        } catch (Throwable t) {
            Log.e(TAG, "Runtime assets staging failed: " + t);
        }

        initialized = true;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean isInstrumentationInstalled() {
        return instrumentationInstalled;
    }

    public static boolean isEglHooksInstalled() {
        return eglHooksInstalled;
    }
}

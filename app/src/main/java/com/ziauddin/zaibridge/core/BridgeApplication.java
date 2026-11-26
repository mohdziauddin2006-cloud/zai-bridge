package com.ziauddin.zaibridge.core;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Process entry point.
 *
 * <p>{@code attachBaseContext} stays lightweight and fast in the main launcher
 * process (`MainActivity`, `CrashActivity`, `IngestionService`). Only when
 * executing inside the isolated `:guest` container process are hidden-API
 * exemptions and {@link BridgeInstrumentation} installed, keeping the launcher
 * UI 100% immune to guest freezes, crashes, or {@code ActivityThread} mutation.
 */
public class BridgeApplication extends Application {

    private static final String TAG = "ZaiBridge";
    public static final String GUEST_PROCESS_SUFFIX = ":guest";

    private static Context instance;

    public static Context get() {
        return instance;
    }

    public static String getCurrentProcessName(Context context) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                String name = Application.getProcessName();
                if (name != null && !name.isEmpty()) return name;
            } catch (Throwable ignored) {
            }
        }
        try (FileInputStream fis = new FileInputStream("/proc/self/cmdline")) {
            byte[] buf = new byte[128];
            int n = fis.read(buf);
            if (n > 0) {
                int len = 0;
                while (len < n && buf[len] != 0) len++;
                if (len > 0) {
                    return new String(buf, 0, len, StandardCharsets.UTF_8).trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return context != null ? context.getPackageName() : "";
    }

    public static boolean isGuestProcess(Context context) {
        String proc = getCurrentProcessName(context);
        return proc != null && proc.endsWith(GUEST_PROCESS_SUFFIX);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        instance = base;

        // Install CrashGuard in all processes so `:guest` crashes route cleanly
        // to CrashActivity running in the main launcher process.
        CrashGuard.install(base);

        if (isGuestProcess(base)) {
            HiddenApi.exemptAll();
            boolean inst = BridgeInstrumentation.install(base);
            Log.i(TAG, "BridgeApplication(:guest) attached; instrumentation=" + inst);
        } else {
            Log.i(TAG, "BridgeApplication(main) initialized cleanly; launcher process isolated.");
        }
    }
}

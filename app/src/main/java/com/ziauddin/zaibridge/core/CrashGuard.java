package com.ziauddin.zaibridge.core;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.ziauddin.zaibridge.ui.CrashActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Built-in process-wide crash safety guard.
 * Catches any unhandled Java or JNI exception, persists the stack trace to
 * `filesDir/last_crash.txt`, and launches the dark diagnostic screen.
 */
public final class CrashGuard {

    private static final String TAG = "ZaiBridgeCrashGuard";
    private static boolean installed = false;

    private CrashGuard() {
    }

    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;

        final Context targetCtx = (context != null && context.getApplicationContext() != null)
                ? context.getApplicationContext() : context;
        final Thread.UncaughtExceptionHandler originalHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                Log.e(TAG, "Uncaught exception on thread: " + thread.getName(), throwable);

                Context ctx = targetCtx;
                if (ctx == null) {
                    ctx = BridgeApplication.get();
                }

                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                String stackTrace = sw.toString();

                String crashReport = "Thread: " + thread.getName() + " (id=" + thread.getId() + ")\n"
                        + "OS: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                        + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")\n"
                        + "Supported ABIs: " + String.join(", ", Build.SUPPORTED_ABIS) + "\n\n"
                        + "Exception: " + throwable.getClass().getName() + ": " + throwable.getMessage() + "\n\n"
                        + stackTrace;

                // Write to filesDir/last_crash.txt
                if (ctx != null) {
                    try {
                        File crashFile = new File(ctx.getFilesDir(), "last_crash.txt");
                        try (FileOutputStream fos = new FileOutputStream(crashFile)) {
                            fos.write(crashReport.getBytes(StandardCharsets.UTF_8));
                            fos.flush();
                        }
                        Log.i(TAG, "Crash report recorded to " + crashFile.getAbsolutePath());
                    } catch (Throwable writeErr) {
                        Log.e(TAG, "Failed writing last_crash.txt", writeErr);
                    }

                    // Launch CrashActivity
                    try {
                        Intent intent = new Intent(ctx, CrashActivity.class);
                        intent.putExtra(CrashActivity.EXTRA_CRASH_INFO, stackTrace);
                        intent.putExtra(CrashActivity.EXTRA_ERROR_MESSAGE, throwable.getClass().getSimpleName()
                                + (throwable.getMessage() != null ? ": " + throwable.getMessage() : ""));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        ctx.startActivity(intent);
                    } catch (Throwable launchErr) {
                        Log.e(TAG, "Failed starting CrashActivity", launchErr);
                    }
                }

                // Gracefully kill the crashing process
                Process.killProcess(Process.myPid());
                System.exit(10);
            } catch (Throwable fatal) {
                Log.e(TAG, "Fatal error inside CrashGuard", fatal);
                if (originalHandler != null) {
                    originalHandler.uncaughtException(thread, throwable);
                }
            }
        });

        Log.i(TAG, "CrashGuard installed successfully");
    }
}

package com.ziauddin.zaibridge.core;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
 * Catches unhandled exceptions, isolates non-fatal third-party container IPC
 * SDK failures inside `:guest`, persists fatal traces to
 * {@code filesDir/last_crash.txt}, and launches {@link CrashActivity}.
 */
public final class CrashGuard {

    private static final String TAG = "ZaiBridgeCrashGuard";
    private static boolean installed = false;
    private static boolean looperShieldInstalled = false;

    private CrashGuard() {
    }

    private static boolean hasThirdPartySdkFrame(Throwable throwable) {
        int depth = 0;
        for (Throwable cur = throwable; cur != null && depth < 16; cur = cur.getCause(), depth++) {
            for (StackTraceElement el : cur.getStackTrace()) {
                String cn = el.getClassName();
                if (cn.contains("dynamite")
                        || cn.startsWith("com.google.android.gms.")
                        || cn.startsWith("com.google.firebase.")
                        || cn.startsWith("com.google.android.datatransport.")
                        || cn.startsWith("com.google.android.play.core.")
                        || cn.startsWith("com.applovin.")
                        || cn.startsWith("com.safedk.")
                        || cn.startsWith("com.vungle.")
                        || cn.startsWith("com.ironsource.")
                        || cn.startsWith("com.facebook.ads.")
                        || cn.startsWith("com.facebook.")
                        || cn.startsWith("com.unity3d.services.")
                        || cn.startsWith("com.unity3d.ads.")
                        || cn.startsWith("com.adjust.")
                        || cn.startsWith("com.appsflyer.")
                        || cn.startsWith("com.smaato.")
                        || cn.startsWith("io.bidmachine.")
                        || cn.startsWith("androidx.work.")
                        || cn.startsWith("m7.")
                        || cn.startsWith("m84.")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isRecoverableGuestContainerException(Throwable throwable) {
        int depth = 0;
        for (Throwable cur = throwable; cur != null && depth < 16; cur = cur.getCause(), depth++) {
            String msg = cur.getMessage();
            if (cur instanceof SecurityException) {
                if (msg != null && (msg.contains("Unknown calling package name")
                        || msg.contains("not allowed to send as package")
                        || msg.contains("Permission Denial")
                        || msg.contains("does not belong to")
                        || msg.contains("AppOps"))) {
                    return true;
                }
                if (hasThirdPartySdkFrame(cur)) {
                    return true;
                }
            }
            if (msg != null && (msg.contains("Unknown calling package name")
                    || msg.contains("not allowed to send as package"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Identifies non-fatal exceptions thrown on third-party Ad/Analytics/GMS worker
     * threads inside the isolated {@code :guest} container (for example, AdMob/Dynamite
     * {@code AdWorker(ClientSingle)} throwing {@code Looper must not be null} when initialized
     * inside a proxy container).
     */
    public static boolean isThirdPartySdkWorkerException(String threadName, Throwable throwable) {
        if (isRecoverableGuestContainerException(throwable)) {
            return true;
        }
        if (throwable == null) return false;
        boolean sdkThread = threadName != null && (threadName.startsWith("AdWorker")
                || threadName.startsWith("Gass")
                || threadName.startsWith("Measurement")
                || threadName.startsWith("AppLovin")
                || threadName.startsWith("UnityAds")
                || threadName.startsWith("GoogleApiHandler")
                || threadName.startsWith("Dynamite"));
        return sdkThread || hasThirdPartySdkFrame(throwable);
    }

    private static File resolveHostFilesDir(Context ctx) {
        Context bridge = BridgeApplication.get();
        if (bridge != null && bridge.getFilesDir() != null) {
            return bridge.getFilesDir();
        }
        if (ctx instanceof GuestContext) {
            Context hostBase = ((GuestContext) ctx).getHostBaseContext();
            if (hostBase != null && hostBase.getFilesDir() != null) {
                return hostBase.getFilesDir();
            }
        }
        return ctx != null ? ctx.getFilesDir() : null;
    }

    private static void writeTextFile(File dir, String name, String content) {
        if (dir == null || content == null) return;
        try {
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File f = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Throwable writeErr) {
            Log.e(TAG, "Failed writing " + name, writeErr);
        }
    }

    /**
     * Keeps the isolated `:guest` Main Looper alive if a third-party GMS /
     * Measurement / Ads Handler callback throws a UID/package Binder
     * {@link SecurityException} (such as {@code Unknown calling package name}).
     */
    public static synchronized void installMainLooperShield(Context context) {
        if (looperShieldInstalled) return;
        final Context targetCtx = resolveHostAppContext(context);
        if (targetCtx != null && !BridgeApplication.isGuestProcess(targetCtx)) {
            return;
        }
        looperShieldInstalled = true;
        final File hostFiles = resolveHostFilesDir(targetCtx);
        new Handler(Looper.getMainLooper()).post(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Looper.loop();
                    return;
                } catch (Throwable t) {
                    if (isRecoverableGuestContainerException(t)) {
                        Log.w(TAG, "Contained non-fatal container IPC exception on main looper: "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
                        StringWriter sw = new StringWriter();
                        t.printStackTrace(new PrintWriter(sw));
                        writeTextFile(hostFiles, "last_sdk_guard.txt", sw.toString());
                        continue;
                    }
                    throw t;
                }
            }
        });
        Log.i(TAG, "Main-looper container IPC shield armed in :guest");
    }

    private static Context resolveHostAppContext(Context context) {
        Context bridge = BridgeApplication.get();
        if (bridge != null) {
            return bridge.getApplicationContext() != null ? bridge.getApplicationContext() : bridge;
        }
        return (context != null && context.getApplicationContext() != null)
                ? context.getApplicationContext() : context;
    }

    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;

        final Context targetCtx = resolveHostAppContext(context);
        final Thread.UncaughtExceptionHandler originalHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                Context ctx = targetCtx != null ? targetCtx : BridgeApplication.get();
                boolean isMain = Looper.getMainLooper() != null
                        && thread == Looper.getMainLooper().getThread();
                if (!isMain && ctx != null && BridgeApplication.isGuestProcess(ctx)
                        && isThirdPartySdkWorkerException(thread.getName(), throwable)) {
                    Log.w(TAG, "Suppressed non-fatal SDK worker exception on thread '"
                            + thread.getName() + "': " + throwable.getMessage());
                    StringWriter sw = new StringWriter();
                    throwable.printStackTrace(new PrintWriter(sw));
                    writeTextFile(resolveHostFilesDir(ctx), "last_sdk_guard.txt", sw.toString());
                    return;
                }

                Log.e(TAG, "Uncaught exception on thread: " + thread.getName(), throwable);

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

                // Write to host filesDir/last_crash.txt
                if (ctx != null) {
                    writeTextFile(resolveHostFilesDir(ctx), "last_crash.txt", crashReport);

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

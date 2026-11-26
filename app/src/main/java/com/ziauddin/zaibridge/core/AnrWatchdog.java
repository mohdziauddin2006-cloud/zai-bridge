package com.ziauddin.zaibridge.core;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.ziauddin.zaibridge.BuildConfig;
import com.ziauddin.zaibridge.ui.CrashActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * Monitors the isolated {@code :guest} Main UI Looper so no main-thread stall
 * ever fails silently. When the looper stops responding for more than 4 seconds,
 * this watchdog captures the exact main-thread stack trace and the last 100
 * {@code ZaiBridge}/{@code ARM64_WRAPPER} logcat lines to {@code files/last_anr.txt}
 * and opens {@link CrashActivity}.
 */
public final class AnrWatchdog {

    private static final String TAG = "ZaiBridgeAnrWatchdog";
    private static final long TICK_INTERVAL_MS = 500L;
    private static final long STALL_THRESHOLD_MS = 4000L;
    private static final int LOGCAT_TAIL_LINES = 100;

    private static volatile boolean installed;
    private static volatile boolean foreground = true;
    private static volatile long lastHeartbeatUptimeMs;
    private static volatile boolean reportedCurrentStall;
    private static volatile File hostFilesDir;

    private AnrWatchdog() {
    }

    public static synchronized void install(Context context) {
        if (installed) return;
        final Context resolvedHost = resolveHostAppContext(context);
        if (resolvedHost != null && resolvedHost.getFilesDir() != null) {
            hostFilesDir = resolvedHost.getFilesDir();
        }
        if (resolvedHost != null && !BridgeApplication.isGuestProcess(resolvedHost)) {
            return;
        }
        installed = true;
        foreground = true;
        lastHeartbeatUptimeMs = SystemClock.uptimeMillis();

        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final Runnable beatTask = () -> {
            lastHeartbeatUptimeMs = SystemClock.uptimeMillis();
            reportedCurrentStall = false;
        };

        Thread watcher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                mainHandler.post(beatTask);
                SystemClock.sleep(TICK_INTERVAL_MS);

                if (!foreground || Debug.isDebuggerConnected()) {
                    lastHeartbeatUptimeMs = SystemClock.uptimeMillis();
                    continue;
                }

                final long stalledMs = SystemClock.uptimeMillis() - lastHeartbeatUptimeMs;
                if (stalledMs >= STALL_THRESHOLD_MS && !reportedCurrentStall) {
                    reportedCurrentStall = true;
                    onMainThreadStall(resolvedHost, stalledMs);
                }
            }
        }, "zai-anr-watchdog");
        watcher.setDaemon(true);
        watcher.start();
        Log.i(TAG, "AnrWatchdog armed in :guest (threshold=" + STALL_THRESHOLD_MS + "ms)");
    }

    public static void setForeground(boolean isForeground) {
        foreground = isForeground;
        if (isForeground) {
            lastHeartbeatUptimeMs = SystemClock.uptimeMillis();
            reportedCurrentStall = false;
        }
    }

    private static Context resolveHostAppContext(Context context) {
        Context bridge = BridgeApplication.get();
        if (bridge != null) {
            return bridge.getApplicationContext() != null ? bridge.getApplicationContext() : bridge;
        }
        if (context instanceof GuestContext) {
            Context hostBase = ((GuestContext) context).getHostBaseContext();
            if (hostBase != null) {
                return hostBase.getApplicationContext() != null ? hostBase.getApplicationContext() : hostBase;
            }
        }
        return context != null && context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
    }

    private static void onMainThreadStall(Context hostCtx, long stalledMs) {
        String report = buildAnrReport(stalledMs);
        Log.e(TAG, "ANR Watchdog detected main-thread stall (" + stalledMs + "ms):\n" + report);

        File dir = hostFilesDir;
        if (dir == null && hostCtx != null) {
            dir = hostCtx.getFilesDir();
        }
        if (dir != null) {
            try {
                if (!dir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                }
                File out = new File(dir, "last_anr.txt");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(report.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
                Log.i(TAG, "ANR report written to " + out.getAbsolutePath());
            } catch (Throwable writeErr) {
                Log.e(TAG, "Failed writing last_anr.txt", writeErr);
            }
        }

        if (hostCtx != null) {
            try {
                Intent intent = new Intent(hostCtx, CrashActivity.class);
                intent.putExtra(CrashActivity.EXTRA_CRASH_INFO, report);
                intent.putExtra(CrashActivity.EXTRA_ERROR_MESSAGE,
                        "Main Thread Stalled (" + stalledMs + " ms) · ANR Watchdog");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                hostCtx.startActivity(intent);
            } catch (Throwable launchErr) {
                Log.e(TAG, "Failed launching CrashActivity from AnrWatchdog", launchErr);
            }
        }
    }

    private static String buildAnrReport(long stalledMs) {
        Thread mainThread = Looper.getMainLooper().getThread();
        StackTraceElement[] trace = mainThread.getStackTrace();
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());

        StringBuilder sb = new StringBuilder();
        sb.append("=== ZAI-BRIDGE ANR WATCHDOG REPORT ===\n");
        sb.append("Timestamp: ").append(ts).append('\n');
        sb.append("App Version: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("Stall Duration: ").append(stalledMs).append(" ms\n");
        sb.append("Process: ").append(BridgeApplication.getCurrentProcessName(BridgeApplication.get())).append('\n');
        sb.append("Guest: ").append(GuestLauncher.describe()).append('\n');
        sb.append("OS: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n\n");

        sb.append("--- Main Thread (").append(mainThread.getName())
                .append(", state=").append(mainThread.getState()).append(") Stack Trace ---\n");
        for (StackTraceElement el : trace) {
            sb.append("    at ").append(el).append('\n');
        }

        sb.append("\n--- Last ").append(LOGCAT_TAIL_LINES)
                .append(" ZaiBridge / ARM64_WRAPPER Log Lines ---\n");
        sb.append(captureRecentLogs());
        return sb.toString();
    }

    private static String captureRecentLogs() {
        Process proc = null;
        try {
            proc = new ProcessBuilder(
                    "logcat", "-d", "-v", "time", "-t", String.valueOf(LOGCAT_TAIL_LINES),
                    "-s", "ZaiBridge", "ARM64_WRAPPER", "ZaiBridgeCrashGuard",
                    "ZaiBridgeGuestCtx", "ZaiBridgeAnrWatchdog",
                    "AndroidRuntime", "PowerVR", "libEGL", "DEBUG")
                    .redirectErrorStream(true)
                    .start();

            Deque<String> ring = new ArrayDeque<>(LOGCAT_TAIL_LINES);
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (ring.size() >= LOGCAT_TAIL_LINES) {
                        ring.removeFirst();
                    }
                    ring.addLast(line);
                }
            }
            if (ring.isEmpty()) {
                return "(no matching logcat lines captured)\n";
            }
            StringBuilder out = new StringBuilder();
            for (String l : ring) {
                out.append(l).append('\n');
            }
            return out.toString();
        } catch (Throwable t) {
            return "(logcat capture unavailable: " + t.getMessage() + ")\n";
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}

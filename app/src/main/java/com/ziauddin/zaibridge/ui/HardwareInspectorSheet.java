package com.ziauddin.zaibridge.ui;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.ziauddin.zaibridge.R;
import com.ziauddin.zaibridge.core.EglSwapHook;
import com.ziauddin.zaibridge.core.SoCProfiler;
import com.ziauddin.zaibridge.core.UnicornBridge;
import com.ziauddin.zaibridge.data.GraphicsSettings;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Interactive Hardware &amp; SoC Inspector Sheet opened from the launcher's
 * live hardware telemetry pill. Displays probed SoC tier, GPU renderer, CPU
 * topology, RAM, ABI, 16 KB page-size readiness, and one-tap recommended
 * global Zai-Scale Ultra tuning.
 */
public final class HardwareInspectorSheet {

    private static final ExecutorService PROBE_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zai-bridge-hw-inspect");
        t.setDaemon(true);
        return t;
    });

    private HardwareInspectorSheet() {
    }

    public static void show(Activity activity, Runnable onGlobalApplied) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.Theme_ZaiBridge);
        View content = activity.getLayoutInflater()
                .inflate(R.layout.sheet_hardware_inspector, null);
        dialog.setContentView(content);
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);

        TextView tierBadge = content.findViewById(R.id.tv_hw_tier_badge);
        TextView socView = content.findViewById(R.id.tv_hw_soc);
        TextView gpuView = content.findViewById(R.id.tv_hw_gpu);
        TextView metricsView = content.findViewById(R.id.tv_hw_metrics);
        TextView recProfileView = content.findViewById(R.id.tv_hw_rec_profile);
        TextView recDescView = content.findViewById(R.id.tv_hw_rec_desc);
        MaterialButton applyBtn = content.findViewById(R.id.btn_hw_apply_global);
        ImageButton closeBtn = content.findViewById(R.id.btn_close_hw);

        closeBtn.setOnClickListener(v -> dialog.dismiss());
        applyBtn.setEnabled(false);

        Handler mainHandler = new Handler(Looper.getMainLooper());
        PROBE_POOL.execute(() -> {
            SoCProfiler.Profile p = SoCProfiler.get(activity);
            GraphicsSettings rec = p.recommendedSettings();
            String primaryAbi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
            String runtime = UnicornBridge.isPassthrough()
                    ? "Native 32-bit Passthrough + PLT/GOT Hooks"
                    : "Native64 Direct + Unicorn ARM32 Core";
            String metrics = String.format(Locale.US,
                    "CPU: %d cores @ %.2f GHz\n"
                            + "RAM: %.1f GB · ABI: %s · Android %s (API %d)\n"
                            + "16 KB Page Alignment: COMPLIANT (ELF max-page-size=16384)\n"
                            + "Runtime: %s",
                    p.cpuCores, p.maxClockMhz / 1000.0,
                    p.totalRamMb / 1024.0, primaryAbi, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                    runtime);

            mainHandler.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                tierBadge.setText(p.tierLabel.toUpperCase(Locale.US));
                socView.setText(p.socName + " (" + p.vendor + ")");
                gpuView.setText(p.gpuName);
                metricsView.setText(metrics);
                recProfileView.setText(rec.shortLabel() + " · RCAS " + rec.sharpnessPercent + "% · Neural " + rec.neuralPercent + "%");
                recDescView.setText(p.tierDescription != null && !p.tierDescription.isEmpty()
                        ? p.tierDescription
                        : "Automatically calibrated for your device's GPU shader throughput and memory bandwidth.");
                applyBtn.setEnabled(true);
                applyBtn.setOnClickListener(v -> {
                    GraphicsSettings.saveGlobal(activity, rec);
                    EglSwapHook.configure(rec);
                    Toast.makeText(activity,
                            activity.getString(R.string.soc_applied_toast, p.tier),
                            Toast.LENGTH_SHORT).show();
                    if (onGlobalApplied != null) onGlobalApplied.run();
                    dialog.dismiss();
                });
            });
        });

        dialog.show();
    }
}

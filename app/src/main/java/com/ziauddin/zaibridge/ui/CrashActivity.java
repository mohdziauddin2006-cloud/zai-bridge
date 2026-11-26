package com.ziauddin.zaibridge.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.ziauddin.zaibridge.MainActivity;
import com.ziauddin.zaibridge.R;
import com.ziauddin.zaibridge.core.SoCProfiler;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Obsidian Glass Crash Diagnostic Guard presented if any unhandled Java/JNI
 * exception occurs. Displays Android API, detected SoC/GPU, ABI, exception
 * summary, selectable monospace stack trace, Copy Diagnostic Report, and
 * Restart App actions.
 */
public class CrashActivity extends AppCompatActivity {

    public static final String EXTRA_CRASH_INFO = "extra_crash_info";
    public static final String EXTRA_ERROR_MESSAGE = "extra_error_message";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);

        TextView tvError = findViewById(R.id.tv_crash_error);
        TextView tvDevice = findViewById(R.id.tv_device_info);
        TextView tvStack = findViewById(R.id.tv_stack_trace);
        Button btnCopy = findViewById(R.id.btn_copy);
        Button btnRestart = findViewById(R.id.btn_restart);

        String crashInfo = getIntent().getStringExtra(EXTRA_CRASH_INFO);
        String errorMessage = getIntent().getStringExtra(EXTRA_ERROR_MESSAGE);

        if (crashInfo == null || crashInfo.isEmpty()) {
            crashInfo = readLastCrashFile();
        }

        final String bodyText = crashInfo != null ? crashInfo : errorMessage;
        final String baseDeviceHeader = "Zai-Bridge v4.0.0 · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")\n"
                + "ABI: " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");

        String initialDeviceInfo = baseDeviceHeader + "\nSoC / GPU: " + Build.HARDWARE;
        tvDevice.setText(initialDeviceInfo);
        tvError.setText(errorMessage != null && !errorMessage.isEmpty()
                ? errorMessage : "Fatal unhandled runtime exception");
        tvStack.setText(crashInfo != null && !crashInfo.isEmpty()
                ? crashInfo : "No stack trace recorded.");

        final StringBuilder fullReportHolder = new StringBuilder(
                initialDeviceInfo + "\n\n" + bodyText);

        new Thread(() -> {
            try {
                SoCProfiler.Profile profile = SoCProfiler.get(this);
                String socLine = profile.socName + " · " + profile.gpuName
                        + " (" + profile.tierLabel + ")";
                String updatedInfo = baseDeviceHeader + "\nSoC / GPU: " + socLine;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    tvDevice.setText(updatedInfo);
                    fullReportHolder.setLength(0);
                    fullReportHolder.append(updatedInfo).append("\n\n").append(bodyText);
                });
            } catch (Throwable ignored) {
            }
        }, "zai-crash-soc").start();

        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(
                        "Zai-Bridge Diagnostic Report", fullReportHolder.toString()));
                Toast.makeText(this, "Diagnostic report copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });

        btnRestart.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    private String readLastCrashFile() {
        try {
            File f = new File(getFilesDir(), "last_crash.txt");
            if (f.isFile() && f.length() > 0) {
                byte[] b = new byte[(int) Math.min(f.length(), 131072)];
                try (FileInputStream fis = new FileInputStream(f)) {
                    int n = fis.read(b);
                    if (n > 0) return new String(b, 0, n, StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}

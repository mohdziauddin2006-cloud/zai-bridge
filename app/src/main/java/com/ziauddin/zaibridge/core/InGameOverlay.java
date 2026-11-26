package com.ziauddin.zaibridge.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.ziauddin.zaibridge.data.GraphicsSettings;
import com.ziauddin.zaibridge.proxy.ProxyGameActivity;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Self-contained programmatic in-game HUD overlay attached directly onto any
 * guest Activity's {@code DecorView} (works identically for installed 64-bit
 * games and legacy 32-bit games without interfering with guest resources).
 *
 * <p>Provides:
 * <ul>
 *   <li>Draggable live FPS / MFG / Upscaler telemetry pill (tap to open drawer)</li>
 *   <li>Zai-Scale Ultra presets including God-Level Berserk Mode</li>
 *   <li>Live Render Scale (50–100% or up to 125% SSAA in Berserk Mode)</li>
 *   <li>Multi-Frame Generation chips: Off | 2x | 3x | 4x | 5x | 8x | UNLIMITED</li>
 *   <li>Live before/after split-screen compare wipe</li>
 *   <li>Universal Force-Save &amp; Force-Load Checkpoints (Quick Save, Slot 1–3)</li>
 * </ul>
 */
public final class InGameOverlay {

    private static final String TAG = "ZaiBridge";
    private static final String OVERLAY_TAG = "zai_bridge_ingame_overlay";
    private static final ExecutorService IO_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zai-bridge-checkpoint");
        t.setDaemon(true);
        return t;
    });

    private final Activity activity;
    private final Context hostContext;
    private final File gameDir;
    private final String guestPackage;
    private final String guestClass;
    private final FrameLayout overlayRoot;

    private GraphicsSettings graphics;
    private TextView telemetryPill;
    private View drawer;
    private TextView drawerStats;
    private int drawerWidth;
    private boolean drawerOpen;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            updateTelemetry();
            handler.postDelayed(this, 850);
        }
    };

    public static void attach(Activity guest, Context hostContext, File gameDir,
                              String guestPackage, String guestClass) {
        if (guest == null || gameDir == null) return;
        Window window = guest.getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        if (decor.findViewWithTag(OVERLAY_TAG) != null) return;

        try {
            new InGameOverlay(guest, hostContext, gameDir, guestPackage, guestClass,
                    (ViewGroup) decor);
        } catch (Throwable t) {
            Log.w(TAG, "in-game overlay could not be attached: " + t);
        }
    }

    private InGameOverlay(Activity activity, Context hostContext, File gameDir,
                          String guestPackage, String guestClass, ViewGroup decor) {
        this.activity = activity;
        this.hostContext = hostContext != null ? hostContext.getApplicationContext() : activity;
        this.gameDir = gameDir;
        this.guestPackage = guestPackage;
        this.guestClass = guestClass;
        this.graphics = GraphicsSettings.forGame(this.hostContext, gameDir);

        overlayRoot = new FrameLayout(hostContext);
        overlayRoot.setTag(OVERLAY_TAG);
        overlayRoot.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        buildTelemetryPill();
        buildEdgeSwipeHandle();

        decor.addView(overlayRoot);
        handler.postDelayed(tick, 600);
        Log.i(TAG, "in-game Zai-Scale + Force-Save overlay attached for " + guestPackage);
    }

    private int dp(float value) {
        return Math.round(value * hostContext.getResources().getDisplayMetrics().density);
    }

    private void buildTelemetryPill() {
        telemetryPill = new TextView(hostContext);
        telemetryPill.setTextColor(0xFFEAF2FF);
        telemetryPill.setTextSize(10f);
        telemetryPill.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        telemetryPill.setPadding(dp(11), dp(6), dp(11), dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC0D131D);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x6600E5FF);
        telemetryPill.setBackground(bg);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = dp(14);
        lp.topMargin = dp(38);
        telemetryPill.setLayoutParams(lp);
        telemetryPill.setText("ZAI · " + graphics.shortLabel());

        telemetryPill.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int startLeft;
            private int startTop;
            private long downAt;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startLeft = p.leftMargin;
                        startTop = p.topMargin;
                        downAt = System.currentTimeMillis();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        int maxL = Math.max(0, overlayRoot.getWidth() - v.getWidth() - dp(8));
                        int maxT = Math.max(0, overlayRoot.getHeight() - v.getHeight() - dp(8));
                        p.leftMargin = clamp(startLeft + (int) dx, dp(4), maxL);
                        p.topMargin = clamp(startTop + (int) dy, dp(4), maxT);
                        v.setLayoutParams(p);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved && System.currentTimeMillis() - downAt < 300) {
                            toggleDrawer();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });

        overlayRoot.addView(telemetryPill);
    }

    private void buildEdgeSwipeHandle() {
        View handle = new View(hostContext);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(18), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        handle.setLayoutParams(lp);
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downX;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX();
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (downX - event.getRawX() > dp(22)) openDrawer();
                    return true;
                }
                return false;
            }
        });
        overlayRoot.addView(handle);
    }

    private void updateTelemetry() {
        if (telemetryPill == null || graphics == null) return;
        String stats = EglSwapHook.compositorStats();
        int guestFps = (int) Math.round(extractNumber(stats, "guestFps"));
        int outputFps = (int) Math.round(extractNumber(stats, "outputFps"));
        int vkFps = (int) Math.round(extractNumber(stats, "vk_fps"));
        String prefix = graphics.berserk != 0 ? "BERSERK · " : "";
        String text;
        if (guestFps > 0) {
            text = String.format(Locale.US, "%s%d→%d FPS · %s · %s %d%%",
                    prefix, guestFps, Math.max(guestFps, outputFps),
                    graphics.frameGenName(), graphics.upscalerName(), graphics.scalePercent);
        } else if (vkFps > 0) {
            text = String.format(Locale.US, "%sVK %d FPS · %s",
                    prefix, vkFps, graphics.frameGenName());
        } else {
            text = String.format(Locale.US, "%s%s MFG · %s %d%%",
                    prefix, graphics.frameGenName(), graphics.upscalerName(), graphics.scalePercent);
        }
        telemetryPill.setText(text);
        if (drawerStats != null) drawerStats.setText(stats);
    }

    private void toggleDrawer() {
        if (drawerOpen) closeDrawer();
        else openDrawer();
    }

    private void openDrawer() {
        if (drawer == null) drawer = buildDrawer();
        if (drawer.getParent() == null) overlayRoot.addView(drawer);
        drawerOpen = true;
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(drawerWidth);
        drawer.animate().translationX(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
        EglSwapHook.setCompare(0.5f);
        updateTelemetry();
    }

    private void closeDrawer() {
        if (drawer == null) return;
        drawerOpen = false;
        EglSwapHook.setCompare(0f);
        drawer.animate().translationX(drawerWidth).setDuration(180)
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    private void rebuildDrawer() {
        if (drawer != null) overlayRoot.removeView(drawer);
        drawer = buildDrawer();
        drawer.setVisibility(View.VISIBLE);
        overlayRoot.addView(drawer);
        updateTelemetry();
    }

    private View buildDrawer() {
        int maxW = overlayRoot.getWidth() > dp(64) ? overlayRoot.getWidth() - dp(24) : dp(340);
        drawerWidth = Math.min(dp(340), maxW);

        FrameLayout panel = new FrameLayout(hostContext);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.END;
        panel.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE0B1019);
        bg.setStroke(dp(1), 0x4400E5FF);
        panel.setBackground(bg);
        panel.setClickable(true);

        ScrollView scroll = new ScrollView(hostContext);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout body = new LinearLayout(hostContext);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(22), dp(16), dp(26));
        scroll.addView(body);

        // Header
        LinearLayout header = new LinearLayout(hostContext);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(hostContext);
        title.setText("ZAI-SCALE ULTRA & CHECKPOINTS");
        title.setTextColor(0xFF00E5FF);
        title.setTextSize(12.5f);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        TextView closeBtn = new TextView(hostContext);
        closeBtn.setText("✕");
        closeBtn.setTextColor(0xFFE8EEF7);
        closeBtn.setTextSize(14f);
        closeBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        closeBtn.setOnClickListener(v -> closeDrawer());
        header.addView(closeBtn);
        body.addView(header);

        // Berserk Mode toggle
        LinearLayout berserkRow = new LinearLayout(hostContext);
        berserkRow.setOrientation(LinearLayout.HORIZONTAL);
        berserkRow.setGravity(Gravity.CENTER_VERTICAL);
        berserkRow.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable berserkBg = new GradientDrawable();
        berserkBg.setColor(graphics.berserk != 0 ? 0x33FF3D00 : 0x1AFFFFFF);
        berserkBg.setCornerRadius(dp(12));
        berserkBg.setStroke(dp(1), graphics.berserk != 0 ? 0xCCFF3D00 : 0x33FFFFFF);
        berserkRow.setBackground(berserkBg);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dp(12);
        berserkRow.setLayoutParams(brLp);

        TextView berserkLabel = new TextView(hostContext);
        berserkLabel.setText("BERSERK MODE (Bypass Limits · 125% · Uncapped)");
        berserkLabel.setTextColor(graphics.berserk != 0 ? 0xFFFFAB91 : 0xFFD0DCEB);
        berserkLabel.setTextSize(10.5f);
        berserkLabel.setTypeface(null, Typeface.BOLD);
        berserkLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        berserkRow.addView(berserkLabel);

        Switch berserkSwitch = new Switch(hostContext);
        berserkSwitch.setChecked(graphics.berserk != 0);
        berserkSwitch.setOnCheckedChangeListener((btn, checked) -> {
            graphics.berserk = checked ? 1 : 0;
            if (!checked && graphics.scalePercent > GraphicsSettings.SCALE_NATIVE) {
                graphics.scalePercent = GraphicsSettings.SCALE_NATIVE;
            }
            if (checked) {
                graphics.autoMfg = 0;
            }
            graphics.markCustom();
            applyGraphics();
            rebuildDrawer();
        });
        berserkRow.addView(berserkSwitch);
        body.addView(berserkRow);

        // Presets
        body.addView(sectionLabel("ALL-IN-ONE PRESET"));
        body.addView(chipRow(new String[][]{
                {"Off", "0"},
                {"FSR Perf", "1"},
                {"FSR Qual", "2"},
                {"DLSS-5 Ultra", "3"},
                {"4x Overdrive", "4"},
                {"GOD BERSERK", "5"}
        }, String.valueOf(graphics.preset), val -> {
            graphics = GraphicsSettings.ofPreset(Integer.parseInt(val));
            applyGraphics();
            rebuildDrawer();
        }));

        // Scale slider (50..100 or 50..125 in Berserk Mode)
        int maxScale = graphics.berserk != 0
                ? GraphicsSettings.SCALE_SUPER_SAMPLE : GraphicsSettings.SCALE_NATIVE;
        body.addView(sliderRow("RENDER SCALE", 50, maxScale,
                clamp(graphics.scalePercent, 50, maxScale), v -> {
                    graphics.scalePercent = v;
                    graphics.markCustom();
                    applyGraphics();
                }));

        body.addView(sliderRow("SHARPNESS (RCAS)", 0, 100, graphics.sharpnessPercent, v -> {
            graphics.sharpnessPercent = v;
            graphics.markCustom();
            applyGraphics();
        }));

        body.addView(sliderRow("NEURAL DETAIL", 0, 100, graphics.neuralPercent, v -> {
            graphics.neuralPercent = v;
            graphics.markCustom();
            applyGraphics();
        }));

        // Frame generation (Off | 2x | 3x | 4x | 5x | 8x | UNLIMITED)
        body.addView(sectionLabel("MULTI-FRAME GENERATION (UNCAPPED)"));
        body.addView(chipRow(new String[][]{
                {"Off", "0"},
                {"2x", "2"},
                {"3x", "3"},
                {"4x", "4"},
                {"5x", "5"},
                {"8x", "8"},
                {"UNLIMITED", String.valueOf(GraphicsSettings.FRAME_GEN_UNLIMITED)}
        }, String.valueOf(graphics.frameGen), val -> {
            graphics.frameGen = Integer.parseInt(val);
            if (graphics.frameGen != GraphicsSettings.FRAME_GEN_OFF
                    && graphics.upscaler == GraphicsSettings.UPSCALER_OFF) {
                graphics.upscaler = GraphicsSettings.UPSCALER_DLSS;
                graphics.temporal = 1;
            }
            if (graphics.frameGen == GraphicsSettings.FRAME_GEN_UNLIMITED) {
                graphics.autoMfg = 0;
            }
            graphics.markCustom();
            applyGraphics();
            rebuildDrawer();
        }));

        // Split compare wipe
        body.addView(sectionLabel("SPLIT COMPARE (VANILLA vs ZAI-SCALE)"));
        SeekBar compare = new SeekBar(hostContext);
        compare.setMax(100);
        compare.setProgress(50);
        compare.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) {
                    EglSwapHook.setCompare(value <= 2 ? 0f : Math.min(0.98f, value / 100f));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        body.addView(compare);

        // Universal Force-Save Checkpoints
        body.addView(sectionLabel("UNIVERSAL FORCE-SAVE CHECKPOINTS"));
        for (String slot : SaveStateManager.ALL_SLOTS) {
            body.addView(buildCheckpointRow(slot));
        }

        // Live telemetry counters
        drawerStats = new TextView(hostContext);
        drawerStats.setTextSize(8.5f);
        drawerStats.setTextColor(0xFF7B8CA3);
        drawerStats.setPadding(0, dp(12), 0, dp(10));
        drawerStats.setText(EglSwapHook.compositorStats());
        body.addView(drawerStats);

        // Exit button
        Button exit = new Button(hostContext);
        exit.setText("Exit Game");
        exit.setAllCaps(false);
        exit.setTextSize(12.5f);
        exit.setTextColor(0xFFFFB300);
        GradientDrawable exitBg = new GradientDrawable();
        exitBg.setColor(0x1AFFB300);
        exitBg.setCornerRadius(dp(12));
        exitBg.setStroke(dp(1), 0x66FFB300);
        exit.setBackground(exitBg);
        exit.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        exit.setOnClickListener(v -> activity.finish());
        body.addView(exit);

        panel.addView(scroll);
        return panel;
    }

    private View buildCheckpointRow(final String slotId) {
        LinearLayout row = new LinearLayout(hostContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(7), dp(10), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0x2600E5FF);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(hostContext);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(hostContext);
        name.setText(slotId);
        name.setTextColor(0xFFE8EEF7);
        name.setTextSize(11f);
        name.setTypeface(null, Typeface.BOLD);
        col.addView(name);

        TextView meta = new TextView(hostContext);
        meta.setText("Checking...");
        meta.setTextColor(0xFF8A99AD);
        meta.setTextSize(9f);
        col.addView(meta);
        row.addView(col);

        TextView saveBtn = actionPill("Save", 0xFF00E5FF, 0x2200E5FF);
        saveBtn.setOnClickListener(v -> performForceSave(slotId));
        row.addView(saveBtn);

        TextView loadBtn = actionPill("Load", 0xFF546273, 0x12FFFFFF);
        loadBtn.setEnabled(false);
        loadBtn.setOnClickListener(v -> performForceLoad(slotId));
        row.addView(loadBtn);

        IO_POOL.execute(() -> {
            final SaveStateManager.CheckpointInfo info =
                    SaveStateManager.inspect(gameDir, slotId);
            handler.post(() -> {
                name.setText(info.displayName);
                meta.setText(info.formattedSummary());
                loadBtn.setEnabled(info.exists);
                loadBtn.setTextColor(info.exists ? 0xFFFFB300 : 0xFF546273);
                GradientDrawable lbg = new GradientDrawable();
                lbg.setColor(info.exists ? 0x22FFB300 : 0x12FFFFFF);
                lbg.setCornerRadius(dp(9));
                lbg.setStroke(dp(1), info.exists ? 0xFFFFB300 : 0xFF546273);
                loadBtn.setBackground(lbg);
            });
        });

        return row;
    }

    private TextView actionPill(String label, int textColor, int bgColor) {
        TextView tv = new TextView(hostContext);
        tv.setText(label);
        tv.setTextColor(textColor);
        tv.setTextSize(10f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(9));
        bg.setStroke(dp(1), textColor);
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void performForceSave(String slotId) {
        IO_POOL.execute(() -> {
            try {
                SaveStateManager.CheckpointInfo info =
                        SaveStateManager.forceSave(hostContext, gameDir, guestPackage, slotId);
                handler.post(() -> {
                    Toast.makeText(hostContext,
                            "Force-Saved " + info.displayName + " (" + info.fileCount + " files)",
                            Toast.LENGTH_SHORT).show();
                    if (drawerOpen) rebuildDrawer();
                });
            } catch (Throwable t) {
                handler.post(() -> Toast.makeText(hostContext,
                        "Force-Save failed: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void performForceLoad(String slotId) {
        IO_POOL.execute(() -> {
            try {
                int count = SaveStateManager.forceLoad(hostContext, gameDir, slotId);
                handler.post(() -> {
                    Toast.makeText(hostContext,
                            "Checkpoint restored (" + count + " files) — reloading game…",
                            Toast.LENGTH_SHORT).show();
                    relaunchGuest();
                });
            } catch (Throwable t) {
                handler.post(() -> Toast.makeText(hostContext,
                        "Force-Load failed: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void relaunchGuest() {
        try {
            Intent relaunch = new Intent(hostContext, ProxyGameActivity.class);
            relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            relaunch.putExtra(GuestLauncher.EXTRA_GAME_DIR, gameDir.getAbsolutePath());
            if (guestClass != null && !guestClass.isEmpty()) {
                relaunch.putExtra(GuestLauncher.EXTRA_GUEST_ACTIVITY, guestClass);
            }
            activity.finish();
            hostContext.startActivity(relaunch);
        } catch (Throwable t) {
            Log.w(TAG, "checkpoint relaunch failed: " + t);
        }
    }

    private void applyGraphics() {
        EglSwapHook.configure(graphics);
        GuestLauncher.applyDisplayFrameRate(activity, graphics);
        try {
            GraphicsSettings.saveGame(gameDir, graphics);
        } catch (Throwable t) {
            Log.w(TAG, "overlay graphics save failed: " + t);
        }
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(hostContext);
        tv.setText(text);
        tv.setTextSize(9f);
        tv.setLetterSpacing(0.10f);
        tv.setTextColor(0xFF8A99AD);
        tv.setPadding(0, dp(14), 0, dp(6));
        return tv;
    }

    private interface StrSink { void accept(String value); }
    private interface IntSink { void accept(int value); }

    private View chipRow(String[][] chips, String selected, StrSink sink) {
        HorizontalScrollView scroll = new HorizontalScrollView(hostContext);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(hostContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] chip : chips) {
            TextView tv = new TextView(hostContext);
            tv.setText(chip[0]);
            tv.setTextSize(10f);
            boolean on = chip[1].equals(selected);
            boolean hot = "5".equals(chip[1]) || "99".equals(chip[1]);
            int activeColor = hot ? 0xFFFF5252 : 0xFF00E5FF;
            tv.setTextColor(on ? activeColor : 0xFF9AA8BC);
            if (on) tv.setTypeface(null, Typeface.BOLD);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? (hot ? 0x33FF5252 : 0x2600E5FF) : 0xFF141A24);
            bg.setCornerRadius(dp(12));
            bg.setStroke(dp(1), on ? activeColor : 0xFF263042);
            tv.setBackground(bg);
            tv.setPadding(dp(11), dp(6), dp(11), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> sink.accept(chip[1]));
            row.addView(tv);
        }
        scroll.addView(row);
        return scroll;
    }

    private View sliderRow(String title, int min, int max, int value, IntSink sink) {
        LinearLayout wrap = new LinearLayout(hostContext);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(10);
        wrap.setLayoutParams(wlp);

        LinearLayout header = new LinearLayout(hostContext);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = new TextView(hostContext);
        label.setText(title);
        label.setTextSize(9f);
        label.setLetterSpacing(0.10f);
        label.setTextColor(0xFF8A99AD);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(hostContext);
        badge.setText(String.format(Locale.US, "%d%%", value));
        badge.setTextSize(10.5f);
        badge.setTextColor(value > 100 ? 0xFFFF5252 : 0xFF00E5FF);
        badge.setTypeface(null, Typeface.BOLD);
        header.addView(label);
        header.addView(badge);
        wrap.addView(header);

        SeekBar bar = new SeekBar(hostContext);
        bar.setMax(Math.max(1, max - min));
        bar.setProgress(Math.max(0, value - min));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int cur = progress + min;
                badge.setText(String.format(Locale.US, "%d%%", cur));
                badge.setTextColor(cur > 100 ? 0xFFFF5252 : 0xFF00E5FF);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                sink.accept(sb.getProgress() + min);
            }
        });
        wrap.addView(bar);
        return wrap;
    }

    private static double extractNumber(String stats, String key) {
        if (stats == null) return 0;
        int at = stats.indexOf(key + "=");
        if (at < 0) return 0;
        int start = at + key.length() + 1;
        int end = start;
        while (end < stats.length()) {
            char c = stats.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.') end++;
            else break;
        }
        try {
            return Double.parseDouble(stats.substring(start, end));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int clamp(int value, int lo, int hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }
}

package com.ziauddin.zaibridge.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.ziauddin.zaibridge.R;
import com.ziauddin.zaibridge.core.EglSwapHook;
import com.ziauddin.zaibridge.data.GraphicsSettings;

/**
 * The graphics sheet used for a per-game configuration and for the global
 * defaults. It edits a working copy; nothing is persisted or pushed to the
 * compositor unless the user saves.
 *
 * <p>Built from framework widgets on purpose: the app deliberately has no
 * third-party UI dependency, and this dialog must work on the oldest API level
 * the launcher supports.
 */
public final class GraphicsSettingsSheet {

    public interface Listener {
        /**
         * @param settings the edited values
         * @param useGlobal true when "Use global defaults" was chosen (the
         *                  per-game override should be removed)
         */
        void onSaved(GraphicsSettings settings, boolean useGlobal);
    }

    private GraphicsSettingsSheet() {
    }

    public static void show(Context context, String title, GraphicsSettings current,
                            boolean allowGlobalReset, Listener listener) {
        final GraphicsSettings draft =
                current == null ? GraphicsSettings.defaults() : current.copy();

        final int pad = dp(context, 20);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(context, 6), pad, dp(context, 4));

        // ---- upscaler ---------------------------------------------------
        root.addView(sectionLabel(context, R.string.graphics_upscaler));
        RadioGroup upscaler = new RadioGroup(context);
        upscaler.setOrientation(RadioGroup.VERTICAL);
        upscaler.addView(option(context, context.getString(R.string.graphics_off),
                draft.upscaler == GraphicsSettings.UPSCALER_OFF,
                () -> draft.upscaler = GraphicsSettings.UPSCALER_OFF));
        upscaler.addView(option(context, context.getString(R.string.graphics_sgsr),
                draft.upscaler == GraphicsSettings.UPSCALER_SGSR,
                () -> draft.upscaler = GraphicsSettings.UPSCALER_SGSR));
        upscaler.addView(option(context, context.getString(R.string.graphics_fsr),
                draft.upscaler == GraphicsSettings.UPSCALER_FSR,
                () -> draft.upscaler = GraphicsSettings.UPSCALER_FSR));
        root.addView(upscaler);

        // ---- render resolution -----------------------------------------
        root.addView(sectionLabel(context, R.string.graphics_scale));
        RadioGroup scale = new RadioGroup(context);
        scale.setOrientation(RadioGroup.VERTICAL);
        scale.addView(option(context, context.getString(R.string.graphics_scale_50),
                draft.scalePercent == GraphicsSettings.SCALE_PERFORMANCE,
                () -> draft.scalePercent = GraphicsSettings.SCALE_PERFORMANCE));
        scale.addView(option(context, context.getString(R.string.graphics_scale_67),
                draft.scalePercent == GraphicsSettings.SCALE_BALANCED,
                () -> draft.scalePercent = GraphicsSettings.SCALE_BALANCED));
        scale.addView(option(context, context.getString(R.string.graphics_scale_75),
                draft.scalePercent == GraphicsSettings.SCALE_QUALITY,
                () -> draft.scalePercent = GraphicsSettings.SCALE_QUALITY));
        scale.addView(option(context, context.getString(R.string.graphics_scale_100),
                draft.scalePercent == GraphicsSettings.SCALE_NATIVE,
                () -> draft.scalePercent = GraphicsSettings.SCALE_NATIVE));
        root.addView(scale);

        // ---- sharpness ---------------------------------------------------
        LinearLayout sharpnessHeader = new LinearLayout(context);
        sharpnessHeader.setOrientation(LinearLayout.HORIZONTAL);
        TextView sharpnessLabel = sectionLabel(context, R.string.graphics_sharpness);
        sharpnessLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView sharpnessValue = new TextView(context);
        sharpnessValue.setTextColor(0xFF3BE0F2);
        sharpnessValue.setTextSize(12f);
        sharpnessValue.setText(draft.sharpnessPercent + "%");
        sharpnessHeader.addView(sharpnessLabel);
        sharpnessHeader.addView(sharpnessValue);
        root.addView(sharpnessHeader);

        SeekBar sharpness = new SeekBar(context);
        sharpness.setMax(100);
        sharpness.setProgress(draft.sharpnessPercent);
        sharpness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                draft.sharpnessPercent = progress;
                sharpnessValue.setText(progress + "%");
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { }

            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        root.addView(sharpness);

        // ---- frame generation --------------------------------------------
        root.addView(sectionLabel(context, R.string.graphics_frame_gen));
        RadioGroup frameGen = new RadioGroup(context);
        frameGen.setOrientation(RadioGroup.VERTICAL);
        frameGen.addView(option(context, context.getString(R.string.graphics_off),
                draft.frameGen == GraphicsSettings.FRAME_GEN_OFF,
                () -> draft.frameGen = GraphicsSettings.FRAME_GEN_OFF));
        frameGen.addView(option(context, context.getString(R.string.graphics_frame_gen_2x),
                draft.frameGen == GraphicsSettings.FRAME_GEN_2X,
                () -> draft.frameGen = GraphicsSettings.FRAME_GEN_2X));
        root.addView(frameGen);

        // ---- detected hardware -------------------------------------------
        TextView info = new TextView(context);
        info.setTextSize(10f);
        info.setTextColor(0xFF8FA3B8);
        info.setPadding(0, dp(context, 10), 0, 0);
        String detected = EglSwapHook.graphicsInfo();
        if (detected.length() > 240) detected = detected.substring(0, 240) + "…";
        info.setText(detected);
        root.addView(info);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(R.string.graphics_save,
                        (dialog, which) -> listener.onSaved(draft, false))
                .setNegativeButton(R.string.cancel, null);
        if (allowGlobalReset) {
            builder.setNeutralButton(R.string.graphics_use_global,
                    (dialog, which) -> listener.onSaved(draft, true));
        }
        builder.show();
    }

    // ------------------------------------------------------------------
    // Widget helpers
    // ------------------------------------------------------------------

    private static TextView sectionLabel(Context context, int textRes) {
        TextView label = new TextView(context);
        label.setText(textRes);
        label.setTextSize(11f);
        label.setTextColor(0xFF8FA3B8);
        label.setAllCaps(true);
        label.setLetterSpacing(0.08f);
        label.setPadding(0, dp(context, 14), 0, dp(context, 4));
        return label;
    }

    private static RadioButton option(Context context, String text, boolean checked,
                                      Runnable pick) {
        RadioButton button = new RadioButton(context);
        button.setText(text);
        button.setTextSize(13f);
        button.setTextColor(0xFFEAF2FA);
        button.setChecked(checked);
        button.setPadding(0, dp(context, 2), 0, dp(context, 2));
        button.setOnClickListener(v -> pick.run());
        return button;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

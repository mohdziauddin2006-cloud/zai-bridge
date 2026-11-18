package com.ziauddin.zaibridge.ui;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.slider.Slider;
import com.ziauddin.zaibridge.R;
import com.ziauddin.zaibridge.core.EglSwapHook;
import com.ziauddin.zaibridge.data.Game;
import com.ziauddin.zaibridge.data.GraphicsSettings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * The game details + all-in-one graphics sheet.
 *
 * <p>Everything edits a working copy: a preset chip rewrites every control
 * below it, and touching any control switches the sheet to {@code Custom} while
 * leaving the chips in sync. Values are pushed to the live compositor as they
 * change, and persisted to the game's {@code meta.json} (or the global defaults)
 * when the sheet closes.
 */
public final class GameDetailsSheet {

    private static final String TAG = "ZaiBridge";

    public interface Listener {
        /** Called with the saved configuration when the user starts the game. */
        void onPlay(Game game, GraphicsSettings settings);
    }

    private final Activity activity;
    private final Game game;
    private final File gameDir;
    private final boolean global;
    private final BottomSheetDialog dialog;
    private final GraphicsSettings draft;

    private final ChipGroup presetChips;
    private final ChipGroup frameGenChips;
    private final ChipGroup orientationChips;
    private final Slider scaleSlider;
    private final Slider sharpnessSlider;
    private final Slider neuralSlider;
    private final TextView scaleValue;
    private final TextView sharpValue;
    private final TextView neuralValue;
    private final SwitchCompat autoMfg;

    private boolean syncing;
    private boolean cleared;

    public static void show(Activity activity, Game game, File gameDir, Listener onSaved,
                            Listener onPlay) {
        new GameDetailsSheet(activity, game, gameDir, false, onSaved, onPlay).open();
    }

    public static void showEngine(Activity activity, Listener onSaved) {
        new GameDetailsSheet(activity, null, null, true, onSaved, null).open();
    }

    private GameDetailsSheet(Activity activity, Game game, File gameDir, boolean global,
                             Listener onSaved, Listener onPlay) {
        this.activity = activity;
        this.game = game;
        this.gameDir = gameDir;
        this.global = global;
        this.draft = global ? GraphicsSettings.global(activity)
                : GraphicsSettings.forGame(activity, gameDir);

        dialog = new BottomSheetDialog(activity, R.style.Theme_ZaiBridge);
        View content = activity.getLayoutInflater()
                .inflate(R.layout.sheet_game_details, null);
        dialog.setContentView(content);
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);

        presetChips = content.findViewById(R.id.chips_preset);
        frameGenChips = content.findViewById(R.id.chips_frame_gen);
        orientationChips = content.findViewById(R.id.chips_orientation);
        scaleSlider = content.findViewById(R.id.slider_scale);
        sharpnessSlider = content.findViewById(R.id.slider_sharpness);
        neuralSlider = content.findViewById(R.id.slider_neural);
        scaleValue = content.findViewById(R.id.tv_scale_value);
        sharpValue = content.findViewById(R.id.tv_sharp_value);
        neuralValue = content.findViewById(R.id.tv_neural_value);
        autoMfg = content.findViewById(R.id.switch_auto_mfg);

        ImageView icon = content.findViewById(R.id.iv_details_icon);
        TextView name = content.findViewById(R.id.tv_details_name);
        TextView pkg = content.findViewById(R.id.tv_details_package);
        TextView obb = content.findViewById(R.id.tv_details_obb);
        View orientationWrap = content.findViewById(R.id.wrap_orientation);
        Button play = content.findViewById(R.id.btn_play);
        View useGlobal = content.findViewById(R.id.btn_use_global);
        ImageButton close = content.findViewById(R.id.btn_close_details);

        if (global) {
            icon.setImageResource(R.drawable.ic_emblem);
            name.setText(R.string.engine_settings);
            pkg.setText(R.string.engine_defaults_sub);
            obb.setVisibility(View.GONE);
            orientationWrap.setVisibility(View.GONE);
            play.setVisibility(View.GONE);
            useGlobal.setVisibility(View.GONE);
        } else {
            name.setText(game.label);
            pkg.setText(game.packageName);
            obb.setText(game.hasObb() ? R.string.obb_ready : R.string.obb_none);
            File iconFile = game.iconFile.isEmpty() ? null : new File(gameDir, game.iconFile);
            if (iconFile != null && iconFile.isFile()) {
                icon.setImageBitmap(android.graphics.BitmapFactory
                        .decodeFile(iconFile.getAbsolutePath()));
            }
            play.setOnClickListener(v -> {
                persist();
                if (onPlay != null) onPlay.onPlay(game, draft);
                dialog.dismiss();
            });
            useGlobal.setOnClickListener(v -> {
                cleared = true;
                GraphicsSettings.clearGame(gameDir);
                dialog.dismiss();
            });
            bindOrientation();
        }

        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            if (!cleared) persist();
            if (onSaved != null) onSaved.onPlay(global ? null : game, draft);
        });

        // ---- presets -----------------------------------------------------
        presetChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (syncing || checkedIds.isEmpty()) return;
            int preset = presetOf(checkedIds.get(0));
            GraphicsSettings next = GraphicsSettings.ofPreset(preset);
            copyInto(next);
            syncUi();
            applyLive();
        });

        // ---- sliders -----------------------------------------------------
        scaleSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                draft.scalePercent = Math.round(value);
                draft.markCustom();
                customChipState();
            }
            scaleValue.setText(get(activity.getString(R.string.value_percent),
                    draft.scalePercent));
        });
        sharpnessSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                draft.sharpnessPercent = Math.round(value);
                draft.markCustom();
                customChipState();
            }
            sharpValue.setText(get(activity.getString(R.string.value_percent),
                    draft.sharpnessPercent));
        });
        neuralSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                draft.neuralPercent = Math.round(value);
                draft.markCustom();
                customChipState();
            }
            neuralValue.setText(get(activity.getString(R.string.value_percent),
                    draft.neuralPercent));
        });
        Slider.OnSliderTouchListener persistOnRelease = new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) { }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                applyLive();
            }
        };
        scaleSlider.addOnSliderTouchListener(persistOnRelease);
        sharpnessSlider.addOnSliderTouchListener(persistOnRelease);
        neuralSlider.addOnSliderTouchListener(persistOnRelease);

        // ---- frame generation -------------------------------------------
        frameGenChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (syncing || checkedIds.isEmpty()) return;
            draft.frameGen = frameGenOf(checkedIds.get(0));
            if (draft.frameGen != GraphicsSettings.FRAME_GEN_OFF
                    && draft.upscaler == GraphicsSettings.UPSCALER_OFF) {
                draft.upscaler = GraphicsSettings.UPSCALER_DLSS;
                draft.temporal = 1;
            }
            draft.markCustom();
            customChipState();
            applyLive();
        });

        autoMfg.setOnCheckedChangeListener((button, checked) -> {
            if (syncing) return;
            draft.autoMfg = checked ? 1 : 0;
            draft.markCustom();
            applyLive();
        });

        syncUi();
    }

    private void open() {
        dialog.show();
    }

    // ------------------------------------------------------------------
    // UI <-> model
    // ------------------------------------------------------------------

    private void syncUi() {
        syncing = true;
        try {
            scaleSlider.setValue(clamp(draft.scalePercent, 50, 100));
            sharpnessSlider.setValue(clamp(draft.sharpnessPercent, 0, 100));
            neuralSlider.setValue(clamp(draft.neuralPercent, 0, 100));
            scaleValue.setText(get(activity.getString(R.string.value_percent),
                    draft.scalePercent));
            sharpValue.setText(get(activity.getString(R.string.value_percent),
                    draft.sharpnessPercent));
            neuralValue.setText(get(activity.getString(R.string.value_percent),
                    draft.neuralPercent));
            autoMfg.setChecked(draft.autoMfg != 0);

            int presetChip = chipOfPreset(draft.preset);
            if (presetChip != View.NO_ID) {
                presetChips.check(presetChip);
            } else {
                presetChips.clearCheck();
            }
            frameGenChips.check(chipOfFrameGen(draft.frameGen));
        } finally {
            syncing = false;
        }
    }

    /** Any manual edit drops the lit preset and shows Custom in the chips row. */
    private void customChipState() {
        if (draft.preset == GraphicsSettings.PRESET_CUSTOM) {
            presetChips.clearCheck();
        }
    }

    private void copyInto(GraphicsSettings source) {
        draft.preset = source.preset;
        draft.upscaler = source.upscaler;
        draft.scalePercent = source.scalePercent;
        draft.sharpnessPercent = source.sharpnessPercent;
        draft.neuralPercent = source.neuralPercent;
        draft.frameGen = source.frameGen;
        draft.temporal = source.temporal;
        draft.autoMfg = source.autoMfg;
    }

    private void applyLive() {
        EglSwapHook.configure(draft);
    }

    private void persist() {
        if (global) {
            GraphicsSettings.saveGlobal(activity, draft);
            EglSwapHook.configure(draft);
            return;
        }
        try {
            GraphicsSettings.saveGame(gameDir, draft);
            EglSwapHook.configure(draft);
        } catch (Exception e) {
            Log.e(TAG, "graphics settings were not saved", e);
            Toast.makeText(activity, R.string.import_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    // Orientation lock
    // ------------------------------------------------------------------

    private void bindOrientation() {
        syncing = true;
        try {
            String orientation = game == null ? "" : game.orientation;
            if (orientation.contains("landscape")) {
                orientationChips.check(R.id.chip_orient_landscape);
            } else if (orientation.contains("portrait")) {
                orientationChips.check(R.id.chip_orient_portrait);
            } else {
                orientationChips.check(R.id.chip_orient_auto);
            }
        } finally {
            syncing = false;
        }

        orientationChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (syncing || checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            String value = id == R.id.chip_orient_landscape ? "landscape"
                    : id == R.id.chip_orient_portrait ? "portrait" : "unspecified";
            writeOrientation(value);
        });
    }

    /** The lock lives in meta.json, which GameOrientation reads at launch. */
    private void writeOrientation(String value) {
        File meta = new File(gameDir, "meta.json");
        try {
            JSONObject root = meta.isFile() ? new JSONObject(readText(meta)) : new JSONObject();
            root.put("orientation", value);
            try (FileOutputStream out = new FileOutputStream(meta)) {
                out.write(root.toString(2).getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.e(TAG, "orientation lock was not saved", e);
        }
    }

    private static String readText(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    (int) Math.min(file.length(), 16384));
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        }
    }

    // ------------------------------------------------------------------
    // Chip mapping
    // ------------------------------------------------------------------

    private static int presetOf(int chipId) {
        if (chipId == R.id.chip_preset_fsr_perf) return GraphicsSettings.PRESET_FSR_PERFORMANCE;
        if (chipId == R.id.chip_preset_fsr_quality) return GraphicsSettings.PRESET_FSR_QUALITY;
        if (chipId == R.id.chip_preset_dlss_ultra) return GraphicsSettings.PRESET_DLSS_ULTRA;
        if (chipId == R.id.chip_preset_dlss_overdrive) {
            return GraphicsSettings.PRESET_DLSS_OVERDRIVE;
        }
        return GraphicsSettings.PRESET_OFF;
    }

    private static int chipOfPreset(int preset) {
        switch (preset) {
            case GraphicsSettings.PRESET_OFF: return R.id.chip_preset_off;
            case GraphicsSettings.PRESET_FSR_PERFORMANCE: return R.id.chip_preset_fsr_perf;
            case GraphicsSettings.PRESET_FSR_QUALITY: return R.id.chip_preset_fsr_quality;
            case GraphicsSettings.PRESET_DLSS_ULTRA: return R.id.chip_preset_dlss_ultra;
            case GraphicsSettings.PRESET_DLSS_OVERDRIVE: return R.id.chip_preset_dlss_overdrive;
            default: return View.NO_ID;
        }
    }

    private static int frameGenOf(int chipId) {
        if (chipId == R.id.chip_fg_2x) return GraphicsSettings.FRAME_GEN_2X;
        if (chipId == R.id.chip_fg_3x) return GraphicsSettings.FRAME_GEN_3X;
        if (chipId == R.id.chip_fg_4x) return GraphicsSettings.FRAME_GEN_4X;
        return GraphicsSettings.FRAME_GEN_OFF;
    }

    private static int chipOfFrameGen(int frameGen) {
        switch (frameGen) {
            case GraphicsSettings.FRAME_GEN_2X: return R.id.chip_fg_2x;
            case GraphicsSettings.FRAME_GEN_3X: return R.id.chip_fg_3x;
            case GraphicsSettings.FRAME_GEN_4X: return R.id.chip_fg_4x;
            default: return R.id.chip_fg_off;
        }
    }

    private static int clamp(int value, int lo, int hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }

    private static String get(String format, int value) {
        return String.format(Locale.US, format, value);
    }
}

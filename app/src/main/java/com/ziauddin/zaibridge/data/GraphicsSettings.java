package com.ziauddin.zaibridge.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Graphics pipeline configuration for one game, or the global default.
 *
 * <p>Per-game settings live inside the game's own {@code meta.json} under the
 * {@code graphics} key, next to the package and orientation the importer wrote.
 * That keeps a configuration with the import it belongs to: uninstalling a game
 * removes its settings, and a restored library carries them back. Global
 * defaults are a {@link SharedPreferences} entry, used for any game that has no
 * override of its own.
 *
 * <p>Every value maps onto the native Zai-Scale Ultra compositor:
 * {@code upscaler} 0 off / 1 mobile SGSR / 2 FSR temporal / 3 DLSS-5 neural,
 * {@code scalePercent} 50/67/75/85/100, plus 125 for DLAA super-sampling,
 * {@code sharpnessPercent} 0-100, {@code neuralPercent} 0-100 (local contrast,
 * specular and micro-detail reconstruction), {@code frameGen} 0/2/3/4/5/8 or
 * -1 for uncapped, {@code temporal} 0/1, {@code autoMfg} 0/1 (dynamic
 * multiplier) and {@code berserk} 0/1 (hardware limits bypassed).
 */
public final class GraphicsSettings {

    private static final String TAG = "ZaiBridge";
    private static final String META_KEY = "graphics";
    private static final String PREFS = "zai_graphics";

    public static final int UPSCALER_OFF = 0;
    public static final int UPSCALER_SGSR = 1;
    public static final int UPSCALER_FSR = 2;
    public static final int UPSCALER_DLSS = 3;

    public static final int SCALE_PERFORMANCE = 50;
    public static final int SCALE_BALANCED = 67;
    public static final int SCALE_QUALITY = 75;
    public static final int SCALE_ULTRA_QUALITY = 85;
    public static final int SCALE_NATIVE = 100;
    /** DLAA-style super-sampling: render above panel resolution, then downsample. */
    public static final int SCALE_DLAA_125 = 125;
    public static final int SCALE_SUPER_SAMPLE = SCALE_DLAA_125;

    public static final int FRAME_GEN_UNLIMITED = -1;
    public static final int FRAME_GEN_OFF = 0;
    public static final int FRAME_GEN_2X = 2;
    public static final int FRAME_GEN_3X = 3;
    public static final int FRAME_GEN_4X = 4;
    public static final int FRAME_GEN_5X = 5;
    public static final int FRAME_GEN_8X = 8;

    public static final int PRESET_CUSTOM = -1;
    public static final int PRESET_OFF = 0;
    public static final int PRESET_FSR_PERFORMANCE = 1;
    public static final int PRESET_FSR_QUALITY = 2;
    public static final int PRESET_DLSS_ULTRA = 3;
    public static final int PRESET_DLSS_OVERDRIVE = 4;
    /** Bypasses the SoC recommendation: maximum quality on any hardware. */
    public static final int PRESET_BERSERK = 5;
    /** Lightweight mobile edge-aware SGSR upscaling for thermal/battery savings. */
    public static final int PRESET_SGSR_BATTERY = 6;

    public int preset = PRESET_OFF;
    public int upscaler;
    public int scalePercent;
    public int sharpnessPercent;
    public int neuralPercent;
    public int frameGen;
    public int temporal;
    public int autoMfg;
    /** 1 while Berserk Mode forces God-Level Graphics regardless of tier. */
    public int berserk;

    public GraphicsSettings() {
        this.preset = PRESET_OFF;
        this.upscaler = UPSCALER_OFF;
        this.scalePercent = SCALE_NATIVE;
        this.sharpnessPercent = 0;
        this.neuralPercent = 0;
        this.frameGen = FRAME_GEN_OFF;
        this.temporal = 0;
        this.autoMfg = 0;
        this.berserk = 0;
    }

    public GraphicsSettings(int upscaler, int scalePercent, int sharpnessPercent,
                            int frameGen) {
        this.preset = PRESET_CUSTOM;
        this.upscaler = validUpscaler(upscaler);
        this.scalePercent = validScale(scalePercent);
        this.sharpnessPercent = clamp(sharpnessPercent, 0, 100);
        this.neuralPercent = 0;
        this.frameGen = validFrameGen(frameGen);
        this.temporal = upscaler == UPSCALER_FSR || upscaler == UPSCALER_DLSS ? 1 : 0;
        this.autoMfg = 0;
        this.berserk = 0;
    }

    public GraphicsSettings(GraphicsSettings other) {
        if (other == null) return;
        preset = other.preset;
        upscaler = other.upscaler;
        scalePercent = other.scalePercent;
        sharpnessPercent = other.sharpnessPercent;
        neuralPercent = other.neuralPercent;
        frameGen = other.frameGen;
        temporal = other.temporal;
        autoMfg = other.autoMfg;
        berserk = other.berserk;
    }

    public static GraphicsSettings defaults() {
        return new GraphicsSettings();
    }

    /** Builds the one-tap master preset with every parameter already set. */
    public static GraphicsSettings ofPreset(int preset) {
        GraphicsSettings s = new GraphicsSettings();
        s.preset = preset;
        switch (preset) {
            case PRESET_FSR_PERFORMANCE:
                s.upscaler = UPSCALER_FSR;
                s.scalePercent = SCALE_PERFORMANCE;
                s.sharpnessPercent = 45;
                s.neuralPercent = 10;
                s.frameGen = FRAME_GEN_2X;
                s.temporal = 1;
                s.autoMfg = 1;
                break;
            case PRESET_FSR_QUALITY:
                s.upscaler = UPSCALER_FSR;
                s.scalePercent = SCALE_QUALITY;
                s.sharpnessPercent = 35;
                s.neuralPercent = 25;
                s.frameGen = FRAME_GEN_2X;
                s.temporal = 1;
                s.autoMfg = 0;
                break;
            case PRESET_DLSS_ULTRA:
                s.upscaler = UPSCALER_DLSS;
                s.scalePercent = SCALE_ULTRA_QUALITY;
                s.sharpnessPercent = 45;
                s.neuralPercent = 55;
                s.frameGen = FRAME_GEN_3X;
                s.temporal = 1;
                s.autoMfg = 1;
                break;
            case PRESET_DLSS_OVERDRIVE:
                s.upscaler = UPSCALER_DLSS;
                s.scalePercent = SCALE_BALANCED;
                s.sharpnessPercent = 65;
                s.neuralPercent = 80;
                s.frameGen = FRAME_GEN_4X;
                s.temporal = 1;
                s.autoMfg = 1;
                break;
            case PRESET_BERSERK:
                // God-Level Graphics: 125% DLAA super-sampling, maximum neural
                // lighting and RCAS, uncapped frame generation. No tier check.
                s.upscaler = UPSCALER_DLSS;
                s.scalePercent = SCALE_DLAA_125;
                s.sharpnessPercent = 100;
                s.neuralPercent = 100;
                s.frameGen = FRAME_GEN_UNLIMITED;
                s.temporal = 1;
                s.autoMfg = 0;
                s.berserk = 1;
                break;
            case PRESET_SGSR_BATTERY:
                s.upscaler = UPSCALER_SGSR;
                s.scalePercent = SCALE_QUALITY;
                s.sharpnessPercent = 35;
                s.neuralPercent = 10;
                s.frameGen = FRAME_GEN_OFF;
                s.temporal = 0;
                s.autoMfg = 0;
                s.berserk = 0;
                break;
            case PRESET_OFF:
            default:
                s.preset = PRESET_OFF;
                break;
        }
        return s;
    }

    public GraphicsSettings copy() {
        return new GraphicsSettings(this);
    }

    /** True when the compositor has any work to do for this configuration. */
    public boolean active() {
        return upscaler != UPSCALER_OFF || frameGen != FRAME_GEN_OFF
                || neuralPercent > 0 || temporal != 0
                || scalePercent != SCALE_NATIVE;
    }

    /** True when the configuration bypasses the SoC recommendation. */
    public boolean isBerserk() {
        return berserk != 0;
    }

    /** True when uncapped frame generation or Berserk Mode is enabled. */
    public boolean isUncapped() {
        return frameGen == FRAME_GEN_UNLIMITED || berserk != 0;
    }

    /**
     * Turns Berserk on or off in place. Enabling rewrites every parameter to
     * the God-Level profile; disabling steps back to the DLSS-5 Ultra preset
     * so the device is not left with an uncapped multiplier by accident.
     */
    public void setBerserk(boolean enabled) {
        GraphicsSettings next = ofPreset(enabled ? PRESET_BERSERK : PRESET_DLSS_ULTRA);
        preset = next.preset;
        upscaler = next.upscaler;
        scalePercent = next.scalePercent;
        sharpnessPercent = next.sharpnessPercent;
        neuralPercent = next.neuralPercent;
        frameGen = next.frameGen;
        temporal = next.temporal;
        autoMfg = next.autoMfg;
        berserk = enabled ? 1 : 0;
    }

    public boolean isDlss() {
        return upscaler == UPSCALER_DLSS;
    }

    public String upscalerName() {
        switch (upscaler) {
            case UPSCALER_SGSR: return "SGSR";
            case UPSCALER_FSR: return "FSR";
            case UPSCALER_DLSS: return "DLSS-5";
            default: return "Off";
        }
    }

    public String frameGenName() {
        switch (frameGen) {
            case FRAME_GEN_2X: return "2x";
            case FRAME_GEN_3X: return "3x";
            case FRAME_GEN_4X: return "4x";
            case FRAME_GEN_5X: return "5x";
            case FRAME_GEN_8X: return "8x";
            case FRAME_GEN_UNLIMITED: return "UNLIMITED";
            default: return "Off";
        }
    }

    /** Compact label for the launcher card, e.g. "DLSS-5 85% · 3x". */
    public String shortLabel() {
        if (!active()) return "Native";
        StringBuilder out = new StringBuilder(upscalerName());
        if (upscaler != UPSCALER_OFF && scalePercent != SCALE_NATIVE) {
            out.append(' ').append(scalePercent).append('%');
        }
        if (frameGen != FRAME_GEN_OFF) out.append(" · ").append(frameGenName());
        if (autoMfg != 0) out.append(" auto");
        if (berserk != 0) out.append(" · BERSERK");
        return out.toString();
    }

    /** Badge text for the library card: the engine family, or nothing. */
    public String badge() {
        if (upscaler == UPSCALER_DLSS) return "DLSS-5";
        if (upscaler == UPSCALER_FSR) return "FSR";
        if (upscaler == UPSCALER_SGSR) return "SGSR";
        return "";
    }

    /** Marks the configuration as manually edited, so no preset stays lit. */
    public void markCustom() {
        preset = PRESET_CUSTOM;
    }

    @Override
    public String toString() {
        return "GraphicsSettings{p=" + preset + ", u=" + upscaler + ", scale=" + scalePercent
                + ", sharp=" + sharpnessPercent + ", neural=" + neuralPercent
                + ", fg=" + frameGen + ", temporal=" + temporal + ", auto=" + autoMfg + '}';
    }

    // ------------------------------------------------------------------
    // Serialisation
    // ------------------------------------------------------------------

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("preset", preset);
            o.put("upscaler", upscaler);
            o.put("scale", scalePercent);
            o.put("sharpness", sharpnessPercent);
            o.put("neural", neuralPercent);
            o.put("frameGen", frameGen);
            o.put("temporal", temporal);
            o.put("autoMfg", autoMfg);
            o.put("berserk", berserk);
        } catch (Exception ignored) {
            // JSONObject.put only throws for non-finite numbers; these are ints.
        }
        return o;
    }

    public static GraphicsSettings fromJson(JSONObject o, GraphicsSettings fallback) {
        GraphicsSettings base = fallback == null ? defaults() : fallback.copy();
        if (o == null) return base;
        GraphicsSettings out = new GraphicsSettings();
        out.upscaler = validUpscaler(o.optInt("upscaler", base.upscaler));
        out.scalePercent = validScale(o.optInt("scale", base.scalePercent));
        out.sharpnessPercent = clamp(o.optInt("sharpness", base.sharpnessPercent), 0, 100);
        out.neuralPercent = clamp(o.optInt("neural", base.neuralPercent), 0, 100);
        out.frameGen = validFrameGen(o.optInt("frameGen", base.frameGen));
        out.temporal = o.optInt("temporal", base.temporal) != 0 ? 1 : 0;
        out.autoMfg = o.optInt("autoMfg", base.autoMfg) != 0 ? 1 : 0;
        out.berserk = o.optInt("berserk", base.berserk) != 0 ? 1 : 0;
        out.preset = o.optInt("preset", base.preset);
        return out;
    }

    // ------------------------------------------------------------------
    // Per-game override (meta.json)
    // ------------------------------------------------------------------

    /** Reads the stored override, or null when this game uses the global default. */
    public static GraphicsSettings loadGame(File gameDir) {
        if (gameDir == null) return null;
        File meta = new File(gameDir, "meta.json");
        if (!meta.isFile()) return null;
        try {
            JSONObject root = new JSONObject(readText(meta));
            if (!root.has(META_KEY)) return null;
            return fromJson(root.optJSONObject(META_KEY), defaults());
        } catch (Exception e) {
            Log.w(TAG, "unreadable graphics settings in " + meta, e);
            return null;
        }
    }

    /** Writes (or replaces) the per-game override, preserving every other key. */
    public static void saveGame(File gameDir, GraphicsSettings settings) throws Exception {
        if (gameDir == null || settings == null) return;
        File meta = new File(gameDir, "meta.json");
        JSONObject root = meta.isFile() ? new JSONObject(readText(meta)) : new JSONObject();
        root.put(META_KEY, settings.toJson());

        File temp = new File(gameDir, "meta.json.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(root.toString(2).getBytes("UTF-8"));
            out.getFD().sync();
        }
        if (!temp.renameTo(meta)) {
            // Some external-storage mounts refuse rename across a directory
            // entry; fall back to writing in place.
            try (FileOutputStream out = new FileOutputStream(meta)) {
                out.write(root.toString(2).getBytes("UTF-8"));
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    /** Removes the override so the game follows the global default again. */
    public static void clearGame(File gameDir) {
        if (gameDir == null) return;
        File meta = new File(gameDir, "meta.json");
        if (!meta.isFile()) return;
        try {
            JSONObject root = new JSONObject(readText(meta));
            if (root.has(META_KEY)) {
                root.remove(META_KEY);
                try (FileOutputStream out = new FileOutputStream(meta)) {
                    out.write(root.toString(2).getBytes("UTF-8"));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "could not clear graphics settings in " + meta, e);
        }
    }

    // ------------------------------------------------------------------
    // Global defaults
    // ------------------------------------------------------------------

    public static GraphicsSettings global(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        GraphicsSettings s = defaults();
        s.preset = prefs.getInt("preset", PRESET_OFF);
        s.upscaler = validUpscaler(prefs.getInt("upscaler", UPSCALER_OFF));
        s.scalePercent = validScale(prefs.getInt("scale", SCALE_NATIVE));
        s.sharpnessPercent = clamp(prefs.getInt("sharpness", 0), 0, 100);
        s.neuralPercent = clamp(prefs.getInt("neural", 0), 0, 100);
        s.frameGen = validFrameGen(prefs.getInt("frameGen", FRAME_GEN_OFF));
        s.temporal = prefs.getInt("temporal", 0) != 0 ? 1 : 0;
        s.autoMfg = prefs.getInt("autoMfg", 0) != 0 ? 1 : 0;
        s.berserk = prefs.getInt("berserk", 0) != 0 ? 1 : 0;
        return s;
    }

    public static void saveGlobal(Context context, GraphicsSettings settings) {
        if (settings == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt("preset", settings.preset)
                .putInt("upscaler", settings.upscaler)
                .putInt("scale", settings.scalePercent)
                .putInt("sharpness", settings.sharpnessPercent)
                .putInt("neural", settings.neuralPercent)
                .putInt("frameGen", settings.frameGen)
                .putInt("temporal", settings.temporal)
                .putInt("autoMfg", settings.autoMfg)
                .putInt("berserk", settings.berserk)
                .apply();
    }

    /** The effective configuration for one game: override, else global default. */
    public static GraphicsSettings forGame(Context context, File gameDir) {
        GraphicsSettings override = loadGame(gameDir);
        return override != null ? override : global(context);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    private static int validUpscaler(int value) {
        return (value == UPSCALER_SGSR || value == UPSCALER_FSR
                || value == UPSCALER_DLSS) ? value : UPSCALER_OFF;
    }

    private static int validScale(int value) {
        // The sliders expose 50-125%: below 100 is upscaling, 125 is DLAA
        // super-sampling. The compositor clamps the target dimensions itself.
        return clamp(value, SCALE_PERFORMANCE, SCALE_DLAA_125);
    }

    private static int validFrameGen(int value) {
        switch (value) {
            case FRAME_GEN_UNLIMITED:
            case FRAME_GEN_2X:
            case FRAME_GEN_3X:
            case FRAME_GEN_4X:
            case FRAME_GEN_5X:
            case FRAME_GEN_8X:
                return value;
            default:
                return FRAME_GEN_OFF;
        }
    }

    private static int clamp(int value, int lo, int hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }
}

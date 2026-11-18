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
 * <p>The values map directly onto the native compositor:
 * {@code upscaler} 0/1/2, {@code scalePercent} 50/67/75/100,
 * {@code sharpnessPercent} 0-100 and {@code frameGen} 0/2.
 */
public final class GraphicsSettings {

    private static final String TAG = "ZaiBridge";
    private static final String META_KEY = "graphics";
    private static final String PREFS = "zai_graphics";

    public static final int UPSCALER_OFF = 0;
    public static final int UPSCALER_SGSR = 1;
    public static final int UPSCALER_FSR = 2;

    public static final int SCALE_PERFORMANCE = 50;
    public static final int SCALE_BALANCED = 67;
    public static final int SCALE_QUALITY = 75;
    public static final int SCALE_NATIVE = 100;

    public static final int FRAME_GEN_OFF = 0;
    public static final int FRAME_GEN_2X = 2;

    public int upscaler;
    public int scalePercent;
    public int sharpnessPercent;
    public int frameGen;

    public GraphicsSettings() {
        this(UPSCALER_OFF, SCALE_NATIVE, 50, FRAME_GEN_OFF);
    }

    public GraphicsSettings(int upscaler, int scalePercent, int sharpnessPercent,
                            int frameGen) {
        this.upscaler = validUpscaler(upscaler);
        this.scalePercent = validScale(scalePercent);
        this.sharpnessPercent = clamp(sharpnessPercent, 0, 100);
        this.frameGen = validFrameGen(frameGen);
    }

    public static GraphicsSettings defaults() {
        return new GraphicsSettings();
    }

    public GraphicsSettings copy() {
        return new GraphicsSettings(upscaler, scalePercent, sharpnessPercent, frameGen);
    }

    /** True when the compositor has any work to do for this configuration. */
    public boolean active() {
        return upscaler != UPSCALER_OFF || frameGen != FRAME_GEN_OFF;
    }

    public String upscalerName() {
        switch (upscaler) {
            case UPSCALER_SGSR: return "SGSR";
            case UPSCALER_FSR: return "FSR 1.0";
            default: return "Off";
        }
    }

    /** Compact label for the launcher card, e.g. "SGSR 67% · 2x". */
    public String shortLabel() {
        StringBuilder out = new StringBuilder(upscalerName());
        if (upscaler != UPSCALER_OFF && scalePercent != SCALE_NATIVE) {
            out.append(' ').append(scalePercent).append('%');
        }
        if (frameGen == FRAME_GEN_2X) out.append(" · 2x");
        return out.toString();
    }

    @Override
    public String toString() {
        return "GraphicsSettings{u=" + upscaler + ", scale=" + scalePercent
                + ", sharp=" + sharpnessPercent + ", fg=" + frameGen + '}';
    }

    // ------------------------------------------------------------------
    // Serialisation
    // ------------------------------------------------------------------

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("upscaler", upscaler);
            o.put("scale", scalePercent);
            o.put("sharpness", sharpnessPercent);
            o.put("frameGen", frameGen);
        } catch (Exception ignored) {
            // JSONObject.put only throws for non-finite numbers; these are ints.
        }
        return o;
    }

    public static GraphicsSettings fromJson(JSONObject o, GraphicsSettings fallback) {
        GraphicsSettings base = fallback == null ? defaults() : fallback.copy();
        if (o == null) return base;
        return new GraphicsSettings(
                o.optInt("upscaler", base.upscaler),
                o.optInt("scale", base.scalePercent),
                o.optInt("sharpness", base.sharpnessPercent),
                o.optInt("frameGen", base.frameGen));
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
        return new GraphicsSettings(
                prefs.getInt("upscaler", UPSCALER_OFF),
                prefs.getInt("scale", SCALE_NATIVE),
                prefs.getInt("sharpness", 50),
                prefs.getInt("frameGen", FRAME_GEN_OFF));
    }

    public static void saveGlobal(Context context, GraphicsSettings settings) {
        if (settings == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt("upscaler", settings.upscaler)
                .putInt("scale", settings.scalePercent)
                .putInt("sharpness", settings.sharpnessPercent)
                .putInt("frameGen", settings.frameGen)
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
        return (value == UPSCALER_SGSR || value == UPSCALER_FSR) ? value : UPSCALER_OFF;
    }

    private static int validScale(int value) {
        switch (value) {
            case SCALE_PERFORMANCE:
            case SCALE_BALANCED:
            case SCALE_QUALITY:
            case SCALE_NATIVE:
                return value;
            default:
                return SCALE_NATIVE;
        }
    }

    private static int validFrameGen(int value) {
        return value == FRAME_GEN_2X ? FRAME_GEN_2X : FRAME_GEN_OFF;
    }

    private static int clamp(int value, int lo, int hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }
}

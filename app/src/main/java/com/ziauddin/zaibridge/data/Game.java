package com.ziauddin.zaibridge.data;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * One imported game. Everything here comes from the guest APK's manifest plus
 * the bookkeeping Zai-Bridge wrote during ingestion; the OBB is tracked by name
 * only and is never surfaced by the launcher.
 */
public final class Game {

    private static final String TAG = "ZaiBridge";

    public final String directoryName;
    public final String packageName;
    public final String label;
    public final String launcherActivity;
    /** The launch Activity's {@code android:screenOrientation}, as declared. */
    public final String orientation;
    public final String iconFile;
    public final String obbFile;
    public final String abi;
    public final long importedAt;
    public final long sizeBytes;
    /** NativeActivity metadata: android.app.func_name (custom entry point, e.g. UnityInitApplication) */
    public final String funcName;
    /** NativeActivity metadata: android.app.lib_name (native library name, e.g. "unity") */
    public final String libName;

    public Game(String directoryName, String packageName, String label, String launcherActivity,
                String orientation, String iconFile, String obbFile, String abi,
                long importedAt, long sizeBytes, String funcName, String libName) {
        this.directoryName = directoryName;
        this.packageName = packageName;
        this.label = label;
        this.launcherActivity = launcherActivity;
        this.orientation = orientation == null || orientation.isEmpty()
                ? "unspecified" : orientation;
        this.iconFile = iconFile;
        this.obbFile = obbFile;
        this.abi = abi;
        this.importedAt = importedAt;
        this.sizeBytes = sizeBytes;
        this.funcName = funcName;
        this.libName = libName;
    }

    public boolean hasObb() {
        return obbFile != null && !obbFile.isEmpty();
    }

    public JSONObject toJson(String directoryName) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("directory", directoryName);
        o.put("package", packageName);
        o.put("label", label);
        o.put("launcher", launcherActivity);
        o.put("orientation", orientation);
        o.put("icon", iconFile);
        o.put("obb", obbFile);
        o.put("abi", abi);
        o.put("importedAt", importedAt);
        o.put("sizeBytes", sizeBytes);
        o.put("funcName", funcName == null ? "" : funcName);
        o.put("libName", libName == null ? "" : libName);
        return o;
    }

    /**
     * Reads meta.json from an ingested game directory. Returns null when the
     * directory is not a completed import (staging folders, partial copies).
     */
    public static Game load(File dir) {
        File meta = new File(dir, "meta.json");
        if (!meta.isFile()) return null;
        try {
            JSONObject o = new JSONObject(readText(meta));
            return new Game(
                    dir.getName(),
                    o.optString("package"),
                    o.optString("label", o.optString("package")),
                    o.optString("launcher"),
                    // Imports made before orientation was recorded have no key;
                    // the guest keeps the device's orientation, as they did then.
                    o.optString("orientation", "unspecified"),
                    o.optString("icon"),
                    o.optString("obb"),
                    o.optString("abi", "armeabi-v7a"),
                    o.optLong("importedAt", 0L),
                    o.optLong("sizeBytes", 0L),
                    o.optString("funcName", ""),
                    o.optString("libName", ""));
        } catch (Exception e) {
            Log.w(TAG, "unreadable game metadata in " + dir.getName(), e);
            return null;
        }
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(f.length(), 8192));
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}

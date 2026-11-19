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
 * One imported game. Supports both legacy 32-bit ARM games ("unicorn32") and
 * modern 64-bit / Split-APK installed games ("native64").
 */
public final class Game {

    private static final String TAG = "ZaiBridge";

    public static final String MODE_NATIVE64 = "native64";
    public static final String MODE_UNICORN32 = "unicorn32";

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
    /** Execution mode: "native64" (direct 64-bit host execution) or "unicorn32" (ARM32 translation) */
    public final String runtimeMode;
    /** Number of split APKs bundled alongside base.apk (0 for standalone APK). */
    public final int splitCount;

    public Game(String directoryName, String packageName, String label, String launcherActivity,
                String orientation, String iconFile, String obbFile, String abi,
                long importedAt, long sizeBytes, String funcName, String libName) {
        this(directoryName, packageName, label, launcherActivity, orientation, iconFile,
                obbFile, abi, importedAt, sizeBytes, funcName, libName,
                (abi != null && (abi.contains("arm64") || abi.contains("x86_64")))
                        ? MODE_NATIVE64 : MODE_UNICORN32,
                0);
    }

    public Game(String directoryName, String packageName, String label, String launcherActivity,
                String orientation, String iconFile, String obbFile, String abi,
                long importedAt, long sizeBytes, String funcName, String libName,
                String runtimeMode, int splitCount) {
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
        this.runtimeMode = (runtimeMode == null || runtimeMode.isEmpty())
                ? MODE_UNICORN32 : runtimeMode;
        this.splitCount = Math.max(0, splitCount);
    }

    public boolean hasObb() {
        return obbFile != null && !obbFile.isEmpty();
    }

    public boolean isNative64() {
        return MODE_NATIVE64.equalsIgnoreCase(runtimeMode)
                || (abi != null && (abi.contains("arm64") || abi.contains("x86_64")));
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
        o.put("runtimeMode", runtimeMode);
        o.put("splitCount", splitCount);
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
            String abi = o.optString("abi", "armeabi-v7a");
            String defaultMode = (abi.contains("arm64") || abi.contains("x86_64")
                    || new File(dir, "native64").isDirectory())
                    ? MODE_NATIVE64 : MODE_UNICORN32;
            return new Game(
                    dir.getName(),
                    o.optString("package"),
                    o.optString("label", o.optString("package")),
                    o.optString("launcher"),
                    o.optString("orientation", "unspecified"),
                    o.optString("icon"),
                    o.optString("obb"),
                    abi,
                    o.optLong("importedAt", 0L),
                    o.optLong("sizeBytes", 0L),
                    o.optString("funcName", ""),
                    o.optString("libName", ""),
                    o.optString("runtimeMode", defaultMode),
                    o.optInt("splitCount", 0));
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

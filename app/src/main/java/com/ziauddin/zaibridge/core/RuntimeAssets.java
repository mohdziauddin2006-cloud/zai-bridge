package com.ziauddin.zaibridge.core;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Materialises {@code assets/runtime/} into app-private storage once per
 * install.
 *
 * <p>The 32-bit compatibility sysroot ships compressed inside the APK, but a
 * guest ELF's {@code DT_NEEDED} resolver needs real paths to {@code mmap}. This
 * class unpacks the bundle verbatim into
 * {@code files/runtime/<assetVersion>/} and hands that path to the native core,
 * which searches it after the payload directory.
 */
public final class RuntimeAssets {

    private static final String TAG = "ZaiBridge";

    /** Bump when the bundled runtime changes, to force a re-extract. */
    private static final String VERSION = "r1";

    private static volatile File root;

    private RuntimeAssets() {
    }

    /** Blocks on first call only; later calls return the cached directory. */
    public static synchronized File ensure(Context ctx) {
        if (root != null) return root;

        File base = new File(ctx.getFilesDir(), "runtime");
        File dir = new File(base, VERSION);
        File stamp = new File(dir, ".complete");
        if (stamp.isFile()) {
            root = dir;
            return root;
        }

        try {
            deleteTree(dir);
            if (!dir.mkdirs() && !dir.isDirectory()) {
                Log.e(TAG, "runtime: cannot create " + dir);
                root = dir;  // still return it: the native side probes and skips
                return root;
            }
            copyTree(ctx.getAssets(), "runtime", dir);
            // A marker file, so a partially copied bundle is never reused.
            try (OutputStream out = new FileOutputStream(stamp)) {
                out.write(VERSION.getBytes("US-ASCII"));
            }
            root = dir;
            Log.i(TAG, "runtime assets extracted to " + dir + " (" + dirSize(dir) + " bytes)");
        } catch (Throwable t) {
            Log.e(TAG, "runtime extraction failed", t);
            root = dir;
        }
        return root;
    }

    /** {@code files/runtime/<v>/sysroot_arm32}, or null when absent. */
    public static File arm32Sysroot(Context ctx) {
        File dir = new File(ensure(ctx), "sysroot_arm32");
        return dir.isDirectory() ? dir : null;
    }

    public static File x86Sysroot(Context ctx) {
        File dir = new File(ensure(ctx), "sysroot_x86");
        return dir.isDirectory() ? dir : null;
    }

    private static void copyTree(AssetManager assets, String assetPath, File target)
            throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            copyFile(assets, assetPath, target);
            return;
        }
        if (!target.mkdirs() && !target.isDirectory()) {
            throw new IOException("mkdirs failed: " + target);
        }
        for (String child : children) {
            copyTree(assets, assetPath + "/" + child, new File(target, child));
        }
    }

    private static void copyFile(AssetManager assets, String assetPath, File target)
            throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("mkdirs failed: " + parent);
        }
        try (InputStream in = assets.open(assetPath);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static long dirSize(File dir) {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            total += f.isDirectory() ? dirSize(f) : f.length();
        }
        return total;
    }

    private static void deleteTree(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteTree(f);
                else f.delete();
            }
        }
        dir.delete();
    }
}

package com.ziauddin.zaibridge.data;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import com.ziauddin.zaibridge.R;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The ingestion engine.
 *
 * <pre>
 *   games/&lt;package&gt;/
 *     game.apk            original APK, untouched (DexClassLoader source)
 *     icon.png            launcher icon rendered from the guest manifest
 *     meta.json           name / package / launcher / ABI / obb bookkeeping
 *     payload/
 *       libxxx32.so       every armeabi-v7a library, renamed for the core
 *       dex/classes*.dex  extracted dex, kept for inspection / future use
 *     obb/&lt;name&gt;.obb      expansion data, never listed by the UI
 * </pre>
 *
 * The directory is assembled under {@code .staging-*} and renamed into place in
 * one step, so the launcher can never observe a half-imported game.
 */
public final class GameImporter {

    private static final String TAG = "ZaiBridge";
    private static final String EXPANSION_DIR = "obb";

    public interface Progress {
        void onProgress(String message);
    }

    private GameImporter() {
    }

    public static Game importGame(Context context, Uri apkUri, Uri obbUri, Progress progress)
            throws Exception {
        File root = GameRepository.root(context);
        File staging = new File(root, ".staging-" + System.currentTimeMillis());
        if (!staging.mkdirs()) throw new IOException("Cannot create staging directory");

        try {
            step(progress, "Copying APK");
            File apk = new File(staging, "game.apk");
            copyUri(context, apkUri, apk);
            // ART refuses to open a writable dex source ("Writable dex file is
            // not allowed"), so the archive must be read-only before it is
            // handed to DexClassLoader.
            makeReadOnly(apk);

            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_ACTIVITIES);
            if (info == null || info.applicationInfo == null) {
                throw new IOException("Not a readable Android package");
            }

            // getApplicationLabel / getApplicationIcon need to be pointed at the
            // archive, not at an installed application record.
            ApplicationInfo app = info.applicationInfo;
            app.sourceDir = apk.getAbsolutePath();
            app.publicSourceDir = apk.getAbsolutePath();

            String label = safeLabel(pm, app, info.packageName);
            String launcher = pickLauncherActivity(info);

            step(progress, "Extracting icon");
            File icon = new File(staging, "icon.png");
            writeIcon(pm, app, label, icon);

            step(progress, "Extracting 32-bit libraries");
            File payload = new File(staging, "payload");
            File dexDir = new File(payload, "dex");
            if (!payload.mkdirs()) throw new IOException("Cannot create payload directory");

            int libs = extractApkContents(apk, payload, dexDir);
            if (libs == 0) {
                throw new IOException(context.getString(R.string.import_no_32bit));
            }

            String obbName = "";
            if (obbUri != null) {
                step(progress, "Moving expansion file");
                String name = sanitize(displayName(context, obbUri, "main.obb"));
                if (!name.toLowerCase().endsWith(".obb")) name = name + ".obb";
                File obbDir = new File(staging, EXPANSION_DIR);
                if (!obbDir.mkdirs()) throw new IOException("Cannot create obb directory");
                copyUri(context, obbUri, new File(obbDir, name));
                obbName = EXPANSION_DIR + "/" + name;
            }

            long size = treeSize(staging);
            Game game = new Game(
                    safeDirName(info.packageName),
                    info.packageName,
                    label,
                    launcher,
                    "icon.png",
                    obbName,
                    "armeabi-v7a",
                    System.currentTimeMillis(),
                    size);

            step(progress, "Finalising");
            writeMeta(staging, game);
            publish(staging, new File(root, game.directoryName));
            Log.i(TAG, "imported " + info.packageName + " (" + libs + " payload libraries, "
                    + size + " bytes)");
            return game;
        } catch (Exception e) {
            deleteTree(staging);
            throw e;
        }
    }

    private static void step(Progress progress, String message) {
        if (progress != null) progress.onProgress(message);
    }

    // ---------------------------------------------------------------------
    // APK contents
    // ---------------------------------------------------------------------

    /**
     * Pulls the legacy ARM libraries and the dex out of the APK. The guest
     * libraries are renamed {@code libfoo.so -> libfoo32.so}: that suffix is the
     * only contract the native core needs, so the same core can translate any
     * APK without knowing a single symbol name.
     */
    private static int extractApkContents(File apk, File payload, File dexDir)
            throws IOException {
        int libs = 0;
        if (!dexDir.exists() && !dexDir.mkdirs()) {
            throw new IOException("Cannot create dex directory");
        }

        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();

                if (name.startsWith("lib/armeabi-v7a/")
                        || name.startsWith("lib/armeabi/")
                        || name.startsWith("lib/arm/")) {
                    if (!name.endsWith(".so")) continue;   // gdbserver, etc.
                    String base = name.substring(name.lastIndexOf('/') + 1);
                    if (!base.startsWith("lib")) continue;
                    File out = new File(payload, payloadName(base));
                    copyStream(zip.getInputStream(entry), out);
                    Log.i(TAG, "payload library " + base + " -> " + out.getName()
                            + " (" + out.length() + " bytes)");
                    ++libs;
                } else if (isDex(name)) {
                    File dex = new File(dexDir, name);
                    copyStream(zip.getInputStream(entry), dex);
                    makeReadOnly(dex);
                }
            }
        }
        return libs;
    }

    private static String payloadName(String base) {
        // libfoo.so -> libfoo32.so   (already-suffixed names are left alone)
        if (base.endsWith("32.so")) return base;
        return base.substring(0, base.length() - 3) + "32.so";
    }

    private static boolean isDex(String name) {
        return name.startsWith("classes") && name.endsWith(".dex") && name.indexOf('/') < 0;
    }

    // ---------------------------------------------------------------------
    // Manifest metadata
    // ---------------------------------------------------------------------

    private static String safeLabel(PackageManager pm, ApplicationInfo app, String fallback) {
        try {
            CharSequence label = pm.getApplicationLabel(app);
            if (label != null && label.length() > 0) return label.toString();
        } catch (Throwable ignored) {
            // Unparseable resource table; the package name still identifies it.
        }
        return fallback;
    }

    /**
     * Best-effort launch component. PackageManager cannot expose intent filters
     * for an archive without installing it, so the preference order is: a class
     * literally called MainActivity, then the first declared activity (which is
     * the launcher in the overwhelming majority of manifests).
     */
    private static String pickLauncherActivity(PackageInfo info) {
        ActivityInfo[] activities = info.activities;
        if (activities == null || activities.length == 0) return "";
        for (ActivityInfo a : activities) {
            if (a.name != null && a.name.contains("MainActivity")) return a.name;
        }
        return activities[0].name == null ? "" : activities[0].name;
    }

    private static void writeIcon(PackageManager pm, ApplicationInfo app, String label, File out) {
        try {
            Drawable drawable = pm.getApplicationIcon(app);
            Bitmap bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            bmp.recycle();
            return;
        } catch (Throwable t) {
            Log.w(TAG, "guest icon unreadable, drawing a placeholder", t);
        }
        writePlaceholderIcon(label, out);
    }

    private static void writePlaceholderIcon(String label, File out) {
        final int size = 192;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.parseColor("#16232F"));
        canvas.drawRoundRect(new RectF(0, 0, size, size), 40, 40, paint);
        paint.setColor(Color.parseColor("#FFB454"));
        paint.setTextSize(96);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        String letter = (label == null || label.isEmpty()) ? "?" : label.substring(0, 1);
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(letter, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, paint);
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        } catch (IOException e) {
            Log.w(TAG, "placeholder icon not written", e);
        }
        bmp.recycle();
    }

    // ---------------------------------------------------------------------
    // Filesystem helpers
    // ---------------------------------------------------------------------

    private static void writeMeta(File staging, Game game) throws IOException {
        try (OutputStream os = new BufferedOutputStream(
                new FileOutputStream(new File(staging, "meta.json")))) {
            os.write(game.toJson(game.directoryName).toString(2).getBytes("UTF-8"));
        } catch (org.json.JSONException e) {
            throw new IOException("Cannot serialise metadata", e);
        }
    }

    /**
     * Renames the staging tree onto its final name. Both paths are inside
     * filesDir, so this is a single atomic rename on the same filesystem.
     */
    private static void publish(File staging, File target) throws IOException {
        if (target.exists() && !deleteTree(target)) {
            throw new IOException("Cannot replace existing import " + target.getName());
        }
        if (!staging.renameTo(target)) {
            throw new IOException("Cannot move import into place");
        }
    }

    private static void copyUri(Context context, Uri uri, File dst) throws IOException {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Cannot open " + uri);
        try {
            copyStream(in, dst);
        } finally {
            in.close();
        }
    }

    private static void copyStream(InputStream in, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        try (InputStream is = in;
             OutputStream os = new BufferedOutputStream(new FileOutputStream(dst), 1 << 20)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private static void makeReadOnly(File file) {
        if (file.isFile()) {
            file.setReadable(true, false);
            file.setWritable(false, false);
        }
    }

    private static long treeSize(File file) {
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) total += treeSize(child);
        }
        return total;
    }

    private static boolean deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        return file.delete();
    }

    private static String displayName(Context context, Uri uri, String fallback) {
        try (Cursor c = context.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
            // Provider without DISPLAY_NAME; the fallback is fine.
        }
        return fallback;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safeDirName(String packageName) {
        return sanitize(packageName == null || packageName.isEmpty() ? "unknown" : packageName);
    }
}

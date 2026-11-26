package com.ziauddin.zaibridge.data;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
import android.os.Environment;
import android.provider.OpenableColumns;
import android.util.Log;

import com.ziauddin.zaibridge.R;
import com.ziauddin.zaibridge.core.GameOrientation;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The universal ingestion engine.
 *
 * <pre>
 *   games/&lt;package&gt;/
 *     game.apk                  base APK, marked read-only (DexClassLoader source)
 *     splits/split_*.apk        optional Split APKs / App Bundle slices, marked read-only
 *     icon.png                  launcher icon rendered from the guest manifest
 *     meta.json                 name / package / launcher / ABI / runtimeMode / obb bookkeeping
 *     native64/libxxx.so        extracted 64-bit libraries for "native64" direct host execution
 *     payload/libxxx32.so       extracted 32-bit libraries renamed for "unicorn32" translation
 *     payload/dex/classes*.dex  extracted dex, marked read-only for Android 14–16 ART
 *     native/libxxx.so          32-bit passthrough / bridge alias directory
 *     obb/&lt;name&gt;.obb            expansion data, hidden from the launcher grid
 * </pre>
 *
 * The directory is assembled under {@code .staging-*} and renamed into place in
 * one atomic step, so the launcher can never observe a half-imported game.
 */
public final class GameImporter {

    private static final String TAG = "ZaiBridge";
    private static final String EXPANSION_DIR = "obb";

    public interface Progress {
        void onProgress(String message);
    }

    private GameImporter() {
    }

    /**
     * Imports a standalone APK (32-bit or 64-bit) and optional OBB from storage.
     */
    public static Game importGame(Context context, Uri apkUri, Uri obbUri, Progress progress)
            throws Exception {
        File root = GameRepository.root(context);
        File staging = new File(root, ".staging-" + System.currentTimeMillis());
        if (!staging.mkdirs()) throw new IOException("Cannot create staging directory");

        try {
            step(progress, "Copying APK");
            File apk = new File(staging, "game.apk");
            copyUri(context, apkUri, apk);
            // ART on Android 14–16 refuses to open a writable dex source
            // ("Writable dex file is not allowed").
            makeReadOnly(apk);

            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
            if (info == null || info.applicationInfo == null) {
                throw new IOException("Not a readable Android package");
            }

            ApplicationInfo app = info.applicationInfo;
            app.sourceDir = apk.getAbsolutePath();
            app.publicSourceDir = apk.getAbsolutePath();

            String label = safeLabel(pm, app, info.packageName);
            String launcher = pickLauncherActivity(pm, info);

            ActivityInfo launchActivity = findActivity(info, launcher);
            String orientation = GameOrientation.nameOf(launchActivity == null
                    ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    : launchActivity.screenOrientation);

            String funcName = "";
            String libName = "";
            if (launchActivity != null && launchActivity.metaData != null) {
                funcName = launchActivity.metaData.getString("android.app.func_name", "");
                libName = launchActivity.metaData.getString("android.app.lib_name", "");
            }

            step(progress, "Extracting icon");
            File icon = new File(staging, "icon.png");
            writeIcon(pm, app, label, icon);

            step(progress, "Extracting native libraries & DEX");
            File payload = new File(staging, "payload");
            File dexDir = new File(payload, "dex");
            File native64Dir = new File(staging, "native64");
            File native32Dir = new File(staging, "native");
            if (!payload.mkdirs() || !native64Dir.mkdirs() || !native32Dir.mkdirs()) {
                throw new IOException("Cannot create payload directories");
            }

            List<File> archives = new ArrayList<>();
            archives.add(apk);
            ExtractionStats stats = extractArchives(archives, null, payload, dexDir,
                    native64Dir, native32Dir);

            String runtimeMode;
            String primaryAbi;
            if (stats.libs64 > 0) {
                runtimeMode = Game.MODE_NATIVE64;
                primaryAbi = stats.abi64 != null ? stats.abi64 : "arm64-v8a";
            } else if (stats.libs32 > 0) {
                runtimeMode = Game.MODE_UNICORN32;
                primaryAbi = "armeabi-v7a";
            } else if (stats.dexCount > 0) {
                // Pure Java/Kotlin APK runs natively on the 64-bit host
                runtimeMode = Game.MODE_NATIVE64;
                primaryAbi = "arm64-v8a";
            } else {
                throw new IOException(context.getString(R.string.import_no_32bit));
            }

            String obbName = "";
            if (obbUri != null) {
                step(progress, "Moving expansion file");
                String name = sanitize(displayName(context, obbUri, "main.obb"));
                if (!name.toLowerCase(Locale.ROOT).endsWith(".obb")) name = name + ".obb";
                File obbDir = new File(staging, EXPANSION_DIR);
                if (!obbDir.mkdirs() && !obbDir.isDirectory()) {
                    throw new IOException("Cannot create obb directory");
                }
                copyUri(context, obbUri, new File(obbDir, name));
                obbName = EXPANSION_DIR + "/" + name;
            } else {
                obbName = copyDeviceObbsIfPresent(info.packageName, staging, progress);
            }

            long size = treeSize(staging);
            Game game = new Game(
                    safeDirName(info.packageName),
                    info.packageName,
                    label,
                    launcher,
                    orientation,
                    "icon.png",
                    obbName,
                    primaryAbi,
                    System.currentTimeMillis(),
                    size,
                    funcName,
                    libName,
                    runtimeMode,
                    0);

            step(progress, "Finalising");
            writeMeta(staging, game);
            publish(staging, new File(root, game.directoryName));
            Log.i(TAG, "imported " + info.packageName + " (mode=" + runtimeMode
                    + ", libs64=" + stats.libs64 + ", libs32=" + stats.libs32
                    + ", size=" + size + " bytes)");
            return game;
        } catch (Exception e) {
            deleteTree(staging);
            throw e;
        }
    }

    /**
     * Imports an already-installed 64-bit or 32-bit game directly from the device,
     * including all Split APKs ({@code split_config.*.apk}), installed native
     * libraries ({@code appInfo.nativeLibraryDir}), and any OBB expansion files
     * under {@code /Android/obb/<pkg>/}.
     */
    public static Game importInstalledPackage(Context context, String packageName,
                                              Progress progress) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo info;
        ApplicationInfo app;
        try {
            info = pm.getPackageInfo(packageName,
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
            app = info != null && info.applicationInfo != null
                    ? info.applicationInfo
                    : pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("Installed package not found: " + packageName, e);
        }

        if (app == null || app.sourceDir == null) {
            throw new IOException("Unreadable installed application: " + packageName);
        }

        File root = GameRepository.root(context);
        File staging = new File(root, ".staging-" + System.currentTimeMillis());
        if (!staging.mkdirs()) throw new IOException("Cannot create staging directory");

        try {
            step(progress, "Copying base.apk (" + packageName + ")");
            File baseSrc = new File(app.sourceDir);
            File apk = new File(staging, "game.apk");
            copyFile(baseSrc, apk);
            makeReadOnly(apk);

            List<File> archives = new ArrayList<>();
            archives.add(apk);

            int splitCount = 0;
            if (app.splitSourceDirs != null && app.splitSourceDirs.length > 0) {
                File splitsDir = new File(staging, "splits");
                if (!splitsDir.mkdirs() && !splitsDir.isDirectory()) {
                    throw new IOException("Cannot create splits directory");
                }
                for (int i = 0; i < app.splitSourceDirs.length; i++) {
                    String splitPath = app.splitSourceDirs[i];
                    if (splitPath == null) continue;
                    File splitSrc = new File(splitPath);
                    if (!splitSrc.isFile()) continue;

                    String splitName = sanitize(splitSrc.getName());
                    if (!splitName.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                        splitName = "split_" + i + "_" + splitName + ".apk";
                    }
                    step(progress, "Copying split APK (" + (i + 1) + "/"
                            + app.splitSourceDirs.length + "): " + splitName);
                    File splitDst = new File(splitsDir, splitName);
                    copyFile(splitSrc, splitDst);
                    makeReadOnly(splitDst);
                    archives.add(splitDst);
                    splitCount++;
                }
            }

            String label = safeLabel(pm, app, packageName);
            String launcher = pickLauncherActivity(pm, info);
            ActivityInfo launchActivity = findActivity(info, launcher);
            String orientation = GameOrientation.nameOf(launchActivity == null
                    ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    : launchActivity.screenOrientation);

            String funcName = "";
            String libName = "";
            if (launchActivity != null && launchActivity.metaData != null) {
                funcName = launchActivity.metaData.getString("android.app.func_name", "");
                libName = launchActivity.metaData.getString("android.app.lib_name", "");
            }

            step(progress, "Extracting icon");
            File icon = new File(staging, "icon.png");
            writeIcon(pm, app, label, icon);

            step(progress, "Extracting 64-bit / 32-bit native engine libraries");
            File payload = new File(staging, "payload");
            File dexDir = new File(payload, "dex");
            File native64Dir = new File(staging, "native64");
            File native32Dir = new File(staging, "native");
            if (!payload.mkdirs() || !native64Dir.mkdirs() || !native32Dir.mkdirs()) {
                throw new IOException("Cannot create native directories");
            }

            ExtractionStats stats = extractArchives(archives, app.nativeLibraryDir,
                    payload, dexDir, native64Dir, native32Dir);

            String runtimeMode;
            String primaryAbi;
            if (stats.libs64 > 0) {
                runtimeMode = Game.MODE_NATIVE64;
                primaryAbi = stats.abi64 != null ? stats.abi64 : "arm64-v8a";
            } else if (stats.libs32 > 0) {
                runtimeMode = Game.MODE_UNICORN32;
                primaryAbi = "armeabi-v7a";
            } else {
                runtimeMode = Game.MODE_NATIVE64;
                primaryAbi = "arm64-v8a";
            }

            String obbName = copyDeviceObbsIfPresent(packageName, staging, progress);

            long size = treeSize(staging);
            Game game = new Game(
                    safeDirName(packageName),
                    packageName,
                    label,
                    launcher,
                    orientation,
                    "icon.png",
                    obbName,
                    primaryAbi,
                    System.currentTimeMillis(),
                    size,
                    funcName,
                    libName,
                    runtimeMode,
                    splitCount);

            step(progress, "Finalising " + label);
            writeMeta(staging, game);
            publish(staging, new File(root, game.directoryName));
            Log.i(TAG, "imported installed package " + packageName
                    + " (mode=" + runtimeMode + ", splits=" + splitCount
                    + ", libs64=" + stats.libs64 + ", libs32=" + stats.libs32
                    + ", size=" + size + " bytes)");
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
    // Archive & Native Library Extraction (Base + Splits + nativeLibraryDir)
    // ---------------------------------------------------------------------

    private static final class ExtractionStats {
        int libs64 = 0;
        int libs32 = 0;
        int dexCount = 0;
        String abi64 = null;
    }

    private static ExtractionStats extractArchives(List<File> archives,
                                                   String installedNativeLibDir,
                                                   File payload32Dir,
                                                   File dexDir,
                                                   File native64Dir,
                                                   File native32Dir) throws IOException {
        ExtractionStats stats = new ExtractionStats();
        if (!dexDir.exists() && !dexDir.mkdirs()) {
            throw new IOException("Cannot create dex directory");
        }

        int archiveIdx = 0;
        for (File apk : archives) {
            archiveIdx++;
            if (!apk.isFile()) continue;
            try (ZipFile zip = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();

                    if (name.startsWith("lib/arm64-v8a/") || name.startsWith("lib/x86_64/")) {
                        if (!name.endsWith(".so")) continue;
                        String base = name.substring(name.lastIndexOf('/') + 1);
                        if (!base.startsWith("lib")) continue;
                        File out64 = new File(native64Dir, base);
                        copyStream(zip.getInputStream(entry), out64);
                        out64.setReadable(true, false);
                        out64.setExecutable(true, false);
                        if (stats.abi64 == null) {
                            stats.abi64 = name.startsWith("lib/x86_64/") ? "x86_64" : "arm64-v8a";
                        }
                        stats.libs64++;
                        Log.i(TAG, "extracted 64-bit library " + base
                                + " (" + out64.length() + " bytes)");
                    } else if (name.startsWith("lib/armeabi-v7a/")
                            || name.startsWith("lib/armeabi/")
                            || name.startsWith("lib/arm/")) {
                        // 32-bit is always translated by the ARM32 Unicorn core,
                        // which maps EM_ARM only. A lib/x86 copy staged under the
                        // same payload name would overwrite the ARM library and
                        // the guest would fail with UnsatisfiedLinkError on its
                        // first UnityPlayer native call, so x86 is skipped here.
                        if (!name.endsWith(".so")) continue;
                        String base = name.substring(name.lastIndexOf('/') + 1);
                        if (!base.startsWith("lib")) continue;
                        File outPayload = new File(payload32Dir, payloadName(base));
                        copyStream(zip.getInputStream(entry), outPayload);
                        File outNative32 = new File(native32Dir, base);
                        copyFile(outPayload, outNative32);
                        stats.libs32++;
                        Log.i(TAG, "extracted 32-bit library " + base + " -> "
                                + outPayload.getName() + " (" + outPayload.length() + " bytes)");
                    } else if (isDex(name)) {
                        String dexOutName = archiveIdx == 1
                                ? name : ("split" + archiveIdx + "_" + name);
                        File dex = new File(dexDir, dexOutName);
                        copyStream(zip.getInputStream(entry), dex);
                        makeReadOnly(dex);
                        stats.dexCount++;
                    }
                }
            }
        }

        // Also mirror installed nativeLibraryDir if Android already extracted .so files on disk
        if (installedNativeLibDir != null && !installedNativeLibDir.isEmpty()) {
            File sysLibDir = new File(installedNativeLibDir);
            File[] soFiles = sysLibDir.isDirectory()
                    ? sysLibDir.listFiles((d, name) -> name.endsWith(".so")) : null;
            if (soFiles != null && soFiles.length > 0) {
                String lowerPath = installedNativeLibDir.toLowerCase(Locale.ROOT);
                boolean dirIs64 = lowerPath.contains("arm64") || lowerPath.contains("x86_64")
                        || isElf64(soFiles[0]);
                for (File so : soFiles) {
                    if (!so.isFile()) continue;
                    String base = so.getName();
                    if (dirIs64) {
                        File dst = new File(native64Dir, base);
                        if (!dst.isFile() || dst.length() != so.length()) {
                            copyFile(so, dst);
                            dst.setReadable(true, false);
                            dst.setExecutable(true, false);
                            stats.libs64++;
                        }
                        if (stats.abi64 == null) {
                            stats.abi64 = lowerPath.contains("x86_64") ? "x86_64" : "arm64-v8a";
                        }
                    } else {
                        // Same rule as above: an x86 nativeLibraryDir cannot feed
                        // the ARM32 translation core.
                        if (lowerPath.contains("/x86") && !lowerPath.contains("x86_64")) {
                            continue;
                        }
                        File dstPayload = new File(payload32Dir, payloadName(base));
                        if (!dstPayload.isFile() || dstPayload.length() != so.length()) {
                            copyFile(so, dstPayload);
                            stats.libs32++;
                        }
                        File dstNative32 = new File(native32Dir, base);
                        if (!dstNative32.isFile() || dstNative32.length() != so.length()) {
                            copyFile(so, dstNative32);
                        }
                    }
                }
            }
        }

        return stats;
    }

    /** Checks the ELF header (EI_CLASS byte at offset 4: 2 = 64-bit, 1 = 32-bit). */
    private static boolean isElf64(File soFile) {
        try (InputStream in = new FileInputStream(soFile)) {
            byte[] hdr = new byte[5];
            if (in.read(hdr) == 5 && hdr[0] == 0x7F && hdr[1] == 'E'
                    && hdr[2] == 'L' && hdr[3] == 'F') {
                return hdr[4] == 2;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Copies any existing OBB files from {@code /Android/obb/<packageName>/} into
     * {@code staging/obb/}.
     */
    private static String copyDeviceObbsIfPresent(String packageName, File staging,
                                                  Progress progress) {
        if (packageName == null || packageName.isEmpty()) return "";
        try {
            File deviceObbDir = new File(Environment.getExternalStorageDirectory(),
                    "Android/obb/" + packageName);
            if (!deviceObbDir.isDirectory()) return "";
            File[] obbs = deviceObbDir.listFiles(
                    (d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".obb"));
            if (obbs == null || obbs.length == 0) return "";

            File targetObbDir = new File(staging, EXPANSION_DIR);
            if (!targetObbDir.mkdirs() && !targetObbDir.isDirectory()) return "";

            String primaryObb = "";
            for (File srcObb : obbs) {
                if (!srcObb.isFile()) continue;
                step(progress, "Copying OBB expansion: " + srcObb.getName());
                File dstObb = new File(targetObbDir, sanitize(srcObb.getName()));
                copyFile(srcObb, dstObb);
                if (primaryObb.isEmpty() || srcObb.getName().startsWith("main.")) {
                    primaryObb = EXPANSION_DIR + "/" + dstObb.getName();
                }
            }
            return primaryObb;
        } catch (Throwable t) {
            Log.w(TAG, "Device OBB scan/copy skipped for " + packageName + ": " + t);
            return "";
        }
    }

    private static String payloadName(String base) {
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
        }
        return fallback;
    }

    private static ActivityInfo findActivity(PackageInfo info, String name) {
        if (info == null) return null;
        ActivityInfo[] activities = info.activities;
        if (activities == null || name == null) return null;
        for (ActivityInfo activity : activities) {
            if (activity.name != null && activity.name.equals(name)) return activity;
        }
        return null;
    }

    private static String pickLauncherActivity(PackageManager pm, PackageInfo info) {
        if (info == null) return "";
        String pkg = info.packageName != null ? info.packageName : "";
        String apkPath = (info.applicationInfo != null) ? info.applicationInfo.sourceDir : null;
        com.ziauddin.zaibridge.core.BinaryXmlManifestReader.ParsedManifest parsed =
                (apkPath != null && !apkPath.isEmpty())
                        ? com.ziauddin.zaibridge.core.BinaryXmlManifestReader.readFromApk(apkPath)
                        : null;

        if (pm != null && !pkg.isEmpty()) {
            try {
                Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
                if (launchIntent != null && launchIntent.getComponent() != null) {
                    ComponentName cn = launchIntent.getComponent();
                    String cls = cn.getClassName();
                    if (cls != null && !cls.isEmpty()) {
                        try {
                            ActivityInfo ai = pm.getActivityInfo(cn, 0);
                            if (ai != null && ai.targetActivity != null && !ai.targetActivity.isEmpty()) {
                                cls = ai.targetActivity;
                            }
                        } catch (Throwable ignored) {
                        }
                        cls = com.ziauddin.zaibridge.core.BinaryXmlManifestReader
                                .normalizeClassName(pkg, cls);
                        if (parsed != null) {
                            cls = parsed.resolveActivityTarget(cls);
                        }
                        if (!cls.isEmpty()) return cls;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (parsed != null && parsed.launcherActivity != null && !parsed.launcherActivity.isEmpty()) {
            return parsed.resolveActivityTarget(parsed.launcherActivity);
        }
        ActivityInfo[] activities = info.activities;
        if (activities == null || activities.length == 0) return "";
        for (ActivityInfo a : activities) {
            if (a.name != null && (a.name.contains("UnityPlayer")
                    || a.name.contains("MainActivity")
                    || a.name.contains("GameActivity")
                    || a.name.contains("NativeActivity"))) {
                return a.name;
            }
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

    private static void publish(File staging, File target) throws IOException {
        if (target.exists() && !deleteTree(target)) {
            throw new IOException("Cannot replace existing import " + target.getName());
        }
        if (!staging.renameTo(target)) {
            throw new IOException("Cannot move import into place");
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src)) {
            copyStream(in, dst);
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
            file.setReadOnly();
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

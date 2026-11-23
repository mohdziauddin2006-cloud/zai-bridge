package com.ziauddin.zaibridge.core;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Universal Force-Save & Checkpoint Manager for ANY game or app (64-bit or 32-bit).
 *
 * <p>Snapshots the guest's isolated save directories ({@code files/games/<pkg>/data/},
 * {@code shared_prefs/}, {@code databases/}, and redirected external save data)
 * on demand into timestamped compressed ZIP checkpoints ({@code quick_save},
 * {@code slot_1}, {@code slot_2}, {@code slot_3}), allowing players to force-save
 * and force-load any game at any moment—even when the game lacks a manual save button.
 */
public final class SaveStateManager {

    private static final String TAG = "ZaiBridge";
    private static final String CHECKPOINTS_DIR = "checkpoints";

    public static final String SLOT_QUICK = "quick_save";
    public static final String SLOT_1 = "slot_1";
    public static final String SLOT_2 = "slot_2";
    public static final String SLOT_3 = "slot_3";

    public static final String[] ALL_SLOTS = {
            SLOT_QUICK, SLOT_1, SLOT_2, SLOT_3
    };

    public static final class CheckpointInfo {
        public final String slotId;
        public final String displayName;
        public final boolean exists;
        public final long savedAtMillis;
        public final long sizeBytes;
        public final int fileCount;

        public CheckpointInfo(String slotId, String displayName, boolean exists,
                              long savedAtMillis, long sizeBytes, int fileCount) {
            this.slotId = slotId;
            this.displayName = displayName;
            this.exists = exists;
            this.savedAtMillis = savedAtMillis;
            this.sizeBytes = sizeBytes;
            this.fileCount = fileCount;
        }

        public String formattedSummary() {
            if (!exists) return "Empty";
            SimpleDateFormat fmt = new SimpleDateFormat("MMM dd, HH:mm:ss", Locale.US);
            String when = fmt.format(new Date(savedAtMillis));
            String size = sizeBytes < 1024 * 1024
                    ? Math.max(1, sizeBytes / 1024) + " KB"
                    : String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0));
            return when + " · " + fileCount + " files (" + size + ")";
        }
    }

    private SaveStateManager() {
    }

    public static String slotDisplayName(String slotId) {
        if (SLOT_QUICK.equals(slotId)) return "Quick Save";
        if (SLOT_1.equals(slotId)) return "Slot 1";
        if (SLOT_2.equals(slotId)) return "Slot 2";
        if (SLOT_3.equals(slotId)) return "Slot 3";
        return slotId;
    }

    public static CheckpointInfo inspect(File gameDir, String slotId) {
        if (gameDir == null) {
            return new CheckpointInfo(slotId, slotDisplayName(slotId), false, 0, 0, 0);
        }
        File zip = checkpointZip(gameDir, slotId);
        File meta = checkpointMeta(gameDir, slotId);
        if (!zip.isFile()) {
            return new CheckpointInfo(slotId, slotDisplayName(slotId), false, 0, 0, 0);
        }
        long savedAt = zip.lastModified();
        int files = 0;
        if (meta.isFile()) {
            try {
                JSONObject o = new JSONObject(readText(meta));
                savedAt = o.optLong("savedAt", savedAt);
                files = o.optInt("fileCount", 0);
            } catch (Throwable ignored) {
            }
        }
        return new CheckpointInfo(slotId, slotDisplayName(slotId), true,
                savedAt, zip.length(), files);
    }

    /**
     * Force-saves all active isolated save state directories for the game into
     * a compressed ZIP checkpoint.
     */
    public static CheckpointInfo forceSave(Context context, File gameDir,
                                           String guestPackage, String slotId) throws IOException {
        if (gameDir == null || !gameDir.isDirectory()) {
            throw new IOException("Invalid game directory");
        }

        File cpDir = new File(gameDir, CHECKPOINTS_DIR);
        if (!cpDir.isDirectory() && !cpDir.mkdirs()) {
            throw new IOException("Cannot create checkpoints directory");
        }

        File targetZip = checkpointZip(gameDir, slotId);
        File tempZip = new File(cpDir, slotId + ".zip.tmp");

        int[] fileCounter = {0};
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(tempZip), 64 * 1024))) {
            // 1. Primary isolated guest storage (files/, external/files/, etc.)
            File guestData = new File(gameDir, "data");
            if (guestData.isDirectory()) {
                addDirectoryToZip(guestData, "data", zos, fileCounter);
            }

            // 2. Guest-local shared_prefs and databases inside gameDir
            File guestPrefs = new File(gameDir, "shared_prefs");
            if (guestPrefs.isDirectory()) {
                addDirectoryToZip(guestPrefs, "shared_prefs", zos, fileCounter);
            }
            File guestDbs = new File(gameDir, "databases");
            if (guestDbs.isDirectory()) {
                addDirectoryToZip(guestDbs, "databases", zos, fileCounter);
            }

            // 3. Host shared_prefs matching guest package prefix (if guest used host prefs)
            if (context != null && guestPackage != null && !guestPackage.isEmpty()) {
                File hostRoot = context.getFilesDir().getParentFile();
                if (hostRoot != null) {
                    File hostPrefsDir = new File(hostRoot, "shared_prefs");
                    File[] prefFiles = hostPrefsDir.isDirectory() ? hostPrefsDir.listFiles() : null;
                    if (prefFiles != null) {
                        for (File pf : prefFiles) {
                            if (!pf.isFile()) continue;
                            String n = pf.getName();
                            // Never include Zai-Bridge's own graphics prefs
                            if ("zai_graphics.xml".equals(n)) continue;
                            if (n.contains(guestPackage) || n.contains("v2.playerprefs")
                                    || n.contains("Unity") || n.contains("game")) {
                                addFileToZip(pf, "host_shared_prefs/" + n, zos);
                                fileCounter[0]++;
                            }
                        }
                    }
                    File hostDbDir = new File(hostRoot, "databases");
                    File[] dbFiles = hostDbDir.isDirectory() ? hostDbDir.listFiles() : null;
                    if (dbFiles != null) {
                        for (File df : dbFiles) {
                            if (!df.isFile()) continue;
                            addFileToZip(df, "host_databases/" + df.getName(), zos);
                            fileCounter[0]++;
                        }
                    }
                }
            }

            // Ensure ZIP always has at least a marker entry so empty-state save succeeds cleanly
            ZipEntry marker = new ZipEntry(".zai_checkpoint_marker");
            zos.putNextEntry(marker);
            zos.write(("slot=" + slotId + "\ntime=" + System.currentTimeMillis() + "\n").getBytes("UTF-8"));
            zos.closeEntry();
        }

        if (targetZip.exists()) {
            //noinspection ResultOfMethodCallIgnored
            targetZip.delete();
        }
        if (!tempZip.renameTo(targetZip)) {
            throw new IOException("Cannot commit checkpoint archive");
        }

        long now = System.currentTimeMillis();
        try {
            JSONObject meta = new JSONObject();
            meta.put("slot", slotId);
            meta.put("savedAt", now);
            meta.put("fileCount", fileCounter[0]);
            meta.put("sizeBytes", targetZip.length());
            try (FileOutputStream os = new FileOutputStream(checkpointMeta(gameDir, slotId))) {
                os.write(meta.toString(2).getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }

        Log.i(TAG, "Force-Save checkpoint written: " + slotId + " ("
                + fileCounter[0] + " files, " + targetZip.length() + " bytes)");
        return new CheckpointInfo(slotId, slotDisplayName(slotId), true,
                now, targetZip.length(), fileCounter[0]);
    }

    /**
     * Restores a previously captured ZIP checkpoint cleanly into the game's
     * save directories. Caller should relaunch the game Activity immediately
     * afterwards so the engine reads the restored state fresh from disk.
     */
    public static int forceLoad(Context context, File gameDir, String slotId) throws IOException {
        if (gameDir == null || !gameDir.isDirectory()) {
            throw new IOException("Invalid game directory");
        }
        File zip = checkpointZip(gameDir, slotId);
        if (!zip.isFile()) {
            throw new IOException("Checkpoint " + slotDisplayName(slotId) + " is empty");
        }

        File hostRoot = context != null ? context.getFilesDir().getParentFile() : null;
        int restored = 0;
        byte[] buf = new byte[64 * 1024];

        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zip), 64 * 1024))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty() || ".zai_checkpoint_marker".equals(name)) {
                    continue;
                }
                if (name.contains("..")) continue;  // Zip-slip protection

                File dest;
                if (name.startsWith("host_shared_prefs/") && hostRoot != null) {
                    dest = new File(new File(hostRoot, "shared_prefs"),
                            name.substring("host_shared_prefs/".length()));
                } else if (name.startsWith("host_databases/") && hostRoot != null) {
                    dest = new File(new File(hostRoot, "databases"),
                            name.substring("host_databases/".length()));
                } else {
                    dest = new File(gameDir, name);
                }

                if (entry.isDirectory()) {
                    dest.mkdirs();
                    continue;
                }
                File parent = dest.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    parent.mkdirs();
                }
                try (BufferedOutputStream bos = new BufferedOutputStream(
                        new FileOutputStream(dest), 64 * 1024)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                }
                restored++;
            }
        }

        Log.i(TAG, "Force-Load checkpoint restored: " + slotId + " (" + restored + " files)");
        return restored;
    }

    /** Deletes a saved checkpoint slot and its metadata JSON from disk. */
    public static boolean deleteCheckpoint(File gameDir, String slotId) {
        if (gameDir == null || slotId == null) return false;
        File zip = checkpointZip(gameDir, slotId);
        File meta = checkpointMeta(gameDir, slotId);
        boolean deleted = false;
        if (zip.exists()) deleted = zip.delete();
        if (meta.exists()) {
            //noinspection ResultOfMethodCallIgnored
            meta.delete();
        }
        return deleted;
    }

    private static void addDirectoryToZip(File dir, String basePath,
                                          ZipOutputStream zos, int[] counter) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String childPath = basePath + "/" + child.getName();
            if (child.isDirectory()) {
                addDirectoryToZip(child, childPath, zos, counter);
            } else if (child.isFile()) {
                addFileToZip(child, childPath, zos);
                counter[0]++;
            }
        }
    }

    private static void addFileToZip(File file, String entryPath, ZipOutputStream zos)
            throws IOException {
        ZipEntry entry = new ZipEntry(entryPath);
        entry.setTime(file.lastModified());
        zos.putNextEntry(entry);
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file), 64 * 1024)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = bis.read(buf)) > 0) {
                zos.write(buf, 0, n);
            }
        }
        zos.closeEntry();
    }

    private static File checkpointZip(File gameDir, String slotId) {
        return new File(new File(gameDir, CHECKPOINTS_DIR), slotId + ".zip");
    }

    private static File checkpointMeta(File gameDir, String slotId) {
        return new File(new File(gameDir, CHECKPOINTS_DIR), slotId + ".json");
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(file.length(), 4096));
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}

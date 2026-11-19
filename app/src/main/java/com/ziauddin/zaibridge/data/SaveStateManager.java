package com.ziauddin.zaibridge.data;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Universal Force-Save / checkpoint manager.
 *
 * <p>An emulated game writes its progress exactly where a native install would:
 * {@code shared_prefs/}, {@code files/}, {@code databases/} and its redirected
 * {@code Android/data/<pkg>/files/}. Because the guest runs under this UID,
 * every one of those paths is reachable without root. A checkpoint is a single
 * zip of all four, stored beside the import, so it survives cache wipes and is
 * restored in one tap.
 *
 * <p>The imported library itself ({@code files/games}) and the extracted
 * runtime ({@code files/runtime}) are deliberately excluded: they are
 * gigabytes of read-only payload, not progress.
 */
public final class SaveStateManager {

    private static final String TAG = "ZaiBridge";

    public static final int SLOT_QUICK = 0;
    public static final int SLOT_1 = 1;
    public static final int SLOT_2 = 2;
    public static final int SLOT_3 = 3;

    private static final int BUFFER = 64 * 1024;
    private static final String EXTENSION = ".zai";

    private SaveStateManager() {
    }

    /** Checkpoint directory for one imported game. */
    public static File root(File gameDir) {
        File dir = new File(gameDir, "savestates");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create " + dir);
        }
        return dir;
    }

    public static File file(File gameDir, int slot) {
        return new File(root(gameDir), nameOf(slot));
    }

    public static String nameOf(int slot) {
        return slot == SLOT_QUICK ? "quick" + EXTENSION : "slot" + slot + EXTENSION;
    }

    public static String labelOf(int slot) {
        return slot == SLOT_QUICK ? "Quick Save" : "Slot " + slot;
    }

    public static boolean has(File gameDir, int slot) {
        File f = file(gameDir, slot);
        return f.isFile() && f.length() > 0;
    }

    public static long timestamp(File gameDir, int slot) {
        File f = file(gameDir, slot);
        return f.isFile() ? f.lastModified() : 0L;
    }

    /**
     * Force Save: zips prefs, files, databases and the redirected external
     * data directory into the requested slot.
     *
     * @return the checkpoint file, or null when nothing could be written
     */
    public static synchronized File save(Context context, File gameDir, int slot) {
        if (context == null || gameDir == null) return null;
        File target = file(gameDir, slot);
        File temp = new File(target.getAbsolutePath() + ".tmp");

        List<Source> sources = sources(context);
        int entries = 0;
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(temp), BUFFER))) {
            zip.setLevel(6);
            for (Source source : sources) {
                entries += zipTree(zip, source.root, source.prefix, source.excluded);
            }
            if (entries == 0) {
                // Still produce a valid checkpoint: an empty save is a legitimate
                // state right after a fresh install.
                zip.putNextEntry(new ZipEntry("zai-savestate.txt"));
                zip.write(("slot=" + slot).getBytes("UTF-8"));
                zip.closeEntry();
            }
        } catch (Exception e) {
            Log.e(TAG, "force save failed for " + gameDir.getName(), e);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return null;
        }

        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!temp.renameTo(target)) {
                Log.e(TAG, "cannot publish checkpoint " + target);
                return null;
            }
        }
        Log.i(TAG, "force save " + labelOf(slot) + " -> " + target.getName()
                + " (" + entries + " entries, " + target.length() + " bytes)");
        return target;
    }

    /**
     * Force Load / Resume: clears the live state and unpacks the checkpoint.
     * The caller must restart the guest afterwards; a running renderer cannot
     * have its databases replaced underneath it.
     *
     * @return number of restored entries, or -1 when the slot is empty
     */
    public static synchronized int restore(Context context, File gameDir, int slot) {
        if (context == null || gameDir == null) return -1;
        File source = file(gameDir, slot);
        if (!source.isFile() || source.length() == 0) return -1;

        List<Source> sources = sources(context);
        int restored = 0;
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(source), BUFFER))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                String name = entry.getName();
                if ("zai-savestate.txt".equals(name)) {
                    zip.closeEntry();
                    continue;
                }
                Source destination = destinationFor(sources, name);
                if (destination == null) {
                    zip.closeEntry();
                    continue;
                }
                String relative = name.substring(destination.prefix.length());
                if (isExcluded(relative, destination.excluded)) {
                    zip.closeEntry();
                    continue;
                }
                File out = new File(destination.root, relative);
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    zip.closeEntry();
                    continue;
                }
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    byte[] buffer = new byte[BUFFER];
                    int read;
                    while ((read = zip.read(buffer)) > 0) stream.write(buffer, 0, read);
                }
                ++restored;
                zip.closeEntry();
            }
        } catch (Exception e) {
            Log.e(TAG, "force load failed for " + gameDir.getName(), e);
            return -1;
        }

        Log.i(TAG, "force load " + labelOf(slot) + ": " + restored + " entries restored");
        return restored;
    }

    /** Clears the live state without touching the checkpoint. */
    public static synchronized void clearLiveState(Context context) {
        for (Source source : sources(context)) {
            deleteTree(source.root, source.excluded);
        }
    }

    // ------------------------------------------------------------------
    // Sources
    // ------------------------------------------------------------------

    private static final class Source {
        final File root;
        final String prefix;
        final String[] excluded;

        Source(File root, String prefix, String[] excluded) {
            this.root = root;
            this.prefix = prefix;
            this.excluded = excluded;
        }
    }

    private static List<Source> sources(Context context) {
        List<Source> out = new ArrayList<>();
        // getDataDir() is API 24+ and is exactly filesDir's parent.
        File dataDir = context.getDataDir();
        out.add(new Source(new File(dataDir, "shared_prefs"), "internal/shared_prefs/",
                new String[0]));
        // files/games holds every imported APK/OBB; files/runtime the extracted
        // sysroot. Neither is game progress.
        out.add(new Source(new File(dataDir, "files"), "internal/files/",
                new String[]{"games", "runtime"}));
        out.add(new Source(new File(dataDir, "databases"), "internal/databases/",
                new String[0]));
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            out.add(new Source(external, "external/files/", new String[0]));
        }
        return out;
    }

    private static Source destinationFor(List<Source> sources, String entryName) {
        for (Source source : sources) {
            if (entryName.startsWith(source.prefix)) return source;
        }
        return null;
    }

    private static boolean isExcluded(String relative, String[] excluded) {
        for (String name : excluded) {
            if (relative.equals(name) || relative.startsWith(name + "/")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Zip helpers
    // ------------------------------------------------------------------

    private static int zipTree(ZipOutputStream zip, File root, String prefix,
                               String[] excluded) throws IOException {
        if (root == null || !root.isDirectory()) return 0;
        return zipTree(zip, root, "", prefix, excluded);
    }

    private static int zipTree(ZipOutputStream zip, File dir, String relative,
                               String prefix, String[] excluded) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int count = 0;
        for (File child : children) {
            String name = relative.isEmpty() ? child.getName() : relative + "/" + child.getName();
            if (isExcluded(name, excluded)) continue;
            if (child.isDirectory()) {
                count += zipTree(zip, child, name, prefix, excluded);
                continue;
            }
            if (!child.isFile()) continue;
            zip.putNextEntry(new ZipEntry(prefix + name));
            copy(child, zip);
            zip.closeEntry();
            ++count;
        }
        return count;
    }

    private static void copy(File source, ZipOutputStream zip) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(source), BUFFER)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) > 0) zip.write(buffer, 0, read);
        }
    }

    private static void deleteTree(File dir, String[] excluded) {
        if (dir == null || !dir.isDirectory()) return;
        deleteTree(dir, "", excluded);
    }

    private static void deleteTree(File dir, String relative, String[] excluded) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = relative.isEmpty() ? child.getName() : relative + "/" + child.getName();
            if (isExcluded(name, excluded)) continue;
            if (child.isDirectory()) {
                deleteTree(child, name, excluded);
                //noinspection ResultOfMethodCallIgnored
                child.delete();
            } else {
                //noinspection ResultOfMethodCallIgnored
                child.delete();
            }
        }
    }
}

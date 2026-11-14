package com.ziauddin.zaibridge.core;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;

import dalvik.system.DexClassLoader;

/**
 * The guest APK's class loader.
 *
 * <p>Two jobs: resolve the game's dex, and answer {@code
 * System.loadLibrary("foo")} with a host-ABI library instead of the ARM32
 * payload the APK ships. The payload is already mapped inside the Unicorn guest
 * by the time guest code runs, and its {@code Java_*} / {@code RegisterNatives}
 * bindings are installed, so the guest only needs the load to <em>succeed</em>.
 *
 * <p>ART refuses to open one library path from two class loaders
 * ({@code UnsatisfiedLinkError: ... already opened by ClassLoader ...}), so each
 * requested name gets its own read-only copy of the bridge runtime in the game's
 * private directory. That copy is a distinct file, ART accepts it, and its
 * {@code JNI_OnLoad} is harmless: it only stores the JavaVM.
 */
public final class GuestDexLoader extends DexClassLoader {

    private static final String TAG = "ZaiBridge";

    private final File aliasDir;
    private final String hostLibrary;

    public GuestDexLoader(String apkPath, String optimizedDir, String nativeDir,
                          ClassLoader parent, String hostLibrary, File aliasDir) {
        super(apkPath, optimizedDir, nativeDir, parent);
        this.hostLibrary = hostLibrary;
        this.aliasDir = aliasDir;
    }

    @Override
    public String findLibrary(String name) {
        if (hostLibrary == null) {
            // 32-bit passthrough host: the payload is a real loadable 32-bit .so.
            return super.findLibrary(name);
        }
        File alias = aliasFor(name);
        if (alias != null) {
            Log.i(TAG, "guest System.loadLibrary(\"" + name
                    + "\") served by the bridge runtime (" + alias.getName() + ")");
            return alias.getAbsolutePath();
        }
        Log.w(TAG, "guest System.loadLibrary(\"" + name + "\") has no bridge alias");
        return null;
    }

    private File aliasFor(String name) {
        if (aliasDir == null) return null;
        synchronized (GuestDexLoader.class) {
            if (!aliasDir.isDirectory() && !aliasDir.mkdirs()) return null;
            // The runtime is linked against the shared C++ runtime, so the alias
            // directory must stay self-contained for the guest's dlopen.
            copySibling("libc++_shared.so");
            File target = new File(aliasDir, "lib" + name + ".so");
            if (target.isFile() && target.length() > 0) return target;
            try (InputStream in = new FileInputStream(hostLibrary);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return target;
            } catch (Throwable t) {
                Log.e(TAG, "bridge alias copy failed for " + name, t);
                return null;
            }
        }
    }

    private void copySibling(String fileName) {
        File src = new File(new File(hostLibrary).getParentFile(), fileName);
        File dst = new File(aliasDir, fileName);
        if (!src.isFile() || (dst.isFile() && dst.length() == src.length())) return;
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            Log.i(TAG, "bridge alias dependency staged: " + fileName);
        } catch (Throwable t) {
            Log.e(TAG, "bridge alias dependency copy failed: " + fileName, t);
        }
    }

    /** Absolute path of libwrapper.so inside the installed APK's lib dir. */
    public static String hostBridgeLibrary(android.content.Context ctx) {
        File dir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        File lib = new File(dir, "libwrapper.so");
        return lib.isFile() ? lib.getAbsolutePath() : null;
    }
}

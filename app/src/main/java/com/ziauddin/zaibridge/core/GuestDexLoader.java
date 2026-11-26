package com.ziauddin.zaibridge.core;

import android.content.Context;
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
 * requested name gets its own copy of {@code libbridge_alias_shim.so} in the
 * game's private directory. The shim is a few kilobytes that forwards
 * {@code JNI_OnLoad}, {@code ANativeActivity_onCreate} and the Unity
 * {@code NativeLoader} thunks to the one live {@code libwrapper.so}, so the
 * guest never gets a second, empty copy of the runtime's session state.
 */
public final class GuestDexLoader extends DexClassLoader {

    private static final String TAG = "ZaiBridge";

    /** Tiny forwarding library staged in place of the guest's native payload. */
    public static final String SHIM_LIBRARY = "libbridge_alias_shim.so";

    private final File aliasDir;
    private final String hostLibrary;

    public GuestDexLoader(String apkPath, String optimizedDir, String nativeDir,
                          ClassLoader parent, String hostLibrary, File aliasDir) {
        super(apkPath, optimizedDir, nativeDir, parent);
        this.hostLibrary = hostLibrary;
        this.aliasDir = aliasDir;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Native 64-bit / passthrough guests (Unity 6 GameActivity, AppCompat, Play Services)
        // must load their own androidx.* and game classes child-first so resource R IDs and
        // library versions match the guest APK rather than Zai-Bridge's Material/AppCompat deps.
        if (hostLibrary == null && name != null) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            boolean forceParentFirst = name.startsWith("java.")
                    || name.startsWith("javax.")
                    || name.startsWith("sun.")
                    || name.startsWith("dalvik.")
                    || name.startsWith("libcore.")
                    || name.startsWith("org.xml.")
                    || name.startsWith("org.xmlpull.")
                    || name.startsWith("org.w3c.")
                    || name.startsWith("org.json.")
                    || name.startsWith("com.ziauddin.zaibridge.")
                    || (name.startsWith("android.") && !name.startsWith("android.support."));
            if (!forceParentFirst) {
                try {
                    Class<?> guestCls = findClass(name);
                    if (resolve) {
                        resolveClass(guestCls);
                    }
                    return guestCls;
                } catch (ClassNotFoundException ignored) {
                    // Fall back to parent classloader cleanly.
                }
            }
        }
        return super.loadClass(name, resolve);
    }

    @Override
    public String findLibrary(String name) {
        if (hostLibrary == null) {
            // Native 64-bit / passthrough host: resolve from staged native64/native + split APKs.
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

    /**
     * Stages the alias the guest's {@code System.loadLibrary(name)} resolves to.
     *
     * <p>ART refuses to open one library path from two class loaders, so every
     * requested name needs its own file. That file is now a copy of the tiny
     * forwarding shim rather than of the whole runtime: the shim re-exports the
     * JNI and NativeActivity entry points and dispatches them to the single
     * live {@code libwrapper.so}, so {@code ANativeActivity_onCreate} reaches the
     * session the bridge actually opened.
     */
    private File aliasFor(String name) {
        if (aliasDir == null) return null;
        synchronized (GuestDexLoader.class) {
            if (!aliasDir.isDirectory() && !aliasDir.mkdirs()) return null;
            String fileName = name.startsWith("lib")
                    ? (name.endsWith(".so") ? name : name + ".so")
                    : "lib" + name + ".so";
            File target = new File(aliasDir, fileName);
            File host = new File(hostLibrary);
            File shim = new File(host.getParentFile(), SHIM_LIBRARY);
            if (!shim.isFile()) {
                // Older staging layout: fall back to a copy of the runtime. It
                // still loads, but the copy cannot see the live session state.
                Log.w(TAG, "alias shim missing; staging the full runtime copy");
                copySibling("libc++_shared.so");
                return copyAlias(host, target, name);
            }
            return copyAlias(shim, target, name);
        }
    }

    private File copyAlias(File src, File target, String name) {
        if (target.isFile() && target.length() == src.length()
                && target.lastModified() >= src.lastModified()) {
            return target;
        }
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            Log.i(TAG, "staged guest alias: " + target.getName()
                    + " (" + src.getName() + ", " + target.length() + " bytes)");
            return target;
        } catch (Throwable t) {
            Log.e(TAG, "bridge alias copy failed for " + name, t);
            return null;
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

    /**
     * Loads the bundled legacy runtime JARs as a parent-first class loader.
     *
     * <p>The assets are dex archives (a jar containing {@code classes.dex}), not
     * plain {@code .class} bundles, because {@link DexClassLoader} only opens
     * dex. They are copied once into the code cache and marked read-only: ART
     * refuses a writable dex on Android 14+.
     *
     * @return the legacy loader, or {@code hostParent} when no assets ship
     */
    public static ClassLoader legacyParent(Context ctx, ClassLoader hostParent) {
        final File dir = new File(ctx.getCodeCacheDir(), "legacy");
        if (!dir.isDirectory() && !dir.mkdirs()) return hostParent;

        final StringBuilder classPath = new StringBuilder();
        try {
            final String[] names = ctx.getAssets().list("runtime/legacy");
            if (names != null) {
                for (String name : names) {
                    if (!name.endsWith(".jar")) continue;
                    File target = new File(dir, name);
                    if (!target.isFile() || target.length() == 0) {
                        try (InputStream in = ctx.getAssets().open("runtime/legacy/" + name);
                             OutputStream out = new FileOutputStream(target)) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                    }
                    if (!target.setReadOnly()) {
                        Log.w(TAG, "legacy jar could not be made read-only: " + name);
                    }
                    if (classPath.length() > 0) classPath.append(File.pathSeparatorChar);
                    classPath.append(target.getAbsolutePath());
                    Log.i(TAG, "legacy runtime available: " + name
                            + " (" + target.length() + " bytes)");
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "legacy runtime extraction failed", t);
            return hostParent;
        }

        if (classPath.length() == 0) return hostParent;
        return new DexClassLoader(classPath.toString(), dir.getAbsolutePath(),
                null, hostParent);
    }

    /** Absolute path of libwrapper.so inside the installed APK's lib dir. */
    public static String hostBridgeLibrary(android.content.Context ctx) {
        File dir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        File lib = new File(dir, "libwrapper.so");
        return lib.isFile() ? lib.getAbsolutePath() : null;
    }
}

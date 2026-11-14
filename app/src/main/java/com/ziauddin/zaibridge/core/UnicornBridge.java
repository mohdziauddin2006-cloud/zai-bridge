package com.ziauddin.zaibridge.core;

import android.os.Build;
import android.util.Log;
import android.view.Surface;

/**
 * The Java face of the Unicorn translation core.
 *
 * <p>The core is a 64-bit shared library that runs legacy ARM32 payloads inside
 * this process. It maps the {@code payload/lib*32.so} files of an ingested game,
 * applies their relocations, runs their constructors, binds every
 * {@code Java_*} export to ART through 64-bit trampolines, and finally calls the
 * guests' {@code JNI_OnLoad}. It does not know any game by name.
 *
 * <p>Graphics are forwarded to the real host EGL/GLES drivers. The guest's
 * opaque EGL objects are exchanged for 32-bit handles, and window surfaces are
 * bound to the {@link Surface} handed to {@link #setSurface(Surface)}.
 */
public final class UnicornBridge {

    private static final String TAG = "ZaiBridge";

    /** The emulated guest started and is now the active session. */
    public static final int SESSION_OK = 0;
    /** A guest is already running; this process hosts one session at a time. */
    public static final int SESSION_ALREADY_RUNNING = 1;
    public static final int SESSION_INIT_FAILED = -1;
    public static final int SESSION_NO_PAYLOAD = -2;
    public static final int SESSION_LINK_FAILED = -3;
    /** The host process is 32-bit; the guest libraries run without a bridge. */
    public static final int SESSION_PASSTHROUGH = -4;

    /** Primary ABI of the process this code is running in. */
    public static final String HOST_ABI = Build.SUPPORTED_ABIS.length > 0
            ? Build.SUPPORTED_ABIS[0] : "unknown";

    /** Architecture the legacy payloads are compiled for. */
    public static final String GUEST_ABI = "armeabi-v7a";

    private static final boolean CORE_LOADED;

    static {
        boolean loaded = false;
        if (!isPassthrough()) {
            try {
                System.loadLibrary("wrapper");
                loaded = true;
                Log.i(TAG, "Unicorn core loaded for " + HOST_ABI);
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "Unicorn core missing for " + HOST_ABI, e);
            }
        }
        CORE_LOADED = loaded;
    }

    /**
     * A 32-bit host process (armeabi-v7a or x86) already runs the guest
     * libraries, so the translator is not loaded and the guest is started
     * directly. The 64-bit APK variants (arm64-v8a, x86_64) always use Unicorn.
     */
    public static boolean isPassthrough() {
        return HOST_ABI.startsWith("armeabi") || "x86".equals(HOST_ABI);
    }

    public static boolean isEmulated() {
        return CORE_LOADED;
    }

    private UnicornBridge() {
    }

    public static boolean isCoreLoaded() {
        return CORE_LOADED;
    }

    /**
     * Maps and starts the guest runtime found in {@code gameDir}.
     *
     * @param gameDir     private game directory (contains {@code payload/} and {@code game.apk})
     * @param obbPath     absolute path of the expansion file, or null
     * @param packageName guest package, used to rewrite its hardcoded storage paths
     * @param guestLoader DexClassLoader positioned on the guest APK, so guest
     *                    classes resolve by their real names
     * @param guestActivity the guest Activity instance, used as the {@code this}
     *                    the engine's native side calls back into
     */
    public static int startSession(String gameDir, String obbPath, String packageName,
                                   ClassLoader guestLoader, Object guestActivity) {
        if (isPassthrough()) {
            Log.i(TAG, "32-bit host: guest libraries run natively (passthrough)");
            return SESSION_PASSTHROUGH;
        }
        if (!CORE_LOADED) return SESSION_INIT_FAILED;
        return nativeStartSession(gameDir, obbPath, packageName, guestLoader, guestActivity);
    }

    /**
     * Hands the host's SurfaceView window to the core. Call with null when the
     * surface is destroyed; EGL keeps its own reference while a surface exists.
     */
    public static void setSurface(Surface surface) {
        if (!CORE_LOADED) return;
        nativeSetSurface(surface);
    }

    /**
     * Root of the unpacked 32-bit runtime bundle. The core searches
     * {@code <root>/sysroot_arm32} (and {@code sysroot_x86}) after the payload
     * directory when a guest binary requests a library by name.
     */
    public static void setRuntimeSysroot(String path) {
        if (!CORE_LOADED) return;
        nativeSetRuntimeSysroot(path);
    }

    /** Attaches the guest Activity instance once it exists. */
    public static void setGuestActivity(Object activity) {
        if (!CORE_LOADED) return;
        nativeSetGuestActivity(activity);
    }

    public static boolean isSessionActive() {
        return CORE_LOADED && nativeIsSessionActive();
    }

    /** Unicorn version and guest/host architecture, for diagnostics. */
    public static native String nativeVersion();

    private static native int nativeStartSession(String gameDir, String obbPath,
                                                 String packageName, ClassLoader guestLoader,
                                                 Object guestActivity);

    private static native void nativeSetSurface(Surface surface);

    private static native void nativeSetRuntimeSysroot(String path);

    private static native void nativeSetGuestActivity(Object activity);

    private static native boolean nativeIsSessionActive();
}

package com.ziauddin.zaibridge.core;

import android.util.Log;

/**
 * The Java face of the Unicorn translation core.
 *
 * <p>The core is a 64-bit shared library that runs legacy ARM32 payloads inside
 * this process. It maps the {@code payload/lib*32.so} files of an ingested game,
 * applies their relocations, runs their constructors, binds every
 * {@code Java_*} export to ART through 64-bit trampolines, and finally calls the
 * guests' {@code JNI_OnLoad}. It does not know any game by name.
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

    private static final boolean CORE_LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("wrapper_arm64");
            loaded = true;
            Log.i(TAG, "Unicorn core loaded");
        } catch (UnsatisfiedLinkError e) {
            loaded = false;
            Log.e(TAG, "Unicorn core missing for this ABI", e);
        }
        CORE_LOADED = loaded;
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
     * @param guestLoader DexClassLoader positioned on the guest APK, so guest
     *                    classes resolve by their real names
     */
    public static int startSession(String gameDir, String obbPath, ClassLoader guestLoader) {
        if (!CORE_LOADED) return SESSION_INIT_FAILED;
        return nativeStartSession(gameDir, obbPath, guestLoader);
    }

    /** Unicorn version and guest/host architecture, for the host HUD. */
    public static native String nativeVersion();

    private static native int nativeStartSession(String gameDir, String obbPath,
                                                 ClassLoader guestLoader);
}

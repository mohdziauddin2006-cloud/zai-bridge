package com.ziauddin.zaibridge.core;

import android.util.Log;

/**
 * Frame accounting at the Java EGL boundary.
 *
 * <p>The native core only sees {@code eglSwapBuffers} when the <em>payload</em>
 * imports it — a pure NDK game. A game whose renderer is a Java
 * {@code GLSurfaceView} never enters libwrapper: it swaps through the
 * framework's native EGL methods ({@code
 * com.google.android.gles_jni.EGLImpl.eglSwapBuffers} for EGL10, {@code
 * android.opengl.EGL14.eglSwapBuffers} for EGL14).
 *
 * <p>Both of those are {@code native} methods, so the bridge re-registers them
 * with its own JNI thunks and forwards to the real {@code libEGL.so}. Frame
 * counting, pacing and the "frame N presented" log therefore come from one
 * clock whichever EGL flavour a game uses, and whichever ABI it runs on.
 */
public final class EglSwapHook {

    private static final String TAG = "ZaiBridge";

    private static final boolean INSTALLED;

    static {
        boolean installed = false;
        try {
            System.loadLibrary("wrapper");
            installed = nativeInstall();
        } catch (Throwable t) {
            // A missing or older core must never stop a game from starting.
            Log.w(TAG, "EGL swap hook unavailable: " + t);
        }
        INSTALLED = installed;
    }

    private EglSwapHook() {
    }

    /**
     * Installs the hooks once per process. Called from
     * {@code Application.attachBaseContext}, before any Activity (and therefore
     * any GLSurfaceView) exists.
     */
    public static boolean install() {
        Log.i(TAG, "EGL swap hook " + (INSTALLED ? "installed" : "not installed"));
        return INSTALLED;
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    /** Frame counters for diagnostics: swaps, fps and forward failures. */
    public static String stats() {
        return INSTALLED ? nativeStats() : "unavailable";
    }

    private static native boolean nativeInstall();

    private static native String nativeStats();
}

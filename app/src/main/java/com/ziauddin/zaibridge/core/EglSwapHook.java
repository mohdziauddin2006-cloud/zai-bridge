package com.ziauddin.zaibridge.core;

import android.util.Log;

/**
 * Frame accounting, upscaling and frame generation at the Java EGL boundary.
 *
 * <p>The native core only sees {@code eglSwapBuffers} when the <em>payload</em>
 * imports it — a pure NDK game. A game whose renderer is a Java
 * {@code GLSurfaceView} never enters libwrapper: it swaps through the
 * framework's native EGL methods ({@code
 * com.google.android.gles_jni.EGLImpl.eglSwapBuffers} for EGL10, {@code
 * android.opengl.EGL14.eglSwapBuffers} for EGL14).
 *
 * <p>Both of those are {@code native} methods, so the bridge re-registers them
 * with its own JNI thunks and forwards to the real {@code libEGL.so}. The same
 * registration point runs the GPU compositor (SGSR/FSR upscaling and 2x
 * motion-compensated frame generation) before the frame is presented.
 *
 * <p>The compositor configuration is process-wide and is applied by the native
 * side on the guest's own GL thread, so {@link #configure} may be called from
 * the launcher or from the in-game menu at any time.
 */
public final class EglSwapHook {

    private static final String TAG = "ZaiBridge";

    /** The bridge shared library is resident. */
    private static final boolean LOADED;
    /** The Java EGL hooks were installed by the core. */
    private static final boolean INSTALLED;

    static {
        boolean loaded = false;
        boolean installed = false;
        try {
            System.loadLibrary("wrapper");
            loaded = true;
            installed = nativeInstall();
        } catch (Throwable t) {
            // A missing or older core must never stop a game from starting.
            Log.w(TAG, "EGL swap hook unavailable: " + t);
        }
        LOADED = loaded;
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

    /**
     * Applies the effective graphics configuration to the compositor.
     *
     * @param upscaler         0 = off, 1 = SGSR, 2 = FSR 1.0
     * @param scalePercent     50 / 67 / 75 / 100
     * @param sharpnessPercent 0-100
     * @param frameGen         0 = off, 2 = 2x fluid motion
     */
    public static void configure(int upscaler, int scalePercent, int sharpnessPercent,
                                 int frameGen) {
        if (!LOADED) return;
        try {
            nativeConfigure(upscaler, scalePercent, sharpnessPercent, frameGen);
        } catch (Throwable t) {
            Log.w(TAG, "graphics configuration rejected: " + t);
        }
    }

    /** Frame counters for diagnostics: swaps, fps and forward failures. */
    public static String stats() {
        if (!LOADED) return "unavailable";
        try {
            return nativeStats();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /** Compositor counters: scale, generated frames, shader failures, GL version. */
    public static String compositorStats() {
        if (!LOADED) return "unavailable";
        try {
            return nativeCompositorStats();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /** Detected GPU model, driver and CPU topology, for the settings sheet. */
    public static String graphicsInfo() {
        if (!LOADED) return "native core unavailable";
        try {
            return nativeGraphicsInfo();
        } catch (Throwable t) {
            return "native core unavailable";
        }
    }

    private static native boolean nativeInstall();

    private static native String nativeStats();

    private static native void nativeConfigure(int upscaler, int scalePercent,
                                               int sharpnessPercent, int frameGen);

    private static native String nativeCompositorStats();

    private static native String nativeGraphicsInfo();
}

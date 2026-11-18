package com.ziauddin.zaibridge.core;

import android.util.Log;

import com.ziauddin.zaibridge.data.GraphicsSettings;

/**
 * Frame accounting, upscaling and multi-frame generation at the Java EGL
 * boundary.
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
 * registration point runs the Zai-Scale Ultra compositor (temporal SR, neural
 * detail reconstruction and 2x/3x/4x motion-compensated frame generation)
 * before the frame is presented.
 *
 * <p>The compositor configuration is process-wide and is applied by the native
 * side on the guest's own GL thread, so {@link #configure} may be called from
 * the launcher or from the in-game drawer at any time.
 */
public final class EglSwapHook {

    private static final String TAG = "ZaiBridge";

    /** The bridge shared library is resident. */
    private static volatile boolean sLoaded = false;
    /** The Java EGL hooks were installed by the core. */
    private static volatile boolean sInstalled = false;

    private EglSwapHook() {
    }

    /**
     * Lazily installs the hooks once per process when a guest game launches.
     */
    public static synchronized boolean install() {
        if (sInstalled) return true;
        try {
            if (!sLoaded) {
                System.loadLibrary("wrapper");
                sLoaded = true;
            }
            sInstalled = nativeInstall();
        } catch (Throwable t) {
            // A missing or older core must never stop a game from starting.
            Log.w(TAG, "EGL swap hook unavailable or blocked by OEM: " + t);
            sInstalled = false;
        }
        Log.i(TAG, "EGL swap hook " + (sInstalled ? "installed" : "not installed"));
        return sInstalled;
    }

    public static boolean isInstalled() {
        return sInstalled;
    }

    /** Applies a full configuration, including the one-tap preset id. */
    public static void configure(GraphicsSettings settings) {
        if (settings == null) return;
        configure(settings.upscaler, settings.scalePercent, settings.sharpnessPercent,
                settings.neuralPercent, settings.frameGen, settings.temporal,
                settings.autoMfg, settings.preset);
    }

    /**
     * Applies the effective graphics configuration to the compositor.
     *
     * @param upscaler         0 off, 1 mobile SGSR, 2 FSR temporal, 3 DLSS-5
     * @param scalePercent     50 / 67 / 75 / 85 / 100
     * @param sharpnessPercent 0-100 RCAS strength
     * @param neuralPercent    0-100 local contrast / specular / micro-detail
     * @param frameGen         0 off, 2 / 3 / 4 synthetic frames per real frame
     * @param temporal         0 or 1, temporal history accumulation
     * @param autoMfg          0 or 1, dynamic multiplier from measured headroom
     * @param preset           preset id recorded for telemetry / restore
     */
    public static void configure(int upscaler, int scalePercent, int sharpnessPercent,
                                 int neuralPercent, int frameGen, int temporal,
                                 int autoMfg, int preset) {
        if (!sLoaded) return;
        try {
            nativeConfigure(upscaler, scalePercent, sharpnessPercent, neuralPercent,
                    frameGen, temporal, autoMfg, preset);
        } catch (Throwable t) {
            Log.w(TAG, "graphics configuration rejected: " + t);
        }
    }

    /**
     * Split-screen before/after wipe. {@code 0} disables the comparison and
     * presents the processed frame; {@code 0.05..0.95} shows vanilla on the
     * left of the divider and Zai-Scale on the right, live, without touching
     * the temporal history.
     */
    public static void setCompare(float split) {
        if (!sLoaded) return;
        try {
            nativeSetCompare(split);
        } catch (Throwable t) {
            Log.w(TAG, "compare wipe rejected: " + t);
        }
    }

    /** Frame counters for diagnostics: swaps, fps and forward failures. */
    public static String stats() {
        if (!sLoaded) return "unavailable";
        try {
            return nativeStats();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /** Compositor counters: scale, generated frames, shader failures, GL version. */
    public static String compositorStats() {
        if (!sLoaded) return "unavailable";
        try {
            return nativeCompositorStats();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /** Detected GPU model, driver and CPU topology, for the settings sheet. */
    public static String graphicsInfo() {
        if (!sLoaded) return "native core unavailable";
        try {
            return nativeGraphicsInfo();
        } catch (Throwable t) {
            return "native core unavailable";
        }
    }

    private static native boolean nativeInstall();

    private static native String nativeStats();

    private static native void nativeConfigure(int upscaler, int scalePercent,
                                               int sharpnessPercent, int neuralPercent,
                                               int frameGen, int temporal,
                                               int autoMfg, int preset);

    private static native void nativeSetCompare(float split);

    private static native String nativeCompositorStats();

    private static native String nativeGraphicsInfo();
}

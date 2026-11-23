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
    /** At least one 64-bit module had its EGL/GLES GOT slots rewritten. */
    private static volatile boolean sPltInstalled = false;
    /** The Vulkan queue-present passthrough is live. */
    private static volatile boolean sVulkanInstalled = false;

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
            // The Java boundary covers GLSurfaceView games; a 64-bit renderer
            // that links libEGL/libGLESv2/vulkan directly is reached through
            // its PLT/GOT slots. Both are best effort and independent.
            try {
                sPltInstalled = nativeInstallPltHooks();
                sVulkanInstalled = nativeInstallVulkanHooks();
            } catch (Throwable t) {
                Log.w(TAG, "native PLT/Vulkan hooks unavailable: " + t);
            }
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

    public static boolean isPltInstalled() {
        return sPltInstalled;
    }

    public static boolean isVulkanInstalled() {
        return sVulkanInstalled;
    }

    /**
     * Re-patches the modules mapped since {@link #install()}.
     * A game loads its renderer (libunity.so, libmain.so, ...) after the
     * Activity starts, so the launcher calls this once the session is up and
     * the host calls it when the surface is recreated.
     */
    public static synchronized int refreshHooks() {
        if (!sLoaded) return 0;
        try {
            int patched = nativeRefreshPltHooks();
            if (patched > 0) sPltInstalled = true;
            return patched;
        } catch (Throwable t) {
            Log.w(TAG, "PLT refresh rejected: " + t);
            return 0;
        }
    }

    /** Convenience void wrapper for Handler.postDelayed / lifecycle callbacks. */
    public static void refreshNativeHooks() {
        refreshHooks();
    }

    /** GOT counter line: modules, slots patched, redirects, swaps, fps. */
    public static String pltStats() {
        if (!sLoaded) return "unavailable";
        try {
            return nativePltStats();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /** Vulkan present counter line: presents, images, fps. */
    public static String vulkanStats() {
        if (!sLoaded) return "unavailable";
        try {
            return nativeVulkanStats();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /** Applies a full configuration, including the one-tap preset id and Berserk flag. */
    public static void configure(GraphicsSettings settings) {
        if (settings == null) return;
        configure(settings.upscaler, settings.scalePercent, settings.sharpnessPercent,
                settings.neuralPercent, settings.frameGen, settings.temporal,
                settings.autoMfg, settings.preset, settings.berserk);
    }

    public static void configure(int upscaler, int scalePercent, int sharpnessPercent,
                                 int neuralPercent, int frameGen, int temporal,
                                 int autoMfg, int preset) {
        configure(upscaler, scalePercent, sharpnessPercent, neuralPercent,
                frameGen, temporal, autoMfg, preset,
                preset == GraphicsSettings.PRESET_BERSERK ? 1 : 0);
    }

    public static void configure(int upscaler, int scalePercent, int sharpnessPercent,
                                 int neuralPercent, int frameGen, int temporal,
                                 int autoMfg, int preset, int berserk) {
        if (!sLoaded) return;
        try {
            nativeConfigure(upscaler, scalePercent, sharpnessPercent, neuralPercent,
                    frameGen, temporal, autoMfg, preset, berserk);
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
                                               int autoMfg, int preset, int berserk);

    private static native void nativeSetCompare(float split);

    private static native String nativeCompositorStats();

    private static native String nativeGraphicsInfo();

    private static native boolean nativeInstallPltHooks();

    private static native int nativeRefreshPltHooks();

    private static native String nativePltStats();

    private static native boolean nativeInstallVulkanHooks();

    private static native String nativeVulkanStats();
}

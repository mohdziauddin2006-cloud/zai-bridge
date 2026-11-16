package com.ziauddin.zaibridge.core;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.util.Log;

import com.ziauddin.zaibridge.data.Game;
import com.ziauddin.zaibridge.data.GameRepository;

import java.io.File;

/**
 * Guest screen orientation, carried from the imported manifest to the host
 * window.
 *
 * <p>The proxy Activity is the one the system launched, but its window is
 * filled by the guest's own Activity. A guest designed for landscape therefore
 * stays in whatever orientation the host happens to be in and renders cropped,
 * unless the orientation its manifest asked for is applied to the Activity
 * before the guests' surface exists.
 *
 * <p>{@link #nameOf(int)} is the ingestion side (ActivityInfo, which the
 * platform parses out of the imported APK's {@code AndroidManifest.xml}),
 * {@link #apply} is the launch side.
 */
public final class GameOrientation {

    private static final String TAG = "ZaiBridge";

    /** The manifest declared nothing; the device keeps its own orientation. */
    public static final String UNSPECIFIED = "unspecified";

    private GameOrientation() {
    }

    /** {@code ActivityInfo.screenOrientation} -> the attribute's spelling. */
    public static String nameOf(int orientation) {
        switch (orientation) {
            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                return "landscape";
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                return "portrait";
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
                return "reverseLandscape";
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT:
                return "reversePortrait";
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:
                return "sensorLandscape";
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:
                return "sensorPortrait";
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR:
                return "sensor";
            case ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR:
                return "fullSensor";
            case ActivityInfo.SCREEN_ORIENTATION_USER:
                return "user";
            case ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE:
                return "userLandscape";
            case ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT:
                return "userPortrait";
            case ActivityInfo.SCREEN_ORIENTATION_FULL_USER:
                return "fullUser";
            case ActivityInfo.SCREEN_ORIENTATION_LOCKED:
                return "locked";
            case ActivityInfo.SCREEN_ORIENTATION_NOSENSOR:
                return "nosensor";
            case ActivityInfo.SCREEN_ORIENTATION_BEHIND:
                return "behind";
            default:
                return UNSPECIFIED;
        }
    }

    /** The attribute's spelling -> {@code ActivityInfo.screenOrientation}. */
    public static int constantOf(String name) {
        if (name == null) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        switch (name.trim()) {
            case "landscape":
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            case "portrait":
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            case "reverseLandscape":
                return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            case "reversePortrait":
                return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            case "sensorLandscape":
                return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            case "sensorPortrait":
                return ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
            case "sensor":
                return ActivityInfo.SCREEN_ORIENTATION_SENSOR;
            case "fullSensor":
                return ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
            case "user":
                return ActivityInfo.SCREEN_ORIENTATION_USER;
            case "userLandscape":
                return ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
            case "userPortrait":
                return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
            case "fullUser":
                return ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
            case "locked":
                return ActivityInfo.SCREEN_ORIENTATION_LOCKED;
            case "nosensor":
                return ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
            case "behind":
                return ActivityInfo.SCREEN_ORIENTATION_BEHIND;
            default:
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }

    /**
     * Applies the orientation recorded for an imported game directory. Called
     * from the Activity's {@code onCreate} path, before any SurfaceView exists,
     * because a window surface cannot change its dimensions after EGL binds it.
     *
     * @return true when an orientation was requested
     */
    public static boolean apply(Activity activity, File gameDir) {
        if (activity == null || gameDir == null) return false;
        Game game = GameRepository.load(gameDir);
        if (game == null) return false;
        return apply(activity, game.orientation, game.label);
    }

    public static boolean apply(Activity activity, String orientation, String label) {
        if (activity == null) return false;
        final int constant = constantOf(orientation);
        if (constant == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            Log.i(TAG, "guest orientation: manifest declares none"
                    + (label == null ? "" : " (" + label + ")")
                    + "; the device keeps its current orientation");
            return false;
        }
        try {
            activity.setRequestedOrientation(constant);
            Log.i(TAG, "guest orientation: manifest " + orientation + " -> "
                    + "setRequestedOrientation(" + constant + ") on "
                    + activity.getClass().getName()
                    + (label == null ? "" : " (" + label + ")"));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "guest orientation: setRequestedOrientation(" + constant
                    + ") failed for " + activity.getClass().getName(), t);
            return false;
        }
    }
}

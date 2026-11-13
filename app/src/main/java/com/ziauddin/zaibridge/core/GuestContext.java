package com.ziauddin.zaibridge.core;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;

/**
 * A {@link ContextWrapper} whose storage view is the ingested game, whose
 * resource table also contains the guest APK, while every system service
 * (AudioManager, WindowManager, SensorManager, ...) is still the host's real,
 * 64-bit service object. Guest code never sees a path outside
 * {@code files/games/<package>}.
 */
public final class GuestContext extends ContextWrapper {

    private static final String TAG = "ZaiBridge";

    private final File gameDir;
    private final String guestPackage;
    private final ClassLoader guestLoader;
    private final File apk;

    private boolean resourcesMerged;
    private Resources.Theme guestTheme;

    public GuestContext(Context host, File gameDir, String guestPackage,
                        ClassLoader guestLoader) {
        super(host);
        this.gameDir = gameDir;
        this.guestPackage = guestPackage;
        this.guestLoader = guestLoader;
        this.apk = new File(gameDir, "game.apk");
        mergeGuestResources();
    }

    /**
     * Adds the guest APK to the process asset table so its XML layouts,
     * drawables, strings and Holo/Material themes resolve instead of throwing
     * {@code Resources$NotFoundException}. The framework table stays in place
     * because the host's own {@link AssetManager} is extended rather than
     * replaced.
     */
    private void mergeGuestResources() {
        if (!apk.isFile()) return;
        try {
            AssetManager assets = super.getAssets();
            Method addAssetPath =
                    AssetManager.class.getMethod("addAssetPath", String.class);
            Object cookie = addAssetPath.invoke(assets, apk.getAbsolutePath());
            resourcesMerged = cookie instanceof Integer && (Integer) cookie != 0;
            Log.i(TAG, "guest resources " + (resourcesMerged ? "merged" : "rejected")
                    + " from " + apk.getName());
        } catch (Throwable t) {
            // addAssetPath is a non-SDK interface on recent releases; a clean
            // host theme is still usable when enforcement blocks it.
            Log.w(TAG, "guest resource merge unavailable: " + t);
        }
    }

    @Override
    public AssetManager getAssets() {
        return super.getAssets();
    }

    @Override
    public Resources getResources() {
        return super.getResources();
    }

    @Override
    public Resources.Theme getTheme() {
        if (!resourcesMerged) return super.getTheme();
        if (guestTheme == null) {
            guestTheme = super.getResources().newTheme();
            guestTheme.setTo(super.getTheme());
        }
        return guestTheme;
    }

    @Override
    public File getFilesDir() {
        return new File(gameDir, "data");
    }

    @Override
    public File getCacheDir() {
        return new File(gameDir, "cache");
    }

    @Override
    public File getObbDir() {
        return new File(gameDir, "obb");
    }

    @Override
    public File getExternalFilesDir(String type) {
        File dir = new File(gameDir, "data/external");
        if (type != null) dir = new File(dir, type);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        File dir = getExternalFilesDir(type);
        return dir == null ? new File[0] : new File[]{dir};
    }

    @Override
    public File getExternalCacheDir() {
        return new File(gameDir, "cache/external");
    }

    @Override
    public String getPackageName() {
        return guestPackage;
    }

    @Override
    public String getOpPackageName() {
        return guestPackage;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public ClassLoader getClassLoader() {
        return guestLoader != null ? guestLoader : super.getClassLoader();
    }
}

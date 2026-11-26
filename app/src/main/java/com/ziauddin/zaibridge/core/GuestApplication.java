package com.ziauddin.zaibridge.core;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;

import java.io.File;

/**
 * Isolated {@link Application} facade returned by {@link GuestContext#getApplicationContext()}
 * and attached as {@code Activity.mApplication} for virtualized guest games.
 *
 * <p>Unity and Google GameActivity query {@code getApplication()} /
 * {@code getApplicationContext()} for resources, assets, package info, files dirs,
 * and OBB directories. Without a dedicated {@code Application} wrapper, those calls
 * hit Zai-Bridge's own {@link BridgeApplication} and resolve the wrong {@code Resources}
 * or {@code PackageManager}.
 */
public final class GuestApplication extends Application {

    private final GuestContext guestContext;

    public GuestApplication(GuestContext guestContext) {
        this.guestContext = guestContext;
        attachBaseContext(guestContext);
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Resources getResources() {
        return guestContext.getResources();
    }

    @Override
    public AssetManager getAssets() {
        return guestContext.getAssets();
    }

    @Override
    public Resources.Theme getTheme() {
        return guestContext.getTheme();
    }

    @Override
    public ClassLoader getClassLoader() {
        return guestContext.getClassLoader();
    }

    @Override
    public String getPackageName() {
        return guestContext.getPackageName();
    }

    @Override
    public String getOpPackageName() {
        return guestContext.getOpPackageName();
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        return guestContext.getApplicationInfo();
    }

    @Override
    public PackageManager getPackageManager() {
        return guestContext.getPackageManager();
    }

    @Override
    public File getFilesDir() {
        return guestContext.getFilesDir();
    }

    @Override
    public File getCacheDir() {
        return guestContext.getCacheDir();
    }

    @Override
    public File getCodeCacheDir() {
        return guestContext.getCodeCacheDir();
    }

    @Override
    public File getNoBackupFilesDir() {
        return guestContext.getNoBackupFilesDir();
    }

    @Override
    public File getExternalFilesDir(String type) {
        return guestContext.getExternalFilesDir(type);
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        return guestContext.getExternalFilesDirs(type);
    }

    @Override
    public File getExternalCacheDir() {
        return guestContext.getExternalCacheDir();
    }

    @Override
    public File getObbDir() {
        return guestContext.getObbDir();
    }

    @Override
    public File[] getObbDirs() {
        return guestContext.getObbDirs();
    }

    @Override
    public File getDir(String name, int mode) {
        return guestContext.getDir(name, mode);
    }

    @Override
    public File getDatabasePath(String name) {
        return guestContext.getDatabasePath(name);
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return guestContext.getSharedPreferences(name, mode);
    }

    @Override
    public void startActivity(Intent intent) {
        startActivity(intent, null);
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        if (intent != null && (intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        guestContext.startActivity(intent, options);
    }
}

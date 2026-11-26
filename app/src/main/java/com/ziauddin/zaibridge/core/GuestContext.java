package com.ziauddin.zaibridge.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import com.ziauddin.zaibridge.R;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Isolated {@link Context} for a virtualized guest game running inside Zai-Bridge.
 *
 * <p>Resolves resources using Android 16's genuine OS-merged split-APK binding
 * ({@code createPackageContext(..., CONTEXT_IGNORE_SECURITY)} /
 * {@code getResourcesForApplication(pkg)}) when the game is installed, falling back to
 * standalone base APK + {@code splits/*.apk} merged {@code AssetManager}, wrapped in
 * {@link GuestResourcesWrapper} to eliminate {@code Resources$NotFoundException: #0x0}.
 * Also virtualizes {@link PackageManager} via {@link GuestPackageManagerWrapper} and
 * {@code Application} via {@link GuestApplication}.
 */
public final class GuestContext extends ContextWrapper {

    private static final String TAG = "ZaiBridgeGuestCtx";

    private final Context hostBase;
    private final File gameDir;
    private final String hostPackage;
    private final String guestPackage;
    private final String primaryActivityClass;
    private final boolean native64;
    private final ClassLoader guestLoader;

    private final List<String> assetApkPaths;
    private final GuestResourcesWrapper resources;
    private final Resources.Theme theme;
    private final ApplicationInfo appInfo;
    private final GuestPackageManagerWrapper packageManager;
    private final GuestApplication guestApplication;
    private final ActivityInfo fallbackActivityInfo;

    public GuestContext(Context host, File gameDir, String guestPackage, ClassLoader guestLoader) {
        this(host, gameDir, guestPackage, "", detectNative64Dir(gameDir), guestLoader);
    }

    public GuestContext(
            Context host,
            File gameDir,
            String guestPackage,
            String primaryActivityClass,
            boolean native64,
            ClassLoader guestLoader) {
        super(host);
        this.hostBase = host;
        this.gameDir = gameDir;
        this.hostPackage = host.getPackageName();
        this.guestPackage = (guestPackage != null && !guestPackage.isEmpty())
                ? guestPackage
                : host.getPackageName();
        this.guestLoader = guestLoader;
        this.native64 = native64;

        File stagedBaseApk = new File(gameDir, "game.apk");
        List<File> stagedSplitFiles = collectStagedSplitApks(gameDir);

        PackageManager hostPm = host.getPackageManager();
        PackageInfo installedPkgInfo = queryInstalledPackageInfo(hostPm, this.guestPackage);

        String resolvedBaseApk = stagedBaseApk.isFile() ? stagedBaseApk.getAbsolutePath() : null;
        if (resolvedBaseApk == null && installedPkgInfo != null && installedPkgInfo.applicationInfo != null) {
            resolvedBaseApk = installedPkgInfo.applicationInfo.sourceDir;
        }

        List<String> apkPaths = new ArrayList<>();
        if (resolvedBaseApk != null && new File(resolvedBaseApk).isFile()) {
            apkPaths.add(resolvedBaseApk);
        }
        List<String> splitPaths = new ArrayList<>();
        if (!stagedSplitFiles.isEmpty()) {
            for (File f : stagedSplitFiles) {
                String p = f.getAbsolutePath();
                splitPaths.add(p);
                apkPaths.add(p);
            }
        } else if (installedPkgInfo != null
                && installedPkgInfo.applicationInfo != null
                && installedPkgInfo.applicationInfo.splitSourceDirs != null) {
            for (String p : installedPkgInfo.applicationInfo.splitSourceDirs) {
                if (p != null && new File(p).isFile()) {
                    splitPaths.add(p);
                    apkPaths.add(p);
                }
            }
        }
        this.assetApkPaths = Collections.unmodifiableList(apkPaths);

        BinaryXmlManifestReader.ParsedManifest manifest =
                BinaryXmlManifestReader.readFromApk(resolvedBaseApk);
        String resolvedActivity = primaryActivityClass != null ? primaryActivityClass : "";
        if (resolvedActivity.isEmpty()) {
            resolvedActivity = manifest.launcherActivity;
        }
        resolvedActivity = BinaryXmlManifestReader.normalizeClassName(
                this.guestPackage, resolvedActivity);
        resolvedActivity = manifest.resolveActivityTarget(resolvedActivity);
        this.primaryActivityClass = resolvedActivity;

        PackageInfo effectivePkgInfo = installedPkgInfo;
        if (effectivePkgInfo == null && resolvedBaseApk != null) {
            effectivePkgInfo = queryArchivePackageInfo(hostPm, resolvedBaseApk);
        }

        this.appInfo = buildGuestApplicationInfo(
                host,
                gameDir,
                this.guestPackage,
                native64,
                resolvedBaseApk,
                splitPaths,
                installedPkgInfo,
                effectivePkgInfo,
                manifest);

        Map<String, ActivityInfo> actMap = buildActivityInfoMap(
                this.guestPackage,
                this.primaryActivityClass,
                this.appInfo,
                effectivePkgInfo,
                manifest);
        this.fallbackActivityInfo = pickFallbackActivityInfo(
                this.guestPackage, this.primaryActivityClass, this.appInfo, actMap, manifest);

        if (effectivePkgInfo != null) {
            effectivePkgInfo.applicationInfo = this.appInfo;
        }

        ResourcesInitResult resInit = resolveGuestResources(
                host, hostPm, this.guestPackage, this.appInfo, this.assetApkPaths);
        this.resources = new GuestResourcesWrapper(
                resInit.primaryResources,
                host.getResources(),
                this.guestPackage,
                this.hostPackage);
        this.theme = this.resources.newTheme();
        if (resInit.packageTheme != null) {
            try {
                this.theme.setTo(resInit.packageTheme);
            } catch (Throwable t) {
                this.theme.applyStyle(R.style.Theme_ZaiBridge_GameHost, true);
            }
        } else {
            this.theme.applyStyle(R.style.Theme_ZaiBridge_GameHost, true);
        }

        this.packageManager = new GuestPackageManagerWrapper(
                hostPm,
                this,
                this.hostPackage,
                this.guestPackage,
                this.primaryActivityClass,
                this.appInfo,
                effectivePkgInfo,
                actMap,
                this.fallbackActivityInfo);

        this.guestApplication = new GuestApplication(this);
        ensureStorageDirs();
    }

    private static final class ResourcesInitResult {
        final Resources primaryResources;
        final Resources.Theme packageTheme;

        ResourcesInitResult(Resources primaryResources, Resources.Theme packageTheme) {
            this.primaryResources = primaryResources;
            this.packageTheme = packageTheme;
        }
    }

    private static boolean detectNative64Dir(File gameDir) {
        if (gameDir == null) return false;
        File dir = new File(gameDir, "native64");
        File[] files = dir.listFiles();
        return files != null && files.length > 0;
    }

    public static List<File> collectStagedSplitApks(File gameDir) {
        List<File> out = new ArrayList<>();
        if (gameDir == null) return out;
        File splitsDir = new File(gameDir, "splits");
        File[] files = splitsDir.listFiles();
        if (files == null || files.length == 0) return out;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".apk")) {
                out.add(f);
            }
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo queryInstalledPackageInfo(PackageManager pm, String pkg) {
        if (pm == null || pkg == null || pkg.isEmpty()) return null;
        int fullFlags = PackageManager.GET_ACTIVITIES
                | PackageManager.GET_META_DATA
                | PackageManager.GET_PROVIDERS
                | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PERMISSIONS;
        try {
            return pm.getPackageInfo(pkg, fullFlags);
        } catch (PackageManager.NameNotFoundException notInstalled) {
            return null;
        } catch (Throwable binderLarge) {
            try {
                return pm.getPackageInfo(
                        pkg, PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo queryArchivePackageInfo(PackageManager pm, String apkPath) {
        if (pm == null || apkPath == null) return null;
        int flags = PackageManager.GET_ACTIVITIES
                | PackageManager.GET_META_DATA
                | PackageManager.GET_SERVICES
                | PackageManager.GET_PROVIDERS
                | PackageManager.GET_RECEIVERS;
        try {
            return pm.getPackageArchiveInfo(apkPath, flags);
        } catch (Throwable ignored) {
            try {
                return pm.getPackageArchiveInfo(
                        apkPath, PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    private static ApplicationInfo buildGuestApplicationInfo(
            Context host,
            File gameDir,
            String guestPkg,
            boolean native64,
            String baseApk,
            List<String> splitPaths,
            PackageInfo installedPkg,
            PackageInfo archivePkg,
            BinaryXmlManifestReader.ParsedManifest manifest) {
        ApplicationInfo ai;
        if (installedPkg != null && installedPkg.applicationInfo != null) {
            ai = new ApplicationInfo(installedPkg.applicationInfo);
        } else if (archivePkg != null && archivePkg.applicationInfo != null) {
            ai = new ApplicationInfo(archivePkg.applicationInfo);
        } else {
            ai = new ApplicationInfo(host.getApplicationInfo());
        }

        ai.packageName = guestPkg;
        ai.uid = Process.myUid();

        if (baseApk != null) {
            ai.sourceDir = baseApk;
            ai.publicSourceDir = baseApk;
        }
        if (!splitPaths.isEmpty()) {
            String[] splitsArr = splitPaths.toArray(new String[0]);
            ai.splitSourceDirs = splitsArr;
            ai.splitPublicSourceDirs = splitsArr;
        }

        File native64Dir = new File(gameDir, "native64");
        File native32Dir = new File(gameDir, "native");
        if (native64 && hasAnyFile(native64Dir)) {
            ai.nativeLibraryDir = native64Dir.getAbsolutePath();
        } else if (installedPkg != null
                && installedPkg.applicationInfo != null
                && installedPkg.applicationInfo.nativeLibraryDir != null) {
            ai.nativeLibraryDir = installedPkg.applicationInfo.nativeLibraryDir;
        } else {
            ai.nativeLibraryDir = native32Dir.getAbsolutePath();
        }

        File dataDir = new File(gameDir, "data");
        //noinspection ResultOfMethodCallIgnored
        dataDir.mkdirs();
        ai.dataDir = dataDir.getAbsolutePath();
        if (Build.VERSION.SDK_INT >= 24) {
            ai.deviceProtectedDataDir = dataDir.getAbsolutePath();
        }

        Bundle meta = new Bundle();
        if (ai.metaData != null) meta.putAll(ai.metaData);
        if (!manifest.applicationMetaData.isEmpty()) {
            meta.putAll(manifest.applicationMetaData);
        }
        ai.metaData = meta;
        return ai;
    }

    private static boolean hasAnyFile(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] list = dir.listFiles();
        return list != null && list.length > 0;
    }

    private static Map<String, ActivityInfo> buildActivityInfoMap(
            String guestPkg,
            String primaryActivity,
            ApplicationInfo appInfo,
            PackageInfo pkgInfo,
            BinaryXmlManifestReader.ParsedManifest manifest) {
        Map<String, ActivityInfo> map = new HashMap<>();
        if (pkgInfo != null && pkgInfo.activities != null) {
            for (ActivityInfo src : pkgInfo.activities) {
                if (src == null || src.name == null) continue;
                ActivityInfo ai = new ActivityInfo(src);
                ai.packageName = guestPkg;
                ai.applicationInfo = appInfo;
                ai.name = BinaryXmlManifestReader.normalizeClassName(guestPkg, src.name);

                Bundle merged = manifest.buildMergedMetaData(ai.name);
                if (src.metaData != null) {
                    merged.putAll(src.metaData);
                }
                ai.metaData = merged;
                map.put(ai.name, ai);
            }
        }
        // Wire <activity-alias> names to their resolved target ActivityInfo.
        for (Map.Entry<String, String> entry : manifest.aliasTargets.entrySet()) {
            ActivityInfo targetInfo = map.get(entry.getValue());
            if (targetInfo != null) {
                ActivityInfo aliasInfo = new ActivityInfo(targetInfo);
                Bundle merged = manifest.buildMergedMetaData(entry.getKey());
                if (targetInfo.metaData != null) merged.putAll(targetInfo.metaData);
                aliasInfo.metaData = merged;
                map.put(entry.getKey(), aliasInfo);
            }
        }
        if (primaryActivity != null && !primaryActivity.isEmpty() && !map.containsKey(primaryActivity)) {
            ActivityInfo synth = new ActivityInfo();
            synth.packageName = guestPkg;
            synth.name = primaryActivity;
            synth.applicationInfo = appInfo;
            synth.metaData = manifest.buildMergedMetaData(primaryActivity);
            map.put(primaryActivity, synth);
        }
        return map;
    }

    private static ActivityInfo pickFallbackActivityInfo(
            String guestPkg,
            String primaryActivity,
            ApplicationInfo appInfo,
            Map<String, ActivityInfo> actMap,
            BinaryXmlManifestReader.ParsedManifest manifest) {
        if (primaryActivity != null && !primaryActivity.isEmpty()) {
            ActivityInfo hit = actMap.get(primaryActivity);
            if (hit != null) return hit;
        }
        if (!actMap.isEmpty()) {
            return actMap.values().iterator().next();
        }
        ActivityInfo fallback = new ActivityInfo();
        fallback.packageName = guestPkg;
        fallback.name = primaryActivity != null ? primaryActivity : "";
        fallback.applicationInfo = appInfo;
        fallback.metaData = manifest.buildMergedMetaData(primaryActivity);
        return fallback;
    }

    @SuppressWarnings("deprecation")
    private static ResourcesInitResult resolveGuestResources(
            Context host,
            PackageManager pm,
            String guestPkg,
            ApplicationInfo guestAppInfo,
            List<String> apkPaths) {
        // 1. Primary Installed Games Fast-Path: OS merges base APK + ALL split APKs + asset packs.
        try {
            Context pkgCtx = host.createPackageContext(guestPkg, Context.CONTEXT_IGNORE_SECURITY);
            Resources pkgRes = pkgCtx != null ? pkgCtx.getResources() : null;
            if (pkgRes != null) {
                Log.i(TAG, "bound OS package resources via createPackageContext(" + guestPkg + ")");
                return new ResourcesInitResult(pkgRes, pkgCtx.getTheme());
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // Game is standalone / uninstalled APK; use fallback below.
        } catch (Throwable t) {
            Log.w(TAG, "createPackageContext failed for " + guestPkg + ": " + t.getMessage());
        }

        try {
            Resources pmRes = pm.getResourcesForApplication(guestPkg);
            if (pmRes != null) {
                Log.i(TAG, "bound OS package resources via getResourcesForApplication(" + guestPkg + ")");
                return new ResourcesInitResult(pmRes, null);
            }
        } catch (Throwable ignored) {
        }

        // 2. Standalone / Uninstalled APK + staged splits fallback.
        try {
            AssetManager am = AssetManager.class.getDeclaredConstructor().newInstance();
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            int loadedCount = 0;
            for (String path : apkPaths) {
                if (path == null || path.isEmpty()) continue;
                Object cookie = addAssetPath.invoke(am, path);
                if (cookie instanceof Integer && ((Integer) cookie) > 0) {
                    loadedCount++;
                } else {
                    Log.w(TAG, "addAssetPath returned 0 for " + path);
                }
            }
            if (loadedCount > 0) {
                Resources res = new Resources(
                        am,
                        host.getResources().getDisplayMetrics(),
                        host.getResources().getConfiguration());
                Log.i(TAG, "built standalone merged AssetManager (" + loadedCount + " APK/split paths)");
                return new ResourcesInitResult(res, null);
            }
            am.close();
        } catch (Throwable t) {
            Log.w(TAG, "standalone AssetManager fallback failed: " + t.getMessage());
        }

        try {
            Resources appRes = pm.getResourcesForApplication(guestAppInfo);
            if (appRes != null) {
                return new ResourcesInitResult(appRes, null);
            }
        } catch (Throwable ignored) {
        }

        Log.w(TAG, "using host Resources as last-resort base for " + guestPkg);
        return new ResourcesInitResult(host.getResources(), null);
    }

    /**
     * Merges the guest's base APK and all staged split APKs into an existing
     * {@link AssetManager} so legacy views created against an Activity's initial
     * resources still read {@code assets/} and resource entries cleanly.
     */
    public void mergeInto(AssetManager target) {
        if (target == null || assetApkPaths.isEmpty()) return;
        try {
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            for (String path : assetApkPaths) {
                try {
                    addAssetPath.invoke(target, path);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "AssetManager mergeInto failed: " + t.getMessage());
        }
    }

    private void ensureStorageDirs() {
        mkdirSafe(getFilesDir());
        mkdirSafe(getCacheDir());
        mkdirSafe(getCodeCacheDir());
        mkdirSafe(getNoBackupFilesDir());
        mkdirSafe(getExternalFilesDir(null));
        mkdirSafe(getExternalCacheDir());
        mkdirSafe(getObbDir());
    }

    private static File mkdirSafe(File dir) {
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public ActivityInfo resolveGuestActivityInfo(ComponentName cn) {
        return packageManager.resolveGuestActivityInfo(cn);
    }

    public GuestApplication getGuestApplication() {
        return guestApplication;
    }

    public String getPrimaryActivityClass() {
        return primaryActivityClass;
    }

    public Context getHostBaseContext() {
        return hostBase;
    }

    @Override
    public Context getApplicationContext() {
        return guestApplication;
    }

    @Override
    public Resources getResources() {
        return resources;
    }

    @Override
    public AssetManager getAssets() {
        return resources.getAssets();
    }

    @Override
    public Resources.Theme getTheme() {
        return theme;
    }

    @Override
    public String getPackageName() {
        return guestPackage;
    }

    @Override
    public String getOpPackageName() {
        // Android 10–16 AppOpsManager verifies calling UID matches getOpPackageName().
        return super.getOpPackageName();
    }

    @Override
    public String getAttributionTag() {
        if (Build.VERSION.SDK_INT >= 30) {
            return super.getAttributionTag();
        }
        return null;
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        return appInfo;
    }

    @Override
    public PackageManager getPackageManager() {
        return packageManager;
    }

    @Override
    public String getPackageCodePath() {
        return appInfo.sourceDir != null ? appInfo.sourceDir : super.getPackageCodePath();
    }

    @Override
    public String getPackageResourcePath() {
        return appInfo.publicSourceDir != null ? appInfo.publicSourceDir : super.getPackageResourcePath();
    }

    @Override
    public ClassLoader getClassLoader() {
        return guestLoader != null ? guestLoader : super.getClassLoader();
    }

    /**
     * Resolves a safe theme resource ID guaranteed to belong either to the guest's
     * own {@link AssetManager} or Android's universal framework resources ({@code 0x01......}).
     * Prevents applying a host {@code 0x7f......} theme ID onto the guest AssetManager.
     */
    public int resolveGuestThemeRes(ActivityInfo guestAi) {
        Resources guestPrimary = resources.getGuestPrimary();
        int[] candidates = {
                guestAi != null ? guestAi.theme : 0,
                appInfo != null ? appInfo.theme : 0,
                guestPrimary.getIdentifier("UnityThemeSelector", "style", guestPackage),
                guestPrimary.getIdentifier("Theme.AppCompat.NoActionBar", "style", guestPackage),
                guestPrimary.getIdentifier("Theme.AppCompat.Light.NoActionBar", "style", guestPackage),
        };
        for (int candidate : candidates) {
            if (candidate == 0) continue;
            if ((candidate & 0xff000000) == 0x01000000) {
                return candidate;
            }
            try {
                String type = guestPrimary.getResourceTypeName(candidate);
                if ("style".equals(type)) {
                    return candidate;
                }
            } catch (Throwable ignored) {
            }
        }
        return native64
                ? android.R.style.Theme_Material_NoActionBar
                : (appInfo != null && appInfo.targetSdkVersion < 21
                        ? android.R.style.Theme_Holo_NoActionBar
                        : android.R.style.Theme_Material_NoActionBar);
    }

    @Override
    public Context createPackageContext(String packageName, int flags)
            throws PackageManager.NameNotFoundException {
        if (guestPackage.equals(packageName)) {
            return this;
        }
        return super.createPackageContext(packageName, flags);
    }

    @Override
    public Context createConfigurationContext(android.content.res.Configuration overrideConfiguration) {
        Context derived = super.createConfigurationContext(overrideConfiguration);
        if (derived == null) return this;
        GuestResourcesWrapper cfgRes = resources.withConfiguration(
                overrideConfiguration, derived.getResources().getDisplayMetrics());
        return wrapDerivedContext(derived, cfgRes);
    }

    @Override
    public Context createDisplayContext(android.view.Display display) {
        Context derived = super.createDisplayContext(display);
        if (derived == null) return this;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        if (display != null) {
            try {
                display.getMetrics(dm);
            } catch (Throwable t) {
                dm = resources.getDisplayMetrics();
            }
        } else {
            dm = resources.getDisplayMetrics();
        }
        GuestResourcesWrapper dispRes = resources.withConfiguration(resources.getConfiguration(), dm);
        return wrapDerivedContext(derived, dispRes);
    }

    @Override
    public Context createDeviceProtectedStorageContext() {
        Context derived = super.createDeviceProtectedStorageContext();
        return derived == null ? this : wrapDerivedContext(derived, resources);
    }

    private Context wrapDerivedContext(Context derived, final GuestResourcesWrapper derivedRes) {
        final Resources.Theme derivedTheme = derivedRes.newTheme();
        derivedTheme.setTo(theme);
        return new ContextWrapper(derived) {
            @Override public Resources getResources() { return derivedRes; }
            @Override public AssetManager getAssets() { return derivedRes.getAssets(); }
            @Override public Resources.Theme getTheme() { return derivedTheme; }
            @Override public String getPackageName() { return GuestContext.this.getPackageName(); }
            @Override public String getOpPackageName() { return GuestContext.this.getOpPackageName(); }
            @Override public ApplicationInfo getApplicationInfo() { return GuestContext.this.getApplicationInfo(); }
            @Override public PackageManager getPackageManager() { return GuestContext.this.getPackageManager(); }
            @Override public ClassLoader getClassLoader() { return GuestContext.this.getClassLoader(); }
            @Override public Context getApplicationContext() { return GuestContext.this.getApplicationContext(); }
            @Override public File getFilesDir() { return GuestContext.this.getFilesDir(); }
            @Override public File getCacheDir() { return GuestContext.this.getCacheDir(); }
            @Override public File getObbDir() { return GuestContext.this.getObbDir(); }
        };
    }

    @Override
    public File getFilesDir() {
        return mkdirSafe(new File(gameDir, "files"));
    }

    @Override
    public File getCacheDir() {
        return mkdirSafe(new File(gameDir, "cache"));
    }

    @Override
    public File getCodeCacheDir() {
        return mkdirSafe(new File(gameDir, "code_cache"));
    }

    @Override
    public File getNoBackupFilesDir() {
        return mkdirSafe(new File(gameDir, "no_backup"));
    }

    @Override
    public File getDir(String name, int mode) {
        return mkdirSafe(new File(gameDir, "app_" + name));
    }

    @Override
    public File getDatabasePath(String name) {
        File dbDir = mkdirSafe(new File(gameDir, "databases"));
        return new File(dbDir, name);
    }

    @Override
    public File getExternalFilesDir(String type) {
        File base = new File(gameDir, "ext_files");
        return mkdirSafe(type != null && !type.isEmpty() ? new File(base, type) : base);
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        return new File[]{getExternalFilesDir(type)};
    }

    @Override
    public File getExternalCacheDir() {
        return mkdirSafe(new File(gameDir, "ext_cache"));
    }

    @Override
    public File getObbDir() {
        return mkdirSafe(new File(gameDir, "obb"));
    }

    @Override
    public File[] getObbDirs() {
        return new File[]{getObbDir()};
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        String safePkg = guestPackage.replaceAll("[^A-Za-z0-9._-]", "_");
        return super.getSharedPreferences("guest_" + safePkg + "_" + name, mode);
    }
}

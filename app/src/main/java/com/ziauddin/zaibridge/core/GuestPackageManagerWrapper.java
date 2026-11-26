package com.ziauddin.zaibridge.core;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.FeatureInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.VersionedPackage;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link PackageManager} virtualization wrapper for guest components inside `:guest`.
 *
 * <p>Intercepts metadata, component, provider, application, package, and resource queries
 * for both the guest package name and the container's own package name (when SDKs query
 * {@code context.getPackageName()} before context propagation finishes) so Unity, Play
 * Services, Firebase, and AndroidX Startup always read the resolved {@code metaData} Bundle.
 */
@SuppressWarnings("deprecation")
public final class GuestPackageManagerWrapper extends PackageManager {

    private final PackageManager base;
    private final GuestContext guestContext;
    private final String hostPackage;
    private final String guestPackage;
    private final String primaryActivityClass;
    private final ApplicationInfo guestAppInfo;
    @Nullable
    private final PackageInfo guestPackageInfo;
    private final Map<String, ActivityInfo> guestActivities;
    private final ActivityInfo fallbackActivityInfo;
    private final List<ProviderInfo> guestProviders;

    public GuestPackageManagerWrapper(
            @NonNull PackageManager base,
            @NonNull GuestContext guestContext,
            @NonNull String hostPackage,
            @NonNull String guestPackage,
            @NonNull String primaryActivityClass,
            @NonNull ApplicationInfo guestAppInfo,
            @Nullable PackageInfo guestPackageInfo,
            @NonNull Map<String, ActivityInfo> guestActivities,
            @NonNull ActivityInfo fallbackActivityInfo,
            @NonNull List<ProviderInfo> guestProviders) {
        this.base = base;
        this.guestContext = guestContext;
        this.hostPackage = hostPackage;
        this.guestPackage = guestPackage;
        this.primaryActivityClass = primaryActivityClass;
        this.guestAppInfo = guestAppInfo;
        this.guestPackageInfo = guestPackageInfo;
        this.guestActivities = guestActivities;
        this.fallbackActivityInfo = fallbackActivityInfo;
        this.guestProviders = guestProviders;
    }

    private boolean isVirtualHostActivity(@Nullable ComponentName component) {
        if (component == null) return false;
        String pkg = component.getPackageName();
        String cls = component.getClassName();
        if (cls != null && (cls.endsWith("ProxyGameActivity") || cls.endsWith("GameHostActivity"))) {
            return true;
        }
        return isVirtualGuestOrContainerPkg(pkg);
    }

    private boolean isGuestPkg(@Nullable String packageName) {
        return packageName != null && packageName.equals(guestPackage);
    }

    private boolean isVirtualGuestOrContainerPkg(@Nullable String packageName) {
        return packageName != null && (packageName.equals(guestPackage) || packageName.equals(hostPackage));
    }

    @Nullable
    public ActivityInfo resolveGuestActivityInfo(@Nullable ComponentName component) {
        if (component == null) {
            return cloneActivityInfo(fallbackActivityInfo);
        }
        String cls = component.getClassName();
        if (cls != null && !cls.isEmpty()) {
            String normalized = BinaryXmlManifestReader.normalizeClassName(guestPackage, cls);
            ActivityInfo exact = guestActivities.get(normalized);
            if (exact != null) return cloneActivityInfo(exact);
            exact = guestActivities.get(cls);
            if (exact != null) return cloneActivityInfo(exact);
        }
        if (isVirtualHostActivity(component)) {
            return cloneActivityInfo(fallbackActivityInfo);
        }
        return null;
    }

    private ApplicationInfo cloneGuestApplicationInfo() {
        ApplicationInfo copy = new ApplicationInfo(guestAppInfo);
        if (guestAppInfo.metaData != null) {
            copy.metaData = new Bundle(guestAppInfo.metaData);
        } else {
            copy.metaData = new Bundle();
        }
        return copy;
    }

    private ActivityInfo cloneActivityInfo(ActivityInfo src) {
        ActivityInfo copy = new ActivityInfo(src);
        copy.applicationInfo = cloneGuestApplicationInfo();
        copy.packageName = guestPackage;
        if (copy.metaData == null) {
            copy.metaData = new Bundle();
        } else {
            copy.metaData = new Bundle(copy.metaData);
        }
        return copy;
    }

    private ProviderInfo cloneProviderInfo(ProviderInfo src) {
        ProviderInfo copy = new ProviderInfo(src);
        copy.applicationInfo = cloneGuestApplicationInfo();
        copy.packageName = guestPackage;
        if (copy.metaData == null) {
            copy.metaData = new Bundle();
        } else {
            copy.metaData = new Bundle(copy.metaData);
        }
        return copy;
    }

    @Nullable
    private ProviderInfo findGuestProvider(@Nullable ComponentName component) {
        if (component == null) return null;
        String pkg = component.getPackageName();
        if (!isVirtualGuestOrContainerPkg(pkg)) return null;
        String cls = component.getClassName();
        String norm = BinaryXmlManifestReader.normalizeClassName(guestPackage, cls);
        for (ProviderInfo pi : guestProviders) {
            if (pi.name != null && (pi.name.equals(cls) || pi.name.equals(norm))) {
                return cloneProviderInfo(pi);
            }
        }
        return null;
    }

    @Nullable
    private PackageInfo cloneGuestPackageInfo() {
        if (guestPackageInfo == null) return null;
        PackageInfo copy = new PackageInfo();
        copy.packageName = guestPackageInfo.packageName != null ? guestPackageInfo.packageName : guestPackage;
        copy.versionCode = guestPackageInfo.versionCode;
        if (Build.VERSION.SDK_INT >= 28) {
            copy.setLongVersionCode(guestPackageInfo.getLongVersionCode());
        }
        copy.versionName = guestPackageInfo.versionName;
        copy.sharedUserId = guestPackageInfo.sharedUserId;
        copy.sharedUserLabel = guestPackageInfo.sharedUserLabel;
        copy.firstInstallTime = guestPackageInfo.firstInstallTime;
        copy.lastUpdateTime = guestPackageInfo.lastUpdateTime;
        copy.requestedPermissions = guestPackageInfo.requestedPermissions;
        copy.requestedPermissionsFlags = guestPackageInfo.requestedPermissionsFlags;
        copy.splitNames = guestPackageInfo.splitNames;
        copy.signatures = guestPackageInfo.signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            copy.signingInfo = guestPackageInfo.signingInfo;
        }
        copy.applicationInfo = cloneGuestApplicationInfo();
        copy.activities = guestPackageInfo.activities;
        copy.services = guestPackageInfo.services;
        copy.receivers = guestPackageInfo.receivers;
        copy.providers = guestProviders.isEmpty()
                ? guestPackageInfo.providers
                : guestProviders.toArray(new ProviderInfo[0]);
        return copy;
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName component, int flags) throws NameNotFoundException {
        ActivityInfo intercepted = resolveGuestActivityInfo(component);
        if (intercepted != null) {
            return intercepted;
        }
        return base.getActivityInfo(component, flags);
    }

    @RequiresApi(33)
    @Override
    public ActivityInfo getActivityInfo(ComponentName component, ComponentInfoFlags flags)
            throws NameNotFoundException {
        ActivityInfo intercepted = resolveGuestActivityInfo(component);
        if (intercepted != null) {
            return intercepted;
        }
        return base.getActivityInfo(component, flags);
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags) throws NameNotFoundException {
        if (isVirtualGuestOrContainerPkg(packageName)) {
            return cloneGuestApplicationInfo();
        }
        return base.getApplicationInfo(packageName, flags);
    }

    @RequiresApi(33)
    @Override
    public ApplicationInfo getApplicationInfo(String packageName, ApplicationInfoFlags flags)
            throws NameNotFoundException {
        if (isVirtualGuestOrContainerPkg(packageName)) {
            return cloneGuestApplicationInfo();
        }
        return base.getApplicationInfo(packageName, flags);
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
        if (isVirtualGuestOrContainerPkg(packageName)) {
            PackageInfo pi = cloneGuestPackageInfo();
            if (pi != null) return pi;
        }
        return base.getPackageInfo(packageName, flags);
    }

    @RequiresApi(33)
    @Override
    public PackageInfo getPackageInfo(String packageName, PackageInfoFlags flags)
            throws NameNotFoundException {
        if (isVirtualGuestOrContainerPkg(packageName)) {
            PackageInfo pi = cloneGuestPackageInfo();
            if (pi != null) return pi;
        }
        return base.getPackageInfo(packageName, flags);
    }

    @Override
    public PackageInfo getPackageInfo(VersionedPackage versionedPackage, int flags)
            throws NameNotFoundException {
        if (versionedPackage != null && isVirtualGuestOrContainerPkg(versionedPackage.getPackageName())) {
            PackageInfo pi = cloneGuestPackageInfo();
            if (pi != null) return pi;
        }
        return base.getPackageInfo(versionedPackage, flags);
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName component, int flags) throws NameNotFoundException {
        ProviderInfo found = findGuestProvider(component);
        if (found != null) return found;
        return base.getProviderInfo(component, flags);
    }

    @RequiresApi(33)
    @Override
    public ProviderInfo getProviderInfo(ComponentName component, ComponentInfoFlags flags)
            throws NameNotFoundException {
        ProviderInfo found = findGuestProvider(component);
        if (found != null) return found;
        return base.getProviderInfo(component, flags);
    }

    @Override
    public ProviderInfo resolveContentProvider(String authority, int flags) {
        if (authority != null && !authority.isEmpty()) {
            for (ProviderInfo pi : guestProviders) {
                if (pi.authority != null && pi.authority.contains(authority)) {
                    return cloneProviderInfo(pi);
                }
            }
        }
        return base.resolveContentProvider(authority, flags);
    }

    @Override
    public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags) {
        List<ProviderInfo> merged = new ArrayList<>();
        for (ProviderInfo pi : guestProviders) {
            merged.add(cloneProviderInfo(pi));
        }
        List<ProviderInfo> real = base.queryContentProviders(processName, uid, flags);
        if (real != null) {
            merged.addAll(real);
        }
        return merged;
    }

    @Override
    public Resources getResourcesForActivity(ComponentName activityName) throws NameNotFoundException {
        if (isVirtualHostActivity(activityName) || resolveGuestActivityInfo(activityName) != null) {
            return guestContext.getResources();
        }
        return base.getResourcesForActivity(activityName);
    }

    @Override
    public Resources getResourcesForApplication(ApplicationInfo app) throws NameNotFoundException {
        if (app != null && (isVirtualGuestOrContainerPkg(app.packageName) || app == guestAppInfo)) {
            return guestContext.getResources();
        }
        return base.getResourcesForApplication(app);
    }

    @SuppressLint("NewApi")
    @Override
    public Resources getResourcesForApplication(ApplicationInfo app, Configuration configuration)
            throws NameNotFoundException {
        if (app != null && (isVirtualGuestOrContainerPkg(app.packageName) || app == guestAppInfo)) {
            return guestContext.getResources();
        }
        return base.getResourcesForApplication(app, configuration);
    }

    @Override
    public Resources getResourcesForApplication(String appPackageName) throws NameNotFoundException {
        if (isVirtualGuestOrContainerPkg(appPackageName)) {
            return guestContext.getResources();
        }
        return base.getResourcesForApplication(appPackageName);
    }

    @Override
    public Intent getLaunchIntentForPackage(String packageName) {
        if (isGuestPkg(packageName) && primaryActivityClass != null && !primaryActivityClass.isEmpty()) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(new ComponentName(guestPackage, primaryActivityClass));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        }
        return base.getLaunchIntentForPackage(packageName);
    }

    @Override
    public int checkPermission(String permName, String pkgName) {
        if (isGuestPkg(pkgName)) {
            int res = base.checkPermission(permName, pkgName);
            if (res == PERMISSION_GRANTED) return res;
            return base.checkPermission(permName, hostPackage);
        }
        return base.checkPermission(permName, pkgName);
    }

    @Override
    public Drawable getDrawable(String packageName, int resid, ApplicationInfo appInfo) {
        if (isVirtualGuestOrContainerPkg(packageName) || appInfo == guestAppInfo) {
            try {
                return guestContext.getResources().getDrawable(resid, guestContext.getTheme());
            } catch (Throwable ignored) {
            }
        }
        return base.getDrawable(packageName, resid, appInfo);
    }

    @Override
    public CharSequence getText(String packageName, int resid, ApplicationInfo appInfo) {
        if (isVirtualGuestOrContainerPkg(packageName) || appInfo == guestAppInfo) {
            try {
                return guestContext.getResources().getText(resid);
            } catch (Throwable ignored) {
            }
        }
        return base.getText(packageName, resid, appInfo);
    }

    @Override
    public XmlResourceParser getXml(String packageName, int resid, ApplicationInfo appInfo) {
        if (isVirtualGuestOrContainerPkg(packageName) || appInfo == guestAppInfo) {
            try {
                return guestContext.getResources().getXml(resid);
            } catch (Throwable ignored) {
            }
        }
        return base.getXml(packageName, resid, appInfo);
    }

    @Override
    public CharSequence getApplicationLabel(ApplicationInfo info) {
        if (info != null && isGuestPkg(info.packageName) && info.labelRes != 0) {
            try {
                return guestContext.getResources().getText(info.labelRes);
            } catch (Throwable ignored) {
            }
        }
        return base.getApplicationLabel(info);
    }

    @Override
    public String getInstallerPackageName(String packageName) {
        try {
            return base.getInstallerPackageName(packageName);
        } catch (Throwable t) {
            return isGuestPkg(packageName) ? "com.android.vending" : null;
        }
    }

    @RequiresApi(30)
    @Override
    public InstallSourceInfo getInstallSourceInfo(String packageName) throws NameNotFoundException {
        try {
            return base.getInstallSourceInfo(packageName);
        } catch (NameNotFoundException nnfe) {
            if (isGuestPkg(packageName)) {
                return base.getInstallSourceInfo(hostPackage);
            }
            throw nnfe;
        }
    }

    @Override
    public int[] getPackageGids(String packageName) throws NameNotFoundException {
        return base.getPackageGids(isGuestPkg(packageName) ? hostPackage : packageName);
    }

    @Override
    public int[] getPackageGids(String packageName, int flags) throws NameNotFoundException {
        return base.getPackageGids(isGuestPkg(packageName) ? hostPackage : packageName, flags);
    }

    @Override
    public int getPackageUid(String packageName, int flags) throws NameNotFoundException {
        try {
            return base.getPackageUid(packageName, flags);
        } catch (NameNotFoundException e) {
            if (isGuestPkg(packageName)) return android.os.Process.myUid();
            throw e;
        }
    }

    @RequiresApi(33)
    @Override
    public int getPackageUid(String packageName, PackageInfoFlags flags) throws NameNotFoundException {
        try {
            return base.getPackageUid(packageName, flags);
        } catch (NameNotFoundException e) {
            if (isGuestPkg(packageName)) return android.os.Process.myUid();
            throw e;
        }
    }

    // ---- Pure delegation for remaining PackageManager methods ----

    @Override public PermissionInfo getPermissionInfo(String name, int flags) throws NameNotFoundException { return base.getPermissionInfo(name, flags); }
    @Override public List<PermissionInfo> queryPermissionsByGroup(String group, int flags) throws NameNotFoundException { return base.queryPermissionsByGroup(group, flags); }
    @Override public PermissionGroupInfo getPermissionGroupInfo(String name, int flags) throws NameNotFoundException { return base.getPermissionGroupInfo(name, flags); }
    @Override public List<PermissionGroupInfo> getAllPermissionGroups(int flags) { return base.getAllPermissionGroups(flags); }
    @Override public ActivityInfo getReceiverInfo(ComponentName component, int flags) throws NameNotFoundException { return base.getReceiverInfo(component, flags); }
    @RequiresApi(33) @Override public ActivityInfo getReceiverInfo(ComponentName component, ComponentInfoFlags flags) throws NameNotFoundException { return base.getReceiverInfo(component, flags); }
    @Override public ServiceInfo getServiceInfo(ComponentName component, int flags) throws NameNotFoundException { return base.getServiceInfo(component, flags); }
    @RequiresApi(33) @Override public ServiceInfo getServiceInfo(ComponentName component, ComponentInfoFlags flags) throws NameNotFoundException { return base.getServiceInfo(component, flags); }
    @Override public List<PackageInfo> getInstalledPackages(int flags) { return base.getInstalledPackages(flags); }
    @RequiresApi(33) @Override public List<PackageInfo> getInstalledPackages(PackageInfoFlags flags) { return base.getInstalledPackages(flags); }
    @Override public List<PackageInfo> getPackagesHoldingPermissions(String[] permissions, int flags) { return base.getPackagesHoldingPermissions(permissions, flags); }
    @Override public boolean isPermissionRevokedByPolicy(String permName, String pkgName) { return base.isPermissionRevokedByPolicy(permName, pkgName); }
    @Override public boolean addPermission(PermissionInfo info) { return base.addPermission(info); }
    @Override public boolean addPermissionAsync(PermissionInfo info) { return base.addPermissionAsync(info); }
    @Override public void removePermission(String name) { base.removePermission(name); }
    @Override public int checkSignatures(String pkg1, String pkg2) { return base.checkSignatures(pkg1, pkg2); }
    @Override public int checkSignatures(int uid1, int uid2) { return base.checkSignatures(uid1, uid2); }
    @Override public String[] getPackagesForUid(int uid) { return base.getPackagesForUid(uid); }
    @Override public String getNameForUid(int uid) { return base.getNameForUid(uid); }
    @Override public List<ApplicationInfo> getInstalledApplications(int flags) { return base.getInstalledApplications(flags); }
    @RequiresApi(33) @Override public List<ApplicationInfo> getInstalledApplications(ApplicationInfoFlags flags) { return base.getInstalledApplications(flags); }
    @Override public boolean isInstantApp() { return base.isInstantApp(); }
    @Override public boolean isInstantApp(String packageName) { return base.isInstantApp(packageName); }
    @Override public int getInstantAppCookieMaxBytes() { return base.getInstantAppCookieMaxBytes(); }
    @Override public byte[] getInstantAppCookie() { return base.getInstantAppCookie(); }
    @Override public void clearInstantAppCookie() { base.clearInstantAppCookie(); }
    @Override public void updateInstantAppCookie(byte[] cookie) { base.updateInstantAppCookie(cookie); }
    @Override public String[] getSystemSharedLibraryNames() { return base.getSystemSharedLibraryNames(); }
    @Override public List<SharedLibraryInfo> getSharedLibraries(int flags) { return base.getSharedLibraries(flags); }
    @Override public ChangedPackages getChangedPackages(int sequenceNumber) { return base.getChangedPackages(sequenceNumber); }
    @Override public FeatureInfo[] getSystemAvailableFeatures() { return base.getSystemAvailableFeatures(); }
    @Override public boolean hasSystemFeature(String name) { return base.hasSystemFeature(name); }
    @Override public boolean hasSystemFeature(String name, int version) { return base.hasSystemFeature(name, version); }
    @Override public ResolveInfo resolveActivity(Intent intent, int flags) { return base.resolveActivity(intent, flags); }
    @RequiresApi(33) @Override public ResolveInfo resolveActivity(Intent intent, ResolveInfoFlags flags) { return base.resolveActivity(intent, flags); }
    @Override public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) { return base.queryIntentActivities(intent, flags); }
    @RequiresApi(33) @Override public List<ResolveInfo> queryIntentActivities(Intent intent, ResolveInfoFlags flags) { return base.queryIntentActivities(intent, flags); }
    @Override public List<ResolveInfo> queryIntentActivityOptions(ComponentName caller, Intent[] specifics, Intent intent, int flags) { return base.queryIntentActivityOptions(caller, specifics, intent, flags); }
    @Override public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) { return base.queryBroadcastReceivers(intent, flags); }
    @Override public ResolveInfo resolveService(Intent intent, int flags) { return base.resolveService(intent, flags); }
    @Override public List<ResolveInfo> queryIntentServices(Intent intent, int flags) { return base.queryIntentServices(intent, flags); }
    @Override public List<ResolveInfo> queryIntentContentProviders(Intent intent, int flags) { return base.queryIntentContentProviders(intent, flags); }
    @Override public InstrumentationInfo getInstrumentationInfo(ComponentName className, int flags) throws NameNotFoundException { return base.getInstrumentationInfo(className, flags); }
    @Override public List<InstrumentationInfo> queryInstrumentation(String targetPackage, int flags) { return base.queryInstrumentation(targetPackage, flags); }
    @Override public Drawable getActivityIcon(ComponentName activityName) throws NameNotFoundException { return base.getActivityIcon(activityName); }
    @Override public Drawable getActivityIcon(Intent intent) throws NameNotFoundException { return base.getActivityIcon(intent); }
    @Override public Drawable getActivityBanner(ComponentName activityName) throws NameNotFoundException { return base.getActivityBanner(activityName); }
    @Override public Drawable getActivityBanner(Intent intent) throws NameNotFoundException { return base.getActivityBanner(intent); }
    @Override public Drawable getDefaultActivityIcon() { return base.getDefaultActivityIcon(); }
    @Override public Drawable getApplicationIcon(ApplicationInfo info) { return base.getApplicationIcon(info); }
    @Override public Drawable getApplicationIcon(String packageName) throws NameNotFoundException { return base.getApplicationIcon(packageName); }
    @Override public Drawable getApplicationBanner(ApplicationInfo info) { return base.getApplicationBanner(info); }
    @Override public Drawable getApplicationBanner(String packageName) throws NameNotFoundException { return base.getApplicationBanner(packageName); }
    @Override public Drawable getActivityLogo(ComponentName activityName) throws NameNotFoundException { return base.getActivityLogo(activityName); }
    @Override public Drawable getActivityLogo(Intent intent) throws NameNotFoundException { return base.getActivityLogo(intent); }
    @Override public Drawable getApplicationLogo(ApplicationInfo info) { return base.getApplicationLogo(info); }
    @Override public Drawable getApplicationLogo(String packageName) throws NameNotFoundException { return base.getApplicationLogo(packageName); }
    @Override public Drawable getUserBadgedIcon(Drawable icon, UserHandle user) { return base.getUserBadgedIcon(icon, user); }
    @Override public Drawable getUserBadgedDrawableForDensity(Drawable drawable, UserHandle user, Rect badgeLocation, int badgeDensity) { return base.getUserBadgedDrawableForDensity(drawable, user, badgeLocation, badgeDensity); }
    @Override public CharSequence getUserBadgedLabel(CharSequence label, UserHandle user) { return base.getUserBadgedLabel(label, user); }
    @Override public void verifyPendingInstall(int id, int verificationCode) { base.verifyPendingInstall(id, verificationCode); }
    @Override public void extendVerificationTimeout(int id, int verificationCodeAtTimeout, long millisecondsAfterDelay) { base.extendVerificationTimeout(id, verificationCodeAtTimeout, millisecondsAfterDelay); }
    @Override public void setInstallerPackageName(String targetPackage, String installerPackageName) { base.setInstallerPackageName(targetPackage, installerPackageName); }
    @Override public void addPackageToPreferred(String packageName) { base.addPackageToPreferred(packageName); }
    @Override public void removePackageFromPreferred(String packageName) { base.removePackageFromPreferred(packageName); }
    @Override public List<PackageInfo> getPreferredPackages(int flags) { return base.getPreferredPackages(flags); }
    @Override public void addPreferredActivity(IntentFilter filter, int match, ComponentName[] set, ComponentName activity) { base.addPreferredActivity(filter, match, set, activity); }
    @Override public void clearPackagePreferredActivities(String packageName) { base.clearPackagePreferredActivities(packageName); }
    @Override public int getPreferredActivities(List<IntentFilter> outFilters, List<ComponentName> outActivities, String packageName) { return base.getPreferredActivities(outFilters, outActivities, packageName); }
    @Override public void setComponentEnabledSetting(ComponentName componentName, int newState, int flags) { base.setComponentEnabledSetting(componentName, newState, flags); }
    @Override public int getComponentEnabledSetting(ComponentName componentName) { return base.getComponentEnabledSetting(componentName); }
    @Override public void setApplicationEnabledSetting(String packageName, int newState, int flags) { base.setApplicationEnabledSetting(packageName, newState, flags); }
    @Override public int getApplicationEnabledSetting(String packageName) { return base.getApplicationEnabledSetting(packageName); }
    @Override public boolean isSafeMode() { return base.isSafeMode(); }
    @Override public void setApplicationCategoryHint(String packageName, int categoryHint) { base.setApplicationCategoryHint(packageName, categoryHint); }
    @Override public PackageInstaller getPackageInstaller() { return base.getPackageInstaller(); }
    @Override public boolean canRequestPackageInstalls() { return base.canRequestPackageInstalls(); }
    @Override public Intent getLeanbackLaunchIntentForPackage(String packageName) { return base.getLeanbackLaunchIntentForPackage(packageName); }
    @Override public String[] canonicalToCurrentPackageNames(String[] names) { return base.canonicalToCurrentPackageNames(names); }
    @Override public String[] currentToCanonicalPackageNames(String[] names) { return base.currentToCanonicalPackageNames(names); }
}

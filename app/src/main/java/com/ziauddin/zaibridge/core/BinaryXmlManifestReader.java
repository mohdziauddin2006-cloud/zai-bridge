package com.ziauddin.zaibridge.core;

import android.content.res.AssetManager;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight binary {@code AndroidManifest.xml} inspector using the public
 * {@link XmlResourceParser} API.
 *
 * <p>Framework {@code PackageManager.getPackageArchiveInfo()} can omit
 * {@code <activity-alias>} target mappings, activity metadata on split APKs, or
 * merged application metadata. Reading the APK's binary manifest directly ensures
 * Unity ({@code unity.build-id}, {@code unity.splash-mode},
 * {@code unityplayer.UnityActivity}) and GameActivity ({@code android.app.lib_name})
 * always see accurate metadata and resolved activity class names.
 */
public final class BinaryXmlManifestReader {

    private static final String TAG = "ZaiBridgeManifest";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    public static final class ParsedManifest {
        public String packageName = "";
        public String applicationClass = "";
        public String launcherActivity = "";
        public final Bundle applicationMetaData = new Bundle();
        public final Map<String, Bundle> activityMetaData = new HashMap<>();
        public final Map<String, String> aliasTargets = new HashMap<>();

        public String resolveActivityTarget(String name) {
            if (name == null || name.isEmpty()) return "";
            String normalized = normalizeClassName(packageName, name);
            String target = aliasTargets.get(normalized);
            if (target != null && !target.isEmpty()) {
                return target;
            }
            return normalized;
        }

        public Bundle buildMergedMetaData(String requestedComponentClass) {
            Bundle merged = new Bundle();
            merged.putAll(applicationMetaData);
            if (requestedComponentClass != null && !requestedComponentClass.isEmpty()) {
                String normalized = normalizeClassName(packageName, requestedComponentClass);
                String target = aliasTargets.get(normalized);
                if (target != null) {
                    Bundle targetMeta = activityMetaData.get(target);
                    if (targetMeta != null) merged.putAll(targetMeta);
                }
                Bundle exactMeta = activityMetaData.get(normalized);
                if (exactMeta != null) merged.putAll(exactMeta);
            }
            if (launcherActivity != null && !launcherActivity.isEmpty()) {
                String resolved = resolveActivityTarget(launcherActivity);
                Bundle launcherMeta = activityMetaData.get(resolved);
                if (launcherMeta != null && !launcherMeta.isEmpty()) {
                    for (String k : launcherMeta.keySet()) {
                        if (!merged.containsKey(k)) {
                            Object v = launcherMeta.get(k);
                            putObject(merged, k, v);
                        }
                    }
                }
            }
            return merged;
        }
    }

    private BinaryXmlManifestReader() {}

    public static ParsedManifest readFromApk(String apkPath) {
        ParsedManifest out = new ParsedManifest();
        if (apkPath == null || apkPath.isEmpty()) return out;

        AssetManager am = null;
        XmlResourceParser parser = null;
        try {
            am = AssetManager.class.getDeclaredConstructor().newInstance();
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            Object res = addAssetPath.invoke(am, apkPath);
            int cookie = (res instanceof Integer) ? (Integer) res : 0;
            if (cookie <= 0) return out;
            parser = am.openXmlResourceParser(cookie, "AndroidManifest.xml");
            parse(parser, out);
        } catch (Throwable t) {
            Log.w(TAG, "failed reading binary manifest from " + apkPath + ": " + t.getMessage());
        } finally {
            if (parser != null) {
                try {
                    parser.close();
                } catch (Throwable ignored) {
                }
            }
            if (am != null) {
                try {
                    am.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static void parse(XmlResourceParser parser, ParsedManifest out) throws Exception {
        String currentComponent = null;
        boolean inApplication = false;
        boolean inActivity = false;
        boolean inIntentFilter = false;
        boolean hasMainAction = false;
        boolean hasLauncherCategory = false;

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if ("manifest".equals(tag)) {
                    String pkg = getAttr(parser, "package");
                    if (pkg != null) out.packageName = pkg;
                } else if ("application".equals(tag)) {
                    inApplication = true;
                    String appName = getAttr(parser, "name");
                    if (appName != null && !appName.isEmpty()) {
                        out.applicationClass = normalizeClassName(out.packageName, appName);
                    }
                } else if ("activity".equals(tag) || "activity-alias".equals(tag)) {
                    inActivity = true;
                    hasMainAction = false;
                    hasLauncherCategory = false;
                    String rawName = getAttr(parser, "name");
                    currentComponent = normalizeClassName(out.packageName, rawName);
                    if ("activity-alias".equals(tag)) {
                        String rawTarget = getAttr(parser, "targetActivity");
                        String target = normalizeClassName(out.packageName, rawTarget);
                        if (!currentComponent.isEmpty() && !target.isEmpty()) {
                            out.aliasTargets.put(currentComponent, target);
                        }
                    }
                    if (!currentComponent.isEmpty() && out.launcherActivity.isEmpty()) {
                        // provisional first activity fallback; upgraded below if MAIN+LAUNCHER matches
                        out.launcherActivity = currentComponent;
                    }
                } else if ("intent-filter".equals(tag) && inActivity) {
                    inIntentFilter = true;
                    hasMainAction = false;
                    hasLauncherCategory = false;
                } else if ("action".equals(tag) && inIntentFilter) {
                    String act = getAttr(parser, "name");
                    if ("android.intent.action.MAIN".equals(act)) {
                        hasMainAction = true;
                    }
                } else if ("category".equals(tag) && inIntentFilter) {
                    String cat = getAttr(parser, "name");
                    if ("android.intent.category.LAUNCHER".equals(cat)
                            || "android.intent.category.LEANBACK_LAUNCHER".equals(cat)) {
                        hasLauncherCategory = true;
                    }
                } else if ("meta-data".equals(tag)) {
                    String key = getAttr(parser, "name");
                    if (key != null && !key.isEmpty()) {
                        Bundle targetBundle = null;
                        if (inActivity && currentComponent != null && !currentComponent.isEmpty()) {
                            targetBundle = out.activityMetaData.get(currentComponent);
                            if (targetBundle == null) {
                                targetBundle = new Bundle();
                                out.activityMetaData.put(currentComponent, targetBundle);
                            }
                        } else if (inApplication) {
                            targetBundle = out.applicationMetaData;
                        }
                        if (targetBundle != null) {
                            readMetaDataValue(parser, key, targetBundle);
                        }
                    }
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                String tag = parser.getName();
                if ("intent-filter".equals(tag)) {
                    if (inIntentFilter && inActivity && hasMainAction && hasLauncherCategory
                            && currentComponent != null && !currentComponent.isEmpty()) {
                        out.launcherActivity = out.resolveActivityTarget(currentComponent);
                    }
                    inIntentFilter = false;
                } else if ("activity".equals(tag) || "activity-alias".equals(tag)) {
                    inActivity = false;
                    currentComponent = null;
                } else if ("application".equals(tag)) {
                    inApplication = false;
                }
            }
            eventType = parser.next();
        }
    }

    private static String getAttr(XmlResourceParser parser, String name) {
        String v = parser.getAttributeValue(ANDROID_NS, name);
        if (v != null) return v;
        v = parser.getAttributeValue(null, name);
        if (v != null) return v;
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            if (name.equals(parser.getAttributeName(i))) {
                return parser.getAttributeValue(i);
            }
        }
        return null;
    }

    private static void readMetaDataValue(XmlResourceParser parser, String key, Bundle dst) {
        int valIdx = -1;
        int resIdx = -1;
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            String attr = parser.getAttributeName(i);
            if ("value".equals(attr)) {
                valIdx = i;
            } else if ("resource".equals(attr)) {
                resIdx = i;
            }
        }
        if (resIdx >= 0) {
            int resId = parser.getAttributeResourceValue(resIdx, 0);
            if (resId != 0) {
                dst.putInt(key, resId);
                return;
            }
        }
        if (valIdx < 0) return;

        String raw = parser.getAttributeValue(valIdx);
        if ("true".equals(raw) || "false".equals(raw)) {
            dst.putBoolean(key, Boolean.parseBoolean(raw));
            return;
        }
        int resId = parser.getAttributeResourceValue(valIdx, 0);
        if (resId != 0) {
            dst.putInt(key, resId);
            return;
        }
        if (raw != null) {
            if (raw.startsWith("0x") || raw.matches("-?\\d+")) {
                try {
                    long parsed = raw.startsWith("0x")
                            ? Long.parseLong(raw.substring(2), 16)
                            : Long.parseLong(raw);
                    if (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
                        dst.putInt(key, (int) parsed);
                        return;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            if (raw.matches("-?\\d+\\.\\d+")) {
                try {
                    dst.putFloat(key, Float.parseFloat(raw));
                    return;
                } catch (NumberFormatException ignored) {
                }
            }
            dst.putString(key, raw);
        }
    }

    public static String normalizeClassName(String pkg, String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        if (pkg == null || pkg.isEmpty()) return trimmed;
        if (trimmed.startsWith(".")) {
            return pkg + trimmed;
        }
        if (!trimmed.contains(".")) {
            return pkg + "." + trimmed;
        }
        return trimmed;
    }

    private static void putObject(Bundle b, String key, Object val) {
        if (val instanceof String) b.putString(key, (String) val);
        else if (val instanceof Integer) b.putInt(key, (Integer) val);
        else if (val instanceof Boolean) b.putBoolean(key, (Boolean) val);
        else if (val instanceof Float) b.putFloat(key, (Float) val);
        else if (val instanceof Long) b.putLong(key, (Long) val);
    }
}

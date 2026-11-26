package com.ziauddin.zaibridge.core;

import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;

import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Binary {@code AndroidManifest.xml} inspector with full resource-reference resolution.
 *
 * <p>Unlike naive parsers that store raw {@code 0x7F......} AXML resource IDs into
 * {@code metaData}, this reader resolves every {@code TYPE_REFERENCE} attribute value
 * (such as {@code @integer/google_play_services_version}) against the APK's merged
 * {@link Resources} table so SDKs receive genuine primitives/strings instead of raw IDs.
 */
public final class BinaryXmlManifestReader {

    private static final String TAG = "ZaiBridgeManifest";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    public static final class ProviderEntry {
        public String name = "";
        public String authorities = "";
        public int initOrder = 0;
        public boolean enabled = true;
        public boolean exported = false;
        public final Bundle metaData = new Bundle();
    }

    public static final class ParsedManifest {
        public String packageName = "";
        public String applicationClass = "";
        public String launcherActivity = "";
        public final Bundle applicationMetaData = new Bundle();
        public final Map<String, Bundle> activityMetaData = new HashMap<>();
        public final Map<String, String> aliasTargets = new HashMap<>();
        public final List<ProviderEntry> providers = new ArrayList<>();

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
        return readFromApk(apkPath, null, null);
    }

    public static ParsedManifest readFromApk(
            String apkPath,
            @Nullable List<String> splitPaths,
            @Nullable Resources externalResources) {
        ParsedManifest out = new ParsedManifest();
        if (apkPath == null || apkPath.isEmpty()) return out;

        AssetManager am = null;
        XmlResourceParser parser = null;
        try {
            am = AssetManager.class.getDeclaredConstructor().newInstance();
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            Object res = addAssetPath.invoke(am, apkPath);
            int baseCookie = (res instanceof Integer) ? (Integer) res : 0;
            if (baseCookie <= 0) return out;

            if (splitPaths != null) {
                for (String split : splitPaths) {
                    if (split != null && !split.isEmpty()) {
                        try {
                            addAssetPath.invoke(am, split);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            Resources resolverRes = externalResources;
            if (resolverRes == null) {
                DisplayMetrics dm = new DisplayMetrics();
                dm.setToDefaults();
                Configuration cfg = new Configuration();
                cfg.setToDefaults();
                resolverRes = new Resources(am, dm, cfg);
            }

            parser = am.openXmlResourceParser(baseCookie, "AndroidManifest.xml");
            parse(parser, out, resolverRes);
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

    private static void parse(XmlResourceParser parser, ParsedManifest out, @Nullable Resources resolverRes)
            throws Exception {
        String currentComponent = null;
        ProviderEntry currentProvider = null;
        boolean inApplication = false;
        boolean inActivity = false;
        boolean inProvider = false;
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
                } else if ("provider".equals(tag)) {
                    inProvider = true;
                    currentProvider = new ProviderEntry();
                    currentProvider.name = normalizeClassName(out.packageName, getAttr(parser, "name"));
                    String auth = getAttr(parser, "authorities");
                    if (auth != null) currentProvider.authorities = auth;
                    String enabledAttr = getAttr(parser, "enabled");
                    currentProvider.enabled = !"false".equals(enabledAttr);
                    String exportedAttr = getAttr(parser, "exported");
                    currentProvider.exported = "true".equals(exportedAttr);
                    String orderAttr = getAttr(parser, "initOrder");
                    if (orderAttr != null && !orderAttr.isEmpty()) {
                        try {
                            currentProvider.initOrder = Integer.parseInt(orderAttr);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (!currentProvider.name.isEmpty()) {
                        out.providers.add(currentProvider);
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
                        if (inProvider && currentProvider != null) {
                            targetBundle = currentProvider.metaData;
                        } else if (inActivity && currentComponent != null && !currentComponent.isEmpty()) {
                            targetBundle = out.activityMetaData.get(currentComponent);
                            if (targetBundle == null) {
                                targetBundle = new Bundle();
                                out.activityMetaData.put(currentComponent, targetBundle);
                            }
                        } else if (inApplication) {
                            targetBundle = out.applicationMetaData;
                        }
                        if (targetBundle != null) {
                            readMetaDataValue(parser, key, targetBundle, resolverRes);
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
                } else if ("provider".equals(tag)) {
                    inProvider = false;
                    currentProvider = null;
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

    private static void readMetaDataValue(
            XmlResourceParser parser, String key, Bundle dst, @Nullable Resources resolverRes) {
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

        // If the binary XML attribute is a resource reference (@integer/..., @string/..., etc.),
        // resolve it against Resources instead of storing the raw 0x7F...... resource ID.
        int resId = parser.getAttributeResourceValue(valIdx, 0);
        if (resId != 0) {
            resolveReferenceValue(resolverRes, resId, key, dst);
            return;
        }

        String raw = parser.getAttributeValue(valIdx);
        if (raw == null) return;
        if ("true".equals(raw) || "false".equals(raw)) {
            dst.putBoolean(key, Boolean.parseBoolean(raw));
            return;
        }
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

    /**
     * Resolves an AXML {@code TYPE_REFERENCE} resource ID against {@code res} and writes
     * the actual primitive/String value into {@code dst}. Never leaks raw {@code 0x7F......} IDs.
     */
    public static boolean resolveReferenceValue(
            @Nullable Resources res, int resId, String key, Bundle dst) {
        if (res == null || resId == 0 || key == null || dst == null) return false;
        try {
            TypedValue tv = new TypedValue();
            res.getValue(resId, tv, true);
            if (tv.type == TypedValue.TYPE_INT_BOOLEAN) {
                dst.putBoolean(key, tv.data != 0);
                return true;
            }
            if (tv.type >= TypedValue.TYPE_FIRST_INT && tv.type <= TypedValue.TYPE_LAST_INT) {
                dst.putInt(key, tv.data);
                return true;
            }
            if (tv.type == TypedValue.TYPE_FLOAT) {
                dst.putFloat(key, tv.getFloat());
                return true;
            }
            if (tv.type == TypedValue.TYPE_STRING) {
                CharSequence cs = tv.string != null ? tv.string : res.getText(resId);
                if (cs != null) {
                    dst.putString(key, cs.toString());
                    return true;
                }
            }
            try {
                String strVal = res.getString(resId);
                if (strVal != null) {
                    dst.putString(key, strVal);
                    return true;
                }
            } catch (Throwable ignored) {
            }
            try {
                dst.putInt(key, res.getInteger(resId));
                return true;
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Log.w(TAG, "unresolved meta-data ref 0x" + Integer.toHexString(resId)
                    + " for " + key + ": " + t.getMessage());
        }
        return false;
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

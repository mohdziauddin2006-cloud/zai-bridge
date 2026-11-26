package com.ziauddin.zaibridge.core;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Runtime compatibility hooks installed inside the isolated {@code :guest} process so
 * legacy Android games (targeting SDK &lt; 31) and container binder calls work cleanly on
 * Android 8 through Android 16+.
 *
 * <p>Handles two OS-level container checks:
 * <ol>
 *   <li>Disables client-side {@code PENDING_INTENT_EXPLICIT_MUTABILITY_REQUIRED}
 *       ({@code 160794467L}) enforcement via {@code android.compat.Compatibility.sCallbacks}
 *       when the running guest targets below API 31.</li>
 *   <li>Rewrites {@code IActivityManager.getIntentSender*} binder calls so
 *       {@code callingPackage} uses the host UID package and {@code FLAG_IMMUTABLE} is
 *       supplied whenever a legacy guest omits explicit mutability flags.</li>
 * </ol>
 */
public final class GuestCompatHooks {

    private static final String TAG = "ZaiBridgeCompat";

    /**
     * AOSP {@code PendingIntent.PENDING_INTENT_EXPLICIT_MUTABILITY_REQUIRED} change ID
     * introduced in Android 12 (API 31).
     */
    public static final long CHANGE_PENDING_INTENT_EXPLICIT_MUTABILITY_REQUIRED = 160794467L;

    /** Value of {@code PendingIntent.FLAG_MUTABLE} (API 31+) safe to reference on minSdk 26. */
    public static final int FLAG_MUTABLE_COMPAT = 0x02000000;

    /** Value of {@code PendingIntent.FLAG_IMMUTABLE} (API 23+). */
    public static final int FLAG_IMMUTABLE_COMPAT = PendingIntent.FLAG_IMMUTABLE;

    private static final Object LOCK = new Object();

    private static volatile String sGuestPackage;
    private static volatile String sHostPackage;
    private static volatile int sGuestTargetSdk = 0;

    private static Object sOriginalCompatDelegate;
    private static Object sInstalledCompatProxy;

    private static Object sOriginalActivityManager;
    private static Object sInstalledActivityManagerProxy;

    private GuestCompatHooks() {
    }

    /**
     * Installs idempotent {@code :guest}-process compatibility hooks. Safe to invoke on any
     * Android version from API 26 to 36+.
     */
    public static void installForGuest(
            Context context, String guestPackage, String hostPackage, int guestTargetSdk) {
        if (context != null && !BridgeApplication.isGuestProcess(context)) {
            return;
        }
        synchronized (LOCK) {
            if (guestPackage != null && !guestPackage.isEmpty()) {
                sGuestPackage = guestPackage;
            }
            if (hostPackage != null && !hostPackage.isEmpty()) {
                sHostPackage = hostPackage;
            }
            if (guestTargetSdk > 0) {
                sGuestTargetSdk = guestTargetSdk;
            }

            try {
                HiddenApi.exemptAll();
            } catch (Throwable ignored) {
            }

            installCompatDelegateHookLocked();
            installActivityManagerProxyLocked();
        }
    }

    /** Pure decision helper for {@code Compatibility.BehaviorChangeDelegate.isChangeEnabled}. */
    public static boolean shouldOverrideCompatChange(
            long changeId, int guestTargetSdk, boolean delegateEnabled) {
        if (changeId == CHANGE_PENDING_INTENT_EXPLICIT_MUTABILITY_REQUIRED
                && guestTargetSdk > 0 && guestTargetSdk < 31) {
            return false;
        }
        return delegateEnabled;
    }

    /**
     * Rewrites {@code IActivityManager.getIntentSender*} arguments in place so legacy
     * {@code PendingIntent} calls satisfy both calling-UID ownership and API 31+ mutability rules.
     *
     * @return {@code true} if any argument was adjusted.
     */
    public static boolean rewriteIntentSenderArgs(
            Object[] args, Class<?>[] paramTypes, String guestPackage, String hostPackage) {
        if (args == null || args.length < 2) {
            return false;
        }
        boolean modified = false;

        if (guestPackage != null && !guestPackage.isEmpty()
                && hostPackage != null && !hostPackage.isEmpty()
                && guestPackage.equals(args[1])) {
            args[1] = hostPackage;
            modified = true;
        }

        int flagsIndex = findIntentSenderFlagsIndex(args, paramTypes);
        if (flagsIndex >= 0 && args[flagsIndex] instanceof Integer) {
            int flags = (Integer) args[flagsIndex];
            if ((flags & (FLAG_IMMUTABLE_COMPAT | FLAG_MUTABLE_COMPAT)) == 0) {
                args[flagsIndex] = flags | FLAG_IMMUTABLE_COMPAT;
                modified = true;
            }
        }
        return modified;
    }

    private static int findIntentSenderFlagsIndex(Object[] args, Class<?>[] paramTypes) {
        if (paramTypes != null && paramTypes.length == args.length) {
            for (int i = 0; i + 1 < paramTypes.length; i++) {
                if (paramTypes[i] == String[].class && paramTypes[i + 1] == int.class) {
                    return i + 1;
                }
            }
        }
        if (args.length >= 3 && args[args.length - 1] instanceof Integer
                && (args[args.length - 2] == null || args[args.length - 2] instanceof Bundle)
                && args[args.length - 3] instanceof Integer) {
            return args.length - 3;
        }
        return -1;
    }

    private static void installCompatDelegateHookLocked() {
        try {
            Class<?> compatCls = Class.forName("android.compat.Compatibility");
            Class<?> delegateIface = Class.forName("android.compat.Compatibility$BehaviorChangeDelegate");

            Field sCallbacksField = compatCls.getDeclaredField("sCallbacks");
            sCallbacksField.setAccessible(true);
            Object current = sCallbacksField.get(null);

            if (current != null && current == sInstalledCompatProxy) {
                return;
            }
            if (current == null) {
                try {
                    Method getter = compatCls.getDeclaredMethod("getBehaviorChangeDelegate");
                    getter.setAccessible(true);
                    current = getter.invoke(null);
                } catch (Throwable ignored) {
                }
            }
            if (current != sInstalledCompatProxy) {
                sOriginalCompatDelegate = current;
            }

            final Object origDelegate = sOriginalCompatDelegate;
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if ("isChangeEnabled".equals(name) && args != null && args.length == 1
                        && args[0] instanceof Long) {
                    long changeId = (Long) args[0];
                    if (changeId == CHANGE_PENDING_INTENT_EXPLICIT_MUTABILITY_REQUIRED
                            && sGuestTargetSdk > 0 && sGuestTargetSdk < 31) {
                        return false;
                    }
                    boolean baseEnabled = false;
                    if (origDelegate != null) {
                        try {
                            Object res = method.invoke(origDelegate, args);
                            baseEnabled = res instanceof Boolean && (Boolean) res;
                        } catch (InvocationTargetException ite) {
                            throw ite.getTargetException();
                        }
                    }
                    return shouldOverrideCompatChange(changeId, sGuestTargetSdk, baseEnabled);
                }
                if ("onChangeReported".equals(name) && origDelegate == null) {
                    return null;
                }
                if (origDelegate != null) {
                    try {
                        return method.invoke(origDelegate, args);
                    } catch (InvocationTargetException ite) {
                        throw ite.getTargetException();
                    }
                }
                return defaultValue(method.getReturnType());
            };

            Object proxy = Proxy.newProxyInstance(
                    delegateIface.getClassLoader(),
                    new Class<?>[]{delegateIface},
                    handler);
            sCallbacksField.set(null, proxy);
            sInstalledCompatProxy = proxy;
            Log.i(TAG, "Compatibility BehaviorChangeDelegate hook armed (targetSdk=" + sGuestTargetSdk + ")");
        } catch (ClassNotFoundException ignored) {
            // Android < 11 does not have android.compat.Compatibility or explicit PendingIntent mutability checks.
        } catch (Throwable t) {
            Log.w(TAG, "Compatibility delegate hook unavailable: " + t.getMessage());
        }
    }

    private static void installActivityManagerProxyLocked() {
        try {
            Field singletonField = ActivityManager.class.getDeclaredField("IActivityManagerSingleton");
            singletonField.setAccessible(true);
            Object singleton = singletonField.get(null);
            if (singleton == null) return;

            Method getMethod = singleton.getClass().getMethod("get");
            Object currentService = getMethod.invoke(singleton);
            if (currentService == null) return;
            if (currentService == sInstalledActivityManagerProxy) {
                return;
            }
            sOriginalActivityManager = currentService;

            Class<?> iAmClass = Class.forName("android.app.IActivityManager");
            final Object origAm = sOriginalActivityManager;
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if (("getIntentSender".equals(name)
                        || "getIntentSenderWithFeature".equals(name)
                        || "getIntentSenderWithSourceToken".equals(name))
                        && args != null) {
                    boolean changed = rewriteIntentSenderArgs(
                            args, method.getParameterTypes(), sGuestPackage, sHostPackage);
                    if (changed) {
                        Log.d(TAG, "Rewrote " + name + " container package + PendingIntent mutability flag");
                    }
                }
                try {
                    return method.invoke(origAm, args);
                } catch (InvocationTargetException ite) {
                    throw ite.getTargetException();
                }
            };

            Object proxy = Proxy.newProxyInstance(
                    iAmClass.getClassLoader(),
                    new Class<?>[]{iAmClass},
                    handler);

            Field instanceField = null;
            for (Class<?> c = singleton.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    instanceField = c.getDeclaredField("mInstance");
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (instanceField == null) return;
            instanceField.setAccessible(true);
            instanceField.set(singleton, proxy);
            sInstalledActivityManagerProxy = proxy;
            Log.i(TAG, "IActivityManagerSingleton IntentSender compat proxy armed in :guest");
        } catch (Throwable t) {
            Log.w(TAG, "IActivityManagerSingleton proxy skipped: " + t.getMessage());
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0f;
        if (returnType == double.class) return 0d;
        return null;
    }
}

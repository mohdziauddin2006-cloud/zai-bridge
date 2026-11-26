package com.ziauddin.zaibridge.core;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * Exempts the process from Android's hidden-API blocklist.
 *
 * <p>A translation container has to touch framework internals that are not part
 * of the SDK: {@code ActivityThread}, {@code Instrumentation}, {@code
 * PhoneWindow}, {@code AssetManager.addAssetPath}. Those calls are grey-listed
 * on Android 9 and hard-blocked on 11+, where they fail with
 * {@code NoSuchMethodException} instead of running.
 *
 * <p>{@code VMRuntime.setHiddenApiExemptions(new String[]{"L"})} disables the
 * blocklist for every member whose signature starts with {@code L} — that is,
 * all of them. The call itself is hidden API too, so it is reached with
 * <b>double reflection</b>: {@code Class.getDeclaredMethod} is only available
 * through a {@code Method} handle obtained from {@code Class} itself, which the
 * blocklist never filters because the lookup happens before the exemption.
 */
public final class HiddenApi {

    private static final String TAG = "ZaiBridge";

    private static volatile boolean applied;
    private static volatile boolean reflectionTried;

    private HiddenApi() {
    }

    private static native boolean nativeExemptAll();

    /** Idempotent; retries cleanly via native platform thread once libwrapper loads. */
    public static synchronized boolean exemptAll() {
        if (applied) return true;
        try {
            if (nativeExemptAll()) {
                applied = true;
                Log.i(TAG, "hidden-API exemptions applied (native platform thread)");
                return true;
            }
        } catch (UnsatisfiedLinkError ignored) {
            // Core shared library not yet loaded during early attachBaseContext.
        } catch (Throwable t) {
            Log.w(TAG, "native hidden-API exemption warning: " + t.getMessage());
        }
        if (reflectionTried) return false;
        reflectionTried = true;
        try {
            // Step 1: a Method handle on Class.getDeclaredMethod(String, Class[]).
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            // Step 2: Class.forName through the same unrestricted handle.
            Method forName = (Method) getDeclaredMethod.invoke(
                    Class.class, "forName", new Class[]{String.class});
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            if (vmRuntime == null) return false;

            Method getRuntime = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "getRuntime", new Class[0]);
            Method setExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "setHiddenApiExemptions", new Class[]{String[].class});

            Object runtime = getRuntime.invoke(null);
            setExemptions.invoke(runtime, (Object) new String[]{"L"});
            applied = true;
            Log.i(TAG, "hidden-API exemptions applied (all signatures)");
            return true;
        } catch (Throwable t) {
            // Do not latch `applied = true` on failure so once libwrapper.so loads,
            // nativeExemptAll() unlocks hidden APIs cleanly on Android 14–16+.
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null ? t.getCause() : t;
            Log.w(TAG, "hidden-API reflection unavailable (will retry native): " + cause);
            return false;
        }
    }

    public static boolean isApplied() {
        return applied;
    }
}

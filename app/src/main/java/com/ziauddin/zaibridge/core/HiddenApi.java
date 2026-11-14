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

    private static boolean applied;

    private HiddenApi() {
    }

    /** Idempotent; safe to call from every process start. */
    public static synchronized boolean exemptAll() {
        if (applied) return true;
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
            // Pre-28 releases have no blocklist at all, so this is normal there.
            // On 14+ the call itself can be rejected; the cause says whether the
            // lookup or the invocation was refused.
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null ? t.getCause() : t;
            Log.w(TAG, "hidden-API exemption unavailable: " + cause);
            applied = true;
            return false;
        }
    }

    public static boolean isApplied() {
        return applied;
    }
}

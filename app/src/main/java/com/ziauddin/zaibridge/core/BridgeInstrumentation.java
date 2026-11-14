package com.ziauddin.zaibridge.core;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.ziauddin.zaibridge.proxy.ProxyGameActivity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The Activity virtualization hook.
 *
 * <p>Replaces {@code ActivityThread.mInstrumentation} with this subclass. The
 * framework does all the heavy lifting — it builds the window, decor view,
 * fragment controller, input channel and token in {@code
 * ActivityThread.performLaunchActivity} with this instrument's return value —
 * and the bridge only has to answer two questions:
 *
 * <ul>
 *   <li>{@link #newActivity}: which class should be attached to the proxy's
 *       {@code ActivityClientRecord}? For a bridge launch, the guest Activity
 *       from the game's own {@code DexClassLoader}.</li>
 *   <li>{@link #callActivityOnCreate}: what context should that Activity see
 *       before its {@code onCreate} runs?</li>
 * </ul>
 *
 * <p>Because the guest instance is a normal framework Activity, touch, key,
 * sensor, lifecycle and configuration dispatch all reach it through the real
 * window; nothing is re-implemented here.
 */
public final class BridgeInstrumentation extends Instrumentation {

    private static final String TAG = "ZaiBridge";

    private final Instrumentation delegate;

    /**
     * Activities this hook created from a guest dex. Only these get the guest
     * context and the bridge probing: the launcher's own Activities go through
     * {@link #callActivityOnCreate} too and must keep their real base context.
     */
    private static final java.util.Set<Activity> GUESTS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Activity, Boolean>());

    public BridgeInstrumentation(Instrumentation delegate) {
        this.delegate = delegate;
    }

    private static boolean isGuest(Activity activity) {
        return activity != null && GUESTS.contains(activity);
    }

    /**
     * Installs the hook. Called from {@code Application.attachBaseContext}, i.e.
     * before the launcher Activity is created.
     */
    public static boolean install(Context ctx) {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Method current = threadClass.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            if (thread == null) {
                Log.e(TAG, "instrumentation hook: no ActivityThread yet");
                return false;
            }
            Field field = threadClass.getDeclaredField("mInstrumentation");
            field.setAccessible(true);
            Object existing = field.get(thread);
            if (existing instanceof BridgeInstrumentation) return true;

            BridgeInstrumentation hook = new BridgeInstrumentation(
                    existing instanceof Instrumentation ? (Instrumentation) existing : null);
            field.set(thread, hook);
            Log.i(TAG, "ActivityThread.mInstrumentation -> BridgeInstrumentation "
                    + "(delegate=" + (hook.delegate != null) + ")");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "instrumentation hook failed", t);
            return false;
        }
    }

    public static boolean isInstalled() {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null);
            Field field = threadClass.getDeclaredField("mInstrumentation");
            field.setAccessible(true);
            return field.get(thread) instanceof BridgeInstrumentation;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Guest instantiation
    // ------------------------------------------------------------------

    @Override
    public Activity newActivity(ClassLoader cl, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException,
            InstantiationException {
        if (ProxyGameActivity.class.getName().equals(className)) {
            Context ctx = BridgeApplication.get();
            if (ctx != null) {
                Activity guest = GuestLauncher.createGuest(ctx, intent);
                if (guest != null) {
                    GUESTS.add(guest);
                    Log.i(TAG, "newActivity(" + className + ") -> guest "
                            + guest.getClass().getName());
                    return guest;
                }
            }
            Log.w(TAG, "guest unavailable; falling back to the proxy Activity");
        }
        return super.newActivity(cl, className, intent);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        Context ctx = BridgeApplication.get();
        final boolean guest = isGuest(activity);
        if (ctx != null && guest) {
            GuestLauncher.attachGuestContext(activity, ctx);
        }
        super.callActivityOnCreate(activity, icicle);
        if (ctx != null && guest) {
            // The guest inflated its view tree during onCreate; the surface can
            // be handed to the core from the next layout pass onwards. Input is
            // dispatched by the framework into the same window, so the probe
            // only observes it.
            GuestLauncher.hookSurface(activity);
            GuestLauncher.probeInput(activity);
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle / input: the framework dispatches these itself. The guest is a
    // real Activity, so there is nothing to forward. The overrides below only
    // add logging at the boundary, which is what the logcat verification reads.
    // ------------------------------------------------------------------

    @Override
    public void callActivityOnResume(Activity activity) {
        Log.i(TAG, "lifecycle onResume " + activity.getClass().getName());
        super.callActivityOnResume(activity);
    }

    @Override
    public void callActivityOnPause(Activity activity) {
        Log.i(TAG, "lifecycle onPause " + activity.getClass().getName());
        super.callActivityOnPause(activity);
    }

    @Override
    public void callActivityOnDestroy(Activity activity) {
        Log.i(TAG, "lifecycle onDestroy " + activity.getClass().getName());
        super.callActivityOnDestroy(activity);
    }

    public Instrumentation delegate() {
        return delegate;
    }
}

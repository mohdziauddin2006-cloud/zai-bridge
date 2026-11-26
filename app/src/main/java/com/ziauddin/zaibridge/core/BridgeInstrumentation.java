package com.ziauddin.zaibridge.core;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
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
                    existing instanceof Instrumentation
                            ? (Instrumentation) existing : new Instrumentation());
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
            if (ctx == null) {
                ctx = getTargetContext();
            }
            if (ctx != null) {
                Activity guest = GuestLauncher.createGuest(ctx, intent);
                if (guest != null) {
                    GUESTS.add(guest);
                    Log.i(TAG, "newActivity(" + className + ") -> guest "
                            + guest.getClass().getName());
                    return guest;
                }
            } else {
                GuestLauncher.recordLaunchError(
                        "BridgeApplication context was null in Instrumentation.newActivity", null);
            }
            Log.w(TAG, "guest unavailable; falling back to the proxy Activity");
        }
        return super.newActivity(cl, className, intent);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        Context ctx = BridgeApplication.get();
        if (ctx == null) ctx = getTargetContext();
        final boolean guest = isGuest(activity);
        if (ctx != null && guest) {
            GuestLauncher.attachGuestContext(activity, ctx);
            // Before performCreate: the guest's onCreate may inflate the very
            // SurfaceView whose dimensions a late rotation would invalidate.
            GuestLauncher.applyGuestOrientation(activity);
        }
        super.callActivityOnCreate(activity, icicle);
        if (ctx != null && guest) {
            // A guest that probed the display size in onCreate saw the pre-rotation
            // orientation and may have asked for it. The imported manifest wins.
            GuestLauncher.confirmGuestOrientation(activity);
            // The guest inflated its view tree during onCreate; the surface can
            // be handed to the core from the next layout pass onwards.
            GuestLauncher.hookSurface(activity);
            GuestLauncher.probeInput(activity);
            GuestLauncher.attachOverlayAndRefreshHooks(activity, ctx);
        }
    }

    // ------------------------------------------------------------------
    // Multi-Activity guest apps
    // ------------------------------------------------------------------

    /**
     * Rewrites a guest's {@code startActivity} into another launch of the
     * proxy, carrying the new target class in {@code GUEST_ACTIVITY_CLASS}.
     */
    public Instrumentation.ActivityResult execStartActivity(
            Context who, IBinder contextThread, IBinder token, Activity target,
            Intent intent, int requestCode, Bundle options) {
        final Intent rewritten = rewriteGuestIntent(target, who, intent);
        final Intent effective = rewritten != null ? rewritten : intent;
        Instrumentation base = delegate;
        if (base == null || base == this) base = new Instrumentation();
        try {
            Method method = Instrumentation.class.getDeclaredMethod("execStartActivity",
                    Context.class, IBinder.class, IBinder.class, Activity.class,
                    Intent.class, int.class, Bundle.class);
            method.setAccessible(true);
            return (Instrumentation.ActivityResult) method.invoke(base, who, contextThread,
                    token, target, effective, requestCode, options);
        } catch (Throwable t) {
            Log.e(TAG, "execStartActivity(Activity): base delegate unavailable", t);
            return null;
        }
    }

    /**
     * Handles non-Activity {@code Context.startActivity} calls (e.g. from
     * {@code GuestApplication} or {@code GuestContext}) where Android invokes the
     * {@code String target} hidden overload.
     */
    public Instrumentation.ActivityResult execStartActivity(
            Context who, IBinder contextThread, IBinder token, String target,
            Intent intent, int requestCode, Bundle options) {
        final Intent rewritten = rewriteGuestIntent(null, who, intent);
        final Intent effective = rewritten != null ? rewritten : intent;
        Instrumentation base = delegate;
        if (base == null || base == this) base = new Instrumentation();
        try {
            Method method = Instrumentation.class.getDeclaredMethod("execStartActivity",
                    Context.class, IBinder.class, IBinder.class, String.class,
                    Intent.class, int.class, Bundle.class);
            method.setAccessible(true);
            return (Instrumentation.ActivityResult) method.invoke(base, who, contextThread,
                    token, target, effective, requestCode, options);
        } catch (Throwable t) {
            Log.e(TAG, "execStartActivity(String): base delegate unavailable", t);
            return null;
        }
    }

    /**
     * @return a copy of {@code intent} that launches ProxyGameActivity for the
     *         requested class, or null when the intent should be left alone.
     */
    private static Intent rewriteGuestIntent(Activity target, Context who, Intent intent) {
        if (intent == null) return null;
        final String pkg = GuestLauncher.getGuestPackage();
        final String dir = GuestLauncher.getGameDir();
        final Context host = BridgeApplication.get();
        if (pkg == null || dir == null || host == null) return null;

        boolean fromGuest = isGuest(target)
                || who instanceof GuestContext
                || who instanceof GuestApplication
                || (who != null && pkg.equals(who.getPackageName()));
        if (!fromGuest) return null;

        ComponentName component = intent.getComponent();
        if (component == null) return null;

        String compPkg = component.getPackageName();
        if (!pkg.equals(compPkg) && !host.getPackageName().equals(compPkg)) {
            return null;
        }
        String rawClass = component.getClassName();
        if (rawClass == null || rawClass.isEmpty()
                || ProxyGameActivity.class.getName().equals(rawClass)) {
            return null;
        }
        String targetClass = BinaryXmlManifestReader.normalizeClassName(pkg, rawClass);

        Intent out = new Intent(intent);   // preserves every extra and flag
        out.setComponent(new ComponentName(host.getPackageName(),
                ProxyGameActivity.class.getName()));
        out.putExtra(GuestLauncher.EXTRA_GAME_DIR, dir);
        out.putExtra(GuestLauncher.EXTRA_GUEST_ACTIVITY, targetClass);
        Log.i(TAG, "execStartActivity: " + component.flattenToShortString()
                + " -> ProxyGameActivity (guest class=" + targetClass + ")");
        return out;
    }

    // ------------------------------------------------------------------
    // Lifecycle / input: the framework dispatches these itself. The guest is a
    // real Activity, so there is nothing to forward. The overrides below only
    // add logging at the boundary, which is what the logcat verification reads.
    // ------------------------------------------------------------------

    @Override
    public void callActivityOnResume(Activity activity) {
        Log.i(TAG, "lifecycle onResume " + activity.getClass().getName());
        if (isGuest(activity)) {
            EglSwapHook.refreshNativeHooks();
        }
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

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
 * Activity virtualization hook installed exclusively inside the isolated {@code :guest} process.
 *
 * <p>On initial launch of {@link ProxyGameActivity} (before async bootstrap finishes),
 * {@link #newActivity} intentionally returns {@code ProxyGameActivity} itself in &lt; 1 ms
 * so Android transitions immediately into a non-blocking dark splash without stalling
 * the main thread. Once {@link GuestLauncher#EXTRA_BOOTSTRAP_READY} is set,
 * {@link #newActivity} instantiates the already-prepared guest {@link Activity}.
 */
public final class BridgeInstrumentation extends Instrumentation {

    private static final String TAG = "ZaiBridge";

    private final Instrumentation delegate;

    private static final java.util.Set<Activity> GUESTS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Activity, Boolean>());

    public BridgeInstrumentation(Instrumentation delegate) {
        this.delegate = delegate;
    }

    private static boolean isGuest(Activity activity) {
        return activity != null && GUESTS.contains(activity);
    }

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

    @Override
    public Activity newActivity(ClassLoader cl, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException,
            InstantiationException {
        if (ProxyGameActivity.class.getName().equals(className)
                && GuestLauncher.shouldInstantiateGuest(intent)) {
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
        if (ctx != null) {
            AnrWatchdog.install(ctx);
            AnrWatchdog.setForeground(true);
            CrashGuard.installMainLooperShield(ctx);
        }
        final boolean guest = isGuest(activity);
        if (ctx != null && guest) {
            GuestLauncher.attachGuestContext(activity, ctx);
            GuestLauncher.reassertGuestResources(activity);

            android.content.res.Resources actRes = activity.getResources();
            android.content.res.Resources appRes = activity.getApplicationContext() != null
                    ? activity.getApplicationContext().getResources() : null;
            Log.i(TAG, "resources pre-onCreate check: activityWrapped="
                    + (actRes instanceof GuestResourcesWrapper)
                    + " (" + (actRes != null ? actRes.getClass().getSimpleName() : "null") + "), "
                    + "appWrapped=" + (appRes instanceof GuestResourcesWrapper)
                    + " (" + (appRes != null ? appRes.getClass().getSimpleName() : "null") + ")");

            GuestLauncher.applyGuestOrientation(activity);
        }
        super.callActivityOnCreate(activity, icicle);
        if (ctx != null && guest) {
            GuestLauncher.reassertGuestResources(activity);
            GuestLauncher.confirmGuestOrientation(activity);
            GuestLauncher.releaseInputQueueIfViewHosted(activity);
            GuestLauncher.hookSurface(activity);
            GuestLauncher.probeInput(activity);
            GuestLauncher.attachOverlayAndRefreshHooks(activity, ctx);
        }
    }

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

        Intent out = new Intent(intent);
        out.setComponent(new ComponentName(host.getPackageName(),
                ProxyGameActivity.class.getName()));
        out.putExtra(GuestLauncher.EXTRA_GAME_DIR, dir);
        out.putExtra(GuestLauncher.EXTRA_GUEST_ACTIVITY, targetClass);
        out.putExtra(GuestLauncher.EXTRA_BOOTSTRAP_READY, true);
        Log.i(TAG, "execStartActivity: " + component.flattenToShortString()
                + " -> ProxyGameActivity (guest class=" + targetClass + ")");
        return out;
    }

    @Override
    public void callActivityOnPostCreate(Activity activity, Bundle savedInstanceState) {
        if (isGuest(activity)) GuestLauncher.reassertGuestResources(activity);
        super.callActivityOnPostCreate(activity, savedInstanceState);
        if (isGuest(activity)) GuestLauncher.reassertGuestResources(activity);
    }

    @Override
    public void callActivityOnStart(Activity activity) {
        if (isGuest(activity)) GuestLauncher.reassertGuestResources(activity);
        super.callActivityOnStart(activity);
    }

    @Override
    public void callActivityOnRestart(Activity activity) {
        if (isGuest(activity)) GuestLauncher.reassertGuestResources(activity);
        super.callActivityOnRestart(activity);
    }

    @Override
    public void callActivityOnResume(Activity activity) {
        Log.i(TAG, "lifecycle onResume " + activity.getClass().getName());
        AnrWatchdog.setForeground(true);
        if (isGuest(activity)) {
            GuestLauncher.reassertGuestResources(activity);
            EglSwapHook.refreshNativeHooks();
        }
        super.callActivityOnResume(activity);
    }

    @Override
    public void callActivityOnStop(Activity activity) {
        super.callActivityOnStop(activity);
        if (!activity.isFinishing()) {
            AnrWatchdog.setForeground(false);
        }
    }

    @Override
    public void callActivityOnPause(Activity activity) {
        Log.i(TAG, "lifecycle onPause " + activity.getClass().getName());
        super.callActivityOnPause(activity);
    }

    @Override
    public void callActivityOnDestroy(Activity activity) {
        Log.i(TAG, "lifecycle onDestroy " + activity.getClass().getName());
        final boolean wasGuest = isGuest(activity);
        GUESTS.remove(activity);
        super.callActivityOnDestroy(activity);
        if (wasGuest && GUESTS.isEmpty()) {
            GuestLauncher.restoreHostResourcesIfIdle();
        }
    }

    public Instrumentation delegate() {
        return delegate;
    }
}

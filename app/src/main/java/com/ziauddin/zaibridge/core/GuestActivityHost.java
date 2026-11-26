package com.ziauddin.zaibridge.core;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Instantiates the imported game's Activity from its own DexClassLoader and
 * delegates the lifecycle to it.
 *
 * <p>Instantiation is unconditional; lifecycle delegation only happens when the
 * instance has a usable Window. An Activity cannot run its lifecycle without
 * being attached by the framework (ActivityThread record, Instrumentation and a
 * window token), and those internals are non-SDK interfaces on modern Android.
 * When that attachment is unavailable the host records the exact reason instead
 * of invoking a half-built Activity and crashing the process. Everything the
 * guest's <em>native</em> half needs — a valid base context, the remapped
 * storage roots and the host's real system services — is still injected through
 * {@link GuestContext}.
 */
public final class GuestActivityHost {

    private static final String TAG = "ZaiBridge";

    private final Context host;
    private final Activity hostActivity;
    private final File gameDir;
    private final String guestPackage;
    private final ClassLoader loader;

    private Activity guest;
    private String activityName = "";
    private boolean attached;
    private String status = "not created";

    public GuestActivityHost(Context host, File gameDir, String guestPackage,
                             ClassLoader loader) {
        this.host = host;
        this.hostActivity = host instanceof Activity ? (Activity) host : null;
        this.gameDir = gameDir;
        this.guestPackage = guestPackage;
        this.loader = loader;
    }

    public boolean create(String launcherActivity) {
        if (launcherActivity == null || launcherActivity.isEmpty()) {
            status = "guest manifest declared no launchable Activity";
            Log.w(TAG, status);
            return false;
        }

        activityName = launcherActivity;
        try {
            Class<?> cls = Class.forName(launcherActivity, true, loader);
            if (!Activity.class.isAssignableFrom(cls)) {
                status = launcherActivity + " is not an android.app.Activity";
                Log.w(TAG, status);
                return false;
            }

            Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            guest = (Activity) ctor.newInstance();

            boolean isNative64 = GuestLauncher.isNative64Game(null, gameDir);
            GuestContext guestContext = new GuestContext(
                    host, gameDir, guestPackage, launcherActivity, isNative64, loader);

            GuestLauncher.injectGuestResourcesMultiLayer(guest, guestContext, host);

            Window window = createWindow();
            attached = window != null;
            if (attached) {
                Field windowField = Activity.class.getDeclaredField("mWindow");
                windowField.setAccessible(true);
                windowField.set(guest, window);
                status = "guest Activity attached";
            } else {
                status = "guest Activity constructed; framework attachment "
                        + "(ActivityThread/Instrumentation window token) unavailable";
            }
            Log.i(TAG, "guest Activity " + launcherActivity + ": " + status);
            return attached;
        } catch (Throwable t) {
            guest = null;
            attached = false;
            status = "guest Activity instantiation failed: " + t;
            Log.e(TAG, status, t);
            return false;
        }
    }

    /**
     * Fills in the framework plumbing {@link Activity#onCreate} assumes:
     * ActivityThread, Instrumentation, Application, ActivityInfo, the window
     * token and the guest window's WindowManager. Without these, the first
     * line of the guest's onCreate throws on a null ActivityInfo.
     *
     * <p>This relies on non-SDK access, which debug builds permit and some
     * retail builds do not; failure is logged and the native side is still
     * driven independently.
     */
    public boolean attachToFramework() {
        if (guest == null) return false;
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object mainThread = threadClass.getDeclaredMethod("currentActivityThread")
                    .invoke(null);
            android.app.Instrumentation instrumentation =
                    new android.app.Instrumentation();
            android.content.Context guestBase = guest.getBaseContext();
            android.app.Application application = (guestBase instanceof GuestContext)
                    ? ((GuestContext) guestBase).getGuestApplication()
                    : (hostActivity != null ? hostActivity.getApplication() : null);
            android.content.pm.ActivityInfo info = null;
            if (guestBase instanceof GuestContext) {
                info = ((GuestContext) guestBase).resolveGuestActivityInfo(
                        new android.content.ComponentName(guestPackage, activityName));
            }
            if (info == null && hostActivity != null) {
                Field infoField = Activity.class.getDeclaredField("mActivityInfo");
                infoField.setAccessible(true);
                info = (android.content.pm.ActivityInfo) infoField.get(hostActivity);
            }
            android.os.IBinder token = hostActivity != null
                    && hostActivity.getWindow() != null
                    ? hostActivity.getWindow().getDecorView().getWindowToken() : null;

            setField(Activity.class, guest, "mMainThread", mainThread);
            setField(Activity.class, guest, "mInstrumentation", instrumentation);
            setField(Activity.class, guest, "mApplication", application);
            setField(Activity.class, guest, "mActivityInfo", info);
            setField(Activity.class, guest, "mToken", token);
            setField(Activity.class, guest, "mParent", null);
            setField(Activity.class, guest, "mCalled", false);

            Window window = guest.getWindow();
            if (window != null) {
                try {
                    Method setWindowManager = Window.class.getDeclaredMethod(
                            "setWindowManager", android.view.WindowManager.class,
                            android.os.IBinder.class, String.class, boolean.class);
                    setWindowManager.setAccessible(true);
                    setWindowManager.invoke(window, hostActivity.getWindowManager(), token,
                            hostActivity.getPackageName(), false);
                } catch (Throwable ignored) {
                    // A window without a WindowManager still inflates a view
                    // hierarchy; only framework attach()-driven features need it.
                }
            }
            Log.i(TAG, "guest framework attach: thread/instrumentation/activityInfo/token set");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "guest framework attach unavailable: " + t);
            return false;
        }
    }

    private static void setField(Class<?> type, Object target, String name, Object value)
            throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        Log.w(TAG, "framework attach: field " + name + " not found");
    }

    private Window createWindow() {
        try {
            Class<?> phoneWindow = Class.forName("com.android.internal.policy.PhoneWindow");
            Constructor<?> ctor = phoneWindow.getConstructor(Context.class);
            return (Window) ctor.newInstance(guest);
        } catch (Throwable t) {
            // Hidden-API enforcement on API 28+ makes this the normal outcome.
            Log.w(TAG, "guest window unavailable: " + t.getClass().getSimpleName());
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Lifecycle delegation
    // ---------------------------------------------------------------------

    public void onCreate() {
        invoke("onCreate", new Class<?>[]{Bundle.class}, new Object[]{null});
    }

    public void onStart() {
        invoke("onStart", new Class<?>[0], new Object[0]);
    }

    public void onResume() {
        invoke("onResume", new Class<?>[0], new Object[0]);
    }

    public void onPause() {
        invoke("onPause", new Class<?>[0], new Object[0]);
    }

    public void onStop() {
        invoke("onStop", new Class<?>[0], new Object[0]);
    }

    public void onDestroy() {
        invoke("onDestroy", new Class<?>[0], new Object[0]);
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        invoke("onWindowFocusChanged", new Class<?>[]{boolean.class},
                new Object[]{hasFocus});
    }

    private void invoke(String method, Class<?>[] signature, Object[] args) {
        if (guest == null || !attached) {
            Log.i(TAG, "guest lifecycle " + method + " skipped (" + status + ")");
            return;
        }
        try {
            Method m = findMethod(guest.getClass(), method, signature);
            m.setAccessible(true);
            m.invoke(guest, args);
            Log.i(TAG, "guest lifecycle " + method + " delegated");
        } catch (Throwable t) {
            Log.e(TAG, "guest " + method + " failed", t);
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] signature)
            throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, signature);
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchMethodException(name);
    }

    public boolean isAttached() {
        return attached;
    }

    public Activity getGuest() {
        return guest;
    }

    /** The guest window's decor view, when the framework produced one. */
    public android.view.View getDecorView() {
        if (guest == null) return null;
        try {
            Window window = guest.getWindow();
            return window != null ? window.getDecorView() : null;
        } catch (Throwable t) {
            Log.w(TAG, "guest decor view unavailable: " + t);
            return null;
        }
    }

    public String getStatus() {
        return status + (activityName.isEmpty() ? "" : " [" + activityName + "]");
    }
}

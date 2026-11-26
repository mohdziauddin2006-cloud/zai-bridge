package com.ziauddin.zaibridge.core;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import com.ziauddin.zaibridge.data.Game;
import com.ziauddin.zaibridge.data.GameRepository;
import com.ziauddin.zaibridge.data.GraphicsSettings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Bridge between the `:guest` proxy Activity and the imported game.
 *
 * <p>All heavy bootstrap work (assets unpack, `DexClassLoader`, manifest/resource binding,
 * native `libwrapper.so` load, and Unicorn ARM32 ELF/constructor execution) executes strictly
 * off the Main UI Thread in {@link #bootstrapOffUiThread}.
 */
public final class GuestLauncher {

    private static final String TAG = "ZaiBridge";

    public static final String EXTRA_GAME_DIR = "com.ziauddin.zaibridge.extra.GAME_DIR";
    public static final String EXTRA_GAME_LABEL = "com.ziauddin.zaibridge.extra.GAME_LABEL";
    public static final String EXTRA_GUEST_ACTIVITY =
            "com.ziauddin.zaibridge.extra.GUEST_ACTIVITY_CLASS";
    public static final String EXTRA_GAME_PACKAGE =
            "com.ziauddin.zaibridge.extra.GAME_PACKAGE";
    public static final String EXTRA_IS_NATIVE64 =
            "com.ziauddin.zaibridge.extra.IS_NATIVE64";
    public static final String EXTRA_BOOTSTRAP_READY =
            "com.ziauddin.zaibridge.extra.BOOTSTRAP_READY";

    public interface StatusListener {
        void onStatus(String message);
    }

    private static volatile String sessionGameDir;
    private static volatile ClassLoader guestLoader;
    private static volatile File guestLoaderDir;
    private static volatile boolean guestLoaderNative64;
    private static volatile File guestGameDir;
    private static volatile String guestPackage;
    private static volatile String guestClass;
    private static volatile boolean guestNative64;
    private static volatile GuestContext cachedGuestContext;
    private static volatile File cachedGuestContextDir;
    private static volatile String cachedGuestContextActivity;
    private static volatile String bootstrapPreparedDir;
    private static volatile boolean providersAndAppInitialized;
    private static volatile File initializedAppGameDir;
    private static volatile String lastLaunchError;

    private static final ExecutorService SURFACE_QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zai-bridge-surface");
        t.setDaemon(true);
        return t;
    });

    private GuestLauncher() {
    }

    public static String getLastLaunchError() {
        return lastLaunchError;
    }

    public static void clearLastLaunchError() {
        lastLaunchError = null;
    }

    public static void recordLaunchError(String summary, Throwable t) {
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isEmpty()) {
            sb.append(summary);
        }
        if (t != null) {
            if (sb.length() > 0) sb.append("\n\n");
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            sb.append(sw.toString());
        }
        lastLaunchError = sb.toString();
    }

    public static boolean shouldInstantiateGuest(Intent intent) {
        if (intent == null) return false;
        if (!intent.getBooleanExtra(EXTRA_BOOTSTRAP_READY, false)) {
            return false;
        }
        String dir = intent.getStringExtra(EXTRA_GAME_DIR);
        return dir != null && dir.equals(bootstrapPreparedDir);
    }

    public static boolean isBootstrapPreparedFor(String dir) {
        return dir != null && dir.equals(bootstrapPreparedDir);
    }

    public static synchronized void invalidateBootstrap() {
        bootstrapPreparedDir = null;
        providersAndAppInitialized = false;
        initializedAppGameDir = null;
        cachedGuestContext = null;
        cachedGuestContextDir = null;
        cachedGuestContextActivity = null;
    }

    /**
     * Exits the current guest cleanly and reaps the isolated `:guest` OS process
     * so every subsequent game starts with a 100% clean ART ClassLoader, EGL state,
     * and native memory space without affecting {@code MainActivity}.
     */
    public static void exitGuestProcess(Activity activity) {
        try {
            if (activity != null && !activity.isFinishing()) {
                activity.finish();
            }
        } catch (Throwable ignored) {
        }
        final Context appCtx = activity != null
                ? activity.getApplicationContext()
                : BridgeApplication.get();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            SURFACE_QUEUE.execute(() -> {
                try {
                    UnicornBridge.setSurface(null);
                    UnicornBridge.endSession();
                } catch (Throwable ignored) {
                }
                if (appCtx != null && BridgeApplication.isGuestProcess(appCtx)) {
                    Process.killProcess(Process.myPid());
                    System.exit(0);
                }
            });
        }, 180);
    }

    /**
     * Runs all heavy game preparation off Android's Main UI Thread inside `:guest`.
     * Never blocks UI input dispatch or rotation.
     */
    public static boolean bootstrapOffUiThread(
            Context ctx, Intent intent, StatusListener listener) {
        clearLastLaunchError();
        if (ctx == null || intent == null) {
            recordLaunchError("Missing context or launch Intent during guest bootstrap", null);
            return false;
        }
        final Context appCtx = ctx.getApplicationContext();
        final String dir = intent.getStringExtra(EXTRA_GAME_DIR);
        if (dir == null || dir.isEmpty()) {
            recordLaunchError("Launch invoked without EXTRA_GAME_DIR", null);
            return false;
        }
        final File gameDir = new File(dir);
        final File apk = new File(gameDir, "game.apk");
        if (!apk.isFile()) {
            recordLaunchError("Incomplete import: missing base game.apk at " + apk.getAbsolutePath(), null);
            return false;
        }

        try {
            notifyStatus(listener, "Initializing container hooks…");
            BridgeRuntime.ensureInitialized(appCtx);

            Game game = GameRepository.load(gameDir);
            String pkg = intent.getStringExtra(EXTRA_GAME_PACKAGE);
            if ((pkg == null || pkg.isEmpty()) && game != null) pkg = game.packageName;
            if (pkg == null || pkg.isEmpty()) pkg = gameDir.getName();

            final boolean isNative64 = isNative64Game(game, gameDir)
                    || intent.getBooleanExtra(EXTRA_IS_NATIVE64, false);

            notifyStatus(listener, "Loading manifest & split-APK resources…");
            List<File> splitFiles = GuestContext.collectStagedSplitApks(gameDir);
            java.util.ArrayList<String> splitPaths = new java.util.ArrayList<>();
            for (File f : splitFiles) splitPaths.add(f.getAbsolutePath());

            BinaryXmlManifestReader.ParsedManifest manifest =
                    BinaryXmlManifestReader.readFromApk(apk.getAbsolutePath(), splitPaths, null);

            String cls = intent.getStringExtra(EXTRA_GUEST_ACTIVITY);
            if ((cls == null || cls.isEmpty()) && game != null) cls = game.launcherActivity;
            if (cls == null || cls.isEmpty()) cls = manifest.launcherActivity;
            cls = BinaryXmlManifestReader.normalizeClassName(pkg, cls);
            cls = manifest.resolveActivityTarget(cls);

            if (cls.isEmpty()) {
                recordLaunchError("Guest AndroidManifest.xml declares no launchable Activity (" + pkg + ")", null);
                return false;
            }

            guestGameDir = gameDir;
            guestPackage = pkg;
            guestClass = cls;
            guestNative64 = isNative64;

            EglSwapHook.install();
            EglSwapHook.configure(GraphicsSettings.forGame(appCtx, gameDir));

            if (!isNative64) {
                notifyStatus(listener, "Staging guest runtime assets…");
                unpackBundledAssets(apk, gameDir);
            }

            notifyStatus(listener, "Building guest ClassLoader…");
            ClassLoader loader = ensureGuestLoader(appCtx, gameDir, pkg, isNative64);
            Thread.currentThread().setContextClassLoader(loader);

            getOrCreateGuestContext(appCtx, gameDir, pkg, cls, isNative64, loader);

            if (isNative64) {
                if (UnicornBridge.isSessionActive()) {
                    UnicornBridge.endSession();
                    sessionGameDir = null;
                }
                EglSwapHook.refreshNativeHooks();
            } else {
                boolean hasPayload = hasNativePayload(new File(gameDir, "payload"));
                if (hasPayload) {
                    notifyStatus(listener, "Starting ARM32 translation core…");
                    openNativeSession(appCtx, gameDir, game, pkg, loader);
                }
            }

            if (!gameDir.equals(initializedAppGameDir)) {
                providersAndAppInitialized = false;
            }
            bootstrapPreparedDir = gameDir.getAbsolutePath();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Guest async bootstrap failed", t);
            recordLaunchError("Guest bootstrap failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage(), t);
            return false;
        }
    }

    private static void notifyStatus(StatusListener listener, String msg) {
        if (listener != null && msg != null) {
            listener.onStatus(msg);
        }
    }

    /**
     * Safely initializes guest manifest {@link ContentProvider}s (such as
     * {@code InitializationProvider}, {@code FirebaseInitProvider}, {@code FileProvider})
     * and any declared guest {@link Application} subclass inside `:guest`'s Main Thread
     * before the guest {@link Activity} is created.
     */
    public static synchronized void ensureGuestProvidersAndApplication(Context hostCtx) {
        final File dir = guestGameDir;
        if (dir == null || (providersAndAppInitialized && dir.equals(initializedAppGameDir))) {
            return;
        }
        providersAndAppInitialized = true;
        initializedAppGameDir = dir;

        final GuestContext gc = cachedGuestContext;
        final ClassLoader loader = guestLoader;
        if (gc == null || loader == null) return;

        final ClassLoader prevTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            // Inject guest Resources onto the :guest Application/LoadedApk first so any
            // SDK reading resources during ContentProvider.onCreate() / Application.onCreate()
            // sees GuestResourcesWrapper.
            injectContextChainResources(
                    gc.getGuestApplication(),
                    gc.getResources(),
                    true,
                    Collections.newSetFromMap(new IdentityHashMap<>()));

            String declaredApp = gc.getDeclaredApplicationClass();
            Application appTarget = gc.getGuestApplication();
            if (declaredApp != null && !declaredApp.isEmpty()
                    && !"android.app.Application".equals(declaredApp)
                    && !GuestApplication.class.getName().equals(declaredApp)) {
                try {
                    Class<?> appCls = Class.forName(declaredApp, true, loader);
                    if (Application.class.isAssignableFrom(appCls)) {
                        Application custom = (Application) appCls.getDeclaredConstructor().newInstance();
                        attachCustomApplication(custom, gc);
                        gc.setCustomGuestApplication(custom);
                        appTarget = custom;
                        Log.i(TAG, "instantiated guest Application subclass: " + declaredApp);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "guest Application instantiation skipped (" + declaredApp + "): " + t);
                }
            }

            List<ProviderInfo> providers = gc.getGuestProviders();
            if (providers != null && !providers.isEmpty()) {
                for (ProviderInfo pi : providers) {
                    if (pi == null || pi.name == null || pi.name.isEmpty() || !pi.enabled) continue;
                    // Skip providers pinned to remote processes other than main/default.
                    if (pi.processName != null && !pi.processName.isEmpty()
                            && pi.processName.contains(":")
                            && !pi.processName.endsWith(":guest")) {
                        continue;
                    }
                    try {
                        Class<?> pCls = Class.forName(pi.name, true, loader);
                        if (!ContentProvider.class.isAssignableFrom(pCls)) continue;
                        ContentProvider cp = (ContentProvider) pCls.getDeclaredConstructor().newInstance();
                        ProviderInfo piCopy = new ProviderInfo(pi);
                        cp.attachInfo(appTarget, piCopy);
                        Log.i(TAG, "initialized guest ContentProvider: " + pi.name);
                    } catch (Throwable t) {
                        Log.w(TAG, "guest ContentProvider init guarded (" + pi.name + "): " + t.getMessage());
                    }
                }
            }

            if (appTarget != gc.getGuestApplication()) {
                try {
                    appTarget.onCreate();
                    Log.i(TAG, "guest Application.onCreate() completed");
                } catch (Throwable t) {
                    Log.w(TAG, "guest Application.onCreate() guarded: " + t.getMessage());
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(prevTccl);
        }
    }

    private static void attachCustomApplication(Application custom, GuestContext gc) {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            attach.invoke(custom, gc);
        } catch (Throwable ignored) {
            setFieldQuiet(android.content.ContextWrapper.class, custom, "mBase", gc);
        }
        injectContextChainResources(
                custom,
                gc.getResources(),
                false,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    public static Activity createGuest(Context ctx, Intent intent) {
        clearLastLaunchError();
        BridgeRuntime.ensureInitialized(ctx);
        final String dir = intent == null ? null : intent.getStringExtra(EXTRA_GAME_DIR);
        if (dir == null || dir.isEmpty()) {
            String msg = "Proxy launch invoked without EXTRA_GAME_DIR";
            Log.w(TAG, msg);
            recordLaunchError(msg, null);
            return null;
        }
        final File gameDir = new File(dir);
        final File apk = new File(gameDir, "game.apk");
        if (!apk.isFile()) {
            String msg = "Incomplete import: missing base game.apk at " + apk.getAbsolutePath();
            Log.e(TAG, msg);
            recordLaunchError(msg, null);
            return null;
        }

        try {
            if (!dir.equals(bootstrapPreparedDir)) {
                bootstrapOffUiThread(ctx, intent, null);
            }
            if (getLastLaunchError() != null) {
                return null;
            }
            ensureGuestProvidersAndApplication(ctx);

            final String pkg = guestPackage != null ? guestPackage : gameDir.getName();
            final String name = guestClass != null ? guestClass : "";
            final boolean isNative64 = guestNative64;
            final ClassLoader loader = guestLoader;
            if (name.isEmpty() || loader == null) {
                recordLaunchError("Guest class/loader unavailable for " + pkg, null);
                return null;
            }

            Thread.currentThread().setContextClassLoader(loader);
            Class<?> cls = Class.forName(name, true, loader);
            if (!Activity.class.isAssignableFrom(cls)) {
                String msg = name + " does not extend android.app.Activity (resolved for " + pkg + ")";
                Log.e(TAG, msg);
                recordLaunchError(msg, null);
                return null;
            }

            java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            Activity guest = (Activity) ctor.newInstance();

            EglSwapHook.refreshNativeHooks();
            Log.i(TAG, "guest Activity " + name + " instantiated from " + apk.getName()
                    + " (mode=" + (isNative64 ? "native64" : "unicorn32") + ")");
            return guest;
        } catch (Throwable t) {
            Log.e(TAG, "guest Activity could not be created", t);
            recordLaunchError("Failed instantiating guest Activity ("
                    + t.getClass().getName() + ": " + t.getMessage() + ")", t);
            return null;
        }
    }

    public static void prepareGame(Context ctx, File gameDir, Game game) {
        if (ctx == null || gameDir == null) return;
        Intent i = new Intent();
        i.putExtra(EXTRA_GAME_DIR, gameDir.getAbsolutePath());
        if (game != null) {
            i.putExtra(EXTRA_GAME_PACKAGE, game.packageName);
            i.putExtra(EXTRA_GUEST_ACTIVITY, game.launcherActivity);
            i.putExtra(EXTRA_IS_NATIVE64, game.isNative64());
        }
        bootstrapOffUiThread(ctx, i, null);
    }

    private static synchronized ClassLoader ensureGuestLoader(Context ctx, File gameDir,
                                                              String pkg, boolean isNative64) {
        if (guestLoader != null && gameDir.equals(guestLoaderDir)
                && guestLoaderNative64 == isNative64) {
            return guestLoader;
        }

        File optimized = new File(ctx.getCodeCacheDir(), "guest-" + gameDir.getName());
        if (!optimized.isDirectory() && !optimized.mkdirs()) {
            optimized = ctx.getCodeCacheDir();
        }

        String dexPath = buildCombinedDexPath(gameDir);

        File nativeDir;
        String hostLibrary;
        String searchPath;
        if (isNative64) {
            File dir64 = new File(gameDir, "native64");
            nativeDir = dir64.isDirectory() ? dir64 : new File(gameDir, "native");
            hostLibrary = null;
            searchPath = buildNativeLibrarySearchPath(ctx, gameDir, pkg, true);
        } else {
            nativeDir = new File(gameDir, "native");
            hostLibrary = UnicornBridge.isPassthrough()
                    ? null : GuestDexLoader.hostBridgeLibrary(ctx);
            searchPath = buildNativeLibrarySearchPath(ctx, gameDir, pkg, false);
        }

        ClassLoader parent = isNative64
                ? GuestLauncher.class.getClassLoader()
                : GuestDexLoader.legacyParent(ctx, GuestLauncher.class.getClassLoader());

        ClassLoader loader = new GuestDexLoader(dexPath,
                optimized.getAbsolutePath(), searchPath,
                parent, hostLibrary, nativeDir);
        guestLoader = loader;
        guestLoaderDir = gameDir;
        guestLoaderNative64 = isNative64;
        UnicornBridge.setGuestLoader(loader);
        Log.i(TAG, "guest ClassLoader ready (mode=" + (isNative64 ? "native64" : "unicorn32")
                + ", searchPath=" + searchPath + ")");
        return loader;
    }

    public static String buildNativeLibrarySearchPath(Context ctx, File gameDir,
                                                      String pkg, boolean isNative64) {
        java.util.LinkedHashSet<String> entries = new java.util.LinkedHashSet<>();
        if (isNative64) {
            File dir64 = new File(gameDir, "native64");
            if (dir64.isDirectory()) entries.add(dir64.getAbsolutePath());
            File dir32 = new File(gameDir, "native");
            if (dir32.isDirectory()) entries.add(dir32.getAbsolutePath());
        } else {
            File dir32 = new File(gameDir, "native");
            if (dir32.isDirectory()) entries.add(dir32.getAbsolutePath());
            File payload = new File(gameDir, "payload");
            if (payload.isDirectory()) entries.add(payload.getAbsolutePath());
        }

        String primaryAbi = isNative64
                ? (Build.SUPPORTED_64_BIT_ABIS.length > 0 ? Build.SUPPORTED_64_BIT_ABIS[0] : "arm64-v8a")
                : (Build.SUPPORTED_32_BIT_ABIS.length > 0 ? Build.SUPPORTED_32_BIT_ABIS[0] : "armeabi-v7a");

        if (ctx != null && pkg != null && !pkg.isEmpty()) {
            try {
                android.content.pm.ApplicationInfo ai =
                        ctx.getPackageManager().getApplicationInfo(pkg, 0);
                if (ai != null) {
                    if (ai.nativeLibraryDir != null && !ai.nativeLibraryDir.isEmpty()) {
                        entries.add(ai.nativeLibraryDir);
                    }
                    if (ai.sourceDir != null && !ai.sourceDir.isEmpty()) {
                        entries.add(ai.sourceDir + "!/lib/" + primaryAbi);
                    }
                    if (ai.splitSourceDirs != null) {
                        for (String s : ai.splitSourceDirs) {
                            if (s != null && !s.isEmpty()) {
                                entries.add(s + "!/lib/" + primaryAbi);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        File baseApk = new File(gameDir, "game.apk");
        if (baseApk.isFile()) {
            entries.add(baseApk.getAbsolutePath() + "!/lib/" + primaryAbi);
        }
        File splitsDir = new File(gameDir, "splits");
        if (splitsDir.isDirectory()) {
            File[] splits = splitsDir.listFiles((d, n) -> n.endsWith(".apk"));
            if (splits != null) {
                Arrays.sort(splits);
                for (File split : splits) {
                    if (split.isFile()) {
                        entries.add(split.getAbsolutePath() + "!/lib/" + primaryAbi);
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String e : entries) {
            if (sb.length() > 0) sb.append(File.pathSeparatorChar);
            sb.append(e);
        }
        return sb.toString();
    }

    public static synchronized GuestContext getOrCreateGuestContext(
            Context hostAppCtx, File gameDir, String pkg, String activityClass,
            boolean isNative64, ClassLoader loader) {
        if (cachedGuestContext != null
                && gameDir != null
                && gameDir.equals(cachedGuestContextDir)
                && java.util.Objects.equals(activityClass, cachedGuestContextActivity)) {
            return cachedGuestContext;
        }
        GuestContext gc = new GuestContext(
                hostAppCtx, gameDir, pkg, activityClass, isNative64, loader);
        cachedGuestContext = gc;
        cachedGuestContextDir = gameDir;
        cachedGuestContextActivity = activityClass;
        return gc;
    }

    public static String buildCombinedDexPath(File gameDir) {
        StringBuilder path = new StringBuilder();
        File baseApk = new File(gameDir, "game.apk");
        if (baseApk.isFile()) {
            ensureReadOnly(baseApk);
            path.append(baseApk.getAbsolutePath());
        }
        File splitsDir = new File(gameDir, "splits");
        if (splitsDir.isDirectory()) {
            File[] splits = splitsDir.listFiles((d, n) -> n.endsWith(".apk"));
            if (splits != null && splits.length > 0) {
                Arrays.sort(splits);
                for (File split : splits) {
                    if (!split.isFile()) continue;
                    ensureReadOnly(split);
                    if (path.length() > 0) path.append(File.pathSeparatorChar);
                    path.append(split.getAbsolutePath());
                }
            }
        }
        return path.toString();
    }

    private static void ensureReadOnly(File file) {
        if (file.canWrite() && !file.setReadOnly()) {
            Log.w(TAG, file.getName() + " could not be made read-only; ART may refuse it");
        }
    }

    public static boolean isNative64Game(Game game, File gameDir) {
        if (game != null && game.isNative64()) return true;
        File dir64 = new File(gameDir, "native64");
        if (dir64.isDirectory()) {
            File[] libs = dir64.listFiles((d, name) -> name.endsWith(".so"));
            return libs != null && libs.length > 0;
        }
        return false;
    }

    public static void applyGuestOrientation(Activity guest) {
        GameOrientation.apply(guest, guestGameDir);
    }

    public static void confirmGuestOrientation(Activity guest) {
        Game game = guestGameDir == null ? null : GameRepository.load(guestGameDir);
        if (game == null) return;
        int wanted = GameOrientation.constantOf(game.orientation);
        if (wanted == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return;
        if (guest.getRequestedOrientation() == wanted) return;
        Log.i(TAG, "guest orientation: re-asserting manifest " + game.orientation
                + " over the guest's own request " + guest.getRequestedOrientation());
        GameOrientation.apply(guest, game.orientation, game.label);
    }

    private static final Object RESOURCES_INJECT_LOCK = new Object();
    private static final java.util.Map<Object, java.util.Map<java.lang.reflect.Field, android.content.res.Resources>> SAVED_SHARED_RESOURCES =
            new IdentityHashMap<>();
    private static final java.util.Map<Activity, GuestContext> BOUND_GUEST_CONTEXTS =
            new WeakHashMap<>();
    private static final Set<Activity> CONFIG_HOOKED_ACTIVITIES =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static void setFieldQuiet(Class<?> stopClass, Object target, String fieldName, Object value) {
        if (target == null) return;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                if (c == stopClass) break;
            } catch (Throwable t) {
                Log.w(TAG, "setFieldQuiet(" + fieldName + ") skipped: " + t.getMessage());
                return;
            }
        }
    }

    private static void replaceAllResourcesFields(
            Object target,
            android.content.res.Resources guestRes,
            boolean trackOriginalForRestore) {
        if (target == null || guestRes == null) return;
        final android.content.res.Resources sysRes = android.content.res.Resources.getSystem();
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    String name = f.getName();
                    boolean isResField = "mResources".equals(name)
                            || android.content.res.Resources.class.isAssignableFrom(f.getType());
                    f.setAccessible(true);
                    Object cur = f.get(target);
                    if (!isResField && cur instanceof android.content.res.Resources) {
                        isResField = true;
                    }
                    if (!isResField) continue;
                    if (cur == guestRes || cur == sysRes || "mSystem".equals(name)) continue;
                    boolean shouldTrack = trackOriginalForRestore
                            || (!(target instanceof Activity)
                                && !(target instanceof GuestContext)
                                && !(target instanceof GuestApplication));
                    if (shouldTrack && cur instanceof android.content.res.Resources
                            && !(cur instanceof GuestResourcesWrapper)) {
                        synchronized (RESOURCES_INJECT_LOCK) {
                            java.util.Map<java.lang.reflect.Field, android.content.res.Resources> fieldMap =
                                    SAVED_SHARED_RESOURCES.get(target);
                            if (fieldMap == null) {
                                fieldMap = new java.util.LinkedHashMap<>();
                                SAVED_SHARED_RESOURCES.put(target, fieldMap);
                            }
                            if (!fieldMap.containsKey(f)) {
                                fieldMap.put(f, (android.content.res.Resources) cur);
                            }
                        }
                    }
                    f.set(target, guestRes);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Context getWrapperBaseQuiet(Context ctx) {
        if (ctx instanceof android.content.ContextWrapper) {
            return ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private static void injectContextChainResources(
            Context start,
            android.content.res.Resources guestRes,
            boolean trackSharedForRestore,
            Set<Object> visited) {
        Context cur = start;
        while (cur != null && visited.add(cur)) {
            replaceAllResourcesFields(cur, guestRes, trackSharedForRestore);
            for (Class<?> c = cur.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field pkgInfoField = c.getDeclaredField("mPackageInfo");
                    pkgInfoField.setAccessible(true);
                    Object loadedApk = pkgInfoField.get(cur);
                    if (loadedApk != null && visited.add(loadedApk)) {
                        replaceAllResourcesFields(loadedApk, guestRes, true);
                    }
                    break;
                } catch (NoSuchFieldException ignored) {
                } catch (Throwable ignored) {
                    break;
                }
            }
            cur = getWrapperBaseQuiet(cur);
        }
    }

    public static void injectGuestResourcesMultiLayer(
            Activity activity, GuestContext guestContext, Context hostCtx) {
        if (activity == null || guestContext == null) return;
        try {
            HiddenApi.exemptAll();
        } catch (Throwable ignored) {
        }
        try {
            android.content.pm.ApplicationInfo guestAi = guestContext.getApplicationInfo();
            int targetSdk = guestAi != null ? guestAi.targetSdkVersion : 0;
            String hostPkg = guestContext.getHostBaseContext() != null
                    ? guestContext.getHostBaseContext().getPackageName()
                    : (hostCtx != null ? hostCtx.getPackageName() : activity.getPackageName());
            GuestCompatHooks.installForGuest(
                    hostCtx != null ? hostCtx : activity,
                    guestContext.getPackageName(),
                    hostPkg,
                    targetSdk);
        } catch (Throwable ignored) {
        }

        final android.content.res.Resources guestRes = guestContext.getResources();
        final Context origBase = activity.getBaseContext();
        final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        if (origBase != null && !(origBase instanceof GuestContext)) {
            injectContextChainResources(origBase, guestRes, true, visited);
        }

        if (!(origBase instanceof GuestContext)) {
            setFieldQuiet(android.content.ContextWrapper.class, activity, "mBase", guestContext);
        }

        injectContextChainResources(activity, guestRes, false, visited);
        injectContextChainResources(guestContext, guestRes, true, visited);
        injectContextChainResources(guestContext.getGuestApplication(), guestRes, true, visited);
        injectContextChainResources(guestContext.getEffectiveApplication(), guestRes, true, visited);
        if (hostCtx != null) {
            injectContextChainResources(hostCtx.getApplicationContext(), guestRes, true, visited);
        }

        String targetActName = (guestClass != null && !guestClass.isEmpty())
                ? guestClass
                : (guestContext.getPrimaryActivityClass() != null && !guestContext.getPrimaryActivityClass().isEmpty()
                        ? guestContext.getPrimaryActivityClass()
                        : activity.getClass().getName());
        android.content.ComponentName comp = new android.content.ComponentName(
                guestContext.getPackageName(), targetActName);
        android.content.pm.ActivityInfo guestAi = guestContext.resolveGuestActivityInfo(comp);
        if (guestAi != null) {
            setFieldQuiet(Activity.class, activity, "mActivityInfo", guestAi);
        }
        setFieldQuiet(Activity.class, activity, "mApplication",
                guestContext.getEffectiveApplication());

        int themeRes = guestContext.resolveGuestThemeRes(guestAi);
        if (themeRes != 0) {
            setFieldQuiet(android.view.ContextThemeWrapper.class, activity,
                    "mThemeResource", themeRes);
        }
        setFieldQuiet(android.view.ContextThemeWrapper.class, activity, "mTheme", null);
        setFieldQuiet(android.view.ContextThemeWrapper.class, activity, "mInflater", null);

        synchronized (RESOURCES_INJECT_LOCK) {
            BOUND_GUEST_CONTEXTS.put(activity, guestContext);
        }
        registerConfigReenforcementIfNeeded(activity);
    }

    private static void registerConfigReenforcementIfNeeded(final Activity activity) {
        synchronized (RESOURCES_INJECT_LOCK) {
            if (!CONFIG_HOOKED_ACTIVITIES.add(activity)) return;
        }
        try {
            activity.registerComponentCallbacks(new android.content.ComponentCallbacks() {
                @Override
                public void onConfigurationChanged(android.content.res.Configuration newConfig) {
                    reassertGuestResources(activity);
                }

                @Override
                public void onLowMemory() {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static void reassertGuestResources(Activity activity) {
        if (activity == null) return;
        GuestContext gc;
        synchronized (RESOURCES_INJECT_LOCK) {
            gc = BOUND_GUEST_CONTEXTS.get(activity);
        }
        if (gc == null && activity.getBaseContext() instanceof GuestContext) {
            gc = (GuestContext) activity.getBaseContext();
        }
        if (gc == null) return;
        boolean activityOk = activity.getResources() instanceof GuestResourcesWrapper;
        boolean appOk = activity.getApplicationContext() != null
                && activity.getApplicationContext().getResources() instanceof GuestResourcesWrapper;
        if (!activityOk || !appOk) {
            injectGuestResourcesMultiLayer(activity, gc, gc.getHostBaseContext());
        }
    }

    public static void restoreHostResourcesIfIdle() {
        synchronized (RESOURCES_INJECT_LOCK) {
            if (SAVED_SHARED_RESOURCES.isEmpty()) return;
            for (java.util.Map.Entry<Object, java.util.Map<java.lang.reflect.Field, android.content.res.Resources>> entry :
                    SAVED_SHARED_RESOURCES.entrySet()) {
                Object target = entry.getKey();
                java.util.Map<java.lang.reflect.Field, android.content.res.Resources> fields = entry.getValue();
                if (target == null || fields == null) continue;
                for (java.util.Map.Entry<java.lang.reflect.Field, android.content.res.Resources> fe :
                        fields.entrySet()) {
                    try {
                        java.lang.reflect.Field f = fe.getKey();
                        android.content.res.Resources orig = fe.getValue();
                        if (f != null && orig != null) {
                            f.setAccessible(true);
                            f.set(target, orig);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            SAVED_SHARED_RESOURCES.clear();
        }
        Log.i(TAG, "restored host LoadedApk/Application original Resources after guest exit");
    }

    public static void attachGuestContext(Activity guest, Context ctx) {
        final File gameDir = guestGameDir;
        if (gameDir == null) return;
        try {
            GuestContext guestContext = getOrCreateGuestContext(
                    ctx.getApplicationContext(),
                    gameDir,
                    guestPackage,
                    guestClass,
                    guestNative64,
                    guestLoader);

            injectGuestResourcesMultiLayer(guest, guestContext, ctx);

            Log.i(TAG, "multi-layer guest context + Resources/PM/Application injected (files="
                    + guestContext.getFilesDir() + ", obb=" + guestContext.getObbDir() + ")");
            guestContext.mergeInto(guest.getAssets());
        } catch (Throwable t) {
            Log.w(TAG, "guest context injection failed: " + t);
        }
    }

    public static void attachOverlayAndRefreshHooks(Activity guest, Context hostCtx) {
        if (guest == null) return;
        SURFACE_QUEUE.execute(EglSwapHook::refreshNativeHooks);

        if (guestGameDir != null && hostCtx != null) {
            GraphicsSettings settings = GraphicsSettings.forGame(hostCtx, guestGameDir);
            applyDisplayFrameRate(guest, settings);
            InGameOverlay.attach(guest, hostCtx, guestGameDir, guestPackage, guestClass);
        }

        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(() -> SURFACE_QUEUE.execute(EglSwapHook::refreshNativeHooks), 800);
        h.postDelayed(() -> SURFACE_QUEUE.execute(EglSwapHook::refreshNativeHooks), 2500);
    }

    public static void applyDisplayFrameRate(Activity activity, GraphicsSettings settings) {
        if (activity == null || settings == null) return;
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            WindowManager.LayoutParams lp = window.getAttributes();
            if (Build.VERSION.SDK_INT >= 23 && activity.getWindowManager() != null) {
                Display display = activity.getWindowManager().getDefaultDisplay();
                if (display != null) {
                    Display.Mode[] modes = display.getSupportedModes();
                    if (modes != null && modes.length > 0) {
                        Display.Mode fastest = modes[0];
                        for (Display.Mode m : modes) {
                            if (m.getRefreshRate() > fastest.getRefreshRate()) {
                                fastest = m;
                            }
                        }
                        if (settings.isUncapped()
                                || settings.frameGen != GraphicsSettings.FRAME_GEN_OFF) {
                            lp.preferredDisplayModeId = fastest.getModeId();
                            if (Build.VERSION.SDK_INT >= 30) {
                                lp.preferredRefreshRate = settings.isUncapped()
                                        ? 0f : fastest.getRefreshRate();
                            }
                            window.setAttributes(lp);
                            Log.i(TAG, "display refresh unlocked: mode=" + fastest.getModeId()
                                    + " (" + fastest.getRefreshRate() + " Hz, uncapped="
                                    + settings.isUncapped() + ")");
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "display refresh rate hint skipped: " + t);
        }
    }

    private static void openNativeSession(Context ctx, File gameDir, Game game,
                                          String pkg, ClassLoader loader) {
        if (gameDir.getAbsolutePath().equals(sessionGameDir)
                && UnicornBridge.isSessionActive()) {
            Log.i(TAG, "native session already live for " + pkg);
            return;
        }
        if (UnicornBridge.isSessionActive()) {
            Log.i(TAG, "closing previous guest session before " + pkg);
            UnicornBridge.endSession();
            sessionGameDir = null;
        }
        if (!UnicornBridge.isCoreLoaded()) {
            if (UnicornBridge.isPassthrough()) {
                Log.i(TAG, "32-bit host: guest payload runs natively");
            } else {
                Log.e(TAG, "native core unavailable on this device");
            }
            return;
        }

        File runtime = RuntimeAssets.ensure(ctx);
        UnicornBridge.setRuntimeSysroot(runtime.getAbsolutePath());
        if (game != null) {
            UnicornBridge.setNativeActivityInfo(game.funcName, game.libName);
        }

        String obb = findObb(gameDir);
        int result = UnicornBridge.startSession(gameDir.getAbsolutePath(), obb, pkg,
                loader, null);
        sessionGameDir = gameDir.getAbsolutePath();
        Log.i(TAG, "native session start -> " + result);
    }

    public static void probeInput(Activity guest) {
        Window window = guest.getWindow();
        if (window == null) return;
        final View decor = window.getDecorView();
        if (!(decor instanceof ViewGroup)) return;

        View probe = new View(guest) {
            @Override
            public boolean onTouchEvent(android.view.MotionEvent event) {
                return false;
            }
        };
        probe.setBackgroundColor(Color.TRANSPARENT);
        probe.setClickable(false);
        probe.setFocusable(false);
        probe.setFocusableInTouchMode(false);
        probe.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ((ViewGroup) decor).addView(probe, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * NativeActivity subclasses that swap out the native surface for a Java
     * {@link SurfaceView} (such as Unity 4.x {@code UnityPlayerNativeActivity})
     * rely on Java view input dispatch ({@code onTouchEvent} / {@code injectEvent})
     * rather than an unpolled NDK {@code AInputQueue}. Releasing
     * {@code takeInputQueue(null)} before {@code ViewRootImpl.setView()} keeps
     * {@code NativePostImeInputStage} from buffering events into an ANR while
     * letting both {@link InGameOverlay} and the game view receive touches cleanly.
     */
    public static void releaseInputQueueIfViewHosted(Activity guest) {
        if (!(guest instanceof android.app.NativeActivity)) return;
        Window window = guest.getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (findSurfaceView(decor) != null) {
            window.takeInputQueue(null);
            Log.i(TAG, "released NativeActivity InputQueue for Java SurfaceView host: "
                    + guest.getClass().getName());
        }
    }

    public static void hookSurface(Activity guest) {
        if (guestNative64) {
            EglSwapHook.refreshNativeHooks();
            return;
        }
        final Window window = guest.getWindow();
        if (window == null) return;
        final View decor = window.getDecorView();
        final Handler handler = new Handler(Looper.getMainLooper());
        final int[] attempts = {0};

        final Runnable scan = new Runnable() {
            @Override
            public void run() {
                SurfaceView view = findSurfaceView(decor);
                if (view == null) {
                    if (++attempts[0] < 60) {
                        handler.postDelayed(this, 100);
                    } else {
                        Log.w(TAG, "no SurfaceView in the guest view tree; "
                                + "native window stays detached");
                    }
                    return;
                }
                final String name = view.getClass().getName();
                view.getHolder().addCallback(new SurfaceHolder.Callback() {
                    @Override
                    public void surfaceCreated(SurfaceHolder holder) {
                        Log.i(TAG, "guest " + name + " surface created");
                        dispatchSurface(holder.getSurface(),
                                holder.getSurfaceFrame().width(),
                                holder.getSurfaceFrame().height());
                    }

                    @Override
                    public void surfaceChanged(SurfaceHolder holder, int format,
                                               int width, int height) {
                        Log.i(TAG, "guest surface " + width + "x" + height);
                        dispatchSurface(holder.getSurface(), width, height);
                    }

                    @Override
                    public void surfaceDestroyed(SurfaceHolder holder) {
                        SURFACE_QUEUE.execute(() -> UnicornBridge.setSurface(null));
                    }
                });
                Log.i(TAG, "surface holder hooked on " + name);
            }
        };
        handler.post(scan);
    }

    private static void dispatchSurface(final Surface surface, final int width,
                                        final int height) {
        if (surface == null || !surface.isValid()) return;
        SURFACE_QUEUE.execute(() -> {
            Log.i(TAG, "surface handed to the core off the UI thread: "
                    + width + "x" + height);
            UnicornBridge.setSurface(surface);
        });
    }

    private static SurfaceView findSurfaceView(View root) {
        if (root instanceof SurfaceView) return (SurfaceView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); ++i) {
                SurfaceView found = findSurfaceView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void unpackBundledAssets(File apk, File gameDir) {
        final File data = new File(gameDir, "data");
        final File stamp = new File(data, ".zai-assets-unpacked");
        if (stamp.isFile()) return;
        if (!data.isDirectory() && !data.mkdirs()) {
            Log.w(TAG, "asset unpack: cannot create " + data);
            return;
        }

        try (ZipFile apkZip = new ZipFile(apk)) {
            final ZipEntry bundle = apkZip.getEntry("assets/files.zip");
            if (bundle == null) return;

            int files = 0;
            try (InputStream raw = apkZip.getInputStream(bundle);
                 ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                byte[] buf = new byte[64 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    final String name = entry.getName();
                    final File out = new File(data, name);
                    if (!out.getCanonicalPath().startsWith(data.getCanonicalPath() + File.separator)) {
                        Log.w(TAG, "asset unpack: rejected " + name);
                        continue;
                    }
                    if (entry.isDirectory()) {
                        out.mkdirs();
                        continue;
                    }
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) continue;
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = zip.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    ++files;
                }
            }
            if (files > 0) {
                new FileOutputStream(stamp).close();
                Log.i(TAG, "asset bundle unpacked: " + files + " files into " + data);
            }
        } catch (Throwable t) {
            Log.e(TAG, "asset bundle unpack failed", t);
        }
    }

    private static boolean hasNativePayload(File payload) {
        if (!payload.isDirectory()) return false;
        File[] libs = payload.listFiles((dir, name) -> name.endsWith("32.so"));
        return libs != null && libs.length > 0;
    }

    private static String findObb(File gameDir) {
        File[] files = new File(gameDir, "obb").listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    public static String describe() {
        return guestClass == null ? "<none>"
                : guestClass + " (" + guestPackage + ")";
    }

    public static ClassLoader getGuestLoader() {
        return guestLoader;
    }

    public static String getGuestClass() {
        return guestClass;
    }

    public static String getGuestPackage() {
        return guestPackage;
    }

    public static String getGameDir() {
        return guestGameDir == null ? null : guestGameDir.getAbsolutePath();
    }

    public static boolean isCurrentGuestNative64() {
        return guestNative64;
    }
}

package com.ziauddin.zaibridge;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Looper;
import android.test.InstrumentationTestCase;
import android.view.ContextThemeWrapper;

import com.ziauddin.zaibridge.core.BinaryXmlManifestReader;
import com.ziauddin.zaibridge.core.GuestApplication;
import com.ziauddin.zaibridge.core.GuestContext;
import com.ziauddin.zaibridge.core.GuestLauncher;
import com.ziauddin.zaibridge.core.GuestPackageManagerWrapper;
import com.ziauddin.zaibridge.core.GuestResourcesWrapper;
import com.ziauddin.zaibridge.core.SoCProfiler;
import com.ziauddin.zaibridge.proxy.ProxyGameActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * On-device runtime verification for Zai-Bridge v3.2.1 fixes:
 * <ul>
 *   <li>Phase 1: GuestResourcesWrapper (zero-ID / #0x0 non-throwing across all overloads,
 *       Unity fallback strings, collision-safe host fallback), OEM OverrideResources multi-layer
 *       injection on non-proxy Activity + Application, Theme.ZaiBridge.GameHost AppCompat support,
 *       GuestPackageManagerWrapper metadata interception, BinaryXmlManifestReader alias/class
 *       normalization, GuestContext OpPackageName/storage/Application virtualization.</li>
 *   <li>Phase 2: Live GL_RENDERER priority, shared die-ID clock disambiguation (MT6855),
 *       nominal RAM snapping, and PowerVR BXM-8-256 SoC classification.</li>
 * </ul>
 */
public class BridgeFixesVerificationTest extends InstrumentationTestCase {

    private Context targetContext() {
        return getInstrumentation().getTargetContext();
    }

    @SuppressWarnings("deprecation")
    private static final class FakeOemOverrideResources extends Resources {
        FakeOemOverrideResources(Resources base) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
        }

        @Override
        public CharSequence getText(int id) throws NotFoundException {
            if (id == 0) {
                throw new NotFoundException("String resource ID #0x0 (simulated OEM OverrideResources)");
            }
            return super.getText(id);
        }

        @Override
        public String getString(int id) throws NotFoundException {
            if (id == 0) {
                throw new NotFoundException("String resource ID #0x0 (simulated OEM OverrideResources)");
            }
            return super.getString(id);
        }
    }

    public void testGuestResourcesWrapperZeroIdAndUnityFallbackStrings() {
        Context host = targetContext();
        Resources hostRes = host.getResources();
        GuestResourcesWrapper wrapper = new GuestResourcesWrapper(
                hostRes, hostRes, "com.dts.freefiremax", host.getPackageName());

        // Critical regression check: ID 0 must never throw Resources$NotFoundException #0x0
        assertEquals("Game view", wrapper.getString(0));
        assertEquals("Game view", wrapper.getString(0, "fmt"));
        assertEquals("Game view", wrapper.getText(0).toString());
        assertEquals("fallback-val", wrapper.getText(0, "fallback-val").toString());
        assertEquals("Game view", wrapper.getQuantityString(0, 1));
        assertEquals("Game view", wrapper.getQuantityText(0, 5).toString());
        assertFalse(wrapper.getBoolean(0));
        assertEquals(0, wrapper.getInteger(0));
        assertEquals(0f, wrapper.getFloat(0), 0.001f);
        assertEquals(0, wrapper.getColor(0));
        assertNull(wrapper.getDrawable(0));
        assertNull(wrapper.getLayout(0));
        assertNull(wrapper.getXml(0));
        assertEquals(0, wrapper.getStringArray(0).length);

        // Mandatory Unity runtime strings resolved cleanly for any guest package name
        int descId = wrapper.getIdentifier("game_view_content_description", "string", "com.dts.freefiremax");
        assertTrue("game_view_content_description must resolve", descId != 0);
        assertEquals("Game view", wrapper.getString(descId));

        String[][] expected = {
                {"FreeformWindowOrientation_landscape", "landscape"},
                {"FreeformWindowOrientation_portrait", "portrait"},
                {"FreeformWindowSize_maximize", "maximize"},
                {"FreeformWindowSize_phone", "phone"},
                {"FreeformWindowSize_tablet", "tablet"}
        };
        for (String[] pair : expected) {
            int id = wrapper.getIdentifier(pair[0], "string", "com.kolka.boostrider");
            assertTrue("missing string fallback: " + pair[0], id != 0);
            assertEquals(pair[1], wrapper.getString(id));
        }
    }

    public void testOemOverrideResourcesMultiLayerInjection() throws Throwable {
        final Context host = targetContext();
        final Resources oemOverrideRes = new FakeOemOverrideResources(host.getResources());
        final File cacheGameDir = new File(host.getCacheDir(), "oem-test-game");
        cacheGameDir.mkdirs();

        final GuestContext guestCtx = new GuestContext(
                host,
                cacheGameDir,
                "com.kolka.boostrider",
                "com.unity3d.player.UnityPlayerGameActivity",
                true,
                host.getClassLoader());

        final Activity[] holder = new Activity[1];
        runTestOnUiThread(() -> {
            Activity simulatedUnityGuest = new Activity() {
                // Non-proxy Activity simulating UnityPlayerActivity instantiated by BridgeInstrumentation.
            };
            // Attach a fake OEM wrapper chain holding FakeOemOverrideResources
            final Context oemBase = new ContextWrapper(host) {
                @SuppressWarnings("unused")
                private Resources mOverrideResources = oemOverrideRes;
                @Override
                public Resources getResources() {
                    return mOverrideResources;
                }
            };
            try {
                java.lang.reflect.Field fBase = ContextWrapper.class.getDeclaredField("mBase");
                fBase.setAccessible(true);
                fBase.set(simulatedUnityGuest, oemBase);

                java.lang.reflect.Field fRes = ContextThemeWrapper.class.getDeclaredField("mResources");
                fRes.setAccessible(true);
                fRes.set(simulatedUnityGuest, oemOverrideRes);
            } catch (Throwable t) {
                fail("Failed setting up simulated OEM Activity: " + t);
            }

            GuestLauncher.injectGuestResourcesMultiLayer(simulatedUnityGuest, guestCtx, host);
            holder[0] = simulatedUnityGuest;
        });

        Activity guest = holder[0];
        assertNotNull(guest);
        assertTrue("Activity.getResources() must be GuestResourcesWrapper",
                guest.getResources() instanceof GuestResourcesWrapper);
        assertTrue("ApplicationContext.getResources() must be GuestResourcesWrapper",
                guest.getApplicationContext().getResources() instanceof GuestResourcesWrapper);
        assertEquals("Game view", guest.getResources().getString(0));
        int resId = guest.getResources().getIdentifier(
                "game_view_content_description", "string", "com.kolka.boostrider");
        assertTrue(resId != 0);
        assertEquals("Game view", guest.getResources().getString(resId));
        assertSame(guestCtx.getGuestApplication(), guest.getApplication());
        GuestLauncher.restoreHostResourcesIfIdle();
    }

    public void testGameHostThemeSupportsAppCompatAndMaterial() {
        Context host = targetContext();
        ContextThemeWrapper themed = new ContextThemeWrapper(host, R.style.Theme_ZaiBridge_GameHost);
        TypedArray a = themed.obtainStyledAttributes(
                new int[]{androidx.appcompat.R.attr.windowActionBar, androidx.appcompat.R.attr.windowNoTitle});
        try {
            assertTrue("Theme.ZaiBridge.GameHost must define androidx.appcompat.R.attr.windowActionBar",
                    a.hasValue(0));
            assertFalse("windowActionBar must be false", a.getBoolean(0, true));
            assertTrue("windowNoTitle must be true", a.getBoolean(1, false));
        } finally {
            a.recycle();
        }
    }

    public void testGuestContextPackageManagerAndBinaryManifest() {
        Context host = targetContext();
        File hellDir = new File(host.getFilesDir(), "games/com.feelingtouch.hellzombie");
        assertTrue("expected staged hellzombie dir or create temp test dir",
                hellDir.isDirectory() || new File(host.getCacheDir(), "tmp-test-game").mkdirs());

        File gameDir = hellDir.isDirectory()
                ? hellDir
                : new File(host.getCacheDir(), "tmp-test-game");

        GuestContext guestCtx = new GuestContext(
                host,
                gameDir,
                "com.feelingtouch.hellzombie",
                "com.feelingtouch.hellzombie.UnityPlayerActivity",
                false,
                host.getClassLoader());

        assertEquals("com.feelingtouch.hellzombie", guestCtx.getPackageName());
        assertEquals(host.getPackageName(), guestCtx.getOpPackageName());
        assertTrue(guestCtx.getApplicationContext() instanceof GuestApplication);
        assertTrue(guestCtx.getResources() instanceof GuestResourcesWrapper);
        assertTrue(guestCtx.getPackageManager() instanceof GuestPackageManagerWrapper);

        assertTrue(guestCtx.getFilesDir().isDirectory());
        assertTrue(guestCtx.getCacheDir().isDirectory());
        assertTrue(guestCtx.getObbDir().isDirectory());

        PackageManager pm = guestCtx.getPackageManager();
        ComponentName proxyCn = new ComponentName(
                host.getPackageName(), ProxyGameActivity.class.getName());
        try {
            ActivityInfo ai = pm.getActivityInfo(proxyCn, PackageManager.GET_META_DATA);
            assertNotNull(ai);
            assertEquals("com.feelingtouch.hellzombie", ai.packageName);
            assertNotNull(ai.metaData);
            if (Build.VERSION.SDK_INT >= 33) {
                ActivityInfo ai33 = pm.getActivityInfo(
                        proxyCn, PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA));
                assertNotNull(ai33);
                assertEquals("com.feelingtouch.hellzombie", ai33.packageName);
            }
            ApplicationInfo appInfo = pm.getApplicationInfo("com.feelingtouch.hellzombie", 0);
            assertNotNull(appInfo);
            assertEquals("com.feelingtouch.hellzombie", appInfo.packageName);
        } catch (PackageManager.NameNotFoundException e) {
            fail("GuestPackageManagerWrapper threw NameNotFoundException: " + e.getMessage());
        }

        // Verify BinaryXmlManifestReader normalization + alias resolution
        BinaryXmlManifestReader.ParsedManifest parsed = new BinaryXmlManifestReader.ParsedManifest();
        parsed.packageName = "com.example.game";
        parsed.aliasTargets.put("com.example.game.LaunchAlias", "com.example.game.MainUnityActivity");
        assertEquals("com.example.game.RelativeActivity",
                BinaryXmlManifestReader.normalizeClassName("com.example.game", ".RelativeActivity"));
        assertEquals("com.example.game.MainUnityActivity",
                parsed.resolveActivityTarget(".LaunchAlias"));
    }

    public void testInstalledFastPathBindingOnExistingPackage() {
        Context host = targetContext();
        // Test installed-package fast-path against a genuinely installed package (self host).
        GuestContext gc = new GuestContext(
                host,
                new File(host.getCacheDir(), "self-test-game"),
                host.getPackageName(),
                MainActivity.class.getName(),
                true,
                host.getClassLoader());
        assertNotNull(gc.getResources());
        assertTrue(gc.getResources() instanceof GuestResourcesWrapper);
        int id = gc.getResources().getIdentifier(
                "game_view_content_description", "string", host.getPackageName());
        assertTrue(id != 0);
        assertEquals("Game view", gc.getResources().getString(id));
    }

    public void testGuestLauncherRecordsDetailedErrorInsteadOfSilentNull() {
        Context host = targetContext();
        Intent badIntent = new Intent();
        badIntent.putExtra(GuestLauncher.EXTRA_GAME_DIR,
                new File(host.getCacheDir(), "missing_game_dir_123").getAbsolutePath());
        assertNull(GuestLauncher.createGuest(host, badIntent));
        String err = GuestLauncher.getLastLaunchError();
        assertNotNull(err);
        assertTrue("Expected detailed incomplete import message, got: " + err,
                err.contains("game.apk") || err.contains("Incomplete"));
    }

    public void testSocDatabaseAndProfilerPowerVrBxm8() throws Exception {
        Context host = targetContext();
        byte[] buf = new byte[8192];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream is = host.getAssets().open("soc_database.json")) {
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        }
        String jsonStr = new String(out.toByteArray(), StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(jsonStr);

        boolean foundBxmGpu = false;
        JSONArray gpus = root.getJSONArray("gpus");
        for (int i = 0; i < gpus.length(); i++) {
            String row = gpus.getJSONObject(i).toString().toLowerCase();
            if (row.contains("bxm-8-256") || row.contains("powervr b-series")) {
                foundBxmGpu = true;
                break;
            }
        }
        assertTrue("soc_database.json must classify PowerVR B-Series / BXM-8-256", foundBxmGpu);

        boolean foundMt6855PowerVr = false;
        JSONArray socs = root.getJSONArray("socs");
        for (int i = 0; i < socs.length(); i++) {
            JSONObject s = socs.getJSONObject(i);
            String fam = s.optString("family");
            if ("Dimensity 7025".equals(fam) || "Dimensity 7020".equals(fam)
                    || "Dimensity 7025 Ultra".equals(fam) || "Dimensity 930".equals(fam)) {
                assertTrue(fam + " must use PowerVR IMG BXM-8-256",
                        "PowerVR IMG BXM-8-256".equals(s.optString("gpu")));
                if ("Dimensity 7025".equals(fam)) foundMt6855PowerVr = true;
            }
        }
        assertTrue("Dimensity 7025 / MT6855 must map to PowerVR IMG BXM-8-256", foundMt6855PowerVr);

        // Verify shared die-ID clock disambiguation for MT6855
        String[] d7025 = SoCProfiler.resolveSharedDieFamily("mt6855 powervr b-series bxm-8-256", 2_500_000L, null, null, null);
        assertEquals("Dimensity 7025", d7025[0]);
        assertEquals("PowerVR IMG BXM-8-256", d7025[2]);

        String[] d7020 = SoCProfiler.resolveSharedDieFamily("mt6855 powervr b-series bxm-8-256", 2_200_000L, null, null, null);
        assertEquals("Dimensity 7020", d7020[0]);
        assertEquals("PowerVR IMG BXM-8-256", d7020[2]);

        // Verify nominal RAM snapping (handles kernel reserve offsets cleanly)
        final long gb = 1024L * 1024L * 1024L;
        assertEquals(8, SoCProfiler.snapNominalRamGb((long) (7.4 * gb)));
        assertEquals(12, SoCProfiler.snapNominalRamGb((long) (11.2 * gb)));
        assertEquals(16, SoCProfiler.snapNominalRamGb((long) (15.1 * gb)));
        assertEquals(6, SoCProfiler.snapNominalRamGb((long) (5.6 * gb)));
        assertEquals(4, SoCProfiler.snapNominalRamGb((long) (3.7 * gb)));

        assertFalse("Instrumentation thread is distinct from main looper",
                Looper.getMainLooper().getThread() == Thread.currentThread());
        SoCProfiler.Profile prof = SoCProfiler.get(host);
        assertNotNull(prof);
        assertTrue(prof.tier >= 1 && prof.tier <= 5);
        assertTrue(prof.ramGb >= 1);

        com.ziauddin.zaibridge.data.SoCProfiler.Profile dataProf =
                com.ziauddin.zaibridge.data.SoCProfiler.probe(host);
        assertNotNull(dataProf);
        assertEquals(prof.gpuName, dataProf.gpu);
        assertEquals(prof.ramGb, dataProf.ramGb);
    }

    public void testBinaryManifestResolvesIntegerReferenceInsteadOfRawId() {
        Context testPkgCtx = getInstrumentation().getContext();
        String testApkPath = testPkgCtx.getPackageCodePath();
        BinaryXmlManifestReader.ParsedManifest manifest =
                BinaryXmlManifestReader.readFromApk(testApkPath);
        assertNotNull(manifest.applicationMetaData);
        assertTrue("Manifest must include resolved GMS integer meta-data",
                manifest.applicationMetaData.containsKey("com.google.android.gms.version"));
        int resolved = manifest.applicationMetaData.getInt("com.google.android.gms.version", -1);
        assertEquals("Expected @integer/test_gms_version_ref to resolve to 12451000, not raw 0x7F...... resource ID",
                12451000, resolved);
        assertTrue("Resolved value must never look like an unresolved 0x7F resource ID",
                (resolved & 0xff000000) != 0x7f000000);
    }

    public void testProxyGameActivityRunsInIsolatedGuestProcess() throws Exception {
        Context host = targetContext();
        PackageManager pm = host.getPackageManager();
        ActivityInfo proxyInfo = pm.getActivityInfo(
                new ComponentName(host, ProxyGameActivity.class), 0);
        ActivityInfo hostActInfo = pm.getActivityInfo(
                new ComponentName(host, GameHostActivity.class), 0);
        ActivityInfo mainInfo = pm.getActivityInfo(
                new ComponentName(host, MainActivity.class), 0);
        ActivityInfo crashInfo = pm.getActivityInfo(
                new ComponentName(host, com.ziauddin.zaibridge.ui.CrashActivity.class), 0);

        assertTrue("ProxyGameActivity must run in :guest process, got: " + proxyInfo.processName,
                proxyInfo.processName != null && proxyInfo.processName.endsWith(":guest"));
        assertTrue("GameHostActivity must run in :guest process, got: " + hostActInfo.processName,
                hostActInfo.processName != null && hostActInfo.processName.endsWith(":guest"));
        assertFalse("MainActivity must stay in main process",
                mainInfo.processName != null && mainInfo.processName.endsWith(":guest"));
        assertFalse("CrashActivity must stay in main process",
                crashInfo.processName != null && crashInfo.processName.endsWith(":guest"));
        assertFalse("Main launcher test process must not be treated as :guest",
                com.ziauddin.zaibridge.core.BridgeApplication.isGuestProcess(host));
    }
}

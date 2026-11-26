package com.ziauddin.zaibridge;

import android.content.ComponentName;
import android.content.Context;
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
 * On-device runtime verification for Zai-Bridge fixes:
 * <ul>
 *   <li>Phase 1: GuestResourcesWrapper (zero-ID / #0x0 non-throwing, Unity fallback strings,
 *       collision-safe host fallback), Theme.ZaiBridge.GameHost AppCompat compatibility,
 *       GuestPackageManagerWrapper metadata interception, BinaryXmlManifestReader alias/class
 *       normalization, GuestContext OpPackageName/storage/Application virtualization.</li>
 *   <li>Phase 2: Non-blocking SoCProfiler & ProxyGameActivity error reporting.</li>
 *   <li>Phase 3: PowerVR BXM-8-256 / MT6855 SoC classification entries.</li>
 * </ul>
 */
public class BridgeFixesVerificationTest extends InstrumentationTestCase {

    private Context targetContext() {
        return getInstrumentation().getTargetContext();
    }

    public void testGuestResourcesWrapperZeroIdAndUnityFallbackStrings() {
        Context host = targetContext();
        Resources hostRes = host.getResources();
        GuestResourcesWrapper wrapper = new GuestResourcesWrapper(
                hostRes, hostRes, "com.dts.freefiremax", host.getPackageName());

        // Critical regression check: ID 0 must never throw Resources$NotFoundException #0x0
        assertEquals("", wrapper.getString(0));
        assertEquals("", wrapper.getString(0, "fmt"));
        assertEquals("", wrapper.getText(0).toString());
        assertEquals("fallback-val", wrapper.getText(0, "fallback-val").toString());
        assertEquals("", wrapper.getQuantityString(0, 1));
        assertEquals("", wrapper.getQuantityText(0, 5).toString());

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
            if ("Dimensity 7025".equals(s.optString("family"))) {
                assertTrue(s.optString("gpu").contains("PowerVR") && s.optString("gpu").contains("BXM"));
                foundMt6855PowerVr = true;
            }
        }
        assertTrue("Dimensity 7025 / MT6855 must map to PowerVR B-Series BXM-8-256", foundMt6855PowerVr);

        assertFalse("Instrumentation thread is distinct from main looper",
                Looper.getMainLooper().getThread() == Thread.currentThread());
        SoCProfiler.Profile prof = SoCProfiler.get(host);
        assertNotNull(prof);
        assertTrue(prof.tier >= 1 && prof.tier <= 5);
    }
}

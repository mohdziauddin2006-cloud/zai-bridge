package com.ziauddin.zaibridge.data;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.ziauddin.zaibridge.core.EglSwapHook;

/**
 * Hardware classification for the auto-recommender.
 *
 * <p>Every smartphone SoC family the bridge knows about lives in
 * {@code assets/soc_database.json}: Snapdragon 200 through 8 Elite Gen 6,
 * MediaTek MT65xx/Helio/Dimensity, Exynos, Tensor G1-G5, Kirin, Xiaomi XRING
 * O1, Unisoc/Spreadtrum and Tegra/Atom. A device is matched on its SoC model,
 * board and hardware strings <em>plus</em> the live {@code GL_RENDERER}, RAM and
 * CPU core count, then placed in one of five performance tiers.
 *
 * <p>The tier only ever produces a <em>recommendation</em>. Berserk Mode
 * bypasses it entirely: any device can be forced to God-Level Graphics.
 */
public final class SoCProfiler {

    private static final String TAG = "ZaiBridge";
    private static final String ASSET = "soc_database.json";

    public static final int TIER_ENTRY = 1;
    public static final int TIER_MAINSTREAM = 2;
    public static final int TIER_PERFORMANCE = 3;
    public static final int TIER_FLAGSHIP = 4;
    public static final int TIER_ULTRA = 5;

    /** One classification result. */
    public static final class Profile {
        public final String family;
        public final String maker;
        public final String gpu;
        public final int tier;
        public final String tierName;
        public final int ramGb;
        public final int cores;
        public final String renderer;
        public final String socModel;
        /** False when no database entry matched and the tier came from RAM/cores. */
        public final boolean matched;

        Profile(String family, String maker, String gpu, int tier, String tierName,
                int ramGb, int cores, String renderer, String socModel, boolean matched) {
            this.family = family;
            this.maker = maker;
            this.gpu = gpu;
            this.tier = tier;
            this.tierName = tierName;
            this.ramGb = ramGb;
            this.cores = cores;
            this.renderer = renderer;
            this.socModel = socModel;
            this.matched = matched;
        }

        /** Compact line for the settings sheet, e.g. "Tier 5 · Snapdragon 8 Elite". */
        public String shortLabel() {
            return tierName + " · " + family;
        }

        public String detail() {
            StringBuilder out = new StringBuilder();
            out.append(family);
            if (!maker.isEmpty()) out.append(" (").append(maker).append(')');
            out.append("\nGPU ").append(gpu.isEmpty() ? renderer : gpu);
            out.append("\n").append(ramGb).append(" GB RAM · ").append(cores).append(" cores");
            if (!socModel.isEmpty()) out.append("\nSoC ").append(socModel);
            if (!matched) out.append("\n(tier inferred from memory and cores)");
            return out.toString();
        }
    }

    private static final class Entry {
        final String family;
        final String maker;
        final String gpu;
        final int tier;
        final String[] match;

        Entry(String family, String maker, String gpu, int tier, String[] match) {
            this.family = family;
            this.maker = maker;
            this.gpu = gpu;
            this.tier = tier;
            this.match = match;
        }

        int score(String haystack) {
            int best = 0;
            for (String token : match) {
                if (!token.isEmpty() && haystack.contains(token) && token.length() > best) {
                    best = token.length();
                }
            }
            return best;
        }
    }

    private static volatile List<Entry> sSocs;
    private static volatile List<Entry> sGpus;
    private static volatile Profile sCached;

    private SoCProfiler() {
    }

    /** Classifies this device once per process and caches the result. */
    public static Profile probe(Context context) {
        Profile cached = sCached;
        if (cached != null) return cached;

        loadDatabase(context.getAssets());

        // Build.SOC_MODEL is API 31+; the field does not exist on older
        // releases, so it may only be touched behind the version check.
        final String socModel = Build.VERSION.SDK_INT >= 31 ? safe(Build.SOC_MODEL) : "";
        final String hardware = safe(Build.HARDWARE);
        final String board = safe(Build.BOARD);
        final String device = safe(Build.DEVICE);
        final String manufacturer = safe(Build.MANUFACTURER);
        final String renderer = glRenderer();
        final int ramGb = ramGb(context);
        final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());

        final String haystack = normalize(socModel + " " + hardware + " " + board + " "
                + device + " " + manufacturer + " " + renderer);

        Entry soc = best(sSocs, haystack);
        Entry gpu = best(sGpus, haystack);
        int tier = soc != null ? soc.tier : (gpu != null ? gpu.tier : tierFromHardware(ramGb, cores));

        String family = soc != null ? soc.family
                : (gpu != null && !renderer.isEmpty() ? renderer : hardware);
        String maker = soc != null ? soc.maker : manufacturer;
        String gpuName = soc != null ? soc.gpu : (gpu != null ? renderer : "");

        Profile profile = new Profile(family.isEmpty() ? "Unknown SoC" : family,
                maker, gpuName, tier, tierName(tier), ramGb, cores, renderer, socModel,
                soc != null || gpu != null);
        sCached = profile;
        Log.i(TAG, "SoC profile: " + profile.shortLabel() + " (" + profile.gpu + ", "
                + ramGb + " GB, " + cores + " cores)");
        return profile;
    }

    /** The recommended configuration for a tier. */
    public static GraphicsSettings recommend(Context context, Profile profile) {
        int tier = profile == null ? TIER_MAINSTREAM : profile.tier;
        GraphicsSettings s = new GraphicsSettings();
        switch (tier) {
            case TIER_ULTRA:
                s.upscaler = GraphicsSettings.UPSCALER_DLSS;
                s.scalePercent = GraphicsSettings.SCALE_NATIVE;
                s.sharpnessPercent = 50;
                s.neuralPercent = 70;
                s.frameGen = GraphicsSettings.FRAME_GEN_4X;
                s.temporal = 1;
                s.autoMfg = 1;
                break;
            case TIER_FLAGSHIP:
                s.upscaler = GraphicsSettings.UPSCALER_DLSS;
                s.scalePercent = GraphicsSettings.SCALE_ULTRA_QUALITY;
                s.sharpnessPercent = 45;
                s.neuralPercent = 55;
                s.frameGen = GraphicsSettings.FRAME_GEN_3X;
                s.temporal = 1;
                s.autoMfg = 1;
                break;
            case TIER_PERFORMANCE:
                s.upscaler = GraphicsSettings.UPSCALER_FSR;
                s.scalePercent = GraphicsSettings.SCALE_QUALITY;
                s.sharpnessPercent = 45;
                s.neuralPercent = 35;
                s.frameGen = GraphicsSettings.FRAME_GEN_2X;
                s.temporal = 1;
                s.autoMfg = 1;
                break;
            case TIER_MAINSTREAM:
                s.upscaler = GraphicsSettings.UPSCALER_SGSR;
                s.scalePercent = GraphicsSettings.SCALE_QUALITY;
                s.sharpnessPercent = 45;
                s.neuralPercent = 20;
                s.frameGen = GraphicsSettings.FRAME_GEN_2X;
                s.temporal = 0;
                s.autoMfg = 1;
                break;
            case TIER_ENTRY:
            default:
                s.upscaler = GraphicsSettings.UPSCALER_SGSR;
                s.scalePercent = GraphicsSettings.SCALE_BALANCED;
                s.sharpnessPercent = 50;
                s.neuralPercent = 10;
                s.frameGen = GraphicsSettings.FRAME_GEN_OFF;
                s.temporal = 0;
                s.autoMfg = 0;
                break;
        }
        s.preset = GraphicsSettings.PRESET_CUSTOM;
        return s;
    }

    /** Berserk Mode: God-Level Graphics on any device, hardware tier ignored. */
    public static GraphicsSettings berserk() {
        return GraphicsSettings.ofPreset(GraphicsSettings.PRESET_BERSERK);
    }

    public static String tierName(int tier) {
        switch (tier) {
            case TIER_ULTRA: return "Tier 5: Ultra-Flagship";
            case TIER_FLAGSHIP: return "Tier 4: Flagship";
            case TIER_PERFORMANCE: return "Tier 3: Performance";
            case TIER_MAINSTREAM: return "Tier 2: Mainstream";
            default: return "Tier 1: Entry";
        }
    }

    // ------------------------------------------------------------------
    // Probing
    // ------------------------------------------------------------------

    /**
     * GL_RENDERER is only readable with a current context. The native core
     * caches the driver string at the first swap, so that is the second source.
     */
    private static String glRenderer() {
        String renderer = "";
        try {
            renderer = safe(GLES20.glGetString(GLES20.GL_RENDERER));
        } catch (Throwable ignored) {
            // No current EGL context: the native probe below may still know it.
        }
        if (renderer.isEmpty()) {
            // nativeGraphicsInfo() formats "gpu=<renderer> / <vendor> / <version> cpu=…".
            String info = EglSwapHook.graphicsInfo();
            if (info != null && info.startsWith("gpu=")) {
                String gpu = info.substring(4);
                int cut = gpu.indexOf(" cpu=");
                if (cut > 0) gpu = gpu.substring(0, cut);
                int separator = gpu.indexOf(" / ");
                if (separator > 0) gpu = gpu.substring(0, separator);
                renderer = gpu.trim();
            }
        }
        return renderer;
    }

    private static int ramGb(Context context) {
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return 0;
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return (int) Math.round(info.totalMem / (1024.0 * 1024.0 * 1024.0));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int tierFromHardware(int ramGb, int cores) {
        if (ramGb >= 12 || (ramGb >= 8 && cores >= 8)) return TIER_ULTRA;
        if (ramGb >= 8 || cores >= 8) return TIER_FLAGSHIP;
        if (ramGb >= 6 || cores >= 6) return TIER_PERFORMANCE;
        if (ramGb >= 4) return TIER_MAINSTREAM;
        return TIER_ENTRY;
    }

    // ------------------------------------------------------------------
    // Database
    // ------------------------------------------------------------------

    private static void loadDatabase(AssetManager assets) {
        if (sSocs != null && sGpus != null) return;
        List<Entry> socs = new ArrayList<>();
        List<Entry> gpus = new ArrayList<>();
        try (InputStream in = assets.open(ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            JSONObject root = new JSONObject(out.toString("UTF-8"));
            parse(root.optJSONArray("socs"), socs);
            parse(root.optJSONArray("gpus"), gpus);
        } catch (Exception e) {
            Log.w(TAG, "SoC database unavailable; using memory/cores heuristic", e);
        }
        sGpus = gpus;
        sSocs = socs;
    }

    private static void parse(JSONArray array, List<Entry> out) {
        if (array == null) return;
        for (int i = 0; i < array.length(); ++i) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            JSONArray matchArray = o.optJSONArray("match");
            if (matchArray == null) continue;
            List<String> tokens = new ArrayList<>();
            for (int m = 0; m < matchArray.length(); ++m) {
                String token = normalize(matchArray.optString(m));
                if (!token.isEmpty()) tokens.add(token);
            }
            out.add(new Entry(o.optString("family", ""), o.optString("maker", ""),
                    o.optString("gpu", ""), clampTier(o.optInt("tier", TIER_MAINSTREAM)),
                    tokens.toArray(new String[0])));
        }
    }

    private static Entry best(List<Entry> entries, String haystack) {
        if (entries == null || haystack.isEmpty()) return null;
        Entry winner = null;
        int score = 0;
        for (Entry entry : entries) {
            int candidate = entry.score(haystack);
            if (candidate > score) {
                score = candidate;
                winner = entry;
            }
        }
        return winner;
    }

    private static int clampTier(int tier) {
        return tier < TIER_ENTRY ? TIER_ENTRY : (tier > TIER_ULTRA ? TIER_ULTRA : tier);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.US)
                .replace("(tm)", " ").replace("(r)", " ")
                .replace("processor", " ").replace("technologies", " ")
                .replace("qualcomm", " ").replace("mediatek", " ")
                .replace("samsung", " ").replace("google", " ")
                .replace("hisilicon", " ").replace("unisoc", " ");
        StringBuilder out = new StringBuilder(lower.length());
        boolean space = true;
        for (int i = 0; i < lower.length(); ++i) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.') {
                out.append(c);
                space = false;
            } else if (!space) {
                out.append(' ');
                space = true;
            }
        }
        // Collapse "adreno 830" vs "adreno830" for drivers that omit the space.
        return out.toString().trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

package com.ziauddin.zaibridge.data;

import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * Hardware classification facade for per-game recommended graphics profiles.
 * Delegates live EGL {@code GL_RENDERER}, clock topology, die disambiguation, and
 * nominal RAM probing to {@link com.ziauddin.zaibridge.core.SoCProfiler}.
 */
public final class SoCProfiler {

    private static final String TAG = "ZaiBridge";

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

    private static volatile Profile sCached;

    private SoCProfiler() {
    }

    /** Classifies this device once per process using unified core hardware probing. */
    public static Profile probe(Context context) {
        Profile cached = sCached;
        if (cached != null) return cached;

        com.ziauddin.zaibridge.core.SoCProfiler.Profile core =
                com.ziauddin.zaibridge.core.SoCProfiler.get(context);
        final String socModel = Build.VERSION.SDK_INT >= 31 ? safe(Build.SOC_MODEL) : "";
        Profile profile = new Profile(
                core.socName,
                core.vendor,
                core.gpuName,
                clampTier(core.tier),
                core.tierLabel,
                core.ramGb,
                core.cpuCores,
                core.gpuName,
                socModel,
                core.matchedFromDb);
        sCached = profile;
        Log.i(TAG, "SoC profile: " + profile.shortLabel() + " (" + profile.gpu + ", "
                + profile.ramGb + " GB, " + profile.cores + " cores)");
        return profile;
    }

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

    private static int clampTier(int tier) {
        return tier < TIER_ENTRY ? TIER_ENTRY : (tier > TIER_ULTRA ? TIER_ULTRA : tier);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

package com.ziauddin.zaibridge.core;

import android.app.ActivityManager;
import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.util.Log;

import com.ziauddin.zaibridge.data.GraphicsSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.util.Locale;

/**
 * Universal Smartphone SoC & GPU Hardware Profiler and Auto-Recommender.
 *
 * <p>Combines the bundled {@code assets/soc_database.json} (covering Qualcomm
 * Snapdragon, MediaTek Helio/Dimensity, Samsung Exynos/Xclipse, Google Tensor,
 * HiSilicon Kirin/Maleoon, Xiaomi XRING O1, Unisoc/Spreadtrum, NVIDIA Tegra,
 * and Intel Atom) with dynamic live hardware probing ({@code GL_RENDERER},
 * CPU core count, peak clock MHz, and total RAM) so every current and future
 * Android device is accurately classified into Tiers 1–5.
 */
public final class SoCProfiler {

    private static final String TAG = "ZaiBridge";

    public static final class Profile {
        public final String socName;
        public final String vendor;
        public final String gpuName;
        public final int tier;           // 1 (Entry/Legacy) .. 5 (Ultra-Flagship)
        public final String tierLabel;
        public final String tierDescription;
        public final int cpuCores;
        public final int maxClockMhz;
        public final long totalRamMb;
        public final boolean matchedFromDb;

        // Tier-recommended defaults
        public final int recUpscaler;
        public final int recScalePercent;
        public final int recSharpnessPercent;
        public final int recNeuralPercent;
        public final int recFrameGen;
        public final int recTemporal;
        public final int recAutoMfg;

        public Profile(String socName, String vendor, String gpuName,
                       int tier, String tierLabel, String tierDescription,
                       int cpuCores, int maxClockMhz, long totalRamMb,
                       boolean matchedFromDb,
                       int recUpscaler, int recScalePercent, int recSharpnessPercent,
                       int recNeuralPercent, int recFrameGen, int recTemporal, int recAutoMfg) {
            this.socName = socName;
            this.vendor = vendor;
            this.gpuName = gpuName;
            this.tier = tier;
            this.tierLabel = tierLabel;
            this.tierDescription = tierDescription;
            this.cpuCores = cpuCores;
            this.maxClockMhz = maxClockMhz;
            this.totalRamMb = totalRamMb;
            this.matchedFromDb = matchedFromDb;
            this.recUpscaler = recUpscaler;
            this.recScalePercent = recScalePercent;
            this.recSharpnessPercent = recSharpnessPercent;
            this.recNeuralPercent = recNeuralPercent;
            this.recFrameGen = recFrameGen;
            this.recTemporal = recTemporal;
            this.recAutoMfg = recAutoMfg;
        }

        public GraphicsSettings recommendedSettings() {
            GraphicsSettings s = new GraphicsSettings();
            s.upscaler = recUpscaler;
            s.scalePercent = recScalePercent;
            s.sharpnessPercent = recSharpnessPercent;
            s.neuralPercent = recNeuralPercent;
            s.frameGen = recFrameGen;
            s.temporal = recTemporal;
            s.autoMfg = recAutoMfg;
            s.berserkMode = 0;
            if (tier >= 5) {
                s.preset = GraphicsSettings.PRESET_DLSS_OVERDRIVE;
            } else if (tier == 4) {
                s.preset = GraphicsSettings.PRESET_DLSS_ULTRA;
            } else if (tier == 3) {
                s.preset = GraphicsSettings.PRESET_FSR_QUALITY;
            } else if (tier == 2) {
                s.preset = GraphicsSettings.PRESET_FSR_PERFORMANCE;
            } else {
                s.preset = GraphicsSettings.PRESET_CUSTOM;
            }
            return s;
        }

        public String summaryLine() {
            StringBuilder sb = new StringBuilder();
            sb.append(socName).append(" · ").append(tierLabel);
            if (totalRamMb > 0) {
                sb.append(String.format(Locale.US, " · %.1f GB RAM", totalRamMb / 1024.0));
            }
            if (cpuCores > 0 && maxClockMhz > 0) {
                sb.append(String.format(Locale.US, " · %d-core %.2f GHz",
                        cpuCores, maxClockMhz / 1000.0));
            }
            return sb.toString();
        }
    }

    private static volatile Profile sCached;

    private SoCProfiler() {
    }

    public static Profile get(Context context) {
        Profile cached = sCached;
        if (cached != null) return cached;
        synchronized (SoCProfiler.class) {
            if (sCached != null) return sCached;
            sCached = probeInternal(context.getApplicationContext());
            return sCached;
        }
    }

    private static Profile probeInternal(Context context) {
        final String gpu = probeGpuRenderer();
        final String socRaw = collectSocIdentifiers();
        final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int maxMhz = readMaxCpuClockMhz(cores);
        final long ramMb = readTotalRamMb(context);

        final String haystack = (socRaw + " " + gpu).toUpperCase(Locale.ROOT);

        JSONObject db = loadDatabase(context);
        String matchedName = null;
        String matchedVendor = null;
        int matchedTier = 0;

        if (db != null) {
            JSONArray entries = db.optJSONArray("entries");
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject e = entries.optJSONObject(i);
                    if (e == null) continue;
                    JSONArray patterns = e.optJSONArray("patterns");
                    if (patterns == null) continue;
                    for (int p = 0; p < patterns.length(); p++) {
                        String pat = patterns.optString(p, "").toUpperCase(Locale.ROOT);
                        if (!pat.isEmpty() && haystack.contains(pat)) {
                            matchedName = e.optString("name", "Unknown SoC");
                            matchedVendor = e.optString("vendor", "Generic");
                            matchedTier = e.optInt("tier", 2);
                            break;
                        }
                    }
                    if (matchedTier > 0) break;
                }
            }
        }

        boolean fromDb = matchedTier > 0;
        if (!fromDb) {
            // Dynamic hardware heuristic for brand-new / future / unlisted SoCs
            matchedTier = scoreDynamicHardwareTier(gpu, cores, maxMhz, ramMb);
            matchedName = primaryHardwareLabel();
            matchedVendor = Build.MANUFACTURER != null ? Build.MANUFACTURER : "Android";
        }

        // Load tier recommendation preset from soc_database.json (or safe fallback)
        String tierLabel = defaultTierLabel(matchedTier);
        String tierDesc = "";
        int recUpscaler = GraphicsSettings.UPSCALER_FSR;
        int recScale = 75;
        int recSharp = 45;
        int recNeural = 35;
        int recFg = GraphicsSettings.FRAME_GEN_2X;
        int recTemp = 1;
        int recAuto = 1;

        if (db != null) {
            JSONArray tiers = db.optJSONArray("tiers");
            if (tiers != null) {
                for (int i = 0; i < tiers.length(); i++) {
                    JSONObject t = tiers.optJSONObject(i);
                    if (t != null && t.optInt("tier", 0) == matchedTier) {
                        tierLabel = t.optString("label", tierLabel);
                        tierDesc = t.optString("description", "");
                        recUpscaler = t.optInt("upscaler", recUpscaler);
                        recScale = t.optInt("scalePercent", recScale);
                        recSharp = t.optInt("sharpnessPercent", recSharp);
                        recNeural = t.optInt("neuralPercent", recNeural);
                        recFg = t.optInt("frameGen", recFg);
                        recTemp = t.optInt("temporal", recTemp);
                        recAuto = t.optInt("autoMfg", recAuto);
                        break;
                    }
                }
            }
        }

        Profile profile = new Profile(
                matchedName, matchedVendor, gpu, matchedTier, tierLabel, tierDesc,
                cores, maxMhz, ramMb, fromDb,
                recUpscaler, recScale, recSharp, recNeural, recFg, recTemp, recAuto);
        Log.i(TAG, "SoCProfiler detected: " + profile.summaryLine()
                + " | GPU=" + gpu + " (db=" + fromDb + ")");
        return profile;
    }

    private static int scoreDynamicHardwareTier(String gpu, int cores, int maxMhz, long ramMb) {
        String g = gpu == null ? "" : gpu.toUpperCase(Locale.ROOT);
        int score = 0;

        // GPU class hints
        if (g.contains("ADRENO 8") || g.contains("ADRENO 9") || g.contains("IMMORTALIS-G9")
                || g.contains("XCLIPSE 940") || g.contains("XCLIPSE 950") || g.contains("XCLIPSE 960")) {
            score += 5;
        } else if (g.contains("ADRENO 74") || g.contains("ADRENO 75") || g.contains("IMMORTALIS")
                || g.contains("XCLIPSE 920") || g.contains("MALI-G720") || g.contains("MALEOON 9")) {
            score += 4;
        } else if (g.contains("ADRENO 7") || g.contains("ADRENO 65") || g.contains("ADRENO 66")
                || g.contains("MALI-G710") || g.contains("MALI-G615") || g.contains("MALI-G610")) {
            score += 3;
        } else if (g.contains("ADRENO 6") || g.contains("MALI-G77") || g.contains("MALI-G68")
                || g.contains("MALI-G57") || g.contains("BXM-8")) {
            score += 2;
        } else {
            score += 1;
        }

        // Peak CPU clock hints
        if (maxMhz >= 3400) score += 4;
        else if (maxMhz >= 2900) score += 3;
        else if (maxMhz >= 2300) score += 2;
        else if (maxMhz >= 1800) score += 1;

        // RAM capacity hints
        if (ramMb >= 11000) score += 3;
        else if (ramMb >= 7000) score += 2;
        else if (ramMb >= 3800) score += 1;

        if (cores >= 8) score += 1;

        if (score >= 11) return 5;
        if (score >= 9) return 4;
        if (score >= 6) return 3;
        if (score >= 4) return 2;
        return 1;
    }

    private static String defaultTierLabel(int tier) {
        switch (tier) {
            case 5: return "Tier 5: Ultra-Flagship";
            case 4: return "Tier 4: Flagship";
            case 3: return "Tier 3: Mid-Range";
            case 2: return "Tier 2: Budget";
            default: return "Tier 1: Entry / Legacy";
        }
    }

    private static String primaryHardwareLabel() {
        if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null
                && !Build.SOC_MODEL.isEmpty() && !"unknown".equalsIgnoreCase(Build.SOC_MODEL)) {
            return Build.SOC_MODEL;
        }
        if (Build.HARDWARE != null && !Build.HARDWARE.isEmpty()) {
            return Build.HARDWARE;
        }
        return Build.BOARD != null ? Build.BOARD : "Android Device";
    }

    private static String collectSocIdentifiers() {
        StringBuilder sb = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 31) {
            if (Build.SOC_MANUFACTURER != null) sb.append(Build.SOC_MANUFACTURER).append(' ');
            if (Build.SOC_MODEL != null) sb.append(Build.SOC_MODEL).append(' ');
        }
        if (Build.HARDWARE != null) sb.append(Build.HARDWARE).append(' ');
        if (Build.BOARD != null) sb.append(Build.BOARD).append(' ');
        if (Build.DEVICE != null) sb.append(Build.DEVICE).append(' ');
        if (Build.MODEL != null) sb.append(Build.MODEL).append(' ');

        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines++ < 64) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("hardware") || lower.startsWith("model name")
                        || lower.startsWith("processor")) {
                    sb.append(line).append(' ');
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static int readMaxCpuClockMhz(int cores) {
        long maxKhz = 0;
        for (int i = 0; i < Math.min(cores, 16); i++) {
            File f = new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            if (!f.isFile()) continue;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line = r.readLine();
                if (line != null) {
                    long khz = Long.parseLong(line.trim());
                    if (khz > maxKhz) maxKhz = khz;
                }
            } catch (Throwable ignored) {
            }
        }
        return (int) (maxKhz / 1000L);
    }

    private static long readTotalRamMb(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                return mi.totalMem / (1024L * 1024L);
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static String probeGpuRenderer() {
        try {
            EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) return "Unknown GPU";
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return "Unknown GPU";
            try {
                int[] cfgAttr = {
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_NONE
                };
                EGLConfig[] cfgs = new EGLConfig[1];
                int[] num = new int[1];
                if (!EGL14.eglChooseConfig(display, cfgAttr, 0, cfgs, 0, 1, num, 0) || num[0] <= 0) {
                    return "Unknown GPU";
                }
                int[] ctxAttr = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
                EGLContext ctx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
                if (ctx == EGL14.EGL_NO_CONTEXT) return "Unknown GPU";
                int[] pbAttr = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
                EGLSurface surf = EGL14.eglCreatePbufferSurface(display, cfgs[0], pbAttr, 0);
                String renderer = "Unknown GPU";
                if (surf != EGL14.EGL_NO_SURFACE) {
                    if (EGL14.eglMakeCurrent(display, surf, surf, ctx)) {
                        String r = GLES20.glGetString(GLES20.GL_RENDERER);
                        if (r != null && !r.isEmpty()) renderer = r;
                        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                                EGL14.EGL_NO_CONTEXT);
                    }
                    EGL14.eglDestroySurface(display, surf);
                }
                EGL14.eglDestroyContext(display, ctx);
                return renderer;
            } finally {
                EGL14.eglTerminate(display);
            }
        } catch (Throwable t) {
            return "Unknown GPU";
        }
    }

    private static JSONObject loadDatabase(Context context) {
        try (InputStream in = context.getAssets().open("soc_database.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "soc_database.json could not be loaded: " + t);
            return null;
        }
    }
}

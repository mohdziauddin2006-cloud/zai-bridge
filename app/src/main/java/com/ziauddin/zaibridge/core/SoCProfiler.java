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
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Universal Smartphone SoC & GPU Hardware Profiler and Auto-Recommender.
 *
 * <p>Combines live EGL {@code GL_RENDERER} probing, per-core {@code cpuinfo_max_freq}
 * clock speeds, system properties ({@code Build.SOC_MODEL}, {@code ro.soc.model},
 * {@code ro.board.platform}), nominal RAM tier snapping, and
 * {@code assets/soc_database.json} across Qualcomm, MediaTek, Samsung Exynos,
 * Google Tensor, HiSilicon Kirin, Xiaomi XRING, and Unisoc chipsets.
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
        public final int ramGb;
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
                       int cpuCores, int maxClockMhz, long totalRamMb, int ramGb,
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
            this.ramGb = ramGb;
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
            s.berserk = 0;
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
            if (ramGb > 0) {
                sb.append(" · ").append(ramGb).append(" GB RAM");
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
        final String liveGpu = probeGpuRenderer();
        final String socRaw = collectSocIdentifiers();
        final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        final long maxKhz = readMaxCpuClockKhz(cores);
        final int maxMhz = (int) (maxKhz / 1000L);
        final long ramBytes = readTotalRamBytes(context);
        final long ramMb = ramBytes / (1024L * 1024L);
        final int nominalRamGb = snapNominalRamGb(ramBytes);

        final String haystack = normalizeHaystack(socRaw + " " + liveGpu);

        JSONObject db = loadDatabase(context);
        String matchedName = null;
        String matchedVendor = null;
        String matchedDbGpu = null;
        int matchedTier = 0;

        if (db != null) {
            JSONArray socs = db.optJSONArray("socs");
            if (socs == null) socs = db.optJSONArray("entries");
            int bestTokenLen = 0;
            if (socs != null) {
                for (int i = 0; i < socs.length(); i++) {
                    JSONObject e = socs.optJSONObject(i);
                    if (e == null) continue;
                    JSONArray patterns = e.optJSONArray("match");
                    if (patterns == null) patterns = e.optJSONArray("patterns");
                    if (patterns == null) continue;
                    for (int p = 0; p < patterns.length(); p++) {
                        String pat = normalizeHaystack(patterns.optString(p, ""));
                        if (!pat.isEmpty() && haystack.contains(pat) && pat.length() > bestTokenLen) {
                            bestTokenLen = pat.length();
                            matchedName = e.optString("family", e.optString("name", "Unknown SoC"));
                            matchedVendor = e.optString("maker", e.optString("vendor", "Generic"));
                            matchedDbGpu = e.optString("gpu", "");
                            matchedTier = e.optInt("tier", 2);
                        }
                    }
                }
            }

            // If no SoC token matched, try matching GPU families in "gpus"
            if (matchedTier <= 0) {
                JSONArray gpus = db.optJSONArray("gpus");
                int bestGpuToken = 0;
                if (gpus != null) {
                    for (int i = 0; i < gpus.length(); i++) {
                        JSONObject g = gpus.optJSONObject(i);
                        if (g == null) continue;
                        JSONArray patterns = g.optJSONArray("match");
                        if (patterns == null) continue;
                        for (int p = 0; p < patterns.length(); p++) {
                            String pat = normalizeHaystack(patterns.optString(p, ""));
                            if (!pat.isEmpty() && haystack.contains(pat) && pat.length() > bestGpuToken) {
                                bestGpuToken = pat.length();
                                matchedTier = g.optInt("tier", 0);
                            }
                        }
                    }
                }
            }
        }

        // Disambiguate shared die IDs (MT6855, SM8250, MT6877) using live clock speed.
        String[] sharedDisambiguated = resolveSharedDieFamily(
                haystack, maxKhz, matchedName, matchedVendor, matchedDbGpu);
        matchedName = sharedDisambiguated[0];
        matchedVendor = sharedDisambiguated[1];
        matchedDbGpu = sharedDisambiguated[2];

        boolean fromDb = matchedTier > 0 && matchedName != null && !matchedName.isEmpty();
        if (matchedTier <= 0) {
            matchedTier = scoreDynamicHardwareTier(liveGpu, cores, maxMhz, nominalRamGb * 1024L);
        }
        if (matchedName == null || matchedName.isEmpty()) {
            matchedName = primaryHardwareLabel();
        }
        if (matchedVendor == null || matchedVendor.isEmpty()) {
            matchedVendor = Build.MANUFACTURER != null ? Build.MANUFACTURER : "Android";
        }

        // Live EGL GL_RENDERER ALWAYS wins over static JSON database strings.
        final boolean hasLiveGpu = liveGpu != null
                && !liveGpu.isEmpty()
                && !"Unknown GPU".equalsIgnoreCase(liveGpu);
        final String finalGpu = hasLiveGpu
                ? liveGpu
                : (matchedDbGpu != null && !matchedDbGpu.isEmpty() ? matchedDbGpu : "Unknown GPU");

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
                        tierLabel = t.optString("name", t.optString("label", tierLabel));
                        tierDesc = t.optString("description", "");
                        recUpscaler = t.optInt("upscaler", recUpscaler);
                        recScale = t.optInt("scale", t.optInt("scalePercent", recScale));
                        recSharp = t.optInt("sharpness", t.optInt("sharpnessPercent", recSharp));
                        recNeural = t.optInt("neural", t.optInt("neuralPercent", recNeural));
                        recFg = t.optInt("frameGen", recFg);
                        recTemp = t.optInt("temporal", recTemp);
                        recAuto = t.optInt("autoMfg", recAuto);
                        break;
                    }
                }
            }
        }

        Profile profile = new Profile(
                matchedName, matchedVendor, finalGpu, matchedTier, tierLabel, tierDesc,
                cores, maxMhz, ramMb, nominalRamGb, fromDb,
                recUpscaler, recScale, recSharp, recNeural, recFg, recTemp, recAuto);
        Log.i(TAG, "SoCProfiler detected: " + profile.summaryLine()
                + " | GPU=" + finalGpu + " (live=" + hasLiveGpu + ", db=" + fromDb + ")");
        return profile;
    }

    /**
     * Resolves chips that share a silicon die identifier using live peak CPU frequency
     * (in kHz) and model substrings.
     */
    public static String[] resolveSharedDieFamily(
            String normalizedHaystack,
            long maxKhz,
            String matchedName,
            String matchedVendor,
            String matchedDbGpu) {
        String h = normalizedHaystack == null ? "" : normalizedHaystack;
        if (h.contains("mt6855") || "Dimensity 7025".equals(matchedName)
                || "Dimensity 7020".equals(matchedName) || "Dimensity 930".equals(matchedName)) {
            matchedVendor = "MediaTek";
            matchedDbGpu = "PowerVR IMG BXM-8-256";
            if (h.contains("7025 ultra")) {
                matchedName = "Dimensity 7025 Ultra";
            } else if (h.contains("dimensity 930")) {
                matchedName = "Dimensity 930";
            } else if (h.contains("dimensity 7020")) {
                matchedName = "Dimensity 7020";
            } else if (h.contains("dimensity 7025")) {
                matchedName = "Dimensity 7025";
            } else if (maxKhz >= 2_400_000L) {
                matchedName = "Dimensity 7025";
            } else if (maxKhz >= 2_000_000L) {
                matchedName = "Dimensity 7020";
            } else {
                matchedName = "Dimensity 7025";
            }
        } else if (h.contains("sm8250") && !h.contains("870") && !h.contains("865")) {
            matchedVendor = "Qualcomm";
            if (maxKhz >= 3_100_000L) {
                matchedName = "Snapdragon 870";
            } else if (maxKhz >= 2_950_000L) {
                matchedName = "Snapdragon 865+";
            } else {
                matchedName = "Snapdragon 865";
            }
        } else if (h.contains("mt6877") && !h.contains("900") && !h.contains("7050")
                && !h.contains("1080")) {
            matchedVendor = "MediaTek";
            if (maxKhz >= 2_500_000L) {
                matchedName = "Dimensity 7050 / 1080";
            } else {
                matchedName = "Dimensity 900";
            }
        }
        return new String[]{matchedName, matchedVendor, matchedDbGpu};
    }

    /**
     * Snaps raw kernel-reported {@code ActivityManager.MemoryInfo.totalMem} bytes to the
     * nominal physical LPDDR capacity tier ({@code 2, 3, 4, 6, 8, 12, 16, 24 GB}), accounting
     * for firmware/modem/ION carveouts that hide ~0.3–1.2 GB from Linux.
     */
    public static int snapNominalRamGb(long totalBytes) {
        if (totalBytes <= 0) return 0;
        double rawGb = totalBytes / (1024.0 * 1024.0 * 1024.0);
        if (rawGb > 20.5 && rawGb <= 24.5) return 24;
        if (rawGb > 14.5 && rawGb <= 16.5) return 16;
        if (rawGb > 10.5 && rawGb <= 12.5) return 12;
        if (rawGb > 6.5 && rawGb <= 8.5) return 8;
        if (rawGb > 4.8 && rawGb <= 6.5) return 6;
        if (rawGb > 3.2 && rawGb <= 4.8) return 4;
        if (rawGb > 2.5 && rawGb <= 3.2) return 3;
        if (rawGb > 1.5 && rawGb <= 2.5) return 2;
        return (int) Math.max(1, Math.round(rawGb));
    }

    private static String normalizeHaystack(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT)
                .replace("(tm)", " ").replace("(r)", " ")
                .replace("processor", " ").replace("technologies", " ");
        StringBuilder out = new StringBuilder(lower.length());
        boolean space = true;
        for (int i = 0; i < lower.length(); ++i) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-') {
                out.append(c);
                space = false;
            } else if (!space) {
                out.append(' ');
                space = true;
            }
        }
        return out.toString().trim();
    }

    private static int scoreDynamicHardwareTier(String gpu, int cores, int maxMhz, long ramMb) {
        String g = gpu == null ? "" : gpu.toUpperCase(Locale.ROOT);
        int score = 0;

        if (g.contains("ADRENO 8") || g.contains("ADRENO 9") || g.contains("IMMORTALIS-G9")
                || g.contains("XCLIPSE 940") || g.contains("XCLIPSE 950") || g.contains("XCLIPSE 960")) {
            score += 5;
        } else if (g.contains("ADRENO 74") || g.contains("ADRENO 75") || g.contains("IMMORTALIS")
                || g.contains("XCLIPSE 920") || g.contains("MALI-G720") || g.contains("MALEOON 9")) {
            score += 4;
        } else if (g.contains("ADRENO 7") || g.contains("ADRENO 65") || g.contains("ADRENO 66")
                || g.contains("MALI-G710") || g.contains("MALI-G615") || g.contains("MALI-G610")
                || g.contains("DXT")) {
            score += 3;
        } else if (g.contains("ADRENO 6") || g.contains("MALI-G77") || g.contains("MALI-G68")
                || g.contains("MALI-G57") || g.contains("BXM-8") || g.contains("BXM-")
                || g.contains("POWERVR B-SERIES")) {
            score += 2;
        } else {
            score += 1;
        }

        if (maxMhz >= 3400) score += 4;
        else if (maxMhz >= 2900) score += 3;
        else if (maxMhz >= 2300) score += 2;
        else if (maxMhz >= 1800) score += 1;

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
            case 3: return "Tier 3: Performance";
            case 2: return "Tier 2: Mainstream";
            default: return "Tier 1: Entry";
        }
    }

    private static String primaryHardwareLabel() {
        String propSoc = readSystemProp("ro.soc.model");
        if (!propSoc.isEmpty() && !"unknown".equalsIgnoreCase(propSoc)) {
            return propSoc;
        }
        if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null
                && !Build.SOC_MODEL.isEmpty() && !"unknown".equalsIgnoreCase(Build.SOC_MODEL)) {
            return Build.SOC_MODEL;
        }
        if (Build.HARDWARE != null && !Build.HARDWARE.isEmpty()) {
            return Build.HARDWARE;
        }
        return Build.BOARD != null ? Build.BOARD : "Android Device";
    }

    private static String readSystemProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, key, "");
            return v instanceof String ? ((String) v).trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String collectSocIdentifiers() {
        StringBuilder sb = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 31) {
            if (Build.SOC_MANUFACTURER != null) sb.append(Build.SOC_MANUFACTURER).append(' ');
            if (Build.SOC_MODEL != null) sb.append(Build.SOC_MODEL).append(' ');
        }
        String[] propKeys = {
                "ro.soc.model",
                "ro.soc.manufacturer",
                "ro.board.platform",
                "ro.mediatek.platform",
                "ro.hardware",
                "ro.product.board"
        };
        for (String key : propKeys) {
            String val = readSystemProp(key);
            if (!val.isEmpty()) sb.append(val).append(' ');
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

    private static long readMaxCpuClockKhz(int cores) {
        long maxKhz = 0;
        for (int i = 0; i < Math.min(cores, 16); i++) {
            File f = new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            long v = readLongFile(f);
            if (v > maxKhz) maxKhz = v;
        }
        if (maxKhz <= 0) {
            File[] policies = new File("/sys/devices/system/cpu/cpufreq").listFiles();
            if (policies != null) {
                for (File p : policies) {
                    long v = readLongFile(new File(p, "cpuinfo_max_freq"));
                    if (v > maxKhz) maxKhz = v;
                }
            }
        }
        return maxKhz;
    }

    private static long readLongFile(File f) {
        if (f == null || !f.isFile()) return 0;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine();
            return line != null ? Long.parseLong(line.trim()) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static long readTotalRamBytes(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                return mi.totalMem;
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    public static String probeGpuRenderer() {
        try {
            EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) return fallbackNativeGpu();
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return fallbackNativeGpu();
            try {
                int[] cfgAttr = {
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_NONE
                };
                EGLConfig[] cfgs = new EGLConfig[1];
                int[] num = new int[1];
                if (!EGL14.eglChooseConfig(display, cfgAttr, 0, cfgs, 0, 1, num, 0) || num[0] <= 0) {
                    return fallbackNativeGpu();
                }
                int[] ctxAttr = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
                EGLContext ctx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
                if (ctx == EGL14.EGL_NO_CONTEXT) return fallbackNativeGpu();
                int[] pbAttr = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
                EGLSurface surf = EGL14.eglCreatePbufferSurface(display, cfgs[0], pbAttr, 0);
                String renderer = "";
                if (surf != EGL14.EGL_NO_SURFACE) {
                    if (EGL14.eglMakeCurrent(display, surf, surf, ctx)) {
                        String r = GLES20.glGetString(GLES20.GL_RENDERER);
                        if (r != null && !r.isEmpty()) renderer = r.trim();
                        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                                EGL14.EGL_NO_CONTEXT);
                    }
                    EGL14.eglDestroySurface(display, surf);
                }
                EGL14.eglDestroyContext(display, ctx);
                return renderer.isEmpty() ? fallbackNativeGpu() : renderer;
            } finally {
                EGL14.eglTerminate(display);
            }
        } catch (Throwable t) {
            return fallbackNativeGpu();
        }
    }

    private static String fallbackNativeGpu() {
        try {
            String info = EglSwapHook.graphicsInfo();
            if (info != null && info.startsWith("gpu=")) {
                String gpu = info.substring(4);
                int cut = gpu.indexOf(" cpu=");
                if (cut > 0) gpu = gpu.substring(0, cut);
                int sep = gpu.indexOf(" / ");
                if (sep > 0) gpu = gpu.substring(0, sep);
                gpu = gpu.trim();
                if (!gpu.isEmpty()) return gpu;
            }
        } catch (Throwable ignored) {
        }
        return "Unknown GPU";
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

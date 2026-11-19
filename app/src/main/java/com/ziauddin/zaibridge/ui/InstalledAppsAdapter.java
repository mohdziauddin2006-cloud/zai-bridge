package com.ziauddin.zaibridge.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ziauddin.zaibridge.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Enumerates and displays installed 64-bit and 32-bit Android games and apps
 * (including Split APKs / App Bundles) so the user can import any installed
 * game directly into Zai-Bridge's Zai-Scale Ultra container.
 */
public final class InstalledAppsAdapter extends RecyclerView.Adapter<InstalledAppsAdapter.Holder> {

    public static final class InstalledApp {
        public final String packageName;
        public final String label;
        public final String sourceDir;
        public final String[] splitSourceDirs;
        public final String nativeLibraryDir;
        public final boolean isGame;
        public final boolean is64Bit;
        public final boolean is32Bit;
        public final boolean isSplitBundle;
        public final boolean hasObb;
        public Drawable icon;

        public InstalledApp(String packageName, String label, String sourceDir,
                            String[] splitSourceDirs, String nativeLibraryDir,
                            boolean isGame, boolean is64Bit, boolean is32Bit,
                            boolean isSplitBundle, boolean hasObb, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.sourceDir = sourceDir;
            this.splitSourceDirs = splitSourceDirs != null ? splitSourceDirs : new String[0];
            this.nativeLibraryDir = nativeLibraryDir;
            this.isGame = isGame;
            this.is64Bit = is64Bit;
            this.is32Bit = is32Bit;
            this.isSplitBundle = isSplitBundle;
            this.hasObb = hasObb;
            this.icon = icon;
        }

        public String archBadgeText() {
            if (is64Bit) return "64-BIT NATIVE";
            if (is32Bit) return "32-BIT LEGACY";
            return "64-BIT NATIVE";
        }
    }

    public interface OnAppSelectedListener {
        void onAppSelected(InstalledApp app);
    }

    private final Context context;
    private final OnAppSelectedListener listener;
    private final List<InstalledApp> allApps = new ArrayList<>();
    private final List<InstalledApp> visibleApps = new ArrayList<>();
    private String query = "";
    private String selectedPackage = null;

    public InstalledAppsAdapter(Context context, OnAppSelectedListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setApps(List<InstalledApp> apps) {
        allApps.clear();
        if (apps != null) {
            allApps.addAll(apps);
        }
        applyFilter();
    }

    public void setQuery(String text) {
        query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    public void clearSelection() {
        selectedPackage = null;
        notifyDataSetChanged();
    }

    public InstalledApp getSelectedApp() {
        if (selectedPackage == null) return null;
        for (InstalledApp app : allApps) {
            if (selectedPackage.equals(app.packageName)) return app;
        }
        return null;
    }

    private void applyFilter() {
        visibleApps.clear();
        for (InstalledApp app : allApps) {
            if (query.isEmpty()
                    || app.label.toLowerCase(Locale.ROOT).contains(query)
                    || app.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                visibleApps.add(app);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_installed_app, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        InstalledApp app = visibleApps.get(position);
        holder.label.setText(app.label);
        holder.pkg.setText(app.packageName);

        if (app.icon != null) {
            holder.icon.setImageDrawable(app.icon);
        } else {
            holder.icon.setImageResource(R.mipmap.ic_launcher);
        }

        holder.pillArch.setText(app.archBadgeText());
        if (app.is64Bit || !app.is32Bit) {
            holder.pillArch.setBackgroundResource(R.drawable.bg_badge_accent);
            holder.pillArch.setTextColor(context.getColor(R.color.zb_accent));
        } else {
            holder.pillArch.setBackgroundResource(R.drawable.bg_badge_amber);
            holder.pillArch.setTextColor(context.getColor(R.color.zb_amber));
        }

        if (app.isSplitBundle) {
            holder.pillSplit.setVisibility(View.VISIBLE);
            holder.pillSplit.setText(app.splitSourceDirs.length > 1
                    ? "SPLIT (" + app.splitSourceDirs.length + ")" : "SPLIT BUNDLE");
        } else {
            holder.pillSplit.setVisibility(View.GONE);
        }

        holder.pillObb.setVisibility(app.hasObb ? View.VISIBLE : View.GONE);

        boolean selected = app.packageName.equals(selectedPackage);
        holder.selectBadge.setText(selected ? "SELECTED" : "SELECT");
        holder.selectBadge.setBackgroundResource(
                selected ? R.drawable.bg_badge_amber : R.drawable.bg_badge_accent);
        holder.selectBadge.setTextColor(
                context.getColor(selected ? R.color.zb_amber : R.color.zb_accent));
        holder.itemView.setAlpha(selected ? 1.0f : 0.92f);

        holder.itemView.setOnClickListener(v -> {
            selectedPackage = app.packageName;
            notifyDataSetChanged();
            if (listener != null) {
                listener.onAppSelected(app);
            }
        });
    }

    @Override
    public int getItemCount() {
        return visibleApps.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView pkg;
        final TextView pillArch;
        final TextView pillSplit;
        final TextView pillObb;
        final TextView selectBadge;

        Holder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.iv_installed_icon);
            label = itemView.findViewById(R.id.tv_installed_label);
            pkg = itemView.findViewById(R.id.tv_installed_pkg);
            pillArch = itemView.findViewById(R.id.tv_pill_arch);
            pillSplit = itemView.findViewById(R.id.tv_pill_split);
            pillObb = itemView.findViewById(R.id.tv_pill_obb);
            selectBadge = itemView.findViewById(R.id.tv_installed_select);
        }
    }

    // ------------------------------------------------------------------
    // Background Package Scanner
    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    public static List<InstalledApp> scanInstalledApps(Context context) {
        PackageManager pm = context.getPackageManager();
        String selfPkg = context.getPackageName();
        List<ApplicationInfo> installed;
        try {
            installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        } catch (Throwable t) {
            return Collections.emptyList();
        }

        List<InstalledApp> result = new ArrayList<>();
        File obbRoot = new File(Environment.getExternalStorageDirectory(), "Android/obb");

        for (ApplicationInfo ai : installed) {
            if (ai == null || ai.packageName == null) continue;
            if (selfPkg.equals(ai.packageName)) continue;
            if (ai.sourceDir == null || ai.sourceDir.isEmpty()) continue;

            boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isUpdatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            boolean isGame = (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0
                    || (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME);

            // Filter out non-game pure system components
            if (isSystem && !isUpdatedSystem && !isGame) {
                continue;
            }

            // Require a launchable main activity so system services/providers aren't listed
            Intent launchIntent = pm.getLaunchIntentForPackage(ai.packageName);
            if (launchIntent == null && !isGame) {
                continue;
            }

            CharSequence rawLabel = null;
            try {
                rawLabel = pm.getApplicationLabel(ai);
            } catch (Throwable ignored) {
            }
            String label = (rawLabel != null && rawLabel.length() > 0)
                    ? rawLabel.toString() : ai.packageName;

            Drawable icon = null;
            try {
                icon = pm.getApplicationIcon(ai);
            } catch (Throwable ignored) {
            }

            String[] splits = ai.splitSourceDirs;
            boolean isSplit = splits != null && splits.length > 0;

            boolean[] arch = detectPackageArchitecture(ai);
            boolean has64 = arch[0];
            boolean has32 = arch[1];

            boolean hasObb = false;
            File pkgObbDir = new File(obbRoot, ai.packageName);
            if (pkgObbDir.isDirectory()) {
                File[] obbs = pkgObbDir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".obb"));
                hasObb = obbs != null && obbs.length > 0;
            }

            // Also heuristic game check for Unity / Unreal / Cocos / Godot packages
            if (!isGame) {
                isGame = looksLikeGamePackage(ai);
            }

            result.add(new InstalledApp(
                    ai.packageName,
                    label,
                    ai.sourceDir,
                    splits,
                    ai.nativeLibraryDir,
                    isGame,
                    has64,
                    has32,
                    isSplit,
                    hasObb,
                    icon));
        }

        Collections.sort(result, (a, b) -> {
            if (a.isGame != b.isGame) {
                return a.isGame ? -1 : 1;
            }
            return a.label.compareToIgnoreCase(b.label);
        });

        return result;
    }

    /**
     * Returns {@code [has64Bit, has32Bit]} by inspecting {@code nativeLibraryDir},
     * {@code base.apk}, and any {@code split_config.<abi>.apk}.
     */
    private static boolean[] detectPackageArchitecture(ApplicationInfo ai) {
        boolean has64 = false;
        boolean has32 = false;

        if (ai.nativeLibraryDir != null) {
            String lower = ai.nativeLibraryDir.toLowerCase(Locale.ROOT);
            File libDir = new File(ai.nativeLibraryDir);
            File[] soFiles = libDir.isDirectory()
                    ? libDir.listFiles((d, name) -> name.endsWith(".so")) : null;
            if (soFiles != null && soFiles.length > 0) {
                if (lower.contains("arm64") || lower.contains("x86_64")) {
                    has64 = true;
                } else if (lower.contains("arm") || lower.contains("x86")) {
                    has32 = true;
                }
            }
        }

        if (ai.splitSourceDirs != null) {
            for (String split : ai.splitSourceDirs) {
                if (split == null) continue;
                String name = new File(split).getName().toLowerCase(Locale.ROOT);
                if (name.contains("arm64_v8a") || name.contains("arm64-v8a") || name.contains("x86_64")) {
                    has64 = true;
                } else if (name.contains("armeabi") || name.contains("x86")) {
                    has32 = true;
                }
            }
        }

        if (!has64 && !has32 && ai.sourceDir != null) {
            boolean[] baseArch = inspectZipAbis(new File(ai.sourceDir));
            has64 |= baseArch[0];
            has32 |= baseArch[1];
        }

        if (!has64 && !has32 && ai.splitSourceDirs != null) {
            for (String split : ai.splitSourceDirs) {
                if (split == null) continue;
                boolean[] splitArch = inspectZipAbis(new File(split));
                has64 |= splitArch[0];
                has32 |= splitArch[1];
            }
        }

        // Pure-Java / Kotlin games run natively on the 64-bit ART host
        if (!has64 && !has32) {
            has64 = true;
        }
        return new boolean[]{has64, has32};
    }

    private static boolean[] inspectZipAbis(File apkFile) {
        boolean has64 = false;
        boolean has32 = false;
        if (!apkFile.isFile()) return new boolean[]{false, false};
        try (ZipFile zip = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("lib/") || !name.endsWith(".so")) continue;
                if (name.startsWith("lib/arm64-v8a/") || name.startsWith("lib/x86_64/")) {
                    has64 = true;
                } else if (name.startsWith("lib/armeabi-v7a/")
                        || name.startsWith("lib/armeabi/")
                        || name.startsWith("lib/x86/")) {
                    has32 = true;
                }
                if (has64 && has32) break;
            }
        } catch (Throwable ignored) {
        }
        return new boolean[]{has64, has32};
    }

    private static boolean looksLikeGamePackage(ApplicationInfo ai) {
        if (ai.nativeLibraryDir != null) {
            File dir = new File(ai.nativeLibraryDir);
            if (new File(dir, "libunity.so").isFile()
                    || new File(dir, "libil2cpp.so").isFile()
                    || new File(dir, "libUE4.so").isFile()
                    || new File(dir, "libUnreal.so").isFile()
                    || new File(dir, "libmain.so").isFile()
                    || new File(dir, "libSDL2.so").isFile()
                    || new File(dir, "libgodot_android.so").isFile()) {
                return true;
            }
        }
        String pkg = ai.packageName.toLowerCase(Locale.ROOT);
        return pkg.contains(".game") || pkg.contains("unity") || pkg.contains("gameloft")
                || pkg.contains("ea.game") || pkg.contains("rockstargames")
                || pkg.contains("nekki") || pkg.contains("supercell")
                || pkg.contains("activision") || pkg.contains("ubisoft")
                || pkg.contains("squareenix") || pkg.contains("rovio")
                || pkg.contains("halfbrick") || pkg.contains("kiloo")
                || pkg.contains("sybogames") || pkg.contains("innersloth")
                || pkg.contains("mojang") || pkg.contains("roblox")
                || pkg.contains("epicgames") || pkg.contains("mihoyo")
                || pkg.contains("hoyoverse") || pkg.contains("tencent");
    }
}

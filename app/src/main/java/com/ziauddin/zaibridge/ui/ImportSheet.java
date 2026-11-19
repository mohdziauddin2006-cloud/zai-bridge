package com.ziauddin.zaibridge.ui;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.ziauddin.zaibridge.R;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The guided import experience: supports both
 * 1) "From Storage (APK / OBB)" for standalone APK + OBB files, and
 * 2) "Installed Games (Device)" for importing already-installed 64-bit & 32-bit
 *    games (including Split APKs / App Bundles) directly into Zai-Bridge's
 *    Zai-Scale Ultra container.
 */
public final class ImportSheet {

    public interface Listener {
        void onPickApk();

        void onPickObb();

        void onStart(Uri apk, Uri obb);

        void onStartInstalled(String packageName);
    }

    private final Activity activity;
    private final BottomSheetDialog dialog;
    private final TextView apkName;
    private final TextView obbName;
    private final TextView stage;
    private final TextView error;
    private final LinearProgressIndicator progress;
    private final Button start;
    private final View progressWrap;

    private final TextView tabStorage;
    private final TextView tabInstalled;
    private final View containerStorage;
    private final View containerInstalled;
    private final EditText searchInstalled;
    private final TextView installedStatus;
    private final RecyclerView recyclerInstalled;
    private final InstalledAppsAdapter installedAdapter;

    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean installedTabActive = false;
    private boolean installedScanned = false;
    private Uri apk;
    private Uri obb;
    private InstalledAppsAdapter.InstalledApp selectedInstalledApp;

    public ImportSheet(Activity activity, Listener listener) {
        this.activity = activity;
        dialog = new BottomSheetDialog(activity, R.style.Theme_ZaiBridge);
        View content = LayoutInflater.from(activity).inflate(R.layout.sheet_import, null);
        dialog.setContentView(content);
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);

        apkName = content.findViewById(R.id.tv_apk_name);
        obbName = content.findViewById(R.id.tv_obb_name);
        stage = content.findViewById(R.id.tv_stage);
        error = content.findViewById(R.id.tv_import_error);
        progress = content.findViewById(R.id.progress_import);
        start = content.findViewById(R.id.btn_start_import);
        progressWrap = content.findViewById(R.id.progress_wrap);

        tabStorage = content.findViewById(R.id.tab_from_storage);
        tabInstalled = content.findViewById(R.id.tab_installed_games);
        containerStorage = content.findViewById(R.id.container_storage_import);
        containerInstalled = content.findViewById(R.id.container_installed_import);
        searchInstalled = content.findViewById(R.id.et_search_installed);
        installedStatus = content.findViewById(R.id.tv_installed_status);
        recyclerInstalled = content.findViewById(R.id.recycler_installed_apps);

        recyclerInstalled.setLayoutManager(new LinearLayoutManager(activity));
        installedAdapter = new InstalledAppsAdapter(activity, app -> {
            selectedInstalledApp = app;
            error.setVisibility(View.GONE);
            updateStartButtonState();
        });
        recyclerInstalled.setAdapter(installedAdapter);

        searchInstalled.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                installedAdapter.setQuery(s == null ? "" : s.toString());
                updateInstalledEmptyState();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        tabStorage.setOnClickListener(v -> switchTab(false));
        tabInstalled.setOnClickListener(v -> switchTab(true));

        View rowApk = content.findViewById(R.id.row_apk);
        View rowObb = content.findViewById(R.id.row_obb);
        ImageView clearApk = content.findViewById(R.id.iv_apk_clear);
        ImageView clearObb = content.findViewById(R.id.iv_obb_clear);

        rowApk.setOnClickListener(v -> listener.onPickApk());
        rowObb.setOnClickListener(v -> listener.onPickObb());
        clearApk.setOnClickListener(v -> {
            apk = null;
            apkName.setText(R.string.select_apk_hint);
            clearApk.setVisibility(View.GONE);
            updateStartButtonState();
        });
        clearObb.setOnClickListener(v -> {
            obb = null;
            obbName.setText(R.string.select_obb_hint);
            clearObb.setVisibility(View.GONE);
        });

        content.findViewById(R.id.btn_close_import).setOnClickListener(v -> dialog.dismiss());
        start.setOnClickListener(v -> {
            if (installedTabActive) {
                if (selectedInstalledApp == null) return;
                listener.onStartInstalled(selectedInstalledApp.packageName);
            } else {
                if (apk == null) return;
                listener.onStart(apk, obb);
            }
        });

        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setDimAmount(0.72f);
            }
        });
    }

    private void switchTab(boolean installed) {
        installedTabActive = installed;
        if (installed) {
            tabInstalled.setBackgroundResource(R.drawable.bg_badge_accent);
            tabInstalled.setTextColor(activity.getColor(R.color.zb_accent));
            tabStorage.setBackground(null);
            tabStorage.setTextColor(activity.getColor(R.color.zb_text_dim));
            containerStorage.setVisibility(View.GONE);
            containerInstalled.setVisibility(View.VISIBLE);
            if (!installedScanned) {
                loadInstalledAppsAsync();
            }
        } else {
            tabStorage.setBackgroundResource(R.drawable.bg_badge_accent);
            tabStorage.setTextColor(activity.getColor(R.color.zb_accent));
            tabInstalled.setBackground(null);
            tabInstalled.setTextColor(activity.getColor(R.color.zb_text_dim));
            containerInstalled.setVisibility(View.GONE);
            containerStorage.setVisibility(View.VISIBLE);
        }
        updateStartButtonState();
    }

    private void loadInstalledAppsAsync() {
        installedStatus.setText(R.string.scanning_installed);
        installedStatus.setVisibility(View.VISIBLE);
        recyclerInstalled.setVisibility(View.GONE);
        scanExecutor.execute(() -> {
            final List<InstalledAppsAdapter.InstalledApp> apps =
                    InstalledAppsAdapter.scanInstalledApps(activity.getApplicationContext());
            mainHandler.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                installedScanned = true;
                installedAdapter.setApps(apps);
                updateInstalledEmptyState();
            });
        });
    }

    private void updateInstalledEmptyState() {
        if (!installedScanned) return;
        if (installedAdapter.getItemCount() == 0) {
            installedStatus.setText(R.string.no_installed_found);
            installedStatus.setVisibility(View.VISIBLE);
            recyclerInstalled.setVisibility(View.GONE);
        } else {
            installedStatus.setVisibility(View.GONE);
            recyclerInstalled.setVisibility(View.VISIBLE);
        }
    }

    private void updateStartButtonState() {
        if (installedTabActive) {
            start.setEnabled(selectedInstalledApp != null);
            if (selectedInstalledApp != null) {
                start.setText("Import " + selectedInstalledApp.label);
            } else {
                start.setText(R.string.start_import);
            }
        } else {
            start.setEnabled(apk != null);
            start.setText(R.string.start_import);
        }
    }

    public void show() {
        if (!dialog.isShowing()) dialog.show();
    }

    public void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    public void setApk(Uri uri, String displayName) {
        apk = uri;
        apkName.setText(displayName);
        View clear = dialog.findViewById(R.id.iv_apk_clear);
        if (clear != null) clear.setVisibility(View.VISIBLE);
        error.setVisibility(View.GONE);
        updateStartButtonState();
    }

    public void setObb(Uri uri, String displayName) {
        obb = uri;
        obbName.setText(displayName);
        View clear = dialog.findViewById(R.id.iv_obb_clear);
        if (clear != null) clear.setVisibility(View.VISIBLE);
    }

    /** Streams a stage from the foreground ingestion service. */
    public void showProgress(String message) {
        progressWrap.setVisibility(View.VISIBLE);
        error.setVisibility(View.GONE);
        stage.setText(message);
        progress.setIndeterminate(true);
        start.setEnabled(false);
    }

    public void finish(boolean success, String label, String failure) {
        progress.setIndeterminate(false);
        progress.setProgressCompat(100, true);
        stage.setText(success ? label : failure);
        progressWrap.setVisibility(success ? View.VISIBLE : View.GONE);
        updateStartButtonState();
        if (!success && failure != null) {
            error.setText(failure);
            error.setVisibility(View.VISIBLE);
        }
        if (success) dialog.dismiss();
    }

    /** Clears the sheet for a new import without closing it. */
    public void reset() {
        apk = null;
        obb = null;
        selectedInstalledApp = null;
        installedAdapter.clearSelection();
        apkName.setText(R.string.select_apk_hint);
        obbName.setText(R.string.select_obb_hint);
        progressWrap.setVisibility(View.GONE);
        error.setVisibility(View.GONE);
        updateStartButtonState();
    }

    /** True when the sheet is visible; the caller owns activity results. */
    public boolean isShowing() {
        return dialog.isShowing();
    }
}

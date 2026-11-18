package com.ziauddin.zaibridge.ui;

import android.app.Activity;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.ziauddin.zaibridge.R;

/**
 * The guided import experience: pick an APK, optionally its OBB, press start,
 * and watch the extraction stages stream in. It replaces the old chain of raw
 * file-picker prompts and fixed toasts.
 */
public final class ImportSheet {

    public interface Listener {
        void onPickApk();

        void onPickObb();

        void onStart(Uri apk, Uri obb);
    }

    private final BottomSheetDialog dialog;
    private final TextView apkName;
    private final TextView obbName;
    private final TextView stage;
    private final TextView error;
    private final LinearProgressIndicator progress;
    private final Button start;
    private final View progressWrap;

    private Uri apk;
    private Uri obb;

    public ImportSheet(Activity activity, Listener listener) {
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
            start.setEnabled(false);
        });
        clearObb.setOnClickListener(v -> {
            obb = null;
            obbName.setText(R.string.select_obb_hint);
            clearObb.setVisibility(View.GONE);
        });

        content.findViewById(R.id.btn_close_import).setOnClickListener(v -> dialog.dismiss());
        start.setOnClickListener(v -> {
            if (apk == null) return;
            listener.onStart(apk, obb);
        });

        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setDimAmount(0.72f);
            }
        });
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
        start.setEnabled(true);
        error.setVisibility(View.GONE);
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
        start.setEnabled(apk != null);
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
        apkName.setText(R.string.select_apk_hint);
        obbName.setText(R.string.select_obb_hint);
        progressWrap.setVisibility(View.GONE);
        error.setVisibility(View.GONE);
        start.setEnabled(false);
    }

    /** True when the sheet is visible; the caller owns activity results. */
    public boolean isShowing() {
        return dialog.isShowing();
    }
}

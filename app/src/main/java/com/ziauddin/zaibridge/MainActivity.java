package com.ziauddin.zaibridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import com.ziauddin.zaibridge.core.BridgeInstrumentation;
import com.ziauddin.zaibridge.core.GuestLauncher;
import com.ziauddin.zaibridge.data.Game;
import com.ziauddin.zaibridge.data.GameAdapter;
import com.ziauddin.zaibridge.data.GameRepository;
import com.ziauddin.zaibridge.proxy.ProxyGameActivity;
import com.ziauddin.zaibridge.service.IngestionService;

import java.util.List;

/**
 * The launcher: a grid of imported games, an import flow that pairs an APK with
 * an optional OBB, and the All-files-access gate that lets Zai-Bridge browse
 * the handset for legacy installers.
 */
public class MainActivity extends Activity {

    private static final String TAG = "ZaiBridge";

    private static final int REQ_ALL_FILES = 0x51;
    private static final int REQ_APK = 0x52;
    private static final int REQ_OBB = 0x53;

    private GridView grid;
    private GameAdapter adapter;
    private TextView permissionText;
    private View permissionBanner;
    private Button grantButton;

    private Uri pendingApk;
    private Uri pendingObb;
    private boolean pickApkAfterGrant;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        grid = findViewById(R.id.grid_games);
        adapter = new GameAdapter(this);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> launch(adapter.getItem(position)));

        permissionBanner = findViewById(R.id.banner_permission);
        permissionText = findViewById(R.id.tv_permission);
        grantButton = findViewById(R.id.btn_grant);
        grantButton.setOnClickListener(v -> requestStorageAccess(false));

        findViewById(R.id.btn_import).setOnClickListener(v -> beginImport());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshGames();
        updatePermissionBanner();
    }

    // ---------------------------------------------------------------------
    // Library
    // ---------------------------------------------------------------------

    private void refreshGames() {
        List<Game> games = GameRepository.loadAll(this);
        adapter.setGames(games);
        findViewById(R.id.empty_state).setVisibility(games.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void launch(Game game) {
        final boolean virtualized = BridgeInstrumentation.isInstalled();
        Intent intent = new Intent(this, virtualized
                ? ProxyGameActivity.class : GameHostActivity.class);
        intent.putExtra(GuestLauncher.EXTRA_GAME_DIR,
                GameRepository.gameDir(this, game.directoryName).getAbsolutePath());
        intent.putExtra(GuestLauncher.EXTRA_GAME_LABEL, game.label);
        intent.putExtra(GuestLauncher.EXTRA_GUEST_ACTIVITY, game.launcherActivity);
        Log.i(TAG, "launching " + game.label + " via "
                + (virtualized ? "ProxyGameActivity (Instrumentation)" : "GameHostActivity"));
        startActivity(intent);
    }

    // ---------------------------------------------------------------------
    // Import flow: APK first, then optionally its OBB
    // ---------------------------------------------------------------------

    private void beginImport() {
        if (!hasStorageAccess()) {
            pickApkAfterGrant = true;
            requestStorageAccess(true);
            return;
        }
        pickApk();
    }

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/vnd.android.package-archive", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQ_APK);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
        }
    }

    private void askForObb() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_obb_title)
                .setMessage(R.string.dialog_obb_message)
                .setPositiveButton(R.string.dialog_obb_choose, (d, w) -> pickObb())
                .setNegativeButton(R.string.dialog_obb_skip, (d, w) -> startIngestion())
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    private void pickObb() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/octet-stream", "application/zip", "*/*"});
        try {
            startActivityForResult(intent, REQ_OBB);
        } catch (ActivityNotFoundException e) {
            startIngestion();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_ALL_FILES) {
            updatePermissionBanner();
            if (hasStorageAccess() && pickApkAfterGrant) {
                pickApkAfterGrant = false;
                pickApk();
            }
            return;
        }

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        if (requestCode == REQ_APK) {
            takePersistable(uri);
            pendingApk = uri;
            askForObb();
        } else if (requestCode == REQ_OBB) {
            takePersistable(uri);
            pendingObb = uri;
            startIngestion();
        }
    }

    private void startIngestion() {
        if (pendingApk == null) {
            Toast.makeText(this, "Pick an APK first", Toast.LENGTH_SHORT).show();
            return;
        }

        ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                super.onReceiveResult(resultCode, resultData);
                pendingApk = null;
                pendingObb = null;
                if (resultCode == IngestionService.RESULT_OK) {
                    String label = resultData == null ? ""
                            : resultData.getString(IngestionService.EXTRA_LABEL, "Game");
                    Toast.makeText(MainActivity.this,
                            getString(R.string.import_done, label), Toast.LENGTH_LONG).show();
                } else {
                    String error = resultData == null ? "unknown"
                            : resultData.getString(IngestionService.EXTRA_ERROR, "unknown");
                    Toast.makeText(MainActivity.this,
                            getString(R.string.import_failed, error), Toast.LENGTH_LONG).show();
                }
                refreshGames();
            }
        };

        IngestionService.start(this, pendingApk, pendingObb, receiver);
        Toast.makeText(this, R.string.importing_body, Toast.LENGTH_SHORT).show();
    }

    private void takePersistable(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "picked URI is not persistable: " + uri, e);
        }
    }

    // ---------------------------------------------------------------------
    // All files access
    // ---------------------------------------------------------------------

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess(boolean pickAfterGrant) {
        pickApkAfterGrant = pickAfterGrant;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_ALL_FILES);
            } catch (ActivityNotFoundException e) {
                startActivityForResult(
                        new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        REQ_ALL_FILES);
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_ALL_FILES);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_ALL_FILES) {
            updatePermissionBanner();
            if (hasStorageAccess() && pickApkAfterGrant) {
                pickApkAfterGrant = false;
                pickApk();
            }
        }
    }

    private void updatePermissionBanner() {
        if (permissionBanner == null) return;
        boolean granted = hasStorageAccess();
        permissionBanner.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (!granted) {
            permissionText.setText(R.string.permission_body);
        }
    }
}

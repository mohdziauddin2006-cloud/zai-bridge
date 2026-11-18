package com.ziauddin.zaibridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.ziauddin.zaibridge.GameHostActivity;
import com.ziauddin.zaibridge.R;
import com.ziauddin.zaibridge.core.BridgeInstrumentation;
import com.ziauddin.zaibridge.core.BridgeRuntime;
import com.ziauddin.zaibridge.core.EglSwapHook;
import com.ziauddin.zaibridge.core.GuestLauncher;
import com.ziauddin.zaibridge.data.Game;
import com.ziauddin.zaibridge.data.GameAdapter;
import com.ziauddin.zaibridge.data.GameRepository;
import com.ziauddin.zaibridge.data.GraphicsSettings;
import com.ziauddin.zaibridge.proxy.ProxyGameActivity;
import com.ziauddin.zaibridge.service.IngestionService;
import com.ziauddin.zaibridge.ui.GameDetailsSheet;
import com.ziauddin.zaibridge.ui.ImportSheet;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * The launcher: an obsidian glass header, the animated library grid, the
 * guided import sheet and the All-files-access gate.
 */
public class MainActivity extends AppCompatActivity implements GameAdapter.Listener {

    private static final String TAG = "ZaiBridge";

    private static final int REQ_ALL_FILES = 0x51;
    private static final int REQ_APK = 0x52;
    private static final int REQ_OBB = 0x53;

    private RecyclerView recycler;
    private GameAdapter adapter;
    private EditText search;
    private View emptyState;
    private View permissionBanner;
    private TextView permissionText;

    private ImportSheet importSheet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);

        recycler = findViewById(R.id.recycler_games);
        recycler.setLayoutManager(new GridLayoutManager(this, spanCount()));
        adapter = new GameAdapter(this, this);
        recycler.setAdapter(adapter);
        recycler.setItemAnimator(null);

        search = findViewById(R.id.et_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.setQuery(s == null ? "" : s.toString());
            }

            @Override public void afterTextChanged(Editable s) { }
        });

        emptyState = findViewById(R.id.empty_state);
        permissionBanner = findViewById(R.id.banner_permission);
        permissionText = findViewById(R.id.tv_permission);
        findViewById(R.id.btn_grant).setOnClickListener(v -> requestStorageAccess(false));

        View importButton = findViewById(R.id.btn_import);
        importButton.setOnClickListener(v -> showImportSheet());
        findViewById(R.id.btn_empty_import).setOnClickListener(v -> showImportSheet());
        findViewById(R.id.btn_graphics_defaults)
                .setOnClickListener(v -> showEngineSettings());

        TextView hardware = findViewById(R.id.tv_hardware);
        hardware.setText(getString(
                EglSwapHook.isInstalled() ? R.string.hardware_active : R.string.hardware_software,
                abiLabel()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshGames();
        updatePermissionBanner();
    }

    @Override
    protected void onDestroy() {
        // A BottomSheetDialog holds a window; leaving it attached to a
        // destroyed Activity is the classic leaked-window crash on relaunch.
        if (importSheet != null) importSheet.dismiss();
        importSheet = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------------
    // Library
    // ---------------------------------------------------------------------

    private int spanCount() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        return widthDp >= 720 ? 4 : (widthDp >= 600 ? 3 : 2);
    }

    private String abiLabel() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
        if (abi.startsWith("arm64")) return "ARM64";
        if (abi.startsWith("armeabi")) return "ARM32";
        return abi.toUpperCase(Locale.ROOT).replace('_', '-');
    }

    private void refreshGames() {
        List<Game> games = GameRepository.loadAll(this);
        adapter.setGames(games);
        emptyState.setVisibility(games.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(games.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onPlay(Game game) {
        launch(game);
    }

    @Override
    public void onDetails(Game game) {
        File dir = GameRepository.gameDir(this, game.directoryName);
        GameDetailsSheet.show(this, game, dir,
                (saved, settings) -> refreshGames(),
                (saved, settings) -> launch(saved));
    }

    private void launch(Game game) {
        BridgeRuntime.ensureInitialized(this);
        final boolean virtualized = BridgeRuntime.isInstrumentationInstalled();
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

    private void showEngineSettings() {
        GameDetailsSheet.showEngine(this, (saved, settings) -> {
            adapter.notifyDataSetChanged();
            Toast.makeText(this, R.string.graphics_saved, Toast.LENGTH_SHORT).show();
        });
    }

    // ---------------------------------------------------------------------
    // Import flow
    // ---------------------------------------------------------------------

    private void showImportSheet() {
        if (!hasStorageAccess()) {
            requestStorageAccess(false);
            return;
        }
        if (importSheet == null) {
            importSheet = new ImportSheet(this, new ImportSheet.Listener() {
                @Override public void onPickApk() { pick(REQ_APK); }

                @Override public void onPickObb() { pick(REQ_OBB); }

                @Override public void onStart(Uri apk, Uri obb) {
                    startIngestion(apk, obb);
                }
            });
        } else {
            importSheet.reset();
        }
        importSheet.show();
    }

    private void pick(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (requestCode == REQ_APK) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/vnd.android.package-archive",
                            "application/octet-stream"});
        } else {
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/octet-stream", "application/zip", "*/*"});
        }
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_ALL_FILES) {
            updatePermissionBanner();
            if (hasStorageAccess()) showImportSheet();
            return;
        }

        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        takePersistable(uri);
        if (importSheet == null) return;

        if (requestCode == REQ_APK) {
            importSheet.setApk(uri, displayName(uri));
        } else if (requestCode == REQ_OBB) {
            importSheet.setObb(uri, displayName(uri));
        }
    }

    private void startIngestion(Uri apk, Uri obb) {
        if (apk == null) return;
        if (importSheet != null) importSheet.showProgress(getString(R.string.importing_body));

        ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle data) {
                if (importSheet == null) return;
                if (resultCode == IngestionService.RESULT_PROGRESS) {
                    String stage = data == null ? null
                            : data.getString(IngestionService.EXTRA_STAGE);
                    if (stage != null) importSheet.showProgress(stage);
                    return;
                }
                if (resultCode == IngestionService.RESULT_OK) {
                    String label = data == null ? "Game"
                            : data.getString(IngestionService.EXTRA_LABEL, "Game");
                    importSheet.finish(true, label, null);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.import_done, label), Toast.LENGTH_LONG).show();
                } else {
                    String error = data == null ? "unknown"
                            : data.getString(IngestionService.EXTRA_ERROR, "unknown");
                    importSheet.finish(false, null, error);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.import_failed, error), Toast.LENGTH_LONG).show();
                }
                refreshGames();
            }
        };
        IngestionService.start(this, apk, obb, receiver);
    }

    private void takePersistable(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "picked URI is not persistable: " + uri, e);
        }
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = c.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
            // Provider without DISPLAY_NAME; fall back to the last path segment.
        }
        String last = uri.getLastPathSegment();
        return last == null ? uri.toString() : last;
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
            if (hasStorageAccess()) showImportSheet();
        }
    }

    private void updatePermissionBanner() {
        if (permissionBanner == null) return;
        boolean granted = hasStorageAccess();
        permissionBanner.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (!granted) permissionText.setText(R.string.permission_body);
    }
}

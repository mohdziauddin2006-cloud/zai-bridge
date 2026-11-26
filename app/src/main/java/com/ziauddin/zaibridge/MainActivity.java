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
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

import com.google.android.material.chip.ChipGroup;
import com.ziauddin.zaibridge.GameHostActivity;
import com.ziauddin.zaibridge.R;
import com.ziauddin.zaibridge.core.BridgeInstrumentation;
import com.ziauddin.zaibridge.core.BridgeRuntime;
import com.ziauddin.zaibridge.core.EglSwapHook;
import com.ziauddin.zaibridge.core.GuestLauncher;
import com.ziauddin.zaibridge.core.SoCProfiler;
import com.ziauddin.zaibridge.data.Game;
import com.ziauddin.zaibridge.data.GameAdapter;
import com.ziauddin.zaibridge.data.GameRepository;
import com.ziauddin.zaibridge.data.GraphicsSettings;
import com.ziauddin.zaibridge.proxy.ProxyGameActivity;
import com.ziauddin.zaibridge.service.IngestionService;
import com.ziauddin.zaibridge.ui.GameDetailsSheet;
import com.ziauddin.zaibridge.ui.HardwareInspectorSheet;
import com.ziauddin.zaibridge.ui.ImportSheet;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private GridLayoutManager gridLayoutManager;
    private GameAdapter adapter;
    private EditText search;
    private ImageButton btnViewToggle;
    private boolean listMode = false;
    private View emptyState;
    private View permissionBanner;
    private TextView permissionText;

    private ImportSheet importSheet;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private View loadingOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);

        loadingOverlay = findViewById(R.id.loading_overlay);

        recycler = findViewById(R.id.recycler_games);
        gridLayoutManager = new GridLayoutManager(this, spanCount());
        recycler.setLayoutManager(gridLayoutManager);
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

        btnViewToggle = findViewById(R.id.btn_view_toggle);
        if (btnViewToggle != null) {
            btnViewToggle.setOnClickListener(v -> toggleLayoutMode());
        }

        ChipGroup filterChips = findViewById(R.id.chips_library_filter);
        if (filterChips != null) {
            filterChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
                int id = checkedIds.isEmpty() ? R.id.chip_filter_all : checkedIds.get(0);
                if (id == R.id.chip_filter_32bit) {
                    adapter.setCategoryFilter(GameAdapter.FILTER_32BIT);
                } else if (id == R.id.chip_filter_64bit) {
                    adapter.setCategoryFilter(GameAdapter.FILTER_64BIT);
                } else if (id == R.id.chip_filter_unity) {
                    adapter.setCategoryFilter(GameAdapter.FILTER_UNITY);
                } else if (id == R.id.chip_filter_obb) {
                    adapter.setCategoryFilter(GameAdapter.FILTER_OBB);
                } else {
                    adapter.setCategoryFilter(GameAdapter.FILTER_ALL);
                }
            });
        }

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
        hardware.setText(getString(R.string.hardware_active, abiLabel()));
        hardware.setOnClickListener(v -> HardwareInspectorSheet.show(this, this::refreshGames));
        probeHardwareGpu(hardware);
    }

    private void toggleLayoutMode() {
        listMode = !listMode;
        if (gridLayoutManager != null) {
            gridLayoutManager.setSpanCount(listMode ? 1 : spanCount());
        }
        if (btnViewToggle != null) {
            btnViewToggle.setImageResource(listMode ? R.drawable.ic_view_grid : R.drawable.ic_view_list);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshGames();
        updatePermissionBanner();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refreshGames();
    }

    @Override
    protected void onDestroy() {
        // A BottomSheetDialog holds a window; leaving it attached to a
        // destroyed Activity is the classic leaked-window crash on relaunch.
        if (importSheet != null) importSheet.dismiss();
        importSheet = null;
        bgExecutor.shutdown();
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

    private void showLoading(boolean show) {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void refreshGames() {
        bgExecutor.execute(() -> {
            List<Game> games = GameRepository.loadAll(MainActivity.this);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.setGames(games);
                emptyState.setVisibility(games.isEmpty() ? View.VISIBLE : View.GONE);
                recycler.setVisibility(games.isEmpty() ? View.GONE : View.VISIBLE);
                checkPendingLaunch(games);
            });
        });
    }

    private void checkPendingLaunch(List<Game> games) {
        if (games == null || games.isEmpty()) return;
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("launch_package")) {
            String pkg = intent.getStringExtra("launch_package");
            intent.removeExtra("launch_package");
            if (pkg != null && !pkg.isEmpty()) {
                for (Game g : games) {
                    if ("first".equals(pkg) || pkg.equals(g.packageName) || pkg.equals(g.directoryName)) {
                        Log.i(TAG, "auto-launching requested game: " + g.label);
                        launch(g);
                        break;
                    }
                }
            }
        }
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
        if (game == null) return;
        File dir = GameRepository.gameDir(MainActivity.this, game.directoryName);
        Intent intent = new Intent(MainActivity.this, ProxyGameActivity.class);
        intent.putExtra(GuestLauncher.EXTRA_GAME_DIR, dir.getAbsolutePath());
        intent.putExtra(GuestLauncher.EXTRA_GAME_LABEL, game.label);
        intent.putExtra(GuestLauncher.EXTRA_GUEST_ACTIVITY, game.launcherActivity);
        intent.putExtra(GuestLauncher.EXTRA_GAME_PACKAGE, game.packageName);
        intent.putExtra(GuestLauncher.EXTRA_IS_NATIVE64, game.isNative64());
        Log.i(TAG, "dispatching " + game.label + " to :guest container (non-blocking)");
        startActivity(intent);
    }

    private void probeHardwareGpu(TextView hardware) {
        bgExecutor.execute(() -> {
            String rendererName = null;
            try {
                EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                if (display != EGL14.EGL_NO_DISPLAY) {
                    int[] version = new int[2];
                    if (EGL14.eglInitialize(display, version, 0, version, 1)) {
                        int[] configAttr = {
                                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                                EGL14.EGL_NONE
                        };
                        EGLConfig[] configs = new EGLConfig[1];
                        int[] numConfigs = new int[1];
                        if (EGL14.eglChooseConfig(display, configAttr, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
                            int[] contextAttr = {
                                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                                    EGL14.EGL_NONE
                            };
                            EGLContext context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttr, 0);
                            if (context != EGL14.EGL_NO_CONTEXT) {
                                int[] pbufferAttr = {
                                        EGL14.EGL_WIDTH, 1,
                                        EGL14.EGL_HEIGHT, 1,
                                        EGL14.EGL_NONE
                                };
                                EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0], pbufferAttr, 0);
                                if (surface != EGL14.EGL_NO_SURFACE) {
                                    if (EGL14.eglMakeCurrent(display, surface, surface, context)) {
                                        rendererName = GLES20.glGetString(GLES20.GL_RENDERER);
                                        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                                    }
                                    EGL14.eglDestroySurface(display, surface);
                                }
                                EGL14.eglDestroyContext(display, context);
                            }
                        }
                        EGL14.eglTerminate(display);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Hardware GPU probe failed", t);
            }

            SoCProfiler.Profile profile = null;
            try {
                profile = SoCProfiler.get(MainActivity.this);
            } catch (Throwable ignored) {
            }

            final String gpu = rendererName;
            final SoCProfiler.Profile resolvedProfile = profile;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                String abi = abiLabel();
                if (resolvedProfile != null) {
                    String gpuLabel = (gpu != null && !gpu.isEmpty()) ? gpu : resolvedProfile.gpuName;
                    hardware.setText("T" + resolvedProfile.tier + " \u2022 " + resolvedProfile.socName + " \u2022 " + gpuLabel);
                } else if (gpu != null && !gpu.isEmpty()) {
                    hardware.setText(abi + " \u2022 " + gpu);
                } else {
                    hardware.setText(getString(R.string.hardware_active, abi));
                }
            });
        });
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
        if (importSheet == null) {
            importSheet = new ImportSheet(this, new ImportSheet.Listener() {
                @Override
                public void onPickApk() {
                    if (!hasStorageAccess()) {
                        requestStorageAccess(false);
                        return;
                    }
                    pick(REQ_APK);
                }

                @Override
                public void onPickObb() {
                    if (!hasStorageAccess()) {
                        requestStorageAccess(false);
                        return;
                    }
                    pick(REQ_OBB);
                }

                @Override
                public void onStart(Uri apk, Uri obb) {
                    startIngestion(apk, obb);
                }

                @Override
                public void onStartInstalled(String packageName) {
                    startInstalledIngestion(packageName);
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

    private ResultReceiver buildIngestionReceiver() {
        return new ResultReceiver(new Handler(Looper.getMainLooper())) {
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
    }

    private void startIngestion(Uri apk, Uri obb) {
        if (apk == null) return;
        if (importSheet != null) importSheet.showProgress(getString(R.string.importing_body));
        IngestionService.start(this, apk, obb, buildIngestionReceiver());
    }

    private void startInstalledIngestion(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        if (importSheet != null) importSheet.showProgress(getString(R.string.importing_body));
        IngestionService.startInstalled(this, packageName, buildIngestionReceiver());
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

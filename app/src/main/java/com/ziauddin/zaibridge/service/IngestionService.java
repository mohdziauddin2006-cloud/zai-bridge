package com.ziauddin.zaibridge.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.Log;

import com.ziauddin.zaibridge.R;
import com.ziauddin.zaibridge.data.Game;
import com.ziauddin.zaibridge.data.GameImporter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs ingestion in the foreground: a single OBB or Split-APK bundle can be
 * several gigabytes, so the copy has to keep going while the user leaves the app.
 */
public class IngestionService extends Service {

    private static final String TAG = "ZaiBridge";
    private static final String CHANNEL_ID = "imports";
    private static final int NOTIFICATION_ID = 0x2A1;

    public static final String EXTRA_APK = "com.ziauddin.zaibridge.extra.APK";
    public static final String EXTRA_OBB = "com.ziauddin.zaibridge.extra.OBB";
    public static final String EXTRA_INSTALLED_PKG = "com.ziauddin.zaibridge.extra.INSTALLED_PKG";
    public static final String EXTRA_RECEIVER = "com.ziauddin.zaibridge.extra.RECEIVER";
    public static final String EXTRA_LABEL = "com.ziauddin.zaibridge.extra.LABEL";
    public static final String EXTRA_ERROR = "com.ziauddin.zaibridge.extra.ERROR";

    public static final int RESULT_OK = 0;
    public static final int RESULT_ERROR = 1;
    /** One extraction stage; {@link #EXTRA_STAGE} carries its human label. */
    public static final int RESULT_PROGRESS = 2;
    public static final String EXTRA_STAGE = "com.ziauddin.zaibridge.extra.STAGE";

    private ExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final String installedPkg = intent.getStringExtra(EXTRA_INSTALLED_PKG);
        final String apk = intent.getStringExtra(EXTRA_APK);
        final String obb = intent.getStringExtra(EXTRA_OBB);
        final ResultReceiver receiver = extractReceiver(intent);

        if ((installedPkg == null || installedPkg.isEmpty()) && apk == null) {
            finish(receiver, RESULT_ERROR, null, "No APK or installed package supplied");
            return START_NOT_STICKY;
        }

        final Uri apkUri = apk == null ? null : Uri.parse(apk);
        final Uri obbUri = obb == null ? null : Uri.parse(obb);

        executor.execute(() -> {
            try {
                GameImporter.Progress progress = message -> {
                    Log.i(TAG, "ingest: " + message);
                    if (receiver == null) return;
                    android.os.Bundle stage = new android.os.Bundle();
                    stage.putString(EXTRA_STAGE, message);
                    receiver.send(RESULT_PROGRESS, stage);
                };
                Game game;
                if (installedPkg != null && !installedPkg.isEmpty()) {
                    game = GameImporter.importInstalledPackage(
                            getApplicationContext(), installedPkg, progress);
                } else {
                    game = GameImporter.importGame(
                            getApplicationContext(), apkUri, obbUri, progress);
                }
                finish(receiver, RESULT_OK, game.label, null);
            } catch (Exception e) {
                Log.e(TAG, "ingestion failed", e);
                finish(receiver, RESULT_ERROR, null,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
        return START_NOT_STICKY;
    }

    private void finish(ResultReceiver receiver, int code, String label, String error) {
        if (receiver != null) {
            android.os.Bundle b = new android.os.Bundle();
            if (label != null) b.putString(EXTRA_LABEL, label);
            if (error != null) b.putString(EXTRA_ERROR, error);
            receiver.send(code, b);
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        stopSelf();
    }

    private void startInForeground() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.importing_title))
                .setContentText(getString(R.string.importing_body))
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.importing_title), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @SuppressWarnings("deprecation")
    private static ResultReceiver extractReceiver(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver.class);
        }
        return intent.getParcelableExtra(EXTRA_RECEIVER);
    }

    @Override
    public void onDestroy() {
        if (executor != null) executor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Starts ingestion for a picked storage APK and optional OBB. */
    public static void start(Context context, Uri apk, Uri obb, ResultReceiver receiver) {
        Intent intent = new Intent(context, IngestionService.class);
        intent.putExtra(EXTRA_APK, apk.toString());
        if (obb != null) intent.putExtra(EXTRA_OBB, obb.toString());
        intent.putExtra(EXTRA_RECEIVER, receiver);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** Starts ingestion for an already-installed device package (Base + Split APKs + OBB). */
    public static void startInstalled(Context context, String packageName, ResultReceiver receiver) {
        Intent intent = new Intent(context, IngestionService.class);
        intent.putExtra(EXTRA_INSTALLED_PKG, packageName);
        intent.putExtra(EXTRA_RECEIVER, receiver);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}

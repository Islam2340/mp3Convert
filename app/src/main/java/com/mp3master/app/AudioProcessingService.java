package com.mp3master.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class AudioProcessingService extends Service {

    private static final String CHANNEL_ID = "mp3master_processing";
    private static final int NOTIFICATION_ID = 1001;

    private static final String ACTION_START = "com.mp3master.app.ACTION_START";
    private static final String ACTION_UPDATE = "com.mp3master.app.ACTION_UPDATE";
    private static final String ACTION_STOP = "com.mp3master.app.ACTION_STOP";

    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_STATUS = "status";
    private static final String EXTRA_PROGRESS = "progress";
    private static final String EXTRA_INDETERMINATE = "indeterminate";

    private PowerManager.WakeLock wakeLock;
    private boolean isForeground = false;

    public static void startTask(Context context, String title, String status) {
        if (context == null) return;
        try {
            Context appCtx = context.getApplicationContext();
            Intent intent = new Intent(appCtx, AudioProcessingService.class);
            intent.setAction(ACTION_START);
            intent.putExtra(EXTRA_TITLE, title);
            intent.putExtra(EXTRA_STATUS, status);
            intent.putExtra(EXTRA_PROGRESS, 0);
            intent.putExtra(EXTRA_INDETERMINATE, true);
            ContextCompat.startForegroundService(appCtx, intent);
        } catch (Exception ignored) {
        }
    }

    public static void updateProgress(Context context, String title, String status, int progress, boolean indeterminate) {
        if (context == null) return;
        try {
            Context appCtx = context.getApplicationContext();
            Intent intent = new Intent(appCtx, AudioProcessingService.class);
            intent.setAction(ACTION_UPDATE);
            intent.putExtra(EXTRA_TITLE, title);
            intent.putExtra(EXTRA_STATUS, status);
            intent.putExtra(EXTRA_PROGRESS, progress);
            intent.putExtra(EXTRA_INDETERMINATE, indeterminate);
            appCtx.startService(intent);
        } catch (Exception ignored) {
        }
    }

    public static void stopTask(Context context) {
        if (context == null) return;
        try {
            Context appCtx = context.getApplicationContext();
            Intent intent = new Intent(appCtx, AudioProcessingService.class);
            intent.setAction(ACTION_STOP);
            appCtx.startService(intent);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MP3Master::AudioProcessing");
                wakeLock.setReferenceCounted(false);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        String title = intent.getStringExtra(EXTRA_TITLE);
        String status = intent.getStringExtra(EXTRA_STATUS);
        int progress = intent.getIntExtra(EXTRA_PROGRESS, 0);
        boolean indeterminate = intent.getBooleanExtra(EXTRA_INDETERMINATE, true);

        if (title == null || title.isEmpty()) title = "MP3 Мастер";
        if (status == null || status.isEmpty()) status = "Обработка аудио...";

        Notification notification = buildNotification(title, status, progress, indeterminate);

        if (!isForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
                isForeground = true;
            } catch (Exception e) {
                stopSelfSafely();
                return START_NOT_STICKY;
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                try {
                    wakeLock.acquire(60L * 60L * 1000L);
                } catch (Exception ignored) {
                }
            }
        } else {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, notification);
            }
        }

        return START_NOT_STICKY;
    }

    private Notification buildNotification(String title, String status, int progress, boolean indeterminate) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_tune)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setProgress(100, Math.max(0, Math.min(100, progress)), indeterminate)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Обработка аудио",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Прогресс конвертации, обрезки и склейки MP3 в фоне");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void stopSelfSafely() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        if (isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                //noinspection deprecation
                stopForeground(true);
            }
            isForeground = false;
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

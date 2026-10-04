package com.example.myapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class BatteryService extends Service {

    private static final String CHANNEL_ID_FG = "fg_monitor_v1";
    private static final String CHANNEL_ID_ALERT = "alert_v2";
    private static final int NOTIFICATION_ID_FG = 1;
    private static final int NOTIFICATION_ID_ALERT = 2;

    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_THRESHOLD = "battery_threshold";
    private static final String KEY_INTERVAL = "alert_interval";
    private static final String KEY_MONITOR_ENABLED = "monitor_enabled";

    public static final String ACTION_TEST = "com.example.myapplication.ACTION_TEST";
    public static final String EXTRA_TEST_LEVEL = "test_level";
    public static final String EXTRA_TEST_DELAY = "test_delay";
    public static final String ACTION_STOP_ALERT = "com.example.myapplication.ACTION_STOP_ALERT";
    public static final String ACTION_STOP_SERVICE = "com.example.myapplication.ACTION_STOP_SERVICE";

    private BroadcastReceiver batteryReceiver;
    private Handler handler;
    private Runnable repeatRunnable;
    private Runnable testDelayRunnable;

    private int currentThreshold = 1;
    private int currentInterval = 60;

    private boolean alerting = false;
    private boolean charging = false;
    private int lastLevel = -1;

    // 测试模式：屏蔽真实电量，使用模拟值进行重复提醒
    private boolean testMode = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        handler = new Handler(Looper.getMainLooper());

        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleBatteryChanged(intent);
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 关闭整个监控
        if (intent != null && ACTION_STOP_SERVICE.equals(intent.getAction())) {
            exitTestMode();
            cancelAlertNotification();
            cancelRepeat();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_MONITOR_ENABLED, true)) {
            exitTestMode();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundInternal();
        loadSettings();

        // 停止当前提醒（点“我知道了”）
        if (intent != null && ACTION_STOP_ALERT.equals(intent.getAction())) {
            alerting = false;
            exitTestMode();
            cancelAlertNotification();
            cancelRepeat();
            return START_STICKY;
        }

        // 测试指令
        if (intent != null && ACTION_TEST.equals(intent.getAction())) {
            final int testLevel = intent.getIntExtra(EXTRA_TEST_LEVEL, -1);
            final int testDelay = intent.getIntExtra(EXTRA_TEST_DELAY, 0);
            if (testLevel >= 0) {
                startTest(testLevel, testDelay);
            }
        }

        return START_STICKY;
    }

    /**
     * 启动测试模式：延迟指定秒数后触发提醒，并接入重复提醒机制
     */
    private void startTest(final int testLevel, int testDelay) {
        // 取消上一次测试
        if (testDelayRunnable != null) {
            handler.removeCallbacks(testDelayRunnable);
            testDelayRunnable = null;
        }
        cancelRepeat();
        cancelAlertNotification();
        alerting = false;

        testMode = true;
        lastLevel = testLevel;
        charging = false;

        testDelayRunnable = new Runnable() {
            @Override
            public void run() {
                testDelayRunnable = null;
                if (!testMode) return;
                if (charging) return;
                triggerAlert(testLevel);
                scheduleRepeat();
            }
        };
        handler.postDelayed(testDelayRunnable, Math.max(0, testDelay) * 1000L);
    }

    private void exitTestMode() {
        testMode = false;
        if (testDelayRunnable != null) {
            handler.removeCallbacks(testDelayRunnable);
            testDelayRunnable = null;
        }
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentThreshold = prefs.getInt(KEY_THRESHOLD, 1);
        currentInterval = prefs.getInt(KEY_INTERVAL, 60);
        if (currentInterval < 5) currentInterval = 5;
    }

    private void handleBatteryChanged(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        int percent = (int) ((level / (float) scale) * 100);

        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean nowCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        loadSettings();

        charging = nowCharging;

        // 测试模式：只响应充电，忽略真实电量
        if (testMode) {
            if (nowCharging) {
                exitTestMode();
                alerting = false;
                cancelAlertNotification();
                cancelRepeat();
            }
            return;
        }

        lastLevel = percent;

        if (nowCharging) {
            alerting = false;
            cancelAlertNotification();
            cancelRepeat();
            return;
        }

        if (percent <= currentThreshold) {
            if (!alerting) {
                triggerAlert(percent);
            }
            scheduleRepeat();
        } else {
            alerting = false;
            cancelAlertNotification();
            cancelRepeat();
        }
    }

    private void scheduleRepeat() {
        if (repeatRunnable != null) return;
        repeatRunnable = new Runnable() {
            @Override
            public void run() {
                repeatRunnable = null;
                if (charging) return;
                if (lastLevel < 0 || lastLevel > currentThreshold) return;
                alerting = true;
                showAlertNotification(lastLevel);
                scheduleRepeat();
            }
        };
        handler.postDelayed(repeatRunnable, currentInterval * 1000L);
    }

    private void cancelRepeat() {
        if (repeatRunnable != null) {
            handler.removeCallbacks(repeatRunnable);
            repeatRunnable = null;
        }
    }

    private void triggerAlert(int level) {
        alerting = true;
        showAlertNotification(level);
    }

    private void cancelAlertNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID_ALERT);
    }

    private void showAlertNotification(int level) {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 11, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, BatteryService.class);
        stopIntent.setAction(ACTION_STOP_ALERT);
        PendingIntent stopPi = PendingIntent.getService(this, 12, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
                .setContentTitle("电量极低")
                .setContentText("当前电量 " + level + "%，请立即充电")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(false)
                .setOngoing(true)
                .setOnlyAlertOnce(false)
                .setContentIntent(openPi)
                .addAction(0, "我知道了", stopPi)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID_ALERT);
            nm.notify(NOTIFICATION_ID_ALERT, n);
        }
    }

    private void startForegroundInternal() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID_FG)
                .setContentTitle("电量监控")
                .setContentText("正在后台运行")
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        startForeground(NOTIFICATION_ID_FG, notification);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;

            NotificationChannel fg = new NotificationChannel(
                    CHANNEL_ID_FG, "后台运行", NotificationManager.IMPORTANCE_MIN);
            fg.setDescription("保持电量监控在后台运行");
            fg.setShowBadge(false);
            fg.setSound(null, null);
            fg.enableVibration(false);
            fg.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            manager.createNotificationChannel(fg);

            NotificationChannel alert = new NotificationChannel(
                    CHANNEL_ID_ALERT, "低电量提醒", NotificationManager.IMPORTANCE_HIGH);
            alert.setDescription("电量过低时弹出的横幅提醒");

            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            alert.setSound(alarmUri, attrs);

            alert.setShowBadge(true);
            alert.enableVibration(true);
            alert.setVibrationPattern(new long[]{0, 500, 300, 500, 300, 500});
            alert.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(alert);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelRepeat();
        exitTestMode();
        cancelAlertNotification();
        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception ignored) {}
            batteryReceiver = null;
        }
    }
}
package com.example.myapplication;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_THRESHOLD = "battery_threshold";
    private static final String KEY_INTERVAL = "alert_interval";
    private static final String KEY_MONITOR_ENABLED = "monitor_enabled";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final int REQ_NOTIFICATION = 1001;

    private static final int THEME_FOLLOW_SYSTEM = 0;
    private static final int THEME_LIGHT = 1;
    private static final int THEME_DARK = 2;

    private TextView tvBatteryLevel;
    private TextView tvThreshold;
    private TextView tvInterval;
    private TextView tvMonitorStatus;
    private TextView tvTheme;
    private LinearLayout layoutThreshold;
    private LinearLayout layoutInterval;
    private LinearLayout layoutTheme;
    private SwitchCompat switchMonitor;
    private EditText etTestLevel;
    private EditText etTestDelay;
    private Button btnTest;

    private int threshold = 1;
    private int interval = 60;
    private boolean monitorEnabled = true;
    private int themeMode = THEME_FOLLOW_SYSTEM;
    private boolean suppressSwitchCallback = false;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level < 0 || scale <= 0) return;
                int percent = (int) ((level / (float) scale) * 100);
                tvBatteryLevel.setText(percent + "%");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvBatteryLevel = findViewById(R.id.tvBatteryLevel);
        tvThreshold = findViewById(R.id.tvThreshold);
        tvInterval = findViewById(R.id.tvInterval);
        tvMonitorStatus = findViewById(R.id.tvMonitorStatus);
        tvTheme = findViewById(R.id.tvTheme);
        layoutThreshold = findViewById(R.id.layoutThreshold);
        layoutInterval = findViewById(R.id.layoutInterval);
        layoutTheme = findViewById(R.id.layoutTheme);
        switchMonitor = findViewById(R.id.switchMonitor);
        etTestLevel = findViewById(R.id.etTestLevel);
        etTestDelay = findViewById(R.id.etTestDelay);
        btnTest = findViewById(R.id.btnTest);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        threshold = prefs.getInt(KEY_THRESHOLD, 1);
        interval = prefs.getInt(KEY_INTERVAL, 60);
        monitorEnabled = prefs.getBoolean(KEY_MONITOR_ENABLED, true);
        themeMode = prefs.getInt(KEY_THEME_MODE, THEME_FOLLOW_SYSTEM);

        tvThreshold.setText(threshold + "%");
        tvInterval.setText(interval + " 秒");
        tvTheme.setText(themeLabel(themeMode));

        registerReceiver(batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        suppressSwitchCallback = true;
        switchMonitor.setChecked(monitorEnabled);
        suppressSwitchCallback = false;
        updateMonitorStatusText();

        switchMonitor.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitchCallback) return;
            monitorEnabled = isChecked;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(KEY_MONITOR_ENABLED, isChecked).apply();
            updateMonitorStatusText();
            if (isChecked) {
                requestNotificationPermissionIfNeeded();
                startMonitorService();
                Toast.makeText(this, "电量监控已开启", Toast.LENGTH_SHORT).show();
            } else {
                stopMonitorService();
                Toast.makeText(this, "电量监控已关闭", Toast.LENGTH_SHORT).show();
            }
        });

        layoutThreshold.setOnClickListener(v -> showThresholdDialog());
        layoutInterval.setOnClickListener(v -> showIntervalDialog());
        layoutTheme.setOnClickListener(v -> showThemeDialog());
        btnTest.setOnClickListener(v -> runTest());

        if (monitorEnabled) {
            requestNotificationPermissionIfNeeded();
            startMonitorService();
        }
    }

    private String themeLabel(int mode) {
        switch (mode) {
            case THEME_LIGHT: return "浅色";
            case THEME_DARK: return "深色";
            default: return "跟随系统";
        }
    }

    private void applyTheme(int mode) {
        switch (mode) {
            case THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    /**
     * 外观选择对话框（使用自定义布局）
     */
    private void showThemeDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_theme, null);
        LinearLayout optionFollowSystem = view.findViewById(R.id.optionFollowSystem);
        LinearLayout optionLight = view.findViewById(R.id.optionLight);
        LinearLayout optionDark = view.findViewById(R.id.optionDark);
        TextView checkFollowSystem = view.findViewById(R.id.checkFollowSystem);
        TextView checkLight = view.findViewById(R.id.checkLight);
        TextView checkDark = view.findViewById(R.id.checkDark);
        Button cancelBtn = view.findViewById(R.id.dialogThemeCancel);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        // 根据当前主题显示勾
        updateThemeChecks(themeMode, checkFollowSystem, checkLight, checkDark);

        optionFollowSystem.setOnClickListener(v -> {
            selectTheme(THEME_FOLLOW_SYSTEM);
            dialog.dismiss();
        });
        optionLight.setOnClickListener(v -> {
            selectTheme(THEME_LIGHT);
            dialog.dismiss();
        });
        optionDark.setOnClickListener(v -> {
            selectTheme(THEME_DARK);
            dialog.dismiss();
        });
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void updateThemeChecks(int mode, TextView follow, TextView light, TextView dark) {
        follow.setVisibility(mode == THEME_FOLLOW_SYSTEM ? View.VISIBLE : View.INVISIBLE);
        light.setVisibility(mode == THEME_LIGHT ? View.VISIBLE : View.INVISIBLE);
        dark.setVisibility(mode == THEME_DARK ? View.VISIBLE : View.INVISIBLE);
    }

    private void selectTheme(int mode) {
        themeMode = mode;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putInt(KEY_THEME_MODE, themeMode).apply();
        tvTheme.setText(themeLabel(themeMode));
        applyTheme(themeMode);
    }

    private void updateMonitorStatusText() {
        if (monitorEnabled) {
            tvMonitorStatus.setText("运行中");
            tvMonitorStatus.setTextColor(0xFF34C759);
        } else {
            tvMonitorStatus.setText("已关闭");
            tvMonitorStatus.setTextColor(0xFF8E8E93);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 已授权
            } else {
                Toast.makeText(this,
                        "未授予通知权限，锁屏提醒可能无法显示",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startMonitorService() {
        Intent intent = new Intent(this, BatteryService.class);
        ContextCompat.startForegroundService(this, intent);
    }

    private void stopMonitorService() {
        Intent intent = new Intent(this, BatteryService.class);
        intent.setAction(BatteryService.ACTION_STOP_SERVICE);
        ContextCompat.startForegroundService(this, intent);
    }

    private void restartService() {
        if (monitorEnabled) {
            startMonitorService();
        }
    }

    private void showInputDialog(String title, String message, String initialValue,
                                 OnInputConfirmed onConfirm) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_input, null);
        TextView titleView = view.findViewById(R.id.dialogTitle);
        TextView msgView = view.findViewById(R.id.dialogMessage);
        EditText input = view.findViewById(R.id.dialogInput);
        Button cancelBtn = view.findViewById(R.id.dialogCancel);
        Button confirmBtn = view.findViewById(R.id.dialogConfirm);

        titleView.setText(title);
        msgView.setText(message);
        input.setText(initialValue);
        input.setSelection(input.getText().length());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        confirmBtn.setOnClickListener(v -> {
            String s = input.getText().toString().trim();
            dialog.dismiss();
            onConfirm.onResult(s);
        });

        dialog.show();
    }

    private interface OnInputConfirmed {
        void onResult(String value);
    }

    private void showThresholdDialog() {
        showInputDialog("低电量阈值", "当电量降至该值或以下时开始提醒",
                String.valueOf(threshold), value -> {
                    if (value.isEmpty()) return;
                    try {
                        int v = Integer.parseInt(value);
                        if (v >= 0 && v <= 100) {
                            threshold = v;
                            tvThreshold.setText(v + "%");
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .edit().putInt(KEY_THRESHOLD, v).apply();
                            restartService();
                        } else {
                            Toast.makeText(this, "请输入 0-100 之间的数字",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "请输入有效数字",
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void showIntervalDialog() {
        showInputDialog("重复提醒间隔", "未充电时每隔多少秒重复提醒一次（最小 5 秒）",
                String.valueOf(interval), value -> {
                    if (value.isEmpty()) return;
                    try {
                        int v = Integer.parseInt(value);
                        if (v >= 5 && v <= 3600) {
                            interval = v;
                            tvInterval.setText(v + " 秒");
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .edit().putInt(KEY_INTERVAL, v).apply();
                            restartService();
                        } else {
                            Toast.makeText(this, "请输入 5-3600 之间的数字",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "请输入有效数字",
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void runTest() {
        String levelStr = etTestLevel.getText().toString().trim();
        if (levelStr.isEmpty()) {
            Toast.makeText(this, "请输入模拟电量", Toast.LENGTH_SHORT).show();
            return;
        }
        int level;
        try {
            level = Integer.parseInt(levelStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "电量格式错误", Toast.LENGTH_SHORT).show();
            return;
        }
        if (level < 0 || level > 100) {
            Toast.makeText(this, "电量需在 0-100 之间", Toast.LENGTH_SHORT).show();
            return;
        }

        int delay = 0;
        String delayStr = etTestDelay.getText().toString().trim();
        if (!delayStr.isEmpty()) {
            try {
                delay = Integer.parseInt(delayStr);
                if (delay < 0) delay = 0;
                if (delay > 3600) delay = 3600;
            } catch (NumberFormatException ignored) {}
        }

        if (!monitorEnabled) {
            Toast.makeText(this, "请先开启电量监控开关", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, BatteryService.class);
        intent.setAction(BatteryService.ACTION_TEST);
        intent.putExtra(BatteryService.EXTRA_TEST_LEVEL, level);
        intent.putExtra(BatteryService.EXTRA_TEST_DELAY, delay);
        ContextCompat.startForegroundService(this, intent);

        if (delay > 0) {
            Toast.makeText(this,
                    "已下发测试任务：" + delay + " 秒后提醒，可立即锁屏",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "已触发测试提醒", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {}
    }
}
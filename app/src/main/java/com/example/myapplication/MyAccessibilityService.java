package com.example.myapplication;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

public class MyAccessibilityService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 这个 App 不依赖无障碍事件做具体操作，
        // 注册无障碍服务的主要目的是提升后台存活率
    }

    @Override
    public void onInterrupt() {
        // 服务被中断时的回调
    }
}
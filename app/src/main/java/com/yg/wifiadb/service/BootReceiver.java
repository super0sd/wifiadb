package com.yg.wifiadb.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.yg.wifiadb.core.Prefs;

/** 开机自启：仅在用户开启开关且上次处于运行状态时恢复服务。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Prefs prefs = new Prefs(context);
        if (!prefs.isBootStart() || !prefs.isDesiredRunning()) {
            return;
        }
        try {
            Intent service = new Intent(context, AdbWifiService.class)
                    .setAction(AdbWifiService.ACTION_START);
            ContextCompat.startForegroundService(context, service);
        } catch (Exception ignored) {
            // 部分 ROM 限制开机后台启动前台服务，忽略即可，用户可手动开启。
        }
    }
}

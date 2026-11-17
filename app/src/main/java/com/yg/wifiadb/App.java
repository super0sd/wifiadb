package com.yg.wifiadb;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/** 应用入口：初始化通知渠道。 */
public class App extends Application {

    public static final String CHANNEL_STATUS = "wifiadb_status";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CHANNEL_STATUS) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_STATUS, "无线调试状态", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示无线调试服务的运行状态与端口");
        channel.setShowBadge(false);
        channel.enableVibration(false);
        nm.createNotificationChannel(channel);
    }
}

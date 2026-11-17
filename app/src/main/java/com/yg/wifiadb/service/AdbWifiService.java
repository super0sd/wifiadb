package com.yg.wifiadb.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.yg.wifiadb.App;
import com.yg.wifiadb.R;
import com.yg.wifiadb.core.AdbWifiManager;
import com.yg.wifiadb.core.NetworkUtils;
import com.yg.wifiadb.core.Prefs;
import com.yg.wifiadb.ui.MainActivity;

/**
 * 常驻前台服务：维持无线调试状态、周期性自检并同步通知与界面。
 * 仅承担状态守护，ADB 客户端连接由界面按需发起。
 */
public class AdbWifiService extends Service {

    public static final String ACTION_START = "com.yg.wifiadb.action.START";
    public static final String ACTION_STOP = "com.yg.wifiadb.action.STOP";
    public static final String ACTION_STATUS = "com.yg.wifiadb.action.STATUS";

    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_IP = "ip";
    public static final String EXTRA_MESSAGE = "message";

    private static final int NOTIFICATION_ID = 0x0ADB;
    private static final long TICK_MS = 30_000L;

    private Prefs prefs;
    private AdbWifiManager manager;
    private Handler handler;
    private boolean foregroundStarted;
    private volatile boolean stopped;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stopped) {
                return;
            }
            if (!prefs.isDesiredRunning()) {
                handler.postDelayed(this, TICK_MS);
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    AdbWifiManager.Status status = manager.inspect();
                    if (status.state != AdbWifiManager.State.RUNNING && prefs.isDesiredRunning()) {
                        status = manager.enableBlocking(prefs.getPort());
                        if (status.state != AdbWifiManager.State.RUNNING) {
                            prefs.setDesiredRunning(false);
                        }
                    }
                    final AdbWifiManager.Status result = status;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            publish(result);
                            updateNotification(result);
                        }
                    });
                }
            }, "adb-wifi-tick").start();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        manager = new AdbWifiManager(this);
        handler = new Handler(Looper.getMainLooper());
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground("正在准备无线调试…", "");

        String action = intent != null && intent.getAction() != null
                ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            prefs.setDesiredRunning(false);
            handler.removeCallbacks(tick);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    AdbWifiManager.Status status = manager.inspect();
                    if (status.state == AdbWifiManager.State.RUNNING) {
                        status = manager.disableBlocking();
                    }
                    final AdbWifiManager.Status result = status;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            publish(result);
                            updateNotification(result);
                            stopForegroundCompat(true);
                            stopSelf();
                        }
                    });
                }
            }, "adb-wifi-stop").start();
            return START_NOT_STICKY;
        }

        prefs.setDesiredRunning(true);
        handler.removeCallbacks(tick);
        handler.post(tick);
        refreshAsync(true);
        return START_STICKY;
    }

    private void refreshAsync(final boolean enableIfNeeded) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                AdbWifiManager.Status status = manager.inspect();
                if (enableIfNeeded && status.state != AdbWifiManager.State.RUNNING
                        && prefs.isDesiredRunning()) {
                    status = manager.enableBlocking(prefs.getPort());
                    if (status.state != AdbWifiManager.State.RUNNING) {
                        prefs.setDesiredRunning(false);
                    }
                }
                final AdbWifiManager.Status result = status;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        publish(result);
                        updateNotification(result);
                    }
                });
            }
        }, "adb-wifi-refresh").start();
    }

    private void publish(AdbWifiManager.Status status) {
        boolean running = status.state == AdbWifiManager.State.RUNNING;
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra(EXTRA_RUNNING, running);
        intent.putExtra(EXTRA_PORT, status.port);
        intent.putExtra(EXTRA_IP, status.wifiIp);
        intent.putExtra(EXTRA_MESSAGE, status.message);
        sendBroadcast(intent);
    }

    private void updateNotification(AdbWifiManager.Status status) {
        boolean running = status.state == AdbWifiManager.State.RUNNING;
        String ip = status.wifiIp == null ? "" : status.wifiIp;
        String detail;
        if (running) {
            detail = "adb connect " + ip + ":" + status.port;
        } else if (status.state == AdbWifiManager.State.NEED_PERMISSION) {
            detail = "需要 Root / Shizuku 授权，点按查看";
        } else if (status.state == AdbWifiManager.State.ERROR) {
            detail = status.message == null || status.message.isEmpty() ? "启动失败" : status.message;
        } else {
            detail = "已停止";
        }
        Notification notification = buildNotification(
                running ? "无线调试已开启" : "无线调试未运行", detail);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
        if (!foregroundStarted) {
            startInForeground(running ? "无线调试已开启" : "无线调试未运行", detail);
        }
    }

    private void startInForeground(String title, String detail) {
        try {
            Notification notification = buildNotification(title, detail);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
        } catch (Exception ignored) {
            foregroundStarted = false;
        }
    }

    private Notification buildNotification(String title, String detail) {
        Intent content = new Intent(this, MainActivity.class);
        content.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, content, flags);

        return new NotificationCompat.Builder(this, App.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_wifi)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void stopForegroundCompat(boolean removeNotification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(removeNotification
                    ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(removeNotification);
        }
        foregroundStarted = false;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(App.CHANNEL_STATUS) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                App.CHANNEL_STATUS, "无线调试状态", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示无线调试服务的运行状态与端口");
        nm.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        stopped = true;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        stopForegroundCompat(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 便捷：判断当前是否已处于运行状态（同步，勿在主线程调用）。 */
    public static boolean isRunningNow(Prefs prefs) {
        return NetworkUtils.isLocalPortListening(prefs.getPort());
    }
}

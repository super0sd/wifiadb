package com.yg.wifiadb.ui;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.yg.wifiadb.R;
import com.yg.wifiadb.core.AdbSession;
import com.yg.wifiadb.core.AdbWifiManager;
import com.yg.wifiadb.core.DeviceStore;
import com.yg.wifiadb.core.Prefs;
import com.yg.wifiadb.core.UsbAdbTransport;
import com.yg.wifiadb.service.AdbWifiService;
import com.yg.wifiadb.util.UiUtils;

/** 主界面：无线调试开关、本机地址展示、连接指引与 USB 命令行入口。 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIFY = 1001;
    private static final String ACTION_USB_PERMISSION = "com.yg.wifiadb.action.USB_PERMISSION";

    private Prefs prefs;
    private AdbWifiManager manager;
    private DeviceStore store;

    private FrameLayout btnPower;
    private TextView tvPowerLabel;
    private TextView tvPowerHint;
    private TextView tvStatus;
    private TextView tvIp;
    private TextView tvAdbCommand;
    private TextView tvUsbTitle;
    private ProgressBar progress;
    private SwitchMaterial swBoot;

    private boolean busy;
    private boolean receiversRegistered;
    private AdbWifiManager.Status lastStatus;
    private UsbDevice pendingUsbDevice;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!busy) {
                refreshAsync();
            }
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            UsbDevice device = pendingUsbDevice;
            if (device == null) {
                device = readUsbDevice(intent);
            }
            pendingUsbDevice = null;
            if (granted && device != null) {
                showUsbTerminal(device);
            } else {
                UiUtils.toast(MainActivity.this, "USB 访问被拒绝，无法打开命令行");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        manager = new AdbWifiManager(this);
        store = new DeviceStore(this);

        bindViews();
        setupClicks();
        registerLocalReceivers();
        maybeRequestNotification();
        maybeShowWelcome();
    }

    private void bindViews() {
        btnPower = findViewById(R.id.btnPower);
        tvPowerLabel = findViewById(R.id.tvPowerLabel);
        tvPowerHint = findViewById(R.id.tvPowerHint);
        tvStatus = findViewById(R.id.tvStatus);
        tvIp = findViewById(R.id.tvIp);
        tvAdbCommand = findViewById(R.id.tvAdbCommand);
        tvUsbTitle = findViewById(R.id.tvUsbTitle);
        progress = findViewById(R.id.progress);
        swBoot = findViewById(R.id.swBoot);
        swBoot.setChecked(prefs.isBootStart());
    }

    private void setupClicks() {
        btnPower.setOnClickListener(v -> {
            if (busy) {
                return;
            }
            boolean running = lastStatus != null && lastStatus.state == AdbWifiManager.State.RUNNING;
            if (running) {
                disableWifiAdb();
            } else {
                enableWifiAdb();
            }
        });

        View cardIp = findViewById(R.id.cardIp);
        cardIp.setOnClickListener(v -> copyAddress());
        findViewById(R.id.btnCopyIp).setOnClickListener(v -> copyAddress());

        View cardCommand = findViewById(R.id.cardCommand);
        cardCommand.setOnClickListener(v -> copyCommand());
        findViewById(R.id.btnCopyCommand).setOnClickListener(v -> copyCommand());

        findViewById(R.id.cardUsb).setOnClickListener(v -> requestUsbTerminal());

        findViewById(R.id.rowManual).setOnClickListener(v -> showCommandManual());
        findViewById(R.id.rowKeyboard).setOnClickListener(v -> showKeyboardHelp());

        View.OnClickListener openSettings = v ->
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        findViewById(R.id.rowSettings).setOnClickListener(openSettings);
        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(openSettings);

        swBoot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.setBootStart(isChecked);
            if (isChecked && !prefs.isDesiredRunning()) {
                UiUtils.toast(this, "开机自启将在下次无线调试开启后生效");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        swBoot.setChecked(prefs.isBootStart());
        refreshAsync();
    }

    private void refreshAsync() {
        if (busy) {
            return;
        }
        new Thread(() -> {
            final AdbWifiManager.Status status = manager.inspect();
            runOnUiThread(() -> render(status));
        }, "adb-wifi-inspect").start();
    }

    private void render(AdbWifiManager.Status status) {
        if (isFinishing()) {
            return;
        }
        lastStatus = status;
        boolean running = status.state == AdbWifiManager.State.RUNNING;

        String ip = status.wifiIp;
        String address = ip == null ? "Wi-Fi 未连接" : ip + ":" + status.port;
        tvIp.setText(address);
        tvAdbCommand.setText("adb connect " + (ip == null ? "<IP>" : ip) + ":" + status.port);

        btnPower.setBackgroundResource(running
                ? R.drawable.bg_circle_ring_active : R.drawable.bg_circle_ring);
        tvPowerLabel.setText(running ? "关闭" : "开启");
        tvPowerLabel.setTextColor(running
                ? Color.WHITE : UiUtils.color(this, R.color.text_primary));
        tvPowerHint.setText(running ? "运行中" : "已停止");
        tvPowerHint.setTextColor(running
                ? Color.parseColor("#DDEDEA") : UiUtils.color(this, R.color.text_secondary));

        StringBuilder sb = new StringBuilder();
        switch (status.state) {
            case RUNNING:
                sb.append("无线调试运行中 · 端口 ").append(status.port);
                if ("root".equals(status.mode)) {
                    sb.append(" · Root");
                } else if ("shizuku".equals(status.mode)) {
                    sb.append(" · Shizuku");
                }
                break;
            case NEED_PERMISSION:
                sb.append("需要 Root 或 Shizuku 授权，点按开启后可查看可在 PC 上执行的手动命令");
                break;
            case ERROR:
                sb.append(status.message == null ? "启动失败" : status.message);
                break;
            default:
                sb.append(status.message == null ? "无线调试已关闭" : status.message);
                break;
        }
        if (!status.usbDebuggingEnabled) {
            sb.append("\n未检测到 USB 调试开关，请先在开发者选项中开启");
        }
        if (status.wirelessDebuggingEnabled && !running) {
            sb.append("\n系统无线调试已开启，可在开发者选项中使用配对码连接");
        }
        tvStatus.setText(sb.toString());

        tvUsbTitle.setText(prefs.isKeepScreenOn() ? "USB 命令行（屏幕常亮）" : "USB连接后点击命令行");
    }

    private void enableWifiAdb() {
        final int port = prefs.getPort();
        setBusy(true, "正在开启无线调试…");
        manager.enableAsync(port, status -> runOnUiThread(() -> {
            setBusy(false, null);
            render(status);
            if (status.state == AdbWifiManager.State.RUNNING) {
                prefs.setDesiredRunning(true);
                startGuardService();
                vibrateOnce();
                UiUtils.toast(MainActivity.this, "无线调试已开启");
            } else {
                prefs.setDesiredRunning(false);
                showPermissionGuide(status);
            }
        }));
    }

    private void disableWifiAdb() {
        setBusy(true, "正在关闭无线调试…");
        manager.disableAsync(status -> runOnUiThread(() -> {
            setBusy(false, null);
            render(status);
            if (status.state == AdbWifiManager.State.STOPPED) {
                prefs.setDesiredRunning(false);
                stopGuardService();
                UiUtils.toast(MainActivity.this, "无线调试已关闭");
            } else {
                showPermissionGuide(status);
            }
        }));
    }

    private void setBusy(boolean value, String hint) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.INVISIBLE);
        btnPower.setEnabled(!value);
        btnPower.setAlpha(value ? 0.55f : 1f);
        if (hint != null) {
            tvStatus.setText(hint);
        }
    }

    private void showPermissionGuide(AdbWifiManager.Status status) {
        if (isFinishing()) {
            return;
        }
        boolean needPermission = status.state == AdbWifiManager.State.NEED_PERMISSION;
        String title = needPermission ? "需要提权才能修改 adbd" : "未能开启无线调试";
        String message = status.message == null ? "未知原因" : status.message;
        if (needPermission) {
            message = message + "\n\n提示：也可用 USB 数据线连接 PC，执行一次\n"
                    + AdbWifiManager.manualEnableCommand(status.port);
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("复制命令", (d, w) -> UiUtils.copy(this, "命令",
                        AdbWifiManager.manualEnableCommand(status.port)))
                .setNeutralButton("开发者选项", (d, w) -> openDeveloperOptions())
                .setNegativeButton("知道了", null)
                .show();
    }

    private void copyAddress() {
        String ip = lastStatus != null && lastStatus.wifiIp != null
                ? lastStatus.wifiIp : null;
        int port = prefs.getPort();
        if (ip == null) {
            UiUtils.toast(this, "尚未获取到局域网 IP，请先连接 Wi-Fi");
            return;
        }
        UiUtils.copy(this, "地址", ip + ":" + port);
    }

    private void copyCommand() {
        String ip = lastStatus != null && lastStatus.wifiIp != null
                ? lastStatus.wifiIp : "设备IP";
        UiUtils.copy(this, "adb 命令", "adb connect " + ip + ":" + prefs.getPort());
    }

    private void startGuardService() {
        try {
            Intent intent = new Intent(this, AdbWifiService.class)
                    .setAction(AdbWifiService.ACTION_START);
            ContextCompat.startForegroundService(this, intent);
        } catch (Exception ignored) {
        }
    }

    private void stopGuardService() {
        try {
            Intent intent = new Intent(this, AdbWifiService.class)
                    .setAction(AdbWifiService.ACTION_STOP);
            ContextCompat.startForegroundService(this, intent);
        } catch (Exception ignored) {
        }
    }

    private void vibrateOnce() {
        if (!prefs.isVibrate()) {
            return;
        }
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(
                            30, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(30);
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------- USB ----------------

    private void requestUsbTerminal() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            UiUtils.toast(this, "当前设备不支持 USB Host");
            return;
        }
        UsbDevice target = null;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (UsbAdbTransport.isAdbDevice(device)) {
                target = device;
                break;
            }
        }
        if (target == null) {
            UiUtils.toast(this, "未发现 USB ADB 设备：请用数据线连接另一台设备并开启 USB 调试");
            return;
        }
        if (usbManager.hasPermission(target)) {
            showUsbTerminal(target);
            return;
        }
        pendingUsbDevice = target;
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pending = PendingIntent.getBroadcast(this, 0, intent, flags);
        usbManager.requestPermission(target, pending);
        UiUtils.toast(this, "请在系统弹窗中允许 USB 访问");
    }

    private void showUsbTerminal(final UsbDevice device) {
        setBusy(true, "正在通过 USB 连接设备…");
        new Thread(() -> {
            AdbSession session = null;
            try {
                UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
                session = AdbSession.openUsb(getFilesDir(), usbManager, device, 20000, message -> {
                    if (!isFinishing()) {
                        runOnUiThread(() -> tvStatus.setText(message));
                    }
                });
                final String model = session.model(5000);
                final AdbSession opened = session;
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        opened.close();
                        return;
                    }
                    setBusy(false, null);
                    render(lastStatus != null ? lastStatus : manager.inspect());
                    CommandSheet sheet = new CommandSheet(MainActivity.this, model,
                            "USB · " + device.getDeviceName());
                    sheet.setRunner(command -> opened.exec(command, 20000));
                    sheet.setOnDismissListener(dialog -> opened.close());
                    sheet.show();
                });
            } catch (final Exception e) {
                if (session != null) {
                    session.close();
                }
                final String message = AdbSession.friendlyError(e);
                runOnUiThread(() -> {
                    setBusy(false, null);
                    UiUtils.toast(MainActivity.this, message);
                });
            }
        }, "adb-usb-open").start();
    }

    private UsbDevice readUsbDevice(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            }
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 说明弹窗 ----------------

    private void showCommandManual() {
        String text = "【在 PC 上操作（目标设备已开启 USB 调试）】\n"
                + "adb tcpip 5555            开启目标设备 TCP 监听\n"
                + "adb connect IP:5555       连接目标设备\n"
                + "adb devices               查看已连接设备\n"
                + "adb disconnect IP:5555    断开指定设备\n"
                + "adb kill-server           结束本机 adb 服务\n\n"
                + "【在本机开启 / 关闭无线调试（需 Root 或 Shizuku）】\n"
                + "adb shell \"setprop service.adb.tcp.port 5555; setprop ctl.restart adbd\"\n"
                + "adb shell \"setprop service.adb.tcp.port -1; setprop ctl.restart adbd\"\n\n"
                + "【Android 11+ 原生无线调试】\n"
                + "开发者选项 → 无线调试 → 使用配对码配对设备\n"
                + "配对端口与连接端口不同，请以系统显示为准。";
        new AlertDialog.Builder(this)
                .setTitle("ADB 命令手册")
                .setMessage(text)
                .setPositiveButton("复制", (d, w) -> UiUtils.copy(this, "ADB 命令手册", text))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showKeyboardHelp() {
        String text = "【文本与按键】\n"
                + "adb shell input text \"hello\"       输入文本\n"
                + "adb shell input keyevent 66         回车\n"
                + "adb shell input keyevent 4          返回键\n"
                + "adb shell input keyevent 3          主页键\n"
                + "adb shell input keyevent 26         电源键\n"
                + "adb shell input keyevent 24/25      音量加 / 减\n\n"
                + "【触控】\n"
                + "adb shell input tap 540 1200        点击坐标\n"
                + "adb shell input swipe 540 1600 540 400 300   上滑\n"
                + "adb shell input keyevent 187        切换最近任务\n\n"
                + "【输入法】\n"
                + "adb shell ime list -s               列出输入法\n"
                + "adb shell ime enable <id>           启用输入法\n"
                + "adb shell ime set <id>              切换输入法\n\n"
                + "中文输入建议先在设备上启用 ADBKeyboard 等输入法，再用 input text 发送 UTF-8 文本。";
        new AlertDialog.Builder(this)
                .setTitle("ADB 键盘使用")
                .setMessage(text)
                .setPositiveButton("复制", (d, w) -> UiUtils.copy(this, "ADB 键盘命令", text))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void maybeShowWelcome() {
        if (prefs.isFirstLaunchDone()) {
            return;
        }
        prefs.setFirstLaunchDone();
        new AlertDialog.Builder(this)
                .setTitle("欢迎使用 WiFi ADB")
                .setMessage("· 开启本机无线调试需要 Root 或 Shizuku 授权，用于修改 adbd 的 TCP 监听端口。\n"
                        + "· 未授权时仍可作为 ADB 客户端，连接同一局域网内已开启无线调试的设备。\n"
                        + "· 通过 USB 连接另一台设备后，可直接执行 shell 命令。\n"
                        + "· 所有连接信息仅保存在本机。")
                .setPositiveButton("开始使用", null)
                .setNeutralButton("打开设置", (d, w) ->
                        startActivity(new Intent(this, SettingsActivity.class)))
                .show();
    }

    private void maybeRequestNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFY) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                UiUtils.toast(this, "未授予通知权限，前台服务状态将不可见");
            }
        }
    }

    private void openDeveloperOptions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                UiUtils.toast(this, "无法打开系统设置");
            }
        }
    }

    // ---------------- 广播 ----------------

    private void registerLocalReceivers() {
        if (receiversRegistered) {
            return;
        }
        IntentFilter status = new IntentFilter(AdbWifiService.ACTION_STATUS);
        IntentFilter usb = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, status, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(usbReceiver, usb, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, status);
            registerReceiver(usbReceiver, usb);
        }
        receiversRegistered = true;
    }

    @Override
    protected void onDestroy() {
        if (receiversRegistered) {
            try {
                unregisterReceiver(statusReceiver);
            } catch (Exception ignored) {
            }
            try {
                unregisterReceiver(usbReceiver);
            } catch (Exception ignored) {
            }
            receiversRegistered = false;
        }
        super.onDestroy();
    }
}

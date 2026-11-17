package com.yg.wifiadb.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
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
import com.yg.wifiadb.core.Device;
import com.yg.wifiadb.core.DeviceStore;
import com.yg.wifiadb.core.LocalSession;
import com.yg.wifiadb.core.Shell;
import com.yg.wifiadb.core.NetworkUtils;
import com.yg.wifiadb.core.Prefs;
import com.yg.wifiadb.util.UiUtils;

import java.util.List;

/** 独立设置页：网络配置、端口设置、设备管理、连接偏好、权限说明。 */
public class SettingsActivity extends AppCompatActivity {

    private static final int REQ_NOTIFY = 2001;

    private Prefs prefs;
    private DeviceStore store;
    private AdbWifiManager manager;

    private EditText etPort;
    private EditText etHost;
    private EditText etHostPort;
    private TextView tvPortError;
    private TextView tvPortHint;
    private TextView tvLocalIp;
    private TextView tvPermText;
    private TextView tvDeviceEmpty;
    private LinearLayout deviceList;

    private SwitchMaterial swWifiOnly;
    private SwitchMaterial swAllowLan;
    private SwitchMaterial swAutoReconnect;
    private SwitchMaterial swVibrate;
    private SwitchMaterial swKeepScreenOn;
    private SwitchMaterial swNotify;

    private TextView chipTimeout3;
    private TextView chipTimeout5;
    private TextView chipTimeout10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = new Prefs(this);
        store = new DeviceStore(this);
        manager = new AdbWifiManager(this);

        bindViews();
        loadValues();
        setupListeners();
        renderDevices();
        refreshLocalIp();
        refreshPermissionState();
    }

    private void bindViews() {
        etPort = findViewById(R.id.etPort);
        etHost = findViewById(R.id.etHost);
        etHostPort = findViewById(R.id.etHostPort);
        tvPortError = findViewById(R.id.tvPortError);
        tvPortHint = findViewById(R.id.tvPortHint);
        tvLocalIp = findViewById(R.id.tvLocalIp);
        tvPermText = findViewById(R.id.tvPermText);
        tvDeviceEmpty = findViewById(R.id.tvDeviceEmpty);
        deviceList = findViewById(R.id.deviceList);

        swWifiOnly = findViewById(R.id.swWifiOnly);
        swAllowLan = findViewById(R.id.swAllowLan);
        swAutoReconnect = findViewById(R.id.swAutoReconnect);
        swVibrate = findViewById(R.id.swVibrate);
        swKeepScreenOn = findViewById(R.id.swKeepScreenOn);
        swNotify = findViewById(R.id.swNotify);

        chipTimeout3 = findViewById(R.id.chipTimeout3);
        chipTimeout5 = findViewById(R.id.chipTimeout5);
        chipTimeout10 = findViewById(R.id.chipTimeout10);

        TextView tvVersion = findViewById(R.id.tvVersion);
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            tvVersion.setText("v" + name);
        } catch (Exception e) {
            tvVersion.setText("v1.0.0");
        }

        TextView tvAbout = findViewById(R.id.tvAbout);
        tvAbout.setText("WiFi ADB 帮助你在本机开启无线调试端口，或作为 ADB 客户端连接局域网内已开启无线调试的设备。\n"
                + "包名：" + getPackageName() + "\n"
                + "支持 Android 6.0 及以上；Android 11+ 建议配合系统无线调试使用。\n"
                + "所有设备信息仅存储在本机应用目录，不会上传。");
    }

    private void loadValues() {
        swWifiOnly.setChecked(prefs.isWifiOnly());
        swAllowLan.setChecked(prefs.isAllowLan());
        swAutoReconnect.setChecked(prefs.isAutoReconnect());
        swVibrate.setChecked(prefs.isVibrate());
        swKeepScreenOn.setChecked(prefs.isKeepScreenOn());
        swNotify.setChecked(prefs.isNotify());

        etPort.setText(String.valueOf(prefs.getPort()));
        etHostPort.setText(String.valueOf(prefs.getPort()));

        int timeout = prefs.getTimeoutMs();
        if (timeout <= 3500) {
            applyTimeoutChip(3000);
        } else if (timeout >= 9000) {
            applyTimeoutChip(10000);
        } else {
            applyTimeoutChip(5000);
        }

        tvPortHint.setText("当前端口 " + prefs.getPort() + "，修改后将在下次开启无线调试时生效");
        highlightPortChips(prefs.getPort());
    }

    private void setupListeners() {
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        swWifiOnly.setOnCheckedChangeListener((b, checked) -> {
            prefs.setWifiOnly(checked);
            if (checked && !NetworkUtils.isLocalNetwork(this)) {
                UiUtils.toast(this, "当前不在 Wi-Fi / 局域网，开启无线调试会失败");
            }
        });

        swAllowLan.setOnCheckedChangeListener((b, checked) -> {
            prefs.setAllowLan(checked);
            UiUtils.toast(this, checked
                    ? "允许局域网设备连接（下次开启生效）"
                    : "仅监听本机回环地址（下次开启生效）");
        });

        swAutoReconnect.setOnCheckedChangeListener((b, checked) -> prefs.setAutoReconnect(checked));
        swVibrate.setOnCheckedChangeListener((b, checked) -> prefs.setVibrate(checked));
        swKeepScreenOn.setOnCheckedChangeListener((b, checked) -> prefs.setKeepScreenOn(checked));
        swNotify.setOnCheckedChangeListener((b, checked) -> {
            prefs.setNotify(checked);
            if (checked && !hasNotificationPermission()) {
                requestNotificationPermission();
            }
        });

        findViewById(R.id.btnRefreshIp).setOnClickListener(v -> {
            refreshLocalIp();
            UiUtils.toast(this, tvLocalIp.getText().toString());
        });

        findViewById(R.id.btnSavePort).setOnClickListener(v -> savePort());
        findViewById(R.id.chip5555).setOnClickListener(v -> applyPort(5555));
        findViewById(R.id.chip5556).setOnClickListener(v -> applyPort(5556));
        findViewById(R.id.chip5599).setOnClickListener(v -> applyPort(5599));

        findViewById(R.id.btnAddDevice).setOnClickListener(v -> addDevice());

        chipTimeout3.setOnClickListener(v -> applyTimeoutChip(3000));
        chipTimeout5.setOnClickListener(v -> applyTimeoutChip(5000));
        chipTimeout10.setOnClickListener(v -> applyTimeoutChip(10000));

        findViewById(R.id.btnNotifyPerm).setOnClickListener(v -> {
            if (hasNotificationPermission()) {
                openNotificationSettings();
            } else {
                requestNotificationPermission();
            }
        });
        findViewById(R.id.btnDevOptions).setOnClickListener(v -> openDeveloperOptions());
        findViewById(R.id.btnAppSettings).setOnClickListener(v -> openAppSettings());

        etPort.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                hidePortError();
            }
        });
    }

    private void applyPort(int port) {
        etPort.setText(String.valueOf(port));
        prefs.setPort(port);
        hidePortError();
        tvPortHint.setText("当前端口 " + port + "，修改后将在下次开启无线调试时生效");
        highlightPortChips(port);
        UiUtils.toast(this, "端口已切换为 " + port);
    }

    /** 让常用端口 chip 体现当前选择，避免用户看不出哪个是生效值。 */
    private void highlightPortChips(int current) {
        int[] ids = {R.id.chip5555, R.id.chip5556, R.id.chip5599};
        int[] values = {5555, 5556, 5599};
        for (int i = 0; i < ids.length; i++) {
            TextView chip = findViewById(ids[i]);
            boolean active = values[i] == current;
            chip.setBackgroundResource(active ? R.drawable.bg_pill_green : R.drawable.bg_pill_outline);
            chip.setTextColor(UiUtils.color(this, active ? R.color.white : R.color.green_dark));
        }
    }

    private void savePort() {
        String raw = etPort.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            showPortError("请输入 1024–65535 之间的端口号");
            return;
        }
        if (!Prefs.validPort(port)) {
            showPortError("端口需在 1024–65535 之间");
            return;
        }
        prefs.setPort(port);
        hidePortError();
        etPort.clearFocus();
        tvPortHint.setText("当前端口 " + port + "，修改后将在下次开启无线调试时生效");
        UiUtils.toast(this, "端口已保存：" + port);
    }

    private void showPortError(String text) {
        tvPortError.setText(text);
        tvPortError.setVisibility(View.VISIBLE);
        etPort.setBackgroundResource(R.drawable.bg_input);
    }

    private void hidePortError() {
        tvPortError.setVisibility(View.GONE);
    }

    private void applyTimeoutChip(int ms) {
        prefs.setTimeoutMs(ms);
        int activeColor = UiUtils.color(this, R.color.white);
        int idleColor = UiUtils.color(this, R.color.text_secondary);
        int activeBg = R.drawable.bg_pill_green;
        int idleBg = R.drawable.bg_pill_outline;

        chipTimeout3.setTextColor(ms == 3000 ? activeColor : idleColor);
        chipTimeout3.setBackgroundResource(ms == 3000 ? activeBg : idleBg);
        chipTimeout5.setTextColor(ms == 5000 ? activeColor : idleColor);
        chipTimeout5.setBackgroundResource(ms == 5000 ? activeBg : idleBg);
        chipTimeout10.setTextColor(ms == 10000 ? activeColor : idleColor);
        chipTimeout10.setBackgroundResource(ms == 10000 ? activeBg : idleBg);
    }

    private void refreshLocalIp() {
        String ip = NetworkUtils.getLocalIpv4();
        tvLocalIp.setText(ip == null ? "未连接到局域网" : ip);
    }

    private void addDevice() {
        String host = etHost.getText().toString().trim();
        if (host.isEmpty()) {
            etHost.setError("请输入 IP 地址或主机名");
            etHost.requestFocus();
            return;
        }
        if (!NetworkUtils.isValidHost(host)) {
            etHost.setError("IP 或主机名格式不正确");
            etHost.requestFocus();
            return;
        }
        String rawPort = etHostPort.getText().toString().trim();
        int port = prefs.getPort();
        if (!rawPort.isEmpty()) {
            try {
                port = Integer.parseInt(rawPort);
            } catch (NumberFormatException e) {
                etHostPort.setError("端口格式不正确");
                return;
            }
        }
        if (!Prefs.validPort(port)) {
            etHostPort.setError("端口需在 1024–65535 之间");
            return;
        }
        Device device = store.addOrUpdate(host, port, null);
        etHost.setText("");
        etHostPort.setText(String.valueOf(prefs.getPort()));
        renderDevices();
        UiUtils.toast(this, "已添加 " + device.address() + "，点按设备行可连接");
    }

    private void renderDevices() {
        final ScrollView scroller = findViewById(R.id.settingsScroll);
        final int keepY = scroller == null ? 0 : scroller.getScrollY();
        List<Device> devices = store.list();
        deviceList.removeAllViews();
        if (devices.isEmpty()) {
            tvDeviceEmpty.setVisibility(View.VISIBLE);
            deviceList.setVisibility(View.GONE);
            return;
        }
        tvDeviceEmpty.setVisibility(View.GONE);
        deviceList.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final Device device : devices) {
            View row = inflater.inflate(R.layout.item_device, deviceList, false);
            TextView name = row.findViewById(R.id.tvDeviceName);
            TextView addr = row.findViewById(R.id.tvDeviceAddr);
            TextView state = row.findViewById(R.id.tvDeviceState);

            name.setText(device.displayName());
            addr.setText(device.address());
            state.setText(stateLabel(device.lastState));
            state.setTextColor(stateColor(device.lastState));

            row.setOnClickListener(v -> connectDevice(device));
            row.findViewById(R.id.btnDeviceMore)
                    .setOnClickListener(v -> showDeviceMenu(v, device));
            deviceList.addView(row);
        }
        if (scroller != null && keepY > 0) {
            scroller.post(() -> scroller.scrollTo(0, keepY));
        }
    }

    private String stateLabel(int state) {
        switch (state) {
            case Device.STATE_CONNECTED:
                return "已连接";
            case Device.STATE_FAILED:
                return "连接失败";
            default:
                return "未连接";
        }
    }

    private int stateColor(int state) {
        switch (state) {
            case Device.STATE_CONNECTED:
                return UiUtils.color(this, R.color.green_dark);
            case Device.STATE_FAILED:
                return UiUtils.color(this, R.color.danger);
            default:
                return UiUtils.color(this, R.color.text_hint);
        }
    }

    private void showDeviceMenu(View anchor, final Device device) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "连接并打开命令行");
        menu.getMenu().add(0, 2, 1, "重命名");
        menu.getMenu().add(0, 3, 2, "断开 / 复位状态");
        menu.getMenu().add(0, 4, 3, "删除");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    connectDevice(device);
                    return true;
                case 2:
                    renameDevice(device);
                    return true;
                case 3:
                    store.touch(device, Device.STATE_UNKNOWN);
                    renderDevices();
                    UiUtils.toast(this, "已断开 " + device.address());
                    return true;
                case 4:
                    confirmRemove(device);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void renameDevice(final Device device) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(device.displayName());
        input.setSelection(input.getText().length());
        int pad = (int) UiUtils.dp(this, 20);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(this)
                .setTitle("重命名设备")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        UiUtils.toast(this, "名称不能为空");
                        return;
                    }
                    store.rename(device, name);
                    renderDevices();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemove(final Device device) {
        new AlertDialog.Builder(this)
                .setTitle("删除设备")
                .setMessage("从历史记录中移除 " + device.displayName() + "（" + device.address() + "）？")
                .setPositiveButton("删除", (d, w) -> {
                    store.remove(device);
                    renderDevices();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void connectDevice(final Device device) {
        UiUtils.toast(this, "正在连接 " + device.address() + " …");
        final int timeout = prefs.getTimeoutMs();
        final boolean localTarget = LocalSession.isLocalTarget(device.host);
        new Thread(() -> {
            // 本机目标：root 可用时走本地 shell，等价执行命令且不受 adbd 授权流程限制
            if (localTarget && Shell.hasRoot()) {
                final LocalSession local = new LocalSession(true);
                final String localModel = local.model();
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    store.addOrUpdate(device.host, device.port, localModel);
                    store.touch(device, Device.STATE_CONNECTED);
                    renderDevices();
                    CommandSheet sheet = new CommandSheet(this,
                            localModel + " · " + local.mode(), device.address());
                    sheet.setRunner(command -> local.exec(command, 20000));
                    sheet.setOnDismissListener(dialog -> {
                        local.close();
                        store.touch(device, Device.STATE_UNKNOWN);
                        renderDevices();
                    });
                    sheet.show();
                });
                return;
            }
            AdbSession session = null;
            String model = null;
            String error = null;
            try {
                session = AdbSession.openTcp(getFilesDir(), device.host, device.port, timeout,
                        message -> runOnUiThread(() -> UiUtils.toast(SettingsActivity.this, message)));
                model = session.model(4000);
            } catch (Exception e) {
                error = AdbSession.friendlyError(e);
            }

            final AdbSession opened = session;
            final String modelName = model;
            final String failure = error;

            runOnUiThread(() -> {
                if (isFinishing()) {
                    if (opened != null) {
                        opened.close();
                    }
                    return;
                }
                if (opened == null) {
                    store.touch(device, Device.STATE_FAILED);
                    renderDevices();
                    new AlertDialog.Builder(this)
                            .setTitle("连接失败")
                            .setMessage(failure == null ? "未知错误" : failure)
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                store.addOrUpdate(device.host, device.port, modelName);
                store.touch(device, Device.STATE_CONNECTED);
                renderDevices();

                CommandSheet sheet = new CommandSheet(this,
                        modelName == null ? "ADB 命令行" : modelName, device.address());
                sheet.setRunner(command -> opened.exec(command, 20000));
                sheet.setOnDismissListener(dialog -> {
                    opened.close();
                    store.touch(device, Device.STATE_UNKNOWN);
                    renderDevices();
                });
                sheet.show();
            });
        }, "adb-tcp-connect").start();
    }

    private void refreshPermissionState() {
        new Thread(() -> {
            AdbWifiManager.Status status = manager.inspect();
            final AdbWifiManager.Status s = status;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("通知权限：").append(hasNotificationPermission() ? "已授予" : "未授予（前台服务状态不可见）").append("\n");
                sb.append("USB 调试开关：").append(s.usbDebuggingEnabled ? "已开启" : "未开启").append("\n");
                sb.append("Root：").append(s.rootAvailable ? "可用" : "不可用").append("\n");
                sb.append("Shizuku：").append(s.shizukuAvailable ? "可用" : "未检测到").append("\n");
                sb.append("无线调试服务：").append(s.state == AdbWifiManager.State.RUNNING
                        ? "运行中 · 端口 " + s.port : "未运行").append("\n\n");
                sb.append("开启本机无线调试需要 Root 或 Shizuku 授权以修改 adbd 的 TCP 监听端口；"
                        + "未授权时仍可作为客户端连接局域网设备，或通过 USB 使用命令行。");
                tvPermText.setText(sb.toString());
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    findViewById(R.id.btnNotifyPerm).setEnabled(false);
                }
            });
        }, "adb-wifi-perm").start();
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            UiUtils.toast(this, "Android 12 及以下无需单独申请通知权限");
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
            UiUtils.toast(this, granted ? "通知权限已授予" : "未授予通知权限");
            refreshPermissionState();
        }
    }

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            openAppSettings();
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

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(intent);
        } catch (Exception e) {
            UiUtils.toast(this, "无法打开应用设置");
        }
    }
}

package com.yg.wifiadb.core;

import android.content.Context;
import android.content.SharedPreferences;

/** 全局偏好设置，所有页面与服务共用同一份数据。 */
public class Prefs {

    public static final int DEFAULT_PORT = 5555;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    private static final String FILE = "wifi_adb_prefs";

    private static final String KEY_PORT = "port";
    private static final String KEY_WIFI_ONLY = "wifi_only";
    private static final String KEY_ALLOW_LAN = "allow_lan";
    private static final String KEY_AUTO_RECONNECT = "auto_reconnect";
    private static final String KEY_TIMEOUT = "timeout_ms";
    private static final String KEY_VIBRATE = "vibrate";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String KEY_NOTIFY = "notify";
    private static final String KEY_BOOT_START = "boot_start";
    private static final String KEY_DESIRED = "service_desired";
    private static final String KEY_FIRST_LAUNCH = "first_launch_done";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int getPort() {
        int p = sp.getInt(KEY_PORT, DEFAULT_PORT);
        return validPort(p) ? p : DEFAULT_PORT;
    }

    public void setPort(int port) {
        if (validPort(port)) {
            sp.edit().putInt(KEY_PORT, port).apply();
        }
    }

    public boolean isWifiOnly() {
        return sp.getBoolean(KEY_WIFI_ONLY, true);
    }

    public void setWifiOnly(boolean v) {
        sp.edit().putBoolean(KEY_WIFI_ONLY, v).apply();
    }

    public boolean isAllowLan() {
        return sp.getBoolean(KEY_ALLOW_LAN, true);
    }

    public void setAllowLan(boolean v) {
        sp.edit().putBoolean(KEY_ALLOW_LAN, v).apply();
    }

    public boolean isAutoReconnect() {
        return sp.getBoolean(KEY_AUTO_RECONNECT, true);
    }

    public void setAutoReconnect(boolean v) {
        sp.edit().putBoolean(KEY_AUTO_RECONNECT, v).apply();
    }

    public int getTimeoutMs() {
        int t = sp.getInt(KEY_TIMEOUT, 5000);
        if (t < 1000 || t > 15000) {
            return 5000;
        }
        return t;
    }

    public void setTimeoutMs(int ms) {
        sp.edit().putInt(KEY_TIMEOUT, ms).apply();
    }

    public boolean isVibrate() {
        return sp.getBoolean(KEY_VIBRATE, true);
    }

    public void setVibrate(boolean v) {
        sp.edit().putBoolean(KEY_VIBRATE, v).apply();
    }

    public boolean isKeepScreenOn() {
        return sp.getBoolean(KEY_KEEP_SCREEN_ON, false);
    }

    public void setKeepScreenOn(boolean v) {
        sp.edit().putBoolean(KEY_KEEP_SCREEN_ON, v).apply();
    }

    public boolean isNotify() {
        return sp.getBoolean(KEY_NOTIFY, true);
    }

    public void setNotify(boolean v) {
        sp.edit().putBoolean(KEY_NOTIFY, v).apply();
    }

    public boolean isBootStart() {
        return sp.getBoolean(KEY_BOOT_START, false);
    }

    public void setBootStart(boolean v) {
        sp.edit().putBoolean(KEY_BOOT_START, v).apply();
    }

    public boolean isDesiredRunning() {
        return sp.getBoolean(KEY_DESIRED, false);
    }

    public void setDesiredRunning(boolean v) {
        sp.edit().putBoolean(KEY_DESIRED, v).apply();
    }

    public boolean isFirstLaunchDone() {
        return sp.getBoolean(KEY_FIRST_LAUNCH, false);
    }

    public void setFirstLaunchDone() {
        sp.edit().putBoolean(KEY_FIRST_LAUNCH, true).apply();
    }

    public static boolean validPort(int port) {
        return port >= MIN_PORT && port <= MAX_PORT;
    }
}

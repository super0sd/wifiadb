package com.yg.wifiadb.core;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 无线调试（adbd TCP 监听）的状态机与开关实现。
 *
 * <p>普通应用无法直接修改 adbd 的属性，因此按优先级尝试：
 * 1) root：su 执行 setprop + 重启 adbd；
 * 2) Shizuku：通过已授权的 Shizuku 进程执行同样命令；
 * 3) 均不可用时返回 NEED_PERMISSION，并给出可在 PC 上执行的手动命令。</p>
 */
public class AdbWifiManager {

    public enum State {
        RUNNING, STOPPED, NEED_PERMISSION, ERROR
    }

    public static class Status {
        public State state = State.STOPPED;
        public int port;
        public int propPort = -1;
        public boolean listening;
        public boolean rootAvailable;
        public boolean shizukuAvailable;
        public boolean wirelessDebuggingEnabled;
        public boolean usbDebuggingEnabled;
        public String mode = "none";
        public String adbdState = "";
        public String wifiIp;
        public String message = "";
    }

    public interface Callback {
        void onDone(Status status);
    }

    private final Context context;
    private final Prefs prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public AdbWifiManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = new Prefs(this.context);
    }

    public Status inspect() {
        Status s = new Status();
        s.port = prefs.getPort();
        s.rootAvailable = Shell.hasRoot();
        s.shizukuAvailable = ShizukuBridge.available();
        s.usbDebuggingEnabled = readGlobalFlag(Settings.Global.ADB_ENABLED);
        s.wirelessDebuggingEnabled = readGlobalFlag("adb_wifi_enabled");
        s.adbdState = Shell.getProp("init.svc.adbd");
        int propPort = parseInt(Shell.getProp("service.adb.tcp.port"), -1);
        s.propPort = propPort;
        s.listening = NetworkUtils.isLocalPortListening(s.port)
                || NetworkUtils.isLoopbackConnectable(s.port);
        s.wifiIp = NetworkUtils.getLocalIpv4();

        if (s.listening) {
            s.state = State.RUNNING;
            s.mode = s.rootAvailable ? "root" : (s.shizukuAvailable ? "shizuku" : "unknown");
        } else if (propPort > 0) {
            s.state = State.ERROR;
            s.message = "端口 " + propPort + " 已配置，但 adbd 未在该端口监听";
        } else {
            s.state = State.STOPPED;
            s.message = s.rootAvailable ? "已就绪，可开启无线调试"
                    : (s.shizukuAvailable ? "可通过 Shizuku 开启" : "需要 Root 或 Shizuku 授权");
        }
        return s;
    }

    public void enableAsync(final int port, final Callback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Status s = enableBlocking(port);
                if (callback != null) {
                    callback.onDone(s);
                }
            }
        });
    }

    public void disableAsync(final Callback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Status s = disableBlocking();
                if (callback != null) {
                    callback.onDone(s);
                }
            }
        });
    }

    public Status enableBlocking(int port) {
        Status status = new Status();
        status.port = port;
        status.rootAvailable = Shell.hasRoot();
        status.shizukuAvailable = ShizukuBridge.available();
        status.wifiIp = NetworkUtils.getLocalIpv4();

        if (!Prefs.validPort(port)) {
            status.state = State.ERROR;
            status.message = "端口需在 1024–65535 之间";
            return status;
        }
        if (prefs.isWifiOnly() && !NetworkUtils.isLocalNetwork(context)) {
            status.state = State.ERROR;
            status.message = "未连接到 Wi-Fi / 局域网，请先连接后再开启";
            return status;
        }
        if (NetworkUtils.isLocalPortListening(port) || NetworkUtils.isLoopbackConnectable(port)) {
            status.state = State.RUNNING;
            status.listening = true;
            status.propPort = port;
            status.mode = status.rootAvailable ? "root" : (status.shizukuAvailable ? "shizuku" : "unknown");
            status.message = "端口已在监听";
            return status;
        }

        String listen = prefs.isAllowLan() ? "" : "; setprop service.adb.listen_addrs 127.0.0.1";
        String command = "setprop service.adb.tcp.port " + port + listen
                + "; setprop ctl.restart adbd; setprop init.svc.adbd";

        Shell.Result result = null;
        String mode = null;

        if (status.rootAvailable) {
            result = Shell.runRoot(command, 8000);
            mode = "root";
        }
        if ((result == null || !waitListening(port, 4000)) && ShizukuBridge.available()
                && ShizukuBridge.granted()) {
            Shell.Result sr = ShizukuBridge.run(command, 8000);
            if (sr != null) {
                result = sr;
                mode = "shizuku";
            }
        }

        boolean up = waitListening(port, 6000);
        if (up) {
            status.state = State.RUNNING;
            status.listening = true;
            status.propPort = port;
            status.mode = mode == null ? "unknown" : mode;
            status.message = "无线调试已开启";
            return status;
        }

        // 兜底：部分 ROM 只认 stop/start。
        String fallback = "setprop service.adb.tcp.port " + port
                + (prefs.isAllowLan() ? "" : "; setprop service.adb.listen_addrs 127.0.0.1")
                + "; stop adbd; start adbd";
        if (status.rootAvailable) {
            Shell.runRoot(fallback, 8000);
            mode = "root";
        } else if (ShizukuBridge.available() && ShizukuBridge.granted()) {
            ShizukuBridge.run(fallback, 8000);
            mode = "shizuku";
        }
        if (waitListening(port, 5000)) {
            status.state = State.RUNNING;
            status.listening = true;
            status.propPort = port;
            status.mode = mode == null ? "unknown" : mode;
            status.message = "无线调试已开启";
            return status;
        }

        status.state = State.NEED_PERMISSION;
        status.mode = "none";
        StringBuilder sb = new StringBuilder();
        if (!status.rootAvailable && !status.shizukuAvailable) {
            sb.append("未检测到 Root 或 Shizuku。");
        } else {
            sb.append("已尝试提权开启，但 adbd 未监听端口。");
        }
        if (result != null && !result.output.isEmpty()) {
            sb.append("\n").append(result.output);
        }
        sb.append("\n可在 PC 上执行：").append(manualEnableCommand(port));
        status.message = sb.toString();
        return status;
    }

    public Status disableBlocking() {
        Status status = new Status();
        status.port = prefs.getPort();
        status.rootAvailable = Shell.hasRoot();
        status.shizukuAvailable = ShizukuBridge.available();
        status.wifiIp = NetworkUtils.getLocalIpv4();

        String command = "setprop service.adb.tcp.port -1; setprop ctl.restart adbd; stop adbd; start adbd";
        String mode = "none";
        if (status.rootAvailable) {
            Shell.runRoot(command, 8000);
            mode = "root";
        } else if (ShizukuBridge.available() && ShizukuBridge.granted()) {
            ShizukuBridge.run(command, 8000);
            mode = "shizuku";
        } else {
            status.state = State.NEED_PERMISSION;
            status.message = "关闭 TCP 监听需要 Root 或 Shizuku 授权";
            return status;
        }

        boolean closed = waitClosed(status.port, 5000);
        status.propPort = parseInt(Shell.getProp("service.adb.tcp.port"), -1);
        status.mode = mode;
        if (closed || status.propPort <= 0) {
            status.state = State.STOPPED;
            status.listening = false;
            status.message = "无线调试已关闭";
        } else {
            status.state = State.ERROR;
            status.listening = true;
            status.message = "端口仍在监听，可能被其他进程占用";
        }
        return status;
    }

    /** 手动开启命令（在 PC 上通过 USB 执行一次即可永久生效于当前 adbd 生命周期）。 */
    public static String manualEnableCommand(int port) {
        return "adb shell \"setprop service.adb.tcp.port " + port + "; setprop ctl.restart adbd\"";
    }

    public static String manualDisableCommand() {
        return "adb shell \"setprop service.adb.tcp.port -1; setprop ctl.restart adbd\"";
    }

    /** 当前 adbd 是否已配置 TCP 端口（不要求端口可连）。 */
    public static boolean isTcpConfigured() {
        int p = parseInt(Shell.getProp("service.adb.tcp.port"), -1);
        return p > 0;
    }

    private boolean waitListening(int port, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (NetworkUtils.isLocalPortListening(port) || NetworkUtils.isLoopbackConnectable(port)) {
                return true;
            }
            sleep(300);
        }
        return NetworkUtils.isLocalPortListening(port) || NetworkUtils.isLoopbackConnectable(port);
    }

    private boolean waitClosed(int port, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!NetworkUtils.isLocalPortListening(port) && !NetworkUtils.isLoopbackConnectable(port)) {
                return true;
            }
            sleep(300);
        }
        return !NetworkUtils.isLocalPortListening(port) && !NetworkUtils.isLoopbackConnectable(port);
    }

    private boolean readGlobalFlag(String key) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                return Settings.Global.getInt(context.getContentResolver(), key, 0) != 0;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static int parseInt(String value, int def) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Shizuku 反射桥，未安装/未授权时安全降级。 */
    static final class ShizukuBridge {

        private ShizukuBridge() {
        }

        static boolean available() {
            try {
                Class<?> clazz = Class.forName("rikka.shizuku.Shizuku");
                Object ping = clazz.getMethod("pingBinder").invoke(null);
                return ping instanceof Boolean && (Boolean) ping;
            } catch (Throwable e) {
                return false;
            }
        }

        static boolean granted() {
            try {
                Class<?> clazz = Class.forName("rikka.shizuku.Shizuku");
                Method m = clazz.getMethod("checkSelfPermission");
                Object v = m.invoke(null);
                return v instanceof Integer && (Integer) v == 0;
            } catch (Throwable e) {
                return false;
            }
        }

        static Shell.Result run(String command, int timeoutMs) {
            Process p = null;
            try {
                Class<?> clazz = Class.forName("rikka.shizuku.Shizuku");
                Method m = clazz.getMethod("newProcess", String[].class, String[].class, String.class);
                Object obj = m.invoke(null, new String[]{"/system/bin/sh", "-c", command}, null, null);
                if (!(obj instanceof Process)) {
                    return null;
                }
                p = (Process) obj;
                StringBuilder sb = new StringBuilder();
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() < 32 * 1024) {
                        sb.append(line).append('\n');
                    }
                }
                boolean done = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!done) {
                    p.destroy();
                    return new Shell.Result(-2, sb.toString());
                }
                return new Shell.Result(p.exitValue(), sb.toString().trim());
            } catch (Throwable e) {
                return new Shell.Result(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (p != null) {
                    try {
                        p.destroy();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }
}

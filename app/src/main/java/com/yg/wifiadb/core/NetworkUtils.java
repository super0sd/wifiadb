package com.yg.wifiadb.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** 网络发现、校验与连通性探测。 */
public final class NetworkUtils {

    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$");

    private NetworkUtils() {
    }

    /** 返回最适合展示给 PC 的局域网 IPv4，优先 wlan 接口。 */
    public static String getLocalIpv4() {
        List<String> all = listLocalIpv4();
        for (String ip : all) {
            if (ip.startsWith("192.168.") || ip.startsWith("10.") || is172Private(ip)) {
                return ip;
            }
        }
        return all.isEmpty() ? null : all.get(0);
    }

    public static List<String> listLocalIpv4() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif.isLoopback()) {
                    continue;
                }
                String name = nif.getName() == null ? "" : nif.getName();
                if (!(name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")
                        || name.startsWith("swlan"))) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (!TextUtils.isEmpty(ip) && !result.contains(ip)) {
                            result.add(ip);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (result.isEmpty()) {
            // 部分 ROM 的接口命名不同，退化为全接口扫描。
            try {
                for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (nif.isLoopback()) {
                        continue;
                    }
                    for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            String ip = addr.getHostAddress();
                            if (!result.contains(ip)) {
                                result.add(ip);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static boolean is172Private(String ip) {
        if (!ip.startsWith("172.")) {
            return false;
        }
        String[] parts = ip.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 当前是否处于 Wi-Fi / 以太网网络。 */
    @SuppressWarnings("deprecation")
    public static boolean isLocalNetwork(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) {
                return false;
            }
            int type = info.getType();
            return type == ConnectivityManager.TYPE_WIFI || type == ConnectivityManager.TYPE_ETHERNET;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    public static boolean isWifiEnabled(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isValidHost(String host) {
        if (TextUtils.isEmpty(host)) {
            return false;
        }
        String h = host.trim();
        if (h.length() > 253) {
            return false;
        }
        return IPV4.matcher(h).matches() || HOSTNAME.matcher(h).matches();
    }

    public static boolean tcpProbe(String host, int port, int timeoutMs) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 读取 /proc/net/tcp{,6} 判断本机端口是否处于 LISTEN。 */
    public static boolean isLocalPortListening(int port) {
        if (port <= 0) {
            return false;
        }
        String[] files = {"/proc/net/tcp", "/proc/net/tcp6"};
        for (String file : files) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(file));
                String line;
                boolean header = true;
                while ((line = reader.readLine()) != null) {
                    if (header) {
                        header = false;
                        continue;
                    }
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    String[] parts = trimmed.split("\\s+");
                    if (parts.length < 4) {
                        continue;
                    }
                    String[] local = parts[1].split(":");
                    if (local.length != 2) {
                        continue;
                    }
                    int p;
                    try {
                        p = Integer.parseInt(local[1], 16);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (p == port && "0A".equalsIgnoreCase(parts[3])) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return false;
    }

    /** 本机端口是否可连接（LISTEN 且可握手），作为 /proc 的兜底判断。 */
    public static boolean isLoopbackConnectable(int port) {
        return tcpProbe("127.0.0.1", port, 400);
    }
}

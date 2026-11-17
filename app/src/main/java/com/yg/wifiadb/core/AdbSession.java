package com.yg.wifiadb.core;

import android.util.Log;

import android.hardware.usb.UsbManager;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** 面向 UI 的 ADB 会话封装：建立连接、执行 shell、读取设备型号。 */
public class AdbSession implements Closeable {

    private static final String TAG = "WiFiAdb";

    private final AdbConnection connection;
    private final String target;

    private AdbSession(AdbConnection connection, String target) {
        this.connection = connection;
        this.target = target;
    }

    public static AdbSession openTcp(File keyDir, String host, int port, int timeoutMs,
                                     AdbConnection.ProgressListener listener) throws IOException {
        TcpAdbTransport transport = new TcpAdbTransport(host, port, Math.min(timeoutMs, 8000));
        return open(transport, keyDir, timeoutMs, listener);
    }

    public static AdbSession openUsb(File keyDir, UsbManager manager,
                                     android.hardware.usb.UsbDevice device, int timeoutMs,
                                     AdbConnection.ProgressListener listener) throws IOException {
        UsbAdbTransport transport = new UsbAdbTransport(manager, device);
        return open(transport, keyDir, timeoutMs, listener);
    }

    private static AdbSession open(AdbTransport transport, File keyDir, int timeoutMs,
                                   AdbConnection.ProgressListener listener) throws IOException {
        AdbCrypto crypto = AdbCrypto.loadOrCreate(keyDir);
        AdbConnection connection = new AdbConnection(transport, crypto);
        try {
            // 首次连接需要在设备上人工确认调试授权，等待时间应明显长于普通连接超时。
            int handshakeTimeout = Math.max(timeoutMs * 3, 20000);
            connection.handshake(handshakeTimeout, listener);
        } catch (IOException e) {
            Log.d(TAG, "handshake failed: " + e.getClass().getSimpleName() + " / " + e.getMessage());
            connection.close();
            throw e;
        }
        return new AdbSession(connection, transport.describe());
    }

    public String exec(String command, int timeoutMs) throws IOException {
        return connection.shell(command, timeoutMs, null);
    }

    public String model(int timeoutMs) {
        try {
            String out = exec("getprop ro.product.model", timeoutMs);
            String model = out.replace("\r", "").trim();
            if (model.isEmpty()) {
                return "ADB 设备";
            }
            return model;
        } catch (IOException e) {
            return "ADB 设备";
        }
    }

    public String target() {
        return target;
    }

    @Override
    public void close() {
        connection.close();
    }

    public static String friendlyError(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = e.getClass().getSimpleName();
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("connection refused")) {
            return "连接被拒绝：请确认设备已开启无线调试与端口";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "连接超时：请检查 IP、端口与局域网连通性";
        }
        if (lower.contains("unreachable") || lower.contains("no route")) {
            return "网络不可达：请确认两台设备在同一局域网";
        }
        if (lower.contains("authoriz") || lower.contains("授权")) {
            return msg;
        }
        return msg;
    }
}

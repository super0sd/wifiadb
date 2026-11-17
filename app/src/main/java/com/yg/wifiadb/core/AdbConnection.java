package com.yg.wifiadb.core;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/** 一条 ADB 会话：CNXN 握手、AUTH 认证、OPEN 子流执行 shell。 */
public class AdbConnection implements Closeable {

    public interface ProgressListener {
        void onMessage(String message);
    }

    private static final int MAX_OUTPUT = 512 * 1024;

    private final AdbTransport transport;
    private final AdbCrypto crypto;
    private int nextLocalId = 1;
    private volatile boolean closed;

    public AdbConnection(AdbTransport transport, AdbCrypto crypto) {
        this.transport = transport;
        this.crypto = crypto;
    }

    public void handshake(int timeoutMs, ProgressListener listener) throws IOException {
        send(AdbProtocol.A_CNXN, AdbProtocol.VERSION, AdbProtocol.MAX_DATA,
                "host::features=shell_v2,cmd,stat_v2\0".getBytes(StandardCharsets.UTF_8));

        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean signatureSent = false;
        boolean keySent = false;

        while (System.currentTimeMillis() < deadline) {
            AdbProtocol.Message message;
            try {
                message = readFrame(deadline);
            } catch (SocketTimeoutException e) {
                continue;
            }
            Log.d("WiFiAdb", "recv " + message.commandName() + " arg0=" + message.arg0
                    + " arg1=" + message.arg1 + " len=" + message.data.length);
            if (message.command == AdbProtocol.A_CNXN) {
                return;
            }
            if (message.command == AdbProtocol.A_AUTH) {
                if (message.arg0 == AdbProtocol.AUTH_TOKEN) {
                    if (!signatureSent) {
                        byte[] signature = crypto.sign(message.data);
                        send(AdbProtocol.A_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, signature);
                        signatureSent = true;
                    } else {
                        if (keySent) {
                            throw new IOException("设备拒绝了调试密钥，请在设备上删除旧授权后重试");
                        }
                        byte[] pub = (crypto.adbPublicKeyString() + "\0").getBytes(StandardCharsets.UTF_8);
                        send(AdbProtocol.A_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, pub);
                        keySent = true;
                        if (listener != null) {
                            listener.onMessage("请在设备上确认 USB 调试授权");
                        }
                    }
                } else if (message.arg0 == AdbProtocol.AUTH_RSAPUBLICKEY) {
                    byte[] pub = (crypto.adbPublicKeyString() + "\0").getBytes(StandardCharsets.UTF_8);
                    send(AdbProtocol.A_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, pub);
                    keySent = true;
                    if (listener != null) {
                        listener.onMessage("请在设备上确认 USB 调试授权");
                    }
                }
                continue;
            }
            if (message.command == AdbProtocol.A_STLS) {
                // 本实现不启用 ADB over TLS，保持明文传输；设备端会继续等待 CNXN。
                continue;
            }
        }
        throw new IOException("ADB 握手超时：请确认设备已开启 USB 调试并允许本机授权");
    }

    public String shell(String command, int timeoutMs, ProgressListener listener) throws IOException {
        int localId = nextLocalId++;
        String payload = "shell:" + command + "\0";
        send(AdbProtocol.A_OPEN, localId, 0, payload.getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int remoteId = -1;
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            AdbProtocol.Message message;
            try {
                message = readFrame(deadline);
            } catch (SocketTimeoutException e) {
                continue;
            }
            Log.d("WiFiAdb", "shell recv " + message.commandName() + " arg0=" + message.arg0
                    + " arg1=" + message.arg1 + " len=" + message.data.length);
            switch (message.command) {
                case AdbProtocol.A_OKAY:
                    if (remoteId < 0) {
                        remoteId = message.arg0;
                    }
                    break;
                case AdbProtocol.A_WRTE:
                    if (message.arg1 == localId) {
                        if (output.size() < MAX_OUTPUT) {
                            output.write(message.data);
                        }
                        send(AdbProtocol.A_OKAY, localId, message.arg0 < 0 ? remoteId : message.arg0, null);
                    }
                    break;
                case AdbProtocol.A_CLSE:
                    if (message.arg1 == localId || message.arg0 == localId) {
                        send(AdbProtocol.A_CLSE, localId, remoteId < 0 ? message.arg0 : remoteId, null);
                        return output.toString("UTF-8");
                    }
                    break;
                case AdbProtocol.A_AUTH:
                    if (listener != null) {
                        listener.onMessage("会话需要重新授权");
                    }
                    break;
                default:
                    break;
            }
        }
        throw new IOException("执行超时：" + command);
    }

    /**
     * 读取一帧。超时作用于"等待下一帧"的间隙，而不是帧内切片，
     * 否则 header 已读、payload 未到时中断会造成字节流错位（表现为 CRC 校验失败）。
     */
    private AdbProtocol.Message readFrame(long deadline) throws IOException {
        long remaining = deadline - System.currentTimeMillis();
        int timeout = (int) Math.max(1200, Math.min(Integer.MAX_VALUE, remaining));
        transport.setReadTimeout(timeout);
        return AdbProtocol.read(transport.getInputStream());
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        if (closed) {
            throw new IOException("连接已关闭");
        }
        transport.getOutputStream().write(AdbProtocol.encode(command, arg0, arg1, data));
        transport.getOutputStream().flush();
    }

    @Override
    public void close() {
        closed = true;
        try {
            transport.close();
        } catch (Exception ignored) {
        }
    }
}

package com.yg.wifiadb.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** 通过 TCP 连接目标设备的 adbd。 */
public class TcpAdbTransport implements AdbTransport {

    private final Socket socket;
    private final String host;
    private final int port;
    private InputStream input;
    private OutputStream output;

    public TcpAdbTransport(String host, int port, int connectTimeoutMs) throws IOException {
        this.host = host;
        this.port = port;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setTcpNoDelay(true);
            s.setSoTimeout(connectTimeoutMs);
            this.input = s.getInputStream();
            this.output = s.getOutputStream();
        } catch (IOException e) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
            throw new IOException("无法连接 " + host + ":" + port + "（" + e.getMessage() + "）", e);
        }
        this.socket = s;
    }

    @Override
    public String describe() {
        return host + ":" + port;
    }

    @Override
    public InputStream getInputStream() {
        return input;
    }

    @Override
    public OutputStream getOutputStream() {
        return output;
    }

    @Override
    public void setReadTimeout(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
    }

    @Override
    public boolean isOpen() {
        return socket.isConnected() && !socket.isClosed();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }
}

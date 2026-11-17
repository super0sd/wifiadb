package com.yg.wifiadb.core;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;

/**
 * 通过 USB Host 直接与另一台设备的 adbd 通信。
 *
 * <p>ADB 接口固定为 class 0xFF / subclass 0x42 / protocol 0x01，采用两个 bulk 端点。</p>
 */
public class UsbAdbTransport implements AdbTransport {

    private static final int USB_CLASS_ADB = 0xFF;
    private static final int USB_SUBCLASS_ADB = 0x42;
    private static final int USB_PROTOCOL_ADB = 0x01;
    private static final int DEFAULT_TIMEOUT = 10000;

    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final UsbInterface usbInterface;
    private final UsbEndpoint endpointIn;
    private final UsbEndpoint endpointOut;
    private final InputStream input;
    private final OutputStream output;
    private int timeoutMs = DEFAULT_TIMEOUT;
    private volatile boolean open = true;

    public UsbAdbTransport(UsbManager manager, UsbDevice device) throws IOException {
        if (device == null) {
            throw new IOException("未找到 USB 设备");
        }
        UsbInterface found = null;
        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface itf = device.getInterface(i);
            if (itf.getInterfaceClass() == USB_CLASS_ADB
                    && itf.getInterfaceSubclass() == USB_SUBCLASS_ADB
                    && itf.getInterfaceProtocol() == USB_PROTOCOL_ADB) {
                found = itf;
                for (int e = 0; e < itf.getEndpointCount(); e++) {
                    UsbEndpoint ep = itf.getEndpoint(e);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                            in = ep;
                        } else if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                            out = ep;
                        }
                    }
                }
                break;
            }
        }
        if (found == null || in == null || out == null) {
            throw new IOException("该 USB 设备不是 ADB 接口（未找到 0xFF/0x42/0x01）");
        }
        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) {
            throw new IOException("无法打开 USB 设备，请检查授权");
        }
        if (!conn.claimInterface(found, true)) {
            conn.close();
            throw new IOException("claimInterface 失败，接口可能被占用");
        }
        this.device = device;
        this.connection = conn;
        this.usbInterface = found;
        this.endpointIn = in;
        this.endpointOut = out;
        this.input = new BulkInputStream();
        this.output = new BulkOutputStream();
    }

    public static boolean isAdbDevice(UsbDevice device) {
        if (device == null) {
            return false;
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface itf = device.getInterface(i);
            if (itf.getInterfaceClass() == USB_CLASS_ADB
                    && itf.getInterfaceSubclass() == USB_SUBCLASS_ADB
                    && itf.getInterfaceProtocol() == USB_PROTOCOL_ADB) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String describe() {
        return "USB " + device.getProductName() + " (" + device.getDeviceName() + ")";
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
    public void setReadTimeout(int timeoutMs) {
        this.timeoutMs = Math.max(500, timeoutMs);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
        try {
            connection.releaseInterface(usbInterface);
        } catch (Exception ignored) {
        }
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }

    private final class BulkInputStream extends InputStream {
        private final byte[] single = new byte[1];

        @Override
        public int read() throws IOException {
            int r = read(single, 0, 1);
            return r <= 0 ? -1 : (single[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (!open) {
                return -1;
            }
            if (len == 0) {
                return 0;
            }
            byte[] buffer = (off == 0 && len == b.length) ? b : new byte[len];
            int read = connection.bulkTransfer(endpointIn, buffer, len, timeoutMs);
            if (read < 0) {
                if (!open) {
                    return -1;
                }
                throw new SocketTimeoutException("USB 读取超时");
            }
            if (buffer != b) {
                System.arraycopy(buffer, 0, b, off, read);
            }
            return read;
        }
    }

    private final class BulkOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (!open) {
                throw new IOException("USB 连接已关闭");
            }
            int offset = off;
            int remaining = len;
            while (remaining > 0) {
                byte[] chunk;
                if (offset == 0 && remaining == b.length) {
                    chunk = b;
                } else {
                    chunk = new byte[remaining];
                    System.arraycopy(b, offset, chunk, 0, remaining);
                }
                int written = connection.bulkTransfer(endpointOut, chunk, remaining, timeoutMs);
                if (written <= 0) {
                    throw new IOException("USB 写入失败（" + written + "）");
                }
                offset += written;
                remaining -= written;
            }
        }
    }
}

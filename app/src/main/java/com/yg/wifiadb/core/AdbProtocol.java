package com.yg.wifiadb.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

/** ADB 传输层协议常量与帧编解码（小端序，24 字节头）。 */
public final class AdbProtocol {

    public static final int A_SYNC = 0x434e5953;
    public static final int A_CNXN = 0x4e584e43;
    public static final int A_OPEN = 0x4e45504f;
    public static final int A_OKAY = 0x59414b4f;
    public static final int A_CLSE = 0x45534c43;
    public static final int A_WRTE = 0x45545257;
    public static final int A_AUTH = 0x48545541;
    public static final int A_STLS = 0x534c5453;

    public static final int AUTH_TOKEN = 1;
    public static final int AUTH_SIGNATURE = 2;
    public static final int AUTH_RSAPUBLICKEY = 3;

    public static final int VERSION = 0x01000001;
    public static final int MAX_DATA = 256 * 1024;
    public static final int HEADER_SIZE = 24;
    public static final int MAX_MESSAGE = 1024 * 1024;

    private AdbProtocol() {
    }

    public static final class Message {
        public final int command;
        public final int arg0;
        public final int arg1;
        public final byte[] data;

        public Message(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data == null ? new byte[0] : data;
        }

        public String commandName() {
            switch (command) {
                case A_CNXN:
                    return "CNXN";
                case A_OPEN:
                    return "OPEN";
                case A_OKAY:
                    return "OKAY";
                case A_CLSE:
                    return "CLSE";
                case A_WRTE:
                    return "WRTE";
                case A_AUTH:
                    return "AUTH";
                case A_STLS:
                    return "STLS";
                default:
                    return String.format("0x%08X", command);
            }
        }
    }

    public static byte[] encode(int command, int arg0, int arg1, byte[] data) {
        byte[] payload = data == null ? new byte[0] : data;
        int len = payload.length;
        int checksum = crc32(payload);
        byte[] out = new byte[HEADER_SIZE + len];
        putInt(out, 0, command);
        putInt(out, 4, arg0);
        putInt(out, 8, arg1);
        putInt(out, 12, len);
        putInt(out, 16, checksum);
        putInt(out, 20, command ^ 0xFFFFFFFF);
        System.arraycopy(payload, 0, out, HEADER_SIZE, len);
        return out;
    }

    public static Message read(InputStream in) throws IOException {
        byte[] header = readFully(in, HEADER_SIZE);
        int command = getInt(header, 0);
        int arg0 = getInt(header, 4);
        int arg1 = getInt(header, 8);
        int length = getInt(header, 12);
        int checksum = getInt(header, 16);
        int magic = getInt(header, 20);
        if ((command ^ 0xFFFFFFFF) != magic) {
            throw new IOException("ADB 帧校验失败：magic 不匹配");
        }
        if (length < 0 || length > MAX_MESSAGE) {
            throw new IOException("ADB 帧长度异常：" + length);
        }
        byte[] payload = length == 0 ? new byte[0] : readFully(in, length);
        // 仅 CNXN / OPEN / WRTE 携带有效 data_check；A_AUTH、A_OKAY、A_CLSE 的
        // checksum 字段常为 0（adbd 历史行为），对这些命令校验会造成误判。
        boolean checkCrc = command == A_CNXN || command == A_OPEN || command == A_WRTE;
        if (checkCrc && length > 0 && crc32(payload) != checksum) {
            throw new IOException("ADB 帧数据校验失败");
        }
        return new Message(command, arg0, arg1, payload);
    }

    public static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new EOFException("连接已断开（已读取 " + offset + "/" + length + " 字节）");
            }
            offset += read;
        }
        return buffer;
    }

    public static int crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return (int) crc.getValue();
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static int getInt(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }
}

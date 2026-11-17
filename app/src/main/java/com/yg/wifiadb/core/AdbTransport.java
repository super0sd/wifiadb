package com.yg.wifiadb.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** ADB 连接的字节流抽象：TCP 或被 USB bulk 端点承载。 */
public interface AdbTransport extends Closeable {

    String describe();

    InputStream getInputStream() throws IOException;

    OutputStream getOutputStream() throws IOException;

    void setReadTimeout(int timeoutMs) throws IOException;

    boolean isOpen();
}

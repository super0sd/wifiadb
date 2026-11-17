package com.yg.wifiadb.core;

import java.util.List;

/**
 * 本机会话：目标地址就是本机（回环或本机网卡 IP）时，直接通过 root / shell 执行命令。
 * <p>
 * 本机自连走 ADB 协议时，adbd 会在 AUTH 阶段要求 RSA 签名校验，部分 ROM（含 MIUI）
 * 对本地回环连接的签名校验路径存在限制，导致无法在应用内完成握手；此时改用本地
 * shell 通道，能力等价且延迟更低。连接其它设备仍然使用完整 ADB 协议。
 */
public class LocalSession {

    private final boolean root;

    public LocalSession(boolean root) {
        this.root = root;
    }

    /** 判断目标地址是否指向本机。 */
    public static boolean isLocalTarget(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase();
        if (h.isEmpty()) {
            return false;
        }
        if ("localhost".equals(h) || "127.0.0.1".equals(h) || "::1".equals(h) || "0.0.0.0".equals(h)) {
            return true;
        }
        List<String> locals = NetworkUtils.listLocalIpv4();
        for (String ip : locals) {
            if (ip != null && ip.equals(h)) {
                return true;
            }
        }
        return false;
    }

    public boolean isRoot() {
        return root;
    }

    public String mode() {
        return root ? "本地 Root" : "本地 Shell";
    }

    public String model() {
        String m = Shell.getProp("ro.product.model");
        return m == null || m.trim().isEmpty() ? "本机" : m.trim();
    }

    /** 执行一条命令，返回合并后的输出。 */
    public String exec(String command, int timeoutMs) throws Exception {
        Shell.Result result = root
                ? Shell.runRoot(command, timeoutMs)
                : Shell.run(command, timeoutMs);
        StringBuilder sb = new StringBuilder();
        if (result.output != null && !result.output.isEmpty()) {
            sb.append(result.output);
        } else if (result.code != 0) {
            sb.append("(no output, exit ").append(result.code).append(")");
        }
        if (result.code != 0 && sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        return sb.toString();
    }

    public void close() {
        // 本地通道无常驻连接
    }
}

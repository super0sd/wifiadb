package com.yg.wifiadb.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** 轻量 shell 执行器：普通 shell 与 su 提权。 */
public final class Shell {

    private static volatile Boolean rootCache;

    private Shell() {
    }

    public static class Result {
        public final int code;
        public final String output;

        public Result(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }

        public boolean ok() {
            return code == 0;
        }
    }

    public static Result run(String command, int timeoutMs) {
        return exec(new String[]{"/system/bin/sh", "-c", command}, timeoutMs);
    }

    public static Result runRoot(String command, int timeoutMs) {
        return exec(new String[]{"su", "-c", command}, timeoutMs);
    }

    public static Result exec(String[] argv, int timeoutMs) {
        Process process = null;
        StringBuilder sb = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            process = pb.start();
            final Process p = process;
            final StringBuilder buffer = sb;
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    BufferedReader br = null;
                    try {
                        br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (buffer.length() < 64 * 1024) {
                                buffer.append(line).append('\n');
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (br != null) {
                            try {
                                br.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }, "shell-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                return new Result(-2, sb.toString() + "\n[timeout]");
            }
            reader.join(300);
            return new Result(process.exitValue(), sb.toString().trim());
        } catch (Exception e) {
            return new Result(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 是否拥有 root（su 可用且返回 uid=0），结果缓存。 */
    public static boolean hasRoot() {
        Boolean cached = rootCache;
        if (cached != null) {
            return cached;
        }
        Result r = runRoot("id", 2500);
        boolean ok = r.ok() && r.output.contains("uid=0");
        rootCache = ok;
        return ok;
    }

    public static void resetRootCache() {
        rootCache = null;
    }

    /** 读取系统属性。 */
    public static String getProp(String key) {
        Result r = run("getprop " + key, 1500);
        return r.ok() ? r.output.trim() : "";
    }
}

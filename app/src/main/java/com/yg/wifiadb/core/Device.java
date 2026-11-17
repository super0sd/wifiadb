package com.yg.wifiadb.core;

import org.json.JSONException;
import org.json.JSONObject;

/** 一台历史设备（ADB 客户端连接目标）。 */
public class Device {

    public static final int STATE_UNKNOWN = 0;
    public static final int STATE_CONNECTED = 1;
    public static final int STATE_FAILED = 2;

    public final String host;
    public final int port;
    public String name;
    public long lastConnectedAt;
    public int lastState = STATE_UNKNOWN;

    public Device(String host, int port, String name) {
        this.host = host;
        this.port = port;
        this.name = name;
    }

    public String key() {
        return host + ":" + port;
    }

    public String address() {
        return host + ":" + port;
    }

    public String displayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name;
        }
        return "未命名设备";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("host", host);
        o.put("port", port);
        o.put("name", name == null ? "" : name);
        o.put("last", lastConnectedAt);
        o.put("state", lastState);
        return o;
    }

    public static Device fromJson(JSONObject o) {
        String host = o.optString("host", "");
        int port = o.optInt("port", Prefs.DEFAULT_PORT);
        Device d = new Device(host, port, o.optString("name", ""));
        d.lastConnectedAt = o.optLong("last", 0L);
        d.lastState = o.optInt("state", STATE_UNKNOWN);
        return d;
    }
}

package com.yg.wifiadb.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 历史设备持久化（SharedPreferences + JSON）。 */
public class DeviceStore {

    private static final String FILE = "wifi_adb_devices";
    private static final String KEY_LIST = "devices";

    private final SharedPreferences sp;

    public DeviceStore(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public synchronized List<Device> list() {
        List<Device> out = new ArrayList<>();
        String raw = sp.getString(KEY_LIST, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Device d = Device.fromJson(o);
                if (d.host != null && !d.host.isEmpty()) {
                    out.add(d);
                }
            }
        } catch (JSONException ignored) {
            // 数据损坏时返回空列表，下一次写入自动修复。
        }
        Collections.sort(out, new Comparator<Device>() {
            @Override
            public int compare(Device a, Device b) {
                return Long.compare(b.lastConnectedAt, a.lastConnectedAt);
            }
        });
        return out;
    }

    public synchronized Device find(String host, int port) {
        for (Device d : list()) {
            if (d.host.equalsIgnoreCase(host) && d.port == port) {
                return d;
            }
        }
        return null;
    }

    public synchronized Device addOrUpdate(String host, int port, String name) {
        List<Device> all = list();
        Device found = null;
        for (Device d : all) {
            if (d.host.equalsIgnoreCase(host) && d.port == port) {
                found = d;
                break;
            }
        }
        if (found == null) {
            found = new Device(host, port, name);
            all.add(found);
        } else if (name != null && !name.trim().isEmpty()) {
            found.name = name;
        }
        save(all);
        return found;
    }

    public synchronized void touch(Device device, int state) {
        List<Device> all = list();
        for (Device d : all) {
            if (d.key().equals(device.key())) {
                d.lastConnectedAt = System.currentTimeMillis();
                d.lastState = state;
                break;
            }
        }
        save(all);
    }

    public synchronized void rename(Device device, String name) {
        List<Device> all = list();
        for (Device d : all) {
            if (d.key().equals(device.key())) {
                d.name = name == null ? "" : name;
                break;
            }
        }
        save(all);
    }

    public synchronized void remove(Device device) {
        List<Device> all = list();
        List<Device> keep = new ArrayList<>();
        for (Device d : all) {
            if (!d.key().equals(device.key())) {
                keep.add(d);
            }
        }
        save(keep);
    }

    private void save(List<Device> all) {
        JSONArray arr = new JSONArray();
        for (Device d : all) {
            try {
                arr.put(d.toJson());
            } catch (JSONException ignored) {
            }
        }
        sp.edit().putString(KEY_LIST, arr.toString()).apply();
    }
}

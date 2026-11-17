# WiFi ADB

[![Release](https://img.shields.io/github/v/release/super0sd/wifiadb?label=release)](https://github.com/super0sd/wifiadb/releases/latest)
[![Platform](https://img.shields.io/badge/Android-6.0%2B-3DDC84)](https://github.com/super0sd/wifiadb)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

> **下载**：[WiFiADB-1.0.0-release.apk](https://github.com/super0sd/wifiadb/releases/download/v1.0.0/WiFiADB-1.0.0-release.apk)（4.0 MB，已签名）

在 Android 手机上**开启无线调试（ADB over Wi-Fi）**，并用同一份 App **作为 ADB 客户端**连接局域网内的设备，附带命令行面板。


界面参考绿色圆环开关：首页一个大圆环控制开关，独立设置页分「网络配置 / 端口设置 / 设备管理 / 连接偏好 / 权限说明 / 关于」六个区块。

---

## 1. 功能

### 首页
- **绿色圆环开关**：开启 / 关闭本机 adbd 的 TCP 监听端口（`service.adb.tcp.port` + 重启 adbd）
- **状态行**：`无线调试运行中 · 端口 5555 · Root`，实时反映 adbd 监听状态、实现方式（Root / Shizuku / 手动）
- **IP 卡片**：显示 `IP:端口`，一键复制
- **命令卡片**：显示 `adb connect IP:端口`，一键复制
- **USB 命令行**：插入 USB 后点一下即可对 USB 设备执行 shell 命令（走 ADB 协议 + USB 传输）
- **更多**：开机自启、ADB 命令手册、ADB 键盘说明、设置入口

### 设置页
| 区块 | 内容 |
|---|---|
| 网络配置 | 仅允许 Wi-Fi 网络、允许局域网内任意设备连接、本机局域网地址（可刷新） |
| 端口设置 | 端口输入（1024–65535 校验）、保存、5555 / 5556 / 5599 快捷 chips（选中态高亮） |
| 设备管理 | IP/主机名 + 端口添加、历史设备列表（名称 / 地址 / 状态）、点击连接、长按菜单（连接并打开命令行 / 重命名 / 断开复位 / 删除） |
| 连接偏好 | 断线自动重连、连接成功震动、保持屏幕常亮、状态通知、连接超时 3/5/10 秒 |
| 权限说明 | 通知权限 / USB 调试开关 / Root / Shizuku / 服务状态实时检测，三个跳转入口 |
| 关于 | 包名、支持版本、数据本地存储说明 |

### 命令行面板
底部弹出面板，内置 8 条常用命令（型号、系统版本、分辨率、DPI、电量、第三方应用、回桌面、/data 空间），也可自行输入；深色终端样式输出。

---

## 2. 项目结构

```
Wi-Fi adb/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── local.properties            # sdk.dir
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/yg/wifiadb/
        │   ├── App.java                     # Application，通知渠道
        │   ├── core/
        │   │   ├── Prefs.java               # SharedPreferences 配置项
        │   │   ├── Device.java              # 设备模型（host/port/name/last/state）
        │   │   ├── DeviceStore.java         # 历史设备持久化（JSON）
        │   │   ├── NetworkUtils.java        # 本机 IP、网络判断、TCP 探测、/proc/net 监听检测
        │   │   ├── Shell.java               # shell / su 执行封装（带超时）
        │   │   ├── LocalSession.java        # 本机目标：走 root/shizuku 本地通道
        │   │   ├── AdbWifiManager.java      # 开启/关闭无线调试，状态巡检
        │   │   ├── AdbProtocol.java         # ADB 帧编解码 + CRC32
        │   │   ├── AdbCrypto.java           # RSA2048 密钥、AUTH token 签名、ADB 公钥序列化
        │   │   ├── AdbTransport.java        # 传输抽象
        │   │   ├── TcpAdbTransport.java     # TCP 传输
        │   │   ├── UsbAdbTransport.java     # USB(Bulk) 传输
        │   │   ├── AdbConnection.java       # CNXN/AUTH 握手、shell 流
        │   │   └── AdbSession.java          # 会话门面 + 友好错误文案
        │   ├── service/
        │   │   ├── AdbWifiService.java      # 前台服务，30s 巡检，通知状态
        │   │   └── BootReceiver.java        # 开机自启
        │   ├── ui/
        │   │   ├── MainActivity.java        # 首页
        │   │   ├── SettingsActivity.java    # 设置页
        │   │   └── CommandSheet.java        # 命令行底部面板
        │   └── util/UiUtils.java
        └── res/  (layout 5 / drawable 29 / values 4 / mipmap 2)
```

---

## 3. 构建

```bash
# 环境：JDK 17、Android SDK（build-tools 36.0.0）、Gradle 9.6.0
echo "sdk.dir=/Users/<you>/Library/Android/sdk" > local.properties
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

配置：`compileSdk 35` / `minSdk 23`（Android 6.0+）/ `targetSdk 35` / Java 17 / AGP 9.4.0。

---

## 4. 权限与配置

`AndroidManifest.xml`：

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.VIBRATE"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>

<uses-feature android:name="android.hardware.usb.host" android:required="false"/>
<uses-feature android:name="android.hardware.wifi" android:required="false"/>
```

- `AdbWifiService`：`foregroundServiceType="dataSync"`，Android 10+ 常驻通知显示端口与状态
- `BootReceiver`：仅在「开机自启 + 上次是开启状态」时拉起服务
- 通知权限：Android 13+ 首次进入首页时申请

**开启无线调试的三条路径**（`AdbWifiManager` 依次尝试）：

1. **Root**：`su -c "setprop service.adb.tcp.port <port>; setprop ctl.restart adbd"`（可选 `service.adb.listen_addrs 127.0.0.1` 限制仅本机）
2. **Shizuku**：反射 `rikka.shizuku.Shizuku.newProcess(...)` 执行同样的 setprop
3. **兜底**：`stop adbd; start adbd`；全部失败时返回「需要授权」，并在权限说明里给出可复制的 PC 端命令

---

## 5. 连接策略（重要）

点击设备行时按目标地址自动分流：

| 目标 | 通道 | 说明 |
|---|---|---|
| 本机（`127.0.0.1` / 本机网卡 IP）且 Root 可用 | **本地 Root 通道**（`LocalSession`） | 直接 shell 执行，能力等价、零握手延迟 |
| 本机但无 Root | ADB over TCP | 需要 adbd 授权 |
| 局域网其它设备 | **ADB over TCP**（`AdbSession`） | 需要目标设备弹出「允许 USB 调试」并确认 |

ADB 客户端为自研实现，与官方协议一致：

- `CNXN` 携带 `host::features=shell_v2,cmd,stat_v2`
- 收到 `A_AUTH(AUTH_TOKEN)` → 用本地 RSA2048 私钥做 `SHA1withRSA` 签名回 `AUTH_SIGNATURE`
- 若被拒 → 回 `AUTH_RSAPUBLICKEY`（ADB 公钥结构：`words(64) + n0inv + n[256] + rr[256] + exponent`，小端，base64 + ` user@host`）
- 成功后 `OPEN "shell:<cmd>"` 收发 `WRTE` / `CLSE`

> 说明：在 MI 8（Android 10 / MIUI）真机上，App 连接**本机**回环地址时 adbd 会反复要求 re-auth 而不返回 CNXN（同一密钥用 PC 端 `adb connect` 正常，签名经 openssl 校验为 `Verified OK`），属于该 ROM 的 adbd 本地认证限制。因此本机目标自动走本地 Root 通道，**连接 → 命令行 → 执行命令**的完整闭环在真机上已验证可用；连接**其它**设备时仍使用完整 ADB 协议。

---

## 6. 已验证

真机 Xiaomi MI 8（Android 10，Magisk Root）：

- [x] 首页圆环开启无线调试，状态变为「关闭 / 运行中」，`/proc/net/tcp6` 出现 `0.0.0.0:15B3 LISTEN`
- [x] PC 端 `adb devices` 出现 `192.168.0.104:5555  device`，可执行 `adb -s 192.168.0.104:5555 shell`
- [x] IP 卡片复制、`adb connect` 命令复制
- [x] 关闭开关后端口释放、通知与前台服务停止
- [x] 设置页六大区块交互：端口校验与 chips 高亮、设备增删改、超时切换、权限状态实时检测
- [x] 设备行连接 → 命令行面板 → 执行 `getprop ro.product.model` 返回 `MI 8`
- [x] 开机自启开关、命令手册、键盘说明弹窗

---

## 7. 数据存储

| 文件 | 位置 | 内容 |
|---|---|---|
| `wifi_adb_prefs` | SharedPreferences | 端口、开关类偏好、超时、开机自启、期望运行状态 |
| `wifi_adb_devices` | SharedPreferences | 历史设备 JSON 数组 |
| `files/adbkey` / `files/adbkey.pub` | 应用私有目录 | ADB RSA2048 密钥对 |

所有数据仅存本机应用目录，不做任何上传。

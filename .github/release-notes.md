## WiFi ADB v1.0.0

在 Android 手机上**一键开启无线调试**，并用同一份 App 作为 **ADB 客户端**连接局域网设备。

### 功能
- 首页绿色圆环开关：Root / Shizuku 双通道开启 adbd 的 TCP 监听，状态行实时显示端口与实现方式
- IP 卡片与 `adb connect` 命令一键复制
- 独立设置页：网络配置 · 端口设置 · 设备管理 · 连接偏好 · 权限说明 · 关于
- 历史设备管理（连接 / 重命名 / 断开复位 / 删除），点击即连接
- 命令行面板：8 条常用命令预置 + 自定义输入，深色终端样式输出
- 前台服务 30s 巡检、开机自启、状态通知、Android 13+ 通知权限申请
- USB 连接另一台设备后可直接执行 shell（ADB over USB）

### 内置 ADB 协议栈
`CNXN` → `A_AUTH`（`SHA1withRSA` 签名）→ `AUTH_RSAPUBLICKEY` 兜底 → `OPEN shell:<cmd>`，支持 TCP 与 USB Bulk 两种传输。
本机目标自动走本地 Root 通道（规避个别 ROM 对回环连接的 adbd 认证限制），其他设备走完整 ADB 协议。

### 安装
1. 下载 `WiFiADB-1.0.0-release.apk`
2. 安装后打开，首次进入授予通知权限
3. 点绿色圆环 → 状态变为「关闭 / 运行中」
4. PC 端执行 `adb connect <手机IP>:5555`

### 兼容性
Android 6.0+（minSdk 23），targetSdk 35。开启本机无线调试需要 Root 或 Shizuku；未授权时仍可作为 ADB 客户端连接其他设备。

### 已验证
Xiaomi MI 8（Android 10，Magisk Root）：PC `adb devices` 出现 `192.168.0.104:5555 device`；App 内连接 → 命令行 → `getprop ro.product.model` 返回 `MI 8`。

### 说明
发布签名密钥随仓库提供（`keystore/wifiadb.jks`），仅用于本项目，请勿用于其他用途。

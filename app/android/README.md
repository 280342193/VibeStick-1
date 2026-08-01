# VibeStick Android

VibeStick Android 是现有 VibeStick Windows Bridge 的局域网手机端。它复用
StickS3 的 UDP 发现和 HTTP 协议，不需要公网服务器，也不会把 Bridge Token 或
应用数据备份到云端。

工作链路如下：

```text
Android 麦克风 -> 局域网 PCM -> Windows Bridge -> 电脑端 ASR -> 电脑当前输入框
Android 手动文字 -> 局域网 -> Windows Bridge -> 电脑当前输入框 + Enter
```

Android 应用本身只连接所选电脑。默认 SiliconFlow 语音识别仍由 Windows Bridge
调用，API key 只保存在电脑上；如果电脑使用 SiliconFlow，音频会由电脑发送给
SiliconFlow。安卓端不保存 ASR key，也不直接访问 ASR 服务。

## 使用前准备

- Windows 上已经安装并运行 VibeStick Bridge。
- 手机和电脑连接到同一个可信局域网，且设备之间允许互相访问。
- Windows 网络类型建议设为“专用网络”。
- Bridge 默认使用 TCP `8765`，自动发现使用 UDP `8766`。

Windows 安装版的配置文件位于 `%APPDATA%\VibeStick\.env`。使用默认
SiliconFlow 时，电脑端至少需要：

```dotenv
VIBE_STICK_ASR_API_KEY=你的SiliconFlow_API_key
VIBE_STICK_AUTO_ENTER=off
```

`VIBE_STICK_AUTO_ENTER` 必须保持为 `off`（或不设置）。这样手机语音识别完成后
只粘贴到电脑当前聚焦输入框，不会自动提交。手机底部输入框的绿色发送按钮会在
粘贴手动文字后单独发送一次 Enter。

如果电脑配置了共享 Token：

```dotenv
VIBE_STICK_BRIDGE_TOKEN=你的共享Token
```

在应用右上角的连接设置中填写同一个 Token。不要把 Token 或 ASR key 提交到
Git。

如果 Windows 防火墙没有自动放行，可在管理员 PowerShell 中添加专用网络入站
规则：

```powershell
New-NetFirewallRule -DisplayName "VibeStick TCP 8765" -Direction Inbound -Profile Private -Protocol TCP -LocalPort 8765 -Action Allow
New-NetFirewallRule -DisplayName "VibeStick UDP 8766" -Direction Inbound -Profile Private -Protocol UDP -LocalPort 8766 -Action Allow
```

## 手机端使用

1. 打开应用并允许通知权限，应用会自动发现同一局域网中的电脑。
2. 如果发现多台电脑，打开右上角连接设置选择目标；需要时填写 Bridge Token。
3. 长按蓝色圆圈开始录音。圆圈会随麦克风音量轻微缩放和晃动；松手后，音频上传
   到电脑，由电脑识别并粘贴到当前聚焦输入框，不发送 Enter。首次使用会先申请
   麦克风权限，允许后请再次长按开始录音。
4. 在底部输入框手动输入文字，点击右侧绿色发送按钮。文字会粘贴到电脑当前聚焦
   输入框，然后发送 Enter。
5. Codex 任务完成、失败或等待确认时，手机会按新的事件 ID 弹出一次通知。

录音最长 60 秒。录音过短、没有声音、电脑离线、Token 错误、识别失败、粘贴
失败或 Enter 失败都会保留为可重试的错误状态。

如果手动文字已经粘贴、但 Enter 发送失败，应用会保留待发送状态。再次发送相同
文字时只重试 Enter，不会重复粘贴；该保护在重连、Token 更新和应用进程重建后
仍然有效，手机本地只保存文字的 SHA-256 指纹，不保存正文。

## 构建和安装

环境要求：

- JDK 17
- Android SDK 35
- Android Build Tools 35.0.0
- 一台 Android 8.0（API 26）或更高版本设备

构建 APK：

```powershell
.\gradlew.bat :app:assembleDebug
```

安装到已连接并开启 USB 调试的手机：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

APK 输出位置为 `app/build/outputs/apk/debug/app-debug.apk`。如果 Windows 上的
Gradle/JUnit 因项目路径包含非 ASCII 字符而无法加载测试类，请从纯英文路径构建。

## 验证

运行单元测试、API 35 模拟器测试、Lint 和 APK 构建：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug
```

模拟器可以验证界面、权限、后台服务和通过宿主机地址访问 Bridge。UDP 广播自动
发现及电脑输入注入必须使用同一 Wi-Fi 下的实体手机验证，因为 Android 模拟器的
NAT 不转发局域网广播。

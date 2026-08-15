<img width="256" height="256" alt="icon_128x128@2x" src="https://github.com/user-attachments/assets/90133f83-b4f6-41c6-aab9-25d0859d2a47" />

## bitchat for Android

一款去中心化的点对点通讯应用，采用双传输架构：本地蓝牙 mesh 网络用于离线通讯，基于互联网的 Nostr 协议用于全球互联。无账户、无手机号、无中心服务器。

这是 bitchat 的 Android 实现，与 [iOS 版](https://github.com/permissionlesstech/bitchat) 完全协议兼容，可实现跨平台 mesh 通讯。

[bitchat.free](http://bitchat.free)

[GitHub Releases](https://github.com/permissionlesstech/bitchat-android/releases)

[<img alt="Get it on Google Play" height="60" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png"/>](https://play.google.com/store/apps/details?id=com.bitchat.droid)

## 实际效果

<table>
  <tr>
    <th>离线 mesh 会话</th>
    <th>Geohash 地球仪选择器</th>
  </tr>
  <tr>
    <td><img src="docs/screenshots/readme-mesh-chat.png" alt="四个 peer 的 Bitchat mesh 会话，包含图片、语音消息和文本消息" width="360"/></td>
    <td><img src="docs/screenshots/readme-geohash-globe.png" alt="Bitchat geohash 位置选择器，显示整个地球和 geohash 网格" width="360"/></td>
  </tr>
</table>

## 许可证

本项目以公有领域（public domain）发布。详见 [LICENSE](LICENSE.md) 文件。

## 功能特性

- **双传输架构**：蓝牙 LE mesh 用于离线消息，Nostr 中继用于联网消息
- **基于位置的频道**：通过 Nostr 中继，使用 geohash 坐标建立地理聊天室
- **智能消息路由**：自动选择最佳传输方式，对不可达的 peer 进行排队和重试
- **端到端加密**：[Noise 协议](https://noiseprotocol.org)（XX 模式，X25519 + ChaCha20-Poly1305）用于 mesh 私密消息
- **去中心化 mesh 网络**：通过蓝牙 LE 自动发现 peer 并多跳中继（最多 7 跳）
- **Wi-Fi Aware 传输**：在受支持的设备上提供更高带宽的本地 mesh
- **频道聊天**：基于主题的群组消息，可选密码保护（Argon2id + AES-256-GCM）
- **IRC 风格命令**：熟悉的 `/join`、`/msg`、`/who` 风格界面
- **Tor 支持**：内置 Tor（Arti）用于私密互联网连接
- **紧急擦除**：三连击立即清除所有数据
- **跨平台**：与 iOS 和 macOS 版 bitchat 二进制协议兼容

## 技术架构

### 蓝牙 Mesh 网络（离线）

- 蓝牙范围内的直接点对点通讯，通过附近设备多跳中继
- Noise 协议会话，具备前向保密；peer 身份由静态密钥派生
- 紧凑的二进制数据包格式，支持分片、TTL 路由和去重
- 自适应占空比和连接数限制，兼顾电池效率
- 前台服务保证 mesh 在 Android 后台执行限制下保持存活

### Nostr 协议（互联网）

- 通过公共中继实现全球互联，基于 geohash 的位置频道
- mesh 不可用时，私密消息降级为通过 Nostr 发送给互相收藏的 peer
- 每个 geohash 区域使用临时密钥

### Android 技术栈

- Kotlin、Jetpack Compose（Material 3）、MVVM
- 所有网络与状态均使用协程和 Flow
- 核心组件：`MeshForegroundService`（持久连接）、`BluetoothMeshService` / `WifiAwareMeshService`（传输层）、`UnifiedMeshService`（传输选择）、`NoiseSessionManager`（加密会话）、`MessageRouter`（mesh/Nostr 路由，带发件箱重试）

## 构建

需要 Android Studio 和 Android SDK（API 26+）。

```bash
git clone https://github.com/permissionlesstech/bitchat-android.git
cd bitchat-android
./gradlew assembleDebug
```

安装到已连接的设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

应用会在运行时请求蓝牙、定位（BLE 扫描必需）和通知权限。

发布版 APK 和 Android App Bundle 可在固定的 Linux 容器中逐字节重建。维护者应遵循 [Android 发布指南](docs/maintainer-release-guide.md)。构建信任模型以及公开的 GitHub/Google Play 验证流程，请参阅 [可复现构建](docs/reproducible-builds.md)。

## 测试

```bash
# 单元测试
./gradlew test

# Lint
./gradlew lint

# 仪器测试（需要设备或模拟器）
./gradlew connectedAndroidTest
```

请注意，BLE mesh 行为难以模拟；协议和会话逻辑由单元测试覆盖，而射频层行为需要真实设备。

<img width="256" height="256" alt="icon_128x128@2x" src="https://github.com/user-attachments/assets/90133f83-b4f6-41c6-aab9-25d0859d2a47" />

# bitChat Rooms

> 基于 [bitchat](https://github.com/permissionlesstech/bitchat-android) 的**聊天室增强分支**：自托管中继服务器 + 客户端信任算法（防机器人 / 虚拟机）+ 更贴近日常的聊天室体验。
> 保留原项目的去中心化通讯能力，同时把开发重心转向聊天室。
>人话：可以实现随时随地的通过蓝牙mesh与身边的人组成聊天室聊天；可以自部署低成本服务器来实现私密频道聊天室（需要联网）；增加了一些更人性化的功能；可以识别机器人与虚拟机客户端，避免被垃圾信息覆盖

**本自述文件由AI辅助编写**~~我不想写~~

[原项目 bitchat](https://github.com/permissionlesstech/bitchat-android) · [iOS 版](https://github.com/permissionlesstech/bitchat) · [bitchat.free](http://bitchat.free)

## 与原项目的关系

本仓库 fork 自 [permissionlesstech/bitchat-android](https://github.com/permissionlesstech/bitchat-android)，在保留原有 mesh + Nostr 双传输能力的基础上做了以下方向性增强：

| 维度 | 原项目 permissionlesstech/bitchat-android | 本项目 bitChat Rooms |
| --- | --- | --- |
| 定位 | 通用 P2P 通讯（mesh + Nostr + 私聊） | **聊天室优先**（频道 + 地理频道） |
| 服务端 | 无（依赖公共 Nostr 中继） | **新增自托管 Go 中继服务器**（`server/`） |
| 反滥用 | 无 | **新增客户端信任算法**（识别机器人 / 虚拟机） |
| 聊天室 | 基础频道 + 密码保护 | **密码真实校验** + **引用 / 回复** + **@提及提醒** |
| 私聊 | 支持 `bitchat://dm` P2P 深链 | 移除 `bitchat://dm`，仅保留 `bitchat://verify` |
| 低带宽 | 通用 | 中继针对 1–1.5 Mbps 链路专门优化 |
| 可靠性 | 若干已知问题 | 修复断连重连、后台保活、DM 时间窗、订阅串扰等 |

## 新增功能（相对原项目）

1. **自托管中继服务器**（`server/`，Go 1.22+）
   - 完整 Nostr 中继：NIP-01 / NIP-11 / NIP-20 / NIP-28（kind 42）/ 地理频道（kind 20000/20001）/ 私聊（kind 14/1059）透明转发
   - 1–1.5 Mbps 低带宽优化：WebSocket 压缩、在线状态节流、慢消费者丢弃、紧凑 JSON、有界重放、临时事件过期
2. **客户端信任算法（防机器人 / 虚拟机）**
   - 客户端采集构建指纹、硬件、模拟器特征、root / debuggable 等系统数据
   - 服务器独立重推导并评分（0–100），分类 `trusted` / `suspicious` / `bot_or_emulator`
   - `-trust-mode`：`off` / `log` / `reject`；详见 [`docs/client-trust-algorithm.md`](docs/client-trust-algorithm.md)
3. **聊天室体验增强**
   - **引用 / 回复**：长按「回复」→ 输入框预览条 → 消息内引用块，跨 mesh / Nostr / geohash 三链路传播
   - **@提及提醒**：mesh 公共消息、频道消息、geohash 消息均支持 @提及通知
   - **频道密码真实校验**：移除 always-true 桩，改用 PBKDF2 密钥承诺校验
4. **可靠性修复**
   - Nostr 中继断连指数退避重连；前台服务无蓝牙权限仍保活；私聊时间窗修正；订阅表按连接隔离

## 功能特性

- **双传输架构**：BLE mesh（离线）+ Nostr 中继（联网）
- **地理频道**：基于 geohash 坐标的地理聊天室
- **命名频道**：主题频道，可选密码保护
- **端到端加密**：Noise Protocol（XX 模式，X25519 + ChaCha20-Poly1305）
- **去中心化 mesh**：BLE 自动发现 + 多跳中继（最多 7 跳）
- **Wi-Fi Aware**：受支持设备上的高带宽本地 mesh
- **IRC 风格命令**：`/join`、`/msg`、`/who` 等
- **Tor 支持**：内置 Tor（Arti）
- **紧急擦除**：三连击清除所有数据
- **跨平台**：与 iOS / macOS 版 bitchat 二进制协议兼容

### 客户端（Android）

- Kotlin + Jetpack Compose（Material 3）+ MVVM；协程与 Flow
- 核心组件：`MeshForegroundService`、`BluetoothMeshService` / `WifiAwareMeshService`、`UnifiedMeshService`、`NoiseSessionManager`、`MessageRouter`

### 中继服务器（`server/`，Go）

- WebSocket（permessage-deflate）+ NIP-01 / 11 / 20 / 28 / 42
- 地理频道 / 命名频道 / 私聊透明转发；客户端信任评分；低带宽优化
- 详见 [`server/README.md`](../server/README.md)

## 构建

### Android 客户端

需要 Android Studio 与 Android SDK（API 26+）。

```bash
git clone <本仓库地址>
cd bitchat-android-main
./gradlew assembleDebug
```

安装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 中继服务器

需要 Go 1.22+。

```bash
cd server
go mod tidy
go build -o relay .
./relay -listen :8080
```

## 测试

```bash
# 单元测试
./gradlew test

# Lint
./gradlew lint

# 仪器测试（需设备或模拟器）
./gradlew connectedAndroidTest

# 服务器测试
cd server && go test ./...
```

## 致谢

本项目 fork 自 [permissionlesstech/bitchat-android](https://github.com/permissionlesstech/bitchat-android)，原项目作者为 **[permissionlesstech](https://github.com/permissionlesstech)**。

感谢原项目作者开源如此出色的去中心化通讯方案，以及配套的 [iOS 版](https://github.com/permissionlesstech/bitchat)。本项目的中继服务器、客户端信任算法与聊天室增强均构建于原项目的坚实基础之上。

## 开源协议

本项目以 **[GNU GPL v3](LICENSE.md)** 发布，完整条款见 [LICENSE.md](LICENSE.md)。

> 说明：原项目 [permissionlesstech/bitchat-android](https://github.com/permissionlesstech/bitchat-android) 的 README 曾声明"公有领域（public domain）"，但仓库内随附的 `LICENSE.md` 实际为 GPL v3 全文。二者冲突时以 `LICENSE.md` 为准，故本项目（含中继服务器、信任算法、聊天室增强以及对原代码的修改）整体按 GPL v3 授权。

## 结尾
放个大佛镇一下~~（因为不想修bug）~~

```
                   _ooOoo_
                  o8888888o
                  88" . "88
                  (| -_- |)
                  O\  =  /O
               ____/`---'\____
             .'  \\|     |//  `.
            /  \\|||  :  |||//  \
           /  _||||| -:- |||||-  \
           |   | \\\  -  /// |   |
           | \_|  ''\---/''  |   |
           \  .-\__  `-`  ___/-. /
         ___`. .'  /--.--\  `. . __
      ."" '<  `.___\_<|>_/___.'  >'"".
     | | :  `- \`.;`\ _ /`;.`/ - ` : | |
     \  \ `-.   \_ __\ /__ _/   .-` /  /
======`-.____`-.___\_____/___.-`____.-'======
                   `=---='
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
            佛祖保佑       永无BUG
```
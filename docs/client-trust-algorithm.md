# 客户端信任算法（防机器人 / 虚拟机）

## 目标

自托管中继服务器需要判断「正在连接的客户端」是否是真实设备，还是机器人脚本或运行在模拟器/虚拟机中的自动化客户端。本算法在客户端连接建立后采集多项系统数据，由服务器打分并分类，可选地拒绝不可信客户端。

## 数据流

1. 客户端（Android）在每条 WebSocket 连接 `onOpen` 后、恢复订阅/发送事件**之前**，发送一条自定义消息：

   ```json
   ["TRUST", { "version": 1, "fingerprint": "...", "isEmulator": false, ... }]
   ```

2. 服务器 `relay.handleTrust` 解析该消息，调用 `trust.evaluateTrust` 打分并分类。
3. 依据 `-trust-mode` 决定策略：`off` / `log`（默认）/ `reject`。

## 采集的系统数据（客户端）

`app/.../util/ClientTrustSignals.kt`：

- 原始构建字段：`Build.FINGERPRINT / HARDWARE / MODEL / MANUFACTURER / BRAND / DEVICE / PRODUCT / BOARD / BOOTLOADER / TAGS`
- `isEmulator`：命中任一模拟器特征即置位
- `isRooted`：`Build.TAGS` 含 `test-keys`，或常见 `su` 二进制/Superuser APK 存在
- `isDebuggable`：`ApplicationInfo.FLAG_DEBUGGABLE`
- `emulatorHints`：命中的特征列表（如 `emulator hardware: ranchu`、`ro.kernel.qemu=1`）

模拟器特征（客户端与服务器各独立实现一套，互相印证）：

- `goldfish` / `ranchu` / `vbox86` / `qemu` / `ttvm` 硬件
- `generic` / `unknown` / `emulator` / `vbox` / `android_x86` / `google_sdk` 指纹
- 模型/厂商/产品含 `emulator` / `android sdk` / `google_sdk` / `droid4x` / `bluestacks` / `nox` / `genymotion` 等
- `ro.kernel.qemu=1` 系统属性
- `Build.TAGS` 含 `test-keys`

## 服务器评分（`server/trust.go`）

风险分 0-100：

| 信号 | 风险分 |
| --- | --- |
| 原始构建字段命中模拟器特征（服务器重新推导） | 每个 +20 |
| 客户端自报 `isEmulator` | +100 |
| `isRooted` | +25 |
| `isDebuggable` | +15 |
| 客户端附加上下文提示 | 每个 +10 |

分类边界（`-trust-threshold` 默认 80）：

- `score >= threshold` → `bot_or_emulator`
- `score >= threshold/2` → `suspicious`
- 否则 → `trusted`

## 拒绝策略（`-trust-mode=reject`）

- 判定为 `bot_or_emulator` → 下发 NOTICE 并断开连接（50ms 延迟以冲刷 NOTICE）。
- EVENT 写入前调用 `client.isUntrusted()`：未上报信任数据或已判为不可信 → 拒绝写入。
- REQ（订阅/读取）不受影响。

## 局限

- 启发式判定可被伪造（修改构建字段、隐藏 root）。`reject` 模式是**补充**手段，不应作为唯一防线。
- `reject` 模式下，不发送 `TRUST` 的第三方 Nostr 客户端无法发布事件（读取仍可用）。
- 评分阈值可按部署环境调优，但建议先用 `log` 模式观察真实设备的分数分布再开启 `reject`。

## 涉及文件

- 客户端：`app/src/main/java/com/bitchat/android/util/ClientTrustSignals.kt`、`app/src/main/java/com/bitchat/android/nostr/NostrRelayManager.kt`
- 服务器：`server/trust.go`、`server/trust_test.go`、`server/relay.go`、`server/config.go`

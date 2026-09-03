# BOLON 无屏眼镜会话、电源与首次配网 MVP 规格

## 1. 文档信息

- 编写日期：2026-09-01
- 适用设备：BOLON AI Glasses / Rokid RV201，Android 12，API 32
- 适用手机：HONOR REP-AN00，Android 15
- 手机项目：`D:\all_projects\AndroidStudioProjects\glasses`
- 眼镜项目：`D:\all_projects\AndroidStudioProjects\connected-wifi-reboot\webrtc-build-glasses-wifi-probe`
- 手机基线提交：`724b909 feat: integrate glasses streaming and provisioning`
- 眼镜基线提交：`820ad4d fix: close signaling socket before WebRTC teardown`
- 文档性质：后续实现与真机验收依据

本文档只规划手机与无屏眼镜之间的启动、权限、蓝牙、Wi-Fi、WebRTC 和折叠恢复链路。
手机端已有的深度估计、地面过滤、障碍网格和 HRTF 音频算法不在本轮重新设计。

## 2. 背景与当前问题

当前已经验证：

```text
眼镜 CameraX
-> 本地热点 Wi-Fi
-> TCP 8888 信令
-> WebRTC 视频轨道
-> 手机深度推理与音频输出
```

真机已经达到：

- WebRTC ICE `CONNECTED/COMPLETED`；
- DataChannel `OPEN`；
- 手机收到 `640x480`、rotation 270 的首帧；
- 手机接收约 10 FPS；
- 手机前后台重连后没有再次出现 `CLOSE-WAIT` 死锁。

但当前生命周期实现存在以下缺口：

1. 镜腿折叠后，系统蓝牙断开，但 Wi-Fi 仍然保持连接，导致待机发热。
2. 镜腿重新打开后，即使 Wi-Fi 已恢复，眼镜也不一定重新创建 WebRTC 会话。
3. `ConnectionRecoveryService` 已经编写，但当前没有稳定启动入口。
4. `MainActivity` 和 `StreamingForegroundService` 都可能拥有 CameraX/WebRTC，职责重复。
5. 手机端 BLE 写入成功只代表参数写入 GATT，不代表 Wi-Fi 或视频已经成功。
6. 眼镜运行时权限目前依赖开发阶段 ADB 显式授权，普通 APK 安装不会自动完成。
7. 全新出厂设备从关机到视频传输的无 ADB 闭环尚未验证。

## 3. MVP 目标

### 3.1 必须实现

1. 镜腿折叠后 5 秒内停止 CameraX、WebRTC、自定义 BLE 广播并关闭 Wi-Fi。
2. 镜腿打开且确认佩戴后，启动轻量 BLE 会话入口，但不立即打开 Wi-Fi。
3. 手机点击“开始辅助”后，先启动 TCP 8888，再通过 BLE 发送启动会话指令。
4. 眼镜收到启动指令后：
   - 已保存热点：开启 Wi-Fi 并自动重连；
   - 未保存热点：保存手机下发的热点配置并首次连接。
5. BLE 向手机持续返回从参数接收到视频启动的状态，而不是只返回 GATT 写入成功。
6. Wi-Fi 获得 IPv4 且手机 8888 可达后，由唯一后台组件启动 CameraX/WebRTC。
7. 镜腿折叠再打开后，用户不需要 ADB，也不需要重启应用即可恢复视频。
8. 眼镜完全关机再开机后，在已完成一次部署和权限初始化的条件下，可以无 ADB 启动。
9. 提供开发人员一次性部署脚本或文档，完成安装、权限、默认 HOME 和环境校验。
10. 完成全新热点、折叠恢复、冷启动和异常场景真机测试。

### 3.2 本轮不实现

- 破解或复刻 Rokid 官方 CXR 协议；
- 依赖官方 App 的业务账号或云服务；
- Wi-Fi Direct；
- 公网 STUN/TURN；
- 普通 App 自动开启手机热点；
- 修改眼镜系统镜像；
- Root；
- 在无系统权限条件下自动授予危险权限；
- 保证所有品牌 Android 眼镜都能使用旧版 `WifiConfiguration`；
- 手机 App 在系统强制杀死后仍无限期维持后台视频接收。

## 4. 产品边界

### 4.1 两类用户流程必须区分

#### 开发部署流程

只执行一次，可使用 USB 和 ADB：

```text
安装眼镜 APK
-> 授予危险权限
-> 设置自有 App 为默认 HOME
-> 安装手机 APK
-> 授予手机蓝牙权限
-> 验证 BLE/Wi-Fi/WebRTC
```

#### 日常使用流程

部署完成后，不再依赖 ADB：

```text
手机开启热点
-> 打开手机 App
-> 眼镜开机、打开镜腿并佩戴
-> 点击“开始辅助”
-> BLE 控制眼镜开启 Wi-Fi
-> 眼镜连接热点
-> WebRTC 视频开始
```

### 4.2 MVP 不宣称的能力

在完成 Device Owner、系统预装或厂商安装支持前，不宣称：

```text
全新出厂眼镜
-> 用户只下载一个 APK
-> 所有权限自动授予
-> 完全不经过任何部署工具
```

普通 APK 安装不会自动授予 Camera、定位和 Android 12 蓝牙危险权限。

## 5. 推荐总体架构

### 5.1 眼镜端

新增唯一的会话编排服务：

```text
GlassesSessionService
    |
    +-- 动态监听镜腿/佩戴广播
    +-- 监听 BluetoothAdapter 状态
    +-- 管理自定义 BLE GATT Server
    +-- 管理 Wi-Fi 开关和配网
    +-- 监听 ConnectivityManager
    +-- 探测手机 TCP 8888
    +-- 启停 StreamingForegroundService
    +-- 发布 BLE 状态通知
```

`StreamingForegroundService` 成为 CameraX 和 WebRTC 的唯一所有者。

`MainActivity` 只负责：

- 开发诊断 UI；
- 权限状态显示；
- 将手工操作转发给 `GlassesSessionService`；
- 不直接创建 `WebRtcSignalingClient`；
- 不直接绑定 CameraX 视频流。

### 5.2 手机端

新增手机会话控制器：

```text
PhoneAssistanceSession
    |
    +-- 启动 LocalSignalingServer:8888
    +-- 扫描眼镜自定义 BLE 服务
    +-- 写入 PROVISION_AND_START / START_SESSION
    +-- 订阅眼镜状态通知
    +-- 接收 WebRTC 首帧
    +-- 停止时发送 STOP_SESSION
```

手机界面显示真实阶段：

```text
准备中
正在搜索眼镜
蓝牙已连接
正在发送热点参数
眼镜正在连接 Wi-Fi
眼镜已连接热点
正在建立视频
视频运行中
```

## 6. 会话状态机

### 6.1 眼镜状态

```text
BOOTING
FOLDED
OPEN_NOT_WORN
BLE_READY
SESSION_REQUESTED
WIFI_ENABLING
WIFI_CONNECTING
WIFI_READY
PHONE_WAITING
STREAM_STARTING
STREAMING
STOPPING
ERROR
```

### 6.2 关键转换

```text
BOOTING
-> 读取最近镜腿/佩戴状态
-> 若未知，保持低功耗等待广播

任意状态 + LEG_FOLDED
-> STOPPING
-> FOLDED

FOLDED + LEG_OPEN
-> OPEN_NOT_WORN

OPEN_NOT_WORN + WORN
-> BLE_READY

BLE_READY + START_SESSION
-> SESSION_REQUESTED
-> WIFI_ENABLING
-> WIFI_CONNECTING

WIFI_CONNECTING + IPv4/gateway ready
-> WIFI_READY
-> PHONE_WAITING

PHONE_WAITING + TCP 8888 reachable
-> STREAM_STARTING
-> STREAMING

STREAMING + STOP_SESSION
-> STOPPING
-> BLE_READY
```

### 6.3 折叠优先级

`LEG_FOLDED` 是最高优先级事件。收到后无论当前处于连接、配网还是视频状态，都必须：

1. 标记 `desiredSession=false`；
2. 取消 Wi-Fi 和端口探测任务；
3. 停止 `StreamingForegroundService`；
4. 关闭 WebRTC、CameraX 和 DataChannel；
5. 停止自定义 BLE 广播并关闭 GATT Server；
6. `WifiManager.disconnect()`；
7. `WifiManager.setWifiEnabled(false)`；
8. 释放所有 WakeLock；
9. 进入 `FOLDED`。

不能依赖手机发送关闭命令，因为折叠时系统蓝牙可能先断开。

## 7. 事件来源

### 7.1 厂商广播

需要由长期运行的服务动态注册：

```text
com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED
extra: glasses_take_state

com.rokid.sprite.ACTION_LEG_STATUS_CHANGED
extra: glasses_leg_state
```

2026-09-01 真机采集已确认以下映射，extra 类型均为 `String`：

```text
glasses_take_state="0" -> 已摘下
glasses_take_state="1" -> 已佩戴
glasses_leg_state="0"  -> 镜腿折叠
glasses_leg_state="1"  -> 镜腿展开
```

佩戴、摘下、折叠和展开分别完成至少 10 次受控采样，未发现值漂移。

同时确认：

- 服务进程重启后静置 5 秒，系统不会补发当前镜腿或佩戴状态；
- 开机完成后再注册监听，系统也不会延迟补发当前状态；
- 该固件会以 `Background execution Third-Party APP not allowed` 拦截普通侧载应用的
  `LOCKED_BOOT_COMPLETED`，不能把它作为唯一启动入口；
- 当前主应用仍能收到普通 `BOOT_COMPLETED` 并启动 Activity，但该能力需要在后续
  会话服务改造后重新验证。

因此服务启动时必须把两个维度初始化为独立的未知状态：

```text
wearState = UNKNOWN
legState = UNKNOWN
```

只有收到上述已验证值后才更新状态。未知、缺失或未来新增值必须记录并忽略，不能猜测。

原始采集还确认了：

- 镜腿打开值；
- 镜腿折叠值；
- 已佩戴值；
- 已摘下值；
- 开机不发送可供普通动态接收器使用的初始状态；
- 服务进程重启后不会补发状态。

### 7.2 系统广播与回调

仅作为补充信号：

- `ACTION_SCREEN_ON`；
- `ACTION_SCREEN_OFF`；
- `ACTION_USER_PRESENT`；
- `BluetoothAdapter.ACTION_STATE_CHANGED`；
- `ConnectivityManager.NetworkCallback`。

不能把 `PowerManager.isInteractive` 直接等同于“镜腿打开且已佩戴”。

## 8. 权限与部署规格

### 8.1 眼镜运行时权限

最低需要验证：

```text
android.permission.CAMERA
android.permission.RECORD_AUDIO
android.permission.ACCESS_FINE_LOCATION
android.permission.BLUETOOTH_SCAN
android.permission.BLUETOOTH_CONNECT
android.permission.BLUETOOTH_ADVERTISE
```

`NEARBY_WIFI_DEVICES` 在眼镜 Android 12/API 32 上不是主要授权路径，但 Manifest 可保留以兼容后续系统。

### 8.2 安装方式

开发 MVP 推荐：

```powershell
adb install -r -g app-debug.apk
```

然后逐项使用 `dumpsys package` 验证权限。若 `-g` 未全部生效，使用 `pm grant` 补齐。

### 8.3 部署工具验收

新增一个部署检查脚本或明确的人工清单，输出以下结果但不输出热点密码：

- 两台设备序列号与在线状态；
- 安装版本；
- 权限 granted 状态；
- 默认 HOME；
- 眼镜 App 进程；
- 手机 App 进程；
- 眼镜 Wi-Fi 和蓝牙开关；
- 不打印完整蓝牙地址、热点密码和账号令牌。

### 8.4 产品化决策门

如果要让全新用户完全不接触 ADB，必须在 MVP 后选择至少一项：

1. Device Owner/DPC；
2. 厂商系统预装或特权签名；
3. 厂商提供的安装与默认权限白名单；
4. 出厂/售前一次性技术人员配置。

## 9. BLE 协议 v2

### 9.1 GATT 角色

- 眼镜：Peripheral / GATT Server；
- 手机：Central / GATT Client。

### 9.2 特征

```text
DEVICE_INFO
  属性：READ
  内容：协议版本、App 版本、设备能力

SESSION_COMMAND
  属性：WRITE
  内容：启动、停止、首次配网、心跳

SESSION_STATUS
  属性：READ + NOTIFY
  内容：当前阶段、requestId、错误码
```

现有 `CREDENTIALS_UUID` 可以兼容保留，但新手机端应优先使用 v2。

### 9.3 命令格式

首次配网并启动：

```json
{
  "version": 2,
  "requestId": "random-id",
  "command": "PROVISION_AND_START",
  "ssid": "user-entered-ssid",
  "password": "user-entered-password",
  "security": "WPA2"
}
```

已保存热点启动：

```json
{
  "version": 2,
  "requestId": "random-id",
  "command": "START_SESSION"
}
```

停止：

```json
{
  "version": 2,
  "requestId": "random-id",
  "command": "STOP_SESSION"
}
```

### 9.4 状态格式

```json
{
  "version": 2,
  "requestId": "random-id",
  "state": "WIFI_CONNECTING",
  "error": null
}
```

### 9.5 必须支持的状态

```text
COMMAND_RECEIVED
CREDENTIALS_ACCEPTED
WIFI_ENABLING
WIFI_CONNECTING
WIFI_READY
PHONE_WAITING
SIGNALING_CONNECTED
STREAMING
STOPPED
```

### 9.6 必须支持的错误

```text
INVALID_COMMAND
INVALID_CREDENTIALS
PERMISSION_MISSING
GLASSES_FOLDED
GLASSES_NOT_WORN
WIFI_ENABLE_REJECTED
WIFI_CONFIGURATION_REJECTED
WIFI_AUTH_FAILED
WIFI_TIMEOUT
NO_IPV4_GATEWAY
PHONE_SIGNALING_UNREACHABLE
CAMERA_START_FAILED
WEBRTC_FAILED
```

### 9.7 BLE 成功定义

手机端不能在 `onCharacteristicWrite(GATT_SUCCESS)` 时显示“配网成功”。

定义如下：

```text
GATT 写入成功 = 参数传输成功
WIFI_READY = 联网成功
STREAMING 或手机收到首帧 = 会话成功
```

### 9.8 凭据安全

当前眼镜端会把密码明文保存在普通 `SharedPreferences`。MVP 至少修改为：

- 手机端只保存 SSID，不保存密码；
- 眼镜端成功写入 Android 系统 Wi-Fi 配置后删除 App 内密码；
- 若确实需要重试凭据，使用 Android Keystore 加密；
- Logcat、UI、测试报告和文档禁止打印明文密码。

## 10. Wi-Fi 策略

### 10.1 已保存热点

```text
START_SESSION
-> setWifiEnabled(true)
-> reconnect()
-> 等待 TRANSPORT_WIFI
-> 等待 IPv4 默认网关
-> 探测 gateway:8888
```

### 10.2 全新热点

MVP 继续验证低 targetSdk 眼镜 App 的旧版 `WifiConfiguration`：

```text
addNetwork
-> enableNetwork
-> reconnect
-> ConnectivityManager callback
```

必须明确记录：

- `addNetwork` 返回值；
- `enableNetwork` 返回值；
- 连接是否出现系统 UI；
- DHCP/IPv4 时间；
- 默认网关；
- 失败原因。

### 10.3 失败降级

如果新 SSID 静默连接在受控测试中不能稳定通过，MVP 必须选择并记录产品边界：

1. 官方 App 完成首次热点保存；
2. ADB/部署工具预置热点；
3. 固定同名热点；
4. Device Owner；
5. 系统/厂商权限。

不使用 `WifiNetworkSpecifier` 作为默认方案，因为无屏设备无法可靠操作系统确认框。

## 11. WebRTC 与手机端生命周期

### 11.1 启动顺序

必须固定为：

```text
手机点击开始辅助
-> 初始化模型和音频
-> LocalSignalingServer 监听 8888
-> 确认端口已监听
-> BLE 发送 START_SESSION
-> 眼镜连接 Wi-Fi
-> 眼镜探测 8888
-> 眼镜启动 WebRTC
```

这样可以避免眼镜先连接后收到 `ECONNREFUSED`。

### 11.2 停止顺序

```text
手机点击停止辅助
-> BLE 发送 STOP_SESSION（若可达）
-> 手机关闭客户端 Socket
-> 手机关闭 PeerConnection/DataChannel
-> 手机关闭 ServerSocket
-> 眼镜停止 CameraX/WebRTC
-> 眼镜关闭 Wi-Fi
```

折叠时不等待手机命令，直接执行眼镜本地停止流程。

### 11.3 手机后台行为

MVP 保持当前边界：手机实时辅助页面必须处于前台或至少处于 STARTED 状态。

手机进入后台时：

- 明确显示会话将停止；
- 关闭 8888 和 WebRTC；
- 返回页面后重新启动 8888；
- 通过 BLE 重新发送 `START_SESSION`，或者允许眼镜重试端口。

后续如需锁屏后台运行，再单独将手机信令接收改成前台服务。

## 12. 开机与无 ADB 流程

### 12.1 BootReceiver 行为

当前 BootReceiver 直接启动 Activity。修改为：

```text
BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED
-> 启动 GlassesSessionService
-> 注册镜腿与佩戴广播
-> 不自动开启 Wi-Fi
-> 等待打开、佩戴和手机 START_SESSION
```

Activity 是否显示不再决定连接能力。

### 12.2 默认 HOME

MVP 期间继续把自有 App 设为默认 HOME，作为厂商 Launcher 覆盖和进程恢复的兜底。

2026-09-01 的 Task 2 真机验证确认：当厂商 Launcher 是默认 HOME、第三方 Activity
退到后台后，固件会撤销普通侧载 App 的前台服务身份，并在约 3 分钟后输出
`Stopping service due to app idle` 后停止服务。补齐 `connectedDevice` 前台服务类型
仍不能绕过该厂商策略。

因此开发 MVP 必须把自有 App 设为默认 HOME。Activity 只作为进程存活锚点，物理
状态、Wi-Fi 请求和视频启动的唯一所有权仍在 `GlassesSessionService`。产品化若要
彻底移除 HOME 依赖，必须取得厂商后台白名单、系统/特权安装、Device Owner 或
其他固件支持的后台执行豁免。

### 12.3 日常冷启动步骤

完成一次性部署后：

1. 手机开启 2.4GHz 热点。
2. 手机打开 App。
3. 眼镜关机时长按约 3 秒开机。
4. 打开镜腿并佩戴。
5. 手机点击“开始辅助”。
6. 首次热点时输入热点名称和密码；已保存热点无需重复输入。
7. 手机等待状态到 `STREAMING` 或收到首帧。

## 13. 代码改造范围

### 13.1 眼镜项目

建议新增：

```text
provisioning/GlassesSessionService.kt
provisioning/GlassesSessionState.kt
provisioning/WearFoldStateReceiver.kt
provisioning/BleSessionProtocol.kt
provisioning/BleSessionGattServer.kt
wifi/GlassesWifiController.kt
webrtc/StreamCoordinator.kt
```

建议修改：

```text
AndroidManifest.xml
BootReceiver.kt
MainActivity.kt
BleProvisioningService.kt
BleProvisioningContract.kt
ConnectionRecoveryService.kt
WifiProvisioningProbe.kt
StreamingForegroundService.kt
WebRtcSignalingClient.java
```

实施时可把现有 `BleProvisioningService` 和 `ConnectionRecoveryService` 合并进
`GlassesSessionService`，避免多个 START_STICKY 服务互相改变 Wi-Fi 状态。

### 13.2 手机项目

建议新增：

```text
session/PhoneAssistanceSession.kt
ble/BleSessionClient.kt
ble/BleSessionProtocol.kt
ui/AssistanceConnectionState.kt
```

建议修改：

```text
DepthCameraScreen.kt
DepthCameraViewModel.kt
BleProvisioningSettings.kt
LocalSignalingServer.java
GlassesApp.kt
SettingsScreen.kt
```

## 14. 分阶段执行规格

### 阶段 0：冻结基线与日志准备

任务：

1. 确认两个仓库工作区干净。
2. 记录当前提交哈希。
3. 确认现有 JVM 测试和 APK 构建通过。
4. 为新状态机定义统一日志 Tag。
5. 不修改 WebRTC 编解码参数。

完成标志：可以随时回退到当前已验证视频基线。

### 阶段 1：采集镜腿与佩戴广播真实值

状态：已完成（2026-09-01）。

任务：

1. 在长期服务中动态注册两个厂商广播。
2. 日志只记录 action、原始状态值和时间。
3. 执行：佩戴、摘下、打开、折叠各 10 次。
4. 测试开机时是否收到初始状态。
5. 测试进程重启后是否补发状态。

完成标志：形成确定的状态值映射表。

### 阶段 2：统一眼镜会话服务

状态：开发 MVP 已完成（2026-09-01），依赖默认 HOME 兜底。

任务：

1. 创建 `GlassesSessionService`。
2. BootReceiver 和 MainActivity 都只负责确保该服务运行。
3. 服务保存单一 `StateFlow<GlassesSessionState>`。
4. 删除或停用重复的恢复探测循环。
5. MainActivity 不再直接创建 WebRTC。

完成结果：统一服务和单一 StateFlow 已落地，旧恢复循环已禁用，Activity 不再拥有
Wi-Fi、CameraX 或 WebRTC。默认 HOME 条件下，服务连续运行超过 6 分钟，并在
Activity 不承担连接逻辑时收到 `NOT_WORN -> FOLDED -> OPEN` 广播，Wi-Fi 保持关闭。

偏差：普通侧载 App 在厂商 Launcher 前台时无法让服务独立长期存活，约 3 分钟后
会被固件停止。开发 MVP 使用默认 HOME；产品化豁免路径列入部署决策门。

### 阶段 3：折叠关流与关 Wi-Fi

状态：已完成（2026-09-01），BLE 运行态折叠复验并入阶段 4。

任务：

1. 折叠事件立即停止视频服务。
2. 关闭自定义 BLE 广播和 GATT。
3. 断开并关闭 Wi-Fi。
4. 取消所有重试任务。
5. 释放 WakeLock。
6. 验证服务不会因“无 Wi-Fi”逻辑再次开启 Wi-Fi。

完成标志：折叠后 5 秒内 Wi-Fi disabled，摄像头关闭，设备温升停止继续上升。

真机结果：在眼镜已连接手机热点、`StreamingForegroundService` 和 CameraX 均运行的
情况下，收到 `LEG_FOLDED` 后约 117 ms 完成会话清理请求，约 206 ms 内视频服务
销毁；Wi-Fi 断开并变为 disabled，相机活动客户端为空，视频 WakeLock 释放。手机
热点保持开启时，重新打开并佩戴也没有恢复旧 Wi-Fi、相机或视频会话。

实现同时清除了 Wi-Fi 延迟回调、BootRecovery 闹钟、BLE 广播/GATT 重试和 BLE
唤醒锁。由于阶段 3 正常流程尚不启动 BLE 服务，完整的 `BLE_READY -> FOLDED`
真机清理验证放入阶段 4。

### 阶段 4：打开/佩戴后 BLE_READY

状态：眼镜端生命周期已完成（2026-09-01），手机端独立扫描观察待补。

任务：

1. 镜腿打开但未佩戴时保持 Wi-Fi 关闭。
2. 打开且佩戴后启动 GATT Server 和低延迟广播。
3. 蓝牙适配器恢复后自动重建 GATT。
4. 广播 watchdog 只在 `BLE_READY` 状态运行。

完成标志：佩戴后 5 秒内手机能扫描到自定义服务。

真机结果：打开但未佩戴超过 6 秒时，BLE 服务、Wi-Fi、相机和应用 WakeLock 均
保持关闭。实测发现镜腿打开过程中佩戴传感器可能误报约 2.9 秒，因此增加 3 秒
稳定佩戴确认窗口。正式佩戴后，从 `WORN` 广播到 `BLE_READY` 共 3,090 ms，其中
GATT 和低延迟广播实际启动耗时约 73 ms，广播 UUID 为
`76b45a10-8e2f-4e8a-9b6a-5d53e76f0100`。

保持佩戴时关闭再开启蓝牙适配器，`STATE_ON` 后 37 ms 自动重建 GATT 并恢复
`BLE_READY`。从 `BLE_READY` 摘下后约 33 ms 销毁 BLE 服务，等待超过一个
watchdog 周期也没有广告、GATT 重试或 WakeLock 残留；随后折叠处理约 19 ms。

阶段 5 已补齐手机侧独立扫描、连接、服务发现、CCCD 订阅和初始状态读取，确认
手机能够发现该 UUID，阶段 4 的手机发现验收边界已关闭。

### 阶段 5：BLE v2 命令与状态通知

任务：

1. 增加 DEVICE_INFO、SESSION_COMMAND、SESSION_STATUS。
2. 实现 requestId 关联。
3. 实现 CCCD 和 Notify。
4. 手机订阅状态后再发送命令。
5. 保留 v1 凭据特征兼容测试。
6. 手机不再把 GATT 写成功显示为配网成功。

完成标志：手机可以完整看到眼镜状态变化和明确错误码。

真机结果：阶段 5 已通过。眼镜版本为 `2.10-ble-v2-task5 (22)`，手机版本为
`1.2-ble-v2-task5 (3)`。手机按“扫描 -> 连接 -> 服务发现 -> MTU -> DEVICE_INFO
-> CCCD -> SESSION_STATUS”的固定顺序完成握手，未订阅通知前不能发送 v2 命令。
`STOP_SESSION` 的 `COMMAND_RECEIVED -> STOPPED` 使用同一 requestId；
`PROVISION_AND_START` 在本阶段只返回 `COMMAND_RECEIVED -> CREDENTIALS_ACCEPTED`，
不启动 Wi-Fi、相机或视频，也不把 GATT 写成功显示为配网成功。v1 凭据 payload
兼容测试保留并通过。

连接状态下折叠时，眼镜先通知 `STOPPED + GLASSES_FOLDED/GLASSES_NOT_WORN`，
约 250 ms 后销毁 BLE；手机收到终止通知后立即清除已连接 UI，并约 300 ms 后
主动关闭当前 GATT，解决系统未及时回调断连导致的旧状态残留。折叠后 Wi-Fi
关闭、相机客户端为空、BLE/视频服务及其应用 WakeLock 均不存在，恢复重试已取消。
详细证据见眼镜工程 `TASK_05_BLE_V2_PROTOCOL_VERIFICATION.md`。

部署边界：本次覆盖安装后，固件没有自动首次启动新包，需要启动一次 Activity；
服务首次启动时镜腿与佩戴状态保持独立 `UNKNOWN`，需要后续真实广播更新。该问题
属于阶段 9 的首次激活和无 ADB 冷启动范围，不影响阶段 5 协议验收。

### 阶段 6：已保存热点启动

任务：

1. 手机先监听 8888。
2. BLE 发送 `START_SESSION`。
3. 眼镜开启 Wi-Fi 并重连已保存网络。
4. 获取 IPv4 默认网关。
5. 探测手机 8888。
6. 启动 `StreamingForegroundService`。

完成标志：连续 10 次打开/佩戴都能恢复首帧。

### 阶段 7：全新热点首次配网

状态：实现完成，受控真机验收完成 `10/20`（2026-09-02）；剩余 10 次按用户决定暂缓，
因此尚未满足原定发布门槛。

任务：

1. 使用眼镜从未保存过的新 SSID。
2. 手机发送 `PROVISION_AND_START`。
3. 眼镜验证参数并调用旧版 Wi-Fi API。
4. 持续 Notify 联网状态。
5. 成功后删除 App 普通存储中的明文密码。
6. 失败时保留 BLE 连接，允许用户改密码重试。

完成标志：至少 20 次受控新 SSID 测试全部获得 IPv4，且不出现系统 UI。

如果达不到此标准，立即进入 Wi-Fi 权限路径决策，不把不稳定能力发布给用户。

当前结果：5 个眼镜从未保存过的 SSID 均通过旧版 `WifiConfiguration` 静默写入，
获得 IPv4 默认网关且没有系统确认 UI。错误密码能够返回明确失败，修正后通过
`updateNetwork()` 成功联网；成功后手机仅保留 SSID，眼镜 App 私有存储不保留密码键。

验收期间修复了三项跨阶段缺陷：手机 GATT 中断后按原 requestId 恢复状态订阅且不
重发凭据；眼镜从折叠恢复 BLE 时清除旧物理关闭错误；眼镜 WebRTC 禁用会在该固件
上触发 `SIGBUS` 的 Java NetworkMonitor，改用原生网卡枚举。最终在同一眼镜进程中
连续两代 WebRTC 会话均成功，手机接收约 10-16 FPS。详细证据见眼镜工程
`TASK_07_FIRST_TIME_PROVISIONING_VERIFICATION.md`。

后续迁移设备又完成 `BOLON-WXC-T7-08`、`BOLON-WXC-T7-09`、
`BOLON-WXC-T7-10` 三轮新 SSID 端到端验证，均完成静默 IPv4、
ICE/DataChannel/视频和停止资源释放，未发生 crash 或 ANR。

边界：当前结果只能记为 `10/20`，还需要 10 次成功的新 SSID 验证，不能宣称达到
本节要求的 20 次稳定性发布标准。

“进程被杀后由物理事件恢复”已独立实测但未通过。排除默认 HOME 和
`START_STICKY` 自动重启后，应用保持无 PID、无运行服务且 `stopped=false`；真实
开镜广播的 flags 为 `0x40000010`，其中 `0x40000000` 是
`FLAG_RECEIVER_REGISTERED_ONLY`。该固件只向运行中动态接收器投递物理事件，清单
静态 `WearFoldRecoveryReceiver` 无法从无进程状态拉起应用，需要调整恢复机制或
获得厂商可投递到静态接收器的事件通道。

用户随后接受 MVP 兜底方案：保留默认 HOME 与 `START_STICKY`，会话服务重建时主动
恢复 HOME Activity；物理状态为 `UNKNOWN` 时只恢复 BLE，明确拒绝会话命令并保持
Wi-Fi、CameraX、WebRTC 关闭；手机提示用户完成一次折叠和重新佩戴，只有收到明确
`OPEN + WORN` 后才允许正常会话。

该方案已在眼镜 `2.20-home-ble-recovery-task7 (32)` 与手机
`1.18-physical-unknown-recovery-task7 (19)` 上独立通过。杀死 PID 5549 后约 1 秒恢复
为 PID 5613，HOME Activity 回到前台，BLE 为 `READY`，连续观察 80 秒进程未再次被
系统销毁，且 Wi-Fi、相机、流媒体均保持关闭。真实
`FOLDED + NOT_WORN -> OPEN + WORN` 循环在 3 秒确认后恢复 BLE，PID 不变；随后完整
配网回归取得 IPv4，完成 TCP 8888、ICE/DataChannel 与约 10 FPS 视频，停止后约
130 ms 完成会话关闭，并释放 Wi-Fi、相机和流媒体服务。

因此“厂商物理广播从绝对无进程状态直接拉起”仍因固件限制失败，但用户批准的
MVP 恢复路径已经通过。该回归复用了 `BOLON-WXC-T7-10`，不增加新 SSID 计数，
Task 7 稳定性门槛仍为 `10/20`。

### 阶段 8：手机会话控制整合

状态：部分完成。Task 7 联调已验证“开始辅助”先监听 8888 再发送 `START_SESSION`，
并修复“停止辅助”为先发送 `STOP_SESSION`、等待 `STOPPED` 后再退出页面和释放本地
资源。手机后台/返回、完整错误展示和更广泛生命周期矩阵仍需按本阶段继续验收。

任务：

1. “开始辅助”先启动模型和 8888。
2. 确认端口监听后启动 BLE。
3. 根据是否有凭据发送对应命令。
4. 显示眼镜状态和错误。
5. 收到首帧后进入 Running。
6. “停止辅助”发送 STOP 并释放本地资源。

完成标志：用户只需要一个“开始辅助”和一个“停止辅助”主流程。

### 阶段 9：无 ADB 冷启动

任务：

1. 完成一次部署后拔掉 USB。
2. 眼镜彻底关机。
3. 手机和眼镜重新启动。
4. 不使用 ADB、Android Studio 或官方 App。
5. 完成热点连接和视频首帧。
6. 重复至少 5 次。

完成标志：5 次冷启动全部通过。

### 阶段 10：功耗与温度验证

任务：

1. Streaming 运行 15 分钟，记录温度和电量。
2. 折叠待机 30 分钟，确认 Wi-Fi、相机和 WebRTC 均关闭。
3. 对比修改前后的表面温升。
4. 检查不存在持续 Partial WakeLock。

完成标志：折叠待机不再因持续 Wi-Fi/视频服务明显发热。

## 15. 自动化测试规格

### 15.1 单元测试

眼镜端：

- 状态机合法转换；
- 折叠事件在任意状态都进入 FOLDED；
- START_SESSION 在未佩戴时被拒绝；
- STOP_SESSION 幂等；
- BLE JSON 编解码；
- requestId 关联；
- 错误码映射；
- Wi-Fi 成功后凭据清理。

手机端：

- 启动顺序：8888 先于 BLE 命令；
- GATT 写成功不等于会话成功；
- 状态 Notify 驱动 UI；
- 首帧驱动 Running；
- STOP 资源释放；
- 超时和重试状态。

### 15.2 Android instrumentation

- 服务启动与 START_STICKY 恢复；
- 动态广播接收；
- 折叠状态模拟后调用 Wi-Fi 关闭接口；
- 前台服务通知和生命周期；
- BLE 服务注册；
- Camera 权限缺失时返回明确错误；
- Activity 销毁后服务状态不丢失。

## 16. 真机验收矩阵

| 场景 | 次数 | 预期结果 |
| --- | ---: | --- |
| 已保存热点首次启动 | 10 | 45 秒内首帧 |
| 折叠后打开并佩戴 | 10 | 自动恢复，无 ADB |
| 折叠待机 | 10 | 5 秒内 Wi-Fi off |
| 全新 SSID 首次配网 | 20 | 无系统 UI，获得 IPv4 |
| 错误密码再修正 | 5 | 明确报错，可重试成功 |
| BLE 写入中断 | 5 | 不崩溃，重新广播 |
| 热点关闭再开启 | 5 | 状态正确并可恢复 |
| 手机 App 后台再返回 | 10 | 无 CLOSE-WAIT，重新首帧 |
| 眼镜彻底关机再启动 | 5 | 无 ADB 完成视频 |
| 手机重启 | 5 | 重新开始辅助后成功 |

## 17. 性能与时间指标

```text
镜腿折叠 -> 停止视频：<= 2 秒
镜腿折叠 -> Wi-Fi disabled：<= 5 秒
打开并佩戴 -> BLE 广播可见：<= 5 秒
BLE 扫描与连接：<= 10 秒
新热点 -> IPv4：<= 30 秒
已保存热点 -> IPv4：<= 20 秒
IPv4 ready -> TCP connected：<= 10 秒
用户点击开始 -> 视频首帧：<= 45 秒
```

手机持续接收目标仍为约 10 FPS 或更高。折叠状态不得保留 CameraX、WebRTC 或应用 WakeLock。

## 18. 日志与诊断

建议统一 Tag：

```text
GlassesSession
GlassesWearFold
GlassesBLE
GlassesWifi
GlassesStream
GlassesWebRTC
PhoneSession
PhoneBLE
StreamSignal
PhoneWebRTC
PhoneVideo
```

每次会话生成短 `sessionId/requestId`，日志记录状态转换和耗时。

禁止记录：

- 热点密码；
- 完整 BLE 地址；
- 官方账号令牌；
- 可复用厂商认证载荷。

## 19. 风险与决策门

### 19.1 厂商广播不稳定

如果开机不补发初始状态，需要：

- 在首次广播前保持 Wi-Fi 关闭；
- 通过 `SCREEN_ON` 仅触发状态查询或等待，不直接开流；
- 评估是否存在厂商状态查询 API。

### 19.2 旧版 Wi-Fi API 被拒绝

如果 20 次全新 SSID 测试不能全部通过，不继续堆叠 BLE 逻辑。转入：

- Device Owner；
- 系统预装；
- 官方 App 首次配网；
- 部署工具预置热点。

### 19.3 摄像头厂商激活依赖

若打开/佩戴后 CameraX 仍返回 `CAMERA_DISABLED`，需要单独确认官方 App/CXR 激活是否仍是必要前置条件。

本 MVP 不通过盲写未知厂商 GATT 特征规避该限制。

### 19.4 多服务竞争

发布前必须保证只有一个组件决定 Wi-Fi 开关和会话目标。禁止：

```text
Activity 尝试开 Wi-Fi
+ RecoveryService 尝试开 Wi-Fi
+ BleService 尝试开 Wi-Fi
```

统一由 `GlassesSessionService` 串行处理。

## 20. Definition of Done

以下条件全部满足才认为本 MVP 完成：

1. 折叠后 Wi-Fi、CameraX、WebRTC 和自定义 BLE 都关闭。
2. 打开并佩戴后 BLE 能稳定被手机发现。
3. 手机先启动 8888，再触发眼镜联网。
4. 已保存热点连续 10 次恢复成功。
5. 全新热点达到既定稳定性门槛，或明确选择降级路径。
6. 手机显示端到端状态，不再把 GATT 写成功当作配网成功。
7. 手机前后台重连没有 `CLOSE-WAIT`。
8. 眼镜冷启动至少 5 次无 ADB 成功。
9. 折叠待机 30 分钟无持续 Wi-Fi 导致的明显发热。
10. 两端构建、单元测试和目标 instrumentation 测试通过。
11. `MVP_VERIFICATION.md` 更新真实结果和未通过项。
12. 文档中不包含热点密码和可复用认证材料。

## 21. 推荐执行顺序摘要

严格按以下顺序推进：

```text
采集真实镜腿/佩戴值
-> 统一 GlassesSessionService
-> 折叠关流和关 Wi-Fi
-> 打开/佩戴后只启 BLE
-> BLE v2 状态通知
-> 已保存热点恢复
-> 全新热点能力闸门
-> 手机会话 UI 整合
-> 无 ADB 冷启动
-> 功耗、温度和稳定性验收
```

不要先实现完整 BLE UI，再去判断 Android 是否允许无屏眼镜静默加入全新热点；首次 Wi-Fi 能力仍然是整个方案的产品决策门。

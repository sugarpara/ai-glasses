# BOLON AI Glasses 联网与视频深度 MVP 全过程总结

## 1. 文档信息

- 整理日期：2026-09-01
- 目标设备：BOLON AI Glasses / Rokid RV201（Android 12，API 32）
- 目标手机：HONOR REP-AN00（Android 15）
- 当前范围：仅打通自有眼镜 App 与自有手机 App 之间的联网链路
- 当前原则：把眼镜作为普通 Android 设备开发，不依赖官方手机 App、官方 CXR 业务协议或官方账号激活

本文汇总从最初目标、方案规划、真机验证、厂商协议调查，到当前“纯 Android 自定义 BLE 配网 + Wi-Fi”方向的完整过程。

本文不包含完整蓝牙地址、Wi-Fi 密码、官方账号令牌、GATT 原始载荷或其他可复用认证材料。

### 1.1 2026-09-02 整合进度补充

本节覆盖并取代本文后续仍写有“队友工程当前不可读取”等旧状态描述。当前已整合并
部署手机端 `1.18-physical-unknown-recovery-task7 (19)` 与眼镜端
`2.20-home-ble-recovery-task7 (32)`，BLE v2、静默热点配网、TCP 8888 信令、
WebRTC 视频、停止握手和折叠/佩戴生命周期均已在两台实机上联调。

Task 7 当前为 `10/20` 个成功的新 SSID 端到端轮次，还需 10 轮才能达到原定稳定性
门槛。新增 `BOLON-WXC-T7-08` 至 `10` 三轮均在约 1 秒内取得 IPv4，完成
ICE/DataChannel/视频，并在停止后释放 Wi-Fi、相机和流媒体服务，无 crash/ANR。

原始“由真实厂商物理事件从绝对无进程状态直接拉起”验收失败。厂商开合广播携带
`FLAG_RECEIVER_REGISTERED_ONLY`，只投递给运行中动态接收器，因此清单静态接收器
无法承担冷拉起。

用户已接受并验证 MVP 兜底：`START_STICKY` 重建会话服务后主动恢复默认 HOME
Activity；物理状态未知时仅恢复 BLE，Wi-Fi、相机和 WebRTC 保持关闭，手机提示完成
一次折叠/重新佩戴。杀死眼镜 PID 5549 后约 1 秒恢复为 PID 5613，连续观察 80 秒未
再次被系统销毁；真实 `FOLDED + NOT_WORN -> OPEN + WORN` 循环后 BLE 在 3 秒确认期
结束时恢复。随后完成 IPv4、TCP 8888、ICE/DataChannel、约 10 FPS 视频及停止资源
释放回归。因此 Task 7 的用户批准 MVP 恢复范围已通过，原始 20/20 新 SSID 稳定性
门槛仍为 `10/20`，剩余 10 轮按用户决定暂缓。下一步可继续 Task 8 的完整手机会话
控制矩阵和 Task 9 的无 ADB 冷启动验收。详细证据见眼镜工程
`TASK_07_FIRST_TIME_PROVISIONING_VERIFICATION.md`。

## 2. 最初目的

最初目标是构建以下完整链路：

```text
BOLON AI Glasses 摄像头
-> 实时采集第一视角视频
-> 手机与眼镜建立本地连接
-> 通过 WebRTC 将视频发送到手机
-> 手机复用现有 YOLO26 Depth MVP 进行单目深度估计
-> 输出深度图、障碍判断和后续音频反馈
```

期望最终不依赖 USB、ADB 或外网，在没有路由器时也可以由手机开启热点完成局域网传输。

最初把问题拆成三个相互独立的层次：

```text
控制面：蓝牙发现、配对、认证、首次配网、状态控制
网络面：眼镜加入手机热点、获得 IPv4、发现手机地址
数据面：WebRTC 信令、ICE、视频轨道和手机端深度推理
```

## 3. 已有文档与参考资料

以下内容已有独立文档，本总结只保留关键结论。

| 文档 | 路径 | 已总结内容 |
| --- | --- | --- |
| 手机 YOLO26 Depth MVP 规格 | `D:\文档\ChatGPT\Android环境配置\docs\yolo26-depth-android-mvp-spec.md` | CameraX、LiteRT、GPU/CPU 回退、深度图和分阶段验收 |
| 早期 BOLON 连接 MVP 规格 | `D:\文档\ChatGPT\Android环境配置\docs\bolon-webrtc-depth-connection-mvp-spec.md` | 官方蓝牙/CXR、Wi-Fi、WebRTC 三层方案和早期执行计划 |
| 眼镜相机诊断总结 | `D:\文档\ChatGPT\Android环境配置\glasses-camera-diagnostic\README.md` | Camera2 能力、休眠限制、官方连接状态下的相机恢复 |
| 实体输入映射 | `D:\文档\ChatGPT\Android环境配置\glasses-camera-diagnostic\PHASE5_INPUT_MAPPING.md` | 按键、触控板、佩戴、休眠和唤醒实验 |
| 手机 GATT 诊断工程说明 | `D:\文档\ChatGPT\Android环境配置\phone-gatt-diagnostic\README.md` | BLE 服务、只读 GATT、RFCOMM 和 CXR ABI 诊断能力 |
| 队友连接方案总结 | `D:\文档\ChatGPT\Android环境配置\docs\PHONE_GLASSES_CONNECTION_SUMMARY.md` | CameraX/WebRTC、热点默认网关、Saved Wi-Fi、BLE 配网尝试 |
| 当前手机深度项目说明 | `D:\all_projects\AndroidStudioProjects\glasses\README.md` | 已完成的深度、地面过滤、障碍网格和 HRTF 音频流水线 |

队友总结中提到的 WebRTC 工程源码路径当前机器上不存在，因此目前只能将该文档作为已经完成过的实验依据，不能直接复用其代码：

```text
D:\Intern Projects\AI glass\connected3.0(onlywifi)\glasses-webrtc-hotspot
D:\Intern Projects\AI glass\connected3.0(onlywifi)\webrtc-build-glasses-wifi-probe
```

参考过的 Rokid 裸机文档包括：

- 裸机开发入口与建议阅读顺序。
- “按键与佩戴折叠”文档，documentId 为 `89cdeabac11f493bab24eac21150b984`。

这些文档只用于了解 Android 输入和硬件能力。当前方案不使用官方 App 业务协议。

## 4. 方案演进

### 4.1 第一版：同一局域网或手机热点 + WebRTC

最早确定手机和眼镜只要处于同一局域网即可传输：

```text
眼镜 CameraX
-> TCP/WebSocket 信令
-> WebRTC PeerConnection
-> 手机接收视频
-> 手机执行深度推理
```

没有外网时，手机热点可以充当局域网。WebRTC 在该场景只使用局域网 host candidate，不要求公网 STUN/TURN。

### 4.2 第二版：尝试复用官方 App 的蓝牙连接与激活能力

由于眼镜无屏，最初考虑复用官方 App 的流程：

```text
官方方式发现/导入眼镜
-> 蓝牙认证和激活
-> 下发热点信息
-> 眼镜连接 Wi-Fi
-> WebRTC 传输
```

随后对官方 BLE、经典蓝牙 RFCOMM 和 CXR 进行了较深入的静态及动态分析。

### 4.3 第三版：放弃官方协议，眼镜运行自有 Android App

后续目标发生了明确变化：

```text
设备启动
-> 自有 App 成为默认入口或由队友的启动服务拉起
-> 自有 BLE GATT 服务等待手机
-> 手机发送热点信息
-> 眼镜使用 Android Wi-Fi API 连接热点
-> 队友的 WebRTC 模块开始发送摄像头视频
```

当前不再继续实现官方 BLE 导入、CXR auth/active 或官方 Wi-Fi 命令。

### 4.4 当前职责边界

用户已明确：

- WebRTC 代码在队友侧。
- 开机广播、后台服务和默认启动代码在队友侧。
- 当前任务只负责打通联网链路。

因此当前交付边界为：

```text
手机热点信息
-> 自定义 BLE GATT
-> 眼镜接收凭据
-> Android Wi-Fi 连接
-> 输出 Network + 手机热点网关地址
```

## 5. 真机与环境验证

### 5.1 ADB 环境

已确认手机和眼镜均可以通过 ADB 访问：

- 眼镜型号属性：`RG-glasses` / RV201。
- 眼镜系统：Android 12，API 32，`user` 构建，`ro.debuggable=0`。
- 眼镜为 arm64 设备。
- 手机 USB 调试依赖荣耀“手机助理”，后续测试不能随意停止该组件。

眼镜处于深度休眠或厂商限制状态时，ADB framework 命令可能返回 `error: closed`。这并不一定代表 USB 物理断开，而可能表示 Android framework 尚未处于可服务状态。

### 5.2 官方 App 导入流程

真机操作已确认：

- 官方手机 App 可以通过蓝牙发现并导入眼镜。
- 导入过程要求手机定位服务开启。
- 未开启定位时不能完成导入。
- 开启定位并点击导入后可以成功。
- 官方 App “已连接”与系统蓝牙“已配对”是不同状态。

该流程现在只作为历史验证依据，不再是目标实现的一部分。

## 6. 眼镜相机验证

使用独立诊断 APK `glasses-camera-diagnostic` 验证了眼镜的普通 Android Camera2 能力。

已验证：

- 第三方 App 可以打开后置摄像头。
- `640x480`、请求 `15 FPS` 时实测约 `14.6-14.7 FPS`。
- 第一个测试帧可以保存为 JPEG。
- 眼镜内部显示/电源状态休眠时，Camera2 可能返回 `CAMERA_DISABLED`。
- Activity 已在前台且系统恢复后，可以重新打开 Camera2。

因此“第三方 Android App 读取眼镜摄像头”本身已经得到证明，不是当前阻塞点。

## 7. 实体按键、佩戴与休眠验证

### 7.1 内核输入采集

通过 `adb shell getevent -lt` 采集了镜腿按键和触控板原始事件。

已验证存在：

- 主功能键短按和长按。
- 触控板单击、双击、长按。
- 触控板前滑和后滑。
- 双指长按识别结果会随接触质量出现不同代码。

佩戴和摘下有提示音，但此前在普通 `/dev/input/event*` 中没有观察到对应按键事件，说明佩戴状态可能由独立传感器或厂商系统服务处理。

详细映射见：

```text
D:\文档\ChatGPT\Android环境配置\glasses-camera-diagnostic\PHASE5_INPUT_MAPPING.md
```

### 7.2 官方 App 连接时的唤醒结果

在官方 App 已连接、诊断 Activity 已经位于前台任务、AI 快捷指令关闭的条件下：

```text
ADB 强制休眠
-> 双指长按
-> Activity 收到 onResume / SCREEN_ON
-> Camera2 重新打开并通过测试
```

诊断 Activity 没有收到对应的 `dispatchKeyEvent()`，说明厂商系统消费了手势，但执行了系统唤醒或恢复行为。

### 7.3 只做系统蓝牙配对时的结果

当官方 App 断开，只保留 Android 系统蓝牙配对和标准经典蓝牙配置时：

- 原始触控事件仍然存在。
- 双指长按不能恢复休眠后的 Activity。
- 没有 `SCREEN_ON`、`onResume` 或 Camera2 自动恢复。

结论：系统蓝牙配对不等于厂商激活状态，也不自动提供应用控制通道。

## 8. 裸机按键文档与系统确认框

Rokid 裸机文档将输入分为两类。

### 8.1 标准 Android KeyEvent

- 单指单击：`KEYCODE_ENTER`。
- 单指双击：`KEYCODE_BACK`。
- 单指前滑：`DPAD_RIGHT -> DPAD_DOWN`。
- 单指后滑：`DPAD_LEFT -> DPAD_UP`。
- 其他触控动作还可能产生 `KEYCODE_PROG_BLUE`、`KEYCODE_NOTIFICATION` 或 `KEYCODE_SETTINGS`。

标准 KeyEvent 由 Android 输入系统发送给当前获得焦点的 Window，不要求官方手机 App 运行。

### 8.2 厂商系统广播

文档还列出了功能键、双指手势、佩戴和折叠状态广播。它们要求应用动态注册 `BroadcastReceiver`。

这些广播可以控制自有 App 的业务，例如开始配网、重试或取消，但普通 App 不能收到广播后向另一个系统 App 注入点击事件。

### 8.3 “焦点”的含义

系统 Wi-Fi 确认框可能包含“取消”和“连接”等按钮。焦点表示其中哪一个按钮当前接收 `ENTER`。

```text
方向键事件移动焦点
-> KEYCODE_ENTER 激活当前焦点按钮
```

由于眼镜没有显示屏，用户看不到焦点位置。系统弹窗布局、默认焦点或 OTA 变化都可能导致同一手势序列选择不同按钮。

当前只确认“标准 KeyEvent 理论上可以送给前台系统 Window”，尚未完成以下真机闭环：

```text
拉起真实 Wi-Fi 确认框
-> 只使用眼镜触控板移动焦点
-> 单指单击确认
-> 获得 Wi-Fi Network
```

因此按键操作目前只能作为待验证的人工兜底，不能写成已完成能力。

## 9. BLE GATT 验证

### 9.1 自研诊断 App

创建了手机端诊断工程：

```text
D:\文档\ChatGPT\Android环境配置\phone-gatt-diagnostic
```

该 App 的原则是：

- 扫描和读取已公开为可读的特征。
- 枚举服务、特征和描述符。
- 只启用标准 CCCD 通知。
- 不向未知厂商特征盲写数据。
- 原始连接信息不显示在 UI 或交付文档中。

### 9.2 已确认的厂商 BLE 服务

自研 App 已成功：

- 发现 BOLON BLE 广播。
- 识别厂商服务 `0x9100`。
- 建立 GATT 连接。
- 发现 `0x9300`、`0x9301`、`0x9201` 至 `0x9204`。
- 对可读特征完成只读获取。

BLE 广播身份和经典蓝牙配对身份可能不同。自研 App 必须连接实时扫描获得的 `0x9100` BLE 身份，不能直接把 bonded classic 地址当作 GATT 地址。

### 9.3 `0x9300` 分析结论

读取样本显示其为固定长度、内部存在重复记录的二进制结构。

已排除：

- 不能把记录中的短数字字段直接当作 CXR `account`。
- 官方 `account` 是更长的动态认证值。
- 不能在没有解析协议的情况下将该值传给 `nativeAuth`。

原始样本仅保存在本地诊断目录，不应提交或复制到总结文档。

## 10. 官方传输链调查

通过官方 APK、运行日志、bugreport、DEX 和 native library 分析，曾定位到大致链路：

```text
CompanionDeviceManager 扫描 0x9100
-> BLE 导入
-> 获得动态 UUID、经典蓝牙地址、account 和 glassesType
-> 经典蓝牙 RFCOMM
-> CXR auth
-> CXR active
```

观察到的 RFCOMM UUID 为：

```text
7cda0790-a779-45f5-92cc-cbcc8ea715fd
```

但动态连接信息和认证数据不得硬编码。

### 10.1 CXR JNI 诊断

诊断工程中修正过厂商 JNI ABI 适配问题，包括：

- 补齐线程优先级 native 方法。
- 校正 client list native 签名。
- 增加厂商日志兼容类。
- 让“初始化 CXR”按钮真正执行 ABI 自检。

已确认：

- 三个 native library 可以加载。
- CXR `nativeCreate` 可以成功。
- 自检没有调用 `auth`、`active`，也没有向眼镜写数据。

`libcxr-bridge-jni.so` 中包含本机 IPC 相关痕迹，可能是眼镜侧服务桥，而不是手机 BLE 导入解析器，未得到最终确认。

### 10.2 RFCOMM 的状态依赖

实验出现过两类结果：

1. 在眼镜此前经过官方导入/激活的状态下，只读 RFCOMM 曾连接并保持稳定，但不发送 CXR 数据时不能恢复休眠、Activity 或 Camera2。
2. 在只完成系统蓝牙配对、未完成官方导入的较干净状态下，SDP 查询虽然返回完成，但目标 UUID 的 RFCOMM channel 为 0，连接失败。

临时停用官方 App 后再次测试，失败仍然存在，说明后一次失败不是官方 App 抢占 Socket，而是眼镜当时没有公布可连接的 RFCOMM 服务通道。

这两个结果说明 RFCOMM 可用性依赖眼镜所处的厂商状态。不能把一次连接成功视为系统配对后的固定能力。

## 11. 系统蓝牙配对验证

通过手机系统蓝牙设置完成了 BOLON 的系统配对。

已确认：

- 手机能够发现 `Bolon_5029`。
- 配对需要手机确认系统配对请求。
- 配对完成后设备类型为双模蓝牙（BLE + BR/EDR）。
- 系统配对完成不代表自定义 GATT 数据通道已经建立。
- 系统配对完成不代表厂商 RFCOMM UUID 已经发布。
- 系统配对完成不代表眼镜可以接收我们自定义的 Wi-Fi 参数。

官方 App 使用 CompanionDeviceService，可能被系统自动拉起。单纯强制停止官方 App 不能保证隔离；测试中曾临时 disable 后立即恢复，以确认 RFCOMM 失败与官方后台抢占无关。

## 12. 队友已经验证的 WebRTC 与 Wi-Fi 事实

根据队友总结，以下链路已经在其工程中跑通：

```text
眼镜 CameraX
-> WebRTC
-> 手机接收视频
```

队友已验证：

- TCP 8888 可用于局域网信令。
- SDP、ICE Candidate、DataChannel 和视频轨道可以建立。
- 手机接收眼镜视频约 10 FPS。
- 手机热点可在没有外网时提供局域网。
- 眼镜可将默认 IPv4 网关识别为热点手机地址，避免写死手机 IP。
- 已保存热点可以自动重连，并恢复 WebRTC。
- BLE 曾成功把 SSID、密码送到眼镜。
- 旧版 `WifiConfiguration` 曾出现完整连接成功，也出现过 Android 拒绝配置。

队友方案的主要未完成点不是 WebRTC，而是：

- 自有 App/服务在开机、休眠和进程回收后不能稳定恢复。
- 自定义 BLE 广播不能保证每次自动重新启动。
- 全新热点的首次静默配网不能保证重复成功。

当前用户已说明，WebRTC、启动广播和服务基础由队友负责，因此本轮不重复实现这些部分。

## 13. 当前生效的纯 Android 联网方案

当前不再依赖官方控制面，目标链路为：

```text
队友启动自有眼镜 App/Service
-> 联网模块启动自定义 BLE GATT Server 和广播
-> 自有手机 App 扫描并连接 GATT
-> 手机发送热点 SSID、密码和安全类型
-> 眼镜使用 Android Wi-Fi API 连接热点
-> ConnectivityManager 返回 Wi-Fi Network
-> LinkProperties 提取默认 IPv4 网关
-> 把 Network 和手机网关地址交给队友 WebRTC 模块
```

建议联网模块对外提供类似接口：

```text
startProvisioning()
stopProvisioning()
StateFlow<ProvisioningState>
onNetworkReady(Network, Inet4Address gateway)
```

BLE 只负责传递配置和状态，不承载视频。

## 14. 当前进行到哪一步

目前已经完成：

- 证明眼镜第三方相机可用。
- 证明局域网 WebRTC 视频链可行（队友侧）。
- 证明手机热点无外网场景可用（队友侧）。
- 证明自定义 BLE GATT 可以由眼镜广播、由手机发现并收取参数（队友侧和当前诊断侧分别有证据）。
- 证明 Saved Wi-Fi 可以自动重连（队友侧）。
- 证明系统蓝牙配对不能替代自定义业务通道。
- 证明官方协议路线复杂且状态依赖，不再适合作为当前 MVP 主线。
- 从裸机文档确认部分触控手势会产生标准 Android KeyEvent。

当前真正处于以下阶段：

```text
验证眼镜普通 Android App
能否在“从未保存过的新热点”场景下
无屏、无确认、可重复地静默加入 Wi-Fi
```

BLE 协议本身不应先于该能力闸门大规模实现。即使 BLE 成功把密码送到眼镜，如果 Android 拒绝静默加入网络，完整链路仍然无法成立。

## 15. 为什么卡在这里

### 15.1 Android 12 对首次 Wi-Fi 配置有限制

普通第三方 App 在较新的 Android 上不能稳定使用旧版 `WifiConfiguration.addNetwork/enableNetwork` 静默加入任意新网络。

队友曾成功过，但也出现过系统拒绝，可能与以下因素有关：

- targetSdk。
- App 是否为 Device Owner/Profile Owner。
- 网络是否已经保存。
- 设备固件兼容行为。
- 测试前眼镜是否处于特殊厂商激活状态。

因此必须重新做“全新 SSID、无历史配置”的受控测试。

### 15.2 `WifiNetworkSpecifier` 会弹系统确认框

`WifiNetworkSpecifier/requestNetwork` 是公开 Android API，但首次连接通常会启动系统确认 Activity。

眼镜无屏，用户看不到按钮和焦点。此前队友方案正是停在该确认界面并最终超时。

即使以后通过实体按键完成确认，它建立的也偏向应用持有的网络请求，不一定等价于系统永久保存的 Wi-Fi。App 重启和下次连接行为仍需验证。

### 15.3 实体按键操作确认框尚未闭环

裸机文档表明单指单击为 `KEYCODE_ENTER`，滑动会产生 DPAD 序列，因此理论上可能操作系统弹窗。

但尚未验证：

- 弹窗首次出现时哪个按钮有焦点。
- 滑动一次后焦点最终落在哪里。
- 单指单击是否真的触发“连接”。
- 每次弹窗的焦点顺序是否稳定。
- 系统升级或弹窗布局变化后是否仍然有效。

因此它目前只能作为人工兜底候选，而不是无人值守方案。

### 15.4 眼镜是 `user` 构建且没有 Root

当前没有系统签名、Root 或普通 App 的跨应用按键注入权限。

如果普通 Wi-Fi API 最终被系统拒绝，可能需要以下之一：

- 将自有 App 配置为 Device Owner/DPC。
- 单独使用较低 targetSdk 的配网 Helper，并验证 Android 12 兼容行为。
- 安装/部署时通过 ADB 预置固定热点。
- 修改系统镜像或使用系统级签名；当前不属于优先 MVP 范围。

### 15.5 队友代码当前不可读取

队友的 WebRTC、启动和恢复工程尚未复制到当前机器，因此目前只能定义联网模块接口，不能直接完成最终集成测试。

## 16. 下一步执行顺序

### 阶段 0：最小 Wi-Fi 能力 Probe

先创建一个不包含 BLE、WebRTC、CameraX 的最小眼镜 APK。

输入方式先使用 ADB/debug intent，避免把 BLE 问题混入 Wi-Fi 权限判断。

受控条件：

```text
手机开启一个眼镜从未保存过的新 WPA2 热点
-> Probe 获得 SSID 和密码
-> 尝试静默保存并连接
-> 等待 ConnectivityManager 回调
-> 检查 IPv4、默认网关和是否出现系统确认框
```

验收：

- 不出现系统 UI。
- 不需要 ADB 点击确认。
- 30 秒内获得 Wi-Fi IPv4。
- 能提取热点手机默认网关。
- 更换至少三个新 SSID 重复成功。

### 阶段 1：Wi-Fi 权限路径决策

按以下顺序验证：

1. 普通 App + 当前 targetSdk 的旧版 Wi-Fi API。
2. 独立低 targetSdk 配网 Helper。
3. Device Owner/DPC。
4. 固定 SSID 的一次性 ADB 预置方案。

只有确定至少一条路径稳定后，才进入完整 BLE 配网。

### 阶段 2：系统确认框按键兜底实验

若公开 API必须弹窗，再做以下独立测试：

1. Probe 拉起真实 Wi-Fi 确认框。
2. ADB 只观察 Window 和焦点，不注入按键。
3. 用户分别执行前滑、后滑、单指单击。
4. 记录焦点移动和最终连接结果。
5. 重启眼镜验证批准或网络是否被记住。

通过后仍只作为首次人工配网降级路径。

### 阶段 3：自定义 BLE 配网

建议眼镜作为 GATT Server，手机作为 Central/Client。

最小特征：

```text
DEVICE_INFO        只读：协议版本和设备能力
PROVISION_REQUEST  写入：SSID、密码、安全类型、requestId
PROVISION_STATUS   Notify：接收、连接中、成功或失败码
```

状态建议：

```text
RECEIVED
WIFI_ENABLING
CONNECTING
CONNECTED
FAILED_INVALID_CREDENTIALS
FAILED_PLATFORM_REJECTED
FAILED_TIMEOUT
```

禁止在 Logcat 和 UI 中打印明文密码。

### 阶段 4：与队友模块集成

联网成功后只向 WebRTC 模块输出：

```text
Network
手机热点网关 IPv4
网络状态变化
```

WebRTC、CameraX、开机服务和默认 Launcher 仍由队友工程负责。

### 阶段 5：恢复与稳定性

至少验证：

- 新热点首次配网 10 次。
- 热点关闭再开启。
- SSID/密码变更。
- BLE 中途断开。
- 眼镜重启。
- 眼镜休眠和唤醒。
- 官方 App 从未启动。

## 17. 当前结论

已经证明的核心可行性是：

```text
眼镜摄像头可由第三方 App 使用
+ 自定义 BLE 可以建立
+ 手机热点可以提供无外网局域网
+ WebRTC 视频可以传到手机
+ 手机端深度推理已经完成
```

当前唯一必须优先解决的基础阻点是：

```text
Android 12 无屏眼镜
如何由普通自有 App
稳定、可重复地加入一个从未保存过的新 Wi-Fi
```

如果该能力通过普通 API、低 targetSdk Helper 或 Device Owner 任一路径验证成功，后续 BLE 传递热点信息只是工程实现问题。

如果所有静默路径均被固件拒绝，则产品必须在以下边界中选择：

- 首次通过实体按键确认系统弹窗。
- 首次安装时由 ADB 预置固定热点。
- 将自有 App 部署为 Device Owner/系统级应用。
- 接受同名固定热点和 Saved Wi-Fi 自动重连，而不支持任意新热点。

官方 App/CXR 路线已经降级为历史分析证据，不再是当前实施主线。

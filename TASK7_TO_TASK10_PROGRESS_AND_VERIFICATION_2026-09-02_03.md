# BOLON AI 眼镜 Task 7-10 整合、优化与验证总结

时间范围：2026-09-02 下午至 2026-09-03

本文汇总手机端与眼镜端工程迁移后的 Task 7-10 联调过程，包括发现的问题、根因、
代码和系统配置修改、真机测试结果、当前版本以及后续工作。文档不记录热点密码、
完整蓝牙地址或其他可复用认证信息。

## 1. 当前工程与版本

| 端 | 工程 | 包名 | 当前版本 |
| --- | --- | --- | --- |
| 手机端 | `glasses-webrtc-hotspot` | `com.example.glasses` | `1.26-raw-depth-classification-preview (27)` |
| 眼镜端 | `webrtc-build-glasses-wifi-probe` | `com.rokid.glassesbaredevsample` | `2.29-not-worn-15s-debounce (41)` |

眼镜端当前视频参数：

- CameraX 采集/分析分辨率：`640x360`。
- CameraX 目标帧率：`15 FPS`。
- WebRTC 输出目标：`640x360 @ 15 FPS`。
- WebRTC 码率：最小 `300 kbps`、起始 `800 kbps`、最大 `1500 kbps`。
- CameraX 背压策略：`KEEP_ONLY_LATEST`。
- 眼镜端禁用 WebRTC Java NetworkMonitor，避免该固件在网络切换时触发原生崩溃。

手机端当前显示与模型参数：

- 原始 WebRTC 视频作为对照画面直接显示，不重复进入模型。
- 深度图刷新间隔：`100 ms`，显示目标约 `10 FPS`。
- 分类/障碍物图刷新间隔：`100 ms`，显示目标约 `10 FPS`。
- 模型输入和深度输出仍为 `640x640`；眼镜传输分辨率变化不会改变模型张量尺寸。

## 2. 总体进度

| Task | 当前状态 | 结论 |
| --- | --- | --- |
| Task 7：首次热点配网 | 部分完成 | 新 SSID 端到端成功 `10/20`，剩余 10 轮由用户决定暂缓 |
| Task 7：进程被杀后恢复 | MVP 方案通过 | 厂商物理广播无法从绝对无进程状态拉起 App；`START_STICKY + 默认 HOME + 物理确认` 兜底通过 |
| Task 8：手机会话控制 | 已测范围通过 | 保留前 9 轮结果；后续由用户主动终止，不记为崩溃 |
| Task 9：无 ADB 冷启动 | 通过 | 5 轮均恢复已保存热点并显示视频 |
| Task 10：15 分钟视频稳定性 | 修复后通过 | 两次均超过 15 分钟，CameraX/WebRTC 保持约 15 FPS，进程和服务未丢失 |
| Task 10：折叠待机 | 通过 | Wi-Fi、相机和 WebRTC 释放，之后由固件折叠超时自动关机 |
| Task 10：活动会话功耗 | 仍需优化 | 两次约 16-17 分钟均消耗约 33%-34% 电量 |

9 月 3 日发现的问题不推翻此前所有测试。Task 9 验证的是无 ADB 冷启动、热点恢复和
视频首帧；后来发现的是拔线后持续运行超过约 5 分钟时的 Android 后台策略问题，
属于 Task 10 未覆盖到的持续运行条件。

## 3. Task 7：首次配网与进程恢复

### 3.1 新 SSID 稳定性

迁移到当前设备后继续完成了 3 轮新 SSID：

- `BOLON-WXC-T7-08`；
- `BOLON-WXC-T7-09`；
- `BOLON-WXC-T7-10`。

三轮均完成：

- Android 静默保存新热点，没有出现系统 Wi-Fi 确认界面；
- 约 1 秒或更短时间取得 IPv4；
- TCP 8888 信令连接；
- ICE `CONNECTED/COMPLETED`；
- DataChannel `OPEN`；
- 手机显示眼镜视频；
- 停止后关闭 Wi-Fi，释放 CameraX 和 WebRTC；
- 无 crash、ANR。

Task 7 当前只能记为 `10/20`。原发布门槛还需要 10 个不同新 SSID 的成功轮次，
不能写成 20 轮已完成。

一次旧 SSID/错误热点尝试能够返回明确超时，并且 BLE 仍可继续用于修正请求；修正后
成功联网。这说明普通配网失败不需要重新安装 App。

### 3.2 厂商物理广播限制

原需求希望 App 进程完全不存在时，打开/佩戴眼镜的真实物理事件可以直接拉起 App。
独立测试发现厂商广播携带：

```text
FLAG_RECEIVER_REGISTERED_ONLY
```

该广播只会发送给运行中进程动态注册的接收器。即使清单中的静态接收器存在且启用，
也无法从绝对无进程状态接收该事件。这是眼镜固件的广播传输限制，不是 Manifest 漏配。

### 3.3 用户接受的 MVP 恢复方案

最终采用：

```text
START_STICKY 重建会话服务
-> 主动恢复默认 HOME Activity
-> 物理状态 UNKNOWN 时只恢复 BLE
-> Wi-Fi、CameraX、WebRTC 保持关闭
-> 手机提示完成一次折叠和重新佩戴
-> 收到明确 OPEN + WORN 后才允许会话
```

实测杀死眼镜进程后约 1 秒创建新 PID，并在 80 秒观察期内保持稳定。完成真实物理循环
后 BLE 恢复，随后完整配网、信令、ICE、DataChannel、视频和停止资源释放均通过。

详细 Task 7 证据见：

`webrtc-build-glasses-wifi-probe/TASK_07_FIRST_TIME_PROVISIONING_VERIFICATION.md`

## 4. Task 8：手机会话控制

手机端主流程已整合为：

```text
开始辅助
-> 手机先监听 TCP 8888
-> 建立 BLE 控制连接
-> 发送 START_SESSION 或首次配网命令
-> 显示眼镜阶段状态和错误
-> 收到视频首帧后进入运行状态

停止辅助
-> 发送 STOP_SESSION
-> 可达时等待眼镜返回 STOPPED
-> 释放本地信令并退出实时辅助页面
```

Task 8 保留前 9 轮用户操作结果。用户主动要求停止后续轮次，因此不能将测试终止写成
App 崩溃。如果最终验收要求严格完成 10 轮，还需补 1 轮。

组合测试中覆盖过：

- 手机解锁和返回首页；
- 进入和退出实时辅助；
- 眼镜折叠、打开和重新佩戴；
- 手机热点暂时关闭后重新开启；
- 活动会话中的物理停止与资源释放。

### 4.1 手机旋转导致退出实时辅助

问题：

- 手机屏幕旋转会重建 `MainActivity`，从而退出实时辅助页面。

修复：

- 手机 `1.22-orientation-session (23)` 由 Activity 自行处理 orientation、screenSize、
  screenLayout、smallestScreenSize 和 keyboardHidden 等配置变化，不销毁活动会话。

结果：

- 修复版实机验证后，横竖屏旋转不再退出实时辅助。

## 5. Task 9：无 ADB 冷启动与蓝牙音频

### 5.1 冷启动结果

正式完成 5 轮无 ADB 冷启动：

- 眼镜完全关机再启动；
- 测量轮次不使用 USB、ADB、Android Studio 或 Rokid 官方 App；
- 复用同一已保存手机热点；
- 每轮均重新连接热点并显示视频；
- 结果：`5/5 PASS`。

冷启动通过与后来持续运行失败不矛盾。每轮开机或打开 App 后都会获得临时活跃窗口，
当时确认视频恢复后就进入下一轮，没有让单轮持续超过后台限制触发所需的约 5 分钟。

### 5.2 系统蓝牙音频未连接

现象：

- 自定义 BLE GATT 控制链路正常，但系统经典蓝牙媒体音频可能未连接；
- 视频继续正常，声音却从手机扬声器输出；
- 有时需要 Rokid 官方 App 才能恢复已有 A2DP 媒体连接。

结论：

- 该 A2DP 重连不稳定现象在安装自定义眼镜 App 前也出现过；
- 不能归因于本次 BLE 配网或 WebRTC 修改；
- 普通第三方 Android App 没有可靠公开 API 强制厂商 A2DP 重连。

手机端已增加：

- A2DP/LE Audio 输出设备状态监控；
- 首页、设置和实时辅助中的音频路由提示；
- 音频未连接时提供恢复入口，但不阻塞视频辅助；
- 最终按用户要求，恢复入口直接打开 Android 蓝牙设置，不再优先跳转官方 App。

彻底消除偶发手动重连仍需要 Rokid SDK、系统级权限或固件修复。

## 6. 手机端帧率与三路画面优化

### 6.1 不同帧率的含义

整个链路包含多个不同帧率：

```text
眼镜 CameraX 采集
-> 眼镜 WebRTC 送帧
-> 手机 WebRTC 接收/解码
-> I420 转 Bitmap
-> 模型推理与地面/障碍后处理
-> 深度图和分类图 UI 刷新
```

不能只用手机屏幕刷新率代表模型处理能力。

优化期间的观测：

- 眼镜 CameraX：约 `15 FPS`；
- 眼镜 WebRTC 送帧：约 `15 FPS`；
- 手机模型完整处理：测得约 `12.7 FPS`；
- 原深度图/分类图显示：约 `4 FPS`，主要受 `250 ms` 重绘间隔限制；
- 当前深度图和分类图显示：重绘间隔 `100 ms`，目标约 `10 FPS`。

后续图像和音频算法应参考模型完整处理/后处理帧率，而不是仅参考 UI 重绘帧率。UI 可以
比算法处理慢，但不会自动降低内存中深度和障碍结果的产生频率。

### 6.2 手机版本变化

| 版本 | 主要变化 |
| --- | --- |
| `1.22-orientation-session (23)` | 修复旋转后退出实时辅助 |
| `1.23` | 按用户要求把音频恢复入口改回 Android 蓝牙设置 |
| `1.24` | 增加/完善帧率诊断，用于区分传输、模型处理和显示刷新 |
| `1.25` | 将深度图显示刷新提高到约 10 FPS，用户观察到明显改善 |
| `1.26-raw-depth-classification-preview (27)` | 分类图同样改为 100 ms 刷新，并在深度图上方增加原始 WebRTC 对照画面 |

手机 `1.26` 已实机确认三路画面都能正常显示：

1. 眼镜原始 WebRTC 视频；
2. 深度图；
3. 分类/障碍物图。

原始视频只是测试对照画面，不会再次进入模型，不改变推理输入链路。

## 7. 眼镜端帧率与功耗方向优化

### 7.1 15 FPS 均衡参数

眼镜 CameraX 和 WebRTC 统一限制为 `15 FPS`，避免相机生成高于传输目标、随后又被丢弃
的多余帧。

### 7.2 640x360 分辨率

眼镜采集和 WebRTC 从 `640x480` 调整为明确支持的 `640x360` 16:9 模式：

- 每帧像素量减少 25%；
- 降低 I420 转换和编码输入负载；
- 比 `320x240` 更能保留图像细节；
- 手机模型预处理后仍使用 `640x640` 输入，不改变模型结构。

实测 CameraX 和 WebRTC 仍稳定在 `14.9-15.1 FPS` 左右。该修改确实减少处理量，但
整机耗电没有同比下降，因为相机传感器、ISP、硬件编码、Wi-Fi 发射和持续 WakeLock
仍处于活动状态。

### 7.3 眼镜版本变化

| 版本 | 主要变化 |
| --- | --- |
| `2.25-balanced-15fps-task10 (37)` | 后续 640x360 与生命周期修复前的本地检查点 |
| `2.27-forced-640x360-15fps-task10 (39)` | 强制 CameraX/WebRTC 使用 `640x360 @ 15 FPS` |
| `2.28-foreground-debounce-recovery (40)` | 修复 BLE 前台服务重复启动超时，加入初版物理事件防抖和恢复日志 |
| `2.29-not-worn-15s-debounce (41)` | NOT_WORN 连续确认延长至 15 秒，折叠仍立即停止 |

## 8. 拔掉 USB 后突然断流

该阶段先后发现两条不同故障链，不能混为同一个问题。

### 8.1 佩戴误报与前台服务超时

旧故障链：

```text
实际仍佩戴
-> 固件误报 NOT_WORN
-> 5 秒确认到期
-> App 关闭 Wi-Fi、WebRTC、BLE
-> 后续 WORN 触发 BLE 重启
-> 重复 startForegroundService 后没有再次及时 startForeground
-> ForegroundServiceDidNotStartInTimeException
-> App 进程崩溃
```

修复：

- `BleProvisioningService` 在 `onCreate()` 和每次 `onStartCommand()` 都执行前台提升；
- NOT_WORN 必须连续稳定 15 秒才停止；
- 期间重新收到 WORN 会取消待处理停止；
- FOLDED 保持立即停止。

结果：

- 前台服务超时崩溃未再出现；
- 短时间佩戴误报能够被取消；
- 2.29 仍需补一轮“只摘下、不折叠”的独立 15 秒停止时序回归。

### 8.2 Android 后台限制停止全部服务

修复 8.1 后，仍出现一次实际佩戴状态下的断流。断开前视频保持约 15 FPS，随后系统
在同一毫秒停止三个服务：

```text
am_uid_idle: 10069
Stopping service due to app idle: BleProvisioningService
Stopping service due to app idle: StreamingForegroundService
Stopping service due to app idle: GlassesSessionService
```

之后 CameraX 报 `ERROR_CAMERA_DISABLED`，App 清理流程断开 Wi-Fi，无线 ADB 离线，
手机画面停止。

修复前系统策略：

```text
RUN_IN_BACKGROUND: ignore
RUN_ANY_IN_BACKGROUND: ignore
```

为什么只在拔 USB 后出现：

- 眼镜设置 `stay_on_while_plugged_in=2`，表示 USB 供电时保持唤醒；
- 插线时系统为 `mStayOn=true`、正在充电、Device Idle 为 ACTIVE；
- 拔线后失去 USB 保持唤醒条件；
- 约 4 分 15 秒至 4 分 57 秒后，受限 UID 被系统判定为空闲，三个服务全部停止。

这不是 ADB 协议本身导致，也不是代码监听“拔线”后主动停止。

### 8.3 用户批准的 MVP 系统策略

经用户明确授权，在当前眼镜执行：

```powershell
adb shell cmd appops set com.rokid.glassesbaredevsample RUN_IN_BACKGROUND allow
adb shell cmd appops set com.rokid.glassesbaredevsample RUN_ANY_IN_BACKGROUND allow
adb shell dumpsys deviceidle whitelist +com.rokid.glassesbaredevsample
adb shell am set-standby-bucket com.rokid.glassesbaredevsample active
```

验证结果：

```text
RUN_IN_BACKGROUND: allow
RUN_ANY_IN_BACKGROUND: allow
Device Idle whitelist: user,com.rokid.glassesbaredevsample
Standby bucket: 10 (active)
```

眼镜经历折叠超时关机和重启后，上述配置仍保留。卸载 App、清除系统策略或更换眼镜
设备后可能需要重新执行，后续应写入一次性部署脚本。

白名单不会取消 App 自身的停止逻辑：折叠仍立即停止，摘下连续 15 秒仍应停止。风险
在于未来如果生命周期代码回归，Android 不会再在约 5 分钟后替 App 强制结束视频，
所以每次修改 WakeLock 或会话状态机后都必须复测折叠资源释放。

## 9. Task 10 功耗与稳定性实测

### 9.1 后台策略修复前

- 眼镜展开并佩戴，USB 已拔；
- 视频初期约 15 FPS；
- 约 5 分钟后系统因 App idle 同时停止三个服务；
- 不是热保护、低电量或整机重启。

### 9.2 修复后第 1 次持续视频

| 指标 | 结果 |
| --- | --- |
| 时长 | `17 分 17 秒` |
| 电量 | 约 `100% -> 67%` |
| 电池温度 | 约 `26.0 C -> 28.5 C` |
| CameraX/WebRTC | 全程约 `15 FPS` |
| Wi-Fi 发送数据 | 约 `160 MB` |
| 进程 | PID 未变化 |
| 服务 | 三个服务全程存在 |
| 异常 | 无 app idle 停服、无崩溃、无物理误停 |

该轮证明后台策略有效，但开始时接近满电电量计变化区，因此又执行了第 2 次受控复测。

### 9.3 修复后第 2 次受控复测

测试准备：

- 眼镜充满至 100%；
- 开始前温度约 25 C；
- 手机热点和三路画面运行；
- 电脑与眼镜连接同一手机热点；
- 拔线前清零 BatteryStats；
- 拔线前建立无线 ADB。

| 指标 | 结果 |
| --- | --- |
| 采样时长 | `15 分 17 秒`，BatteryStats 最终相机时长约 `16 分 39 秒` |
| 电量 | `100% -> 66%` |
| 电压 | 约 `4.384 V -> 3.879 V` |
| 电池温度 | `27.0 C -> 29.0 C` |
| 瞬时电流 | 主要在 `204-382 mA`，采样平均约 `280 mA` |
| CameraX/WebRTC | `14.9-15.2 FPS`，无持续降帧 |
| Wi-Fi 发送数据 | `162.44 MB` |
| Wi-Fi Tx 活动时间 | 约 `57 秒` |
| App 进程 | PID 始终为 `2105` |
| 服务 | 三个服务全程存在 |
| 异常 | 无 |

两轮均在约 16-17 分钟消耗 33%-34% 电量，说明高耗电不是单次电量计跳变。简单线性
估算活动续航约 45-55 分钟，但电池曲线并非严格线性，正式续航仍需从稳定满电测到
自动关机。

内部电池温度保持在约 29 C，不代表用户触摸位置或 SoC 是相同温度；表面发热仍需用
同一位置的外部温度计重复测量。

### 9.4 活动会话 WakeLock

BatteryStats 显示视频运行期间持续持有：

```text
com.rokid.glassesbaredevsample:video-stream
```

持续时间基本覆盖整个相机会话。该 WakeLock 用于避免视频期间系统休眠。直接删除可能
重新引入熄屏、拔线或系统 suspend 后断流，因此应通过受控实验改成条件式或有界持有，
不能直接移除。

### 9.5 折叠 30 分钟

停止辅助并折叠后：

- 无线 ADB 因 App 关闭 Wi-Fi而离线；
- Camera device 0 为 closed，无相机客户端；
- StreamingForegroundService 不存在；
- `video-stream` WakeLock 只有历史记录，不再 running；
- 固件随后自动关机；
- 下次启动原因为 `shutdown,leg_fold_timeout`；
- 折叠前约 67%，30 分钟后重新接 USB 启动约 63%，其中包括关机和重新启动消耗；
- 重启后电池温度约 24 C。

该结果通过“折叠后不保留 Wi-Fi、相机、WebRTC 和持续 WakeLock”的验收。由于固件
自动关机，这 30 分钟实际主要是关机待机，而不是 Android 持续开机待机。

## 10. 构建与安装验证

眼镜 `2.29` 已执行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

结果：

```text
BUILD SUCCESSFUL
81 actionable tasks: 16 executed, 65 up-to-date
```

安装版本：

```text
versionCode=41
versionName=2.29-not-worn-15s-debounce
```

手机当前安装/源码版本：

```text
versionCode=27
versionName=1.26-raw-depth-classification-preview
```

两端当前版本已完成端到端视频验证，手机原画、深度图和分类图三路画面同时正常。

## 11. 当前结论

### 11.1 已通过

- 手机和眼镜通过自定义 BLE、热点 Wi-Fi、TCP 8888 和 WebRTC 完成整合；
- 迁移设备后的 3 轮新 SSID 验证通过；
- 用户接受的 Task 7 进程恢复 MVP 方案通过；
- Task 8 前 9 轮未发生 App 崩溃；
- Task 9 无 ADB 冷启动 `5/5` 通过；
- 手机旋转不再退出实时辅助；
- 深度图和分类图显示从约 4 FPS 提高到目标约 10 FPS；
- 手机三路画面正常；
- 眼镜 `640x360 @ 15 FPS` 稳定；
- BLE 重启时前台服务超时崩溃已修复；
- 拔 USB 后约 5 分钟的 App idle 断流已修复；
- 两轮超过 15 分钟的视频稳定性测试通过；
- 折叠后资源释放和固件自动关机通过。

### 11.2 未完成或仍有风险

- Task 7 仍为 `10/20`，剩余 10 个新 SSID 成功轮次未测；
- Task 8 如果要求正式 10 轮，还差 1 轮；
- 厂商物理广播仍无法从绝对无进程状态直接启动 App；
- 系统蓝牙媒体音频仍可能需要手动进入蓝牙设置或官方 App恢复；
- 2.29 需要补“只摘下、不折叠”约 15 秒停止的独立回归；
- 活动视频功耗仍高，约 280 mA，16-17 分钟损失约 33%-34% 电量；
- 后台 app-op 和 Device Idle 白名单只配置在当前眼镜，尚未写入工程部署脚本；
- 内部电池温度不能代替表面最高温度测量。

## 12. 建议下一步

1. 将手机 `1.26`、眼镜 `2.29` 和本文档保存为新的本地检查点。
2. 把后台 app-op、Device Idle 白名单和核验命令加入一次性眼镜部署脚本。
3. 补测 2.29 的“只摘下、不折叠”15 秒停止与重新佩戴取消场景。
4. 以单变量方式继续优化眼镜活动功耗：
   - 确认实际使用硬件编码器；
   - 优先降低或自适应 WebRTC 码率，再考虑继续降低分辨率；
   - 将 `12 FPS` 作为功耗/流畅度对照组，与当前 15 FPS 比较；
   - 活动会话建立后尝试暂停不必要的 BLE 广播/看门狗工作；
   - 评估条件式或有界 WakeLock，同时防止重新出现 suspend 断流。
5. 每次功耗修改都使用同一套 15 分钟流程，记录电量、电压、温度、电流、Wi-Fi 字节、
   CameraX FPS、WebRTC FPS 和手机模型处理 FPS。
6. 每次修改 WakeLock 或会话状态机后都复测折叠资源释放。
7. 交付前决定是否补齐 Task 7 剩余 10 轮和 Task 8 第 10 轮。

## 13. 交接注意事项

- 当前持续视频修复不仅包含 APK 修改，也包含眼镜系统后台策略。仅复制 APK 到另一副
  眼镜，仍可能重新出现约 5 分钟 App idle 停服。
- 手机原画预览和更高 UI 刷新主要增加手机显示/GPU负载，不是眼镜耗电的直接来源。
- 硬件散热可以改善表面温度和佩戴舒适度，但不会降低电池电流。应先降低相机、编码、
  Wi-Fi 和保持唤醒的有效占空比。
- 本文未包含热点密码或其他可复用认证信息。

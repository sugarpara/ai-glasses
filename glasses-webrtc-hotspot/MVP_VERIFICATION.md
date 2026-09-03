# YOLO26 Depth MVP Verification

## WebRTC/BLE Integration Build

- Integration date: 2026-09-01
- Source: `connected-wifi-reboot/glasses-webrtc-hotspot`
- Result: remote WebRTC input, TCP 8888 signaling, and BLE hotspot provisioning compile in the
  unified depth/ground-filter/HRTF app.
- Debug APK: PASS.
- JVM tests: PASS in an ASCII-only Windows path, including the BLE provisioning payload contract.
- Device end-to-end verification: PASS for hotspot, WebRTC, first frame, depth/audio, and phone reconnect.

The physical glasses remove/wear sensor recovery cycle remains pending; detailed device evidence is
recorded in the phone-to-glasses verification section below.

- Verification date: 2026-08-28
- Device: HONOR REP-AN00
- Android version: 15
- Model: `yolo26n-depth_w8a32.tflite`
- LiteRT accelerator: GPU
- Model input: 640 x 640
- Depth output: 640 x 640
- Average displayed FPS after 60 seconds: approximately 7.0 FPS
- Average displayed inference time after 60 seconds: approximately 32.1 ms
- Five-minute run: PASS
- Background/foreground recovery: PASS
- Lock/unlock recovery: PASS
- Rotation recovery (portrait -> landscape -> portrait): PASS
- Permission settings-return recovery: PASS
- Instrumentation tests: PASS, 3 tests

## GPU Evidence

Logcat reported:

```text
LiteRT accelerator=GPU input=640x640 output=DepthTensorShape(width=640, height=640)
```

The on-screen accelerator label matched Logcat. The final observed screen sample was:

```text
GPU | 7.9 FPS | 26.6 ms
```

## Functional And Stability Results

- The pseudo-color depth image changed continuously as the phone moved.
- The image remained upright in portrait orientation.
- The app did not freeze or crash during the five-minute run.
- FPS and inference time remained visible and valid.
- Observed PSS samples were approximately 607 MB, 708 MB, 822 MB, 775 MB,
  566 MB, 725 MB, and 456 MB. The pattern was consistent with garbage
  collection rather than continuous unbounded growth.
- Graphics memory stabilized at approximately 257 MB.
- Explicit fixed denial of camera permission displayed an understandable
  permission message and an action to grant permission.

## Lifecycle Results

- Pressing Home, waiting 10 seconds, and returning: PASS.
- Locking and unlocking the phone: PASS.
- Opening Settings and returning: PASS.
- Rotating portrait -> landscape -> portrait: PASS.
- The application process remained alive during these checks.

## Automated Verification

The following Gradle verification completed successfully:

```text
clean testDebugUnitTest assembleDebug assembleDebugAndroidTest
connectedDebugAndroidTest
```

The instrumentation run completed all three tests successfully.
This run packaged and installed the permission-resume fix currently in the
working tree. The Android test runner removed its temporary app and test APKs
after completion, as expected.

## Performance Note

The measured average throughput of approximately 7.0 FPS is below the
suggested 10 FPS target. This does not block the functional MVP, but it is the
first optimization target for the next iteration.

## Final Permission-Resume Fix

The screen now rechecks camera permission whenever the Activity resumes. This
addresses returning directly from system settings after granting permission.
The change passes unit tests, APK builds, and all three device instrumentation
tests.

The focused device scenario also passed on the installed Task 9 build:

1. Camera permission was revoked and marked as a fixed denial.
2. The existing Activity displayed the permission explanation and grant action.
3. System app settings were opened while the Activity remained in the back stack.
4. Camera permission was granted while the app was in the background.
5. Returning to the same Activity automatically started the camera and depth
   inference without force-stopping or relaunching the app.

The resumed screen reported GPU inference. Observed samples included
`GPU | 9.5 FPS | 20.8 ms` and `GPU | 10.0 FPS | 21.4 ms`. Camera permission was
confirmed as granted after the test.

## Unified Depth-To-Audio Verification Update

- Verification date: 2026-08-30
- Device: HONOR REP-AN00, Android 15
- Local commit baseline: `385ef48`
- JVM tests: PASS, 67 tests
- Instrumentation tests: PASS, 40 tests, 0 failed, 0 skipped
- Debug APK, AndroidTest APK, Release APK and Release lint vital: PASS

The expanded suite covers metric-depth and ground-filter contracts, Python/C++
golden parity, successful and failed ground fits, full-frame obstacle
classification, 64x64 occupancy, smoothing and hysteresis, immediate-alert
deduplication, latest-only frame processing, native lifecycle, HRTF rendering,
real AudioTrack playback, and visual-to-audio coordination.

Task 15 added two device tests:

1. `DepthCameraLifecycleInstrumentedTest` backgrounds and resumes the real
   `MainActivity`, then rotates landscape and portrait. The live GPU depth page
   recovered after every transition.
2. `Glasses64AudioLifecycleTest` starts a real AudioTrack, calls stop, and
   verifies that the playback callback completes, worker and track references
   are cleared, and the captured track is released.

The permission settings-return scenario was repeated with the unified build.
A fixed CAMERA denial showed the permission page; permission was granted while
the same Activity task was in the background, and returning to that task
automatically restarted camera inference. A real five-second lock/wake cycle
also returned to the same MainActivity and resumed the live page. The observed
post-unlock sample was `GPU | 4.1 FPS | 28.0 ms` with MLE `97.4 ms`. No error
was present in the application PID log.

The complete pipeline remains below the sustained 10 FPS target. Performance
profiling and optimization are intentionally deferred to Task 16.

## Phone-To-Glasses WebRTC Device Verification

- Verification date: 2026-09-01
- Phone: HONOR REP-AN00, Android 15
- Glasses: Rokid RV201 / RG-glasses, Android 12
- Phone app: `1.1-webrtc-glasses` (`versionCode=2`)
- Glasses app: `2.5-home-launcher-no-sleep` (`versionCode=16`)
- Phone hotspot and glasses saved Wi-Fi reconnect: PASS
- TCP signaling on port 8888: PASS
- WebRTC ICE and PeerConnection: PASS (`CONNECTED`, then `COMPLETED`)
- DataChannel: PASS (`OPEN`, bidirectional probe acknowledged)
- Remote first frame: PASS (`640x480`, rotation 270)
- Sustained phone receive rate: approximately 10 FPS
- Glasses CameraX analysis rate: approximately 19-21 FPS after warm-up
- Depth/audio pipeline: PASS; depth inference remained active and AudioTrack output continued
- Phone Home/return reconnect: PASS
- Stale socket cleanup: PASS; old signaling connection entered `TIME_WAIT`, with no `CLOSE-WAIT`
- Physical remove/wear sensor recovery: pending a dedicated user-assisted cycle

The first connection initially retried while the phone app was in system settings and
port 8888 was unavailable. Returning to the real-time assistance screen restarted the
signaling server, after which the glasses connected automatically through the saved
phone hotspot. Backgrounding the phone closed both WebRTC peers cleanly; returning to
the app created a fresh signaling socket, restored ICE/DataChannel state, and resumed
remote video without restarting either device process.

## Task 7 First-Time Hotspot Provisioning Update

- Verification date: 2026-09-02
- Final phone version: `1.18-physical-unknown-recovery-task7 (19)`
- Final glasses version: `2.20-home-ble-recovery-task7 (32)`
- Successful distinct-SSID runs: `10/20`; 10 successful runs remain
- Distinct new SSIDs attempted: 11; all eleven silently reached IPv4
- Silent new-network IPv4 acquisition: PASS for all attempted SSIDs
- System Wi-Fi confirmation UI: none observed
- Wrong password reporting and corrected retry: PASS
- Application password cleanup after successful connection: PASS
- BLE status recovery after a GATT interruption: PASS
- Fold/resume stale physical status cleanup: PASS
- Two consecutive glasses WebRTC factory generations in one process: PASS
- Final phone stop handshake and glasses Wi-Fi shutdown: PASS
- Hotspot-only ICE candidate filtering: PASS
- Native `addIceCandidate()` moved outside the signaling lock: PASS
- Initial-connect stale GATT cache refresh and automatic retry: PASS
- Assistance-page fold/open automatic saved-network video recovery: PASS
- Killed-process recovery from a real vendor physical event: FAIL (`FLAG_RECEIVER_REGISTERED_ONLY`)
- Accepted `START_STICKY` + default HOME process recovery: PASS
- `UNKNOWN` physical-state resource gate and phone instruction: PASS
- Post-recovery provisioning, video, and stop cleanup regression: PASS

Run 3 completed Wi-Fi and video on the glasses but exposed a phone GATT status
gap, leaving the UI on an old provisioning state. The BLE client now reconnects,
resubscribes, and reads the current request status without resending credentials.
The glasses also clear a preserved `GLASSES_FOLDED` or `GLASSES_NOT_WORN` status
before advertising after a new wear cycle.

Run 5 exposed a firmware-specific native `SIGBUS` while WebRTC's Java
`NetworkMonitor` handled a connection-type callback. The glasses now use the
same native interface enumeration strategy already used by the phone hotspot
receiver. Two consecutive saved-network starts then streamed at approximately
10-16 FPS in the same glasses process without another native crash.

The main assistance stop action previously navigated away before sending a BLE
stop command. It now sends `STOP_SESSION`, waits for `STOPPED`, and only then
releases the phone signaling page. The final device run closed glasses WebRTC,
CameraX, and Wi-Fi in approximately 155 ms.

Run 7 initially reached new network ID 8 and signaling, then blocked in native
`addIceCandidate()` while holding the glasses signaling monitor. Teardown waited
for that monitor and the system ANR-killed the process. The final builds filter
ICE to the hotspot socket addresses and perform native candidate insertion
outside synchronized blocks. A fresh replacement SSID, `BOLON-T7-07R`, was
saved as network ID 9 and streamed without an ANR. The phone also recovered a
stale GATT database by refreshing and rediscovering BLE v2 automatically.

In the real assistance page, folding stopped Wi-Fi, camera, WebRTC, and BLE.
Opening and wearing the glasses caused the page to issue a new `START_SESSION`,
reconnect saved network ID 9, and restore video without a user tap. The glasses
process remained PID 6119 throughout.

Three additional migrated-device runs used fresh SSIDs `BOLON-WXC-T7-08`,
`BOLON-WXC-T7-09`, and `BOLON-WXC-T7-10` with network IDs 10-12. All reached
IPv4 in about one second or less, completed ICE/DataChannel/video, and stopped
with Wi-Fi and camera fully released. No crash or ANR occurred. An accidental
old-SSID request before run 9 returned explicit `WIFI_TIMEOUT` and retained BLE
for the corrected request.

The independent killed-process test did not pass. After removing the default
HOME/top-Activity and `START_STICKY` restart effects, the package had no PID or
running service and remained `stopped=false`. A real glasses-open event was
broadcast with flags `0x40000010`; `0x40000000` is
`FLAG_RECEIVER_REGISTERED_ONLY`, so it reached only dynamic receivers and did
not launch the manifest `WearFoldRecoveryReceiver`.

The accepted MVP fallback was then independently verified on versions 1.18 and
2.20. Killing glasses PID 5549 caused Android to recreate PID 5613 in about one
second through `START_STICKY`. The recreated session service restored the
default HOME Activity and BLE advertising, then remained alive for an observed
80 seconds. With physical state `UNKNOWN`, Wi-Fi stayed disabled, camera clients
were empty, no streaming service existed, and session commands were rejected
with `PHYSICAL_STATE_UNKNOWN` while the phone displayed the required
fold/re-wear instruction.

A real `FOLDED + NOT_WORN -> OPEN + WORN` loop cleared the physical error and
returned BLE to `READY` after the three-second confirmation delay without
changing PID 5613. A final end-to-end regression re-saved
`BOLON-WXC-T7-10` as network ID 13, obtained IPv4 `10.217.141.165`, completed
TCP 8888, ICE, DataChannel, CameraX, and 640x480 video at about 10 FPS. The stop
handshake returned `STOPPED`; the glasses disabled Wi-Fi, closed WebRTC,
released the camera, and destroyed the streaming service in about 130 ms.

This result completes the user-approved MVP recovery scope but does not satisfy
the original 20/20 release gate. Ten additional successful new-SSID runs remain
and were deferred by user decision. Direct process launch from the vendor event
remains unsupported; the accepted sticky-service/default-HOME fallback passed.
Detailed evidence is in the glasses repository file
`TASK_07_FIRST_TIME_PROVISIONING_VERIFICATION.md`.

## Task 9 No-ADB Cold Start And Bluetooth Audio Follow-Up

- Verification date: 2026-09-02
- Phone version used for cold-start acceptance: `1.19-phone-session-task8 (20)`
- Formal cold-start result: `5/5` passed
- USB, ADB, Android Studio, and the official App were not used during measured rounds
- All measured rounds reused the same saved hotspot credentials
- Every measured round restored hotspot connectivity and remote video
- The first phone/glasses association and hotspot save was treated as setup, not a measured round

Classic Bluetooth media audio is independent from the custom BLE GATT control link. The
glasses had previously connected to the phone through A2DP/SBC, but did not always restore
that profile after a glasses power cycle. This behavior was also observed before deploying
the custom glasses App, so it is not attributed to the BLE provisioning or WebRTC changes.
Using the Rokid official App restored the existing classic Bluetooth audio pairing and routed
media audio to the glasses.

Phone version `1.20-bluetooth-audio-guard (21)` adds an MVP guard for this firmware/system
limitation. It monitors active A2DP and LE Audio output devices, shows the current audio route
on the home, settings, and real-time assistance screens, and exposes a recovery action when
Bluetooth media audio is absent. The recovery action opens the installed Rokid official App;
if it is unavailable, Android Bluetooth settings are opened instead. Video assistance remains
available when audio is disconnected rather than being blocked.

The new build passed `testDebugUnitTest` and `assembleDebug`, was installed over the existing
phone App without clearing data, and identified the connected glasses media output on-device.
It does not claim to force A2DP reconnection: normal third-party Android apps do not have a
reliable public API for that operation. Fully eliminating the occasional official-App recovery
requires a vendor SDK, privileged/system integration, or a firmware-level reconnect fix.

# Task 7: First-Time Hotspot Provisioning Verification

Date: 2026-09-02

## Builds Under Test

- Glasses package: `com.rokid.glassesbaredevsample`
- Final glasses version: `2.20-home-ble-recovery-task7 (32)`
- Phone package: `com.example.glasses`
- Final phone version: `1.18-physical-unknown-recovery-task7 (19)`
- Test transport: BLE v2 control, Android legacy Wi-Fi configuration, TCP 8888 signaling
- Credential policy: passwords were not recorded in logs or this report

## Implemented Scope

`PROVISION_AND_START` now delegates to the single `GlassesSessionService` owner.
The glasses use `addNetwork()` for a new SSID and `updateNetwork()` when a
previous configuration has the wrong password. The phone opens TCP port 8888
before sending credentials. Only the SSID is retained on the phone, and the
glasses remove the application-private password after Android accepts and
connects the Wi-Fi configuration.

The final BLE and session flow is:

```text
COMMAND_RECEIVED
-> CREDENTIALS_ACCEPTED
-> WIFI_ENABLING
-> WIFI_CONNECTING
-> WIFI_READY
-> PHONE_WAITING
-> SIGNALING_CONNECTED
-> STREAMING
```

Task 7 testing also fixed the following lifecycle and recovery defects found
during repeated runs:

1. A phone GATT disconnect after command transfer now reconnects, resubscribes,
   and reads `SESSION_STATUS` without resending credentials.
2. BLE resume clears a stale `GLASSES_FOLDED` or `GLASSES_NOT_WORN` status
   before advertising again.
3. The glasses WebRTC factory disables the Java `NetworkMonitor` and uses
   native interface enumeration, avoiding a firmware-specific native crash
   during repeated sessions.
4. Both peers filter ICE candidates to the active hotspot signaling addresses.
5. Glasses `addIceCandidate()` calls run outside the signaling-client monitor,
   preventing native candidate insertion from blocking service teardown.
6. Initial phone connections refresh stale GATT caches and retry BLE v2 service
   discovery even before a command has been queued.
7. A restarted session service actively restores the default HOME Activity so
   the firmware does not destroy a background-only foreground service after
   approximately 60 seconds.
8. A restarted process advertises BLE while physical state is `UNKNOWN`, but
   rejects session commands with `PHYSICAL_STATE_UNKNOWN`; Wi-Fi, CameraX, and
   WebRTC remain gated until explicit `OPEN + WORN` events arrive.
9. The phone displays a dedicated physical-state recovery instruction and does
   not misclassify `UNKNOWN` as an ordinary fold or not-worn disconnect.

The phone assistance stop action now sends `STOP_SESSION`, waits for
`STOPPED`, and only then leaves the page and releases local signaling. A
five-second local timeout remains as a UI fallback when the glasses are not
reachable.

## Automated Verification

Both projects passed:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The only build messages were existing Android API deprecation warnings.

## Physical Verification

The user reduced this run from the specified 20 successful distinct-SSID runs
to seven. Eight new SSIDs were attempted: seven completed end to end, while the
original seventh exposed an ICE lock failure and was replaced by a fresh repair
SSID. All eight silently reached IPv4 without system Wi-Fi UI.

### New SSID Runs

| Run | Wi-Fi result | End-to-end result |
| --- | --- | --- |
| `BOLON-T7-01` | `source=new`, network ID 2, IPv4 in about 10.4 s | Video reached about 10.2 FPS; no private password key remained |
| `BOLON-T7-02` | Wrong password produced `WRONG_KEY` and `WIFI_TIMEOUT`; corrected credentials used `source=updated`, network ID 3, IPv4 in about 2.2 s | Corrected run streamed; a later credential-free saved-network start also streamed |
| `BOLON-T7-03` | `source=new`, network ID 4, IPv4 and gateway succeeded | Glasses reached `STREAMING` and the phone received video, but the provisioning UI missed later BLE notifications; GATT status recovery was added |
| `BOLON-T7-04` | `source=new`, network ID 5, IPv4 in about 1.9 s | Request `8f07d149` reached `STREAMING`; phone received about 10-12 FPS; no private password key remained |
| `BOLON-T7-05` | `source=new`, network ID 6, IPv4 in about 1.9 s | Initial stream exposed a native WebRTC crash; after the fix, two consecutive saved-network WebRTC generations streamed in the same glasses process |
| `BOLON-T7-06` | `source=new`, network ID 7, IPv4 in about 2.2 s | Video reached about 10-12 FPS; stop released Wi-Fi and camera |
| `BOLON-T7-07` | `source=new`, network ID 8, IPv4 in about 1.9 s | Signaling connected, then native `addIceCandidate()` blocked and teardown ANR-killed the glasses process |
| `BOLON-T7-07R` | `source=new`, network ID 9, IPv4 in about 2.0 s | Filtered hotspot ICE connected; video reached about 10-12 FPS, then an assistance-page fold/open cycle restored saved Wi-Fi and video without user action |
| `BOLON-WXC-T7-08` | `source=new`, network ID 10, IPv4 in about 1.0 s | ICE reached `CONNECTED/COMPLETED`, DataChannel opened, CameraX/WebRTC streamed, and stop released Wi-Fi and camera in 56 ms |
| `BOLON-WXC-T7-09` | `source=new`, network ID 11, IPv4 in about 0.7 s | An initial old-SSID attempt returned explicit `WIFI_TIMEOUT` without losing BLE; the corrected SSID streamed and stopped cleanly in 109 ms |
| `BOLON-WXC-T7-10` | `source=new`, network ID 12, IPv4 in about 0.9 s | ICE/DataChannel/CameraX/WebRTC passed; phone displayed request `7ac73d83`; stop released Wi-Fi and camera in 142 ms |

### Wrong Password Recovery

The negative test on `BOLON-T7-02` retained BLE long enough to report an
explicit timeout. Correcting the password updated the existing Android network
instead of creating another stale entry. The corrected connection and later
saved-network start both succeeded.

### BLE Status Recovery

Run 3 exposed a GATT disconnect after `WIFI_CONNECTING`. The glasses still
completed Wi-Fi, signaling, and video, but the phone UI remained on an older
status. The phone now retains the active request ID across bounded GATT
recovery, reads the current status after resubscription, and does not resend
`PROVISION_AND_START`.

A separate stale-status race was reproduced after folding: the preserved GATT
database initially returned the previous `GLASSES_NOT_WORN` result. Glasses
version 2.16 and later resets this to `STOPPED` with no error before BLE resume.
The next physical cycle logged:

```text
STOPPED error=none
Cleared stale physical shutdown status before BLE resume
BLE state=READY
```

### Native WebRTC Crash And Fix

The first `BOLON-T7-05` stream attempt obtained Wi-Fi and connected signaling,
then the glasses process terminated with `SIGBUS (BUS_ADRALN)`. The native
backtrace was:

```text
org.webrtc.NetworkMonitor.startMonitoring
-> nativeNotifyConnectionTypeChanged
-> vendor libGLESv2_adreno.so
```

The phone side already used native WebRTC network enumeration for hotspot
interfaces. Applying the same `disableNetworkMonitor=true` factory option to
the glasses removed this firmware callback path. Two consecutive WebRTC
factory generations then completed in the same glasses process, both using the
saved `BOLON-T7-05` configuration. The phone observed roughly 10-16 FPS, the
glasses process remained alive, and the crash buffer received no new entry.

### ICE Lock, GATT Cache, And Fold Recovery

The original `BOLON-T7-07` run blocked in native
`PeerConnection.addIceCandidate()` while holding the
`WebRtcSignalingClient` monitor. Service teardown waited for the same monitor,
caused an ANR, and the system killed the glasses process.

The repair build filters candidates to the active hotspot signaling addresses
and invokes `addIceCandidate()` outside synchronized blocks. On
`BOLON-T7-07R`, the phone sent only `192.168.132.132`; ICE reached
`CONNECTED/COMPLETED`, and no ANR or process restart occurred.

The first repair connection also found a stale phone GATT database. Phone
version 1.17 refreshed the cache, automatically reconnected, discovered BLE v2,
and subscribed without another user tap. The final assistance-page test folded
and reopened the glasses. Wi-Fi, camera, WebRTC, and BLE stopped on fold; after
wear confirmation the page sent a new `START_SESSION`, reconnected saved
network ID 9, and restored video in the same glasses process.

### Stop Handshake

Testing showed the old phone stop button only navigated away, leaving the
glasses Wi-Fi enabled and WebRTC retrying port 8888. The final phone build now
waits for the BLE stop acknowledgment before navigation. Request `14068711`
produced:

```text
COMMAND_RECEIVED -> STOPPED
```

The glasses closed WebRTC, disabled Wi-Fi, and destroyed the streaming service
in about 155 ms. Camera clients were empty, the glasses process remained alive,
and BLE returned to `READY` because the glasses were still open and worn.

### Additional SSID Stability Runs

Three migrated-device runs added `BOLON-WXC-T7-08`, `BOLON-WXC-T7-09`, and
`BOLON-WXC-T7-10`. All three used new Android network IDs, obtained IPv4 in
about one second or less, reached ICE `CONNECTED/COMPLETED`, opened the
DataChannel, and displayed glasses video on the phone. Each stop handshake
disabled Wi-Fi, released CameraX and removed the streaming service while the
same glasses process remained alive. No crash or ANR occurred.

The accidental old-SSID attempt before run 9 timed out explicitly after about
45 seconds and left BLE usable for the corrected request. It is a negative
recovery observation and is not counted as a successful distinct-SSID run.

### Killed-Process Physical Recovery

Independent physical-event recovery failed on the current glasses firmware.
The first process kill was invalid as evidence because the app is the default
HOME: Android immediately recreated the process for the top Activity. A
controlled retry placed the system Settings homepage in front, killed the app
without `force-stop`, and waited until both the sticky-service restart and its
temporary process were removed. The package remained installed, enabled and
`stopped=false`, with no app PID or running service.

Opening and wearing the glasses then generated the real vendor physical
broadcast, but the app remained stopped. `dumpsys activity broadcasts` showed:

```text
act=com.rokid.sprite.ACTION_LEG_STATUS_CHANGED flg=0x40000010
Deliver 0 #0: system dynamic BroadcastFilter
```

Flag `0x40000000` is `Intent.FLAG_RECEIVER_REGISTERED_ONLY`. Therefore the
firmware delivers this hardware event only to receivers registered by a
currently running process; the manifest-declared `WearFoldRecoveryReceiver`
cannot launch a killed process. The static receiver is present and enabled in
the installed package, so this is a transport limitation rather than a missing
manifest entry. After manually restoring the app, a complete fold/open/wear
cycle returned BLE to `READY`; Wi-Fi and camera remained off.

### Accepted MVP Sticky/HOME Recovery

Because the vendor event cannot start a process, the accepted MVP recovery
uses Android's existing `START_STICKY` service restart and the app's default
HOME role. Glasses version 2.20 requests the HOME Activity whenever the session
service is recreated. The process initially starts with independent
`wear=UNKNOWN` and `leg=UNKNOWN`, exposes BLE for recovery, and blocks all
session resources until a real physical cycle establishes `OPEN + WORN`.

The migrated-device verification killed glasses PID 5549. Android recreated
the app as PID 5613 in approximately one second, the session service requested
the HOME Activity, and the Activity became `topResumedActivity`. BLE returned
to `READY`. During an 80-second observation window the process and services
remained alive while Wi-Fi was disabled, camera clients were empty, no
streaming service existed, and no TCP 8888 session was active.

The user then completed a real physical loop. Logs showed:

```text
FOLDED + NOT_WORN
-> OPEN + WORN
-> BLE start scheduled confirmationMs=3000
-> BLE READY
```

PID 5613 remained unchanged. After physical recovery, a complete
`PROVISION_AND_START` regression re-saved `BOLON-WXC-T7-10` as Android network
ID 13, obtained IPv4 `10.217.141.165`, connected TCP 8888, reached ICE
`CONNECTED/COMPLETED`, opened the DataChannel, and delivered 640x480 video at
approximately 9.9-10.0 FPS. The stop request returned `STOPPED`; Wi-Fi shutdown
was requested and the session owner completed in about 130 ms. WebRTC closed,
the streaming service was destroyed, and the camera client list became empty.

This passes the user-approved MVP recovery requirement. It does not change the
firmware finding above: a real `FLAG_RECEIVER_REGISTERED_ONLY` event still
cannot launch the app from a fully absent, non-sticky, non-HOME process state.

## Credential Verification

- No hotspot password was printed by the phone or glasses logs used here.
- The phone retained only the SSID.
- Successful glasses connections removed the application-private password key.
- Android retained the network configuration so later `START_SESSION` commands
  required no application password.

## Result And Remaining Gate

Task 7 implementation and the user-approved MVP recovery path are complete for
the tested scope. Physical validation remains `10/20` successful end-to-end
distinct-SSID runs. Eleven distinct new SSIDs reached IPv4 without system UI;
one was the failed ICE/ANR discovery run and its fresh replacement passed after
the repair. Re-saving `BOLON-WXC-T7-10` after reinstall is a recovery regression
and does not increment the distinct-SSID count.

The original 20/20 release gate is not satisfied because 10 additional
successful new-SSID runs remain and were deferred by user decision. Direct
process launch by the vendor physical event remains impossible on this
firmware, while the accepted `START_STICKY` + default HOME + physical-state
confirmation MVP fallback passed its independent kill, recovery, stream, and
cleanup verification.

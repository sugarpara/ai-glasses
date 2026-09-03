# Task 01: Wear/Fold Broadcast Verification

Date: 2026-09-01

Device: BOLON / Rokid RG glasses, Android 12

## Scope

Capture the vendor wear and temple-fold broadcasts as raw values without assigning
meaning in production code before real-device verification.

The installed main application was left unchanged because the glasses currently run
`versionCode=17`, `versionName=2.6-video-20fps`, while this repository currently
declares `versionCode=16`. A separate `wear-fold-capture` diagnostic APK was used to
avoid replacing unmerged 20 FPS and fixed-resolution changes on the device.

## Verified Mapping

| Action | Extra | Raw type | Raw value | Verified meaning |
| --- | --- | --- | --- | --- |
| `com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED` | `glasses_take_state` | `String` | `0` | Not worn |
| `com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED` | `glasses_take_state` | `String` | `1` | Worn |
| `com.rokid.sprite.ACTION_LEG_STATUS_CHANGED` | `glasses_leg_state` | `String` | `0` | Folded |
| `com.rokid.sprite.ACTION_LEG_STATUS_CHANGED` | `glasses_leg_state` | `String` | `1` | Open |

## Sample Result

The final controlled sample contained at least:

- 10 `TAKE=0` transitions;
- 10 `TAKE=1` transitions;
- 10 `LEG=0` transitions;
- 10 `LEG=1` transitions.

No value drift was observed. Each physical transition generated one corresponding
broadcast during the controlled cycles.

## Process Restart Result

Starting the receiver while the glasses were already open and worn produced no
initial `TAKE` or `LEG` event during a five-second observation window.

Production implication:

- initialize both dimensions as `UNKNOWN` after process start;
- do not assume `PowerManager.isInteractive` means open or worn;
- update state only after a verified vendor event or a future vendor state-query API;
- do not treat a persisted pre-restart value as current physical truth.

## Boot Result

The firmware logged the following behavior:

- `LOCKED_BOOT_COMPLETED` was rejected for third-party applications with
  `Background execution Third-Party APP not allowed`;
- the installed main application later received ordinary `BOOT_COMPLETED` and
  launched its recovery activity;
- the separate diagnostic package did not receive either boot event;
- manually starting capture after boot and waiting five seconds produced no delayed
  initial `TAKE` or `LEG` event.

Production implication:

- do not rely on `LOCKED_BOOT_COMPLETED` as the only startup path;
- keep the default-HOME/activity startup path until a privileged vendor deployment
  path is available;
- the session service must tolerate an unknown physical state after boot and wait
  for the first real transition.

## Diagnostic Module

The `wear-fold-capture` module is isolated from the production application. It:

- registers dynamic receivers for the two vendor actions;
- logs action, expected extra, raw type, raw value and timestamps;
- contains an ADB-launchable no-display activity;
- contains a boot receiver used to verify the firmware restriction;
- does not request camera, Bluetooth, location or Wi-Fi permissions;
- does not modify Wi-Fi, BLE, CameraX or WebRTC state.

The temporary device-idle whitelist used during collection was removed after the
test, and the diagnostic service was force-stopped. The main application remained
at `17 / 2.6-video-20fps`.

## Task 02 Input

The unified session state must represent physical state as two independent values:

```text
wearState = UNKNOWN | NOT_WORN | WORN
legState  = UNKNOWN | FOLDED | OPEN
```

Only these verified mappings may update them:

```text
TAKE "0" -> NOT_WORN
TAKE "1" -> WORN
LEG  "0" -> FOLDED
LEG  "1" -> OPEN
```

Unknown, missing or future values must be logged and ignored rather than guessed.

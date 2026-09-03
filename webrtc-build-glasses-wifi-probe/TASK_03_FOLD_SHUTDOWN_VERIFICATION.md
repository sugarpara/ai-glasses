# Task 3: Fold Shutdown Verification

Date: 2026-09-01

## Build Under Test

- Package: `com.rokid.glassesbaredevsample`
- Version code: `20`
- Version name: `2.8-fold-shutdown-task3`
- Phone hotspot remained enabled during the active-session fold test

## Implemented Scope

`glasses_leg_state="0"` now has the highest local priority. The session service:

1. clears the desired and pending session;
2. closes and invalidates the Wi-Fi provisioning callback;
3. cancels boot recovery alarms;
4. stops `StreamingForegroundService`;
5. stops `BleProvisioningService`;
6. disconnects Wi-Fi and requests `setWifiEnabled(false)`;
7. leaves only `GlassesSessionService` running to receive future physical events.

Additional race fixes:

- closed Wi-Fi probes ignore queued connectivity callbacks;
- BLE destruction removes every queued retry and releases its launch WakeLock;
- CameraX asynchronous provider callbacks cannot bind after stream shutdown;
- ordinary Activity create/resume only ensures the session service and does not
  restore a cleared connection request.

## Automated Verification

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
git diff --check
```

Tests cover folding an active session into `STOPPING`, clearing
`desiredSession`, disabling camera ownership, and ensuring reopening does not
restore the old session intent.

## Physical-State Calibration

The installed build received the real sequence:

```text
NOT_WORN -> FOLDED -> OPEN -> WORN
```

During the inactive fold, shutdown completed in about 30 ms. Reopening and
wearing did not enable Wi-Fi.

## Active-Session Fold

Before folding:

- glasses Wi-Fi was connected to the saved phone hotspot;
- `StreamingForegroundService` was foreground;
- CameraX was bound by this package;
- the video Partial WakeLock existed.

Observed timeline:

```text
16:49:55.197  LEG_FOLDED received
16:49:55.312  Wi-Fi disconnect/disable accepted
16:49:55.320  session shutdown returned, durationMs=117
16:49:55.409  StreamingForegroundService destroyed
```

Post-fold state:

- Wi-Fi setting: `0`, status: disabled;
- active camera clients: empty;
- camera event log records this package disconnecting at 16:49:55;
- video and BLE application WakeLocks absent;
- streaming and BLE services absent;
- only `GlassesSessionService` remained active;
- no `boot-retry` alarm was present.

The phone hotspot remained enabled. After reopening and wearing the glasses,
Wi-Fi stayed disabled and neither CameraX nor the streaming service restarted.
This verifies that queued callbacks, Activity resume and old connection intent
do not revive the folded session.

## BLE Validation Boundary

Task 3 implements BLE advertising, GATT, retry and WakeLock cleanup. The BLE
service is not normally started until Task 4, and the firmware does not allow an
ADB shell UID to start the non-exported service. The full
`BLE_READY -> FOLDED` device test is therefore part of Task 4 acceptance.

## Result

Task 3 passes the active-session fold acceptance for Wi-Fi, CameraX, WebRTC,
retry cancellation and WakeLock release. The measured shutdown is comfortably
inside the MVP limits of two seconds for video and five seconds for Wi-Fi.

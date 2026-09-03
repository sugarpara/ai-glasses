# Task 5: BLE v2 Protocol Verification

Date: 2026-09-01

## Builds Under Test

- Glasses package: `com.rokid.glassesbaredevsample`
- Glasses version: `2.10-ble-v2-task5 (22)`
- Phone package: `com.example.glasses`
- Phone version: `1.2-ble-v2-task5 (3)`
- Glasses serial: `2001092527005029`
- Phone serial: `AXVVUT3814008312`
- Phone hotspot remained disabled throughout Task 5

## Implemented Scope

The existing service UUID and v1 credentials characteristic remain compatible.
BLE v2 adds:

```text
DEVICE_INFO      76b45a10-8e2f-4e8a-9b6a-5d53e76f0102
SESSION_COMMAND  76b45a10-8e2f-4e8a-9b6a-5d53e76f0103
SESSION_STATUS   76b45a10-8e2f-4e8a-9b6a-5d53e76f0104
```

The phone now uses the ordered flow:

```text
scan -> connect -> discover -> MTU -> DEVICE_INFO -> CCCD -> SESSION_STATUS
```

Commands are rejected until status notifications are enabled. Every command
uses a request ID, GATT write completion means only that transport succeeded,
and the user-visible result comes from a status read or notification.

Supported Task 5 commands are `PROVISION_AND_START`, `START_SESSION`,
`STOP_SESSION`, and `HEARTBEAT`. Task 5 deliberately stops
`PROVISION_AND_START` at `CREDENTIALS_ACCEPTED`; Wi-Fi startup belongs to Tasks
6 and 7.

Physical shutdown sends a final `STOPPED` status with
`GLASSES_FOLDED` or `GLASSES_NOT_WORN`, keeps GATT alive for 250 ms so the
notification can leave the glasses, and then destroys the BLE service. The
phone publishes the terminal status, clears its connected UI state, and closes
the current GATT after 300 ms even if Android does not promptly report a remote
disconnect.

## Automated Verification

Both projects passed:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Coverage includes v2 command/status encoding and decoding, request IDs,
protocol errors, and the retained v1 credentials payload.

## Physical Verification

### Discovery And Subscription

Stable `OPEN + WORN` reached BLE `READY` after the existing three-second wear
confirmation while Wi-Fi remained disabled. The phone discovered the expected
service, connected, enabled the status CCCD, read device information, and read
the initial state:

```text
BLE v2 - glasses 2.10-ble-v2-task5 (22)
STOPPED
```

This completes the phone-side discovery item left open by Task 4.

### Request ID And Command Status

`STOP_SESSION` produced the same request ID for both notifications:

```text
COMMAND_RECEIVED -> STOPPED
```

The session cleanup took 24 ms, BLE remained connected, and Wi-Fi stayed off.

A test `PROVISION_AND_START` command produced:

```text
COMMAND_RECEIVED -> CREDENTIALS_ACCEPTED
```

The phone displayed that the hotspot parameters were accepted but networking
had not started. The glasses did not enable Wi-Fi, start CameraX or video, or
save the Task 5 test credentials.

### Connected Fold Shutdown

The final connected fold produced:

```text
18:14:57.077  FOLDED received
18:14:57.099  session stopped, 17 ms
18:14:57.102  NOT_WORN received
18:14:57.111  second idempotent stop completed, 7 ms
18:14:57.112  STOPPED + GLASSES_FOLDED published
18:14:57.117  STOPPED + GLASSES_NOT_WORN published
18:14:57.371  BLE service destroyed
18:14:57.383  BLE state OFF
```

The phone received both terminal notifications and logged:

```text
Closing BLE after physical shutdown status
```

Its UI returned to the disconnected `Connect glasses` action and retained the
physical reason instead of showing stale device information or session
controls.

Post-fold checks found:

- Wi-Fi disabled;
- active camera clients empty;
- streaming and BLE services absent;
- no BLE or video application WakeLock;
- boot recovery retries cancelled;
- only `GlassesSessionService` remained active.

## Deployment Observation

After an ADB package replacement, the firmware did not start the new package
until its Activity was launched once. The session service also correctly began
with independent `UNKNOWN` wear and leg states, so a complete physical cycle was
needed before BLE eligibility could be known. Product cold-start and first-run
activation remain Task 9 work and are not claimed by Task 5.

## Result

Task 5 passes. The phone can discover and subscribe to BLE v2, correlate
commands and statuses by request ID, distinguish transport completion from
protocol success, preserve v1 payload compatibility, and reliably clear stale
connection state when the glasses are removed or folded.

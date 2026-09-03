# Task 4: BLE Ready Verification

Date: 2026-09-01

## Build Under Test

- Package: `com.rokid.glassesbaredevsample`
- Version code: `21`
- Version name: `2.9-ble-ready-task4`
- Glasses serial: `2001092527005029`
- Phone hotspot remained disabled

## Implemented Scope

`GlassesSessionService` is the sole owner of BLE lifecycle decisions:

```text
OPEN + stable WORN for 3 seconds
-> STARTING
-> GATT service registered
-> low-latency advertising
-> READY

NOT_WORN or FOLDED
-> cancel pending wear confirmation
-> stop BLE service
-> close advertiser and GATT
-> cancel watchdog and retries
-> release BLE WakeLock
-> OFF
```

The three-second confirmation window was added after the real sensor briefly
reported `WORN` for about 2.9 seconds while the glasses were being opened but
not worn. This prevents the false-positive interval from starting BLE while
keeping the total ready time below five seconds.

The BLE executor now reports `OFF`, `STARTING`, `READY`, `CONNECTED`, and
`ERROR` to the unified session StateFlow. Screen-on and user-present broadcasts
no longer start BLE or restore saved Wi-Fi state.

The advertising watchdog is scheduled only after advertising reports success.
It is cancelled before every restart, on client connection, when Bluetooth is
turned off, and when the service is destroyed.

## Automated Verification

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
git diff --check
```

The build and unit tests passed. State tests cover initial BLE state, the
`OPEN + WORN` eligibility rule, and immediate BLE/session intent clearing for
both `NOT_WORN` and `FOLDED`.

## Physical Verification

### Open But Not Worn

After `LEG_OPEN`, the glasses remained `NOT_WORN` for more than six seconds:

- `BleProvisioningService` was absent;
- Wi-Fi setting remained `0`;
- active camera clients were empty;
- no BLE application WakeLock or retry was present.

### Stable Wear To BLE Ready

Observed timeline:

```text
17:20:37.964  WORN received while leg=OPEN
17:20:37.971  three-second confirmation scheduled
17:20:40.981  BLE service start requested
17:20:41.033  GATT provisioning service registered
17:20:41.054  advertising success, state=READY
```

Measured results:

- `WORN -> READY`: 3,090 ms;
- confirmed service start -> `READY`: 73 ms;
- advertised service UUID: `76b45a10-8e2f-4e8a-9b6a-5d53e76f0100`.

### Bluetooth Adapter Recovery

While the glasses remained open and worn, ADB disabled and re-enabled the
Bluetooth adapter.

```text
17:21:29.236  adapter OFF; advertiser/GATT stopped; state=ERROR
17:21:55.477  adapter ON
17:21:55.492  GATT service registered
17:21:55.514  advertising restored; state=READY
```

Adapter `ON -> READY` took 37 ms. No application or service restart and no new
wear event was required.

### BLE Ready To Folded

Observed timeline:

```text
17:22:36.842  NOT_WORN received
17:22:36.869  session stop completed
17:22:36.875  BLE service destroyed
17:22:36.882  state=OFF
17:22:39.566  FOLDED received
17:22:39.591  folded stop completed
```

After waiting longer than one watchdog interval:

- only `GlassesSessionService` remained;
- no advertising restart or GATT retry occurred;
- Wi-Fi remained disabled;
- active camera clients were empty;
- BLE and video application WakeLocks were absent.

## Phone Discovery Boundary

The glasses-side Android stack reported successful low-latency advertising
with the expected UUID. The current phone UI does not expose a scan-only action:
its existing button scans, connects, and immediately writes hotspot credentials.
A strict phone-side discovery observation is therefore still pending and should
be performed either with a scan-only diagnostic or as the first acceptance step
of Task 5. This avoids enabling Wi-Fi/video merely to prove Task 4 advertising.

## Result

The glasses-side Task 4 lifecycle passes: stable wear reaches `BLE_READY` in
under five seconds, adapter recovery rebuilds GATT automatically, and removing
or folding the glasses removes all BLE runtime work. The remaining acceptance
item is an independent phone-side scan observation.

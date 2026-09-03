# Task 2: Unified Glasses Session Service Verification

Date: 2026-09-01

## Build Under Test

- Package: `com.rokid.glassesbaredevsample`
- Version code: `19`
- Version name: `2.7-session-service-task2-r2`
- Device: Rokid glasses, serial recorded in the private deployment environment
- Phone hotspot: disabled during this verification

## Implemented Scope

- Added one authoritative `GlassesSessionService`.
- Added one `StateFlow<GlassesSessionState>` for wear, leg and connection state.
- Moved Wi-Fi observation/provisioning and stream startup out of `MainActivity`.
- Kept `StreamingForegroundService` as the only CameraX/WebRTC owner.
- Disabled `ConnectionRecoveryService` in the manifest.
- Initialized wear and leg independently as `UNKNOWN`.
- Deferred connection startup until both `leg=OPEN` and `wear=WORN` have been observed.
- Declared the session service as a `connectedDevice` foreground service.

## Automated Verification

The following commands completed successfully:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
git diff --check
```

Unit coverage verifies:

- initial physical state is unknown and not ready;
- verified string broadcast values map to the expected states;
- unknown values are ignored;
- readiness requires independent `OPEN` and `WORN` events.

## Device Verification

Observed after a fresh install:

```text
Service created ... wear=UNKNOWN leg=UNKNOWN
Connection deferred ... wear=UNKNOWN leg=UNKNOWN
```

No current-state replay occurred when the service registered after the glasses
were already open and worn. Wi-Fi remained disabled and neither the legacy
recovery service nor the streaming service was running.

With the application configured as the default HOME, the session service stayed
foreground for more than six minutes and received physical events while the
connection request remained deferred:

```text
glasses_take_state="0" -> wear=NOT_WORN leg=UNKNOWN
glasses_leg_state="0"  -> wear=NOT_WORN leg=FOLDED
glasses_leg_state="1"  -> wear=NOT_WORN leg=OPEN
```

Wi-Fi remained disabled through the above sequence. The app did not own an open
camera. The camera client visible in `dumpsys media.camera` belonged to the
vendor assist service, not this package.

## Firmware Limitation

When the vendor Launcher is the default HOME and this application's Activity is
sent to the background, the firmware removes the service's foreground status
and stops it after about three minutes:

```text
Service.startForeground() not allowed due to bg restriction
Stopping service due to app idle
```

Adding the correct foreground service type does not remove that vendor policy.
For this development MVP, keeping this application as the default HOME is a
required deployment condition. `GlassesSessionService` remains the sole owner of
physical and connection state; the HOME Activity is only a process-lifetime
anchor.

Removing the HOME dependency for production requires a vendor whitelist,
privileged/system installation, Device Owner policy, or another firmware-
supported background execution exemption.

## Result

Task 2 is complete for the development MVP with the documented default-HOME
requirement. Task 3 must implement immediate local stream teardown, Wi-Fi
disconnect and Wi-Fi disable on `FOLDED`; Task 2 intentionally does not claim
that behavior.

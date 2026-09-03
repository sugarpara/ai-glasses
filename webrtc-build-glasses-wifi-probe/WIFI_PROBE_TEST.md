# Saved Wi-Fi recovery test

Version `2.0-saved-wifi-recovery` uses the Wi-Fi network already saved by
Android. The normal path does not read or send an SSID/password through the
custom BLE service.

The intended network topology is:

```text
phone hotspot (gateway and WebRTC receiver)
  -> glasses join the saved hotspot
  -> glasses discover the IPv4 gateway
  -> glasses wait for TCP port 8888
  -> CameraX and WebRTC start
```

## One-time preparation

1. Use the Rokid app once to connect the glasses to the phone hotspot.
2. Keep the hotspot name and password unchanged so Android can reconnect.
3. Install the glasses build once from Android Studio.

## Normal test

1. Enable the phone hotspot.
2. Open the phone app and wait for `Listening on port 8888`.
3. Put on or wake the glasses. Do not send BLE credentials.

Read the glasses logs:

```powershell
adb -s 2001092527005029 logcat -v time `
  "GlassesRecovery:D" "GlassesStream:D" "WifiProbe:D" "GlassesMain:D" `
  "GlassesWebRTC:D" "BareCameraBind:D" "BareFrame:I" "*:S"
```

Read the phone logs:

```powershell
adb -s 10AF9Y24NK002M3 logcat -v time `
  "StreamSignal:D" "PhoneWebRTC:D" "PhoneVideo:D" "*:S"
```

Expected glasses sequence:

```text
Connection recovery service created
Phone signaling server ready host=...; starting stream
Streaming foreground service created
Starting headless CameraX and WebRTC host=...
Headless CameraX bound
PeerConnectionFactory and local video track initialized
Signaling connected to ...:8888
First WebRTC frame submitted ...
```

Expected phone sequence:

```text
Listening on port 8888
Glasses connected: ...
Connection state=CONNECTED
Remote video first frame ...
Remote video FPS=...
```

## Sleep and recovery test

1. Keep the phone app and hotspot running.
2. Remove the glasses and wait for them to sleep.
3. Put the glasses on again.

CameraX is released when the glasses go to sleep. On wake, the recovery service
checks the saved Wi-Fi and phone port, then restarts the headless streaming
service. A visible Activity is not required on the normal path.

## BLE fallback

If no usable saved IPv4 Wi-Fi route exists for 30 seconds, the glasses start
the custom BLE provisioning service. This is a fallback for a new hotspot, not
a required step during normal reconnection.

The default gateway discovery assumes the receiving phone is the hotspot. If
both devices join a third-party router, the gateway is the router rather than
the receiving phone, so automatic phone discovery needs a separate mechanism.

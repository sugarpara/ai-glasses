package com.example.glasses.ble

import org.json.JSONObject
import java.util.UUID

internal object BleSessionUuids {
    val SERVICE: UUID = UUID.fromString("76b45a10-8e2f-4e8a-9b6a-5d53e76f0100")
    val V1_CREDENTIALS: UUID = UUID.fromString("76b45a10-8e2f-4e8a-9b6a-5d53e76f0101")
    val DEVICE_INFO: UUID = UUID.fromString("76b45a10-8e2f-4e8a-9b6a-5d53e76f0102")
    val SESSION_COMMAND: UUID = UUID.fromString("76b45a10-8e2f-4e8a-9b6a-5d53e76f0103")
    val SESSION_STATUS: UUID = UUID.fromString("76b45a10-8e2f-4e8a-9b6a-5d53e76f0104")
    val CLIENT_CHARACTERISTIC_CONFIG: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

enum class BleSessionCommandType {
    PROVISION_AND_START,
    START_SESSION,
    STOP_SESSION,
    HEARTBEAT,
}

enum class BleProtocolState {
    COMMAND_RECEIVED,
    CREDENTIALS_ACCEPTED,
    WIFI_ENABLING,
    WIFI_CONNECTING,
    WIFI_READY,
    PHONE_WAITING,
    SIGNALING_CONNECTED,
    STREAMING,
    STOPPED,
}

enum class BleProtocolError {
    INVALID_COMMAND,
    INVALID_CREDENTIALS,
    PERMISSION_MISSING,
    GLASSES_FOLDED,
    GLASSES_NOT_WORN,
    PHYSICAL_STATE_UNKNOWN,
    WIFI_ENABLE_REJECTED,
    WIFI_CONFIGURATION_REJECTED,
    WIFI_AUTH_FAILED,
    WIFI_TIMEOUT,
    NO_IPV4_GATEWAY,
    PHONE_SIGNALING_UNREACHABLE,
    CAMERA_START_FAILED,
    WEBRTC_FAILED,
}

data class BleDeviceInfo(
    val protocolVersion: Int,
    val appVersion: String,
    val versionCode: Int,
    val capabilities: Set<String>,
)

data class BleSessionStatus(
    val requestId: String?,
    val state: BleProtocolState,
    val error: BleProtocolError?,
)

internal object BleSessionProtocol {
    const val VERSION = 2

    fun encodeCommand(
        type: BleSessionCommandType,
        requestId: String,
        ssid: String? = null,
        password: String? = null,
    ): ByteArray {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid requestId" }
        val json = JSONObject()
            .put("version", VERSION)
            .put("requestId", requestId)
            .put("command", type.name)
        if (type == BleSessionCommandType.PROVISION_AND_START) {
            require(!ssid.isNullOrBlank()) { "SSID must not be blank" }
            require(password != null && password.length in 8..63) {
                "WPA2 password must contain 8 to 63 characters"
            }
            json.put("ssid", ssid)
                .put("password", password)
                .put("security", "WPA2")
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeDeviceInfo(payload: ByteArray): BleDeviceInfo {
        val json = JSONObject(payload.toString(Charsets.UTF_8))
        val capabilitiesJson = json.getJSONArray("capabilities")
        val capabilities = buildSet {
            for (index in 0 until capabilitiesJson.length()) {
                add(capabilitiesJson.getString(index))
            }
        }
        return BleDeviceInfo(
            protocolVersion = json.getInt("version"),
            appVersion = json.getString("appVersion"),
            versionCode = json.getInt("versionCode"),
            capabilities = capabilities,
        )
    }

    fun decodeStatus(payload: ByteArray): BleSessionStatus {
        val json = JSONObject(payload.toString(Charsets.UTF_8))
        require(json.getInt("version") == VERSION) { "Unsupported BLE protocol version" }
        return BleSessionStatus(
            requestId = if (json.isNull("requestId")) null else json.getString("requestId"),
            state = BleProtocolState.valueOf(json.getString("state")),
            error = if (json.isNull("error")) {
                null
            } else {
                BleProtocolError.valueOf(json.getString("error"))
            },
        )
    }
}

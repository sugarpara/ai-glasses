package com.rokid.glassesbaredevsample.provisioning

import org.json.JSONArray
import org.json.JSONObject

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

data class BleSessionCommand(
    val requestId: String,
    val type: BleSessionCommandType,
    val ssid: String? = null,
    val password: String? = null,
    val security: String? = null,
)

data class BleSessionStatus(
    val requestId: String?,
    val state: BleProtocolState,
    val error: BleProtocolError? = null,
)

sealed interface BleCommandDecodeResult {
    data class Success(val command: BleSessionCommand) : BleCommandDecodeResult
    data class Failure(
        val requestId: String?,
        val error: BleProtocolError,
    ) : BleCommandDecodeResult
}

object BleSessionProtocol {
    const val VERSION = 2
    private val requestIdPattern = Regex("[A-Za-z0-9_-]{1,64}")

    fun decodeCommand(payload: ByteArray): BleCommandDecodeResult {
        var requestId: String? = null
        return runCatching {
            val json = JSONObject(payload.toString(Charsets.UTF_8))
            requestId = json.optString("requestId", "").takeIf { it.isNotBlank() }
            if (json.optInt("version", -1) != VERSION ||
                requestId == null ||
                !requestIdPattern.matches(requestId!!)
            ) {
                return BleCommandDecodeResult.Failure(requestId, BleProtocolError.INVALID_COMMAND)
            }
            val type = runCatching {
                BleSessionCommandType.valueOf(json.getString("command"))
            }.getOrElse {
                return BleCommandDecodeResult.Failure(requestId, BleProtocolError.INVALID_COMMAND)
            }
            if (type == BleSessionCommandType.PROVISION_AND_START) {
                val ssid = json.optString("ssid", "")
                val password = json.optString("password", "")
                val security = json.optString("security", "")
                if (ssid.isBlank() || password.length !in 8..63 || security != "WPA2") {
                    return BleCommandDecodeResult.Failure(
                        requestId,
                        BleProtocolError.INVALID_CREDENTIALS,
                    )
                }
                BleCommandDecodeResult.Success(
                    BleSessionCommand(requestId!!, type, ssid, password, security),
                )
            } else {
                BleCommandDecodeResult.Success(BleSessionCommand(requestId!!, type))
            }
        }.getOrElse {
            BleCommandDecodeResult.Failure(requestId, BleProtocolError.INVALID_COMMAND)
        }
    }

    fun encodeStatus(status: BleSessionStatus): ByteArray = JSONObject()
        .put("version", VERSION)
        .put("requestId", status.requestId ?: JSONObject.NULL)
        .put("state", status.state.name)
        .put("error", status.error?.name ?: JSONObject.NULL)
        .toString()
        .toByteArray(Charsets.UTF_8)

    fun encodeDeviceInfo(appVersion: String, versionCode: Int): ByteArray {
        val capabilities = JSONArray()
            .put(BleSessionCommandType.START_SESSION.name)
            .put(BleSessionCommandType.STOP_SESSION.name)
            .put(BleSessionCommandType.PROVISION_AND_START.name)
            .put(BleSessionCommandType.HEARTBEAT.name)
            .put("STATUS_NOTIFY")
            .put("V1_CREDENTIALS")
        return JSONObject()
            .put("version", VERSION)
            .put("appVersion", appVersion)
            .put("versionCode", versionCode)
            .put("capabilities", capabilities)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }
}

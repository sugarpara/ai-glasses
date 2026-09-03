package com.rokid.glassesbaredevsample.provisioning

import android.content.Context
import java.util.UUID

object BleProvisioningContract {
    val SERVICE_UUID: UUID = UUID.fromString("76b45a10-8e2f-4e8a-9b6a-5d53e76f0100")
    val CREDENTIALS_UUID: UUID = UUID.fromString("76b45a10-8e2f-4e8a-9b6a-5d53e76f0101")
    val DEVICE_INFO_UUID: UUID = UUID.fromString("76b45a10-8e2f-4e8a-9b6a-5d53e76f0102")
    val SESSION_COMMAND_UUID: UUID = UUID.fromString("76b45a10-8e2f-4e8a-9b6a-5d53e76f0103")
    val SESSION_STATUS_UUID: UUID = UUID.fromString("76b45a10-8e2f-4e8a-9b6a-5d53e76f0104")
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val EXTRA_WIFI_SSID = "wifi_ssid"
    const val EXTRA_WIFI_PASSWORD = "wifi_password"

    private const val PREFS = "ble_provisioning"
    private const val KEY_SSID = "ssid"
    private const val KEY_PASSWORD = "password"

    data class Credentials(val ssid: String, val password: String)

    fun saveCredentials(context: Context, credentials: Credentials) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SSID, credentials.ssid)
            .putString(KEY_PASSWORD, credentials.password)
            .apply()
    }

    fun readCredentials(context: Context): Credentials? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ssid = prefs.getString(KEY_SSID, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString(KEY_PASSWORD, null)?.takeIf { it.isNotBlank() } ?: return null
        return Credentials(ssid, password)
    }

    fun clearStoredPassword(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PASSWORD)
            .apply()
    }
}

package com.rokid.glassesbaredevsample.provisioning

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/** Performs the local Wi-Fi shutdown required when a glasses session stops. */
@Suppress("DEPRECATION")
class GlassesWifiController(context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    fun disconnectAndDisable(reason: String) {
        val wasEnabled = wifiManager.isWifiEnabled
        val disconnectAccepted = runCatching { wifiManager.disconnect() }
            .onFailure { error -> Log.w(TAG, "Wi-Fi disconnect failed reason=$reason", error) }
            .getOrDefault(false)
        val disableAccepted = if (wifiManager.isWifiEnabled) {
            runCatching { wifiManager.setWifiEnabled(false) }
                .onFailure { error -> Log.w(TAG, "Wi-Fi disable failed reason=$reason", error) }
                .getOrDefault(false)
        } else {
            true
        }
        Log.i(
            TAG,
            "Wi-Fi shutdown requested reason=$reason wasEnabled=$wasEnabled " +
                "disconnectAccepted=$disconnectAccepted disableAccepted=$disableAccepted",
        )
    }

    private companion object {
        const val TAG = "GlassesWifi"
    }
}

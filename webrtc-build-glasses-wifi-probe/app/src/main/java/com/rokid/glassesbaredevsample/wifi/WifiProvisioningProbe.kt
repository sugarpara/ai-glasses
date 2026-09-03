package com.rokid.glassesbaredevsample.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address

/**
 * Saves and connects to a WPA2 hotspot without opening Android's Wi-Fi picker.
 *
 * This uses the legacy WifiConfiguration API intentionally. The app is a
 * sideloaded prototype targeting API 28 because the glasses have no display on
 * which Android's modern WifiNetworkSpecifier confirmation dialog can be used.
 */
@Suppress("DEPRECATION")
class WifiProvisioningProbe(
    context: Context,
    private val listener: Listener,
) {
    enum class FailureReason {
        ENABLE_REJECTED,
        CONFIGURATION_REJECTED,
        AUTH_FAILED,
        TIMEOUT,
        NO_IPV4_GATEWAY,
    }

    interface Listener {
        fun onConnected(phoneHost: String)
        fun onFailure(message: String, reason: FailureReason)
        fun onDisconnected(message: String) = Unit
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetwork: Network? = null
    private var timeoutTask: Runnable? = null
    private var reconnectTask: Runnable? = null
    private var requestedSsid: String? = null
    private var observeOnly = false
    private var connected = false
    private var failed = false
    private var active = false
    private var wifiSeenWithoutGateway = false

    /**
     * Watches the Wi-Fi connection Android has already restored from its saved networks.
     * No SSID or password is needed on this path.
     */
    fun observeConnectedWifi() {
        close()
        active = true
        observeOnly = true
        registerWifiCallback()
        requestSavedWifiReconnect()
        connectivityManager.allNetworks.forEach(::acceptNetworkIfRequested)
        scheduleReconnectRetry()
        scheduleTimeout("Android-saved network")
        Log.i(TAG, "Waiting for an Android-saved Wi-Fi connection")
    }

    fun connect(ssid: String, password: String) {
        close()
        active = true

        val validationError = validateRequest(ssid, password)
        if (validationError != null) {
            fail(validationError, FailureReason.CONFIGURATION_REJECTED)
            return
        }

        requestedSsid = ssid
        observeOnly = false
        registerWifiCallback()

        try {
            if (!wifiManager.isWifiEnabled && !wifiManager.setWifiEnabled(true)) {
                fail("Wi-Fi could not be enabled", FailureReason.ENABLE_REJECTED)
                return
            }

            if (currentSsid() == ssid) {
                Log.i(TAG, "Already connected to requested Wi-Fi ssid=$ssid")
                scheduleTimeout(ssid)
                return
            }

            val quotedSsid = quote(ssid)
            val configuration = WifiConfiguration().apply {
                SSID = quotedSsid
                preSharedKey = quote(password)
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }

            val existingNetworkId = wifiManager.configuredNetworks
                ?.firstOrNull { it.SSID == quotedSsid }
                ?.networkId
            val networkId = if (existingNetworkId == null) {
                wifiManager.addNetwork(configuration)
            } else {
                configuration.networkId = existingNetworkId
                wifiManager.updateNetwork(configuration)
            }

            if (networkId < 0) {
                fail(
                    "Android rejected the saved Wi-Fi configuration",
                    FailureReason.CONFIGURATION_REJECTED,
                )
                return
            }
            if (!wifiManager.enableNetwork(networkId, true)) {
                fail("Android could not enable the saved Wi-Fi network", FailureReason.AUTH_FAILED)
                return
            }

            val saved = wifiManager.saveConfiguration()
            wifiManager.reconnect()
            val source = if (existingNetworkId == null) "new" else "updated"
            Log.i(
                TAG,
                "Legacy Wi-Fi connection requested ssid=$ssid networkId=$networkId " +
                    "source=$source saved=$saved",
            )
            scheduleReconnectRetry()
            scheduleTimeout(ssid)
        } catch (error: SecurityException) {
            fail(
                "Wi-Fi configuration denied: ${error.message}",
                FailureReason.ENABLE_REJECTED,
                error,
            )
        } catch (error: IllegalArgumentException) {
            fail(
                "Invalid Wi-Fi configuration: ${error.message}",
                FailureReason.CONFIGURATION_REJECTED,
                error,
            )
        }
    }

    fun close() {
        active = false
        clearTimeout()
        clearReconnectRetry()
        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Registration may have failed before this cleanup ran.
            }
        }
        networkCallback = null
        activeNetwork = null
        requestedSsid = null
        observeOnly = false
        connected = false
        failed = false
        wifiSeenWithoutGateway = false
        connectivityManager.bindProcessToNetwork(null)
    }

    private fun registerWifiCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post { acceptNetworkIfRequested(network) }
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: android.net.LinkProperties,
            ) {
                mainHandler.post { acceptNetworkIfRequested(network) }
            }

            override fun onLost(network: Network) {
                if (!active) return
                if (network != activeNetwork) return
                activeNetwork = null
                connected = false
                connectivityManager.bindProcessToNetwork(null)
                val message = "Wi-Fi network was lost"
                Log.w(TAG, message)
                mainHandler.post { if (active) listener.onDisconnected(message) }
            }
        }
        networkCallback = callback
        connectivityManager.registerNetworkCallback(request, callback)
    }

    private fun requestSavedWifiReconnect() {
        try {
            if (!wifiManager.isWifiEnabled) {
                val enabled = wifiManager.setWifiEnabled(true)
                Log.i(TAG, "Wi-Fi enable requested for saved network accepted=$enabled")
                if (!enabled) {
                    fail("Android rejected enabling Wi-Fi", FailureReason.ENABLE_REJECTED)
                    return
                }
            }
            val accepted = wifiManager.reconnect()
            Log.i(TAG, "Android-saved Wi-Fi reconnect requested accepted=$accepted")
            if (!accepted) {
                fail("Android rejected the saved Wi-Fi reconnect", FailureReason.AUTH_FAILED)
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Android-saved Wi-Fi reconnect denied", error)
            fail(
                "Android denied the saved Wi-Fi reconnect: ${error.message}",
                FailureReason.ENABLE_REJECTED,
                error,
            )
        }
    }

    private fun acceptNetworkIfRequested(network: Network) {
        if (!active) return
        if (failed) return
        if (connected && network == activeNetwork) return
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return

        val expectedSsid = requestedSsid
        val actualSsid = currentSsid()
        if (!observeOnly && expectedSsid != null && actualSsid != expectedSsid) {
            Log.d(TAG, "Ignoring Wi-Fi network ssid=$actualSsid; waiting for $expectedSsid")
            return
        }

        val linkProperties = connectivityManager.getLinkProperties(network)
        val gateway = linkProperties
            ?.routes
            ?.firstOrNull { route ->
                route.isDefaultRoute && route.gateway is Inet4Address
            }
            ?.gateway
            ?.hostAddress
        if (gateway.isNullOrBlank()) {
            wifiSeenWithoutGateway = true
            Log.d(TAG, "Connected to ssid=$actualSsid; waiting for IPv4 default gateway")
            mainHandler.postDelayed({ acceptNetworkIfRequested(network) }, GATEWAY_RETRY_MS)
            return
        }

        if (!connectivityManager.bindProcessToNetwork(network)) {
            fail(
                "Wi-Fi connected, but process network binding failed",
                FailureReason.NO_IPV4_GATEWAY,
            )
            return
        }

        activeNetwork = network
        connected = true
        clearTimeout()
        clearReconnectRetry()
        Log.i(TAG, "Wi-Fi connected ssid=$actualSsid gateway=$gateway mode=${if (observeOnly) "saved" else "provisioned"}")
        listener.onConnected(gateway)
    }

    private fun validateRequest(ssid: String, password: String): String? {
        if (ssid.isBlank()) return "Wi-Fi SSID is empty"
        if (password.length !in 8..63) return "WPA2 password must contain 8 to 63 characters"
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "Missing runtime permission: ${Manifest.permission.ACCESS_FINE_LOCATION}"
        }

        val locationManager = appContext.getSystemService(LocationManager::class.java)
        if (!locationManager.isLocationEnabled) {
            return "Location service must be enabled for Wi-Fi provisioning"
        }
        return null
    }

    private fun clearTimeout() {
        timeoutTask?.let(mainHandler::removeCallbacks)
        timeoutTask = null
    }

    private fun scheduleReconnectRetry() {
        clearReconnectRetry()
        reconnectTask = Runnable {
            reconnectTask = null
            if (!active || connected || failed) return@Runnable
            try {
                val scanAccepted = wifiManager.startScan()
                val reconnectAccepted = wifiManager.reconnect()
                Log.i(
                    TAG,
                    "Wi-Fi recovery retry scanAccepted=$scanAccepted " +
                        "reconnectAccepted=$reconnectAccepted",
                )
            } catch (error: SecurityException) {
                Log.w(TAG, "Wi-Fi recovery retry denied", error)
            }
            scheduleReconnectRetry()
        }.also { task -> mainHandler.postDelayed(task, RECONNECT_RETRY_MS) }
    }

    private fun clearReconnectRetry() {
        reconnectTask?.let(mainHandler::removeCallbacks)
        reconnectTask = null
    }

    private fun scheduleTimeout(ssid: String) {
        clearTimeout()
        timeoutTask = Runnable {
            if (!connected) {
                fail(
                    "Wi-Fi connection timed out for ssid=$ssid",
                    if (wifiSeenWithoutGateway) {
                        FailureReason.NO_IPV4_GATEWAY
                    } else {
                        FailureReason.TIMEOUT
                    },
                )
            }
        }.also { task -> mainHandler.postDelayed(task, REQUEST_TIMEOUT_MS) }
    }

    private fun fail(
        message: String,
        reason: FailureReason,
        error: Throwable? = null,
    ) {
        if (!active) return
        if (failed) return
        failed = true
        clearTimeout()
        clearReconnectRetry()
        if (error == null) {
            Log.w(TAG, message)
        } else {
            Log.e(TAG, message, error)
        }
        mainHandler.post { if (active) listener.onFailure(message, reason) }
    }

    private fun currentSsid(): String = wifiManager.connectionInfo.ssid.trim('"')

    private fun quote(value: String): String = "\"$value\""

    private companion object {
        const val TAG = "WifiProbe"
        const val REQUEST_TIMEOUT_MS = 45_000L
        const val GATEWAY_RETRY_MS = 500L
        const val RECONNECT_RETRY_MS = 10_000L
    }
}

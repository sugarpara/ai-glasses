package com.rokid.glassesbaredevsample.provisioning

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rokid.glassesbaredevsample.R
import com.rokid.glassesbaredevsample.activities.main.MainActivity
import com.rokid.glassesbaredevsample.webrtc.StreamingForegroundService
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Keeps only connection discovery alive while the glasses are idle. CameraX and
 * WebRTC remain owned by MainActivity and are started only when the phone server
 * is reachable through an Android-saved Wi-Fi network.
 */
class ConnectionRecoveryService : Service() {
    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val wifiManager by lazy {
        applicationContext.getSystemService(WifiManager::class.java)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val probeExecutor = Executors.newSingleThreadExecutor()

    private var probeRunning = false
    private var unavailableSinceMs = 0L
    private var bleFallbackStarted = false
    private var lastWifiEnableAttemptMs = 0L

    private val periodicProbe = object : Runnable {
        override fun run() {
            probePhoneIfNeeded()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleProbe(0L)

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            scheduleProbe(0L)

        override fun onLost(network: Network) {
            StreamingForegroundService.stop(applicationContext)
            scheduleProbe(PROBE_RETRY_MS)
        }
    }

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "Power broadcast action=${intent?.action} interactive=${powerManager.isInteractive}")
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                StreamingForegroundService.stop(applicationContext)
            }
            scheduleProbe(if (powerManager.isInteractive) 0L else PROBE_RETRY_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Glasses stream recovery")
                .setContentText("Waiting for the saved phone hotspot")
                .setOngoing(true)
                .build(),
        )
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(),
            networkCallback,
        )
        ContextCompat.registerReceiver(
            this,
            wakeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        Log.i(TAG, "Connection recovery service created")
        ensureWifiEnabled("service-created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Recovery requested action=${intent?.action ?: "sticky-restart"}")
        ensureWifiEnabled(intent?.action ?: "sticky-restart")
        scheduleProbe(0L)
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(periodicProbe)
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        runCatching { unregisterReceiver(wakeReceiver) }
        probeExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleProbe(delayMs: Long) {
        mainHandler.removeCallbacks(periodicProbe)
        mainHandler.postDelayed(periodicProbe, delayMs)
    }

    private fun probePhoneIfNeeded() {
        if (MainActivity.isStreamingActivityActive() || StreamingForegroundService.isActive()) {
            unavailableSinceMs = 0L
            scheduleProbe(ACTIVE_CHECK_MS)
            return
        }
        if (probeRunning) return
        probeRunning = true
        probeExecutor.execute {
            val gateway = findWifiGateway()
            val reachable = gateway != null && isPortReachable(gateway, SIGNALING_PORT)
            mainHandler.post {
                probeRunning = false
                when {
                    gateway == null -> handleWifiUnavailable()
                    reachable -> {
                        unavailableSinceMs = 0L
                        stopBleFallback()
                        if (powerManager.isInteractive) {
                            Log.i(TAG, "Phone signaling server ready host=$gateway; starting stream")
                            StreamingForegroundService.start(applicationContext, gateway)
                        } else {
                            Log.i(TAG, "Phone found at $gateway but glasses are asleep")
                        }
                    }
                    else -> {
                        unavailableSinceMs = 0L
                        stopBleFallback()
                        Log.d(TAG, "Saved Wi-Fi is ready; waiting for phone port $SIGNALING_PORT at $gateway")
                    }
                }
                scheduleProbe(PROBE_RETRY_MS)
            }
        }
    }

    private fun findWifiGateway(): String? {
        return connectivityManager.allNetworks.firstNotNullOfOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
                ?: return@firstNotNullOfOrNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return@firstNotNullOfOrNull null
            }
            connectivityManager.getLinkProperties(network)
                ?.routes
                ?.firstOrNull { route ->
                    route.isDefaultRoute && route.gateway is Inet4Address
                }
                ?.gateway
                ?.hostAddress
        }
    }

    private fun isPortReachable(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    private fun handleWifiUnavailable() {
        ensureWifiEnabled("no-ipv4-route")
        if (unavailableSinceMs == 0L) unavailableSinceMs = SystemClock.elapsedRealtime()
        val unavailableFor = SystemClock.elapsedRealtime() - unavailableSinceMs
        Log.d(TAG, "No saved IPv4 Wi-Fi route; waiting ${unavailableFor}ms")
        if (unavailableFor >= BLE_FALLBACK_DELAY_MS && !bleFallbackStarted) {
            bleFallbackStarted = true
            Log.i(TAG, "Saved Wi-Fi unavailable; enabling BLE fallback")
            BleProvisioningService.start(applicationContext)
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureWifiEnabled(reason: String) {
        if (wifiManager.isWifiEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastWifiEnableAttemptMs < WIFI_ENABLE_RETRY_MS) return
        lastWifiEnableAttemptMs = now
        runCatching { wifiManager.setWifiEnabled(true) }
            .onSuccess { accepted ->
                Log.i(TAG, "Wi-Fi enable requested reason=$reason accepted=$accepted")
            }
            .onFailure { error ->
                Log.w(TAG, "Wi-Fi enable request failed reason=$reason", error)
            }
    }

    private fun stopBleFallback() {
        if (!bleFallbackStarted) return
        stopService(Intent(this, BleProvisioningService::class.java))
        bleFallbackStarted = false
        Log.i(TAG, "Saved Wi-Fi restored; BLE fallback stopped")
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Glasses stream recovery",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val TAG = "GlassesRecovery"
        private const val CHANNEL_ID = "glasses_stream_recovery"
        private const val NOTIFICATION_ID = 202
        private const val SIGNALING_PORT = 8888
        private const val SOCKET_TIMEOUT_MS = 1_500
        private const val PROBE_RETRY_MS = 3_000L
        private const val ACTIVE_CHECK_MS = 10_000L
        private const val BLE_FALLBACK_DELAY_MS = 30_000L
        private const val WIFI_ENABLE_RETRY_MS = 15_000L
        fun start(context: Context, reason: String = "manual") {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ConnectionRecoveryService::class.java)
                        .setAction(reason),
                )
            }.onFailure { error ->
                Log.e(TAG, "Unable to start connection recovery reason=$reason", error)
            }
        }
    }
}

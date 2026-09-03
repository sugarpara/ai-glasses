package com.rokid.glassesbaredevsample.provisioning

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rokid.glassesbaredevsample.BuildConfig
import com.rokid.glassesbaredevsample.R
import com.rokid.glassesbaredevsample.webrtc.StreamingForegroundService
import com.rokid.glassesbaredevsample.wifi.WifiProvisioningProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Single owner for physical state, Wi-Fi connection requests and stream startup. */
class GlassesSessionService : Service() {
    private data class ConnectionRequest(
        val ssid: String?,
        val password: String?,
        val phoneHost: String?,
        val requestId: String?,
        val reason: String,
    ) {
        fun targetsSameSession(other: ConnectionRequest?): Boolean =
            other != null &&
                ssid == other.ssid &&
                password == other.password &&
                phoneHost == other.phoneHost &&
                requestId == other.requestId
    }

    private var pendingConnection: ConnectionRequest? = null
    private var activeConnection: ConnectionRequest? = null
    private var wifiProvisioningProbe: WifiProvisioningProbe? = null
    private var signalingPortProbe: SignalingPortProbe? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingNotWornEventElapsedRealtimeMs: Long? = null
    private val confirmedWearBleStart = Runnable {
        if (state.value.shouldRunBle) {
            ensureBleReady("wear-confirmed")
        }
    }
    private val confirmedNotWornStop = Runnable {
        val eventTime = pendingNotWornEventElapsedRealtimeMs ?: return@Runnable
        pendingNotWornEventElapsedRealtimeMs = null
        applyPhysicalEvent(
            event = WearFoldEvent.WearChanged(WearState.NOT_WORN),
            eventTime = eventTime,
            action = WearFoldContract.ACTION_TAKE_STATUS_CHANGED,
            rawValue = "0",
            qualifier = "confirmed",
        )
        stopSession("not-worn")
    }
    private val bleStateListener: (BleSessionState, String?) -> Unit = { bleState, error ->
        mutableState.update { current ->
            current.copy(
                bleState = bleState,
                lastError = if (bleState == BleSessionState.ERROR) {
                    error ?: current.lastError
                } else {
                    current.lastError
                },
            )
        }
        updateNotification()
        Log.i(TAG, "BLE state=$bleState${error?.let { " error=$it" } ?: ""}")
        if (
            state.value.shouldRunBle &&
            (bleState == BleSessionState.READY || bleState == BleSessionState.CONNECTED)
        ) {
            maybeStartPendingConnection("ble-ready")
        }
    }
    private val streamStateListener = object : StreamingForegroundService.Listener {
        override fun onSignalingConnected() {
            val request = activeConnection ?: return
            publishBleStatus(request, BleProtocolState.SIGNALING_CONNECTED)
        }

        override fun onStreaming() {
            val request = activeConnection ?: return
            mutableState.update { current ->
                current.copy(connectionState = SessionConnectionState.STREAMING, lastError = null)
            }
            updateNotification()
            publishBleStatus(request, BleProtocolState.STREAMING)
        }

        override fun onFailure(
            type: StreamingForegroundService.FailureType,
            message: String,
        ) {
            val request = activeConnection ?: return
            val error = when (type) {
                StreamingForegroundService.FailureType.CAMERA ->
                    BleProtocolError.CAMERA_START_FAILED
                StreamingForegroundService.FailureType.WEBRTC -> BleProtocolError.WEBRTC_FAILED
            }
            mutableState.update { current ->
                current.copy(connectionState = SessionConnectionState.ERROR, lastError = message)
            }
            updateNotification()
            publishBleStatus(request, BleProtocolState.STOPPED, error)
        }
    }

    private val wearFoldReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val extra = WearFoldContract.expectedExtra(action) ?: return
            val rawValue = intent.getStringExtra(extra)
            val event = WearFoldContract.parse(action, rawValue)
            if (event == null) {
                Log.w(TAG, "Ignoring unknown physical event action=$action rawValue=$rawValue")
                return
            }

            val eventTime = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "Physical event received action=$action rawValue=$rawValue",
            )
            when (event.shutdownPolicy()) {
                PhysicalShutdownPolicy.DELAYED -> {
                    scheduleNotWornStop(eventTime)
                }

                PhysicalShutdownPolicy.IMMEDIATE -> {
                    cancelPendingNotWornStop("folded")
                    applyPhysicalEvent(event, eventTime, action, rawValue)
                    stopSession("leg-folded")
                }

                PhysicalShutdownPolicy.NONE -> {
                    if (event is WearFoldEvent.WearChanged && event.state == WearState.WORN) {
                        cancelPendingNotWornStop("worn")
                    }
                    applyPhysicalEvent(event, eventTime, action, rawValue)
                    when {
                        state.value.shouldRunBle -> {
                            BleProvisioningService.notifyPhysicalReady()
                            scheduleBleReadyAfterWearConfirmation("physical-state")
                        }

                        state.value.shouldAdvertiseBle -> {
                            ensureBleReady("physical-state-unknown")
                        }

                        else -> {
                            ensureBleStopped("physical-state-not-ready")
                            maybeStartPendingConnection("physical-state")
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        promoteToForeground()
        ContextCompat.registerReceiver(
            this,
            wearFoldReceiver,
            IntentFilter().apply {
                addAction(WearFoldContract.ACTION_TAKE_STATUS_CHANGED)
                addAction(WearFoldContract.ACTION_LEG_STATUS_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        mutableState.value = GlassesSessionState(serviceRunning = true)
        BleProvisioningService.setStateListener(bleStateListener)
        StreamingForegroundService.setStateListener(streamStateListener)
        BootRecoveryScheduler.launchNow(applicationContext, "session-service-created")
        ensureBleReady("service-created-physical-unknown")
        Log.i(
            TAG,
            "Service created version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "wear=UNKNOWN leg=UNKNOWN",
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_ENSURE_STARTED
        Log.i(TAG, "Service start action=$action startId=$startId")
        promoteToForeground()
        when (action) {
            ACTION_REQUEST_CONNECTION -> intent?.let(::handleConnectionRequest)
            ACTION_PHYSICAL_STATE -> intent?.let(::handleForwardedPhysicalState)
            ACTION_STOP_SESSION -> stopSession("explicit-stop")
            ACTION_STOP_ACTIVE_SESSION -> stopSession(
                intent?.getStringExtra(EXTRA_REASON) ?: "active-session-stop",
                stopBle = false,
            )
            ACTION_ENSURE_STARTED -> Unit
            else -> Log.w(TAG, "Ignoring unknown service action=$action")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(wearFoldReceiver) }
        wifiProvisioningProbe?.close()
        wifiProvisioningProbe = null
        signalingPortProbe?.close()
        signalingPortProbe = null
        pendingConnection = null
        activeConnection = null
        BootRecoveryScheduler.cancelRetries(applicationContext)
        StreamingForegroundService.stop(applicationContext)
        BleProvisioningService.stop(applicationContext)
        BleProvisioningService.clearStateListener(bleStateListener)
        StreamingForegroundService.clearStateListener(streamStateListener)
        GlassesWifiController(applicationContext).disconnectAndDisable("service-destroyed")
        mutableState.update { current ->
            current.copy(
                serviceRunning = false,
                bleState = BleSessionState.OFF,
                connectionState = SessionConnectionState.IDLE,
                desiredSession = false,
            )
        }
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleForwardedPhysicalState(intent: Intent) {
        val physicalAction = intent.getStringExtra(EXTRA_PHYSICAL_ACTION) ?: return
        val physicalExtra = WearFoldContract.expectedExtra(physicalAction) ?: return
        val rawValue = intent.getStringExtra(EXTRA_PHYSICAL_VALUE)
        wearFoldReceiver.onReceive(
            this,
            Intent(physicalAction).putExtra(physicalExtra, rawValue),
        )
    }

    private fun applyPhysicalEvent(
        event: WearFoldEvent,
        eventTime: Long,
        action: String,
        rawValue: String?,
        qualifier: String? = null,
    ) {
        mutableState.update { current ->
            GlassesSessionStateReducer.reduce(current, event, eventTime)
        }
        Log.i(
            TAG,
            "Physical state${qualifier?.let { " $it" } ?: ""} action=$action rawValue=$rawValue " +
                "wear=${mutableState.value.wearState} leg=${mutableState.value.legState}",
        )
        updateNotification()
    }

    private fun scheduleNotWornStop(eventTime: Long) {
        pendingNotWornEventElapsedRealtimeMs = eventTime
        mainHandler.removeCallbacks(confirmedNotWornStop)
        mainHandler.postDelayed(
            confirmedNotWornStop,
            WearFoldContract.NOT_WORN_CONFIRMATION_MS,
        )
        Log.i(
            TAG,
            "NOT_WORN confirmation scheduled delayMs=${WearFoldContract.NOT_WORN_CONFIRMATION_MS}",
        )
    }

    private fun cancelPendingNotWornStop(reason: String) {
        if (pendingNotWornEventElapsedRealtimeMs == null) return
        pendingNotWornEventElapsedRealtimeMs = null
        mainHandler.removeCallbacks(confirmedNotWornStop)
        Log.i(TAG, "Cancelled pending NOT_WORN shutdown reason=$reason")
    }

    private fun handleConnectionRequest(intent: Intent) {
        val request = ConnectionRequest(
            ssid = intent.getStringExtra(BleProvisioningContract.EXTRA_WIFI_SSID),
            password = intent.getStringExtra(BleProvisioningContract.EXTRA_WIFI_PASSWORD),
            phoneHost = intent.getStringExtra(EXTRA_PHONE_HOST),
            requestId = intent.getStringExtra(EXTRA_REQUEST_ID),
            reason = intent.getStringExtra(EXTRA_REASON) ?: "unspecified",
        )
        if (
            request.targetsSameSession(activeConnection) &&
            wifiProvisioningProbe != null &&
            state.value.connectionState.isConnectionActive()
        ) {
            Log.i(TAG, "Connection request already active reason=${request.reason}")
            return
        }
        pendingConnection = request
        mutableState.update { current ->
            current.copy(
                connectionState = if (current.isOpenAndWorn) {
                    SessionConnectionState.WIFI_CONNECTING
                } else {
                    SessionConnectionState.WAITING_FOR_PHYSICAL_STATE
                },
                desiredSession = true,
                lastError = null,
            )
        }
        Log.i(
            TAG,
            "Connection requested reason=${request.reason} " +
                "hasCredentials=${!request.ssid.isNullOrBlank()} " +
                "wear=${state.value.wearState} leg=${state.value.legState}",
        )
        updateNotification()
        maybeStartPendingConnection("connection-request")
    }

    private fun maybeStartPendingConnection(trigger: String) {
        val request = pendingConnection ?: return
        if (!state.value.isOpenAndWorn || !state.value.bleState.isReadyForSession()) {
            mutableState.update { current ->
                current.copy(connectionState = SessionConnectionState.WAITING_FOR_PHYSICAL_STATE)
            }
            Log.i(
                TAG,
                "Connection deferred trigger=$trigger wear=${state.value.wearState} " +
                    "leg=${state.value.legState} ble=${state.value.bleState}",
            )
            updateNotification()
            return
        }
        if (
            request.targetsSameSession(activeConnection) &&
            wifiProvisioningProbe != null &&
            state.value.connectionState.isConnectionActive()
        ) {
            return
        }

        pendingConnection = null
        activeConnection = request
        wifiProvisioningProbe?.close()
        signalingPortProbe?.close()
        signalingPortProbe = null
        StreamingForegroundService.stop(applicationContext)
        mutableState.update { current ->
            current.copy(
                connectionState = SessionConnectionState.WIFI_CONNECTING,
                lastError = null,
            )
        }
        updateNotification()
        publishBleStatus(request, BleProtocolState.WIFI_ENABLING)

        wifiProvisioningProbe = WifiProvisioningProbe(
            applicationContext,
            object : WifiProvisioningProbe.Listener {
                override fun onConnected(phoneHost: String) {
                    if (activeConnection != request) return
                    if (!request.password.isNullOrBlank()) {
                        BleProvisioningContract.clearStoredPassword(applicationContext)
                    }
                    BootRecoveryScheduler.cancelRetries(applicationContext)
                    mutableState.update { current ->
                        current.copy(connectionState = SessionConnectionState.WIFI_READY)
                    }
                    updateNotification()
                    publishBleStatus(request, BleProtocolState.WIFI_READY)
                    val resolvedPhoneHost = request.phoneHost?.takeIf { it.isNotBlank() }
                        ?: phoneHost
                    mutableState.update { current ->
                        current.copy(connectionState = SessionConnectionState.PHONE_WAITING)
                    }
                    updateNotification()
                    publishBleStatus(request, BleProtocolState.PHONE_WAITING)
                    signalingPortProbe?.close()
                    signalingPortProbe = SignalingPortProbe(
                        object : SignalingPortProbe.Listener {
                            override fun onReady() {
                                if (activeConnection != request) return
                                mutableState.update { current ->
                                    current.copy(
                                        connectionState = SessionConnectionState.STREAM_STARTING,
                                        lastError = null,
                                    )
                                }
                                updateNotification()
                                StreamingForegroundService.start(
                                    applicationContext,
                                    resolvedPhoneHost,
                                )
                                Log.i(
                                    TAG,
                                    "Phone signaling reachable host=$resolvedPhoneHost; " +
                                        "stream start delegated",
                                )
                            }

                            override fun onFailure(message: String) {
                                if (activeConnection != request) return
                                mutableState.update { current ->
                                    current.copy(
                                        connectionState = SessionConnectionState.ERROR,
                                        lastError = message,
                                    )
                                }
                                updateNotification()
                                publishBleStatus(
                                    request,
                                    BleProtocolState.STOPPED,
                                    BleProtocolError.PHONE_SIGNALING_UNREACHABLE,
                                )
                                Log.e(TAG, message)
                            }
                        },
                    ).also { probe -> probe.start(resolvedPhoneHost, SIGNALING_PORT) }
                }

                override fun onFailure(
                    message: String,
                    reason: WifiProvisioningProbe.FailureReason,
                ) {
                    if (activeConnection != request) return
                    StreamingForegroundService.stop(applicationContext)
                    mutableState.update { current ->
                        current.copy(
                            connectionState = SessionConnectionState.ERROR,
                            lastError = message,
                        )
                    }
                    updateNotification()
                    publishBleStatus(
                        request,
                        BleProtocolState.STOPPED,
                        reason.toBleProtocolError(),
                    )
                    Log.e(TAG, "Connection failed: $message")
                }

                override fun onDisconnected(message: String) {
                    if (activeConnection != request) return
                    signalingPortProbe?.close()
                    signalingPortProbe = null
                    StreamingForegroundService.stop(applicationContext)
                    mutableState.update { current ->
                        current.copy(
                            connectionState = SessionConnectionState.WIFI_CONNECTING,
                            lastError = message,
                        )
                    }
                    updateNotification()
                    publishBleStatus(
                        request,
                        BleProtocolState.WIFI_CONNECTING,
                        BleProtocolError.WIFI_AUTH_FAILED,
                    )
                    Log.w(TAG, "$message; stream stopped while Wi-Fi callback remains active")
                }
            },
        ).also { probe ->
            val ssid = request.ssid
            val password = request.password
            if (!ssid.isNullOrBlank() && !password.isNullOrBlank()) {
                Log.i(TAG, "Starting provisioned Wi-Fi request ssid=$ssid")
                probe.connect(ssid, password)
            } else {
                Log.i(TAG, "Starting Android-saved Wi-Fi request")
                probe.observeConnectedWifi()
            }
            publishBleStatus(request, BleProtocolState.WIFI_CONNECTING)
        }
    }

    private fun publishBleStatus(
        request: ConnectionRequest,
        protocolState: BleProtocolState,
        error: BleProtocolError? = null,
    ) {
        val requestId = request.requestId ?: return
        BleProvisioningService.publishSessionStatus(requestId, protocolState, error)
    }

    private fun WifiProvisioningProbe.FailureReason.toBleProtocolError(): BleProtocolError =
        when (this) {
            WifiProvisioningProbe.FailureReason.ENABLE_REJECTED ->
                BleProtocolError.WIFI_ENABLE_REJECTED
            WifiProvisioningProbe.FailureReason.CONFIGURATION_REJECTED ->
                BleProtocolError.WIFI_CONFIGURATION_REJECTED
            WifiProvisioningProbe.FailureReason.AUTH_FAILED -> BleProtocolError.WIFI_AUTH_FAILED
            WifiProvisioningProbe.FailureReason.TIMEOUT -> BleProtocolError.WIFI_TIMEOUT
            WifiProvisioningProbe.FailureReason.NO_IPV4_GATEWAY ->
                BleProtocolError.NO_IPV4_GATEWAY
        }

    private fun scheduleBleReadyAfterWearConfirmation(reason: String) {
        if (!state.value.shouldRunBle) return
        if (state.value.bleState.isReadyForSession()) {
            maybeStartPendingConnection("ble-already-ready")
            return
        }
        if (state.value.bleState == BleSessionState.STARTING) return
        mainHandler.removeCallbacks(confirmedWearBleStart)
        mainHandler.postDelayed(confirmedWearBleStart, WEAR_CONFIRMATION_MS)
        Log.i(
            TAG,
            "BLE start scheduled reason=$reason confirmationMs=$WEAR_CONFIRMATION_MS",
        )
    }

    private fun ensureBleReady(reason: String) {
        if (!state.value.shouldAdvertiseBle) return
        if (
            state.value.bleState == BleSessionState.STARTING ||
            state.value.bleState == BleSessionState.READY ||
            state.value.bleState == BleSessionState.CONNECTED
        ) {
            return
        }
        mutableState.update { current -> current.copy(bleState = BleSessionState.STARTING) }
        updateNotification()
        Log.i(TAG, "Starting BLE provisioning reason=$reason")
        BleProvisioningService.start(applicationContext)
    }

    private fun ensureBleStopped(reason: String) {
        mainHandler.removeCallbacks(confirmedWearBleStart)
        if (state.value.bleState == BleSessionState.OFF) return
        Log.i(TAG, "Stopping BLE provisioning reason=$reason")
        BleProvisioningService.stop(applicationContext)
        mutableState.update { current -> current.copy(bleState = BleSessionState.OFF) }
        updateNotification()
    }

    private fun stopSession(reason: String, stopBle: Boolean = true) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val terminalError = when (reason) {
            "not-worn" -> BleProtocolError.GLASSES_NOT_WORN
            "leg-folded" -> BleProtocolError.GLASSES_FOLDED
            else -> null
        }
        val deferredBleStop =
            stopBle &&
                terminalError != null &&
                BleProvisioningService.notifyPhysicalShutdown(terminalError)
        mainHandler.removeCallbacks(confirmedWearBleStart)
        mutableState.update { current ->
            current.copy(
                connectionState = SessionConnectionState.STOPPING,
                desiredSession = false,
                lastError = null,
            )
        }
        updateNotification()
        wifiProvisioningProbe?.close()
        wifiProvisioningProbe = null
        signalingPortProbe?.close()
        signalingPortProbe = null
        pendingConnection = null
        activeConnection = null
        BootRecoveryScheduler.cancelRetries(applicationContext)
        StreamingForegroundService.stop(applicationContext)
        if (stopBle && !deferredBleStop) {
            BleProvisioningService.stop(applicationContext)
        }
        GlassesWifiController(applicationContext).disconnectAndDisable(reason)
        mutableState.update { current ->
            current.copy(
                connectionState = SessionConnectionState.IDLE,
                bleState = if (stopBle) BleSessionState.OFF else current.bleState,
                desiredSession = false,
                lastError = null,
            )
        }
        updateNotification()
        Log.i(
            TAG,
            "Session stopped reason=$reason durationMs=" +
                (SystemClock.elapsedRealtime() - startedAtMs),
        )
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Glasses session",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Glasses session")
        .setContentText(
            "${state.value.connectionState} · BLE ${state.value.bleState} · " +
                "${state.value.legState} · ${state.value.wearState}",
        )
        .setOngoing(true)
        .build()

    private fun updateNotification() {
        promoteToForeground()
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun SessionConnectionState.isConnectionActive(): Boolean = when (this) {
        SessionConnectionState.WIFI_CONNECTING,
        SessionConnectionState.WIFI_READY,
        SessionConnectionState.PHONE_WAITING,
        SessionConnectionState.STREAM_STARTING,
        SessionConnectionState.STREAMING,
        -> true

        SessionConnectionState.IDLE,
        SessionConnectionState.WAITING_FOR_PHYSICAL_STATE,
        SessionConnectionState.STOPPING,
        SessionConnectionState.ERROR,
        -> false
    }

    private fun BleSessionState.isReadyForSession(): Boolean =
        this == BleSessionState.READY || this == BleSessionState.CONNECTED

    companion object {
        private const val TAG = "GlassesSession"
        private const val CHANNEL_ID = "glasses_session"
        private const val NOTIFICATION_ID = 200
        private const val WEAR_CONFIRMATION_MS = 3_000L
        private const val SIGNALING_PORT = 8888
        private const val ACTION_ENSURE_STARTED = "session.ensure_started"
        private const val ACTION_REQUEST_CONNECTION = "session.request_connection"
        private const val ACTION_PHYSICAL_STATE = "session.physical_state"
        private const val ACTION_STOP_SESSION = "session.stop"
        private const val ACTION_STOP_ACTIVE_SESSION = "session.stop_active"
        private const val EXTRA_REASON = "session_reason"
        private const val EXTRA_REQUEST_ID = "ble_request_id"
        private const val EXTRA_PHYSICAL_ACTION = "physical_action"
        private const val EXTRA_PHYSICAL_VALUE = "physical_value"
        const val EXTRA_PHONE_HOST = "phone_host"

        private val mutableState = MutableStateFlow(GlassesSessionState())
        val state: StateFlow<GlassesSessionState> = mutableState.asStateFlow()

        fun ensureStarted(context: Context, reason: String): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GlassesSessionService::class.java)
                    .setAction(ACTION_ENSURE_STARTED)
                    .putExtra(EXTRA_REASON, reason),
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to ensure session service reason=$reason", error)
        }.isSuccess

        fun notifyPhysicalState(context: Context, source: Intent): Boolean = runCatching {
            val physicalAction = source.action ?: return false
            val physicalExtra = WearFoldContract.expectedExtra(physicalAction) ?: return false
            ContextCompat.startForegroundService(
                context,
                Intent(context, GlassesSessionService::class.java)
                    .setAction(ACTION_PHYSICAL_STATE)
                    .putExtra(EXTRA_PHYSICAL_ACTION, physicalAction)
                    .putExtra(EXTRA_PHYSICAL_VALUE, source.getStringExtra(physicalExtra)),
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to recover session service from physical state", error)
        }.isSuccess

        fun requestConnection(context: Context, source: Intent?, reason: String): Boolean =
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GlassesSessionService::class.java).apply {
                        action = ACTION_REQUEST_CONNECTION
                        putExtra(EXTRA_REASON, reason)
                        source?.getStringExtra(BleProvisioningContract.EXTRA_WIFI_SSID)
                            ?.let { putExtra(BleProvisioningContract.EXTRA_WIFI_SSID, it) }
                        source?.getStringExtra(BleProvisioningContract.EXTRA_WIFI_PASSWORD)
                            ?.let { putExtra(BleProvisioningContract.EXTRA_WIFI_PASSWORD, it) }
                        source?.getStringExtra(EXTRA_PHONE_HOST)
                            ?.let { putExtra(EXTRA_PHONE_HOST, it) }
                    },
                )
            }.onFailure { error ->
                Log.e(TAG, "Unable to request connection reason=$reason", error)
            }.isSuccess

        fun requestSavedSession(
            context: Context,
            requestId: String,
            reason: String,
        ): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GlassesSessionService::class.java)
                    .setAction(ACTION_REQUEST_CONNECTION)
                    .putExtra(EXTRA_REASON, reason)
                    .putExtra(EXTRA_REQUEST_ID, requestId),
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to request saved session requestId=$requestId", error)
        }.isSuccess

        fun requestProvisionedSession(
            context: Context,
            requestId: String,
            ssid: String,
            password: String,
            reason: String,
        ): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GlassesSessionService::class.java)
                    .setAction(ACTION_REQUEST_CONNECTION)
                    .putExtra(EXTRA_REASON, reason)
                    .putExtra(EXTRA_REQUEST_ID, requestId)
                    .putExtra(BleProvisioningContract.EXTRA_WIFI_SSID, ssid)
                    .putExtra(BleProvisioningContract.EXTRA_WIFI_PASSWORD, password),
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to request provisioned session requestId=$requestId", error)
        }.isSuccess

        fun hasConnectionPayload(source: Intent?): Boolean {
            if (source == null) return false
            val ssid = source.getStringExtra(BleProvisioningContract.EXTRA_WIFI_SSID)
            val password = source.getStringExtra(BleProvisioningContract.EXTRA_WIFI_PASSWORD)
            val phoneHost = source.getStringExtra(EXTRA_PHONE_HOST)
            return (!ssid.isNullOrBlank() && !password.isNullOrBlank()) ||
                !phoneHost.isNullOrBlank()
        }

        fun stopSession(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GlassesSessionService::class.java)
                    .setAction(ACTION_STOP_SESSION),
            )
        }

        fun stopActiveSession(context: Context, reason: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GlassesSessionService::class.java)
                    .setAction(ACTION_STOP_ACTIVE_SESSION)
                    .putExtra(EXTRA_REASON, reason),
            )
        }
    }
}

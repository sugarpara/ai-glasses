package com.rokid.glassesbaredevsample.provisioning

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rokid.glassesbaredevsample.BuildConfig
import com.rokid.glassesbaredevsample.R
import com.rokid.glassesbaredevsample.activities.main.MainActivity
import org.json.JSONObject

@SuppressLint("MissingPermission")
class BleProvisioningService : Service() {
    private val bluetoothManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gattServer: BluetoothGattServer? = null
    private var gattServiceRegistered = false
    private var advertising = false
    private var advertisingStarting = false
    private var bleClientConnected = false
    private var suspended = false
    private var launchWakeLock: PowerManager.WakeLock? = null
    private var runtimeState = BleSessionState.OFF
    private var destroyed = false
    private var sessionStatusCharacteristic: BluetoothGattCharacteristic? = null
    private val connectedDevices = mutableMapOf<String, BluetoothDevice>()
    private val subscribedDevices = mutableMapOf<String, BluetoothDevice>()
    private var currentProtocolStatus = BleSessionStatus(null, BleProtocolState.STOPPED)

    private val advertisingWatchdog = object : Runnable {
        override fun run() {
            if (destroyed || runtimeState != BleSessionState.READY) return
            if (gattServer == null) {
                Log.w(TAG, "BLE watchdog found no GATT server; rebuilding")
                reportState(BleSessionState.STARTING)
                startGattServer()
            } else if (!bleClientConnected) {
                restartAdvertising("ready-watchdog")
            }
        }
    }

    private val recoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR,
                    )
                    Log.i(TAG, "Bluetooth adapter state=$state")
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> stopBleRuntime(
                            reason = "bluetooth-off",
                            terminalState = BleSessionState.ERROR,
                            error = "Bluetooth adapter is off",
                        )

                        BluetoothAdapter.STATE_ON -> {
                            reportState(BleSessionState.STARTING)
                            startGattServer()
                        }
                    }
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (destroyed) return
            if (status == BluetoothGatt.GATT_SUCCESS &&
                service.uuid == BleProvisioningContract.SERVICE_UUID
            ) {
                gattServiceRegistered = true
                Log.i(TAG, "BLE GATT provisioning service registered")
                if (suspended) reportState(BleSessionState.OFF) else startAdvertising()
            } else {
                gattServiceRegistered = false
                Log.e(TAG, "BLE GATT service registration failed status=$status")
                gattServer?.close()
                gattServer = null
                reportState(
                    BleSessionState.ERROR,
                    "GATT service registration failed status=$status",
                )
                scheduleGattRetry()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (destroyed) return
            Log.i(TAG, "BLE client state status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && !gattServiceRegistered) {
                Log.w(TAG, "Rejecting BLE client connected before GATT service registration")
                gattServer?.cancelConnection(device)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (suspended) {
                        Log.w(TAG, "Rejecting BLE client while provisioning is suspended")
                        gattServer?.cancelConnection(device)
                        return
                    }
                    connectedDevices[device.address] = device
                    bleClientConnected = true
                    cancelWatchdog()
                    reportState(BleSessionState.CONNECTED)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device.address)
                    subscribedDevices.remove(device.address)
                    bleClientConnected = connectedDevices.isNotEmpty()
                    advertising = false
                    if (!suspended) restartAdvertising("client-disconnected")
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val payload = when (characteristic.uuid) {
                BleProvisioningContract.DEVICE_INFO_UUID -> BleSessionProtocol.encodeDeviceInfo(
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                )

                BleProvisioningContract.SESSION_STATUS_UUID ->
                    BleSessionProtocol.encodeStatus(currentProtocolStatus)

                else -> null
            }
            if (payload == null) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset,
                    null,
                )
            } else {
                sendReadResponse(device, requestId, offset, payload)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            if (descriptor.uuid != BleProvisioningContract.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset,
                    null,
                )
                return
            }
            val value = if (subscribedDevices.containsKey(device.address)) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            sendReadResponse(device, requestId, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val supported = descriptor.uuid ==
                BleProvisioningContract.CLIENT_CHARACTERISTIC_CONFIG_UUID &&
                descriptor.characteristic.uuid == BleProvisioningContract.SESSION_STATUS_UUID &&
                !preparedWrite &&
                offset == 0
            val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val disabled = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            val accepted = supported && (enabled || disabled)
            if (accepted) {
                if (enabled) subscribedDevices[device.address] = device
                else subscribedDevices.remove(device.address)
                Log.i(TAG, "BLE status notifications enabled=$enabled")
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0,
                    null,
                )
            }
            if (accepted && enabled) {
                mainHandler.post { notifyStatus(device) }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (preparedWrite || offset != 0) {
                sendWriteResponse(
                    device,
                    requestId,
                    responseNeeded,
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                )
                return
            }
            when (characteristic.uuid) {
                BleProvisioningContract.CREDENTIALS_UUID -> handleLegacyCredentialsWrite(
                    device,
                    requestId,
                    responseNeeded,
                    value,
                )

                BleProvisioningContract.SESSION_COMMAND_UUID -> handleSessionCommandWrite(
                    device,
                    requestId,
                    responseNeeded,
                    value,
                )

                else -> sendWriteResponse(
                    device,
                    requestId,
                    responseNeeded,
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                )
            }
        }
    }

    private fun sendReadResponse(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        payload: ByteArray,
    ) {
        if (offset < 0 || offset > payload.size) {
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_INVALID_OFFSET,
                offset,
                null,
            )
            return
        }
        gattServer?.sendResponse(
            device,
            requestId,
            BluetoothGatt.GATT_SUCCESS,
            offset,
            payload.copyOfRange(offset, payload.size),
        )
    }

    private fun sendWriteResponse(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        status: Int,
    ) {
        if (responseNeeded) {
            gattServer?.sendResponse(device, requestId, status, 0, null)
        }
    }

    private fun handleLegacyCredentialsWrite(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        value: ByteArray,
    ) {
        val accepted = runCatching {
            val json = JSONObject(value.toString(Charsets.UTF_8))
            val credentials = BleProvisioningContract.Credentials(
                ssid = json.getString("ssid"),
                password = json.getString("password"),
            )
            require(credentials.ssid.isNotBlank()) { "SSID is empty" }
            require(credentials.password.length in 8..63) { "Invalid WPA2 password length" }
            BleProvisioningContract.saveCredentials(applicationContext, credentials)
            Log.i(TAG, "Legacy BLE credentials accepted")
            launchConfiguration(credentials, "ble-v1-write")
        }.onFailure { error ->
            Log.e(TAG, "Legacy BLE credentials rejected", error)
        }.isSuccess
        sendWriteResponse(
            device,
            requestId,
            responseNeeded,
            if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
        )
    }

    private fun handleSessionCommandWrite(
        device: BluetoothDevice,
        requestId: Int,
        responseNeeded: Boolean,
        value: ByteArray,
    ) {
        if (!subscribedDevices.containsKey(device.address)) {
            Log.w(TAG, "BLE v2 command rejected because status notifications are not enabled")
            sendWriteResponse(
                device,
                requestId,
                responseNeeded,
                BluetoothGatt.GATT_FAILURE,
            )
            return
        }

        sendWriteResponse(device, requestId, responseNeeded, BluetoothGatt.GATT_SUCCESS)
        mainHandler.post {
            when (val decoded = BleSessionProtocol.decodeCommand(value)) {
                is BleCommandDecodeResult.Failure -> publishStatus(
                    currentProtocolStatus.copy(
                        requestId = decoded.requestId,
                        error = decoded.error,
                    ),
                )

                is BleCommandDecodeResult.Success -> processSessionCommand(decoded.command)
            }
        }
    }

    private fun processSessionCommand(command: BleSessionCommand) {
        if (command.type == BleSessionCommandType.HEARTBEAT) {
            publishStatus(currentProtocolStatus.copy(requestId = command.requestId, error = null))
            return
        }

        if (command.type != BleSessionCommandType.STOP_SESSION) {
            val physicalError = GlassesSessionService.state.value.physicalStartError()
            if (physicalError != null) {
                publishStatus(
                    BleSessionStatus(
                        requestId = command.requestId,
                        state = BleProtocolState.STOPPED,
                        error = physicalError,
                    ),
                )
                return
            }
        }

        publishStatus(
            BleSessionStatus(
                requestId = command.requestId,
                state = BleProtocolState.COMMAND_RECEIVED,
            ),
        )
        when (command.type) {
            BleSessionCommandType.STOP_SESSION -> {
                GlassesSessionService.stopActiveSession(applicationContext, "ble-v2-stop")
                publishStatus(
                    BleSessionStatus(command.requestId, BleProtocolState.STOPPED),
                )
            }

            BleSessionCommandType.PROVISION_AND_START -> {
                publishStatus(
                    BleSessionStatus(command.requestId, BleProtocolState.CREDENTIALS_ACCEPTED),
                )
                val accepted = GlassesSessionService.requestProvisionedSession(
                    applicationContext,
                    command.requestId,
                    requireNotNull(command.ssid),
                    requireNotNull(command.password),
                    "ble-v2-provision-and-start",
                )
                if (!accepted) {
                    publishStatus(
                        BleSessionStatus(
                            command.requestId,
                            BleProtocolState.STOPPED,
                            BleProtocolError.WEBRTC_FAILED,
                        ),
                    )
                }
            }

            BleSessionCommandType.START_SESSION -> {
                val accepted = GlassesSessionService.requestSavedSession(
                    applicationContext,
                    command.requestId,
                    "ble-v2-start-session",
                )
                if (!accepted) {
                    publishStatus(
                        BleSessionStatus(
                            command.requestId,
                            BleProtocolState.STOPPED,
                            BleProtocolError.WEBRTC_FAILED,
                        ),
                    )
                }
            }
            BleSessionCommandType.HEARTBEAT -> Unit
        }
    }

    private fun publishStatus(status: BleSessionStatus) {
        currentProtocolStatus = status
        val payload = BleSessionProtocol.encodeStatus(status)
        sessionStatusCharacteristic?.value = payload
        Log.i(
            TAG,
            "BLE v2 status requestId=${status.requestId ?: "none"} " +
                "state=${status.state} error=${status.error ?: "none"}",
        )
        subscribedDevices.values.toList().forEach(::notifyStatus)
    }

    private fun notifyStatus(device: BluetoothDevice) {
        val characteristic = sessionStatusCharacteristic ?: return
        characteristic.value = BleSessionProtocol.encodeStatus(currentProtocolStatus)
        val started = gattServer?.notifyCharacteristicChanged(device, characteristic, false) == true
        if (!started) {
            Log.w(TAG, "BLE status notification could not be queued")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            if (destroyed) return
            advertisingStarting = false
            advertising = true
            reportState(BleSessionState.READY)
            scheduleWatchdog()
            Log.i(TAG, "BLE advertising started service=${BleProvisioningContract.SERVICE_UUID}")
        }

        override fun onStartFailure(errorCode: Int) {
            if (destroyed) return
            advertisingStarting = false
            advertising = false
            reportState(BleSessionState.ERROR, "BLE advertising failed code=$errorCode")
            Log.e(TAG, "BLE advertising failed code=$errorCode")
            mainHandler.postDelayed(
                {
                    if (!destroyed && bluetoothManager.adapter?.isEnabled == true) {
                        restartAdvertising("start-failure-$errorCode")
                    }
                },
                ADVERTISE_FAILURE_RETRY_MS,
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        destroyed = false
        currentProtocolStatus = BleSessionStatus(
            requestId = null,
            state = BleProtocolState.STOPPED,
            error = GlassesSessionService.state.value.physicalStartError(),
        )
        reportState(BleSessionState.STARTING)
        Log.i(
            TAG,
            "Service created version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "interactive=${powerManager.isInteractive}",
        )
        createNotificationChannel()
        promoteToForeground()
        ContextCompat.registerReceiver(
            this,
            recoveryReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startGattServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Some glasses firmware tracks every startForegroundService request separately.
        promoteToForeground()
        Log.i(
            TAG,
            "Service start action=${intent?.action ?: "sticky-restart"} " +
                "startId=$startId interactive=${powerManager.isInteractive}",
        )
        resumeBleRuntime(intent?.action ?: "sticky-restart")
        if (intent?.action == ACTION_LAUNCH_SAVED) {
            launchSavedConfiguration("service-start")
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Glasses connection service")
                .setContentText("Waiting for phone provisioning")
                .setOngoing(true)
                .build(),
        )
    }

    override fun onDestroy() {
        Log.w(TAG, "Service destroyed")
        destroyed = true
        if (activeInstance === this) activeInstance = null
        try {
            unregisterReceiver(recoveryReceiver)
        } catch (_: IllegalArgumentException) {
        }
        stopBleRuntime("service-destroyed", BleSessionState.OFF)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startGattServer() {
        if (destroyed || gattServer != null) return
        gattServiceRegistered = false
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is unavailable or disabled")
            reportState(BleSessionState.ERROR, "Bluetooth is unavailable or disabled")
            return
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            Log.e(TAG, "BLE peripheral advertising is not supported")
            reportState(BleSessionState.ERROR, "BLE peripheral advertising is not supported")
            return
        }

        reportState(BleSessionState.STARTING)
        Log.i(TAG, "Opening BLE GATT provisioning server")
        val server = bluetoothManager.openGattServer(this, gattCallback)
        if (server == null) {
            Log.e(TAG, "Unable to open BLE GATT server")
            reportState(BleSessionState.ERROR, "Unable to open BLE GATT server")
            scheduleGattRetry()
            return
        }
        gattServer = server
        val service = BluetoothGattService(
            BleProvisioningContract.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val legacyCredentials = BluetoothGattCharacteristic(
            BleProvisioningContract.CREDENTIALS_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val deviceInfo = BluetoothGattCharacteristic(
            BleProvisioningContract.DEVICE_INFO_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val sessionCommand = BluetoothGattCharacteristic(
            BleProvisioningContract.SESSION_COMMAND_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val sessionStatus = BluetoothGattCharacteristic(
            BleProvisioningContract.SESSION_STATUS_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleProvisioningContract.CLIENT_CHARACTERISTIC_CONFIG_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
            value = BleSessionProtocol.encodeStatus(currentProtocolStatus)
        }
        sessionStatusCharacteristic = sessionStatus
        service.addCharacteristic(legacyCredentials)
        service.addCharacteristic(deviceInfo)
        service.addCharacteristic(sessionCommand)
        service.addCharacteristic(sessionStatus)
        if (!server.addService(service)) {
            gattServiceRegistered = false
            Log.e(TAG, "Android rejected BLE GATT service registration")
            server.close()
            gattServer = null
            sessionStatusCharacteristic = null
            reportState(BleSessionState.ERROR, "Android rejected BLE GATT service registration")
            scheduleGattRetry()
        }
    }

    private fun startAdvertising() {
        if (
            destroyed || suspended || !gattServiceRegistered || advertising ||
            advertisingStarting || bleClientConnected
        ) return
        val advertiser = bluetoothManager.adapter?.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE advertiser is unavailable")
            reportState(BleSessionState.ERROR, "BLE advertiser is unavailable")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleProvisioningContract.SERVICE_UUID))
            .build()
        reportState(BleSessionState.STARTING)
        advertisingStarting = true
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun restartAdvertising(reason: String) {
        if (destroyed || suspended || bleClientConnected) return
        Log.i(TAG, "Restarting BLE advertising reason=$reason")
        cancelWatchdog()
        reportState(BleSessionState.STARTING)
        advertising = false
        advertisingStarting = false
        val advertiser = bluetoothManager.adapter?.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "BLE advertiser unavailable during restart reason=$reason")
            reportState(BleSessionState.ERROR, "BLE advertiser unavailable during $reason")
            return
        }
        runCatching { advertiser.stopAdvertising(advertiseCallback) }
        mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
        mainHandler.postAtTime(
            { startAdvertising() },
            RESTART_TOKEN,
            android.os.SystemClock.uptimeMillis() + ADVERTISE_RESTART_DELAY_MS,
        )
    }

    private fun scheduleGattRetry() {
        if (destroyed || bluetoothManager.adapter?.isEnabled != true) return
        mainHandler.postDelayed(
            {
                if (!destroyed && bluetoothManager.adapter?.isEnabled == true) {
                    startGattServer()
                }
            },
            GATT_RETRY_MS,
        )
    }

    private fun scheduleWatchdog() {
        cancelWatchdog()
        if (!destroyed && runtimeState == BleSessionState.READY) {
            mainHandler.postDelayed(advertisingWatchdog, ADVERTISE_WATCHDOG_INTERVAL_MS)
        }
    }

    private fun cancelWatchdog() {
        mainHandler.removeCallbacks(advertisingWatchdog)
    }

    private fun resumeBleRuntime(reason: String) {
        if (destroyed) return
        val wasSuspended = suspended
        suspended = false
        if (wasSuspended && currentProtocolStatus.error.isPhysicalStatusError()) {
            publishStatus(BleSessionStatus(null, BleProtocolState.STOPPED))
            Log.i(TAG, "Cleared stale physical shutdown status before BLE resume")
        }
        if (gattServer == null) {
            startGattServer()
        } else if (gattServiceRegistered && !bleClientConnected) {
            if (wasSuspended) Log.i(TAG, "Resuming BLE advertising reason=$reason")
            startAdvertising()
        }
    }

    private fun suspendBleRuntime(reason: String) {
        if (destroyed) return
        Log.i(TAG, "Suspending BLE runtime reason=$reason while preserving GATT database")
        suspended = true
        cancelWatchdog()
        mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
        runCatching {
            bluetoothManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        }
        advertising = false
        advertisingStarting = false
        connectedDevices.values.toList().forEach { device ->
            runCatching { gattServer?.cancelConnection(device) }
        }
        connectedDevices.clear()
        subscribedDevices.clear()
        bleClientConnected = false
        reportState(BleSessionState.OFF)
    }

    private fun stopBleRuntime(
        reason: String,
        terminalState: BleSessionState,
        error: String? = null,
    ) {
        Log.i(TAG, "Stopping BLE runtime reason=$reason terminalState=$terminalState")
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            bluetoothManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        }
        gattServer?.close()
        gattServer = null
        gattServiceRegistered = false
        sessionStatusCharacteristic = null
        connectedDevices.clear()
        subscribedDevices.clear()
        advertising = false
        advertisingStarting = false
        bleClientConnected = false
        suspended = false
        launchWakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        launchWakeLock = null
        reportState(terminalState, error)
    }

    private fun reportState(state: BleSessionState, error: String? = null) {
        runtimeState = state
        publishState(state, error)
    }

    private fun launchSavedConfiguration(reason: String) {
        runCatching { BleProvisioningContract.readCredentials(applicationContext) }
            .onSuccess { credentials ->
                if (credentials == null) {
                    Log.i(TAG, "No saved provisioning credentials reason=$reason")
                } else {
                    launchConfiguration(credentials, reason)
                }
            }
            .onFailure { error ->
                Log.w(TAG, "Saved credentials unavailable reason=$reason", error)
            }
    }

    @Suppress("DEPRECATION")
    private fun launchConfiguration(
        credentials: BleProvisioningContract.Credentials,
        reason: String,
    ) {
        Log.i(TAG, "Launching stream reason=$reason ssid=${credentials.ssid}")
        runCatching {
            launchWakeLock?.let { lock -> if (lock.isHeld) lock.release() }
            launchWakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "$packageName:ble-launch",
            )
            launchWakeLock?.acquire(WAKE_TIMEOUT_MS)
        }.onFailure { error -> Log.w(TAG, "Wake request failed", error) }

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(BleProvisioningContract.EXTRA_WIFI_SSID, credentials.ssid)
            putExtra(BleProvisioningContract.EXTRA_WIFI_PASSWORD, credentials.password)
        }
        runCatching { startActivity(activityIntent) }
            .onFailure { error -> Log.e(TAG, "Unable to launch streaming activity", error) }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Glasses connection",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val TAG = "GlassesBLE"
        private const val CHANNEL_ID = "glasses_ble_connection"
        private const val NOTIFICATION_ID = 201
        private const val WAKE_TIMEOUT_MS = 20_000L
        private const val ADVERTISE_RESTART_DELAY_MS = 400L
        private const val ADVERTISE_FAILURE_RETRY_MS = 2_000L
        private const val ADVERTISE_WATCHDOG_INTERVAL_MS = 15_000L
        private const val GATT_RETRY_MS = 2_000L
        private const val TERMINAL_STATUS_DELAY_MS = 250L
        private const val ACTION_LAUNCH_SAVED = "ble.launch_saved"
        private val RESTART_TOKEN = Any()
        @Volatile
        private var publishedState = BleSessionState.OFF
        @Volatile
        private var publishedError: String? = null
        @Volatile
        private var stateListener: ((BleSessionState, String?) -> Unit)? = null
        @Volatile
        private var activeInstance: BleProvisioningService? = null

        private fun publishState(state: BleSessionState, error: String?) {
            publishedState = state
            publishedError = error
            stateListener?.invoke(state, error)
        }

        fun setStateListener(listener: (BleSessionState, String?) -> Unit) {
            stateListener = listener
            listener(publishedState, publishedError)
        }

        fun clearStateListener(listener: (BleSessionState, String?) -> Unit) {
            if (stateListener === listener) {
                stateListener = null
            }
        }

        fun start(context: Context, launchSaved: Boolean = false) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BleProvisioningService::class.java).apply {
                    if (launchSaved) action = ACTION_LAUNCH_SAVED
                },
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleProvisioningService::class.java))
        }

        fun notifyPhysicalShutdown(error: BleProtocolError): Boolean {
            val service = activeInstance ?: return false
            service.mainHandler.post {
                if (service.destroyed) return@post
                service.publishStatus(
                    BleSessionStatus(
                        requestId = null,
                        state = BleProtocolState.STOPPED,
                        error = error,
                    ),
                )
                service.mainHandler.postDelayed(
                    {
                        if (!service.destroyed) {
                            service.suspendBleRuntime("physical-shutdown-${error.name.lowercase()}")
                        }
                    },
                    TERMINAL_STATUS_DELAY_MS,
                )
            }
            return true
        }

        fun notifyPhysicalReady(): Boolean {
            val service = activeInstance ?: return false
            service.mainHandler.post {
                if (
                    !service.destroyed &&
                    service.currentProtocolStatus.error.isPhysicalStatusError()
                ) {
                    service.publishStatus(BleSessionStatus(null, BleProtocolState.STOPPED))
                    Log.i(TAG, "Cleared physical availability status")
                }
            }
            return true
        }

        fun publishSessionStatus(
            requestId: String,
            state: BleProtocolState,
            error: BleProtocolError? = null,
        ): Boolean {
            val service = activeInstance ?: return false
            service.mainHandler.post {
                if (!service.destroyed) {
                    service.publishStatus(BleSessionStatus(requestId, state, error))
                }
            }
            return true
        }
    }
}

private fun BleProtocolError?.isPhysicalStatusError(): Boolean =
    this == BleProtocolError.GLASSES_FOLDED ||
        this == BleProtocolError.GLASSES_NOT_WORN ||
        this == BleProtocolError.PHYSICAL_STATE_UNKNOWN

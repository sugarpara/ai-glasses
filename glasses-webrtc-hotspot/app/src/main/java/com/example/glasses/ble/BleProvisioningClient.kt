package com.example.glasses.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.util.UUID

@SuppressLint("MissingPermission")
class BleProvisioningClient(
    context: Context,
    private val onStateChanged: (State) -> Unit,
) {
    sealed interface State {
        data object Idle : State
        data object Scanning : State
        data object Connecting : State
        data object Discovering : State
        data object Negotiating : State
        data object Subscribing : State
        data class Ready(
            val deviceInfo: BleDeviceInfo,
            val status: BleSessionStatus,
        ) : State

        data class Sending(
            val command: BleSessionCommandType,
            val requestId: String,
        ) : State

        data class CommandTransferred(
            val command: BleSessionCommandType,
            val requestId: String,
        ) : State

        data class StatusChanged(
            val deviceInfo: BleDeviceInfo,
            val status: BleSessionStatus,
        ) : State

        data class Error(val message: String) : State
    }

    private data class PendingCommand(
        val type: BleSessionCommandType,
        val requestId: String,
        val ssid: String? = null,
        val password: String? = null,
    )

    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(BluetoothManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var scannerCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var deviceInfoCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var deviceInfo: BleDeviceInfo? = null
    private var latestStatus: BleSessionStatus? = null
    private var pendingCommand: PendingCommand? = null
    private var activeRequestId: String? = null
    private var activeCommandType: BleSessionCommandType? = null
    private var statusReceivedForActiveRequest = false
    private var negotiatedMtu = DEFAULT_MTU
    private var notificationsEnabled = false
    private var physicalShutdownExpected = false
    private var closed = true
    private var timeoutTask: Runnable? = null
    private var retryTask: Runnable? = null
    private var serviceDiscoveryRetryCount = 0

    fun connect() {
        close()
        closed = false
        startScan()
    }

    fun startSession() {
        enqueueCommand(PendingCommand(BleSessionCommandType.START_SESSION, newRequestId()))
    }

    fun stopSession() {
        enqueueCommand(PendingCommand(BleSessionCommandType.STOP_SESSION, newRequestId()))
    }

    fun heartbeat() {
        enqueueCommand(PendingCommand(BleSessionCommandType.HEARTBEAT, newRequestId()))
    }

    fun provisionAndStart(ssid: String, password: String) {
        if (ssid.isBlank()) return reject("热点名称不能为空")
        if (password.length !in 8..63) return reject("热点密码必须为 8 到 63 个字符")
        enqueueCommand(
            PendingCommand(
                type = BleSessionCommandType.PROVISION_AND_START,
                requestId = newRequestId(),
                ssid = ssid,
                password = password,
            ),
        )
    }

    fun close() {
        closed = true
        clearTimeout()
        clearRetry()
        stopScan()
        val previousGatt = gatt
        gatt = null
        runCatching { previousGatt?.disconnect() }
        runCatching { previousGatt?.close() }
        deviceInfoCharacteristic = null
        commandCharacteristic = null
        statusCharacteristic = null
        deviceInfo = null
        latestStatus = null
        pendingCommand = null
        activeRequestId = null
        activeCommandType = null
        statusReceivedForActiveRequest = false
        negotiatedMtu = DEFAULT_MTU
        notificationsEnabled = false
        physicalShutdownExpected = false
        serviceDiscoveryRetryCount = 0
    }

    private fun enqueueCommand(command: PendingCommand) {
        if (isReady()) {
            writeCommand(command)
            return
        }
        close()
        closed = false
        pendingCommand = command
        startScan()
    }

    private fun startScan() {
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) return reject("请先打开手机蓝牙")
        val scanner = adapter.bluetoothLeScanner ?: return reject("当前手机不支持 BLE 扫描")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (closed) return
                stopScan()
                connectGatt(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                fail("BLE 扫描失败，错误码 $errorCode")
            }
        }
        scannerCallback = callback
        publish(State.Scanning)
        scanner.startScan(
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(BleSessionUuids.SERVICE))
                    .build(),
            ),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            callback,
        )
    }

    @Suppress("DEPRECATION")
    private fun connectGatt(device: BluetoothDevice) {
        clearTimeout()
        publish(State.Connecting)
        gatt = device.connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            mainHandler,
        )
        scheduleTimeout("连接眼镜蓝牙超时")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            if (closed || callbackGatt !== gatt) return
            if (newState == BluetoothProfile.STATE_DISCONNECTED && physicalShutdownExpected) {
                Log.i(TAG, "BLE disconnected after physical shutdown")
                close()
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED && hasRecoverableGattWork()) {
                retryServiceDiscovery(
                    if (activeRequestId != null) {
                        "眼镜蓝牙连接中断，正在恢复会话状态"
                    } else {
                        "眼镜蓝牙连接中断，正在恢复命令传输"
                    },
                )
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("蓝牙连接失败，状态码 $status")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    clearTimeout()
                    publish(State.Discovering)
                    if (!callbackGatt.discoverServices()) fail("无法发现眼镜 BLE 服务")
                    else scheduleTimeout("发现眼镜 BLE 服务超时")
                }

                BluetoothProfile.STATE_DISCONNECTED -> fail("眼镜蓝牙连接已断开")
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            if (closed || callbackGatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("BLE 服务发现失败，状态码 $status")
                return
            }
            val service = callbackGatt.getService(BleSessionUuids.SERVICE)
                ?: return retryServiceDiscovery("眼镜未提供 BLE v2 服务")
            deviceInfoCharacteristic = service.getCharacteristic(BleSessionUuids.DEVICE_INFO)
                ?: return retryServiceDiscovery("眼镜未提供设备信息特征")
            commandCharacteristic = service.getCharacteristic(BleSessionUuids.SESSION_COMMAND)
                ?: return retryServiceDiscovery("眼镜未提供会话命令特征")
            statusCharacteristic = service.getCharacteristic(BleSessionUuids.SESSION_STATUS)
                ?: return retryServiceDiscovery("眼镜未提供会话状态特征")
            if (statusCharacteristic
                    ?.getDescriptor(BleSessionUuids.CLIENT_CHARACTERISTIC_CONFIG) == null
            ) {
                retryServiceDiscovery("眼镜未提供状态通知描述符")
                return
            }
            serviceDiscoveryRetryCount = 0
            clearTimeout()
            publish(State.Negotiating)
            if (!callbackGatt.requestMtu(REQUESTED_MTU)) fail("无法协商 BLE 数据长度")
            else scheduleTimeout("BLE MTU 协商超时")
        }

        override fun onMtuChanged(callbackGatt: BluetoothGatt, mtu: Int, status: Int) {
            if (closed || callbackGatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                retryServiceDiscovery("BLE MTU 协商失败，状态码 $status")
                return
            }
            negotiatedMtu = mtu
            clearTimeout()
            val target = deviceInfoCharacteristic
                ?: return retryServiceDiscovery("设备信息特征不可用")
            if (!callbackGatt.readCharacteristic(target)) {
                retryServiceDiscovery("无法读取眼镜设备信息")
            } else scheduleTimeout("读取眼镜设备信息超时")
        }

        @Deprecated("Legacy Android callback")
        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handleCharacteristicRead(callbackGatt, characteristic, characteristic.value, status)
        }

        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleCharacteristicRead(callbackGatt, characteristic, value, status)
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (closed || callbackGatt !== gatt) return
            if (descriptor.uuid != BleSessionUuids.CLIENT_CHARACTERISTIC_CONFIG) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                retryServiceDiscovery("开启眼镜状态通知失败，状态码 $status")
                return
            }
            notificationsEnabled = true
            clearTimeout()
            val target = statusCharacteristic
                ?: return retryServiceDiscovery("会话状态特征不可用")
            if (!callbackGatt.readCharacteristic(target)) {
                retryServiceDiscovery("无法读取眼镜当前状态")
            } else scheduleTimeout("读取眼镜当前状态超时")
        }

        @Deprecated("Legacy Android callback")
        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleStatusNotification(callbackGatt, characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleStatusNotification(callbackGatt, characteristic, value)
        }

        override fun onCharacteristicWrite(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (closed || callbackGatt !== gatt ||
                characteristic.uuid != BleSessionUuids.SESSION_COMMAND
            ) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("眼镜未接收会话命令，状态码 $status")
                return
            }
            val requestId = activeRequestId ?: return
            Log.i(TAG, "BLE command transferred requestId=$requestId")
            if (!statusReceivedForActiveRequest) {
                publish(
                    State.CommandTransferred(
                        activeCommandType ?: BleSessionCommandType.HEARTBEAT,
                        requestId,
                    ),
                )
            }
        }
    }

    private fun handleCharacteristicRead(
        callbackGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (closed || callbackGatt !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            retryServiceDiscovery("读取眼镜 BLE 数据失败，状态码 $status")
            return
        }
        clearTimeout()
        when (characteristic.uuid) {
            BleSessionUuids.DEVICE_INFO -> {
                val info = runCatching { BleSessionProtocol.decodeDeviceInfo(value) }
                    .getOrElse { return fail("眼镜设备信息格式无效") }
                if (info.protocolVersion != BleSessionProtocol.VERSION) {
                    fail("眼镜 BLE 协议版本不兼容")
                    return
                }
                deviceInfo = info
                enableStatusNotifications(callbackGatt)
            }

            BleSessionUuids.SESSION_STATUS -> {
                val statusValue = runCatching { BleSessionProtocol.decodeStatus(value) }
                    .getOrElse { return fail("眼镜状态格式无效") }
                serviceDiscoveryRetryCount = 0
                latestStatus = statusValue
                publishReadyOrStatus(statusValue, initial = true)
                flushPendingCommand()
            }
        }
    }

    private fun enableStatusNotifications(callbackGatt: BluetoothGatt) {
        val characteristic = statusCharacteristic
            ?: return retryServiceDiscovery("会话状态特征不可用")
        val descriptor = characteristic.getDescriptor(BleSessionUuids.CLIENT_CHARACTERISTIC_CONFIG)
            ?: return retryServiceDiscovery("会话状态通知描述符不可用")
        publish(State.Subscribing)
        if (!callbackGatt.setCharacteristicNotification(characteristic, true)) {
            retryServiceDiscovery("无法启用眼镜状态通知")
            return
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            callbackGatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            callbackGatt.writeDescriptor(descriptor)
        }
        if (!started) retryServiceDiscovery("状态通知订阅没有启动")
        else scheduleTimeout("状态通知订阅超时")
    }

    private fun handleStatusNotification(
        callbackGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (closed || callbackGatt !== gatt || characteristic.uuid != BleSessionUuids.SESSION_STATUS) {
            return
        }
        val status = runCatching { BleSessionProtocol.decodeStatus(value) }
            .getOrElse { return fail("眼镜状态通知格式无效") }
        latestStatus = status
        if (status.requestId != null && status.requestId == activeRequestId) {
            statusReceivedForActiveRequest = true
            clearTimeout()
        }
        Log.i(
            TAG,
            "BLE status requestId=${status.requestId ?: "none"} " +
                "state=${status.state} error=${status.error ?: "none"}",
        )
        if (status.error.isPhysicalShutdown()) {
            physicalShutdownExpected = true
        }
        publishReadyOrStatus(status, initial = false)
        if (physicalShutdownExpected) {
            mainHandler.postDelayed(
                {
                    if (!closed && callbackGatt === gatt && physicalShutdownExpected) {
                        Log.i(TAG, "Closing BLE after physical shutdown status")
                        close()
                    }
                },
                PHYSICAL_SHUTDOWN_CLOSE_DELAY_MS,
            )
        }
    }

    private fun publishReadyOrStatus(status: BleSessionStatus, initial: Boolean) {
        val info = deviceInfo ?: return
        publish(if (initial) State.Ready(info, status) else State.StatusChanged(info, status))
    }

    private fun flushPendingCommand() {
        val command = pendingCommand ?: return
        pendingCommand = null
        writeCommand(command)
    }

    private fun writeCommand(command: PendingCommand) {
        val callbackGatt = gatt ?: return fail("眼镜蓝牙尚未连接")
        val characteristic = commandCharacteristic ?: return fail("会话命令特征不可用")
        if (!notificationsEnabled) return fail("必须先订阅眼镜状态通知")
        val payload = runCatching {
            BleSessionProtocol.encodeCommand(
                command.type,
                command.requestId,
                command.ssid,
                command.password,
            )
        }.getOrElse { return fail(it.message ?: "会话命令无效") }
        if (payload.size > negotiatedMtu - ATT_HEADER_BYTES) {
            fail("会话命令过长，当前 BLE 最大长度为 ${negotiatedMtu - ATT_HEADER_BYTES} 字节")
            return
        }
        // A freshly issued command supersedes any delayed close from a stale
        // physical-shutdown status received while subscribing.
        physicalShutdownExpected = false
        activeRequestId = command.requestId
        activeCommandType = command.type
        statusReceivedForActiveRequest = false
        publish(State.Sending(command.type, command.requestId))
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            callbackGatt.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = payload
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            callbackGatt.writeCharacteristic(characteristic)
        }
        if (!started) fail("会话命令写入没有启动")
        else scheduleTimeout("眼镜未返回对应的状态通知")
    }

    private fun isReady(): Boolean =
        !closed && gatt != null && commandCharacteristic != null && notificationsEnabled

    private fun stopScan() {
        val callback = scannerCallback ?: return
        runCatching { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback) }
        scannerCallback = null
    }

    private fun reject(message: String) {
        publish(State.Error(message))
    }

    private fun scheduleTimeout(message: String) {
        clearTimeout()
        timeoutTask = Runnable { fail(message) }
            .also { mainHandler.postDelayed(it, OPERATION_TIMEOUT_MS) }
    }

    private fun clearTimeout() {
        timeoutTask?.let(mainHandler::removeCallbacks)
        timeoutTask = null
    }

    private fun retryServiceDiscovery(message: String) {
        if (closed) return
        if (serviceDiscoveryRetryCount >= MAX_SERVICE_DISCOVERY_RETRIES) {
            fail(message)
            return
        }
        serviceDiscoveryRetryCount++
        Log.w(
            TAG,
            "$message; retrying GATT discovery " +
                "$serviceDiscoveryRetryCount/$MAX_SERVICE_DISCOVERY_RETRIES",
        )
        clearTimeout()
        clearRetry()
        stopScan()
        val previousGatt = gatt
        gatt = null
        runCatching { previousGatt?.disconnect() }
        refreshGattCache(previousGatt)
        runCatching { previousGatt?.close() }
        deviceInfoCharacteristic = null
        commandCharacteristic = null
        statusCharacteristic = null
        deviceInfo = null
        latestStatus = null
        negotiatedMtu = DEFAULT_MTU
        notificationsEnabled = false
        physicalShutdownExpected = false
        publish(State.Scanning)
        retryTask = Runnable {
            retryTask = null
            if (!closed) startScan()
        }.also { mainHandler.postDelayed(it, SERVICE_DISCOVERY_RETRY_DELAY_MS) }
    }

    private fun hasRecoverableGattWork(): Boolean =
        pendingCommand != null || activeRequestId != null

    private fun clearRetry() {
        retryTask?.let(mainHandler::removeCallbacks)
        retryTask = null
    }

    private fun refreshGattCache(callbackGatt: BluetoothGatt?) {
        if (callbackGatt == null) return
        runCatching {
            val refreshMethod = callbackGatt.javaClass.getMethod("refresh")
            val refreshed = refreshMethod.invoke(callbackGatt) as? Boolean == true
            Log.i(TAG, "GATT cache refresh requested success=$refreshed")
        }.onFailure { error ->
            Log.w(TAG, "GATT cache refresh unavailable", error)
        }
    }

    private fun fail(message: String) {
        if (closed) return
        Log.e(TAG, message)
        close()
        publish(State.Error(message))
    }

    private fun publish(state: State) {
        mainHandler.post { onStateChanged(state) }
    }

    private fun newRequestId(): String = UUID.randomUUID().toString().substring(0, 8)

    private companion object {
        const val TAG = "PhoneBLE"
        const val DEFAULT_MTU = 23
        const val REQUESTED_MTU = 247
        const val ATT_HEADER_BYTES = 3
        const val OPERATION_TIMEOUT_MS = 30_000L
        const val PHYSICAL_SHUTDOWN_CLOSE_DELAY_MS = 300L
        const val MAX_SERVICE_DISCOVERY_RETRIES = 3
        const val SERVICE_DISCOVERY_RETRY_DELAY_MS = 1_000L
    }
}

private fun BleProtocolError?.isPhysicalShutdown(): Boolean =
    this == BleProtocolError.GLASSES_FOLDED || this == BleProtocolError.GLASSES_NOT_WORN

internal object BleProvisioningPayload {
    fun encode(ssid: String, password: String): ByteArray {
        require(ssid.isNotBlank()) { "SSID must not be blank" }
        require(password.length in 8..63) { "WPA2 password must contain 8 to 63 characters" }
        return JSONObject()
            .put("version", 1)
            .put("ssid", ssid)
            .put("password", password)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }
}

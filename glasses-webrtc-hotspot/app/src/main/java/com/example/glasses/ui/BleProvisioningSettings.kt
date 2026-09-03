package com.example.glasses.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.glasses.ble.BleDeviceInfo
import com.example.glasses.ble.BleProtocolError
import com.example.glasses.ble.BleProtocolState
import com.example.glasses.ble.BleProvisioningClient
import com.example.glasses.ble.BleSessionStatus
import com.example.glasses.ui.theme.AppMutedText
import com.example.glasses.ui.theme.AppRed
import com.example.glasses.webrtc.LocalSignalingServer

@Composable
internal fun BleProvisioningSettings() {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(BLE_PREFS, Context.MODE_PRIVATE)
    }
    var hotspotSsid by remember {
        mutableStateOf(preferences.getString(BLE_SSID, "").orEmpty())
    }
    var hotspotPassword by remember { mutableStateOf("") }
    var bleState by remember {
        mutableStateOf<BleProvisioningClient.State>(BleProvisioningClient.State.Idle)
    }
    var deviceInfo by remember { mutableStateOf<BleDeviceInfo?>(null) }
    var sessionStatus by remember { mutableStateOf<BleSessionStatus?>(null) }
    var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var signalingReady by remember { mutableStateOf(false) }
    val bleClient = remember(context) {
        BleProvisioningClient(context.applicationContext) { state ->
            bleState = state
            when (state) {
                is BleProvisioningClient.State.Ready -> {
                    deviceInfo = state.deviceInfo
                    sessionStatus = state.status
                }

                is BleProvisioningClient.State.StatusChanged -> {
                    sessionStatus = state.status
                    deviceInfo = if (state.status.error.isPhysicalShutdown()) {
                        null
                    } else {
                        state.deviceInfo
                    }
                }

                is BleProvisioningClient.State.Error -> {
                    deviceInfo = null
                    sessionStatus = null
                }

                else -> Unit
            }
        }
    }
    val signalingServer = remember(context) {
        LocalSignalingServer(
            context.applicationContext,
            PROVISIONING_SIGNALING_PORT,
            { error ->
                signalingReady = false
                bleState = BleProvisioningClient.State.Error(
                    "无法启动手机信令端口: ${error.message ?: error.javaClass.simpleName}",
                )
            },
            object : LocalSignalingServer.ListeningListener {
                override fun onListening() {
                    signalingReady = true
                }
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (result.values.all { it }) {
            action?.invoke()
        } else {
            bleState = BleProvisioningClient.State.Error("需要蓝牙权限才能连接眼镜")
        }
    }

    fun runWithBlePermissions(action: () -> Unit) {
        val missingPermissions = requiredBlePermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            action()
        } else {
            pendingPermissionAction = action
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    LaunchedEffect(sessionStatus) {
        if (sessionStatus?.state == BleProtocolState.CREDENTIALS_ACCEPTED) {
            hotspotPassword = ""
        }
    }

    DisposableEffect(bleClient) {
        onDispose { bleClient.close() }
    }

    DisposableEffect(signalingServer) {
        signalingServer.start()
        onDispose { signalingServer.stop() }
    }

    val busy = bleState.isBusy()
    val connected = deviceInfo != null

    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { runWithBlePermissions(bleClient::connect) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.BluetoothSearching, contentDescription = null)
            Text(
                text = if (connected) "重新连接眼镜" else "连接眼镜",
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (connected) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                OutlinedButton(
                    onClick = { runWithBlePermissions(bleClient::heartbeat) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("同步状态", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(
                    onClick = { runWithBlePermissions(bleClient::stopSession) },
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text("停止会话", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        deviceInfo?.let { info ->
            Text(
                text = "BLE v${info.protocolVersion} · 眼镜 ${info.appVersion} (${info.versionCode})",
                color = AppMutedText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
        Text(
            text = bleState.displayText(sessionStatus),
            color = if (
                bleState is BleProvisioningClient.State.Error || sessionStatus?.error != null
            ) {
                AppRed
            } else {
                AppMutedText
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 5.dp),
        )

        OutlinedTextField(
            value = hotspotSsid,
            onValueChange = { hotspotSsid = it },
            label = { Text("手机热点名称") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )
        OutlinedTextField(
            value = hotspotPassword,
            onValueChange = { hotspotPassword = it },
            label = { Text("手机热点密码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Button(
            onClick = {
                preferences.edit().putString(BLE_SSID, hotspotSsid).apply()
                runWithBlePermissions {
                    bleClient.provisionAndStart(hotspotSsid, hotspotPassword)
                }
            },
            enabled = signalingReady &&
                !busy &&
                hotspotSsid.isNotBlank() &&
                hotspotPassword.length in 8..63,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            Text("发送并等待眼镜状态", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun BleProvisioningClient.State.isBusy(): Boolean = when (this) {
    BleProvisioningClient.State.Scanning,
    BleProvisioningClient.State.Connecting,
    BleProvisioningClient.State.Discovering,
    BleProvisioningClient.State.Negotiating,
    BleProvisioningClient.State.Subscribing,
    is BleProvisioningClient.State.Sending,
    -> true

    BleProvisioningClient.State.Idle,
    is BleProvisioningClient.State.Ready,
    is BleProvisioningClient.State.CommandTransferred,
    is BleProvisioningClient.State.StatusChanged,
    is BleProvisioningClient.State.Error,
    -> false
}

private fun BleProvisioningClient.State.displayText(status: BleSessionStatus?): String = when (this) {
    BleProvisioningClient.State.Idle -> "尚未连接"
    BleProvisioningClient.State.Scanning -> "正在搜索眼镜"
    BleProvisioningClient.State.Connecting -> "正在建立蓝牙连接"
    BleProvisioningClient.State.Discovering -> "正在读取 BLE v2 服务"
    BleProvisioningClient.State.Negotiating -> "正在协商蓝牙数据长度"
    BleProvisioningClient.State.Subscribing -> "正在订阅眼镜状态"
    is BleProvisioningClient.State.Ready -> status.displayText("已连接")
    is BleProvisioningClient.State.Sending -> "正在传输命令 · $requestId"
    is BleProvisioningClient.State.CommandTransferred -> "命令已传输，等待状态 · $requestId"
    is BleProvisioningClient.State.StatusChanged -> status.displayText("状态已更新")
    is BleProvisioningClient.State.Error -> message
}

private fun BleSessionStatus?.displayText(prefix: String): String {
    if (this == null) return prefix
    val stateText = when (state) {
        BleProtocolState.COMMAND_RECEIVED -> "眼镜已接收命令"
        BleProtocolState.CREDENTIALS_ACCEPTED -> "眼镜已接收热点参数，尚未联网"
        BleProtocolState.WIFI_ENABLING -> "眼镜正在开启 Wi-Fi"
        BleProtocolState.WIFI_CONNECTING -> "眼镜正在连接热点"
        BleProtocolState.WIFI_READY -> "眼镜 Wi-Fi 已连接"
        BleProtocolState.PHONE_WAITING -> "眼镜正在等待手机信令"
        BleProtocolState.SIGNALING_CONNECTED -> "手机信令已连接"
        BleProtocolState.STREAMING -> "眼镜视频流已启动"
        BleProtocolState.STOPPED -> "眼镜会话已停止"
    }
    val errorText = error?.displayText()
    val requestText = requestId?.let { " · $it" }.orEmpty()
    return if (errorText == null) "$stateText$requestText" else "$errorText$requestText"
}

private fun BleProtocolError.displayText(): String = when (this) {
    BleProtocolError.INVALID_COMMAND -> "眼镜拒绝了无效命令"
    BleProtocolError.INVALID_CREDENTIALS -> "热点名称或密码无效"
    BleProtocolError.PERMISSION_MISSING -> "眼镜缺少所需权限"
    BleProtocolError.GLASSES_FOLDED -> "眼镜镜腿已折叠"
    BleProtocolError.GLASSES_NOT_WORN -> "眼镜尚未佩戴"
    BleProtocolError.PHYSICAL_STATE_UNKNOWN ->
        "眼镜状态未知，请摘下折叠后重新展开佩戴"
    BleProtocolError.WIFI_ENABLE_REJECTED -> "眼镜无法开启 Wi-Fi"
    BleProtocolError.WIFI_CONFIGURATION_REJECTED -> "眼镜无法保存热点配置"
    BleProtocolError.WIFI_AUTH_FAILED -> "热点认证失败"
    BleProtocolError.WIFI_TIMEOUT -> "眼镜连接热点超时"
    BleProtocolError.NO_IPV4_GATEWAY -> "眼镜没有获得手机网关"
    BleProtocolError.PHONE_SIGNALING_UNREACHABLE -> "眼镜无法连接手机信令服务"
    BleProtocolError.CAMERA_START_FAILED -> "眼镜相机启动失败"
    BleProtocolError.WEBRTC_FAILED -> "眼镜视频连接失败"
}

private fun BleProtocolError?.isPhysicalShutdown(): Boolean =
    this == BleProtocolError.GLASSES_FOLDED || this == BleProtocolError.GLASSES_NOT_WORN

private fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private const val BLE_PREFS = "phone_ble_provisioning"
private const val BLE_SSID = "ssid"
private const val PROVISIONING_SIGNALING_PORT = 8888

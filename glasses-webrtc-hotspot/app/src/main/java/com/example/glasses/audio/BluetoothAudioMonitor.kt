package com.example.glasses.audio

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BluetoothAudioOutput(
    val connected: Boolean,
    val deviceName: String? = null,
)

class BluetoothAudioMonitor(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val _output = MutableStateFlow(readOutput())
    val output: StateFlow<BluetoothAudioOutput> = _output.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        audioManager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun refresh() {
        _output.value = readOutput()
    }

    override fun close() {
        runCatching { audioManager?.unregisterAudioDeviceCallback(callback) }
    }

    private fun readOutput(): BluetoothAudioOutput {
        val device = audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { isBluetoothMediaOutputType(it.type) }
        return BluetoothAudioOutput(
            connected = device != null,
            deviceName = device?.productName?.toString()?.takeIf(String::isNotBlank),
        )
    }
}

internal fun isBluetoothMediaOutputType(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    -> true

    else -> false
}

fun openBluetoothAudioRecovery(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

package com.example.glasses.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAudioMonitorTest {
    @Test
    fun bluetoothMediaOutputsAreRecognized() {
        assertTrue(isBluetoothMediaOutputType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(isBluetoothMediaOutputType(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertTrue(isBluetoothMediaOutputType(AudioDeviceInfo.TYPE_BLE_SPEAKER))
    }

    @Test
    fun phoneAndWiredOutputsAreNotBluetoothMediaOutputs() {
        assertFalse(isBluetoothMediaOutputType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(isBluetoothMediaOutputType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
    }
}

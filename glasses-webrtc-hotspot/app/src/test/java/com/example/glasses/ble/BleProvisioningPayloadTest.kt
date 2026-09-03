package com.example.glasses.ble

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BleProvisioningPayloadTest {
    @Test
    fun encodeMatchesGlassesProvisioningContract() {
        val payload = JSONObject(
            BleProvisioningPayload.encode("MVP-Hotspot", "test-password").toString(Charsets.UTF_8),
        )

        assertEquals(1, payload.getInt("version"))
        assertEquals("MVP-Hotspot", payload.getString("ssid"))
        assertEquals("test-password", payload.getString("password"))
    }

    @Test
    fun encodeRejectsInvalidCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            BleProvisioningPayload.encode("", "test-password")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BleProvisioningPayload.encode("MVP-Hotspot", "short")
        }
    }
}

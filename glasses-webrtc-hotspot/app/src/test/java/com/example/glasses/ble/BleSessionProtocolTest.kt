package com.example.glasses.ble

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSessionProtocolTest {
    @Test
    fun startCommandIncludesRequestId() {
        val json = JSONObject(
            BleSessionProtocol.encodeCommand(
                BleSessionCommandType.START_SESSION,
                "req-100",
            ).toString(Charsets.UTF_8),
        )

        assertEquals(2, json.getInt("version"))
        assertEquals("req-100", json.getString("requestId"))
        assertEquals("START_SESSION", json.getString("command"))
    }

    @Test
    fun provisioningCommandValidatesCredentials() {
        val payload = BleSessionProtocol.encodeCommand(
            BleSessionCommandType.PROVISION_AND_START,
            "req-101",
            "MVP-Hotspot",
            "test-password",
        )
        assertEquals("WPA2", JSONObject(payload.toString(Charsets.UTF_8)).getString("security"))

        assertThrows(IllegalArgumentException::class.java) {
            BleSessionProtocol.encodeCommand(
                BleSessionCommandType.PROVISION_AND_START,
                "req-102",
                "MVP-Hotspot",
                "short",
            )
        }
    }

    @Test
    fun statusDecodePreservesRequestIdAndError() {
        val status = BleSessionProtocol.decodeStatus(
            """{"version":2,"requestId":"req-103","state":"STOPPED","error":"GLASSES_FOLDED"}"""
                .toByteArray(),
        )

        assertEquals("req-103", status.requestId)
        assertEquals(BleProtocolState.STOPPED, status.state)
        assertEquals(BleProtocolError.GLASSES_FOLDED, status.error)
    }

    @Test
    fun deviceInfoDecodeIncludesCapabilities() {
        val info = BleSessionProtocol.decodeDeviceInfo(
            """{"version":2,"appVersion":"2.10","versionCode":22,"capabilities":["START_SESSION","STATUS_NOTIFY"]}"""
                .toByteArray(),
        )

        assertEquals(2, info.protocolVersion)
        assertEquals("2.10", info.appVersion)
        assertTrue("STATUS_NOTIFY" in info.capabilities)
    }

    @Test
    fun nullStatusFieldsDecodeAsNull() {
        val status = BleSessionProtocol.decodeStatus(
            """{"version":2,"requestId":null,"state":"STOPPED","error":null}""".toByteArray(),
        )

        assertNull(status.requestId)
        assertNull(status.error)
    }

    @Test
    fun unknownPhysicalStateErrorDecodes() {
        val status = BleSessionProtocol.decodeStatus(
            """{"version":2,"requestId":"req-104","state":"STOPPED","error":"PHYSICAL_STATE_UNKNOWN"}"""
                .toByteArray(),
        )

        assertEquals(BleProtocolError.PHYSICAL_STATE_UNKNOWN, status.error)
    }
}

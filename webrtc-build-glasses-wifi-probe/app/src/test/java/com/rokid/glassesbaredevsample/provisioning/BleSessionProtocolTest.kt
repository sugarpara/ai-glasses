package com.rokid.glassesbaredevsample.provisioning

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSessionProtocolTest {
    @Test
    fun startSessionPreservesRequestId() {
        val result = BleSessionProtocol.decodeCommand(
            """{"version":2,"requestId":"req-123","command":"START_SESSION"}"""
                .toByteArray(),
        )

        assertEquals(
            BleSessionCommand("req-123", BleSessionCommandType.START_SESSION),
            (result as BleCommandDecodeResult.Success).command,
        )
    }

    @Test
    fun provisioningValidatesCredentialsWithoutLoggingThem() {
        val valid = BleSessionProtocol.decodeCommand(
            """{"version":2,"requestId":"req-2","command":"PROVISION_AND_START","ssid":"MVP-Hotspot","password":"test-password","security":"WPA2"}"""
                .toByteArray(),
        )
        assertTrue(valid is BleCommandDecodeResult.Success)

        val invalid = BleSessionProtocol.decodeCommand(
            """{"version":2,"requestId":"req-3","command":"PROVISION_AND_START","ssid":"MVP-Hotspot","password":"short","security":"WPA2"}"""
                .toByteArray(),
        ) as BleCommandDecodeResult.Failure
        assertEquals("req-3", invalid.requestId)
        assertEquals(BleProtocolError.INVALID_CREDENTIALS, invalid.error)
    }

    @Test
    fun invalidCommandReturnsExplicitError() {
        val result = BleSessionProtocol.decodeCommand(
            """{"version":2,"requestId":"req-4","command":"UNKNOWN"}""".toByteArray(),
        ) as BleCommandDecodeResult.Failure

        assertEquals("req-4", result.requestId)
        assertEquals(BleProtocolError.INVALID_COMMAND, result.error)
    }

    @Test
    fun statusEncodingKeepsRequestIdAndNullError() {
        val json = JSONObject(
            BleSessionProtocol.encodeStatus(
                BleSessionStatus("req-5", BleProtocolState.COMMAND_RECEIVED),
            ).toString(Charsets.UTF_8),
        )

        assertEquals(2, json.getInt("version"))
        assertEquals("req-5", json.getString("requestId"))
        assertEquals("COMMAND_RECEIVED", json.getString("state"))
        assertTrue(json.isNull("error"))
    }

    @Test
    fun initialStatusCanOmitRequestId() {
        val json = JSONObject(
            BleSessionProtocol.encodeStatus(
                BleSessionStatus(null, BleProtocolState.STOPPED),
            ).toString(Charsets.UTF_8),
        )

        assertTrue(json.isNull("requestId"))
        assertNull(json.optString("missing", null))
    }

    @Test
    fun unknownPhysicalStateHasExplicitProtocolError() {
        val json = JSONObject(
            BleSessionProtocol.encodeStatus(
                BleSessionStatus(
                    null,
                    BleProtocolState.STOPPED,
                    BleProtocolError.PHYSICAL_STATE_UNKNOWN,
                ),
            ).toString(Charsets.UTF_8),
        )

        assertEquals("PHYSICAL_STATE_UNKNOWN", json.getString("error"))
    }
}

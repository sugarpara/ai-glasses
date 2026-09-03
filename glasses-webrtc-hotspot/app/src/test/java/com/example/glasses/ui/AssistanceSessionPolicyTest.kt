package com.example.glasses.ui

import com.example.glasses.ble.BleProtocolError
import com.example.glasses.ble.BleProtocolState
import com.example.glasses.ble.BleSessionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistanceSessionPolicyTest {
    @Test
    fun glassesSessionStartsOnlyAfterForegroundSignalingIsReady() {
        assertFalse(shouldBeginGlassesSession(true, true, true, false))
        assertFalse(shouldBeginGlassesSession(false, false, true, false))
        assertFalse(shouldBeginGlassesSession(false, true, false, false))
        assertFalse(shouldBeginGlassesSession(false, true, true, true))
        assertTrue(shouldBeginGlassesSession(false, true, true, false))
    }

    @Test
    fun everyProtocolStageHasVisibleAssistanceText() {
        BleProtocolState.entries.forEach { state ->
            val text = BleSessionStatus("task8", state, null).assistanceText()
            assertTrue("Missing text for $state", text.isNotBlank())
        }
    }

    @Test
    fun everyProtocolErrorHasVisibleAssistanceText() {
        BleProtocolError.entries.forEach { error ->
            val text = error.assistanceText()
            assertTrue("Missing text for $error", text.isNotBlank())
        }
    }
}

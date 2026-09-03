package com.rokid.glassesbaredevsample.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesSessionStateTest {
    @Test
    fun initialStateIsUnknownAndNotReady() {
        val state = GlassesSessionState()

        assertEquals(WearState.UNKNOWN, state.wearState)
        assertEquals(LegState.UNKNOWN, state.legState)
        assertEquals(BleSessionState.OFF, state.bleState)
        assertFalse(state.isOpenAndWorn)
        assertFalse(state.shouldRunBle)
        assertFalse(state.isPhysicalStateKnown)
        assertTrue(state.shouldAdvertiseBle)
        assertEquals(BleProtocolError.PHYSICAL_STATE_UNKNOWN, state.physicalStartError())
    }

    @Test
    fun verifiedRawValuesMapToPhysicalEvents() {
        assertEquals(
            WearFoldEvent.WearChanged(WearState.NOT_WORN),
            WearFoldContract.parse(WearFoldContract.ACTION_TAKE_STATUS_CHANGED, "0"),
        )
        assertEquals(
            WearFoldEvent.WearChanged(WearState.WORN),
            WearFoldContract.parse(WearFoldContract.ACTION_TAKE_STATUS_CHANGED, "1"),
        )
        assertEquals(
            WearFoldEvent.LegChanged(LegState.FOLDED),
            WearFoldContract.parse(WearFoldContract.ACTION_LEG_STATUS_CHANGED, "0"),
        )
        assertEquals(
            WearFoldEvent.LegChanged(LegState.OPEN),
            WearFoldContract.parse(WearFoldContract.ACTION_LEG_STATUS_CHANGED, "1"),
        )
    }

    @Test
    fun unknownValuesAreIgnored() {
        assertNull(
            WearFoldContract.parse(WearFoldContract.ACTION_TAKE_STATUS_CHANGED, "true"),
        )
        assertNull(
            WearFoldContract.parse(WearFoldContract.ACTION_LEG_STATUS_CHANGED, "open"),
        )
        assertNull(WearFoldContract.parse("unknown.action", "1"))
    }

    @Test
    fun physicalShutdownPolicyDebouncesOnlyNotWornEvents() {
        assertEquals(15_000L, WearFoldContract.NOT_WORN_CONFIRMATION_MS)
        assertEquals(
            PhysicalShutdownPolicy.DELAYED,
            WearFoldEvent.WearChanged(WearState.NOT_WORN).shutdownPolicy(),
        )
        assertEquals(
            PhysicalShutdownPolicy.IMMEDIATE,
            WearFoldEvent.LegChanged(LegState.FOLDED).shutdownPolicy(),
        )
        assertEquals(
            PhysicalShutdownPolicy.NONE,
            WearFoldEvent.WearChanged(WearState.WORN).shutdownPolicy(),
        )
        assertEquals(
            PhysicalShutdownPolicy.NONE,
            WearFoldEvent.LegChanged(LegState.OPEN).shutdownPolicy(),
        )
    }

    @Test
    fun readinessRequiresOpenAndWornEvents() {
        val opened = GlassesSessionStateReducer.reduce(
            GlassesSessionState(),
            WearFoldEvent.LegChanged(LegState.OPEN),
            elapsedRealtimeMs = 100L,
        )
        assertFalse(opened.isOpenAndWorn)

        val worn = GlassesSessionStateReducer.reduce(
            opened,
            WearFoldEvent.WearChanged(WearState.WORN),
            elapsedRealtimeMs = 200L,
        )
        assertTrue(worn.isOpenAndWorn)
        assertTrue(worn.shouldRunBle)
        assertTrue(worn.shouldAdvertiseBle)
        assertTrue(worn.isPhysicalStateKnown)
        assertNull(worn.physicalStartError())
        assertEquals(200L, worn.lastPhysicalEventElapsedRealtimeMs)
    }

    @Test
    fun foldingOverridesAnActiveSession() {
        val streaming = GlassesSessionState(
            wearState = WearState.WORN,
            legState = LegState.OPEN,
            connectionState = SessionConnectionState.STREAMING,
            bleState = BleSessionState.CONNECTED,
            desiredSession = true,
        )

        val folded = GlassesSessionStateReducer.reduce(
            streaming,
            WearFoldEvent.LegChanged(LegState.FOLDED),
            elapsedRealtimeMs = 300L,
        )

        assertEquals(LegState.FOLDED, folded.legState)
        assertEquals(SessionConnectionState.STOPPING, folded.connectionState)
        assertEquals(BleSessionState.OFF, folded.bleState)
        assertFalse(folded.desiredSession)
        assertFalse(folded.isOpenAndWorn)
        assertFalse(folded.shouldRunBle)
        assertFalse(folded.shouldAdvertiseBle)
        assertEquals(BleProtocolError.GLASSES_FOLDED, folded.physicalStartError())
        assertFalse(folded.cameraOwnedBySession)
    }

    @Test
    fun removingGlassesStopsBleAndClearsSessionIntent() {
        val removed = GlassesSessionStateReducer.reduce(
            GlassesSessionState(
                wearState = WearState.WORN,
                legState = LegState.OPEN,
                bleState = BleSessionState.READY,
                connectionState = SessionConnectionState.WIFI_READY,
                desiredSession = true,
            ),
            WearFoldEvent.WearChanged(WearState.NOT_WORN),
            elapsedRealtimeMs = 350L,
        )

        assertEquals(WearState.NOT_WORN, removed.wearState)
        assertEquals(BleSessionState.OFF, removed.bleState)
        assertEquals(SessionConnectionState.STOPPING, removed.connectionState)
        assertFalse(removed.desiredSession)
        assertFalse(removed.shouldRunBle)
        assertFalse(removed.shouldAdvertiseBle)
        assertEquals(BleProtocolError.GLASSES_NOT_WORN, removed.physicalStartError())
    }

    @Test
    fun reopeningDoesNotRestoreSessionIntent() {
        val reopened = GlassesSessionStateReducer.reduce(
            GlassesSessionState(
                wearState = WearState.NOT_WORN,
                legState = LegState.FOLDED,
                connectionState = SessionConnectionState.IDLE,
                desiredSession = false,
            ),
            WearFoldEvent.LegChanged(LegState.OPEN),
            elapsedRealtimeMs = 400L,
        )

        assertEquals(LegState.OPEN, reopened.legState)
        assertFalse(reopened.desiredSession)
        assertEquals(SessionConnectionState.IDLE, reopened.connectionState)
    }
}

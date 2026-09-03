package com.rokid.glassesbaredevsample.provisioning

enum class WearState {
    UNKNOWN,
    NOT_WORN,
    WORN,
}

enum class LegState {
    UNKNOWN,
    FOLDED,
    OPEN,
}

enum class SessionConnectionState {
    IDLE,
    WAITING_FOR_PHYSICAL_STATE,
    WIFI_CONNECTING,
    WIFI_READY,
    PHONE_WAITING,
    STREAM_STARTING,
    STREAMING,
    STOPPING,
    ERROR,
}

enum class BleSessionState {
    OFF,
    STARTING,
    READY,
    CONNECTED,
    ERROR,
}

data class GlassesSessionState(
    val serviceRunning: Boolean = false,
    val wearState: WearState = WearState.UNKNOWN,
    val legState: LegState = LegState.UNKNOWN,
    val bleState: BleSessionState = BleSessionState.OFF,
    val connectionState: SessionConnectionState = SessionConnectionState.IDLE,
    val desiredSession: Boolean = false,
    val lastPhysicalEventElapsedRealtimeMs: Long? = null,
    val lastError: String? = null,
) {
    val isOpenAndWorn: Boolean
        get() = legState == LegState.OPEN && wearState == WearState.WORN

    val isPhysicalStateKnown: Boolean
        get() = legState != LegState.UNKNOWN && wearState != WearState.UNKNOWN

    val shouldRunBle: Boolean
        get() = isOpenAndWorn

    val shouldAdvertiseBle: Boolean
        get() = legState != LegState.FOLDED && wearState != WearState.NOT_WORN

    val cameraOwnedBySession: Boolean
        get() = connectionState == SessionConnectionState.STREAM_STARTING ||
            connectionState == SessionConnectionState.STREAMING
}

fun GlassesSessionState.physicalStartError(): BleProtocolError? = when {
    legState == LegState.FOLDED -> BleProtocolError.GLASSES_FOLDED
    wearState == WearState.NOT_WORN -> BleProtocolError.GLASSES_NOT_WORN
    !isPhysicalStateKnown -> BleProtocolError.PHYSICAL_STATE_UNKNOWN
    else -> null
}

object GlassesSessionStateReducer {
    fun reduce(
        current: GlassesSessionState,
        event: WearFoldEvent,
        elapsedRealtimeMs: Long,
    ): GlassesSessionState = when (event) {
        is WearFoldEvent.WearChanged -> if (event.state == WearState.NOT_WORN) {
            current.copy(
                wearState = WearState.NOT_WORN,
                bleState = BleSessionState.OFF,
                connectionState = SessionConnectionState.STOPPING,
                desiredSession = false,
                lastPhysicalEventElapsedRealtimeMs = elapsedRealtimeMs,
                lastError = null,
            )
        } else {
            current.copy(
                wearState = event.state,
                lastPhysicalEventElapsedRealtimeMs = elapsedRealtimeMs,
            )
        }

        is WearFoldEvent.LegChanged -> if (event.state == LegState.FOLDED) {
            current.copy(
                legState = LegState.FOLDED,
                bleState = BleSessionState.OFF,
                connectionState = SessionConnectionState.STOPPING,
                desiredSession = false,
                lastPhysicalEventElapsedRealtimeMs = elapsedRealtimeMs,
                lastError = null,
            )
        } else {
            current.copy(
                legState = event.state,
                lastPhysicalEventElapsedRealtimeMs = elapsedRealtimeMs,
            )
        }
    }
}

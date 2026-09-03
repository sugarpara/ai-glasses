package com.rokid.glassesbaredevsample.provisioning

object WearFoldContract {
    const val NOT_WORN_CONFIRMATION_MS = 15_000L
    const val ACTION_TAKE_STATUS_CHANGED =
        "com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED"
    const val ACTION_LEG_STATUS_CHANGED =
        "com.rokid.sprite.ACTION_LEG_STATUS_CHANGED"
    const val EXTRA_TAKE_STATE = "glasses_take_state"
    const val EXTRA_LEG_STATE = "glasses_leg_state"

    fun expectedExtra(action: String): String? = when (action) {
        ACTION_TAKE_STATUS_CHANGED -> EXTRA_TAKE_STATE
        ACTION_LEG_STATUS_CHANGED -> EXTRA_LEG_STATE
        else -> null
    }

    fun parse(action: String, rawValue: String?): WearFoldEvent? = when (action) {
        ACTION_TAKE_STATUS_CHANGED -> when (rawValue) {
            "0" -> WearFoldEvent.WearChanged(WearState.NOT_WORN)
            "1" -> WearFoldEvent.WearChanged(WearState.WORN)
            else -> null
        }

        ACTION_LEG_STATUS_CHANGED -> when (rawValue) {
            "0" -> WearFoldEvent.LegChanged(LegState.FOLDED)
            "1" -> WearFoldEvent.LegChanged(LegState.OPEN)
            else -> null
        }

        else -> null
    }
}

sealed interface WearFoldEvent {
    data class WearChanged(val state: WearState) : WearFoldEvent
    data class LegChanged(val state: LegState) : WearFoldEvent
}

enum class PhysicalShutdownPolicy {
    NONE,
    DELAYED,
    IMMEDIATE,
}

fun WearFoldEvent.shutdownPolicy(): PhysicalShutdownPolicy = when (this) {
    is WearFoldEvent.WearChanged -> if (state == WearState.NOT_WORN) {
        PhysicalShutdownPolicy.DELAYED
    } else {
        PhysicalShutdownPolicy.NONE
    }

    is WearFoldEvent.LegChanged -> if (state == LegState.FOLDED) {
        PhysicalShutdownPolicy.IMMEDIATE
    } else {
        PhysicalShutdownPolicy.NONE
    }
}

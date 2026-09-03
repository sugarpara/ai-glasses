package com.rokid.glassesbaredevsample.activities.keys

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.glassesbaredevsample.provisioning.GlassesSessionService
import com.rokid.glassesbaredevsample.provisioning.LegState
import com.rokid.glassesbaredevsample.provisioning.WearState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class KeysWearViewModel(application: Application) : AndroidViewModel(application) {
    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _takeState = MutableStateFlow("-")
    val takeState: StateFlow<String> = _takeState.asStateFlow()

    private val _legState = MutableStateFlow("-")
    val legState: StateFlow<String> = _legState.asStateFlow()

    private var lastWearState = WearState.UNKNOWN
    private var lastLegState = LegState.UNKNOWN

    init {
        viewModelScope.launch {
            GlassesSessionService.state.collect { session ->
                if (session.wearState != lastWearState) {
                    lastWearState = session.wearState
                    _takeState.value = session.wearState.rawLabel()
                    append("佩戴: ${_takeState.value}")
                }
                if (session.legState != lastLegState) {
                    lastLegState = session.legState
                    _legState.value = session.legState.rawLabel()
                    append("镜腿: ${_legState.value}")
                }
            }
        }
    }

    fun appendLog(line: String) {
        append(line)
    }

    fun register() {
        GlassesSessionService.ensureStarted(
            getApplication(),
            reason = "keys-wear-screen",
        )
        append("Session 状态监听已连接")
    }

    fun unregister() = Unit

    private fun append(line: String) {
        val next = (_logLines.value + line).takeLast(12)
        _logLines.value = next
    }

    private fun WearState.rawLabel(): String = when (this) {
        WearState.UNKNOWN -> "-"
        WearState.NOT_WORN -> "0"
        WearState.WORN -> "1"
    }

    private fun LegState.rawLabel(): String = when (this) {
        LegState.UNKNOWN -> "-"
        LegState.FOLDED -> "0"
        LegState.OPEN -> "1"
    }
}

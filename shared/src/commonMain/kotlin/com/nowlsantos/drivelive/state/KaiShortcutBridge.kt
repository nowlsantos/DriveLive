package com.nowlsantos.drivelive.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object KaiShortcutBridge {
    private val _pendingEngage = MutableStateFlow(false)
    val pendingEngage: StateFlow<Boolean> = _pendingEngage.asStateFlow()

    fun requestEngage() {
        _pendingEngage.value = true
    }

    fun consumeEngage(): Boolean {
        if (!_pendingEngage.value) return false
        _pendingEngage.value = false
        return true
    }
}

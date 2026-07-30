package com.hermesandroid.relay.wake

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WakeWordActivation(
    val id: String = UUID.randomUUID().toString(),
    val startNewSession: Boolean,
    val profileRouting: WakeWordProfileRouting,
)

/**
 * Durable-within-process handoff between the microphone service and Compose.
 * StateFlow (instead of an event-only SharedFlow) lets a background detection
 * remain pending until the user opens the actionable notification.
 */
object WakeWordActivationCoordinator {
    private val _pending = MutableStateFlow<WakeWordActivation?>(null)
    val pending: StateFlow<WakeWordActivation?> = _pending.asStateFlow()

    fun request(activation: WakeWordActivation) {
        _pending.value = activation
    }

    fun consume(id: String): Boolean {
        val current = _pending.value ?: return false
        if (current.id != id) return false
        _pending.value = null
        return true
    }

    internal fun resetForTest() {
        _pending.value = null
    }
}

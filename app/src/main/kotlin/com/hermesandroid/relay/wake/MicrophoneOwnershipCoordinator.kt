package com.hermesandroid.relay.wake

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MicrophoneOwner {
    WakeWord,
    VoiceCapture,
    BargeIn,
    RealtimeDiagnostics,
}

class MicrophoneLease internal constructor(
    val owner: MicrophoneOwner,
    internal val token: String,
)

/**
 * Process-wide ownership seam for every Android AudioRecord path.
 *
 * Wake word, foreground capture, full-turn barge-in, and realtime diagnostics
 * all acquire this lease. The wake service also makes an explicit synchronous
 * handoff before entering the existing voice flow.
 */
object MicrophoneOwnershipCoordinator {
    private val lock = Any()
    private var activeLease: MicrophoneLease? = null
    private val _owner = MutableStateFlow<MicrophoneOwner?>(null)
    val owner: StateFlow<MicrophoneOwner?> = _owner.asStateFlow()

    fun tryAcquire(owner: MicrophoneOwner): MicrophoneLease? = synchronized(lock) {
        if (activeLease != null) return null
        MicrophoneLease(owner, UUID.randomUUID().toString()).also {
            activeLease = it
            _owner.value = owner
        }
    }

    fun release(lease: MicrophoneLease): Boolean = synchronized(lock) {
        if (activeLease?.token != lease.token) return false
        activeLease = null
        _owner.value = null
        true
    }

    internal fun resetForTest() = synchronized(lock) {
        activeLease = null
        _owner.value = null
    }
}

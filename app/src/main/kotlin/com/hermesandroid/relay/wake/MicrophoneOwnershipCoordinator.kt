package com.hermesandroid.relay.wake

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job

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
 * all acquire this lease. Voice mode reserves the microphone against wake
 * acquisition even between utterances, while the lease tracks actual readers.
 */
object MicrophoneOwnershipCoordinator {
    private val lock = Any()
    private var activeLease: MicrophoneLease? = null
    private val _owner = MutableStateFlow<MicrophoneOwner?>(null)
    val owner: StateFlow<MicrophoneOwner?> = _owner.asStateFlow()
    private val voiceSessions = mutableSetOf<Any>()
    private val _voiceSessionActive = MutableStateFlow(false)
    val voiceSessionActive: StateFlow<Boolean> = _voiceSessionActive.asStateFlow()

    fun beginVoiceSession(): Any = synchronized(lock) {
        Any().also {
            voiceSessions.add(it)
            _voiceSessionActive.value = true
        }
    }

    /** Retain the reservation until asynchronous barge-in teardown finishes. */
    fun endVoiceSession(session: Any, microphoneRelease: Job? = null) {
        if (microphoneRelease != null && !microphoneRelease.isCompleted) {
            microphoneRelease.invokeOnCompletion { endVoiceSession(session) }
            return
        }
        synchronized(lock) {
            voiceSessions.remove(session)
            _voiceSessionActive.value = voiceSessions.isNotEmpty()
        }
    }

    /** Wake readers release their lease only after AudioRecord/JNI teardown. */
    suspend fun awaitWakeRelease() {
        owner.first { it != MicrophoneOwner.WakeWord }
    }

    fun tryAcquire(owner: MicrophoneOwner): MicrophoneLease? = synchronized(lock) {
        if (activeLease != null) return null
        if (owner == MicrophoneOwner.WakeWord && voiceSessions.isNotEmpty()) return null
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
        voiceSessions.clear()
        _voiceSessionActive.value = false
    }
}

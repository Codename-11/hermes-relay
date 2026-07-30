package com.hermesandroid.relay.assistant

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import androidx.core.content.edit
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import com.hermesandroid.relay.wake.WakeWordActivation
import com.hermesandroid.relay.wake.WakeWordActivationCoordinator
import com.hermesandroid.relay.wake.WakeWordActivationSource
import com.hermesandroid.relay.wake.WakeWordProfileRouting
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AssistantRoleStatus {
    Unavailable,
    NotSelected,
    Selected,
}

enum class AssistantSessionPhase {
    Launching,
    Listening,
    Transcribing,
    Thinking,
    Speaking,
    Idle,
    Error,
    Closed,
}

data class AssistantSessionSnapshot(
    val phase: AssistantSessionPhase = AssistantSessionPhase.Launching,
    val transcript: String? = null,
    val response: String = "",
    val error: String? = null,
)

object AssistantRole {
    fun status(context: Context): AssistantRoleStatus {
        val component = ComponentName(context, HermesVoiceInteractionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
                ?: return AssistantRoleStatus.Unavailable
            if (!roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                return AssistantRoleStatus.Unavailable
            }
            return if (roles.isRoleHeld(RoleManager.ROLE_ASSISTANT) &&
                VoiceInteractionService.isActiveService(context, component)
            ) {
                AssistantRoleStatus.Selected
            } else {
                AssistantRoleStatus.NotSelected
            }
        }
        return if (VoiceInteractionService.isActiveService(context, component)) {
            AssistantRoleStatus.Selected
        } else {
            AssistantRoleStatus.NotSelected
        }
    }

    fun selectionIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) {
                return roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            }
        }
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            .takeIf { it.resolveActivity(context.packageManager) != null }
    }

    fun managementIntent(context: Context): Intent? =
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: selectionIntent(context)
}

/**
 * Cross-process protocol between the system-owned assistant session process
 * and the normal app process that owns the established voice pipeline.
 */
object AssistantSessionProtocol {
    const val EXTRA_ASSISTANT_SESSION = "com.hermesandroid.relay.assistant.SESSION"
    const val EXTRA_ACTIVATION_ID = "com.hermesandroid.relay.assistant.ACTIVATION_ID"
    const val EXTRA_START_NEW_SESSION =
        "com.hermesandroid.relay.assistant.START_NEW_SESSION"
    private const val ACTION_STATUS = "com.hermesandroid.relay.assistant.STATUS"
    private const val ACTION_FINISH = "com.hermesandroid.relay.assistant.FINISH"
    private const val ACTION_START = "com.hermesandroid.relay.assistant.START"
    private const val EXTRA_PHASE = "phase"
    private const val EXTRA_TRANSCRIPT = "transcript"
    private const val EXTRA_RESPONSE = "response"
    private const val EXTRA_ERROR = "error"
    private const val EXTRA_CANCEL_VOICE = "cancel_voice"

    fun prepareAssistActivation(intent: Intent?) {
        val assistIntent = intent ?: return
        if (!isAssistAction(assistIntent.action)) return
        assistIntent.putExtra(EXTRA_ASSISTANT_SESSION, true)
    }

    internal fun isAssistAction(action: String?): Boolean = action == Intent.ACTION_ASSIST

    fun activationIntent(
        context: Context,
        activationId: String = UUID.randomUUID().toString(),
        startNewSession: Boolean = true,
    ) =
        Intent(context, com.hermesandroid.relay.MainActivity::class.java).apply {
            putExtra(EXTRA_ASSISTANT_SESSION, true)
            putExtra(EXTRA_ACTIVATION_ID, activationId)
            putExtra(EXTRA_START_NEW_SESSION, startNewSession)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    fun consumeActivation(context: Context, intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_ASSISTANT_SESSION, false) != true) return false
        val id = intent.getStringExtra(EXTRA_ACTIVATION_ID) ?: UUID.randomUUID().toString()
        val startNewSession = intent.getBooleanExtra(EXTRA_START_NEW_SESSION, true)
        AssistantSessionPersistence.setActivation(context, id, startNewSession)
        WakeWordActivationCoordinator.request(
            WakeWordActivation(
                id = id,
                startNewSession = startNewSession,
                profileRouting = WakeWordProfileRouting(),
                source = WakeWordActivationSource.SystemAssistant,
            )
        )
        AssistantAppSessionState.setActive(true)
        intent.removeExtra(EXTRA_ASSISTANT_SESSION)
        intent.removeExtra(EXTRA_ACTIVATION_ID)
        intent.removeExtra(EXTRA_START_NEW_SESSION)
        return true
    }

    fun restoreActivation(context: Context): Boolean {
        if (AssistantAppSessionState.active.value) return false
        val activation = AssistantSessionPersistence.restoreActivation(context) ?: return false
        AssistantAppSessionState.setActive(true)
        WakeWordActivationCoordinator.request(activation)
        return true
    }

    fun publish(context: Context, snapshot: AssistantSessionSnapshot) {
        context.sendBroadcast(
            Intent(context, AssistantSessionStateReceiver::class.java).apply {
                action = ACTION_STATUS
                putExtra(EXTRA_PHASE, snapshot.phase.name)
                putExtra(EXTRA_TRANSCRIPT, snapshot.transcript)
                putExtra(EXTRA_RESPONSE, snapshot.response)
                putExtra(EXTRA_ERROR, snapshot.error)
            }
        )
    }

    fun publish(context: Context, state: VoiceUiState) {
        publish(context, snapshotFromVoiceState(state))
    }

    internal fun snapshotFromVoiceState(state: VoiceUiState): AssistantSessionSnapshot {
        val phase = when {
            !state.voiceMode -> AssistantSessionPhase.Closed
            state.state == VoiceState.Listening -> AssistantSessionPhase.Listening
            state.state == VoiceState.Transcribing -> AssistantSessionPhase.Transcribing
            state.state == VoiceState.Thinking -> AssistantSessionPhase.Thinking
            state.state == VoiceState.Speaking -> AssistantSessionPhase.Speaking
            state.state == VoiceState.Error -> AssistantSessionPhase.Error
            else -> AssistantSessionPhase.Idle
        }
        return AssistantSessionSnapshot(
            phase = phase,
            transcript = state.transcribedText?.take(MAX_SESSION_TEXT_CHARS),
            response = state.responseText.take(MAX_SESSION_TEXT_CHARS),
            error = state.error?.take(MAX_SESSION_ERROR_CHARS),
        )
    }

    fun finish(context: Context, cancelVoice: Boolean) {
        context.sendBroadcast(
            Intent(context, AssistantSessionLifecycleReceiver::class.java).apply {
                action = ACTION_FINISH
                putExtra(EXTRA_CANCEL_VOICE, cancelVoice)
            }
        )
    }

    fun started(context: Context) {
        context.sendBroadcast(
            Intent(context, AssistantSessionLifecycleReceiver::class.java).setAction(ACTION_START)
        )
    }

    internal fun isFinishAction(action: String?): Boolean = action == ACTION_FINISH
    internal fun isStartAction(action: String?): Boolean = action == ACTION_START
    internal fun shouldCancelVoice(intent: Intent): Boolean =
        intent.getBooleanExtra(EXTRA_CANCEL_VOICE, false)

    internal fun readSnapshot(intent: Intent): AssistantSessionSnapshot {
        val phase = runCatching {
            AssistantSessionPhase.valueOf(
                intent.getStringExtra(EXTRA_PHASE) ?: AssistantSessionPhase.Launching.name
            )
        }.getOrDefault(AssistantSessionPhase.Error)
        return AssistantSessionSnapshot(
            phase = phase,
            transcript = intent.getStringExtra(EXTRA_TRANSCRIPT),
            response = intent.getStringExtra(EXTRA_RESPONSE).orEmpty(),
            error = intent.getStringExtra(EXTRA_ERROR),
        )
    }

    private const val MAX_SESSION_TEXT_CHARS = 4_000
    private const val MAX_SESSION_ERROR_CHARS = 1_000
}

object AssistantSessionState {
    private val _snapshot = MutableStateFlow(AssistantSessionSnapshot())
    val snapshot: StateFlow<AssistantSessionSnapshot> = _snapshot.asStateFlow()

    internal fun update(snapshot: AssistantSessionSnapshot) {
        _snapshot.value = snapshot
    }

    internal fun reset() {
        _snapshot.value = AssistantSessionSnapshot()
    }
}

class AssistantSessionStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AssistantSessionState.update(AssistantSessionProtocol.readSnapshot(intent))
    }
}

class AssistantSessionLifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AssistantSessionProtocol.isStartAction(intent.action)) {
            AssistantSessionPersistence.setActive(context, true)
            HermesVoiceInteractionService.setVoiceSessionActive(true)
            return
        }
        if (!AssistantSessionProtocol.isFinishAction(intent.action)) return
        AssistantSessionPersistence.setActive(context, false)
        if (AssistantSessionProtocol.shouldCancelVoice(intent)) {
            AssistantVoiceCommandCoordinator.requestCancel()
        }
        AssistantAppSessionState.setActive(false)
        HermesVoiceInteractionService.setVoiceSessionActive(false)
    }
}

object AssistantSessionPersistence {
    private const val STORE = "assistant_session_lifecycle"
    private const val KEY_ACTIVE_SINCE = "active_since"
    private const val KEY_ACTIVATION_ID = "activation_id"
    private const val KEY_START_NEW_SESSION = "start_new_session"
    private const val STALE_AFTER_MS = 30 * 60 * 1_000L

    fun setActive(context: Context, active: Boolean) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit(commit = true) {
            putLong(KEY_ACTIVE_SINCE, if (active) System.currentTimeMillis() else 0L)
            if (!active) {
                remove(KEY_ACTIVATION_ID)
            }
        }
    }

    fun setActivation(context: Context, id: String, startNewSession: Boolean) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit(commit = true) {
            putString(KEY_ACTIVATION_ID, id)
            putBoolean(KEY_START_NEW_SESSION, startNewSession)
        }
    }

    fun restoreActivation(context: Context): WakeWordActivation? {
        if (!isActive(context)) return null
        val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val id = store.getString(KEY_ACTIVATION_ID, null) ?: return null
        return WakeWordActivation(
            id = id,
            startNewSession = store.getBoolean(KEY_START_NEW_SESSION, true),
            profileRouting = WakeWordProfileRouting(),
            source = WakeWordActivationSource.SystemAssistant,
        )
    }

    fun isActive(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val since = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getLong(KEY_ACTIVE_SINCE, 0L)
        return isFresh(since, nowMs)
    }

    internal fun isFresh(sinceMs: Long, nowMs: Long): Boolean =
        sinceMs > 0L && nowMs - sinceMs in 0..STALE_AFTER_MS
}

object AssistantAppSessionState {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    @Volatile private var voiceStarted = false

    internal fun setActive(active: Boolean) {
        if (active && !_active.value) voiceStarted = false
        if (!active) voiceStarted = false
        _active.value = active
    }

    fun markVoiceStarted() {
        voiceStarted = true
    }

    fun hasVoiceStarted(): Boolean = voiceStarted
}

object AssistantVoiceCommandCoordinator {
    private val _cancelRequest = MutableStateFlow<String?>(null)
    val cancelRequest: StateFlow<String?> = _cancelRequest.asStateFlow()

    fun requestCancel() {
        _cancelRequest.value = UUID.randomUUID().toString()
    }

    fun consume(id: String): Boolean {
        if (_cancelRequest.value != id) return false
        _cancelRequest.value = null
        return true
    }
}

package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.SessionTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ConversationBindingOrigin {
    GlobalSelection,
    ExplicitSession,
}

/**
 * The one profile/session identity currently owned by Chat.
 *
 * Drawer filters such as "All Profiles" are deliberately absent: browsing
 * state may expose many owners, but selecting a row creates exactly one active
 * binding. [contextKey] is null only before the process runtime has bound an
 * active connection/profile.
 */
internal data class ConversationBinding(
    val contextKey: String? = null,
    val profileName: String? = null,
    val sessionId: String? = null,
    val transport: SessionTransport? = null,
    val displayProfile: Profile? = null,
    val origin: ConversationBindingOrigin = ConversationBindingOrigin.GlobalSelection,
    val revision: Long = 0L,
) {
    val isBound: Boolean get() = contextKey != null
    val hasExplicitOwner: Boolean get() = origin == ConversationBindingOrigin.ExplicitSession
}

/** Serial reducer for process-owned conversation identity. */
internal class ConversationBindingController {
    private val _state = MutableStateFlow(ConversationBinding())
    val state: StateFlow<ConversationBinding> = _state.asStateFlow()

    fun profileAllowed(profileName: String?, lockedProfileToken: String?): Boolean =
        lockedProfileToken == null ||
            AgentDisplay.profileSessionKey(profileName) == lockedProfileToken

    fun openExplicit(
        contextKey: String,
        profileName: String?,
        sessionId: String?,
        displayProfile: Profile?,
        lockedProfileToken: String?,
        transport: SessionTransport? = sessionId?.let(SessionTransport::forSessionId),
    ): Boolean {
        if (!profileAllowed(profileName, lockedProfileToken)) return false
        reduce(
            contextKey = contextKey,
            profileName = profileName,
            sessionId = sessionId,
            displayProfile = displayProfile,
            origin = ConversationBindingOrigin.ExplicitSession,
            transport = transport,
        )
        return true
    }

    fun forceGlobal(
        contextKey: String,
        profileName: String?,
        sessionId: String?,
        displayProfile: Profile? = null,
        transport: SessionTransport? = sessionId?.let(SessionTransport::forSessionId),
    ) {
        reduce(
            contextKey = contextKey,
            profileName = profileName,
            sessionId = sessionId,
            displayProfile = displayProfile,
            origin = ConversationBindingOrigin.GlobalSelection,
            transport = transport,
        )
    }

    /** Lifecycle reconciliation never overrides a user-opened session. */
    fun reconcileGlobal(
        contextKey: String,
        profileName: String?,
        sessionId: String?,
        displayProfile: Profile? = null,
        transport: SessionTransport? = sessionId?.let(SessionTransport::forSessionId),
    ): Boolean {
        val current = _state.value
        if (
            current.hasExplicitOwner &&
            (
                current.contextKey != contextKey ||
                    current.profileName != profileName ||
                    current.sessionId != sessionId
                )
        ) return false
        forceGlobal(contextKey, profileName, sessionId, displayProfile, transport)
        return true
    }

    fun switchSession(sessionId: String?) {
        val current = _state.value
        if (current.sessionId == sessionId) return
        _state.value = current.copy(
            sessionId = sessionId,
            transport = sessionId?.let(SessionTransport::forSessionId) ?: current.transport,
            revision = current.revision + 1,
        )
    }

    /** A user-requested draft keeps its owner and fences persisted-session reconciliation. */
    fun startFreshDraft(transport: SessionTransport? = _state.value.transport) {
        val current = _state.value
        if (!current.isBound) return
        if (current.sessionId == null && current.hasExplicitOwner) return
        _state.value = current.copy(
            sessionId = null,
            transport = transport,
            origin = ConversationBindingOrigin.ExplicitSession,
            revision = current.revision + 1,
        )
    }

    fun releaseExplicitOwner() {
        if (!_state.value.hasExplicitOwner) return
        reset()
    }

    fun reset() {
        val revision = _state.value.revision + 1
        _state.value = ConversationBinding(revision = revision)
    }

    private fun reduce(
        contextKey: String,
        profileName: String?,
        sessionId: String?,
        displayProfile: Profile?,
        origin: ConversationBindingOrigin,
        transport: SessionTransport?,
    ) {
        val current = _state.value
        val next = ConversationBinding(
            contextKey = contextKey,
            profileName = profileName,
            sessionId = sessionId,
            displayProfile = displayProfile,
            origin = origin,
            transport = transport,
            revision = current.revision + 1,
        )
        if (current.copy(revision = next.revision) != next) {
            _state.value = next
        }
    }
}

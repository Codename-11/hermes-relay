package com.hermesandroid.relay.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.data.AppAnalytics
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.network.HermesApiClient
import com.hermesandroid.relay.network.handlers.ChatHandler
import com.hermesandroid.relay.network.models.SkillInfo
import com.hermesandroid.relay.network.models.UsageInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource
import java.util.UUID

class ChatViewModel : ViewModel() {

    private var apiClient: HermesApiClient? = null
    private var chatHandler: ChatHandler? = null
    private var activeStream: EventSource? = null
    private var intentionallyCancelled = false
    private var firstTokenNotified = false

    /** Callback to persist session ID — set by RelayApp */
    var onSessionChanged: ((String?) -> Unit)? = null

    // --- Message queue ---
    private val _queuedMessages = MutableStateFlow<List<String>>(emptyList())
    val queuedMessages: StateFlow<List<String>> = _queuedMessages.asStateFlow()

    // --- Pending attachments ---
    private val _pendingAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    val pendingAttachments: StateFlow<List<Attachment>> = _pendingAttachments.asStateFlow()

    fun addAttachment(attachment: Attachment) {
        _pendingAttachments.update { it + attachment }
    }

    fun removeAttachment(index: Int) {
        _pendingAttachments.update { list ->
            list.filterIndexed { i, _ -> i != index }
        }
    }

    fun clearAttachments() {
        _pendingAttachments.value = emptyList()
    }

    companion object {
        /** Brief app context sent as system_message when enabled in settings. */
        const val APP_CONTEXT_PROMPT = "The user is chatting via the Hermes Relay Android app. Keep responses mobile-friendly and concise when possible."
    }

    // Server-side personality selection
    private val _selectedPersonality = MutableStateFlow("default")
    val selectedPersonality: StateFlow<String> = _selectedPersonality.asStateFlow()

    private val _personalityNames = MutableStateFlow<List<String>>(emptyList())
    val personalityNames: StateFlow<List<String>> = _personalityNames.asStateFlow()

    /** Default personality name from server (config.display.personality) */
    private val _defaultPersonality = MutableStateFlow("")
    val defaultPersonality: StateFlow<String> = _defaultPersonality.asStateFlow()

    /** Personality name → system prompt. Used to send the right prompt when switching. */
    private var personalityPrompts: Map<String, String> = emptyMap()

    /** Model name from the server's /api/config response (e.g. "claude-opus-4-6") */
    private val _serverModelName = MutableStateFlow("")
    val serverModelName: StateFlow<String> = _serverModelName.asStateFlow()

    /** Whether to include the brief app context system message */
    var appContextEnabled: Boolean = true

    /** Streaming endpoint: "sessions" or "runs" */
    var streamingEndpoint: String = "sessions"

    fun selectPersonality(name: String) {
        _selectedPersonality.value = name
    }

    /** The display name of the currently active personality (for chat bubbles). */
    val activePersonalityName: String
        get() {
            val selected = _selectedPersonality.value
            return if (selected == "default") _defaultPersonality.value else selected
        }

    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()

    private val _availableSkills = MutableStateFlow<List<SkillInfo>>(emptyList())
    val availableSkills: StateFlow<List<SkillInfo>> = _availableSkills.asStateFlow()

    // Cached fallback StateFlows to avoid creating new instances on each access
    private val _emptyMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _emptyStreaming = MutableStateFlow(false)
    private val _emptySessions = MutableStateFlow<List<ChatSession>>(emptyList())
    private val _emptyError = MutableStateFlow<String?>(null)
    private val _emptySessionId = MutableStateFlow<String?>(null)

    // Delegated to ChatHandler
    val messages: StateFlow<List<ChatMessage>>
        get() = chatHandler?.messages ?: _emptyMessages

    val isStreaming: StateFlow<Boolean>
        get() = chatHandler?.isStreaming ?: _emptyStreaming

    val sessions: StateFlow<List<ChatSession>>
        get() = chatHandler?.sessions ?: _emptySessions

    val error: StateFlow<String?>
        get() = chatHandler?.error ?: _emptyError

    val currentSessionId: StateFlow<String?>
        get() = chatHandler?.currentSessionId ?: _emptySessionId

    fun initialize(apiClient: HermesApiClient, chatHandler: ChatHandler) {
        this.apiClient = apiClient
        this.chatHandler = chatHandler
        fetchSkills()
        fetchPersonalities()
    }

    fun fetchSkills() {
        val client = apiClient ?: return
        viewModelScope.launch {
            val skills = client.getSkills()
            _availableSkills.value = skills
        }
    }

    fun updateApiClient(client: HermesApiClient) {
        activeStream?.cancel()
        activeStream = null
        this.apiClient = client
        fetchSkills()
        fetchPersonalities()
    }

    private fun fetchPersonalities() {
        val client = apiClient ?: return
        viewModelScope.launch {
            val config = client.getPersonalities()
            _personalityNames.value = config.names
            _defaultPersonality.value = config.defaultName
            personalityPrompts = config.prompts
            _serverModelName.value = config.modelName
        }
    }

    // --- Session management ---

    fun refreshSessions() {
        val client = apiClient ?: return
        val handler = chatHandler ?: return
        viewModelScope.launch {
            val sessions = client.listSessions()
            handler.updateSessions(sessions)
        }
    }

    fun createNewChat() {
        val client = apiClient ?: return
        val handler = chatHandler ?: return

        // Cancel any in-flight stream
        activeStream?.cancel()
        activeStream = null

        viewModelScope.launch {
            val session = client.createSession()
            if (session != null) {
                val chatSession = ChatSession(
                    sessionId = session.id,
                    title = session.title ?: "New Chat",
                    model = session.model
                )
                handler.addSession(chatSession)
                handler.setSessionId(session.id)
                handler.clearMessages()
                onSessionChanged?.invoke(session.id)
                AppAnalytics.onSessionCreated()
            }
        }
    }

    fun switchSession(sessionId: String) {
        val client = apiClient ?: return
        val handler = chatHandler ?: return

        // Cancel any in-flight stream
        intentionallyCancelled = true
        activeStream?.cancel()
        activeStream = null

        handler.setSessionId(sessionId)
        handler.clearMessages()
        onSessionChanged?.invoke(sessionId)
        AppAnalytics.onSessionSwitched()

        // Load message history
        _isLoadingHistory.value = true
        viewModelScope.launch {
            val messages = client.getMessages(sessionId)
            handler.loadMessageHistory(messages)
            _isLoadingHistory.value = false
        }
    }

    /**
     * Resume a session by ID (e.g., from persisted last session).
     * Only loads history, doesn't create a new session.
     */
    fun resumeSession(sessionId: String) {
        switchSession(sessionId)
    }

    fun deleteSession(sessionId: String) {
        val client = apiClient ?: return
        val handler = chatHandler ?: return

        // Save reference before removing (for rollback on failure)
        val removedSession = handler.sessions.value.find { it.sessionId == sessionId }

        // Optimistic removal
        handler.removeSession(sessionId)
        if (handler.currentSessionId.value == null) {
            onSessionChanged?.invoke(null)
        }

        viewModelScope.launch {
            val success = client.deleteSession(sessionId)
            if (!success && removedSession != null) {
                // Restore on failure
                handler.addSession(removedSession)
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val client = apiClient ?: return
        val handler = chatHandler ?: return

        // Optimistic rename
        handler.renameSessionLocal(sessionId, newTitle)

        viewModelScope.launch {
            client.renameSession(sessionId, newTitle)
        }
    }

    // --- Message sending ---

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val client = apiClient ?: return
        val handler = chatHandler ?: return

        // If currently streaming, queue the message instead of cancelling
        if (activeStream != null) {
            _queuedMessages.update { it + text.trim() }
            return
        }

        sendMessageInternal(client, handler, text)
    }

    fun clearQueue() {
        _queuedMessages.value = emptyList()
    }

    private fun drainQueue() {
        val client = apiClient ?: return
        val handler = chatHandler ?: return
        val next = _queuedMessages.value.firstOrNull() ?: return
        _queuedMessages.update { it.drop(1) }
        sendMessageInternal(client, handler, next)
    }

    private fun sendMessageInternal(client: HermesApiClient, handler: ChatHandler, text: String) {
        AppAnalytics.onMessageSent()

        // Snapshot and clear pending attachments
        val attachments = _pendingAttachments.value.ifEmpty { null }
        _pendingAttachments.value = emptyList()

        val messageId = UUID.randomUUID().toString()

        // Add user message locally (with attachments for display)
        handler.addUserMessage(
            ChatMessage(
                id = messageId,
                role = MessageRole.USER,
                content = text.trim(),
                timestamp = System.currentTimeMillis(),
                attachments = attachments ?: emptyList()
            )
        )
        handler.setLastSentMessage(text.trim())

        val assistantMessageId = UUID.randomUUID().toString()
        val sessionId = handler.currentSessionId.value

        if (streamingEndpoint == "runs") {
            startStream(client, handler, sessionId ?: "", text.trim(), assistantMessageId, attachments)
        } else if (sessionId != null) {
            startStream(client, handler, sessionId, text.trim(), assistantMessageId, attachments)
        } else {
            viewModelScope.launch {
                val session = client.createSession()
                if (session != null) {
                    val chatSession = ChatSession(
                        sessionId = session.id,
                        title = null,
                        model = session.model
                    )
                    handler.addSession(chatSession)
                    handler.setSessionId(session.id)
                    onSessionChanged?.invoke(session.id)
                    startStream(client, handler, session.id, text.trim(), assistantMessageId, attachments)

                    // Auto-title: use first ~50 chars of user message
                    val autoTitle = text.trim().take(50).let {
                        if (text.length > 50) "$it..." else it
                    }
                    client.renameSession(session.id, autoTitle)
                    handler.renameSessionLocal(session.id, autoTitle)
                } else {
                    handler.onStreamError("Failed to create chat session")
                }
            }
        }
    }

    private fun startStream(
        client: HermesApiClient,
        handler: ChatHandler,
        sessionId: String,
        message: String,
        assistantMessageId: String,
        attachments: List<Attachment>? = null
    ) {
        // Build system_message from personality prompt + app context
        val selected = _selectedPersonality.value
        val personalityPrompt = if (selected != "default" && selected != _defaultPersonality.value) {
            // Non-default personality selected — send its system prompt to override server default
            personalityPrompts[selected]
        } else null
        val appContext = if (appContextEnabled) APP_CONTEXT_PROMPT else null
        val systemMsg = listOfNotNull(personalityPrompt, appContext)
            .joinToString("\n\n")
            .ifBlank { null }

        // Set agent name for display on chat bubbles
        handler.activeAgentName = activePersonalityName.replaceFirstChar { it.uppercase() }
            .ifBlank { null }

        firstTokenNotified = false
        var lastInputTokens: Int? = null
        var lastOutputTokens: Int? = null

        // Track the current message ID — starts with our generated ID,
        // but updates when the server sends message.started with its own ID.
        var currentMessageId = assistantMessageId

        // Show placeholder "thinking" message immediately — filled when first delta arrives
        handler.addPlaceholderMessage(
            ChatMessage(
                id = assistantMessageId,
                role = MessageRole.ASSISTANT,
                content = "",
                timestamp = System.currentTimeMillis(),
                isStreaming = true,
                agentName = handler.activeAgentName
            )
        )

        // Shared callbacks for both endpoints
        val onMessageStartedCb = { serverMsgId: String ->
            // Server assigned a new message ID for this turn — update tracking
            currentMessageId = serverMsgId
        }
        val onTextDeltaCb = { delta: String ->
            if (!firstTokenNotified) {
                firstTokenNotified = true
                AppAnalytics.onFirstTokenReceived()
            }
            handler.onTextDelta(currentMessageId, delta)
        }
        val onThinkingDeltaCb = { delta: String ->
            handler.onThinkingDelta(currentMessageId, delta)
        }
        val onToolCallStartCb = { toolCallId: String, toolName: String ->
            handler.onToolCallStart(currentMessageId, toolCallId, toolName)
        }
        val onToolCallDoneCb = { toolCallId: String, resultPreview: String? ->
            handler.onToolCallComplete(currentMessageId, toolCallId, resultPreview)
        }
        val onToolCallFailedCb = { toolCallId: String, errorMsg: String? ->
            handler.onToolCallFailed(currentMessageId, toolCallId, errorMsg)
        }
        // Turn complete — one assistant message finished, but the run may continue
        val onTurnCompleteCb = {
            handler.onTurnComplete(currentMessageId)
        }
        val onCompleteCb = {
            handler.onStreamComplete(currentMessageId)
            AppAnalytics.onStreamComplete(lastInputTokens, lastOutputTokens)
            activeStream = null
            drainQueue()
        }
        val onUsageCb = { usage: UsageInfo? ->
            if (usage != null) {
                val tokIn = usage.resolvedInputTokens
                val tokOut = usage.resolvedOutputTokens
                if (tokIn != null || tokOut != null) {
                    lastInputTokens = tokIn
                    lastOutputTokens = tokOut
                    handler.onUsageReceived(
                        currentMessageId,
                        tokIn,
                        tokOut,
                        usage.resolvedTotalTokens,
                        null
                    )
                }
            }
        }
        val onErrorCb = { errorMsg: String ->
            if (intentionallyCancelled) {
                intentionallyCancelled = false
                // Don't surface cancellation errors
            } else {
                AppAnalytics.onStreamError()
                handler.onStreamError(errorMsg)
            }
            activeStream = null
            _queuedMessages.value = emptyList()
        }

        activeStream = if (streamingEndpoint == "runs") {
            client.sendRunStream(
                message = message,
                systemMessage = systemMsg,
                attachments = attachments,
                onSessionId = { sid ->
                    handler.setSessionId(sid)
                    onSessionChanged?.invoke(sid)
                },
                onMessageStarted = onMessageStartedCb,
                onTextDelta = onTextDeltaCb,
                onThinkingDelta = onThinkingDeltaCb,
                onToolCallStart = onToolCallStartCb,
                onToolCallDone = onToolCallDoneCb,
                onToolCallFailed = onToolCallFailedCb,
                onTurnComplete = onTurnCompleteCb,
                onComplete = onCompleteCb,
                onUsage = onUsageCb,
                onError = onErrorCb
            )
        } else {
            client.sendChatStream(
                sessionId = sessionId,
                message = message,
                systemMessage = systemMsg,
                attachments = attachments,
                onSessionId = { /* already set */ },
                onMessageStarted = onMessageStartedCb,
                onTextDelta = onTextDeltaCb,
                onThinkingDelta = onThinkingDeltaCb,
                onToolCallStart = onToolCallStartCb,
                onToolCallDone = onToolCallDoneCb,
                onToolCallFailed = onToolCallFailedCb,
                onTurnComplete = onTurnCompleteCb,
                onComplete = onCompleteCb,
                onUsage = onUsageCb,
                onError = onErrorCb
            )
        }
    }

    fun cancelStream() {
        intentionallyCancelled = true
        activeStream?.cancel()
        activeStream = null
        _queuedMessages.value = emptyList()
        AppAnalytics.onStreamCancelled()
        chatHandler?.let { handler ->
            val streamingMsg = handler.messages.value.findLast { it.isStreaming }
            if (streamingMsg != null) {
                handler.onStreamComplete(streamingMsg.id)
            }
        }
    }

    fun clearError() {
        chatHandler?.clearError()
    }

    fun retryLastMessage() {
        val lastMsg = chatHandler?.lastSentMessage?.value ?: return
        sendMessage(lastMsg)
    }

    override fun onCleared() {
        super.onCleared()
        activeStream?.cancel()
    }
}

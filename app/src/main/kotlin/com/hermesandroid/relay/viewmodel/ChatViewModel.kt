package com.hermesandroid.relay.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.data.AppAnalytics
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.AttachmentState
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.MediaSettings
import com.hermesandroid.relay.data.MediaSettingsRepository
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.network.HermesApiClient
import com.hermesandroid.relay.network.RelayHttpClient
import com.hermesandroid.relay.network.handlers.ChatHandler
import com.hermesandroid.relay.network.models.SkillInfo
import com.hermesandroid.relay.network.models.UsageInfo
import com.hermesandroid.relay.util.MediaCacheWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    // --- Media dependencies (wired via initializeMedia from RelayApp) ---
    private var relayHttpClient: RelayHttpClient? = null
    private var mediaSettingsRepo: MediaSettingsRepository? = null
    private var mediaCacheWriter: MediaCacheWriter? = null
    private var appContext: Context? = null

    /**
     * Marker used in [Attachment.errorMessage] when a fetch is deferred to
     * manual download (cellular + auto-fetch-on-cellular off). The UI uses
     * [Attachment.state] == [AttachmentState.LOADING] plus this exact string
     * to render a "Tap to download" CTA instead of a spinner.
     *
     * Encoded as a plain string rather than a new enum value to keep the data
     * class surface small — the UI already switches on (state, errorMessage)
     * for the FAILED/LOADED cases.
     */
    companion object {
        /** Brief app context sent as system_message when enabled in settings. */
        const val APP_CONTEXT_PROMPT = "The user is chatting via the Hermes-Relay Android app. Keep responses mobile-friendly and concise when possible."
        const val MEDIA_TAP_TO_DOWNLOAD = "Tap to download"
    }

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

    /**
     * Wire inbound-media dependencies. Called from [RelayApp][com.hermesandroid.relay.ui.RelayApp]
     * once after the singleton services are constructed.
     *
     * Separated from [initialize] because the media pipeline doesn't require
     * an active API client to be meaningful — the fetch path is independent
     * of chat streaming state and uses a different auth token entirely.
     *
     * Safe to call multiple times (rewires the ChatHandler callbacks).
     */
    fun initializeMedia(
        context: Context,
        relayHttpClient: RelayHttpClient,
        mediaSettingsRepo: MediaSettingsRepository,
        mediaCacheWriter: MediaCacheWriter
    ) {
        this.appContext = context.applicationContext
        this.relayHttpClient = relayHttpClient
        this.mediaSettingsRepo = mediaSettingsRepo
        this.mediaCacheWriter = mediaCacheWriter

        // Wire ChatHandler callbacks so streaming media markers flow here.
        chatHandler?.let { handler ->
            handler.onMediaAttachmentRequested = { messageId, token ->
                onMediaAttachmentRequested(messageId, token)
            }
            handler.onUnavailableMediaMarker = { messageId, originalPath ->
                onUnavailableMediaMarker(messageId, originalPath)
            }
        }
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
            // Replace the placeholder's ID so subsequent deltas/tool calls attach
            // to it instead of creating a duplicate orphan bubble with streaming dots.
            // Only replaces empty+streaming messages (the placeholder), not completed turns.
            handler.replaceMessageId(currentMessageId, serverMsgId)
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

            // Sessions endpoint doesn't emit structured tool events during streaming —
            // tool calls are only available as JSON on the stored messages. Reload the
            // server-authoritative history to get proper message boundaries + tool_calls.
            val sid = handler.currentSessionId.value
            if (sid != null && streamingEndpoint == "sessions") {
                viewModelScope.launch {
                    val serverMessages = client.getMessages(sid)
                    handler.loadMessageHistory(serverMessages)
                    drainQueue()
                }
                Unit
            } else {
                drainQueue()
            }
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

    // --- Inbound media handling ------------------------------------------------

    /**
     * Invoked when ChatHandler parses a `MEDIA:hermes-relay://<token>` marker.
     *
     * Flow:
     *  1. Insert a LOADING placeholder attachment on the matching assistant
     *     message so the user sees something immediately.
     *  2. Read current media settings (auto-fetch cap, cellular gate).
     *  3. If on cellular AND auto-fetch-on-cellular is off → leave the
     *     LOADING placeholder in place with [MEDIA_TAP_TO_DOWNLOAD] in
     *     [Attachment.errorMessage]. The UI renders a "Tap to download" CTA.
     *  4. Otherwise → kick off a background fetch, enforce the max size cap,
     *     cache the bytes via [MediaCacheWriter], and update the attachment
     *     to LOADED (or FAILED on any error).
     *
     * Note: the matching happens by (messageId + relayToken). If the same
     * token shows up twice (e.g. reconciliation pass finds it after real-time
     * already dispatched) the ChatHandler dedupes via dispatchedMediaMarkers
     * so we shouldn't see duplicate calls here.
     */
    fun onMediaAttachmentRequested(messageId: String, token: String) {
        val handler = chatHandler ?: return
        val relay = relayHttpClient
        val repo = mediaSettingsRepo
        val cache = mediaCacheWriter

        // 1. Insert LOADING placeholder.
        val placeholder = Attachment(
            contentType = "application/octet-stream",
            content = "",
            state = AttachmentState.LOADING,
            relayToken = token
        )
        appendAttachmentToMessage(handler, messageId, placeholder)

        if (relay == null || repo == null || cache == null) {
            // Media dependencies not wired yet — flip to FAILED so the UI
            // doesn't spin forever on a placeholder with no fetch in flight.
            updateAttachmentByToken(handler, messageId, token) { att ->
                att.copy(
                    state = AttachmentState.FAILED,
                    errorMessage = "Media pipeline not ready"
                )
            }
            return
        }

        viewModelScope.launch {
            val settings = repo.settings.first()

            // 2. Cellular gate.
            if (isOnCellular() && !settings.autoFetchOnCellular) {
                updateAttachmentByToken(handler, messageId, token) { att ->
                    att.copy(
                        state = AttachmentState.LOADING,
                        errorMessage = MEDIA_TAP_TO_DOWNLOAD
                    )
                }
                return@launch
            }

            // 3. Fetch + cache.
            performFetch(handler, messageId, token, settings)
        }
    }

    /**
     * Re-run the fetch for an attachment that's in the "Tap to download"
     * deferred state. Used by the inbound-media card's CTA on cellular.
     */
    fun manualFetchAttachment(messageId: String, attachmentIndex: Int) {
        val handler = chatHandler ?: return
        val relay = relayHttpClient ?: return
        val repo = mediaSettingsRepo ?: return
        val cache = mediaCacheWriter ?: return

        val msg = handler.messages.value.find { it.id == messageId } ?: return
        val att = msg.attachments.getOrNull(attachmentIndex) ?: return
        val token = att.relayToken ?: return

        // Flip back to a pure LOADING spinner (drop the CTA marker) so the
        // user gets immediate feedback that the download kicked off.
        updateAttachmentByToken(handler, messageId, token) { existing ->
            existing.copy(state = AttachmentState.LOADING, errorMessage = null)
        }

        viewModelScope.launch {
            val settings = repo.settings.first()
            performFetch(handler, messageId, token, settings)
        }
    }

    /**
     * Invoked when ChatHandler parses a bare-path `MEDIA:/abs/path` marker
     * (relay wasn't reachable when the tool fired, so the file only exists
     * on the server's local disk and we have no way to retrieve it).
     *
     * Inserts a FAILED placeholder so the chat shows "Image unavailable"
     * instead of the literal path text.
     */
    fun onUnavailableMediaMarker(messageId: String, originalPath: String) {
        val handler = chatHandler ?: return
        val placeholder = Attachment(
            contentType = "application/octet-stream",
            content = "",
            state = AttachmentState.FAILED,
            errorMessage = "Image unavailable — relay offline",
            fileName = originalPath.substringAfterLast('/').ifBlank { null }
        )
        appendAttachmentToMessage(handler, messageId, placeholder)
    }

    /**
     * Core fetch routine. Extracted so both the auto-fetch path and the
     * manual-retry path can share error handling and size enforcement.
     */
    private suspend fun performFetch(
        handler: ChatHandler,
        messageId: String,
        token: String,
        settings: MediaSettings
    ) {
        val relay = relayHttpClient ?: return
        val cache = mediaCacheWriter ?: return
        val maxBytes = settings.maxInboundSizeMb.toLong().coerceAtLeast(1) * 1024L * 1024L

        val result = relay.fetchMedia(token)
        result.fold(
            onSuccess = { fetched ->
                if (fetched.bytes.size > maxBytes) {
                    val sizeMb = fetched.bytes.size / (1024.0 * 1024.0)
                    updateAttachmentByToken(handler, messageId, token) { att ->
                        att.copy(
                            state = AttachmentState.FAILED,
                            errorMessage = "File too large (%.1f MB, max %d MB)".format(
                                sizeMb, settings.maxInboundSizeMb
                            ),
                            contentType = fetched.contentType,
                            fileName = fetched.fileName ?: att.fileName,
                            fileSize = fetched.bytes.size.toLong()
                        )
                    }
                    return
                }

                try {
                    val uri = cache.cache(fetched.bytes, fetched.contentType, fetched.fileName)
                    updateAttachmentByToken(handler, messageId, token) { att ->
                        att.copy(
                            state = AttachmentState.LOADED,
                            errorMessage = null,
                            contentType = fetched.contentType,
                            fileName = fetched.fileName ?: att.fileName,
                            fileSize = fetched.bytes.size.toLong(),
                            cachedUri = uri.toString()
                        )
                    }
                } catch (e: Exception) {
                    updateAttachmentByToken(handler, messageId, token) { att ->
                        att.copy(
                            state = AttachmentState.FAILED,
                            errorMessage = "Failed to cache: ${e.message ?: "unknown"}"
                        )
                    }
                }
            },
            onFailure = { err ->
                updateAttachmentByToken(handler, messageId, token) { att ->
                    att.copy(
                        state = AttachmentState.FAILED,
                        errorMessage = err.message ?: "Fetch failed"
                    )
                }
            }
        )
    }

    /**
     * Append an attachment to a specific assistant message, matched by id.
     * No-ops if the message can't be found (e.g. it was trimmed from the
     * MAX_MESSAGES rolling buffer between the marker parse and the update).
     */
    private fun appendAttachmentToMessage(
        handler: ChatHandler,
        messageId: String,
        attachment: Attachment
    ) {
        // Direct StateFlow mutation via the handler's messages flow would be
        // cleaner, but ChatHandler exposes the flow as read-only. We piggyback
        // on the same pattern used by the tool-call callbacks: mutate in-place
        // through a handler method. For attachments there's no existing helper,
        // so we reach into the StateFlow via Kotlin's `update` reflection-free
        // pattern — except we can't, because _messages is private. Fall back
        // to a minimal helper added below.
        handler.mutateMessage(messageId) { msg ->
            if (msg.role != MessageRole.ASSISTANT) msg
            else msg.copy(attachments = msg.attachments + attachment)
        }
    }

    /**
     * Find the attachment on [messageId] whose [Attachment.relayToken] equals
     * [token] and apply [transform] to it. Used for all LOADING→LOADED/FAILED
     * transitions so the update key is stable across list shifts.
     */
    private fun updateAttachmentByToken(
        handler: ChatHandler,
        messageId: String,
        token: String,
        transform: (Attachment) -> Attachment
    ) {
        handler.mutateMessage(messageId) { msg ->
            if (msg.role != MessageRole.ASSISTANT) return@mutateMessage msg
            val idx = msg.attachments.indexOfFirst { it.relayToken == token }
            if (idx < 0) return@mutateMessage msg
            val updated = msg.attachments.toMutableList().also {
                it[idx] = transform(it[idx])
            }
            msg.copy(attachments = updated)
        }
    }

    /**
     * True when the currently active network is cellular (metered LTE/5G).
     * Returns false on Wi-Fi, Ethernet, or when the state is unavailable.
     */
    private fun isOnCellular(): Boolean {
        val ctx = appContext ?: return false
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) {
            false
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeStream?.cancel()
    }
}

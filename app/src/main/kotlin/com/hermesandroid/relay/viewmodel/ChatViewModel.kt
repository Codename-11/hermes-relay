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
import com.hermesandroid.relay.util.AppContextSettings
import com.hermesandroid.relay.util.HumanError
import com.hermesandroid.relay.util.MediaCacheWriter
import com.hermesandroid.relay.util.PhoneSnapshot
import com.hermesandroid.relay.util.buildPromptBlock
import com.hermesandroid.relay.util.classifyError
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
        // === PHASE3-status: APP_CONTEXT_PROMPT removed ===
        // The old static one-liner used to live here. Replaced by the
        // dynamic block built in PhoneStatusPromptBuilder.buildPromptBlock()
        // from `appContextSettings` + a freshly-read PhoneSnapshot. See
        // `send()` below for the construction site.
        // === END PHASE3-status ===
        const val MEDIA_TAP_TO_DOWNLOAD = "Tap to download"
    }

    /** Callback to persist session ID — set by RelayApp */
    var onSessionChanged: ((String?) -> Unit)? = null

    // --- Human-readable error events ---
    // One-shot events consumed by ChatScreen via snackbar. Shape mirrors
    // other VMs for consistency; DROP_OLDEST so a burst of errors never
    // stalls the emitter.
    private val _errorEvents = MutableSharedFlow<HumanError>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errorEvents: SharedFlow<HumanError> = _errorEvents.asSharedFlow()

    private fun emitError(t: Throwable?, context: String?) {
        _errorEvents.tryEmit(classifyError(t, context = context))
    }

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

    // === PHASE3-status: dynamic app-context settings ===
    /**
     * Granular phone-status settings. Written from RelayApp via a collect
     * on the five `appContext*` StateFlows in ConnectionViewModel. Read on
     * every send() to build the system-prompt block.
     *
     * Defaults match ConnectionViewModel's default values — master on,
     * bridgeState on, currentApp/battery off (privacy), safetyStatus on.
     */
    var appContextSettings: AppContextSettings = AppContextSettings()
    // === END PHASE3-status ===

    /**
     * Streaming endpoint to use for the next chat turn. Always one of
     * "sessions" or "runs" — never "auto", since the auto-resolver in
     * ConnectionViewModel.resolveStreamingEndpoint() collapses "auto" to
     * a concrete value before this field is written from RelayApp.
     *
     * Defaults to "runs" so that a fresh ChatViewModel (before RelayApp
     * pushes the resolved value) prefers the standard upstream chat path.
     * That's the safer fallback than the previous "sessions" default,
     * which would 404 on vanilla upstream installs.
     */
    var streamingEndpoint: String = "runs"

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
            handler.onMediaBarePathRequested = { messageId, originalPath ->
                onMediaBarePathRequested(messageId, originalPath)
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
                    emitError(Exception("Failed to create chat session"), context = "send_message")
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
        // === PHASE3-status: dynamic phone-status block ===
        val appContext = buildPromptBlock(appContextSettings, capturePhoneSnapshot())
        val systemMsg = listOfNotNull(personalityPrompt, appContext)
            .joinToString("\n\n")
            .ifBlank { null }
        // === END PHASE3-status ===

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
                // Keep the in-place error banner AND push to the global
                // snackbar — classifier wraps the string into a throwable
                // so context-specific copy kicks in for send_message.
                emitError(Exception(errorMsg), context = "send_message")
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
            performFetchWith(handler, messageId, token, settings) {
                relay.fetchMedia(token)
            }
        }
    }

    /**
     * Re-run the fetch for an attachment that's in the "Tap to download"
     * deferred state. Used by the inbound-media card's CTA on cellular.
     *
     * Works for both flavors of inbound attachment: if the stored key starts
     * with `/` it's an absolute path (bare-media form, use
     * [RelayHttpClient.fetchMediaByPath]); otherwise it's a relay token
     * (use [RelayHttpClient.fetchMedia]). `secrets.token_urlsafe` never
     * produces `/` so the prefix check is unambiguous.
     */
    fun manualFetchAttachment(messageId: String, attachmentIndex: Int) {
        val handler = chatHandler ?: return
        val relay = relayHttpClient ?: return
        val repo = mediaSettingsRepo ?: return
        val cache = mediaCacheWriter ?: return

        val msg = handler.messages.value.find { it.id == messageId } ?: return
        val att = msg.attachments.getOrNull(attachmentIndex) ?: return
        val fetchKey = att.relayToken ?: return

        // Flip back to a pure LOADING spinner (drop the CTA marker) so the
        // user gets immediate feedback that the download kicked off.
        updateAttachmentByToken(handler, messageId, fetchKey) { existing ->
            existing.copy(state = AttachmentState.LOADING, errorMessage = null)
        }

        viewModelScope.launch {
            val settings = repo.settings.first()
            performFetchWith(handler, messageId, fetchKey, settings) {
                if (fetchKey.startsWith("/")) {
                    relay.fetchMediaByPath(fetchKey)
                } else {
                    relay.fetchMedia(fetchKey)
                }
            }
        }
    }

    /**
     * Invoked when ChatHandler parses a bare-path `MEDIA:/abs/path` marker.
     *
     * The bare-path form is the LLM's native output — upstream
     * `agent/prompt_builder.py` explicitly instructs the model to "include
     * MEDIA:/absolute/path/to/file in your response" — so this is the
     * primary inbound-media path, not a fallback.
     *
     * Flow mirrors [onMediaAttachmentRequested]:
     *  1. Insert a LOADING placeholder with [Attachment.relayToken] set to
     *     the absolute path (serves as the stable key for state updates —
     *     since `secrets.token_urlsafe` never starts with `/`, we can
     *     disambiguate token vs path downstream by the leading `/`).
     *  2. Cellular gate (same as token path).
     *  3. Kick off a background fetch via [RelayHttpClient.fetchMediaByPath],
     *     which hits the bearer-auth'd `/media/by-path` relay route. The
     *     route enforces the same path sandbox as `/media/register`.
     *  4. On success → cache + flip to LOADED. On failure → FAILED with a
     *     user-facing message from [RelayHttpClient].
     */
    fun onMediaBarePathRequested(messageId: String, originalPath: String) {
        val handler = chatHandler ?: return
        val relay = relayHttpClient
        val repo = mediaSettingsRepo
        val cache = mediaCacheWriter

        val placeholder = Attachment(
            contentType = "application/octet-stream",
            content = "",
            state = AttachmentState.LOADING,
            // Reuse relayToken as a generic inbound-fetch key. Paths always
            // start with `/`, real tokens never do — downstream helpers
            // that need to distinguish can check the prefix.
            relayToken = originalPath,
            fileName = originalPath.substringAfterLast('/').ifBlank { null }
        )
        appendAttachmentToMessage(handler, messageId, placeholder)

        if (relay == null || repo == null || cache == null) {
            updateAttachmentByToken(handler, messageId, originalPath) { att ->
                att.copy(
                    state = AttachmentState.FAILED,
                    errorMessage = "Media pipeline not ready"
                )
            }
            return
        }

        viewModelScope.launch {
            val settings = repo.settings.first()

            if (isOnCellular() && !settings.autoFetchOnCellular) {
                updateAttachmentByToken(handler, messageId, originalPath) { att ->
                    att.copy(
                        state = AttachmentState.LOADING,
                        errorMessage = MEDIA_TAP_TO_DOWNLOAD
                    )
                }
                return@launch
            }

            performFetchWith(handler, messageId, originalPath, settings) {
                relay.fetchMediaByPath(originalPath)
            }
        }
    }

    /**
     * Core fetch routine. Takes a [fetch] lambda so it can serve both the
     * `hermes-relay://<token>` path ([RelayHttpClient.fetchMedia]) and the
     * bare-path `MEDIA:/abs/path` path ([RelayHttpClient.fetchMediaByPath]).
     *
     * [fetchKey] is whatever string identifies the attachment in
     * [Attachment.relayToken] — a token for the relay-hosted case, an
     * absolute path for the bare-path case. The size / cache / state
     * handling is identical; only the remote call differs.
     */
    private suspend fun performFetchWith(
        handler: ChatHandler,
        messageId: String,
        fetchKey: String,
        settings: MediaSettings,
        fetch: suspend () -> Result<RelayHttpClient.FetchedMedia>,
    ) {
        val cache = mediaCacheWriter ?: return
        val maxBytes = settings.maxInboundSizeMb.toLong().coerceAtLeast(1) * 1024L * 1024L

        val result = fetch()
        result.fold(
            onSuccess = { fetched ->
                if (fetched.bytes.size > maxBytes) {
                    val sizeMb = fetched.bytes.size / (1024.0 * 1024.0)
                    updateAttachmentByToken(handler, messageId, fetchKey) { att ->
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
                    updateAttachmentByToken(handler, messageId, fetchKey) { att ->
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
                    // Classifier produces a specific label (disk full, bad
                    // URI, permission, …) for both the in-card text and the
                    // global snackbar — same event, two surfaces.
                    val human = classifyError(e, context = "media_fetch")
                    updateAttachmentByToken(handler, messageId, fetchKey) { att ->
                        att.copy(
                            state = AttachmentState.FAILED,
                            errorMessage = human.body
                        )
                    }
                    _errorEvents.tryEmit(human)
                }
            },
            onFailure = { err ->
                val human = classifyError(err, context = "media_fetch")
                updateAttachmentByToken(handler, messageId, fetchKey) { att ->
                    att.copy(
                        state = AttachmentState.FAILED,
                        errorMessage = human.body
                    )
                }
                _errorEvents.tryEmit(human)
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

    // === PHASE3-status: cached-state snapshot for the dynamic prompt block ===
    /**
     * Construct a [PhoneSnapshot] from whatever cached state is reachable
     * *without* network calls or suspend functions. Every field is guarded
     * with `runCatching` so this is safe to call on a fresh install where
     * the Phase 3 accessibility/bridge/safety classes may not exist yet.
     *
     * Reflection is used for the Phase 3 classes (HermesAccessibilityService,
     * MediaProjectionHolder, BridgeSafetyManager, HermesNotificationCompanion)
     * so this file compiles before those classes land in the worktree. When
     * they do land, the reflective reads start returning real values with
     * zero further code changes here. Stay alert: if any of those classes
     * change their FQCN or their singleton accessor shape, update the
     * reflective lookups below.
     *
     * Privacy-sensitive fields (currentApp, batteryPercent) are gated by
     * the caller's [AppContextSettings] — this function always reads them
     * when they're cheap, but the builder drops them when the sub-toggle
     * is off. We do honour the gate for battery because `BATTERY_PROPERTY_CAPACITY`
     * may wake the battery stats service; when the sub-toggle is off we
     * simply don't read it.
     */
    private fun capturePhoneSnapshot(): PhoneSnapshot {
        val ctx = appContext ?: return PhoneSnapshot()

        // --- Accessibility service (Phase 3) ---
        // Reflective lookup of HermesAccessibilityService.instance — a
        // @Volatile companion singleton set in onServiceConnected. Kotlin
        // compiles companion object properties to either a static field on
        // the enclosing class (when @JvmStatic is used) or to a
        // Companion.getInstance() accessor. Try both shapes.
        var bridgeBound = false
        var masterEnabled = false
        var accessibilityGranted = false
        var currentAppPkg: String? = null
        runCatching {
            val cls = Class.forName("com.hermesandroid.relay.accessibility.HermesAccessibilityService")
            val instance: Any? = runCatching {
                // Shape A: @JvmStatic — static field on the outer class
                val f = cls.getDeclaredField("instance").apply { isAccessible = true }
                f.get(null)
            }.getOrNull() ?: runCatching {
                // Shape B: companion property with generated accessor
                val companionField = cls.getDeclaredField("Companion").apply { isAccessible = true }
                val companion = companionField.get(null)
                companion.javaClass.getMethod("getInstance").invoke(companion)
            }.getOrNull()

            if (instance != null) {
                bridgeBound = true
                accessibilityGranted = true
                runCatching {
                    val m = instance.javaClass.getMethod("isMasterEnabled")
                    masterEnabled = (m.invoke(instance) as? Boolean) == true
                }
                if (appContextSettings.currentApp) {
                    // Kotlin `val currentApp: String?` compiles to getCurrentApp()
                    runCatching {
                        val m = instance.javaClass.getMethod("getCurrentApp")
                        currentAppPkg = m.invoke(instance) as? String
                    }
                }
            }
        }

        // --- Screen capture (Phase 3 MediaProjectionHolder) ---
        var screenCaptureGranted = false
        runCatching {
            val cls = Class.forName("com.hermesandroid.relay.accessibility.MediaProjectionHolder")
            val instanceField = cls.getDeclaredField("INSTANCE").apply { isAccessible = true }
            val instance = instanceField.get(null)
            val m = instance.javaClass.getMethod("getProjection")
            screenCaptureGranted = m.invoke(instance) != null
        }

        // --- Overlay permission (platform API, always available) ---
        val overlayGranted = runCatching {
            android.provider.Settings.canDrawOverlays(ctx)
        }.getOrDefault(false)

        // --- Notification listener (Phase 3 HermesNotificationCompanion) ---
        val notificationsGranted = runCatching {
            val flat = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                "enabled_notification_listeners",
            ) ?: ""
            flat.contains(ctx.packageName)
        }.getOrDefault(false)

        // --- Battery (platform API, privacy-gated) ---
        val batteryPercent: Int? = if (appContextSettings.battery) {
            runCatching {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (pct != null && pct in 0..100) pct else null
            }.getOrNull()
        } else null

        // --- Safety manager (Phase 3 BridgeSafetyManager.peek()) ---
        var blocklistCount: Int? = null
        var destructiveVerbCount: Int? = null
        var autoDisableMinutes: Int? = null
        runCatching {
            val cls = Class.forName("com.hermesandroid.relay.bridge.BridgeSafetyManager")
            val companionField = cls.getDeclaredField("Companion").apply { isAccessible = true }
            val companion = companionField.get(null)
            val manager = companion.javaClass.getMethod("peek").invoke(companion)
            if (manager != null) {
                val settingsFlow = manager.javaClass.getMethod("getSettings").invoke(manager)
                val settings = settingsFlow?.javaClass?.getMethod("getValue")?.invoke(settingsFlow)
                if (settings != null) {
                    runCatching {
                        val blocklist = settings.javaClass.getMethod("getBlocklist").invoke(settings) as? Collection<*>
                        blocklistCount = blocklist?.size
                    }
                    runCatching {
                        val verbs = settings.javaClass.getMethod("getDestructiveVerbs").invoke(settings) as? Collection<*>
                        destructiveVerbCount = verbs?.size
                    }
                    runCatching {
                        val m = settings.javaClass.getMethod("getAutoDisableMinutes")
                        autoDisableMinutes = m.invoke(settings) as? Int
                    }
                }
            }
        }

        return PhoneSnapshot(
            bridgeBound = bridgeBound,
            masterEnabled = masterEnabled,
            accessibilityGranted = accessibilityGranted,
            screenCaptureGranted = screenCaptureGranted,
            overlayGranted = overlayGranted,
            notificationsGranted = notificationsGranted,
            currentApp = currentAppPkg,
            batteryPercent = batteryPercent,
            blocklistCount = blocklistCount,
            destructiveVerbCount = destructiveVerbCount,
            autoDisableMinutes = autoDisableMinutes,
        )
    }
    // === END PHASE3-status ===

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

package com.hermesandroid.relay.network.handlers

import android.util.Log
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.ToolCall
import com.hermesandroid.relay.network.models.MessageItem
import com.hermesandroid.relay.network.models.SessionItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Manages chat message state, session list, and streaming events.
 */
class ChatHandler {

    companion object {
        private const val TAG = "ChatHandler"

        /** Maximum number of messages kept in memory per session. Oldest are trimmed. */
        private const val MAX_MESSAGES = 500

        // Tool annotation patterns embedded as text markers by Hermes.
        //
        // Hermes /v1/chat/completions injects tool progress as inline markdown:
        //   `💻 terminal`           — tool-type-specific emoji + tool name in backticks
        //   `🔍 Python docs`        — some tool names have spaces
        //
        // The emoji varies by tool type. We match ANY non-whitespace char(s) as the
        // emoji token (covers 💻🔍📝🔧⏳🔄✅✓❌✗ and future additions).
        //
        // We detect start vs. complete by tracking: first occurrence = start,
        // second occurrence of the same tool = complete.
        //
        // Additionally supports bare emoji formats: 🔧 Running: terminal
        //
        // Format 1 (primary): `<emoji> <tool_name>` — backtick-wrapped, any emoji
        private val toolAnnotationBacktickRegex = Regex(
            """`([^\s`]+)\s+([^`]+)`"""
        )
        // Format 2 (verbose): 🔧 Running: tool_name / ✅ Completed: tool_name / ❌ Failed: tool_name
        private val toolAnnotationVerboseStartRegex = Regex(
            """([🔧⏳🔄💻🔍📝🛠️])\s+(?:Running(?:\s*:\s*|\s+))(\w[\w\s]*)"""
        )
        private val toolAnnotationVerboseCompleteRegex = Regex(
            """([✅✓])\s+(?:Completed(?:\s*:\s*|\s+)|Done(?:\s*:\s*|\s+))(\w[\w\s]*)"""
        )
        private val toolAnnotationVerboseFailedRegex = Regex(
            """([❌✗])\s+(?:Failed(?:\s*:\s*|\s+)|Error(?:\s*:\s*|\s+))(\w[\w\s]*)"""
        )
        // Inbound media markers emitted by tool results (e.g. android_screenshot).
        //
        // Primary form (server with relay): `MEDIA:hermes-relay://<token>` — the
        //   relay has the file, we fetch it over HTTP and render it inline.
        // Fallback form (no relay): `MEDIA:/absolute/path` — relay wasn't
        //   reachable when the tool fired, so we render an "unavailable"
        //   placeholder instead of attempting a fetch.
        private val mediaRelayRegex = Regex("""MEDIA:hermes-relay://([A-Za-z0-9_-]+)""")
        private val mediaBarePathRegex = Regex("""^\s*MEDIA:(/\S+)\s*$""")
        // Known completion/failure emojis — if these appear in backtick format, it's a completion
        private val completionEmojis = setOf("✅", "✓", "☑")
        private val failureEmojis = setOf("❌", "✗", "⚠")
    }

    /** Whether to parse tool annotations from assistant text (for servers that don't emit tool events). */
    var parseToolAnnotations: Boolean = false

    /** Active personality/agent name — set by ChatViewModel before each stream. Included on new assistant messages. */
    var activeAgentName: String? = null

    /**
     * Fired when the text stream contains `MEDIA:hermes-relay://<token>`. The
     * ViewModel should insert a LOADING [com.hermesandroid.relay.data.Attachment]
     * on the matching message and kick off a relay fetch.
     * Default is a no-op so tests and legacy callers don't have to wire it.
     */
    var onMediaAttachmentRequested: (messageId: String, token: String) -> Unit = { _, _ -> }

    /**
     * Fired when the text stream contains a bare-path `MEDIA:/path` marker.
     *
     * The bare-path form is the PRIMARY format LLMs emit — upstream
     * `hermes-agent/agent/prompt_builder.py` explicitly instructs the model
     * to "include MEDIA:/absolute/path/to/file in your response" — so this
     * callback fires for most inbound media, not just fallback / unavailable
     * cases.
     *
     * The ViewModel inserts a LOADING placeholder and kicks off a direct
     * fetch via `RelayHttpClient.fetchMediaByPath`, which hits the
     * bearer-auth'd `/media/by-path` relay route. The route enforces the
     * same path sandbox as `/media/register`. The placeholder flips to
     * LOADED on success, FAILED on any fetch error (relay offline → "Relay
     * unreachable", sandbox violation → "Path not allowed", missing file →
     * "File not found", etc).
     */
    var onMediaBarePathRequested: (messageId: String, originalPath: String) -> Unit = { _, _ -> }

    /**
     * Buffer for incomplete lines during streaming. Tool annotations are line-oriented
     * (backtick + emoji + tool_name + backtick), so we accumulate text until we see a
     * newline and then scan completed lines. This handles the case where a single
     * annotation is split across multiple SSE deltas.
     */
    private var annotationLineBuffer = StringBuilder()

    /**
     * Separate line buffer used exclusively for media-marker scanning.
     *
     * Media parsing runs unconditionally (unlike tool-annotation parsing which
     * is gated behind [parseToolAnnotations]), so it needs its own buffer so
     * disabling the tool-annotation path doesn't silently drop partial media
     * lines that hadn't yet hit a newline.
     *
     * Tracks already-fired tokens per message to avoid duplicate attachments
     * when a marker happens to appear in both real-time streaming and the
     * post-stream finalize reconciliation pass.
     */
    private var mediaLineBuffer = StringBuilder()
    private val dispatchedMediaMarkers = mutableSetOf<String>()

    /**
     * Tracks which tool names currently have an active (in-progress) annotation-based
     * ToolCall, keyed by "messageId:toolName" → toolCallId. This lets us match a
     * completion/failure annotation back to the correct ToolCall.
     */
    private val activeAnnotationTools = mutableMapOf<String, String>()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    // --- Message management ---

    fun addUserMessage(message: ChatMessage) {
        _messages.update { list ->
            (list + message).let { if (it.size > MAX_MESSAGES) it.drop(it.size - MAX_MESSAGES) else it }
        }
    }

    /**
     * Add a placeholder assistant message immediately after the user sends,
     * showing streaming dots before the first SSE delta arrives.
     * Gets filled in naturally when onTextDelta finds the matching ID.
     */
    fun addPlaceholderMessage(message: ChatMessage) {
        _isStreaming.value = true
        _messages.update { list ->
            (list + message).let { if (it.size > MAX_MESSAGES) it.drop(it.size - MAX_MESSAGES) else it }
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
        // Drop any pending line buffers / dedupe state so a fresh session
        // doesn't inherit leftovers from the previous one.
        mediaLineBuffer.clear()
        dispatchedMediaMarkers.clear()
        annotationLineBuffer.clear()
        activeAnnotationTools.clear()
    }

    /**
     * Replace a placeholder message's ID with the server-assigned ID.
     * Only acts on empty, streaming messages to avoid renaming completed turns
     * during multi-turn agent runs.
     */
    fun replaceMessageId(oldId: String, newId: String) {
        _messages.update { messages ->
            messages.map { msg ->
                if (msg.id == oldId && msg.content.isBlank() && msg.isStreaming) {
                    msg.copy(id = newId)
                } else msg
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Apply [transform] to the first message matching [messageId]. Used by
     * [ChatViewModel][com.hermesandroid.relay.viewmodel.ChatViewModel] to
     * mutate attachment state (LOADING → LOADED / FAILED) without having
     * direct access to the private [_messages] StateFlow.
     *
     * No-op if no message matches (e.g. it was trimmed from the rolling
     * MAX_MESSAGES buffer between the callback being queued and executing).
     */
    fun mutateMessage(messageId: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { messages ->
            messages.map { msg ->
                if (msg.id == messageId) transform(msg) else msg
            }
        }
    }

    /**
     * Load message history from API response into the messages list.
     * Replaces current messages with the loaded history.
     * Reconstructs tool calls from assistant messages' tool_calls field.
     *
     * Server-persisted message content still contains raw `MEDIA:...` markers
     * (the streaming-time stripping is a phone-local operation that never
     * mutated server storage), so this pass re-runs the marker parser on each
     * loaded assistant message: strips the marker lines from displayed content
     * and re-fires [onMediaAttachmentRequested] / [onMediaBarePathRequested]
     * so the ViewModel can inject attachments against the freshly-loaded
     * message IDs. Without this, the session_end reload — which happens at
     * every stream complete — would wipe the placeholder the streaming path
     * just injected, and the raw marker text would become visible in the
     * bubble. See the placeholder-flicker fix in DEVLOG 2026-04-11.
     */
    fun loadMessageHistory(items: List<MessageItem>) {
        // Build a map of tool result messages (role:"tool") keyed by tool_call_id
        // so we can attach results back to the originating assistant message's ToolCall
        val toolResults = items.filter { it.role == "tool" }
            .associateBy { it.toolCallId }

        // Accumulator for media markers we find in loaded content — fired AFTER
        // the wholesale `_messages.value = ...` assignment so the ViewModel's
        // mutateMessage lookups find the newly-loaded messages.
        val pendingMediaHits = mutableListOf<Pair<String, MediaMarkerHit>>()

        val loaded = items.mapNotNull { item ->
            val role = when (item.role) {
                "user" -> MessageRole.USER
                "assistant" -> MessageRole.ASSISTANT
                "system" -> MessageRole.SYSTEM
                "tool" -> return@mapNotNull null // Merged into assistant tool calls above
                else -> return@mapNotNull null
            }
            // If > 1e12, already in milliseconds; otherwise convert from seconds
            val ts = item.timestamp ?: 0.0
            val timestampMs = if (ts > 1e12) ts.toLong() else (ts * 1000).toLong()

            // Reconstruct tool calls from assistant messages
            val toolCalls = if (role == MessageRole.ASSISTANT) {
                parseToolCallsFromHistory(item.toolCalls, toolResults)
            } else {
                emptyList()
            }

            val messageId = item.id?.toString() ?: java.util.UUID.randomUUID().toString()
            val rawContent = item.contentText ?: ""

            // Run the media marker parser on assistant content; strip matched
            // lines and queue hits for post-assignment dispatch.
            val cleanedContent = if (role == MessageRole.ASSISTANT && rawContent.isNotEmpty()) {
                extractMediaMarkersFromContent(messageId, rawContent, pendingMediaHits)
            } else {
                rawContent
            }

            ChatMessage(
                id = messageId,
                role = role,
                content = cleanedContent,
                timestamp = timestampMs,
                isStreaming = false,
                toolCalls = toolCalls
            )
        }

        // Reload swaps the entire message list — any stale dedupe entries keyed
        // on pre-reload message IDs are meaningless now. Clear so the hits we
        // just collected against the reloaded IDs are guaranteed to fire.
        dispatchedMediaMarkers.clear()

        _messages.value = if (loaded.size > MAX_MESSAGES) loaded.takeLast(MAX_MESSAGES) else loaded

        // Now that the reloaded messages are in state, fire callbacks so the
        // ViewModel can insert LOADING/FAILED attachments via mutateMessage.
        for ((messageId, hit) in pendingMediaHits) {
            when (hit) {
                is MediaMarkerHit.RelayToken -> {
                    val dedupeKey = "$messageId:relay:${hit.token}"
                    if (dispatchedMediaMarkers.add(dedupeKey)) {
                        Log.d(TAG, "Media marker (relay, reload): token=${hit.token}")
                        onMediaAttachmentRequested(messageId, hit.token)
                    }
                }
                is MediaMarkerHit.BarePath -> {
                    val dedupeKey = "$messageId:bare:${hit.path}"
                    if (dispatchedMediaMarkers.add(dedupeKey)) {
                        Log.d(TAG, "Media marker (bare-path, reload): ${hit.path}")
                        onMediaBarePathRequested(messageId, hit.path)
                    }
                }
            }
        }
    }

    /**
     * Marker hit collected during [loadMessageHistory] for post-assignment dispatch.
     */
    private sealed interface MediaMarkerHit {
        data class RelayToken(val token: String) : MediaMarkerHit
        data class BarePath(val path: String) : MediaMarkerHit
    }

    /**
     * Scan loaded (non-streaming) message content line-by-line for media
     * markers, append hits to [out], and return the content with matched
     * lines removed. Pure function — does NOT mutate [_messages] or fire
     * callbacks. Called from [loadMessageHistory].
     */
    private fun extractMediaMarkersFromContent(
        messageId: String,
        content: String,
        out: MutableList<Pair<String, MediaMarkerHit>>,
    ): String {
        var cleaned = content
        for (rawLine in content.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val relayMatch = mediaRelayRegex.find(trimmed)
            if (relayMatch != null) {
                out.add(messageId to MediaMarkerHit.RelayToken(relayMatch.groupValues[1]))
                cleaned = cleaned
                    .replace("\n$rawLine\n", "\n")
                    .replace("\n$rawLine", "")
                    .replace("$rawLine\n", "")
                    .replace(rawLine, "")
                continue
            }

            val bareMatch = mediaBarePathRegex.find(trimmed)
            if (bareMatch != null) {
                out.add(messageId to MediaMarkerHit.BarePath(bareMatch.groupValues[1]))
                cleaned = cleaned
                    .replace("\n$rawLine\n", "\n")
                    .replace("\n$rawLine", "")
                    .replace("$rawLine\n", "")
                    .replace(rawLine, "")
            }
        }
        return cleaned.trim()
    }

    /**
     * Parse the tool_calls JSON from an assistant message into ToolCall objects.
     * Format: array of objects with {id, type:"function", function: {name, arguments}}
     * or Hermes format: {name, call_id, args, ...}
     */
    private fun parseToolCallsFromHistory(
        toolCallsJson: kotlinx.serialization.json.JsonElement?,
        toolResults: Map<String?, MessageItem>
    ): List<ToolCall> {
        if (toolCallsJson == null || toolCallsJson !is JsonArray) return emptyList()

        return toolCallsJson.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null

            // Try OpenAI format: { id, type:"function", function: { name, arguments } }
            val funcObj = obj["function"] as? JsonObject
            val name: String
            val callId: String?
            val args: String?

            if (funcObj != null) {
                name = (funcObj["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                callId = (obj["id"] as? JsonPrimitive)?.content
                args = (funcObj["arguments"] as? JsonPrimitive)?.content
            } else {
                // Hermes format: { name, call_id, args, ... }
                name = (obj["name"] as? JsonPrimitive)?.content
                    ?: (obj["tool_name"] as? JsonPrimitive)?.content
                    ?: return@mapNotNull null
                callId = (obj["call_id"] as? JsonPrimitive)?.content
                    ?: (obj["id"] as? JsonPrimitive)?.content
                args = obj["args"]?.toString()
            }

            // Check if we have a tool result for this call
            val resultItem = toolResults[callId]
            val resultText = resultItem?.contentText

            ToolCall(
                id = callId,
                name = name,
                args = args,
                result = resultText,
                success = resultText != null, // Has result → completed
                isComplete = true // History items are always complete
            )
        }
    }

    // --- Session management ---

    fun setSessionId(sessionId: String?) {
        _currentSessionId.value = sessionId
    }

    /**
     * Update sessions list from API response.
     */
    fun updateSessions(items: List<SessionItem>) {
        _sessions.value = items.map { item ->
            // If > 1e12, already in milliseconds; otherwise convert from seconds
            val ts = item.startedAt ?: 0.0
            val timestampMs = if (ts > 1e12) ts.toLong() else (ts * 1000).toLong()
            ChatSession(
                sessionId = item.id,
                title = item.title,
                model = item.model,
                messageCount = item.messageCount ?: 0,
                updatedAt = timestampMs
            )
        }
    }

    /**
     * Remove a session from the local list (optimistic delete).
     */
    fun removeSession(sessionId: String) {
        _sessions.update { sessions ->
            sessions.filter { it.sessionId != sessionId }
        }
        if (_currentSessionId.value == sessionId) {
            _currentSessionId.value = null
            clearMessages()
        }
    }

    /**
     * Update a session's title in the local list (optimistic rename).
     */
    fun renameSessionLocal(sessionId: String, newTitle: String) {
        _sessions.update { sessions ->
            sessions.map { s ->
                if (s.sessionId == sessionId) s.copy(title = newTitle) else s
            }
        }
    }

    /**
     * Add a newly created session to the list.
     */
    fun addSession(session: ChatSession) {
        _sessions.update { listOf(session) + it }
    }

    // --- SSE streaming event entry points ---

    /**
     * Tracks whether we are currently inside a `<think>`/`<thinking>` block
     * in the text stream. Content inside these tags is redirected to
     * thinkingContent instead of the main message content.
     */
    private var insideThinkingBlock = false

    fun onTextDelta(messageId: String, delta: String) {
        _isStreaming.value = true

        // Check for inline reasoning tags — some servers embed thinking in the text stream
        val processedDelta = processInlineReasoning(messageId, delta)

        // If all content was redirected to thinking, nothing left for main content
        if (processedDelta.isEmpty()) return

        _messages.update { messages ->
            val existing = messages.findLast {
                it.id == messageId && it.role == MessageRole.ASSISTANT
            }

            if (existing != null) {
                messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(content = msg.content + processedDelta)
                    } else {
                        msg
                    }
                }
            } else {
                messages + ChatMessage(
                    id = messageId,
                    role = MessageRole.ASSISTANT,
                    content = processedDelta,
                    timestamp = System.currentTimeMillis(),
                    isStreaming = true,
                    agentName = activeAgentName
                )
            }
        }

        // Scan for tool annotations in the text stream
        if (parseToolAnnotations) {
            scanForToolAnnotations(messageId, processedDelta)
        }

        // Always scan for media markers — inbound attachments are a first-class
        // feature and shouldn't be gated behind the tool-annotation flag.
        scanForMediaMarkers(messageId, processedDelta)
    }

    /**
     * Detect and extract inline `<think>`/`<thinking>` blocks from the text stream.
     * Content inside these tags is redirected to onThinkingDelta.
     * Returns the remaining non-thinking content.
     */
    private fun processInlineReasoning(messageId: String, delta: String): String {
        // Fast path: no tags in delta and not inside a block
        if (!insideThinkingBlock && !delta.contains("<think", ignoreCase = true)) {
            return delta
        }

        val result = StringBuilder()
        var remaining = delta

        while (remaining.isNotEmpty()) {
            if (insideThinkingBlock) {
                // Look for closing tag
                val closeIdx = remaining.indexOfClose()
                if (closeIdx != -1) {
                    // Extract thinking content before the close tag
                    val thinkingPart = remaining.substring(0, closeIdx)
                    if (thinkingPart.isNotEmpty()) {
                        onThinkingDelta(messageId, thinkingPart)
                    }
                    // Skip past the closing tag
                    val tagEnd = remaining.indexOf('>', closeIdx) + 1
                    remaining = if (tagEnd > 0) remaining.substring(tagEnd) else ""
                    insideThinkingBlock = false
                } else {
                    // Entire remaining is thinking content
                    onThinkingDelta(messageId, remaining)
                    remaining = ""
                }
            } else {
                // Look for opening tag
                val openIdx = remaining.indexOfOpen()
                if (openIdx != -1) {
                    // Content before the tag is regular text
                    val beforeTag = remaining.substring(0, openIdx)
                    result.append(beforeTag)
                    // Skip past the opening tag
                    val tagEnd = remaining.indexOf('>', openIdx) + 1
                    remaining = if (tagEnd > 0) remaining.substring(tagEnd) else ""
                    insideThinkingBlock = true
                } else {
                    // No tags — all regular content
                    result.append(remaining)
                    remaining = ""
                }
            }
        }

        return result.toString()
    }

    /** Find index of `</think>` or `</thinking>` closing tag. */
    private fun String.indexOfClose(): Int {
        val i1 = indexOf("</think>", ignoreCase = true)
        val i2 = indexOf("</thinking>", ignoreCase = true)
        return when {
            i1 == -1 -> i2
            i2 == -1 -> i1
            else -> minOf(i1, i2)
        }
    }

    /** Find index of `<think>` or `<thinking>` opening tag. */
    private fun String.indexOfOpen(): Int {
        val i1 = indexOf("<think>", ignoreCase = true)
        val i2 = indexOf("<thinking>", ignoreCase = true)
        return when {
            i1 == -1 -> i2
            i2 == -1 -> i1
            else -> minOf(i1, i2)
        }
    }

    // --- Tool annotation parsing ---

    /**
     * Accumulate incoming text in a line buffer and scan completed lines for
     * tool annotation patterns. Annotations are line-oriented, so we only
     * attempt matching once we have a full line (terminated by newline).
     *
     * If the stream completes with a partial line still in the buffer, it is
     * flushed in [onStreamComplete].
     */
    private fun scanForToolAnnotations(messageId: String, delta: String) {
        annotationLineBuffer.append(delta)

        // Process all complete lines (newline-terminated)
        while (true) {
            val newlineIndex = annotationLineBuffer.indexOf('\n')
            if (newlineIndex == -1) break

            val line = annotationLineBuffer.substring(0, newlineIndex)
            annotationLineBuffer.delete(0, newlineIndex + 1)

            val trimmed = line.trim()
            if (parseAnnotationLine(messageId, trimmed)) {
                // Matched — strip this annotation line from the message content
                stripLineFromContent(messageId, trimmed)
            }
        }
    }

    /**
     * Accumulate incoming text in a dedicated media line buffer and scan
     * completed lines for media markers. Runs independently of the
     * tool-annotation pipeline so inbound files always render, regardless of
     * whether the user has enabled `parseToolAnnotations`.
     *
     * Matched markers fire [onMediaAttachmentRequested] / [onMediaBarePathRequested]
     * and the raw line is stripped from the visible message content (via
     * [stripLineFromContent]) so the user sees the rendered attachment card
     * instead of the literal `MEDIA:...` text.
     */
    private fun scanForMediaMarkers(messageId: String, delta: String) {
        mediaLineBuffer.append(delta)

        while (true) {
            val newlineIndex = mediaLineBuffer.indexOf('\n')
            if (newlineIndex == -1) break

            val line = mediaLineBuffer.substring(0, newlineIndex)
            mediaLineBuffer.delete(0, newlineIndex + 1)

            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (tryDispatchMediaMarker(messageId, trimmed)) {
                stripLineFromContent(messageId, trimmed)
            }
        }
    }

    /**
     * Inspect [line] for a media marker and dispatch the appropriate callback.
     *
     *  - `MEDIA:hermes-relay://<token>` → [onMediaAttachmentRequested]
     *  - bare `MEDIA:/path` (the whole line, no other content) →
     *    [onMediaBarePathRequested]
     *
     * De-dupes via [dispatchedMediaMarkers] so the same token doesn't fire
     * twice (e.g. once during streaming and again during finalize).
     *
     * Returns true when a marker was matched so the caller can strip the line.
     */
    private fun tryDispatchMediaMarker(messageId: String, line: String): Boolean {
        val relayMatch = mediaRelayRegex.find(line)
        if (relayMatch != null) {
            val token = relayMatch.groupValues[1]
            val dedupeKey = "$messageId:relay:$token"
            if (dispatchedMediaMarkers.add(dedupeKey)) {
                Log.d(TAG, "Media marker (relay): token=$token")
                onMediaAttachmentRequested(messageId, token)
            }
            return true
        }

        val bareMatch = mediaBarePathRegex.find(line)
        if (bareMatch != null) {
            val path = bareMatch.groupValues[1]
            val dedupeKey = "$messageId:bare:$path"
            if (dispatchedMediaMarkers.add(dedupeKey)) {
                Log.d(TAG, "Media marker (bare-path, unavailable): $path")
                onMediaBarePathRequested(messageId, path)
            }
            return true
        }

        return false
    }

    /**
     * Remove a matched annotation line from the message's displayed content.
     * This prevents the raw annotation text (e.g., `💻 terminal`) from showing
     * in the chat bubble alongside the ToolCall card.
     */
    private fun stripLineFromContent(messageId: String, line: String) {
        _messages.update { messages ->
            messages.map { msg ->
                if (msg.id == messageId && msg.role == MessageRole.ASSISTANT) {
                    // Remove the line (with surrounding newlines) from content
                    val cleaned = msg.content
                        .replace("\n$line\n", "\n")
                        .replace("\n$line", "")
                        .replace("$line\n", "")
                        .replace(line, "")
                        .trim()
                    msg.copy(content = cleaned)
                } else msg
            }
        }
    }

    /**
     * Check a single completed line for a tool annotation pattern and
     * dispatch the appropriate tool call event.
     *
     * Hermes injects tool progress as backtick-wrapped inline markdown:
     *   `💻 terminal`         — first occurrence = tool start
     *   `💻 terminal`         — second occurrence of same tool = tool complete
     *   `✅ terminal`         — explicit completion emoji
     *   `❌ terminal`         — explicit failure emoji
     *
     * Also supports verbose format:
     *   🔧 Running: terminal
     *   ✅ Completed: terminal
     *   ❌ Failed: terminal
     */
    /**
     * Returns true if the line matched an annotation pattern (caller should strip it from content).
     */
    private fun parseAnnotationLine(messageId: String, line: String): Boolean {
        if (line.isEmpty()) return false

        // --- Media markers (run first so they're always detected, even when
        // parseToolAnnotations is off — the scanForMediaMarkers path handles
        // the streaming case, this branch handles finalize reconciliation) ---
        if (tryDispatchMediaMarker(messageId, line)) {
            return true
        }

        // --- Format 1: Backtick-wrapped `<emoji> <tool_name>` ---
        toolAnnotationBacktickRegex.find(line)?.let { match ->
            val emojiToken = match.groupValues[1]
            val toolName = match.groupValues[2].trim()
            if (toolName.isEmpty()) return false

            val key = "$messageId:$toolName"

            when {
                // Explicit failure emoji
                failureEmojis.any { emojiToken.contains(it) } -> {
                    val toolCallId = activeAnnotationTools.remove(key)
                    if (toolCallId != null) {
                        onToolCallFailed(messageId, toolCallId, null)
                        Log.d(TAG, "Annotation tool failed (backtick): $toolName")
                    }
                }
                // Explicit completion emoji
                completionEmojis.any { emojiToken.contains(it) } -> {
                    val toolCallId = activeAnnotationTools.remove(key)
                    if (toolCallId != null) {
                        onToolCallComplete(messageId, toolCallId, null)
                        Log.d(TAG, "Annotation tool complete (backtick): $toolName")
                    }
                }
                // Tool-type emoji — first occurrence = start, second = complete
                activeAnnotationTools.containsKey(key) -> {
                    val toolCallId = activeAnnotationTools.remove(key)
                    if (toolCallId != null) {
                        onToolCallComplete(messageId, toolCallId, null)
                        Log.d(TAG, "Annotation tool complete (repeat): $toolName")
                    }
                }
                else -> {
                    val toolCallId = "annotation-${toolName.replace(" ", "_")}-${System.currentTimeMillis()}"
                    activeAnnotationTools[key] = toolCallId
                    onToolCallStart(messageId, toolCallId, toolName)
                    Log.d(TAG, "Annotation tool start (backtick): $toolName [$emojiToken]")
                }
            }
            return true
        }

        // --- Format 2: Verbose bare emoji ---
        toolAnnotationVerboseStartRegex.find(line)?.let { match ->
            val toolName = match.groupValues[2].trim()
            val toolCallId = "annotation-${toolName.replace(" ", "_")}-${System.currentTimeMillis()}"
            activeAnnotationTools["$messageId:$toolName"] = toolCallId
            onToolCallStart(messageId, toolCallId, toolName)
            Log.d(TAG, "Annotation tool start (verbose): $toolName")
            return true
        }

        toolAnnotationVerboseCompleteRegex.find(line)?.let { match ->
            val toolName = match.groupValues[2].trim()
            val key = "$messageId:$toolName"
            val toolCallId = activeAnnotationTools.remove(key) ?: return false
            onToolCallComplete(messageId, toolCallId, null)
            Log.d(TAG, "Annotation tool complete (verbose): $toolName")
            return true
        }

        toolAnnotationVerboseFailedRegex.find(line)?.let { match ->
            val toolName = match.groupValues[2].trim()
            val key = "$messageId:$toolName"
            val toolCallId = activeAnnotationTools.remove(key) ?: return false
            onToolCallFailed(messageId, toolCallId, null)
            Log.d(TAG, "Annotation tool failed (verbose): $toolName")
            return true
        }

        return false
    }

    /**
     * Flush any remaining partial line in the media buffer and re-scan the
     * final message content for any media markers that survived real-time
     * stripping. Called unconditionally from [onStreamComplete] and
     * [onTurnComplete] — inbound media is a first-class feature regardless
     * of whether tool-annotation parsing is enabled.
     */
    private fun finalizeMediaMarkers(messageId: String) {
        // Drain any partial line (last media marker without trailing newline).
        if (mediaLineBuffer.isNotEmpty()) {
            val remaining = mediaLineBuffer.toString().trim()
            mediaLineBuffer.clear()
            if (remaining.isNotEmpty() && tryDispatchMediaMarker(messageId, remaining)) {
                stripLineFromContent(messageId, remaining)
            }
        }

        // Post-stream reconciliation: re-scan the final content for markers
        // that raced with stripLineFromContent during streaming.
        _messages.update { messages ->
            messages.map { msg ->
                if (msg.id != messageId || msg.role != MessageRole.ASSISTANT) return@map msg
                var cleaned = msg.content
                var changed = false
                for (rawLine in msg.content.lines()) {
                    val trimmed = rawLine.trim()
                    if (trimmed.isEmpty()) continue
                    if (mediaRelayRegex.containsMatchIn(trimmed) ||
                        mediaBarePathRegex.containsMatchIn(trimmed)
                    ) {
                        tryDispatchMediaMarker(messageId, trimmed)
                        cleaned = cleaned
                            .replace("\n$rawLine\n", "\n")
                            .replace("\n$rawLine", "")
                            .replace("$rawLine\n", "")
                            .replace(rawLine, "")
                        changed = true
                    }
                }
                if (changed) msg.copy(content = cleaned.trim()) else msg
            }
        }
    }

    /**
     * Flush any remaining partial line in the annotation buffer.
     * Called when the stream ends so we don't miss annotations that
     * arrived without a trailing newline.
     */
    private fun flushAnnotationBuffer(messageId: String) {
        if (annotationLineBuffer.isNotEmpty()) {
            val remaining = annotationLineBuffer.toString().trim()
            annotationLineBuffer.clear()
            if (parseAnnotationLine(messageId, remaining)) {
                stripLineFromContent(messageId, remaining)
            }
        }
        // Clean up any active annotation tools for this message that never completed
        val keysToRemove = activeAnnotationTools.keys.filter { it.startsWith("$messageId:") }
        keysToRemove.forEach { activeAnnotationTools.remove(it) }
    }

    /**
     * Post-stream reconciliation: re-scan the final message content for any
     * annotation text that survived the real-time stripping (due to
     * update-ordering races between onTextDelta and stripLineFromContent).
     *
     * Also marks any still-active annotation tool calls as completed, since
     * the stream is over and they'll never get a closing annotation.
     *
     * This is the "session_end hook" — called once from [onStreamComplete].
     */
    private fun finalizeAnnotations(messageId: String) {
        _messages.update { messages ->
            messages.map { msg ->
                if (msg.id != messageId || msg.role != MessageRole.ASSISTANT) return@map msg

                val existingToolNames = msg.toolCalls.map { it.name }.toSet()
                val newToolCalls = mutableListOf<ToolCall>()
                var cleaned = msg.content

                // Re-scan every line for annotation patterns that weren't stripped
                for (rawLine in msg.content.lines()) {
                    val trimmed = rawLine.trim()
                    if (trimmed.isEmpty()) continue

                    val toolName = matchAnnotationToolName(trimmed) ?: continue

                    // Strip the raw line (preserving surrounding structure)
                    cleaned = cleaned
                        .replace("\n$rawLine\n", "\n")
                        .replace("\n$rawLine", "")
                        .replace("$rawLine\n", "")
                        .replace(rawLine, "")

                    // If no tool call was created during streaming, add one now
                    if (toolName !in existingToolNames &&
                        newToolCalls.none { it.name == toolName }
                    ) {
                        newToolCalls.add(
                            ToolCall(
                                id = "finalized-${toolName.replace(" ", "_")}-${System.currentTimeMillis()}",
                                name = toolName,
                                args = null,
                                result = null,
                                success = true,
                                isComplete = true
                            )
                        )
                    }
                }

                // Mark any in-progress annotation tool calls as completed
                // (the stream ended, so they'll never get a closing annotation)
                val reconciledCalls = msg.toolCalls.map { call ->
                    if (!call.isComplete && call.id?.startsWith("annotation-") == true) {
                        call.copy(success = true, isComplete = true, completedAt = System.currentTimeMillis())
                    } else call
                }

                val finalCalls = reconciledCalls + newToolCalls
                val finalContent = cleaned.trim()

                if (finalCalls == msg.toolCalls && finalContent == msg.content) return@map msg
                msg.copy(content = finalContent, toolCalls = finalCalls)
            }
        }
    }

    /**
     * Extract the tool name from an annotation line, regardless of whether
     * it's a start, complete, or failure annotation. Returns null if the
     * line doesn't match any annotation pattern.
     */
    private fun matchAnnotationToolName(line: String): String? {
        toolAnnotationBacktickRegex.find(line)?.let {
            return it.groupValues[2].trim().ifEmpty { null }
        }
        toolAnnotationVerboseStartRegex.find(line)?.let {
            return it.groupValues[2].trim().ifEmpty { null }
        }
        toolAnnotationVerboseCompleteRegex.find(line)?.let {
            return it.groupValues[2].trim().ifEmpty { null }
        }
        toolAnnotationVerboseFailedRegex.find(line)?.let {
            return it.groupValues[2].trim().ifEmpty { null }
        }
        return null
    }

    fun onToolCallStart(messageId: String, toolCallId: String, toolName: String) {
        _isStreaming.value = true

        val toolCall = ToolCall(
            id = toolCallId,
            name = toolName,
            args = null,
            result = null,
            success = null,
            isComplete = false
        )

        _messages.update { messages ->
            val target = messages.findLast {
                it.id == messageId && it.role == MessageRole.ASSISTANT
            }
            if (target != null) {
                messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(toolCalls = msg.toolCalls + toolCall)
                    } else {
                        msg
                    }
                }
            } else {
                messages + ChatMessage(
                    id = messageId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    timestamp = System.currentTimeMillis(),
                    isStreaming = true,
                    toolCalls = listOf(toolCall),
                    agentName = activeAgentName
                )
            }
        }
    }

    fun onToolCallComplete(messageId: String, toolCallId: String, resultPreview: String? = null) {
        _messages.update { messages ->
            messages.map { msg ->
                if (msg.id == messageId && msg.role == MessageRole.ASSISTANT) {
                    val updatedCalls = msg.toolCalls.map { call ->
                        if (call.id == toolCallId && !call.isComplete) {
                            call.copy(
                                success = true,
                                isComplete = true,
                                result = resultPreview ?: call.result,
                                completedAt = System.currentTimeMillis()
                            )
                        } else {
                            call
                        }
                    }
                    msg.copy(toolCalls = updatedCalls)
                } else {
                    msg
                }
            }
        }
    }

    fun onToolCallFailed(messageId: String, toolCallId: String, error: String?) {
        _messages.update { messages ->
            messages.map { msg ->
                if (msg.id == messageId && msg.role == MessageRole.ASSISTANT) {
                    val updatedCalls = msg.toolCalls.map { call ->
                        if (call.id == toolCallId && !call.isComplete) {
                            call.copy(
                                success = false,
                                isComplete = true,
                                error = error,
                                completedAt = System.currentTimeMillis()
                            )
                        } else {
                            call
                        }
                    }
                    msg.copy(toolCalls = updatedCalls)
                } else {
                    msg
                }
            }
        }
    }

    /**
     * A single assistant turn completed, but the agent run may continue
     * (e.g., tool calls pending → next assistant turn). Marks the current
     * message as no longer streaming but keeps the global isStreaming flag
     * active so the UI continues showing progress.
     */
    fun onTurnComplete(messageId: String) {
        // Flush annotations for this turn
        if (parseToolAnnotations) {
            flushAnnotationBuffer(messageId)
        }

        _messages.update { messages ->
            messages.map { msg ->
                if (msg.id == messageId) {
                    msg.copy(isStreaming = false, isThinkingStreaming = false)
                } else {
                    msg
                }
            }
        }

        // Clean up any surviving annotation text from this turn
        if (parseToolAnnotations) {
            finalizeAnnotations(messageId)
        }

        // Finalize media markers unconditionally (not gated by parseToolAnnotations)
        finalizeMediaMarkers(messageId)
        // Note: do NOT set _isStreaming to false — the run is still active
    }

    /**
     * The entire agent run is complete (run.completed / done).
     * Marks the stream as finished and finalizes all messages.
     */
    fun onStreamComplete(messageId: String) {
        _isStreaming.value = false
        insideThinkingBlock = false

        // Flush any remaining annotation text that didn't end with a newline
        if (parseToolAnnotations) {
            flushAnnotationBuffer(messageId)
        }

        _messages.update { messages ->
            messages.map { msg ->
                if (msg.id == messageId || msg.isStreaming) {
                    msg.copy(isStreaming = false, isThinkingStreaming = false)
                } else {
                    msg
                }
            }
        }

        // Post-stream reconciliation: re-scan final content for any annotation
        // text that survived real-time stripping (update-ordering races)
        if (parseToolAnnotations) {
            finalizeAnnotations(messageId)
        }

        // Finalize media markers unconditionally
        finalizeMediaMarkers(messageId)
    }

    fun onStreamError(message: String) {
        _isStreaming.value = false
        _error.value = message
        // Clear streaming flag on any actively streaming message
        _messages.update { messages ->
            messages.map { msg ->
                if (msg.isStreaming || msg.isThinkingStreaming) {
                    msg.copy(isStreaming = false, isThinkingStreaming = false)
                } else msg
            }
        }
    }

    fun onThinkingDelta(messageId: String, delta: String) {
        _isStreaming.value = true
        _messages.update { messages ->
            val existing = messages.findLast { it.id == messageId && it.role == MessageRole.ASSISTANT }
            if (existing != null) {
                messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(
                            thinkingContent = msg.thinkingContent + delta,
                            isThinkingStreaming = true
                        )
                    } else msg
                }
            } else {
                messages + ChatMessage(
                    id = messageId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    thinkingContent = delta,
                    isThinkingStreaming = true,
                    timestamp = System.currentTimeMillis(),
                    isStreaming = true,
                    agentName = activeAgentName
                )
            }
        }
    }

    fun onUsageReceived(messageId: String, inputTokens: Int?, outputTokens: Int?, totalTokens: Int?, cost: Double?) {
        _messages.update { messages ->
            messages.map { msg ->
                if (msg.id == messageId) {
                    msg.copy(
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        totalTokens = totalTokens,
                        estimatedCost = cost
                    )
                } else msg
            }
        }
    }

    // --- Retry support ---

    private val _lastSentMessage = MutableStateFlow<String?>(null)
    val lastSentMessage: StateFlow<String?> = _lastSentMessage.asStateFlow()

    fun setLastSentMessage(text: String) {
        _lastSentMessage.value = text
    }
}

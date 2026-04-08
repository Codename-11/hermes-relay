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
        // Known completion/failure emojis — if these appear in backtick format, it's a completion
        private val completionEmojis = setOf("✅", "✓", "☑")
        private val failureEmojis = setOf("❌", "✗", "⚠")
    }

    /** Whether to parse tool annotations from assistant text (for servers that don't emit tool events). */
    var parseToolAnnotations: Boolean = true

    /** Active personality/agent name — set by ChatViewModel before each stream. Included on new assistant messages. */
    var activeAgentName: String? = null

    /**
     * Buffer for incomplete lines during streaming. Tool annotations are line-oriented
     * (backtick + emoji + tool_name + backtick), so we accumulate text until we see a
     * newline and then scan completed lines. This handles the case where a single
     * annotation is split across multiple SSE deltas.
     */
    private var annotationLineBuffer = StringBuilder()

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
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Load message history from API response into the messages list.
     * Replaces current messages with the loaded history.
     * Reconstructs tool calls from assistant messages' tool_calls field.
     */
    fun loadMessageHistory(items: List<MessageItem>) {
        // Build a map of tool result messages (role:"tool") keyed by tool_call_id
        // so we can attach results back to the originating assistant message's ToolCall
        val toolResults = items.filter { it.role == "tool" }
            .associateBy { it.toolCallId }

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

            ChatMessage(
                id = item.id?.toString() ?: java.util.UUID.randomUUID().toString(),
                role = role,
                content = item.contentText ?: "",
                timestamp = timestampMs,
                isStreaming = false,
                toolCalls = toolCalls
            )
        }
        _messages.value = if (loaded.size > MAX_MESSAGES) loaded.takeLast(MAX_MESSAGES) else loaded
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

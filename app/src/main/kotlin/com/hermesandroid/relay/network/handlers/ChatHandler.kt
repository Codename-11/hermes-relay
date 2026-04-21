package com.hermesandroid.relay.network.handlers

import android.util.Log
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.HermesCard
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.ToolCall
import com.hermesandroid.relay.data.VoiceIntentTrace
import com.hermesandroid.relay.network.models.MessageItem
import com.hermesandroid.relay.network.models.SessionItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        // Rich card marker — single line, full JSON object payload.
        //
        // Agents emit:
        //   CARD:{"type":"approval_request","title":"...","actions":[...]}
        //
        // Constraints (mirrors the `MEDIA:` marker contract in
        // hermes-agent's prompt_builder.py): the marker MUST live on its
        // own line, and the JSON must be single-line (escape newlines in
        // string fields as `\n`). This keeps the line-buffer parser
        // trivial — same strategy as MEDIA.
        //
        // The regex is intentionally greedy on the JSON body so nested
        // braces in fields/actions are captured correctly. Invalid JSON is
        // logged and the line is left in content untouched so the user
        // still sees _something_ rather than a silent drop.
        private val cardMarkerRegex = Regex("""^\s*CARD:(\{.*\})\s*$""")
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
     * Separate line buffer + dedupe set for rich-card markers, mirroring
     * the media-marker pipeline above. Cards are a first-class feature
     * (not gated behind any flag), so they need their own buffer for the
     * same reason `MEDIA:` does — tool-annotation parsing can be toggled
     * off without silently dropping partial card lines.
     */
    private var cardLineBuffer = StringBuilder()
    private val dispatchedCardMarkers = mutableSetOf<String>()

    /**
     * Lenient JSON for card payloads. `ignoreUnknownKeys` means future
     * schema additions (new card types, new field shapes) won't crash
     * older phone builds — the renderer's unknown-type fallback handles
     * display.
     */
    private val cardJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

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
     * Append a local-only voice-intent trace to the chat scroll. Used by
     * the sideload voice intent flow (`RealVoiceBridgeIntentHandler`) so
     * phone-control utterances like "open Chrome" or "text Sam" leave a
     * visible record in chat history rather than vanishing into a side-
     * channel the user can't see.
     *
     * Adds two messages back-to-back:
     *  - a user message with the raw transcribed text
     *  - an assistant message with the action description
     *
     * The two bubbles are injected straight into [_messages] for
     * visual continuity. Server-side session sync — so the gateway LLM
     * sees prior voice actions in its memory when the user follows up
     * via text or voice — is handled by a separate path (v0.4.1):
     * the post-dispatch result bubble (emitted from
     * [com.hermesandroid.relay.viewmodel.VoiceViewModel]'s
     * `onDispatchResult` callback into [appendLocalVoiceIntentResult])
     * carries a structured [com.hermesandroid.relay.data.VoiceIntentTrace],
     * which [com.hermesandroid.relay.voice.VoiceIntentSyncBuilder]
     * reads on the next chat send to synthesize an OpenAI
     * `assistant` (with `tool_calls`) + `tool` message pair that
     * rides under the request body's `messages` field. This pre-
     * dispatch bubble carries no trace — it's purely a UI marker.
     *
     * @param userText The raw transcribed voice utterance.
     * @param actionDescription Human-readable description of what the
     *   bridge layer did (or is about to do). Shown verbatim in the
     *   assistant message bubble.
     */
    fun appendLocalVoiceIntentTrace(
        userText: String,
        actionDescription: String,
        voiceIntent: VoiceIntentTrace? = null,
    ) {
        val ts = System.currentTimeMillis()
        val userMsg = ChatMessage(
            id = "voice-intent-user-$ts",
            role = MessageRole.USER,
            content = userText,
            timestamp = ts,
        )
        val assistantMsg = ChatMessage(
            id = "voice-intent-action-$ts",
            role = MessageRole.ASSISTANT,
            content = actionDescription,
            timestamp = ts + 1,
            // Mark the personality so the bubble doesn't render under the
            // current personality's avatar (which would imply the LLM said
            // it). The chat UI's existing agentName plumbing handles the
            // alternate label automatically.
            agentName = "Voice action",
            // Structured trace — read by VoiceIntentSyncBuilder on the next
            // chat send to materialize OpenAI-format tool_call + tool
            // messages so the server-side LLM sees the action in its
            // session memory. Null for the pre-dispatch user bubble (the
            // raw transcribed utterance carries no structure on its own).
            voiceIntent = voiceIntent,
        )
        _messages.update { list ->
            (list + userMsg + assistantMsg).let {
                if (it.size > MAX_MESSAGES) it.drop(it.size - MAX_MESSAGES) else it
            }
        }
    }

    /**
     * Append ONLY an assistant-role bubble showing the post-dispatch
     * outcome of a voice intent action (e.g. "SMS sent", "user denied",
     * "permission missing"). Called by [ChatViewModel.recordVoiceIntentResult]
     * after the phone-side executor returns, so the user sees the actual
     * result of the destructive-verb flow instead of just the pre-dispatch
     * preview. ID prefix `voice-intent-result-` makes this survive
     * [loadMessageHistory] reloads the same way the pre-dispatch trace
     * does, and also lets [CompactTranscriptRow] in voice mode render it
     * via MarkdownContent.
     *
     * [agentName] defaults to "Voice action" for the voice-mode origin
     * (classifier → sideload handler path) but chat mode tool-call
     * parity passes "Phone action" so the label reflects that the bubble
     * is a structured trace of an LLM-initiated android_* tool call
     * rather than a user utterance classified and dispatched locally.
     */
    fun appendLocalVoiceIntentResult(
        description: String,
        agentName: String = "Voice action",
        voiceIntent: VoiceIntentTrace? = null,
    ) {
        val ts = System.currentTimeMillis()
        val resultMsg = ChatMessage(
            id = "voice-intent-result-$ts",
            role = MessageRole.ASSISTANT,
            content = description,
            timestamp = ts,
            agentName = agentName,
            // When the dispatch result lands AFTER the pre-dispatch trace
            // (destructive intents wait on the 5-second countdown), the
            // post-dispatch bubble carries the authoritative success /
            // failure data — this is the bubble VoiceIntentSyncBuilder
            // should read for the synthetic tool-response, not the
            // pre-dispatch placeholder. The VoiceViewModel post-dispatch
            // path passes a fully-populated VoiceIntentTrace here; safe
            // intents (where the dispatch already happened before the
            // pre-dispatch trace was appended) leave this null and rely
            // on the pre-dispatch trace's voiceIntent field.
            voiceIntent = voiceIntent,
        )
        _messages.update { list ->
            (list + resultMsg).let {
                if (it.size > MAX_MESSAGES) it.drop(it.size - MAX_MESSAGES) else it
            }
        }
    }

    /**
     * Flip [VoiceIntentTrace.syncedToServer] to true on every voice-intent
     * message currently in the message list, so they're not re-emitted in
     * the next chat payload's synthetic-messages array. Called from
     * [com.hermesandroid.relay.viewmodel.ChatViewModel.startStream]
     * immediately after the API client takes ownership of the request — at
     * that point the server-side session has the synthetic context, and
     * future sends should not duplicate it.
     *
     * Only mutates messages where the embedded
     * [com.hermesandroid.relay.data.VoiceIntentTrace.syncedToServer] is
     * currently false, so this is safe to call repeatedly without
     * triggering redundant StateFlow emissions.
     */
    fun markVoiceIntentsSynced() {
        _messages.update { messages ->
            var changed = false
            val mapped = messages.map { msg ->
                val trace = msg.voiceIntent
                if (trace != null && !trace.syncedToServer) {
                    changed = true
                    msg.copy(voiceIntent = trace.copy(syncedToServer = true))
                } else msg
            }
            if (changed) mapped else messages
        }
    }

    /**
     * Twin of [markVoiceIntentsSynced] for rich-card action dispatches.
     * Flips [com.hermesandroid.relay.data.HermesCardDispatch.syncedToServer]
     * to true on every dispatch whose flag is currently false, so the
     * [com.hermesandroid.relay.viewmodel.CardDispatchSyncBuilder] doesn't
     * re-emit them on the next chat send. Called from
     * [com.hermesandroid.relay.viewmodel.ChatViewModel.startStream] at the
     * same point — right after the API client accepts the request — so
     * the voice-intent and card-dispatch sync paths have identical
     * commit-timing semantics and a thrown request-building exception
     * falsely marks neither stream as synced.
     */
    fun markCardDispatchesSynced() {
        _messages.update { messages ->
            var changed = false
            val mapped = messages.map { msg ->
                if (msg.cardDispatches.isEmpty()) return@map msg
                val anyUnsynced = msg.cardDispatches.any { !it.syncedToServer }
                if (!anyUnsynced) return@map msg
                changed = true
                msg.copy(
                    cardDispatches = msg.cardDispatches.map {
                        if (it.syncedToServer) it else it.copy(syncedToServer = true)
                    }
                )
            }
            if (changed) mapped else messages
        }
    }

    /**
     * Reusable lenient JSON parser for tool-result previews. [Json { ... }]
     * is cheap to construct but we share one instance so per-tool-completion
     * parsing doesn't churn allocations.
     */
    private val phoneActionResultJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Inspect a just-completed android_* tool call and, if it's an ACTION
     * tool (not a read-only probe or UI micro-action), synthesize a
     * [LocalDispatchResult]-shaped outcome from [resultPreview] and emit a
     * structured follow-up bubble via [appendLocalVoiceIntentResult]. This
     * gives chat-mode tool calls the same post-dispatch feedback the voice
     * flow got in 0.4.1, so the user always sees a visible success/failure
     * trace even when the LLM's narration is lossy or quiet.
     *
     * [isFailure] true means we're being called from [onToolCallFailed] —
     * in that case [resultPreview] is the error string, not a JSON blob,
     * and we skip the parse.
     */
    private fun maybeEmitPhoneActionBubble(
        toolName: String,
        resultPreview: String?,
        isFailure: Boolean,
    ) {
        val label = labelForAndroidTool(toolName) ?: return

        val synthetic = if (isFailure) {
            LocalDispatchResult(
                status = 500,
                errorMessage = resultPreview?.ifBlank { null },
                errorCode = null,
                resultJson = null,
            )
        } else {
            parseAndroidToolResult(resultPreview)
        }

        val description = formatPhoneActionResult(label, synthetic)
        appendLocalVoiceIntentResult(description, agentName = "Phone action")
    }

    /**
     * Map an `android_*` tool name to a short human-readable action label,
     * or null if the tool is read-only / UI-micro and should NOT emit a
     * result bubble.
     *
     * Philosophy: only meaningful action-completion states earn a bubble.
     * Read probes (`android_read_screen`, `android_get_apps`) and UI
     * micro-actions (`android_tap`, `android_swipe`) already render as a
     * [com.hermesandroid.relay.data.ToolCall] card on the assistant
     * message, so emitting a second bubble for each would just spam the
     * scrollback.
     */
    private fun labelForAndroidTool(toolName: String): String? {
        if (!toolName.startsWith("android_")) return null
        return when (toolName) {
            "android_send_sms"        -> "Send SMS"
            "android_call"            -> "Call"
            "android_search_contacts" -> "Search Contacts"
            "android_open_app"        -> "Open App"
            "android_return_to_hermes" -> "Return to Hermes"
            "android_screenshot"      -> "Screenshot"
            "android_press_key"       -> "Key Press"
            "android_setup"           -> "Bridge Setup"
            // Read-only / UI micro-actions — intentionally skipped.
            // The ToolProgressCard on the assistant bubble already
            // surfaces these inline; an extra result bubble would be
            // noise, not signal.
            "android_read_screen",
            "android_find_nodes",
            "android_tap",
            "android_tap_text",
            "android_long_press",
            "android_type",
            "android_swipe",
            "android_scroll",
            "android_drag",
            "android_wait",
            "android_get_apps",
            "android_current_app",
            "android_describe_node",
            "android_ping",
            "android_clipboard_read",
            "android_clipboard_write",
            "android_media",
            "android_screen_hash",
            "android_diff_screen",
            "android_events",
            "android_event_stream",
            "android_location",
            "android_macro",
            "android_send_intent",
            "android_broadcast" -> null
            // Unknown android_* tools still get a generic label so new
            // additions don't vanish silently — the catchall in
            // [formatPhoneActionResult] handles them.
            else -> toolName
                .removePrefix("android_")
                .replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Parse an `android_*` tool result JSON preview into a
     * [LocalDispatchResult]-shaped struct so [formatPhoneActionResult]
     * can reuse the voice-mode formatter verbatim. Plugin handlers in
     * `plugin/tools/android_tool.py` return `json.dumps(data)` with
     * `ok: bool` / `error: str` fields, so we just look those up.
     *
     * Fails open: if the preview is missing, blank, truncated, or
     * otherwise unparseable we return a success result with no error
     * message. A streaming tool completion should never crash chat just
     * because the result preview was malformed.
     */
    private fun parseAndroidToolResult(resultPreview: String?): LocalDispatchResult {
        val raw = resultPreview?.trim().orEmpty()
        if (raw.isEmpty() || !raw.startsWith("{")) {
            return LocalDispatchResult(
                status = 200,
                errorMessage = null,
                errorCode = null,
                resultJson = null,
            )
        }
        return try {
            val obj = phoneActionResultJson.parseToJsonElement(raw).jsonObject
            val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull
            val errorMsg = obj["error"]?.jsonPrimitive?.contentOrNull
                ?: obj["message"]?.jsonPrimitive?.contentOrNull
            val errorCode = obj["error_code"]?.jsonPrimitive?.contentOrNull
                ?: obj["code"]?.jsonPrimitive?.contentOrNull
            val status = when {
                ok == true -> 200
                ok == false -> 400
                errorMsg != null -> 400
                else -> 200
            }
            LocalDispatchResult(
                status = status,
                errorMessage = errorMsg,
                errorCode = errorCode,
                resultJson = obj,
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseAndroidToolResult: unparseable preview (${e.message})")
            LocalDispatchResult(
                status = 200,
                errorMessage = null,
                errorCode = null,
                resultJson = null,
            )
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
        cardLineBuffer.clear()
        dispatchedCardMarkers.clear()
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
            val afterMedia = if (role == MessageRole.ASSISTANT && rawContent.isNotEmpty()) {
                extractMediaMarkersFromContent(messageId, rawContent, pendingMediaHits)
            } else {
                rawContent
            }

            // Cards are synchronous (no async fetch) so we attach them
            // straight onto the reconstructed ChatMessage and strip their
            // lines from the displayed content in the same pass. No
            // post-assignment dispatch needed.
            val (cleanedContent, extractedCards) = if (
                role == MessageRole.ASSISTANT && afterMedia.isNotEmpty()
            ) {
                extractCardsFromContent(afterMedia)
            } else {
                afterMedia to emptyList()
            }

            ChatMessage(
                id = messageId,
                role = role,
                content = cleanedContent,
                timestamp = timestampMs,
                isStreaming = false,
                toolCalls = toolCalls,
                cards = extractedCards,
            )
        }

        // Reload swaps the entire message list — any stale dedupe entries keyed
        // on pre-reload message IDs are meaningless now. Clear so the hits we
        // just collected against the reloaded IDs are guaranteed to fire.
        dispatchedMediaMarkers.clear()

        // === PHASE3-voice-intents-chathistory ===
        // Preserve local-only voice-intent trace messages across a reload.
        // These messages are injected by [appendLocalVoiceIntentTrace] with
        // IDs prefixed "voice-intent-" and never reach the server-side
        // session, so a wholesale `_messages.value = loaded` assignment
        // would wipe them. Bailey hit this 2026-04-15: voice fall-through
        // ("proceed" → not a recognized intent → chat.sendMessage) triggered
        // a history reload on stream complete and the previous voice trace
        // vanished, making it look like "the chat cleared". Server-side
        // sync (so these traces reach the LLM's session memory too) is
        // still a v0.4.1 follow-up, but preserving them client-side is
        // enough to fix the disappearing-scrollback bug today.
        val preservedVoiceTraces = _messages.value.filter {
            it.id.startsWith("voice-intent-")
        }
        val merged = if (preservedVoiceTraces.isEmpty()) {
            loaded
        } else {
            // Merge by timestamp so voice traces interleave with the
            // reloaded server messages in chronological order. The voice
            // trace IDs carry `System.currentTimeMillis()` in their suffix
            // (see appendLocalVoiceIntentTrace), so ChatMessage.timestamp
            // is the source of truth here.
            (loaded + preservedVoiceTraces).sortedBy { it.timestamp }
        }

        _messages.value = if (merged.size > MAX_MESSAGES) merged.takeLast(MAX_MESSAGES) else merged
        // === END PHASE3-voice-intents-chathistory ===

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
     * Scan loaded content for `CARD:{json}` lines, parse each to a
     * [HermesCard], return the cleaned content + the extracted cards. Pure
     * function — does not mutate [_messages] or mark anything dispatched.
     * Called from [loadMessageHistory]. Unparseable card lines are left in
     * the content (same policy as [tryDispatchCardMarker]) so the user
     * sees a visible artifact instead of a silent drop.
     */
    private fun extractCardsFromContent(content: String): Pair<String, List<HermesCard>> {
        var cleaned = content
        val cards = mutableListOf<HermesCard>()
        for (rawLine in content.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            val match = cardMarkerRegex.find(trimmed) ?: continue
            val payload = match.groupValues[1]
            val card = try {
                cardJson.decodeFromString(HermesCard.serializer(), payload)
            } catch (e: Exception) {
                Log.w(TAG, "Card reload parse failed: ${e.message}")
                continue
            }
            cards += card
            cleaned = cleaned
                .replace("\n$rawLine\n", "\n")
                .replace("\n$rawLine", "")
                .replace("$rawLine\n", "")
                .replace(rawLine, "")
        }
        return cleaned.trim() to cards
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

        // Rich cards ride the same "always on" treatment as media — the
        // agent can emit `CARD:{json}` at any point in any endpoint and
        // the renderer should pick it up without the user having to
        // opt in to any parsing mode.
        scanForCardMarkers(messageId, processedDelta)
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
     * Card-marker line scanner — twin of [scanForMediaMarkers] for
     * `CARD:{json}` rows. Matched cards are appended to the message's
     * [ChatMessage.cards] list and the raw line is stripped from
     * [ChatMessage.content] so the user only sees the rendered
     * [com.hermesandroid.relay.ui.components.HermesCardBubble], never the
     * literal JSON.
     */
    private fun scanForCardMarkers(messageId: String, delta: String) {
        cardLineBuffer.append(delta)

        while (true) {
            val newlineIndex = cardLineBuffer.indexOf('\n')
            if (newlineIndex == -1) break

            val line = cardLineBuffer.substring(0, newlineIndex)
            cardLineBuffer.delete(0, newlineIndex + 1)

            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (tryDispatchCardMarker(messageId, trimmed)) {
                stripLineFromContent(messageId, trimmed)
            }
        }
    }

    /**
     * Parse a single `CARD:{json}` line. On success, appends the card to
     * [ChatMessage.cards] for [messageId]. Dedupes by "messageId:cardKey"
     * where cardKey is the parsed [HermesCard.id] or a SHA-style hash
     * (content-based) fallback, so the same card re-appearing during
     * streaming + finalize reconciliation doesn't render twice.
     *
     * Invalid JSON is logged and returns false — the caller leaves the
     * line in the content, which at least gives the user a visible hint
     * that the agent tried to emit a card the phone couldn't parse.
     */
    private fun tryDispatchCardMarker(messageId: String, line: String): Boolean {
        val match = cardMarkerRegex.find(line) ?: return false
        val payload = match.groupValues[1]

        val card = try {
            cardJson.decodeFromString(HermesCard.serializer(), payload)
        } catch (e: Exception) {
            Log.w(TAG, "Card marker parse failed: ${e.message} | payload=${payload.take(200)}")
            return false
        }

        // Content-based fallback key when the agent didn't supply an id —
        // good enough for dedupe of the exact same card within a single
        // message turn.
        val cardKey = card.id ?: "anon:${payload.hashCode()}"
        val dedupeKey = "$messageId:$cardKey"
        if (!dispatchedCardMarkers.add(dedupeKey)) {
            Log.d(TAG, "Card marker duplicate, skipping: $cardKey")
            return true // Still strip the line — it's a valid card, just a repeat.
        }

        Log.d(TAG, "Card marker: type=${card.type} key=$cardKey")

        _messages.update { messages ->
            messages.map { msg ->
                if (msg.id == messageId && msg.role == MessageRole.ASSISTANT) {
                    msg.copy(cards = msg.cards + card)
                } else msg
            }
        }
        return true
    }

    /**
     * Flush the card line buffer + post-stream reconcile, twin of
     * [finalizeMediaMarkers]. Guards against the race where a CARD: line
     * arrived as the last delta with no trailing newline, and also
     * re-sweeps the finalized content for any card lines that survived
     * real-time stripping (update-ordering race against [stripLineFromContent]).
     */
    private fun finalizeCardMarkers(messageId: String) {
        if (cardLineBuffer.isNotEmpty()) {
            val remaining = cardLineBuffer.toString().trim()
            cardLineBuffer.clear()
            if (remaining.isNotEmpty() && tryDispatchCardMarker(messageId, remaining)) {
                stripLineFromContent(messageId, remaining)
            }
        }

        _messages.update { messages ->
            messages.map { msg ->
                if (msg.id != messageId || msg.role != MessageRole.ASSISTANT) return@map msg
                var cleaned = msg.content
                var changed = false
                for (rawLine in msg.content.lines()) {
                    val trimmed = rawLine.trim()
                    if (trimmed.isEmpty()) continue
                    if (cardMarkerRegex.containsMatchIn(trimmed)) {
                        tryDispatchCardMarker(messageId, trimmed)
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
        // Snapshot the matching tool call's name BEFORE mutating — we need it
        // to decide whether to emit a phone-action result bubble below.
        val toolName = _messages.value
            .firstOrNull { it.id == messageId && it.role == MessageRole.ASSISTANT }
            ?.toolCalls
            ?.firstOrNull { it.id == toolCallId }
            ?.name

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

        // Chat parity: emit a structured follow-up bubble for android_*
        // action tools the LLM called, mirroring the voice-mode post-
        // dispatch feedback path. Only fires for ACTION tools — read-only
        // and UI micro-actions are skipped (see [labelForAndroidTool]) so
        // chat parity doesn't spam the scrollback with `android_read_screen`
        // or `android_tap` bubbles that the existing ToolProgressCard
        // already surfaces inline on the assistant message.
        if (toolName != null) {
            maybeEmitPhoneActionBubble(toolName, resultPreview, isFailure = false)
        }
    }

    fun onToolCallFailed(messageId: String, toolCallId: String, error: String?) {
        // Snapshot tool name before the update so the phone-action bubble
        // can label the failure correctly.
        val toolName = _messages.value
            .firstOrNull { it.id == messageId && it.role == MessageRole.ASSISTANT }
            ?.toolCalls
            ?.firstOrNull { it.id == toolCallId }
            ?.name

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

        if (toolName != null) {
            maybeEmitPhoneActionBubble(
                toolName = toolName,
                resultPreview = error,
                isFailure = true,
            )
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
        // Rich cards get the same unconditional treatment — a partial
        // card line without a trailing newline should still render.
        finalizeCardMarkers(messageId)
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
        finalizeCardMarkers(messageId)
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

    // --- Card action dispatch ---

    /**
     * Record that the user tapped an action on a rich card. Appends a
     * [com.hermesandroid.relay.data.HermesCardDispatch] to the owning
     * message's dispatch list, which
     * [com.hermesandroid.relay.ui.components.HermesCardBubble] reads to
     * collapse the action row into a "you chose X" confirmation.
     *
     * Idempotent — taps on the same (cardKey, actionValue) pair are
     * silently coalesced, so tapping twice during a slow send doesn't
     * double-stamp the history.
     */
    fun recordCardDispatch(messageId: String, cardKey: String, actionValue: String) {
        val stamp = com.hermesandroid.relay.data.HermesCardDispatch(
            cardKey = cardKey,
            actionValue = actionValue,
            timestamp = System.currentTimeMillis(),
        )
        _messages.update { messages ->
            messages.map { msg ->
                if (msg.id != messageId) return@map msg
                val alreadyDispatched = msg.cardDispatches.any {
                    it.cardKey == cardKey && it.actionValue == actionValue
                }
                if (alreadyDispatched) msg
                else msg.copy(cardDispatches = msg.cardDispatches + stamp)
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

/**
 * Markdown-formatted description of a [LocalDispatchResult] for rendering
 * in a chat bubble. Shared between the voice post-dispatch feedback path
 * ([com.hermesandroid.relay.viewmodel.ChatViewModel.recordVoiceIntentResult])
 * and the chat-mode android_* tool-completion path ([ChatHandler.onToolCallComplete])
 * so both origins render identical-looking bubbles for identical outcomes.
 *
 * The label parameter is the short human-readable action name
 * ("Send SMS", "Open App", "Call", etc). Error-code branches mirror the
 * `error_code` strings [com.hermesandroid.relay.network.handlers.BridgeCommandHandler]
 * emits on destructive-verb rejections.
 */
internal fun formatPhoneActionResult(
    label: String,
    result: LocalDispatchResult,
): String = when {
    result.isSuccess -> when (label) {
        "Send SMS"      -> "**$label — sent** ✓"
        "Open App"      -> "**$label — opened** ✓"
        "Tap"           -> "**$label — done** ✓"
        "Navigate back" -> "**$label — done** ✓"
        "Home"          -> "**$label — done** ✓"
        else            -> "**$label — complete** ✓"
    }
    result.errorCode == "user_denied" -> buildString {
        append("**$label — cancelled by you**")
    }
    result.errorCode == "bridge_disabled" -> buildString {
        append("**$label — agent control is off**")
        append('\n')
        append("Enable Agent Control in the Hermes Bridge tab to retry.")
    }
    result.errorCode == "permission_denied" -> buildString {
        append("**$label — permission needed**")
        append('\n')
        append(result.errorMessage ?: "The phone is missing a required runtime permission.")
    }
    result.errorCode == "service_unavailable" -> buildString {
        append("**$label — bridge offline**")
        append('\n')
        append("The accessibility service isn't connected. Enable Hermes accessibility in Settings.")
    }
    result.errorCode == "cancelled" -> buildString {
        append("**$label — cancelled before dispatch**")
    }
    result.errorMessage != null -> buildString {
        append("**$label — failed**")
        append('\n')
        append(result.errorMessage)
    }
    else -> buildString {
        append("**$label — failed**")
        append('\n')
        append("Status ${result.status}.")
    }
}

package com.hermesandroid.relay.data

/**
 * Lifecycle state of an inbound attachment (media fetched from the relay).
 *
 * Outbound attachments authored by the user are always [LOADED].
 * Inbound attachments start as [LOADING], then transition to [LOADED] (bytes
 * cached + content:// URI available) or [FAILED] (network error, size cap
 * exceeded, relay offline, etc).
 */
enum class AttachmentState { LOADING, LOADED, FAILED }

/**
 * How the UI should render a loaded attachment. Derived from the MIME type.
 * - [IMAGE]  inline image (decode bytes / load URI).
 * - [VIDEO]  file card with play icon, tap opens ACTION_VIEW.
 * - [AUDIO]  file card with audio icon, tap opens ACTION_VIEW.
 * - [PDF]    file card with document icon, tap opens ACTION_VIEW.
 * - [TEXT]   file card with text icon (used for any `text/...` MIME and
 *            text-like application types: json, xml, yaml, toml, etc).
 * - [GENERIC] generic file card for unknown or binary types.
 */
enum class AttachmentRenderMode { IMAGE, VIDEO, AUDIO, PDF, TEXT, GENERIC }

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val isStreaming: Boolean = false,
    val toolCalls: List<ToolCall> = emptyList(),
    // Reasoning/thinking content (collapsible in UI)
    val thinkingContent: String = "",
    val isThinkingStreaming: Boolean = false,
    // Token tracking (from content_complete event)
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val estimatedCost: Double? = null,
    // Agent/personality name for display on assistant messages
    val agentName: String? = null,
    // File attachments (images, documents, etc.)
    val attachments: List<Attachment> = emptyList(),
    /**
     * Rich content cards emitted by the agent via `CARD:{json}` line
     * markers in the text stream. Parsed in
     * [com.hermesandroid.relay.network.handlers.ChatHandler.scanForCardMarkers]
     * and rendered inline by
     * [com.hermesandroid.relay.ui.components.HermesCardBubble]. Mirrors
     * [attachments]' lifecycle — the marker line is stripped from
     * [content] on match so the raw `CARD:{...}` never appears in the
     * bubble text, same as the `MEDIA:` parser.
     */
    val cards: List<HermesCard> = emptyList(),
    /**
     * Tapped actions on any of [cards]. Checked by the renderer so a card
     * whose action has been dispatched collapses into a confirmation state
     * rather than re-offering the buttons.
     */
    val cardDispatches: List<HermesCardDispatch> = emptyList(),
    /**
     * Structured trace of a phone-local voice intent dispatch. Populated only
     * for messages whose [id] starts with `voice-intent-` and that originated
     * from the sideload voice classifier. Used by
     * [com.hermesandroid.relay.voice.VoiceIntentSyncBuilder] to reconstruct
     * OpenAI-format `assistant` + `tool` message pairs the next time chat
     * sends a payload to the server, so the LLM sees prior voice actions in
     * its session memory.
     *
     * Null on every other message — including server-loaded history, normal
     * user messages, regular assistant turns, and tool-call cards rendered
     * via [ToolCall].
     *
     * The sync builder treats messages with [voiceIntent] != null and
     * [VoiceIntentTrace.syncedToServer] == false as the inputs to its
     * synthesis pass; on a successful send we flip [VoiceIntentTrace.syncedToServer]
     * to true via [com.hermesandroid.relay.network.handlers.ChatHandler.markVoiceIntentsSynced]
     * so they're not re-sent on the next turn.
     */
    val voiceIntent: VoiceIntentTrace? = null
)

/**
 * Structured details about a phone-local voice intent that was dispatched
 * in-process via [com.hermesandroid.relay.network.handlers.BridgeCommandHandler.handleLocalCommand].
 *
 * Captured on a [ChatMessage] (id prefix `voice-intent-`) so the next chat
 * payload can include synthetic OpenAI-format `assistant` + `tool` message
 * pairs that bring the server-side LLM up to speed on what the user did via
 * voice. Without this, follow-up text questions like "did that work?" hit
 * the LLM with no prior context and get hallucinated answers.
 *
 * Why a single field instead of three booleans + nullable strings on
 * [ChatMessage]: keeps the ChatMessage surface small and makes the "this is
 * a voice-intent trace, treat it specially" check a single null-vs-non-null
 * comparison rather than a multi-field rule that would drift over time.
 *
 * @property toolName The Hermes plugin tool name the intent maps to
 *   (`android_open_app`, `android_send_sms`, etc.). Matches the names the
 *   gateway-side LLM tools use when it calls the same actions itself. Must
 *   start with `android_` to be a valid sync target — the builder uses this
 *   prefix as a sanity gate when constructing the synthetic tool_call.
 * @property argumentsJson Compact JSON object with the args the intent
 *   resolved to, e.g. `{"app_name":"Chrome","package":"com.android.chrome"}`
 *   for an Open App dispatch. Stored as a string so we can hand it straight
 *   to the OpenAI `function.arguments` field, which is itself a JSON-encoded
 *   string by spec. Must be valid JSON.
 * @property success True if the local dispatch returned a 200-class status,
 *   false on any other status (denial, permission missing, dispatcher
 *   failure, etc.). Drives whether the synthetic `tool`-role response
 *   includes an `error` field.
 * @property resultJson Compact JSON object describing the dispatch outcome.
 *   On success, typically `{"ok":true,...}` with any tool-specific fields
 *   from [com.hermesandroid.relay.network.handlers.LocalDispatchResult.resultJson].
 *   On failure, an error envelope including `ok:false`, `error`, optionally
 *   `error_code`. Stored as a string and rendered verbatim into the
 *   synthetic `tool`-role message's `content` field.
 * @property syncedToServer Idempotency guard. Flipped to true by
 *   [com.hermesandroid.relay.network.handlers.ChatHandler.markVoiceIntentsSynced]
 *   the moment we hand the request payload to the API client. Once true,
 *   the trace is excluded from future sync passes — the server-side
 *   session has already absorbed it.
 */
data class VoiceIntentTrace(
    val toolName: String,
    val argumentsJson: String,
    val success: Boolean,
    val resultJson: String,
    val syncedToServer: Boolean = false,
)

/**
 * A file attachment sent with a message.
 *
 * Two shapes:
 *  1. Outbound — user picks a file, it's base64'd into [content] with a known
 *     [contentType]. [state] defaults to [AttachmentState.LOADED] so the
 *     render pipeline treats it like a "ready" attachment.
 *  2. Inbound — tool output emitted a `MEDIA:hermes-relay://<token>` marker.
 *     Starts as [AttachmentState.LOADING] with [relayToken] set. Once the
 *     bytes land via [RelayHttpClient.fetchMedia], [cachedUri] is populated
 *     with a `content://` URI from the FileProvider and state flips to
 *     [AttachmentState.LOADED]. On failure state is [AttachmentState.FAILED]
 *     and [errorMessage] holds a human-readable reason.
 *
 * Matches the Hermes API outbound format: { contentType, content (base64) }.
 */
data class Attachment(
    val contentType: String,   // MIME type (e.g. "image/png", "application/pdf", "text/plain")
    val content: String,       // Base64-encoded file content (outbound) or empty (inbound)
    val fileName: String? = null,
    val fileSize: Long? = null,
    // --- Inbound fetch state ---
    val state: AttachmentState = AttachmentState.LOADED,
    val errorMessage: String? = null,
    /** Opaque token from `MEDIA:hermes-relay://<token>` — identifies the file on the relay. */
    val relayToken: String? = null,
    /** content:// URI from the FileProvider once bytes are cached to disk. */
    val cachedUri: String? = null
) {
    val isImage: Boolean get() = contentType.startsWith("image/")

    /**
     * How the UI should render this attachment, derived from [contentType].
     * Falls back to [AttachmentRenderMode.GENERIC] for unknown types.
     */
    val renderMode: AttachmentRenderMode
        get() = when {
            contentType.startsWith("image/") -> AttachmentRenderMode.IMAGE
            contentType.startsWith("video/") -> AttachmentRenderMode.VIDEO
            contentType.startsWith("audio/") -> AttachmentRenderMode.AUDIO
            contentType == "application/pdf" -> AttachmentRenderMode.PDF
            contentType.startsWith("text/") || contentType in textLikeMimes -> AttachmentRenderMode.TEXT
            else -> AttachmentRenderMode.GENERIC
        }

    companion object {
        /**
         * MIME types that are text-like even though they don't start with `text/`.
         * Used by [renderMode] to route these to [AttachmentRenderMode.TEXT].
         */
        val textLikeMimes = setOf(
            "application/json",
            "application/xml",
            "application/yaml",
            "application/x-yaml",
            "application/toml",
            "application/javascript",
            "application/x-sh",
            "text/plain",
            "text/markdown",
            "text/html",
            "text/css",
            "text/csv"
        )
    }
}

data class ToolCall(
    val id: String? = null,
    val name: String,
    val args: String?,
    val result: String?,
    val success: Boolean?,
    val isComplete: Boolean = false,
    val error: String? = null,
    // Duration tracking
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ChatSession(
    val sessionId: String,
    val title: String?,
    val model: String?,
    val messageCount: Int = 0,
    val updatedAt: Long = 0L
)

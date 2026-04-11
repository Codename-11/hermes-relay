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
    val attachments: List<Attachment> = emptyList()
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

package com.hermesandroid.relay.data

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
 * Matches the Hermes API format: { contentType, content (base64) }
 */
data class Attachment(
    val contentType: String,   // MIME type (e.g. "image/png", "application/pdf", "text/plain")
    val content: String,       // Base64-encoded file content
    val fileName: String? = null,
    val fileSize: Long? = null
) {
    val isImage: Boolean get() = contentType.startsWith("image/")
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

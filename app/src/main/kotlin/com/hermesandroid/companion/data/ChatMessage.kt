package com.hermesandroid.companion.data

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val isStreaming: Boolean = false,
    val toolCalls: List<ToolCall> = emptyList()
)

data class ToolCall(
    val name: String,
    val args: String?,
    val result: String?,
    val success: Boolean?,
    val isComplete: Boolean = false
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ChatSession(
    val sessionId: String,
    val title: String?,
    val model: String?
)

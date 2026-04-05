package com.hermesandroid.companion.network.handlers

import com.hermesandroid.companion.data.ChatMessage
import com.hermesandroid.companion.data.ChatSession
import com.hermesandroid.companion.data.MessageRole
import com.hermesandroid.companion.data.ToolCall
import com.hermesandroid.companion.network.ChannelMultiplexer
import com.hermesandroid.companion.network.models.ChatCompletedPayload
import com.hermesandroid.companion.network.models.ChatDeltaPayload
import com.hermesandroid.companion.network.models.ChatErrorPayload
import com.hermesandroid.companion.network.models.ChatSessionPayload
import com.hermesandroid.companion.network.models.ChatToolCompletedPayload
import com.hermesandroid.companion.network.models.ChatToolStartedPayload
import com.hermesandroid.companion.network.models.Envelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Processes incoming chat channel messages and updates state.
 *
 * Handles:
 * - chat.session — new session created
 * - chat.delta — streaming text delta
 * - chat.tool.started — tool execution started
 * - chat.tool.completed — tool execution completed
 * - chat.completed — assistant message complete
 * - chat.error — error in chat
 */
class ChatHandler : ChannelMultiplexer.ChannelHandler {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    override fun onMessage(envelope: Envelope) {
        when (envelope.type) {
            "chat.session" -> handleSession(envelope)
            "chat.delta" -> handleDelta(envelope)
            "chat.tool.started" -> handleToolStarted(envelope)
            "chat.tool.completed" -> handleToolCompleted(envelope)
            "chat.completed" -> handleCompleted(envelope)
            "chat.error" -> handleError(envelope)
        }
    }

    fun addUserMessage(message: ChatMessage) {
        _messages.update { it + message }
    }

    fun clearError() {
        _error.value = null
    }

    private fun handleSession(envelope: Envelope) {
        try {
            val payload = json.decodeFromJsonElement<ChatSessionPayload>(envelope.payload)
            val session = ChatSession(
                sessionId = payload.sessionId,
                title = payload.title,
                model = payload.model
            )
            _sessions.update { sessions ->
                if (sessions.any { it.sessionId == session.sessionId }) {
                    sessions.map { if (it.sessionId == session.sessionId) session else it }
                } else {
                    sessions + session
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleDelta(envelope: Envelope) {
        try {
            val payload = json.decodeFromJsonElement<ChatDeltaPayload>(envelope.payload)
            _isStreaming.value = true

            _messages.update { messages ->
                val existing = messages.findLast {
                    it.id == payload.messageId && it.role == MessageRole.ASSISTANT
                }

                if (existing != null) {
                    // Append delta to existing streaming message
                    messages.map { msg ->
                        if (msg.id == payload.messageId) {
                            msg.copy(content = msg.content + payload.delta)
                        } else {
                            msg
                        }
                    }
                } else {
                    // Create new assistant message
                    messages + ChatMessage(
                        id = payload.messageId,
                        role = MessageRole.ASSISTANT,
                        content = payload.delta,
                        timestamp = System.currentTimeMillis(),
                        isStreaming = true
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleToolStarted(envelope: Envelope) {
        try {
            val payload = json.decodeFromJsonElement<ChatToolStartedPayload>(envelope.payload)
            val toolCall = ToolCall(
                name = payload.toolName,
                args = payload.args?.toString(),
                result = null,
                success = null,
                isComplete = false
            )

            _messages.update { messages ->
                val lastAssistant = messages.findLast { it.role == MessageRole.ASSISTANT }
                if (lastAssistant != null) {
                    messages.map { msg ->
                        if (msg.id == lastAssistant.id) {
                            msg.copy(toolCalls = msg.toolCalls + toolCall)
                        } else {
                            msg
                        }
                    }
                } else {
                    // Create a new assistant message to hold the tool call
                    messages + ChatMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        role = MessageRole.ASSISTANT,
                        content = "",
                        timestamp = System.currentTimeMillis(),
                        isStreaming = true,
                        toolCalls = listOf(toolCall)
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleToolCompleted(envelope: Envelope) {
        try {
            val payload = json.decodeFromJsonElement<ChatToolCompletedPayload>(envelope.payload)

            _messages.update { messages ->
                messages.map { msg ->
                    if (msg.role == MessageRole.ASSISTANT && msg.toolCalls.any { it.name == payload.toolName && !it.isComplete }) {
                        val updatedCalls = msg.toolCalls.map { call ->
                            if (call.name == payload.toolName && !call.isComplete) {
                                call.copy(
                                    result = payload.resultPreview,
                                    success = payload.success,
                                    isComplete = true
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleCompleted(envelope: Envelope) {
        try {
            val payload = json.decodeFromJsonElement<ChatCompletedPayload>(envelope.payload)
            _isStreaming.value = false

            _messages.update { messages ->
                messages.map { msg ->
                    if (msg.id == payload.messageId) {
                        msg.copy(
                            content = payload.content,
                            isStreaming = false
                        )
                    } else {
                        msg
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleError(envelope: Envelope) {
        try {
            val payload = json.decodeFromJsonElement<ChatErrorPayload>(envelope.payload)
            _isStreaming.value = false
            _error.value = payload.message
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

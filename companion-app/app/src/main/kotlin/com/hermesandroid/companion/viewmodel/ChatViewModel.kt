package com.hermesandroid.companion.viewmodel

import androidx.lifecycle.ViewModel
import com.hermesandroid.companion.data.ChatMessage
import com.hermesandroid.companion.data.ChatSession
import com.hermesandroid.companion.data.MessageRole
import com.hermesandroid.companion.network.ChannelMultiplexer
import com.hermesandroid.companion.network.handlers.ChatHandler
import com.hermesandroid.companion.network.models.ChatSendPayload
import com.hermesandroid.companion.network.models.Envelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.UUID

class ChatViewModel : ViewModel() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var multiplexer: ChannelMultiplexer? = null
    private var chatHandler: ChatHandler? = null

    private val _currentProfile = MutableStateFlow("default")
    val currentProfile: StateFlow<String> = _currentProfile.asStateFlow()

    private val _profiles = MutableStateFlow<List<String>>(listOf("default"))
    val profiles: StateFlow<List<String>> = _profiles.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    // Delegated to ChatHandler
    val messages: StateFlow<List<ChatMessage>>
        get() = chatHandler?.messages ?: MutableStateFlow(emptyList())

    val isStreaming: StateFlow<Boolean>
        get() = chatHandler?.isStreaming ?: MutableStateFlow(false)

    val sessions: StateFlow<List<ChatSession>>
        get() = chatHandler?.sessions ?: MutableStateFlow(emptyList())

    val error: StateFlow<String?>
        get() = chatHandler?.error ?: MutableStateFlow(null)

    /**
     * Wire up the chat handler and multiplexer.
     * Called once from the UI layer after dependencies are ready.
     */
    fun initialize(multiplexer: ChannelMultiplexer, chatHandler: ChatHandler) {
        this.multiplexer = multiplexer
        this.chatHandler = chatHandler
    }

    fun updateProfiles(profileList: List<String>) {
        _profiles.value = profileList
        if (profileList.isNotEmpty() && _currentProfile.value !in profileList) {
            _currentProfile.value = profileList.first()
        }
    }

    fun selectProfile(name: String) {
        _currentProfile.value = name
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val messageId = UUID.randomUUID().toString()

        // Add user message locally
        val userMessage = ChatMessage(
            id = messageId,
            role = MessageRole.USER,
            content = text.trim(),
            timestamp = System.currentTimeMillis()
        )
        chatHandler?.addUserMessage(userMessage)

        // Build and send envelope
        val payload = ChatSendPayload(
            profile = _currentProfile.value,
            sessionId = _currentSessionId.value,
            message = text.trim()
        )

        val envelope = Envelope(
            channel = "chat",
            type = "chat.send",
            id = messageId,
            payload = json.encodeToJsonElement(payload).jsonObject
        )

        multiplexer?.send(envelope)
    }

    fun setSessionId(sessionId: String) {
        _currentSessionId.value = sessionId
    }

    fun clearError() {
        chatHandler?.clearError()
    }
}

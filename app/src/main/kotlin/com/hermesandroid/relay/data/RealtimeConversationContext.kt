package com.hermesandroid.relay.data

/**
 * Compact text context sent to the relay when opening a provider-native
 * Realtime Agent session.
 */
data class RealtimeConversationContextMessage(
    val role: MessageRole,
    val content: String,
    val source: String? = null,
)

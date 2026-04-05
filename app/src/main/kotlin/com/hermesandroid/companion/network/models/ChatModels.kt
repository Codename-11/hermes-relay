package com.hermesandroid.companion.network.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatSendPayload(
    val profile: String,
    @SerialName("session_id")
    val sessionId: String? = null,
    val message: String
)

@Serializable
data class ChatDeltaPayload(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("message_id")
    val messageId: String,
    val delta: String
)

@Serializable
data class ChatCompletedPayload(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("message_id")
    val messageId: String,
    val content: String
)

@Serializable
data class ChatToolStartedPayload(
    @SerialName("tool_name")
    val toolName: String,
    val preview: String? = null,
    val args: JsonObject? = null
)

@Serializable
data class ChatToolCompletedPayload(
    @SerialName("tool_name")
    val toolName: String,
    @SerialName("result_preview")
    val resultPreview: String? = null,
    val success: Boolean
)

@Serializable
data class ChatSessionPayload(
    @SerialName("session_id")
    val sessionId: String,
    val title: String? = null,
    val model: String? = null
)

@Serializable
data class ChatErrorPayload(
    val message: String
)

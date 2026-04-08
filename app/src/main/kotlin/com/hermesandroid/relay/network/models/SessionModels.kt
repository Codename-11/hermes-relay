package com.hermesandroid.relay.network.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Models for the Hermes /api/sessions REST API.
 * These are Hermes-native format, not OpenAI-compatible.
 */

/**
 * Serializer that accepts both string and integer IDs from the server,
 * normalizing them to String. Hermes returns int IDs for messages but
 * string IDs for sessions.
 */
@OptIn(ExperimentalSerializationApi::class)
object FlexibleIdSerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        return try {
            val jsonDecoder = decoder as? JsonDecoder
                ?: return decoder.decodeString()
            val element = jsonDecoder.decodeJsonElement()
            when {
                element is JsonNull -> null
                element is JsonPrimitive -> element.content
                else -> element.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value != null) encoder.encodeString(value) else encoder.encodeNull()
    }
}

/** Non-null variant — returns empty string instead of null. Safe for primary key fields. */
object FlexibleIdNonNullSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleIdNonNull", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        return try {
            val jsonDecoder = decoder as? JsonDecoder
                ?: return decoder.decodeString()
            val element = jsonDecoder.decodeJsonElement()
            when {
                element is JsonNull -> ""
                element is JsonPrimitive -> element.content
                else -> element.toString()
            }
        } catch (_: Exception) {
            ""
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

// --- Session CRUD responses ---

@Serializable
data class SessionListResponse(
    val items: List<SessionItem>? = null,
    val sessions: List<SessionItem>? = null, // alternate key
    val total: Int? = null
)

@Serializable
data class SessionResponse(
    val session: SessionItem? = null,
    // Flat session fields for when server returns at top level
    @Serializable(with = FlexibleIdSerializer::class)
    val id: String? = null,
    val title: String? = null,
    val model: String? = null
)

@Serializable
data class SessionItem(
    @Serializable(with = FlexibleIdNonNullSerializer::class)
    val id: String = "",
    val title: String? = null,
    val model: String? = null,
    val source: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("tool_call_count") val toolCallCount: Int? = null,
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null
)

@Serializable
data class CreateSessionRequest(
    val title: String? = null,
    val model: String? = null
)

@Serializable
data class RenameSessionRequest(
    val title: String
)

// --- Messages ---

@Serializable
data class MessageListResponse(
    val items: List<MessageItem>? = null,
    val messages: List<MessageItem>? = null, // alternate key
    val total: Int? = null
)

@Serializable
data class MessageItem(
    @Serializable(with = FlexibleIdSerializer::class)
    val id: String? = null,
    @SerialName("session_id")
    @Serializable(with = FlexibleIdSerializer::class)
    val sessionId: String? = null,
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: JsonElement? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_call_id")
    @Serializable(with = FlexibleIdSerializer::class)
    val toolCallId: String? = null,
    val timestamp: Double? = null,
    @SerialName("finish_reason") val finishReason: String? = null
) {
    /** Extract content as plain text string. Handles both string and array-of-parts formats. */
    val contentText: String?
        get() = when (content) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.jsonArray
                .filterIsInstance<JsonObject>()
                .filter { (it["type"] as? JsonPrimitive)?.content == "text" }
                .mapNotNull { (it["text"] as? JsonPrimitive)?.content }
                .joinToString("")
                .ifEmpty { null }
            else -> null
        }

    /** Extract image URLs from OpenAI-format content arrays. */
    val imageUrls: List<String>
        get() = when (content) {
            is JsonArray -> content.jsonArray
                .filterIsInstance<JsonObject>()
                .filter { (it["type"] as? JsonPrimitive)?.content == "image_url" }
                .mapNotNull { block ->
                    val imageUrl = block["image_url"] as? JsonObject
                    (imageUrl?.get("url") as? JsonPrimitive)?.content
                }
            else -> emptyList()
        }
}

// --- SSE streaming events from /api/sessions/{id}/chat/stream ---
//
// Hermes WebAPI event types (from server source):
//   session.created     — { session_id, run_id, title? }
//   run.started         — { session_id, run_id, user_message: { id, role, content } }
//   message.started     — { session_id, run_id, message: { id, role } }
//   assistant.delta     — { session_id, run_id, message_id, delta }
//   tool.progress       — { session_id, run_id, message_id, delta } (thinking/reasoning)
//   tool.pending        — { session_id, run_id, tool_name, call_id }
//   tool.started        — { session_id, run_id, tool_name, call_id, preview?, args }
//   tool.completed      — { session_id, run_id, tool_call_id, tool_name, args, result_preview }
//   tool.failed         — { session_id, run_id, call_id, tool_name, error }
//   assistant.completed — { session_id, run_id, message_id, content, completed, partial, interrupted }
//   run.completed       — { session_id, run_id, message_id, completed, partial, interrupted, api_calls? }
//   error               — { message (string), error }
//   done                — { session_id, run_id, state: "final" }

@Serializable
data class HermesSseEvent(
    // Event type — may come as "type" or "event" depending on server version
    val type: String? = null,
    val event: String? = null,
    // Shared envelope fields
    @SerialName("session_id")
    @Serializable(with = FlexibleIdSerializer::class)
    val sessionId: String? = null,
    @SerialName("run_id")
    @Serializable(with = FlexibleIdSerializer::class)
    val runId: String? = null,
    @SerialName("message_id")
    @Serializable(with = FlexibleIdSerializer::class)
    val messageId: String? = null,
    val seq: Int? = null,
    val ts: Double? = null,
    val timestamp: Double? = null,
    // assistant.delta / tool.progress / message.delta
    val delta: String? = null,
    // tool fields — different servers use different names
    val name: String? = null,
    val tool: String? = null,              // /v1/runs format: "tool":"terminal"
    @SerialName("tool_name") val toolName: String? = null,
    val preview: String? = null,
    val args: JsonObject? = null,
    @SerialName("result_preview") val resultPreview: String? = null,
    val duration: Double? = null,          // /v1/runs tool.completed duration in seconds
    val success: Boolean? = null,
    @SerialName("call_id")
    @Serializable(with = FlexibleIdSerializer::class)
    val callId: String? = null,
    @SerialName("tool_call_id")
    @Serializable(with = FlexibleIdSerializer::class)
    val toolCallId: String? = null,
    // assistant.completed / run.completed
    val content: String? = null,
    val output: String? = null,            // /v1/runs run.completed final text
    @SerialName("final_response") val finalResponse: String? = null,
    val completed: Boolean? = null,
    val partial: Boolean? = null,
    val interrupted: Boolean? = null,
    @SerialName("api_calls") val apiCalls: Int? = null,
    // session.created
    val title: String? = null,
    // run.started — user_message is an object
    @SerialName("user_message") val userMessage: JsonObject? = null,
    // message.started / error — message can be String or Object
    val message: JsonElement? = null,
    val error: String? = null,
    // done event
    val state: String? = null,
    // Reasoning fields — multiple possible names across server versions
    val thinking: String? = null,
    @SerialName("thinking_delta") val thinkingDelta: String? = null,
    val text: String? = null,              // /v1/runs reasoning.available text
    // Usage/token fields (on assistant.completed / run.completed)
    val usage: UsageInfo? = null
) {
    /** Resolve the event type from whichever field is populated. */
    val resolvedType: String?
        get() = type ?: event

    /** Resolve tool name from whichever field the server uses. */
    val resolvedToolName: String?
        get() = toolName ?: tool ?: name

    /** Extract message as string (returns null if message is an object, not a string). */
    val messageText: String?
        get() = (message as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Serializable
data class UsageInfo(
    // Hermes naming
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    // OpenAI naming (fallback)
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    // Cache tokens
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int? = null
) {
    /** Resolved input tokens — prefers Hermes naming, falls back to OpenAI. */
    val resolvedInputTokens: Int? get() = inputTokens ?: promptTokens
    /** Resolved output tokens — prefers Hermes naming, falls back to OpenAI. */
    val resolvedOutputTokens: Int? get() = outputTokens ?: completionTokens
    /** Resolved total tokens. */
    val resolvedTotalTokens: Int? get() = totalTokens
        ?: if (resolvedInputTokens != null || resolvedOutputTokens != null)
            (resolvedInputTokens ?: 0) + (resolvedOutputTokens ?: 0)
        else null
}

// --- Skills API ---

@Serializable
data class SkillInfo(
    val name: String,
    val description: String? = null,
    val category: String? = null,
    @SerialName("usage") val usage: String? = null
)

@Serializable
data class SkillListResponse(
    val skills: List<SkillInfo>? = null,
    val items: List<SkillInfo>? = null
)

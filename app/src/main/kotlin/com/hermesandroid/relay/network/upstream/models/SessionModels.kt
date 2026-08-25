package com.hermesandroid.relay.network.upstream.models

import com.hermesandroid.relay.data.MessageReaction
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

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

/** Unknown-safe durable SQLite row id used by current Gateway history. */
@OptIn(ExperimentalSerializationApi::class)
object FlexibleLongSerializer : KSerializer<Long?> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long? {
        return try {
            val jsonDecoder = decoder as? JsonDecoder
                ?: return decoder.decodeLong()
            (jsonDecoder.decodeJsonElement() as? JsonPrimitive)?.longOrNull
        } catch (_: Exception) {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value != null) encoder.encodeLong(value) else encoder.encodeNull()
    }
}

/**
 * Dashboard versions may expose SQLite JSON columns either as an object or as
 * their raw JSON string. Normalize both shapes so persisted presentation data
 * (notably message reactions) survives a history reload on every supported
 * upstream version.
 */
object FlexibleJsonObjectSerializer : KSerializer<JsonObject?> {
    override val descriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): JsonObject? {
        return try {
            val jsonDecoder = decoder as? JsonDecoder ?: return null
            when (val element = jsonDecoder.decodeJsonElement()) {
                is JsonObject -> element
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
                    ?.let { Json.parseToJsonElement(it) as? JsonObject }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: JsonObject?) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("FlexibleJsonObjectSerializer requires JSON")
        jsonEncoder.encodeJsonElement(value ?: JsonNull)
    }
}

/** Timestamp serializer for Hermes session metadata.
 *
 * Upstream currently returns epoch seconds for `started_at` / `last_active`;
 * some documented surfaces use ISO strings for update-style fields. Decode
 * both into epoch seconds so callers can convert once at the UI boundary.
 */
@OptIn(ExperimentalSerializationApi::class)
object FlexibleTimestampSerializer : KSerializer<Double?> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleTimestamp", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double? {
        return try {
            val jsonDecoder = decoder as? JsonDecoder
                ?: return decoder.decodeDouble()
            val element = jsonDecoder.decodeJsonElement()
            when (element) {
                is JsonNull -> null
                is JsonPrimitive -> parseTimestamp(element.content)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: Double?) {
        if (value != null) encoder.encodeDouble(value) else encoder.encodeNull()
    }

    private fun parseTimestamp(raw: String): Double? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        trimmed.toDoubleOrNull()?.let { return it }
        return runCatching { Instant.parse(trimmed).toEpochMilli() / 1000.0 }.getOrNull()
    }
}

/**
 * Boolean serializer for session flags backed by SQLite integer columns.
 *
 * Older Dashboard responses can expose those columns as `0` / `1` instead of
 * JSON booleans. Accept the equivalent primitive forms without making arbitrary
 * numbers or strings truthy, and always serialize back to a real JSON boolean.
 */
object FlexibleBooleanSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val element = jsonDecoder.decodeJsonElement()
        val value = (element as? JsonPrimitive)?.content?.trim()?.lowercase()
        return when (value) {
            "true", "1" -> true
            "false", "0" -> false
            else -> throw SerializationException("Expected a boolean-compatible value, got $element")
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeBoolean(value)
    }
}

// --- Session CRUD responses ---

@Serializable
data class SessionListResponse(
    val items: List<SessionItem>? = null,
    val sessions: List<SessionItem>? = null, // alternate key
    val data: List<SessionItem>? = null, // upstream /api/sessions list envelope
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
    /** Upstream's first-user-message label when no persisted title exists. */
    val preview: String? = null,
    val model: String? = null,
    val source: String? = null,
    /** Owning profile on the cross-profile `/api/profiles/sessions` endpoint. */
    val profile: String? = null,
    @SerialName("started_at")
    @Serializable(with = FlexibleTimestampSerializer::class)
    val startedAt: Double? = null,
    @SerialName("ended_at")
    @Serializable(with = FlexibleTimestampSerializer::class)
    val endedAt: Double? = null,
    @SerialName("last_active")
    @Serializable(with = FlexibleTimestampSerializer::class)
    val lastActive: Double? = null,
    @SerialName("last_activity")
    @Serializable(with = FlexibleTimestampSerializer::class)
    val lastActivity: Double? = null,
    @SerialName("last_activity_at")
    @Serializable(with = FlexibleTimestampSerializer::class)
    val lastActivityAt: Double? = null,
    @SerialName("updated_at")
    @Serializable(with = FlexibleTimestampSerializer::class)
    val updatedAt: Double? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("tool_call_count") val toolCallCount: Int? = null,
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("actual_cost_usd") val actualCostUsd: Double? = null,
    @SerialName("estimated_cost_usd") val estimatedCostUsd: Double? = null,
    /** REST recency heuristic from upstream; not live Gateway execution state. */
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("has_model_config")
    @Serializable(with = FlexibleBooleanSerializer::class)
    val hasModelConfig: Boolean = false,
    /** Durable flags returned by current Dashboard and API-server session resources. */
    @Serializable(with = FlexibleBooleanSerializer::class)
    val pinned: Boolean = false,
    @Serializable(with = FlexibleBooleanSerializer::class)
    val archived: Boolean = false,
    /** Optional workspace metadata added by newer Dashboard session lists. */
    val cwd: String? = null,
    @SerialName("git_branch") val gitBranch: String? = null,
    @SerialName("git_repo_root") val gitRepoRoot: String? = null,
    /** Best-effort association from the Dashboard's read-only transcript scan. */
    val pullRequest: SessionPullRequest? = null,
) {
    val resolvedLastActivity: Double?
        get() = lastActive ?: lastActivity ?: lastActivityAt ?: updatedAt
}

@Serializable
data class SessionPullRequest(
    val number: Int,
    val url: String,
    val branch: String? = null,
    val state: String? = null,
    val draft: Boolean = false,
    val title: String? = null,
)

@Serializable
data class SessionPullRequestScanResponse(
    @SerialName("pull_requests") val pullRequests: Map<String, SessionPullRequest> = emptyMap(),
    val scanned: List<String> = emptyList(),
)

@Serializable
data class RepositoryPullRequestListResponse(
    val ghReady: Boolean = false,
    val prs: List<SessionPullRequest> = emptyList(),
)

@Serializable
data class CreateSessionRequest(
    val title: String? = null,
    val model: String? = null,
    val profile: String? = null,
)

@Serializable
data class RenameSessionRequest(
    val title: String
)

// --- Server-backed bulk cleanup (dashboard POST /api/sessions/prune) ---

/**
 * Client-side subset of upstream's `SessionPrune` body. Nulls are omitted from
 * the request; a fully-bare filter set is a "bare prune", where upstream
 * applies its own implicit ended-more-than-90-days-ago cutoff.
 */
data class SessionPruneFilters(
    val olderThanDays: Double? = null,
    val source: String? = null,
    val profile: String? = null,
    val includeArchived: Boolean = false,
)

/** One row of the dry-run preview (`sessions` in the prune response). */
@Serializable
data class SessionPruneCandidate(
    @Serializable(with = FlexibleIdNonNullSerializer::class)
    val id: String = "",
    val source: String? = null,
    val title: String? = null,
    val model: String? = null,
    @SerialName("started_at")
    @Serializable(with = FlexibleTimestampSerializer::class)
    val startedAt: Double? = null,
    @SerialName("message_count") val messageCount: Int? = null,
)

/**
 * Dry-run response: what a prune WOULD delete — count, started-at span, and
 * the candidate rows — without deleting anything. Upstream orders candidates
 * oldest-first.
 */
@Serializable
data class SessionPrunePreview(
    val matched: Int = 0,
    @SerialName("oldest_started_at")
    @Serializable(with = FlexibleTimestampSerializer::class)
    val oldestStartedAt: Double? = null,
    @SerialName("newest_started_at")
    @Serializable(with = FlexibleTimestampSerializer::class)
    val newestStartedAt: Double? = null,
    val sessions: List<SessionPruneCandidate> = emptyList(),
)

/** Apply response — how many sessions the server actually removed. */
@Serializable
data class SessionPruneResult(
    val ok: Boolean = true,
    val removed: Int = 0,
)

// --- Messages ---

@Serializable
data class MessageListResponse(
    val items: List<MessageItem>? = null,
    val messages: List<MessageItem>? = null, // alternate key
    val data: List<MessageItem>? = null, // upstream /api/sessions/{id}/messages list envelope
    val total: Int? = null,
    val pagination: MessagePagination? = null,
)

@Serializable
data class MessagePagination(
    val limit: Int? = null,
    val offset: Int? = null,
    val order: String? = null,
    val returned: Int? = null,
)

@Serializable
data class MessageItem(
    @Serializable(with = FlexibleIdSerializer::class)
    val id: String? = null,
    @SerialName("session_id")
    @Serializable(with = FlexibleIdSerializer::class)
    val sessionId: String? = null,
    @SerialName("row_id")
    @Serializable(with = FlexibleLongSerializer::class)
    val rowId: Long? = null,
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: JsonElement? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_call_id")
    @Serializable(with = FlexibleIdSerializer::class)
    val toolCallId: String? = null,
    val timestamp: Double? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    @SerialName("display_kind") val displayKind: String? = null,
    @SerialName("display_metadata")
    @Serializable(with = FlexibleJsonObjectSerializer::class)
    val displayMetadata: JsonObject? = null,
    // Reasoning persisted with the assistant message (upstream serializes
    // both names; reasoning is the canonical one). Restored into
    // ChatMessage.thinkingContent so the Thought-process block survives a
    // return to the chat instead of existing only for the live turn.
    val reasoning: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
) {
    /**
     * Dashboard history uses the SQLite row id as numeric `id`; Gateway
     * history exposes the same value explicitly as `row_id`. Match Desktop by
     * accepting either representation so persisted rows remain directly
     * reactable after reload.
     */
    val resolvedRowId: Long?
        get() = rowId ?: id?.toLongOrNull()

    /** Reasoning text under whichever field name the server used. */
    val resolvedReasoning: String?
        get() = reasoning?.takeIf { it.isNotBlank() }
            ?: reasoningContent?.takeIf { it.isNotBlank() }

    /** Persisted tapbacks stored by Hermes in display_metadata.reactions. */
    val reactions: List<MessageReaction>
        get() = parseMessageReactions(displayMetadata?.get("reactions"))

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

fun parseMessageReactions(element: JsonElement?): List<MessageReaction> =
    (element as? JsonArray).orEmpty().mapNotNull { raw ->
        val reaction = raw as? JsonObject ?: return@mapNotNull null
        val emoji = (reaction["emoji"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val author = (reaction["author"] as? JsonPrimitive)?.content
            ?.takeIf { it == "user" || it == "agent" }
            ?: return@mapNotNull null
        MessageReaction(
            emoji = emoji,
            author = author,
            at = (reaction["at"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        )
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
data class RelayStreamEventEnvelope(
    val type: String = "stream.event",
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("session_id")
    @Serializable(with = FlexibleIdSerializer::class)
    val sessionId: String? = null,
    @SerialName("run_id")
    @Serializable(with = FlexibleIdSerializer::class)
    val runId: String? = null,
    val seq: Int? = null,
    val event: String,
    val ts: String? = null,
    val payload: JsonObject = kotlinx.serialization.json.buildJsonObject { },
)

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
    val usage: UsageInfo? = null,
    // Native session routing proof on run.started and terminal events.
    val runtime: JsonObject? = null,
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
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int? = null,
    // Gateway context-window block (session-cumulative; present only when the
    // server's context compressor is active — upstream _get_usage()). Render
    // context UI only when contextMax is non-null.
    @SerialName("context_used") val contextUsed: Int? = null,
    @SerialName("context_max") val contextMax: Int? = null,
    @SerialName("context_percent") val contextPercent: Int? = null
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
    val items: List<SkillInfo>? = null,
    val data: List<SkillInfo>? = null
)

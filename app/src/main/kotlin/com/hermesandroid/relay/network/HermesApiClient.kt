package com.hermesandroid.relay.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hermesandroid.relay.data.AppAnalytics
import com.hermesandroid.relay.network.models.CreateSessionRequest
import com.hermesandroid.relay.network.models.HermesSseEvent
import com.hermesandroid.relay.network.models.MessageItem
import com.hermesandroid.relay.network.models.MessageListResponse
import com.hermesandroid.relay.network.models.RenameSessionRequest
import com.hermesandroid.relay.network.models.SessionItem
import com.hermesandroid.relay.network.models.SessionListResponse
import com.hermesandroid.relay.network.models.SessionResponse
import com.hermesandroid.relay.network.models.SkillInfo
import com.hermesandroid.relay.network.models.SkillListResponse
import com.hermesandroid.relay.network.models.UsageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed interface HealthCheckResult {
    data object Healthy : HealthCheckResult
    data class Unhealthy(val message: String) : HealthCheckResult
}

enum class ChatMode {
    /** Full Hermes Sessions API — /api/sessions/{id}/chat/stream */
    ENHANCED_HERMES,
    /** Only OpenAI-compatible /v1/chat/completions */
    PORTABLE,
    /** Cannot connect to the server */
    DISCONNECTED
}

/**
 * Direct HTTP/SSE client for the Hermes API Server.
 *
 * Session CRUD via /api/sessions REST endpoints.
 * Chat streaming via /api/sessions/{id}/chat/stream SSE.
 * All event callbacks dispatched to the main thread for safe StateFlow updates.
 */
class HermesApiClient(
    baseUrl: String,
    private val apiKey: String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
) {
    private val baseUrl: String = baseUrl.trimEnd('/')

    companion object {
        private const val TAG = "HermesApiClient"
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.MINUTES)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val sseFactory = EventSources.createFactory(client)

    // --- Health check ---

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            val request = authRequest("$baseUrl/health").get().build()
            client.newCall(request).execute().use { response ->
                val latencyMs = System.currentTimeMillis() - startMs
                val success = if (!response.isSuccessful) {
                    false
                } else {
                    // Validate it's actually a JSON API, not an HTML error page
                    val contentType = response.header("Content-Type") ?: ""
                    contentType.contains("json", ignoreCase = true) ||
                        contentType.contains("text/plain", ignoreCase = true) ||
                        (response.body?.string()?.trimStart()?.startsWith("{") == true)
                }
                AppAnalytics.onHealthCheck(success, latencyMs)
                success
            }
        } catch (_: Exception) {
            val latencyMs = System.currentTimeMillis() - startMs
            AppAnalytics.onHealthCheck(false, latencyMs)
            false
        }
    }

    suspend fun checkHealthDetailed(): HealthCheckResult = withContext(Dispatchers.IO) {
        try {
            val request = authRequest("$baseUrl/health").get().build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> HealthCheckResult.Healthy
                    response.code == 401 || response.code == 403 ->
                        HealthCheckResult.Unhealthy("Unauthorized — check your API key")
                    else ->
                        HealthCheckResult.Unhealthy("Server returned HTTP ${response.code}")
                }
            }
        } catch (e: javax.net.ssl.SSLException) {
            if (baseUrl.startsWith("https://", ignoreCase = true)) {
                HealthCheckResult.Unhealthy("TLS handshake failed — try http:// if your server doesn't use HTTPS")
            } else {
                HealthCheckResult.Unhealthy("SSL error: ${e.message}")
            }
        } catch (e: java.net.ConnectException) {
            HealthCheckResult.Unhealthy("Connection refused — check the URL and port")
        } catch (e: java.net.UnknownHostException) {
            HealthCheckResult.Unhealthy("Server not found — check the hostname")
        } catch (e: java.net.SocketTimeoutException) {
            HealthCheckResult.Unhealthy("Connection timed out — is the server running?")
        } catch (e: IOException) {
            val msg = e.message ?: ""
            when {
                msg.contains("tls", ignoreCase = true) || msg.contains("ssl", ignoreCase = true) ->
                    HealthCheckResult.Unhealthy("TLS error — try http:// if your server doesn't use HTTPS")
                else -> HealthCheckResult.Unhealthy("Connection failed: $msg")
            }
        } catch (e: Exception) {
            HealthCheckResult.Unhealthy("Unexpected error: ${e.message}")
        }
    }

    // --- Session CRUD ---

    suspend fun listSessions(limit: Int = 50): List<SessionItem> = withContext(Dispatchers.IO) {
        try {
            val request = authRequest("$baseUrl/api/sessions?limit=$limit").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<SessionListResponse>(body)
                parsed.items ?: parsed.sessions ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list sessions: ${e.message}")
            emptyList()
        }
    }

    suspend fun createSession(title: String? = null): SessionItem? = withContext(Dispatchers.IO) {
        try {
            val reqBody = json.encodeToString(CreateSessionRequest(title = title))
            val request = authRequest("$baseUrl/api/sessions")
                .post(reqBody.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = json.decodeFromString<SessionResponse>(body)
                parsed.session ?: parsed.id?.let {
                    SessionItem(id = it, title = parsed.title, model = parsed.model)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create session: ${e.message}")
            null
        }
    }

    suspend fun deleteSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = authRequest("$baseUrl/api/sessions/$sessionId")
                .delete()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete session: ${e.message}")
            false
        }
    }

    suspend fun renameSession(sessionId: String, title: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val reqBody = json.encodeToString(RenameSessionRequest(title = title))
            val request = authRequest("$baseUrl/api/sessions/$sessionId")
                .patch(reqBody.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rename session: ${e.message}")
            false
        }
    }

    suspend fun getMessages(sessionId: String): List<MessageItem> = withContext(Dispatchers.IO) {
        try {
            val request = authRequest("$baseUrl/api/sessions/$sessionId/messages")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<MessageListResponse>(body)
                parsed.items ?: parsed.messages ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get messages: ${e.message}")
            emptyList()
        }
    }

    // --- Skills ---

    suspend fun getSkills(): List<SkillInfo> = withContext(Dispatchers.IO) {
        try {
            val request = authRequest("$baseUrl/api/skills").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                // Try structured response: { "skills": [...] } or { "items": [...] }
                try {
                    val parsed = json.decodeFromString<SkillListResponse>(body)
                    val skills = parsed.skills ?: parsed.items
                    if (skills != null) return@withContext skills
                } catch (_: Exception) { /* fall through */ }
                // Try direct array: [...]
                try {
                    return@withContext json.decodeFromString<List<SkillInfo>>(body)
                } catch (_: Exception) { /* fall through */ }
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch skills: ${e.message}")
            emptyList()
        }
    }

    // --- Server personalities ---

    /**
     * Personality config fetched from GET /api/config.
     * Names are the keys from config.agent.personalities.
     * Prompts map personality name → system prompt text.
     * Default is from config.display.personality (the server's active personality).
     */
    data class PersonalityConfig(
        val names: List<String> = emptyList(),
        val prompts: Map<String, String> = emptyMap(),
        val defaultName: String = "",
        val modelName: String = ""
    )

    suspend fun getPersonalities(): PersonalityConfig = withContext(Dispatchers.IO) {
        try {
            val request = authRequest("$baseUrl/api/config").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext PersonalityConfig()
                val body = response.body?.string() ?: return@withContext PersonalityConfig()
                val root = json.parseToJsonElement(body) as? JsonObject
                    ?: return@withContext PersonalityConfig()

                // Model name from top-level: { "model": "claude-opus-4-6", ... }
                val modelName = (root["model"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

                val config = root["config"] as? JsonObject
                    ?: return@withContext PersonalityConfig(modelName = modelName)

                // Personalities: config.agent.personalities { name: "system prompt", ... }
                val agent = config["agent"] as? JsonObject
                val personalitiesObj = agent?.get("personalities") as? JsonObject
                val prompts = personalitiesObj?.entries?.associate { (key, value) ->
                    key to ((value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
                } ?: emptyMap()

                // Default personality: config.display.personality
                val display = config["display"] as? JsonObject
                val defaultName = (display?.get("personality") as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content ?: ""

                PersonalityConfig(
                    names = prompts.keys.toList(),
                    prompts = prompts,
                    defaultName = defaultName,
                    modelName = modelName
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch personalities: ${e.message}")
            PersonalityConfig()
        }
    }

    // --- Chat streaming via /api/sessions/{id}/chat/stream ---

    fun sendChatStream(
        sessionId: String,
        message: String,
        systemMessage: String? = null,
        attachments: List<com.hermesandroid.relay.data.Attachment>? = null,
        onSessionId: (String) -> Unit,
        onMessageStarted: (String) -> Unit,
        onTextDelta: (String) -> Unit,
        onThinkingDelta: (String) -> Unit,
        onToolCallStart: (String, String) -> Unit,
        onToolCallDone: (String, String?) -> Unit,
        onToolCallFailed: (String, String?) -> Unit,
        onTurnComplete: () -> Unit,
        onComplete: () -> Unit,
        onUsage: (UsageInfo?) -> Unit,
        onError: (String) -> Unit
    ): EventSource {
        val requestPayload = buildJsonObject {
            put("message", message)
            if (!systemMessage.isNullOrBlank()) {
                put("system_message", systemMessage)
            }
            if (!attachments.isNullOrEmpty()) {
                putJsonArray("attachments") {
                    attachments.forEach { att ->
                        addJsonObject {
                            put("contentType", att.contentType)
                            put("content", att.content)
                        }
                    }
                }
            }
        }
        val requestBody = json.encodeToString(JsonObject.serializer(), requestPayload)

        val request = authRequest("$baseUrl/api/sessions/$sessionId/chat/stream")
            .header("Accept", "text/event-stream")
            .post(requestBody.toRequestBody(JSON_MEDIA))
            .build()

        val completeCalled = AtomicBoolean(false)

        // Notify caller of the session ID being used
        mainHandler.post { onSessionId(sessionId) }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    if (completeCalled.compareAndSet(false, true)) {
                        mainHandler.post { onComplete() }
                    }
                    return
                }

                try {
                    val event = json.decodeFromString<HermesSseEvent>(data)

                    // Check for usage data on ANY event before type resolution
                    // (OpenAI-format chunks have no type/event field but may carry usage)
                    if (event.usage != null && (event.usage.resolvedInputTokens != null || event.usage.resolvedOutputTokens != null)) {
                        mainHandler.post { onUsage(event.usage) }
                    }

                    val eventType = type ?: event.resolvedType ?: return

                    // Debug: log every SSE event type (content truncated for deltas)
                    if (eventType.startsWith("assistant.delta") || eventType == "tool.progress") {
                        Log.d(TAG, "SSE ← $eventType (${data.length} chars)")
                    } else {
                        Log.d(TAG, "SSE ← $eventType | ${data.take(300)}")
                    }

                    when (eventType) {
                        // --- Hermes-native events ---
                        "assistant.delta" -> {
                            val delta = event.delta
                            if (!delta.isNullOrEmpty()) {
                                mainHandler.post { onTextDelta(delta) }
                            }
                        }
                        "tool.progress" -> {
                            val delta = event.delta
                            if (!delta.isNullOrEmpty()) {
                                mainHandler.post { onThinkingDelta(delta) }
                            }
                        }
                        "tool.pending", "tool.started" -> {
                            val toolName = event.resolvedToolName ?: "unknown"
                            val callId = event.callId ?: event.toolCallId ?: toolName
                            Log.d(TAG, "SSE tool start: name=$toolName callId=$callId")
                            mainHandler.post { onToolCallStart(callId, toolName) }
                        }
                        "tool.completed" -> {
                            val callId = event.callId ?: event.toolCallId ?: event.resolvedToolName ?: ""
                            mainHandler.post { onToolCallDone(callId, event.resultPreview) }
                        }
                        "tool.failed" -> {
                            val callId = event.callId ?: event.toolCallId ?: event.resolvedToolName ?: ""
                            val errorMsg = event.error ?: event.messageText ?: "Tool failed"
                            mainHandler.post { onToolCallFailed(callId, errorMsg) }
                        }
                        // message.started — server assigns a new message ID for each turn
                        "message.started" -> {
                            val msgObj = event.message as? JsonObject
                            val serverMsgId = (msgObj?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content
                            if (serverMsgId != null) {
                                mainHandler.post { onMessageStarted(serverMsgId) }
                            }
                            Log.d(TAG, "SSE message.started: id=$serverMsgId")
                        }
                        // Informational events — acknowledged but not surfaced to UI yet
                        "session.created", "run.started",
                        "memory.updated", "skill.loaded", "artifact.created" -> {
                            Log.d(TAG, "SSE info event: $eventType")
                        }
                        // assistant.completed — one turn finished, but run may continue with tool calls
                        "assistant.completed" -> {
                            mainHandler.post {
                                onUsage(event.usage)
                                if (event.interrupted == true) {
                                    if (completeCalled.compareAndSet(false, true)) {
                                        onError("Response interrupted")
                                    }
                                } else {
                                    onTurnComplete()
                                }
                            }
                        }
                        // run.completed — the entire agent loop is done (all turns + tool calls)
                        "run.completed" -> {
                            if (completeCalled.compareAndSet(false, true)) {
                                mainHandler.post {
                                    onUsage(event.usage)
                                    if (event.interrupted == true) {
                                        onError("Run interrupted")
                                    } else {
                                        onComplete()
                                    }
                                }
                            }
                        }
                        "done" -> {
                            if (completeCalled.compareAndSet(false, true)) {
                                mainHandler.post { onComplete() }
                            }
                        }
                        "error" -> {
                            if (completeCalled.compareAndSet(false, true)) {
                                val msg = event.messageText ?: event.error ?: "Unknown error"
                                mainHandler.post { onError(msg) }
                            }
                        }

                        // --- Legacy/backward-compat event names ---
                        "thinking_delta", "reasoning_delta", "thinking" -> {
                            val thinkingText = event.thinkingDelta ?: event.thinking ?: event.delta
                            if (!thinkingText.isNullOrEmpty()) {
                                mainHandler.post { onThinkingDelta(thinkingText) }
                            }
                        }
                        "content_delta", "delta" -> {
                            val thinking = event.thinking ?: event.thinkingDelta
                            if (!thinking.isNullOrEmpty()) {
                                mainHandler.post { onThinkingDelta(thinking) }
                            }
                            val delta = event.delta
                            if (!delta.isNullOrEmpty()) {
                                mainHandler.post { onTextDelta(delta) }
                            }
                        }
                        "tool_start", "tool_started" -> {
                            val toolName = event.toolName ?: event.name ?: "unknown"
                            val callId = event.callId ?: event.toolCallId ?: toolName
                            mainHandler.post { onToolCallStart(callId, toolName) }
                        }
                        "tool_result", "tool_completed" -> {
                            val callId = event.callId ?: event.toolCallId ?: event.toolName ?: event.name ?: ""
                            mainHandler.post { onToolCallDone(callId, event.resultPreview) }
                        }
                        "content_complete", "complete", "completed" -> {
                            if (completeCalled.compareAndSet(false, true)) {
                                mainHandler.post {
                                    onUsage(event.usage)
                                    onComplete()
                                }
                            }
                        }
                        else -> {
                            Log.d(TAG, "Unhandled SSE event type: $eventType | data: ${data.take(200)}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unparseable SSE event ($type): ${e.message}\nRaw: $data")
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                if (completeCalled.compareAndSet(false, true)) {
                    val msg = when {
                        response != null && !response.isSuccessful ->
                            "API error ${response.code}: ${response.message}"
                        t is IOException -> "Connection failed: ${t.message}"
                        t != null -> "Stream error: ${t.message}"
                        else -> "Unknown stream error"
                    }
                    mainHandler.post { onError(msg) }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (completeCalled.compareAndSet(false, true)) {
                    mainHandler.post { onComplete() }
                }
            }
        }

        return sseFactory.newEventSource(request, listener)
    }

    // --- Run streaming via /v1/runs ---

    fun sendRunStream(
        message: String,
        model: String? = null,
        systemMessage: String? = null,
        attachments: List<com.hermesandroid.relay.data.Attachment>? = null,
        onSessionId: (String) -> Unit,
        onMessageStarted: (String) -> Unit,
        onTextDelta: (String) -> Unit,
        onThinkingDelta: (String) -> Unit,
        onToolCallStart: (String, String) -> Unit,
        onToolCallDone: (String, String?) -> Unit,
        onToolCallFailed: (String, String?) -> Unit,
        onTurnComplete: () -> Unit,
        onComplete: () -> Unit,
        onUsage: (UsageInfo?) -> Unit,
        onError: (String) -> Unit
    ): EventSource {
        val requestPayload = buildJsonObject {
            put("model", model ?: "default")
            put("input", message)
            put("stream", true)
            if (!systemMessage.isNullOrBlank()) {
                put("system_message", systemMessage)
            }
            if (!attachments.isNullOrEmpty()) {
                putJsonArray("attachments") {
                    attachments.forEach { att ->
                        addJsonObject {
                            put("contentType", att.contentType)
                            put("content", att.content)
                        }
                    }
                }
            }
        }
        val requestBody = json.encodeToString(JsonObject.serializer(), requestPayload)

        val request = authRequest("$baseUrl/v1/runs")
            .header("Accept", "text/event-stream")
            .post(requestBody.toRequestBody(JSON_MEDIA))
            .build()

        val completeCalled = AtomicBoolean(false)

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    if (completeCalled.compareAndSet(false, true)) {
                        mainHandler.post { onComplete() }
                    }
                    return
                }

                try {
                    val event = json.decodeFromString<HermesSseEvent>(data)

                    // Check for usage data before type resolution (catches OpenAI-format chunks)
                    if (event.usage != null && (event.usage.resolvedInputTokens != null || event.usage.resolvedOutputTokens != null)) {
                        mainHandler.post { onUsage(event.usage) }
                    }

                    val eventType = type ?: event.resolvedType ?: return

                    when (eventType) {
                        "response.created" -> {
                            // Extract session/run ID if available
                            val sid = event.sessionId ?: event.runId
                            if (sid != null) {
                                mainHandler.post { onSessionId(sid) }
                            }
                            Log.d(TAG, "Run response created")
                        }
                        "response.in_progress" -> {
                            Log.d(TAG, "Run response in progress")
                        }
                        "response.output_item.added",
                        "response.content_part.added" -> {
                            Log.d(TAG, "Run SSE info: $eventType")
                        }
                        "response.output_text.delta" -> {
                            val delta = event.delta
                            if (!delta.isNullOrEmpty()) {
                                mainHandler.post { onTextDelta(delta) }
                            }
                        }
                        "response.output_text.done" -> {
                            Log.d(TAG, "Run output text done")
                        }
                        // Tool events — Hermes /v1/runs uses "tool" field, sessions uses "tool_name"
                        "tool.started", "tool.pending" -> {
                            val toolName = event.resolvedToolName ?: "unknown"
                            val callId = event.callId ?: event.toolCallId ?: toolName
                            Log.d(TAG, "Run SSE tool start: name=$toolName callId=$callId")
                            mainHandler.post { onToolCallStart(callId, toolName) }
                        }
                        "tool.completed" -> {
                            val callId = event.callId ?: event.toolCallId ?: event.resolvedToolName ?: ""
                            val durationStr = event.duration?.let { String.format("%.1fs", it) }
                            val preview = event.resultPreview ?: durationStr
                            mainHandler.post { onToolCallDone(callId, preview) }
                        }
                        "tool.failed" -> {
                            val callId = event.callId ?: event.toolCallId ?: event.resolvedToolName ?: ""
                            val errorMsg = event.error ?: event.messageText ?: "Tool failed"
                            mainHandler.post { onToolCallFailed(callId, errorMsg) }
                        }
                        "tool.progress" -> {
                            val delta = event.delta
                            if (!delta.isNullOrEmpty()) {
                                mainHandler.post { onThinkingDelta(delta) }
                            }
                        }
                        // Reasoning — /v1/runs uses "reasoning.available" with "text" field
                        "reasoning.available" -> {
                            val reasoningText = event.text
                            if (!reasoningText.isNullOrEmpty()) {
                                mainHandler.post { onThinkingDelta(reasoningText) }
                            }
                        }
                        // Text deltas — /v1/runs uses "message.delta", sessions uses "assistant.delta"
                        "message.delta", "assistant.delta" -> {
                            val delta = event.delta
                            if (!delta.isNullOrEmpty()) {
                                mainHandler.post { onTextDelta(delta) }
                            }
                        }
                        "response.completed" -> {
                            if (completeCalled.compareAndSet(false, true)) {
                                mainHandler.post {
                                    onUsage(event.usage)
                                    if (event.interrupted == true) {
                                        onError("Run interrupted")
                                    } else {
                                        onComplete()
                                    }
                                }
                            }
                        }
                        // assistant.completed — one turn done, run may continue
                        "assistant.completed" -> {
                            mainHandler.post {
                                onUsage(event.usage)
                                if (event.interrupted == true) {
                                    if (completeCalled.compareAndSet(false, true)) {
                                        onError("Response interrupted")
                                    }
                                } else {
                                    onTurnComplete()
                                }
                            }
                        }
                        "run.completed" -> {
                            if (completeCalled.compareAndSet(false, true)) {
                                mainHandler.post {
                                    onUsage(event.usage)
                                    if (event.interrupted == true) {
                                        onError("Run interrupted")
                                    } else {
                                        onComplete()
                                    }
                                }
                            }
                        }
                        "done" -> {
                            if (completeCalled.compareAndSet(false, true)) {
                                mainHandler.post { onComplete() }
                            }
                        }
                        "error", "run.failed" -> {
                            if (completeCalled.compareAndSet(false, true)) {
                                val msg = event.error ?: event.messageText ?: "Unknown error"
                                mainHandler.post { onError(msg) }
                            }
                        }
                        // message.started — server assigns a new message ID for each turn
                        "message.started" -> {
                            val msgObj = event.message as? JsonObject
                            val serverMsgId = (msgObj?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content
                            if (serverMsgId != null) {
                                mainHandler.post { onMessageStarted(serverMsgId) }
                            }
                            Log.d(TAG, "Run SSE message.started: id=$serverMsgId")
                        }
                        // Informational events
                        "session.created", "run.started",
                        "memory.updated", "skill.loaded", "artifact.created" -> {
                            Log.d(TAG, "Run SSE info event: $eventType")
                        }
                        else -> {
                            Log.d(TAG, "Unhandled run SSE event: $eventType | data: ${data.take(200)}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unparseable run SSE event ($type): ${e.message}\nRaw: $data")
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                if (completeCalled.compareAndSet(false, true)) {
                    val msg = when {
                        response != null && !response.isSuccessful ->
                            "API error ${response.code}: ${response.message}"
                        t is IOException -> "Connection failed: ${t.message}"
                        t != null -> "Stream error: ${t.message}"
                        else -> "Unknown stream error"
                    }
                    mainHandler.post { onError(msg) }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (completeCalled.compareAndSet(false, true)) {
                    mainHandler.post { onComplete() }
                }
            }
        }

        return sseFactory.newEventSource(request, listener)
    }

    // --- Capability detection ---

    /**
     * Probe the server to determine which chat API is available.
     * Checks /health, then /api/sessions (enhanced), then /v1/models (portable).
     */
    suspend fun detectChatMode(): ChatMode = withContext(Dispatchers.IO) {
        // 1. Basic connectivity
        try {
            val healthReq = authRequest("$baseUrl/health").get().build()
            client.newCall(healthReq).execute().use { response ->
                if (!response.isSuccessful) return@withContext ChatMode.DISCONNECTED
            }
        } catch (_: Exception) {
            return@withContext ChatMode.DISCONNECTED
        }

        // 2. Try enhanced sessions API
        try {
            val sessionsReq = authRequest("$baseUrl/api/sessions?limit=1").get().build()
            client.newCall(sessionsReq).execute().use { response ->
                if (response.isSuccessful) return@withContext ChatMode.ENHANCED_HERMES
            }
        } catch (_: Exception) { /* fall through */ }

        // 3. Try OpenAI-compatible models endpoint
        try {
            val modelsReq = authRequest("$baseUrl/v1/models").get().build()
            client.newCall(modelsReq).execute().use { response ->
                if (response.isSuccessful) return@withContext ChatMode.PORTABLE
            }
        } catch (_: Exception) { /* fall through */ }

        // Server is reachable but neither API is available
        ChatMode.DISCONNECTED
    }

    // --- Lifecycle ---

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        try {
            if (!client.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                client.dispatcher.executorService.shutdownNow()
            }
        } catch (_: InterruptedException) {
            client.dispatcher.executorService.shutdownNow()
        }
        client.connectionPool.evictAll()
    }

    private fun authRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        return builder
    }
}

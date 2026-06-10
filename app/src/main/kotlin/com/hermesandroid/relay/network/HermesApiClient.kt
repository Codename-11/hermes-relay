package com.hermesandroid.relay.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hermesandroid.relay.data.AgentDisplay
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
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
 * Per-endpoint capability snapshot. Populated by [HermesApiClient.probeCapabilities].
 *
 * The Android client uses this to pick the best chat path automatically when
 * `streamingEndpoint = "auto"`. The bootstrap-injected vanilla-upstream case
 * is the interesting one: `sessionsApi=true` (we injected it) but
 * `sessionsChatStream=false` (the chat handler is absent). The auto-resolver
 * now picks OpenAI-compatible chat completions for that case because the route
 * returns an SSE stream, while `/v1/runs` may be an async JSON run-start API.
 */
data class ServerCapabilities(
    /** `/api/sessions` (CRUD) — true on native upstream, fork, OR bootstrap-injected older builds. */
    val sessionsApi: Boolean,
    /** `/api/sessions/{id}/chat/stream` (SSE) — true on native upstream or legacy fork builds. */
    val sessionsChatStream: Boolean,
    /** `/v1/runs` (structured-event SSE) — true only when explicitly advertised as SSE-compatible. */
    val runs: Boolean,
    /** `/v1/chat/completions` — OpenAI-compatible fallback. */
    val portable: Boolean,
    /** `/health` — basic reachability. */
    val healthy: Boolean,
) {
    /** Resolve `streamingEndpoint = "auto"` to the best concrete choice. */
    fun preferredChatEndpoint(): String = when {
        sessionsChatStream -> "sessions"
        portable -> "completions"
        runs -> "runs"
        else -> "sessions"  // last-resort: try sessions, will surface a clear error
    }

    fun toChatMode(): ChatMode = when {
        !healthy -> ChatMode.DISCONNECTED
        sessionsApi -> ChatMode.ENHANCED_HERMES
        portable || runs -> ChatMode.PORTABLE
        else -> ChatMode.DISCONNECTED
    }

    companion object {
        val DISCONNECTED = ServerCapabilities(
            sessionsApi = false,
            sessionsChatStream = false,
            runs = false,
            portable = false,
            healthy = false,
        )
    }
}

private fun JsonObject.childObject(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.booleanFlag(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull == true

private fun JsonObject.hasEndpoint(key: String): Boolean {
    val path = ((this[key] as? JsonObject)?.get("path") as? JsonPrimitive)?.contentOrNull
    return !path.isNullOrBlank()
}

internal fun parseCapabilitiesBody(json: Json, body: String): ServerCapabilities? {
    val root = try {
        json.decodeFromString<JsonObject>(body)
    } catch (_: Exception) {
        return null
    }

    val features = root.childObject("features")
    val endpoints = root.childObject("endpoints")
    if (features == null && endpoints == null) return null

    fun feature(name: String): Boolean = features?.booleanFlag(name) == true
    fun endpoint(name: String): Boolean = endpoints?.hasEndpoint(name) == true

    return ServerCapabilities(
        sessionsApi = feature("session_resources") ||
            endpoint("sessions") ||
            endpoint("session_create"),
        sessionsChatStream = feature("session_chat_streaming") ||
            endpoint("session_chat_stream"),
        runs = feature("run_events_sse") || endpoint("run_events"),
        portable = feature("chat_completions_streaming") ||
            feature("chat_completions") ||
            endpoint("chat_completions"),
        healthy = true,
    )
}

internal val HERMES_SKILL_ENDPOINTS = listOf("/v1/skills", "/api/skills")

internal fun parseSkillListBody(json: Json, body: String): List<SkillInfo>? {
    try {
        val parsed = json.decodeFromString<SkillListResponse>(body)
        val skills = parsed.skills ?: parsed.items ?: parsed.data
        if (skills != null) return skills
    } catch (_: Exception) {
        // Fall through to direct-array compatibility below.
    }

    try {
        return json.decodeFromString<List<SkillInfo>>(body)
    } catch (_: Exception) {
        return null
    }
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

    suspend fun checkSessionsAuthDetailed(): HealthCheckResult = withContext(Dispatchers.IO) {
        try {
            val request = authRequest("$baseUrl/api/sessions?limit=1").get().build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> HealthCheckResult.Healthy
                    response.code == 401 || response.code == 403 ->
                        HealthCheckResult.Unhealthy("API reachable, but sessions auth failed - check your API key")
                    response.code == 404 ->
                        HealthCheckResult.Unhealthy("API reachable, but /api/sessions is unavailable")
                    else ->
                        HealthCheckResult.Unhealthy("Sessions check returned HTTP ${response.code}")
                }
            }
        } catch (e: javax.net.ssl.SSLException) {
            if (baseUrl.startsWith("https://", ignoreCase = true)) {
                HealthCheckResult.Unhealthy("TLS handshake failed - try http:// if your server doesn't use HTTPS")
            } else {
                HealthCheckResult.Unhealthy("SSL error: ${e.message}")
            }
        } catch (e: java.net.ConnectException) {
            HealthCheckResult.Unhealthy("Connection refused - check the URL and port")
        } catch (e: java.net.UnknownHostException) {
            HealthCheckResult.Unhealthy("Server not found - check the hostname")
        } catch (e: java.net.SocketTimeoutException) {
            HealthCheckResult.Unhealthy("Connection timed out - is the server running?")
        } catch (e: IOException) {
            HealthCheckResult.Unhealthy("Connection failed: ${e.message ?: "I/O error"}")
        } catch (e: Exception) {
            HealthCheckResult.Unhealthy("Unexpected error: ${e.message}")
        }
    }

    // --- Session CRUD ---

    suspend fun listSessionsResult(limit: Int = 50): Result<List<SessionItem>> = withContext(Dispatchers.IO) {
        try {
            val request = authRequest("$baseUrl/api/sessions?limit=$limit").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(apiFailure(response, "List sessions"))
                }
                val body = response.body.string()
                if (body.isBlank()) {
                    return@withContext Result.failure(IOException("List sessions returned an empty response"))
                }
                val parsed = json.decodeFromString<SessionListResponse>(body)
                Result.success(parsed.data ?: parsed.items ?: parsed.sessions ?: emptyList())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list sessions: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun listSessions(limit: Int = 50): List<SessionItem> =
        listSessionsResult(limit).getOrElse { emptyList() }

    suspend fun createSessionResult(
        title: String? = null,
        profileName: String? = null,
        model: String? = null,
    ): Result<SessionItem> = withContext(Dispatchers.IO) {
        try {
            val reqBody = json.encodeToString(
                CreateSessionRequest(
                    title = title,
                    model = model,
                    profile = AgentDisplay.profileRequestName(profileName),
                ),
            )
            val request = authRequest("$baseUrl/api/sessions")
                .post(reqBody.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(apiFailure(response, "Create session"))
                }
                val body = response.body.string()
                if (body.isBlank()) {
                    return@withContext Result.failure(IOException("Create session returned an empty response"))
                }
                val parsed = json.decodeFromString<SessionResponse>(body)
                val session = parsed.session ?: parsed.id?.let {
                    SessionItem(id = it, title = parsed.title, model = parsed.model)
                }
                session?.let { Result.success(it) }
                    ?: Result.failure(IOException("Create session response missing session id"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create session: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun createSession(
        title: String? = null,
        profileName: String? = null,
        model: String? = null,
    ): SessionItem? =
        createSessionResult(title, profileName, model).getOrNull()

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
                parsed.data ?: parsed.items ?: parsed.messages ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get messages: ${e.message}")
            emptyList()
        }
    }

    // --- Skills ---

    suspend fun getSkills(): List<SkillInfo> = withContext(Dispatchers.IO) {
        for (endpoint in HERMES_SKILL_ENDPOINTS) {
            try {
                val request = authRequest("$baseUrl$endpoint").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val skills = parseSkillListBody(json, body)
                    if (skills != null) return@withContext skills
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch skills from $endpoint: ${e.message}")
            }
        }

        emptyList()
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

                // Default display identity. Upstream Hermes currently uses
                // config.display.personality for the active persona and often
                // mirrors the same identity through skin. Accept name-style
                // aliases too so older or profile-specific configs don't make
                // Android fall back to the literal "Hermes" label.
                val display = config["display"] as? JsonObject
                val defaultPersonality = display.stringField("personality")
                val defaultName = firstNonBlank(
                    defaultPersonality.takeUnless { it.equals("default", ignoreCase = true) },
                    display.stringField("agent_name"),
                    display.stringField("assistant_name"),
                    display.stringField("display_name"),
                    display.stringField("name"),
                    display.stringField("skin"),
                    defaultPersonality,
                )

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

    /**
     * Stream chat via the sessions endpoint.
     *
     * @param modelOverride When non-null and non-blank, injects `"model":
     *   "<value>"` at the top level of the session-chat request body,
     *   asking the server to use that model for this turn. When null or
     *   blank the `model` field is omitted entirely and the server falls
     *   back to its session default. Used by the agent-profile picker so
     *   an explicit user choice wins over implicit session/server defaults.
     */
    fun sendChatStream(
        sessionId: String,
        message: String,
        systemMessage: String? = null,
        attachments: List<com.hermesandroid.relay.data.Attachment>? = null,
        /**
         * Pre-built OpenAI-format synthetic messages to splice into the
         * payload alongside the live `message`. Produced by
         * [com.hermesandroid.relay.voice.VoiceIntentSyncBuilder.buildSyntheticMessages]
         * for the v0.4.1 voice-intent → server session sync feature.
         *
         * When non-empty, the request body grows a top-level `messages`
         * array containing the synthetic `assistant` (with `tool_calls`)
         * + `tool` (with `tool_call_id`) pairs. The server-side session
         * absorbs them into its conversation history so the LLM sees
         * prior phone-local voice actions in its session memory.
         *
         * Null / empty on every send that has no unsynced voice intents
         * to communicate, which is the common case after the first sync.
         * The Hermes API server treats unrecognised body fields
         * permissively (matches OpenAI Chat Completions semantics), so
         * this stays a safe additive change against any conformant
         * upstream.
         */
        voiceIntentMessages: JsonArray? = null,
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
        onError: (String) -> Unit,
        modelOverride: String? = null,
        profileName: String? = null,
    ): EventSource {
        if (!modelOverride.isNullOrBlank()) {
            Log.d(TAG, "sendChatStream: modelOverride=$modelOverride (profile pick)")
        }
        AgentDisplay.profileRequestName(profileName)?.let {
            Log.d(TAG, "sendChatStream: profile=$it")
        }
        val requestPayload = buildSessionChatStreamPayload(
            message = message,
            systemMessage = systemMessage,
            attachments = attachments,
            voiceIntentMessages = voiceIntentMessages,
            modelOverride = modelOverride,
            profileName = profileName,
        )
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

    // --- OpenAI-compatible chat streaming via /v1/chat/completions ---

    /**
     * Stream chat through the OpenAI-compatible chat completions endpoint.
     *
     * This is the portable SSE fallback for servers that expose
     * `/v1/chat/completions` but where `/v1/runs` is an async JSON run-start
     * API rather than an EventSource-compatible stream.
     */
    fun sendChatCompletionsStream(
        message: String,
        model: String? = null,
        systemMessage: String? = null,
        attachments: List<com.hermesandroid.relay.data.Attachment>? = null,
        voiceIntentMessages: JsonArray? = null,
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
        onError: (String) -> Unit,
        modelOverride: String? = null,
        profileName: String? = null,
    ): EventSource {
        if (!modelOverride.isNullOrBlank()) {
            Log.d(TAG, "sendChatCompletionsStream: modelOverride=$modelOverride (profile pick, was model=$model)")
        }
        AgentDisplay.profileRequestName(profileName)?.let {
            Log.d(TAG, "sendChatCompletionsStream: profile=$it")
        }
        val requestPayload = buildChatCompletionsStreamPayload(
            message = message,
            model = model,
            systemMessage = systemMessage,
            attachments = attachments,
            voiceIntentMessages = voiceIntentMessages,
            modelOverride = modelOverride,
            profileName = profileName,
        )
        val requestBody = json.encodeToString(JsonObject.serializer(), requestPayload)

        val request = authRequest("$baseUrl/v1/chat/completions")
            .header("Accept", "text/event-stream")
            .post(requestBody.toRequestBody(JSON_MEDIA))
            .build()

        val completeCalled = AtomicBoolean(false)
        val messageStarted = AtomicBoolean(false)

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
                    val event = json.decodeFromString<JsonObject>(data)
                    openAiErrorMessage(event)?.let { msg ->
                        if (completeCalled.compareAndSet(false, true)) {
                            mainHandler.post { onError(msg) }
                        }
                        return
                    }

                    openAiUsage(event)?.let { usage ->
                        mainHandler.post { onUsage(usage) }
                    }

                    if (messageStarted.compareAndSet(false, true)) {
                        openAiMessageId(event)?.let { messageId ->
                            mainHandler.post { onMessageStarted(messageId) }
                        }
                    }

                    openAiReasoningDelta(event)?.let { reasoning ->
                        if (reasoning.isNotEmpty()) {
                            mainHandler.post { onThinkingDelta(reasoning) }
                        }
                    }

                    openAiTextDelta(event)?.let { delta ->
                        if (delta.isNotEmpty()) {
                            mainHandler.post { onTextDelta(delta) }
                        }
                    }

                    val finishReason = openAiFinishReason(event)
                    if (!finishReason.isNullOrBlank() && completeCalled.compareAndSet(false, true)) {
                        mainHandler.post { onComplete() }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unparseable chat completion SSE event ($type): ${e.message}\nRaw: $data")
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

    private fun openAiChoice(event: JsonObject): JsonObject? =
        (event["choices"] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }

    private fun openAiDelta(event: JsonObject): JsonObject? =
        openAiChoice(event)?.get("delta") as? JsonObject

    private fun openAiTextDelta(event: JsonObject): String? =
        (openAiDelta(event)?.get("content") as? JsonPrimitive)?.contentOrNull

    private fun openAiReasoningDelta(event: JsonObject): String? {
        val delta = openAiDelta(event) ?: return null
        return (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
            ?: (delta["reasoning"] as? JsonPrimitive)?.contentOrNull
            ?: (delta["thinking"] as? JsonPrimitive)?.contentOrNull
    }

    private fun openAiFinishReason(event: JsonObject): String? =
        (openAiChoice(event)?.get("finish_reason") as? JsonPrimitive)?.contentOrNull

    private fun openAiMessageId(event: JsonObject): String? =
        (event["id"] as? JsonPrimitive)?.contentOrNull

    private fun openAiErrorMessage(event: JsonObject): String? {
        val error = event["error"] ?: return null
        return when (error) {
            is JsonPrimitive -> error.contentOrNull
            is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
                ?: (error["error"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
    }

    private fun openAiUsage(event: JsonObject): UsageInfo? =
        (event["usage"] as? JsonObject)?.let { usage ->
            runCatching { json.decodeFromJsonElement<UsageInfo>(usage) }.getOrNull()
        }

    // --- Run streaming via /v1/runs ---

    /**
     * Stream a run via `/v1/runs`.
     *
     * @param model Caller's default model selection (nullable). When
     *   [modelOverride] is null/blank this is used as the `model` field,
     *   or `"default"` if both are null — preserving the pre-profile
     *   behaviour exactly.
     * @param modelOverride When non-null and non-blank, wins over [model]
     *   and is injected as the top-level `"model"` field in the run
     *   request body. Used by the agent-profile picker so an explicit
     *   user-selected profile model takes precedence over any implicit
     *   caller default. When null/blank this parameter is ignored and
     *   [model] drives selection as before.
     */
    fun sendRunStream(
        message: String,
        model: String? = null,
        systemMessage: String? = null,
        attachments: List<com.hermesandroid.relay.data.Attachment>? = null,
        /** See [sendChatStream]'s `voiceIntentMessages` doc — same semantics. */
        voiceIntentMessages: JsonArray? = null,
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
        onError: (String) -> Unit,
        modelOverride: String? = null,
        profileName: String? = null,
    ): EventSource {
        if (!modelOverride.isNullOrBlank()) {
            Log.d(TAG, "sendRunStream: modelOverride=$modelOverride (profile pick, was model=$model)")
        }
        AgentDisplay.profileRequestName(profileName)?.let {
            Log.d(TAG, "sendRunStream: profile=$it")
        }
        val requestPayload = buildRunStreamPayload(
            message = message,
            model = model,
            systemMessage = systemMessage,
            attachments = attachments,
            voiceIntentMessages = voiceIntentMessages,
            modelOverride = modelOverride,
            profileName = profileName,
        )
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
     * Convenience wrapper around [probeCapabilities] that collapses the
     * per-endpoint result into the older 3-state ChatMode enum for callers
     * that don't need the detail.
     */
    suspend fun detectChatMode(): ChatMode = probeCapabilities().toChatMode()

    /**
     * Probe each endpoint we care about and return a per-route capability
     * snapshot. This is the source of truth for "which chat path should we
     * use" — see [ServerCapabilities.preferredChatEndpoint].
     *
     * Probe order:
     *   1. `/health` — if this fails, everything else is moot.
     *   2. `GET /v1/capabilities` — native upstream feature + endpoint map.
     *   3. `HEAD /api/sessions?limit=1` — sessions CRUD (true on fork,
     *      native upstream, OR bootstrap-injected older upstream).
     *   4. `HEAD /api/sessions/probe/chat/stream` — chat-stream handler
     *      presence. The handler only accepts POST, so HEAD returns 405
     *      (Method Not Allowed) when the route is registered. 404 means
     *      the route doesn't exist at all.
     *   5. `HEAD /v1/chat/completions` — OpenAI-compatible SSE fallback.
     *   6. `HEAD /v1/runs` with `Accept: text/event-stream` — accepted only
     *      when the response explicitly advertises event-stream compatibility.
     *
     * **Why HEAD instead of OPTIONS:** The hermes-agent gateway runs CORS
     * middleware (`security_headers_middleware`) that intercepts OPTIONS
     * preflight requests and returns 403 for both existing AND missing
     * paths — making OPTIONS useless as a probe. HEAD bypasses the CORS
     * middleware path and surfaces the actual router status (200/401/405
     * for present, 404 for missing). Verified empirically against the
     * production hermes-agent gateway on 2026-04-12.
     *
     * **Route presence criterion:** for sessions and completions, any HTTP
     * response code that isn't 404 means the route is registered. We accept
     * 200, 204, 401, 403, 405, 415, etc. as positive because the alternative
     * (404) is the only signal that means "no such path." `/v1/runs` is
     * stricter: route presence alone is not enough because async runs can
     * return `202 application/json`; auto only uses it if event-stream support
     * is explicitly advertised.
     *
     * Network errors (connection refused, DNS failure, etc.) count as
     * "missing" since we can't differentiate from a server-down case.
     */
    suspend fun probeCapabilities(): ServerCapabilities = withContext(Dispatchers.IO) {
        // 1. Health
        val healthy = try {
            val req = authRequest("$baseUrl/health").get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
        if (!healthy) return@withContext ServerCapabilities.DISCONNECTED

        val advertisedCapabilities = try {
            val req = authRequest("$baseUrl/v1/capabilities").get().build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    parseCapabilitiesBody(json, response.body.string())
                }
            }
        } catch (_: Exception) {
            null
        }
        if (advertisedCapabilities != null) return@withContext advertisedCapabilities

        // Reusable HEAD probe — returns true if the route is registered
        // (any status except 404 + network errors). Already inside the
        // Dispatchers.IO context from the outer withContext, so the
        // blocking OkHttp calls are safe here.
        fun routeExists(path: String): Boolean = try {
            val req = authRequest("$baseUrl$path").head().build()
            client.newCall(req).execute().use { response -> response.code != 404 }
        } catch (_: Exception) {
            false
        }

        fun Response.advertisesEventStream(): Boolean {
            val contentType = header("Content-Type").orEmpty()
            val streamMode = header("X-Hermes-Stream-Mode").orEmpty()
            val runStreaming = header("X-Hermes-Run-Streaming").orEmpty()
            return contentType.contains("text/event-stream", ignoreCase = true) ||
                streamMode.equals("sse", ignoreCase = true) ||
                runStreaming.equals("sse", ignoreCase = true)
        }

        fun routeExplicitlySupportsEventStream(path: String): Boolean = try {
            val req = authRequest("$baseUrl$path")
                .head()
                .header("Accept", "text/event-stream")
                .build()
            client.newCall(req).execute().use { response ->
                response.code != 404 && response.advertisesEventStream()
            }
        } catch (_: Exception) {
            false
        }

        val sessionsApi = routeExists("/api/sessions?limit=1")
        val sessionsChatStream = routeExists("/api/sessions/probe/chat/stream")
        val portable = routeExists("/v1/chat/completions")
        val runs = routeExplicitlySupportsEventStream("/v1/runs")

        ServerCapabilities(
            sessionsApi = sessionsApi,
            sessionsChatStream = sessionsChatStream,
            runs = runs,
            portable = portable,
            healthy = true,
        )
    }

    suspend fun probeAudioApi(): Boolean = withContext(Dispatchers.IO) {
        val healthy = try {
            val req = authRequest("$baseUrl/health").get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
        if (!healthy) return@withContext false

        fun routeExists(path: String): Boolean = try {
            val req = authRequest("$baseUrl$path").head().build()
            client.newCall(req).execute().use { response -> response.code != 404 }
        } catch (_: Exception) {
            false
        }

        routeExists("/api/audio/transcribe") && routeExists("/api/audio/speak")
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

    private fun apiFailure(response: Response, operation: String): IOException {
        val detail = response.message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        val message = when (response.code) {
            401, 403 -> "$operation unauthorized - check your API key"
            in 500..599 -> "$operation failed - server error HTTP ${response.code}"
            else -> "$operation failed - HTTP ${response.code}$detail"
        }
        return IOException(message)
    }

    private fun JsonObject?.stringField(name: String): String =
        ((this?.get(name) as? JsonPrimitive)?.contentOrNull ?: "").trim()

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
}

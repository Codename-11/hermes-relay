package com.hermesandroid.relay.network.relay

import android.content.Context
import android.util.Log
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.EnhancedVoiceOverrides
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.RealtimeConversationContextMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP/WebSocket client for the Hermes relay voice endpoints.
 *
 * Main voice output path:
 *   GET  /voice/output/config
 *   POST /voice/output/session
 *   GET  /voice/output/{session_id}
 *
 * Realtime provider lab path:
 *   GET  /voice/realtime/config
 *   POST /voice/realtime/session
 *   GET  /voice/realtime/{session_id}
 *
 * Experimental realtime-agent engine path:
 *   GET  /voice/realtime-agent/config
 *   POST /voice/realtime-agent/session
 *   GET  /voice/realtime-agent/{session_id}
 *
 * Basic fallback path:
 *   POST /voice/transcribe  - multipart upload, returns `{"text": "..."}`
 *   POST /voice/synthesize  - JSON `{"text": "..."}`, returns audio/mpeg bytes
 *
 * Auth, URL conversion (`ws` <-> `http`), and error shape mirror
 * [RelayHttpClient]. Bearer precedence: **paired Relay session token first**,
 * Hermes API key as fallback for chat+voice-only installs that haven't paired.
 *
 * The session token is preferred even when an API key is also present because
 * the relay applies a transport guard to API-bearer auth on the voice routes
 * (`_request_is_secure_enough_for_api_bearer` in `plugin/relay/voice_auth.py`):
 * over plain LAN `ws://`, API bearer is rejected with 403 even though the
 * session token would be accepted. Once paired, the session is the trusted
 * credential — use it. The API-key path stays for installs that never paired.
 *
 * We take the providers as constructor args instead of sharing a
 * [RelayHttpClient] instance so the classes stay decoupled.
 */
class RelayVoiceClient(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val relayUrlProvider: () -> String?,
    private val sessionTokenProvider: suspend () -> String?,
    private val profileNameProvider: () -> String? = { null },
    private val apiBearerTokenProvider: suspend () -> String? = { null },
    private val relayRouteChangesProvider: (() -> Flow<String>)? = null,
    private val routeProbeRequester: (() -> Unit)? = null,
    private val webSocketFactory: ((Request, WebSocketListener) -> WebSocket)? = null,
    private val realtimeResumeRetryIntervalMs: Long = REALTIME_RESUME_RETRY_INTERVAL_MS,
    private val realtimeResumeRetryWindowMs: Long = REALTIME_RESUME_RETRY_WINDOW_MS,
) {

    companion object {
        private const val TAG = "RelayVoiceClient"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val MP4_AUDIO = "audio/mp4".toMediaType()
        private val WAV_AUDIO = "audio/wav".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private const val REALTIME_TIMEOUT_MS = 90_000L
        private const val REALTIME_AGENT_IDLE_TIMEOUT_MS = 90_000L
        private const val REALTIME_AGENT_MAX_TURN_MS = 5 * 60_000L
        private const val REALTIME_AGENT_WAIT_SLICE_MS = 1_000L
        private const val REALTIME_TURN_SOCKET_READY_TIMEOUT_MS = 17_000L
        private const val REALTIME_INPUT_CHUNK_BYTES = 6_400
        private const val SESSION_CALL_TIMEOUT_SECONDS = 15L

        /**
         * Periodic resume retry for the realtime-agent socket. A failed resume
         * used to park forever on "waiting for route change" — but the relay
         * holds a detached session (and any background run's result) open for
         * minutes, so the client should keep knocking until that window closes.
         */
        private const val REALTIME_RESUME_RETRY_INTERVAL_MS = 10_000L
        private const val REALTIME_RESUME_RETRY_WINDOW_MS = 5 * 60_000L
        private const val REALTIME_TURN_RESUME_RETRY_THROTTLE_MS = 750L

        /**
         * Provider "errors" that must NOT end a live realtime turn. Cancelling a
         * response when none is active (the background-summary re-injection path)
         * makes xAI emit "Cancellation failed: no active response found" — a benign
         * notice that should never close the session or dead-end the user with a
         * Retry. The relay now filters these too; this is defense in depth.
         */
        private fun isTransientRealtimeProviderError(message: String): Boolean {
            val m = message.lowercase()
            return m.contains("no active response") ||
                m.contains("cancellation failed") ||
                (m.contains("timed out") && m.contains("inactivity")) ||
                m.contains("voice session expired after a long silence")
        }
    }

    private fun sessionClient(): OkHttpClient =
        okHttpClient.newBuilder()
            .callTimeout(SESSION_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    private fun openWebSocket(request: Request, listener: WebSocketListener): WebSocket =
        webSocketFactory?.invoke(request, listener) ?: okHttpClient.newWebSocket(request, listener)

    private fun requestRouteProbeOnce(
        surface: String,
        reason: String?,
        requested: AtomicBoolean,
    ) {
        val requester = routeProbeRequester ?: return
        if (!requested.compareAndSet(false, true)) return
        Log.i(TAG, "$surface requesting endpoint re-probe after voice route failure: ${reason ?: "route failure"}")
        runCatching { requester() }
            .onFailure { Log.w(TAG, "$surface endpoint re-probe request failed: ${it.message}") }
    }

    /**
     * Upload [audioFile] to `/voice/transcribe` and return the transcribed
     * text. Expects a JSON response of the form
     *   `{"text": "...", "provider": "...", "success": true}`.
     */
    suspend fun transcribe(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(
                missingAuthError()
            )
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(
                IOException("Audio file missing or empty: ${audioFile.name}")
            )
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "audio",
                filename = audioFile.name,
                body = audioFile.asRequestBody(mediaTypeForAudioFile(audioFile)),
            )
            .build()

        val request = Request.Builder()
            .url(urlWithProfile("$httpBase/voice/transcribe"))
            .post(body)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) {
                    return@withContext Result.failure(IOException("Empty transcription response"))
                }
                val obj = try {
                    json.parseToJsonElement(raw).jsonObject
                } catch (e: Exception) {
                    return@withContext Result.failure(IOException("Transcribe returned non-JSON: ${e.message ?: "parse error"}"))
                }
                val text = (obj["text"] as? JsonPrimitive)?.content
                if (text.isNullOrEmpty()) {
                    val success = (obj["success"] as? JsonPrimitive)?.content
                    return@withContext Result.failure(
                        IOException("Transcription empty (success=$success)")
                    )
                }
                Result.success(text)
            }
        } catch (e: IOException) {
            Log.w(TAG, "transcribe failed: ${e.message}")
            Result.failure(IOException("Voice transcribe failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Log.w(TAG, "transcribe unexpected error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * POST [text] to `/voice/synthesize`, stream the audio/mpeg response
     * bytes to a temp file in [Context.getCacheDir], and return the file.
     *
     * The TTS endpoint is not streamed — the relay buffers the full mp3 and
     * returns it in one shot. Caller is responsible for deleting the file
     * when done (typical pattern: keep the last N mp3s in the cache dir and
     * let the OS reclaim on cache pressure).
     */
    suspend fun synthesize(
        text: String,
        enhanced: EnhancedVoiceOverrides? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(
                missingAuthError()
            )
        }
        if (text.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("synthesize: text is blank"))
        }

        val bodyJson = buildJsonObject {
            put("text", JsonPrimitive(text))
            // Per-request enhanced-voice overrides. The relay maps these generic
            // fields onto the active provider (Gemini/xAI) and ignores them for
            // others — see voice.py:_extract_voice_overrides.
            enhanced?.let { ov ->
                ov.voice?.let { put("voice", JsonPrimitive(it)) }
                ov.model?.let { put("model", JsonPrimitive(it)) }
                ov.audioTags?.let { put("audio_tags", JsonPrimitive(it)) }
                ov.personaPrompt?.let { put("persona_prompt", JsonPrimitive(it)) }
                ov.language?.let { put("language", JsonPrimitive(it)) }
            }
            putProfile()
        }.toString()

        val request = Request.Builder()
            .url("$httpBase/voice/synthesize")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Accept", "audio/mpeg")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, errBody))
                    )
                }
                val body = response.body
                    ?: return@withContext Result.failure(IOException("Empty synthesize response body"))

                val outFile = File(context.cacheDir, "voice_tts_${System.currentTimeMillis()}.mp3")
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (outFile.length() == 0L) {
                    outFile.delete()
                    return@withContext Result.failure(IOException("Synthesize returned 0 bytes"))
                }
                Result.success(outFile)
            }
        } catch (e: IOException) {
            Log.w(TAG, "synthesize failed: ${e.message}")
            Result.failure(IOException("Voice synthesize failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Log.w(TAG, "synthesize unexpected error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Probe `/voice/config` to discover which TTS/STT providers the relay
     * has active. Used by VoiceSettingsScreen to show read-only provider
     * labels and by VoiceViewModel to surface a "no voice providers"
     * error when the backend isn't configured.
     */
    suspend fun getVoiceConfig(): Result<VoiceConfig> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(
                missingAuthError()
            )
        }

        val request = Request.Builder()
            .url(urlWithProfile("$httpBase/voice/config"))
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        try {
            sessionClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) {
                    return@withContext Result.failure(IOException("Empty voice config response"))
                }
                try {
                    Result.success(json.decodeFromString(VoiceConfig.serializer(), raw))
                } catch (e: Exception) {
                    Result.failure(IOException("Voice config parse failed: ${e.message ?: "parse error"}"))
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "getVoiceConfig failed: ${e.message}")
            Result.failure(IOException("Voice config failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Log.w(TAG, "getVoiceConfig unexpected error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getRealtimeVoiceConfig(): Result<RealtimeVoiceConfig> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }

        val request = Request.Builder()
            .url(urlWithProfile("$httpBase/voice/realtime/config"))
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        try {
            sessionClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                Result.success(json.decodeFromString(RealtimeVoiceConfig.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("Realtime voice config failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRealtimeProviderOptions(
        providerId: String,
    ): Result<VoiceProviderOptionsResponse> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }
        val provider = providerId.trim()
        if (provider.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Provider ID required"))
        }

        val request = Request.Builder()
            .url(urlWithProfile("$httpBase/voice/realtime/providers/${pathSegment(provider)}/options"))
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        try {
            sessionClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body.string()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body.string()
                Result.success(json.decodeFromString(VoiceProviderOptionsResponse.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("Realtime provider options failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun validateRealtimeProvider(
        providerId: String,
        model: String,
        voice: String,
        sampleRate: Int,
    ): Result<VoiceProviderValidationResponse> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }
        val provider = providerId.trim()
        if (provider.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Provider ID required"))
        }
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model.trim()))
            put("voice", JsonPrimitive(voice.trim()))
            put("sample_rate", JsonPrimitive(sampleRate))
            putProfile()
        }

        val request = Request.Builder()
            .url(urlWithProfile("$httpBase/voice/realtime/providers/${pathSegment(provider)}/validate"))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        try {
            sessionClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                Result.success(json.decodeFromString(VoiceProviderValidationResponse.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("Realtime provider validation failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateRealtimeVoiceConfig(
        enabled: Boolean? = null,
        provider: String? = null,
        model: String? = null,
        voice: String? = null,
        sampleRate: Int? = null,
    ): Result<RealtimeVoiceConfig> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }

        val payload = buildJsonObject {
            enabled?.let { put("enabled", JsonPrimitive(it)) }
            provider?.takeIf { it.isNotBlank() }?.let {
                put("provider", JsonPrimitive(it.trim()))
            }
            model?.takeIf { it.isNotBlank() }?.let {
                put("model", JsonPrimitive(it.trim()))
            }
            voice?.takeIf { it.isNotBlank() }?.let {
                put("voice", JsonPrimitive(it.trim()))
            }
            sampleRate?.let { put("sample_rate", JsonPrimitive(it)) }
        }

        val request = Request.Builder()
            .url(urlWithProfile("$httpBase/voice/realtime/config"))
            .patch(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                Result.success(json.decodeFromString(RealtimeVoiceConfig.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("Realtime voice config update failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRealtimeAgentConfig(): Result<RealtimeVoiceConfig> =
        getRealtimeConfigAt("/voice/realtime-agent/config", "Realtime agent config")

    suspend fun getRealtimeAgentProviderOptions(
        providerId: String,
    ): Result<VoiceProviderOptionsResponse> =
        getRealtimeProviderOptionsAt(
            providerId = providerId,
            pathPrefix = "/voice/realtime-agent/providers",
            label = context.getString(R.string.voice_diag_agent_provider_options),
        )

    suspend fun validateRealtimeAgentProvider(
        providerId: String,
        model: String,
        voice: String,
        sampleRate: Int,
    ): Result<VoiceProviderValidationResponse> =
        validateRealtimeProviderAt(
            providerId = providerId,
            model = model,
            voice = voice,
            sampleRate = sampleRate,
            pathPrefix = "/voice/realtime-agent/providers",
            label = context.getString(R.string.voice_diag_agent_provider_validation),
        )

    suspend fun updateRealtimeAgentConfig(
        enabled: Boolean? = null,
        provider: String? = null,
        model: String? = null,
        voice: String? = null,
        sampleRate: Int? = null,
    ): Result<RealtimeVoiceConfig> =
        updateRealtimeConfigAt(
            enabled = enabled,
            provider = provider,
            model = model,
            voice = voice,
            sampleRate = sampleRate,
            path = "/voice/realtime-agent/config",
            label = context.getString(R.string.voice_diag_agent_config_update),
        )

    /**
     * PATCH the ADR 33 background-run promotion settings. Only non-null fields
     * are sent; the relay echoes back the full [RealtimeVoiceConfig].
     */
    suspend fun updateRealtimeAgentPromotion(
        promotionEnabled: Boolean? = null,
        promoteAfterMs: Int? = null,
        spokenHandoff: Boolean? = null,
        resultDelivery: String? = null,
        backgroundDefaultMode: String? = null,
        progressSpokenAfterMs: Int? = null,
        progressRepeatMs: Int? = null,
        maxBackgroundRuns: Int? = null,
    ): Result<RealtimeVoiceConfig> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }
        val payload = buildJsonObject {
            promotionEnabled?.let { put("promotion_enabled", JsonPrimitive(it)) }
            promoteAfterMs?.let { put("promote_after_ms", JsonPrimitive(it)) }
            spokenHandoff?.let { put("spoken_handoff", JsonPrimitive(it)) }
            resultDelivery?.takeIf { it.isNotBlank() }?.let {
                put("result_delivery", JsonPrimitive(it.trim()))
            }
            backgroundDefaultMode?.takeIf { it.isNotBlank() }?.let {
                put("background_default_mode", JsonPrimitive(it.trim()))
            }
            progressSpokenAfterMs?.let { put("progress_spoken_after_ms", JsonPrimitive(it)) }
            progressRepeatMs?.let { put("progress_repeat_ms", JsonPrimitive(it)) }
            maxBackgroundRuns?.let { put("max_background_runs", JsonPrimitive(it)) }
        }
        val request = Request.Builder()
            .url(urlWithProfile("$httpBase/voice/realtime-agent/config"))
            .patch(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                Result.success(json.decodeFromString(RealtimeVoiceConfig.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("Realtime promotion update failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getVoiceOutputConfig(): Result<VoiceOutputConfig> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }

        val request = Request.Builder()
            .url(urlWithProfile("$httpBase/voice/output/config"))
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                Result.success(json.decodeFromString(VoiceOutputConfig.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("Voice output config failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getVoiceOutputProviderOptions(
        providerId: String,
    ): Result<VoiceProviderOptionsResponse> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }
        val provider = providerId.trim()
        if (provider.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Provider ID required"))
        }

        val request = Request.Builder()
            .url(urlWithProfile("$httpBase/voice/output/providers/${pathSegment(provider)}/options"))
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body.string()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body.string()
                Result.success(json.decodeFromString(VoiceProviderOptionsResponse.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("Voice output provider options failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun validateVoiceOutputProvider(
        providerId: String,
        model: String,
        voice: String,
        sampleRate: Int,
        language: String,
    ): Result<VoiceProviderValidationResponse> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }
        val provider = providerId.trim()
        if (provider.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Provider ID required"))
        }
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model.trim()))
            put("voice", JsonPrimitive(voice.trim()))
            put("sample_rate", JsonPrimitive(sampleRate))
            language.trim().takeIf { it.isNotBlank() }?.let {
                put("language", JsonPrimitive(it))
            }
            putProfile()
        }

        val request = Request.Builder()
            .url(urlWithProfile("$httpBase/voice/output/providers/${pathSegment(provider)}/validate"))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                Result.success(json.decodeFromString(VoiceProviderValidationResponse.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("Voice output provider validation failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateVoiceOutputConfig(
        enabled: Boolean? = null,
        provider: String? = null,
        model: String? = null,
        voice: String? = null,
        sampleRate: Int? = null,
        language: String? = null,
        codec: String? = null,
        optimizeStreamingLatency: Int? = null,
        textNormalization: Boolean? = null,
        autoSpeechTags: Boolean? = null,
        fallbackEnabled: Boolean? = null,
    ): Result<VoiceOutputConfig> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }

        val payload = buildJsonObject {
            enabled?.let { put("enabled", JsonPrimitive(it)) }
            provider?.takeIf { it.isNotBlank() }?.let {
                put("provider", JsonPrimitive(it.trim()))
            }
            model?.takeIf { it.isNotBlank() }?.let {
                put("model", JsonPrimitive(it.trim()))
            }
            voice?.takeIf { it.isNotBlank() }?.let {
                put("voice", JsonPrimitive(it.trim()))
            }
            sampleRate?.let { put("sample_rate", JsonPrimitive(it)) }
            language?.takeIf { it.isNotBlank() }?.let {
                put("language", JsonPrimitive(it.trim()))
            }
            codec?.takeIf { it.isNotBlank() }?.let {
                put("codec", JsonPrimitive(it.trim()))
            }
            optimizeStreamingLatency?.let {
                put("optimize_streaming_latency", JsonPrimitive(it))
            }
            textNormalization?.let { put("text_normalization", JsonPrimitive(it)) }
            autoSpeechTags?.let { put("auto_speech_tags", JsonPrimitive(it)) }
            fallbackEnabled?.let { put("fallback_enabled", JsonPrimitive(it)) }
        }

        val request = Request.Builder()
            .url(urlWithProfile("$httpBase/voice/output/config"))
            .patch(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                Result.success(json.decodeFromString(VoiceOutputConfig.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("Voice output config update failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun runVoiceOutput(
        text: String,
        renderMode: String? = "verbatim",
        onHandoff: (VoiceHandoffEvent) -> Unit = {},
        onEvent: (RealtimeVoiceEvent) -> Unit,
    ): Result<VoiceOutputSummary> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }

        val sessionResult = createVoiceOutputSession(httpBase, token)
        if (sessionResult.isFailure) {
            return@withContext Result.failure(sessionResult.exceptionOrNull() ?: IOException("Voice output session failed"))
        }
        val session = sessionResult.getOrThrow()
        val finished = CompletableDeferred<Result<VoiceOutputSummary>>()
        val completed = AtomicBoolean(false)
        val resumeAttempted = AtomicBoolean(false)
        val routeProbeRequested = AtomicBoolean(false)
        val currentSocket = AtomicReference<WebSocket?>()
        val socketGeneration = AtomicLong(0L)
        val activeSocketGeneration = AtomicLong(0L)
        val lastEventId = AtomicLong(0L)
        val lastAudioEventId = AtomicLong(0L)
        val lastPlayedAudioEventId = AtomicLong(0L)
        var audioChunks = 0
        var audioBytes = 0

        fun activateSocket(webSocket: WebSocket, generation: Long): Boolean {
            while (true) {
                val activeGeneration = activeSocketGeneration.get()
                if (generation < activeGeneration) {
                    return false
                }
                if (activeSocketGeneration.compareAndSet(activeGeneration, generation)) {
                    currentSocket.set(webSocket)
                    return true
                }
            }
        }

        fun completeFailure(message: String, throwable: Throwable? = null) {
            if (completed.compareAndSet(false, true)) {
                if (resumeAttempted.get()) {
                    onHandoff(
                        VoiceHandoffEvent(
                            label = context.getString(R.string.voice_diag_handoff_failed),
                            detail = message,
                            active = false,
                        )
                    )
                }
                finished.complete(Result.failure(IOException(message, throwable)))
            }
        }

        fun openSocket(resume: Boolean, overrideWsBase: String? = null): WebSocket {
            val generation = socketGeneration.incrementAndGet()
            val currentWsBase = overrideWsBase ?: resolveWebSocketBase()
                ?: throw IOException("Relay URL not configured")
            val request = Request.Builder()
                .url("$currentWsBase${session.websocketPath}")
                .header("Authorization", "Bearer $token")
                .build()
            Log.i(
                TAG,
                "Voice output websocket opening resume=$resume url=${request.url}",
            )
            if (resume) {
                onHandoff(
                    VoiceHandoffEvent(
                        label = context.getString(R.string.voice_diag_trying_route),
                        route = routeLabel(currentWsBase),
                        active = true,
                    )
                )
            }
            fun isStaleSocket(webSocket: WebSocket): Boolean {
                val activeSocket = currentSocket.get()
                return activeSocket != null && activeSocket !== webSocket
            }
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!activateSocket(webSocket, generation)) {
                        Log.i(TAG, "Voice output stale websocket opened; closing")
                        webSocket.close(1000, "stale route")
                        return
                    }
                    if (resume) {
                        Log.i(
                            TAG,
                            "Voice output sending session.resume session=${session.session_id} " +
                                "lastEvent=${lastEventId.get()} lastAudio=${lastAudioEventId.get()} " +
                                "lastPlayed=${lastPlayedAudioEventId.get()}",
                        )
                        onHandoff(
                            VoiceHandoffEvent(
                                label = context.getString(R.string.voice_diag_resume_sent),
                                route = routeLabel(webSocket.request().url.toString()),
                                active = true,
                            )
                        )
                        webSocket.send(
                            """{"type":"session.resume","resume_token":"${escapeJson(session.resumeToken.orEmpty())}","last_event_id":${lastEventId.get()},"last_audio_event_id":${lastAudioEventId.get()},"last_played_audio_event_id":${lastPlayedAudioEventId.get()}}"""
                        )
                    } else {
                        webSocket.send("""{"type":"session.start"}""")
                        webSocket.send(
                            buildRealtimeResponseCreate(
                                text = text,
                                toolScaffold = false,
                                renderMode = renderMode,
                            )
                        )
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (isStaleSocket(webSocket)) {
                        Log.i(TAG, "Voice output previous websocket message ignored")
                        return
                    }
                    val event = parseRealtimeEvent(text)
                    logVoiceResumeEvent("Voice output", event)
                    reportVoiceHandoffEvent("Voice output", webSocket, event, onHandoff)
                    event.eventId
                        ?.takeIf { shouldAdvanceResumeEventCursor(event) }
                        ?.let { lastEventId.updateAndGet { current -> maxOf(current, it) } }
                    event.audioEventId?.let {
                        lastAudioEventId.updateAndGet { current -> maxOf(current, it) }
                    }
                    onEvent(event)
                    if (event.isAudioDelta) {
                        event.audioEventId?.let {
                            lastPlayedAudioEventId.updateAndGet { current -> maxOf(current, it) }
                        }
                        audioChunks += 1
                        audioBytes += event.byteCount ?: 0
                    }
                    sendClientAck(
                        webSocket = webSocket,
                        eventId = event.eventId,
                        audioEventId = event.audioEventId,
                        playedAudioEventId = if (event.isAudioDelta) event.audioEventId else null,
                        inputChunkId = event.inputChunkId,
                    )
                    if (event.type == "voice.response.done") {
                        if (completed.compareAndSet(false, true)) {
                            finished.complete(
                                Result.success(
                                    VoiceOutputSummary(
                                        provider = event.provider ?: session.provider,
                                        model = event.model ?: session.model,
                                        voice = event.voice ?: session.voice,
                                        sampleRate = session.sampleRate,
                                        audioChunks = audioChunks,
                                        audioBytes = audioBytes,
                                        firstAudioMs = event.firstAudioMs,
                                        responseDoneMs = event.responseDoneMs,
                                        eventLogPath = event.eventLogPath ?: session.eventLogPath,
                                    )
                                )
                            )
                        }
                        webSocket.close(1000, "done")
                    } else if (event.type == "voice.error" || event.type == "voice.session.resume_failed") {
                        completeFailure(event.message ?: "Voice output error")
                        webSocket.close(1011, "provider error")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (completed.get()) return
                    val activeSocket = currentSocket.get()
                    if (resumeAttempted.get() && activeSocket != null && activeSocket !== webSocket) {
                        Log.i(TAG, "Voice output previous websocket failed during resume; ignoring: ${t.message}")
                        return
                    }
                    if (session.resumeSupported && !session.resumeToken.isNullOrBlank() && resumeAttempted.get()) {
                        Log.i(TAG, "Voice output resume websocket failed; waiting for route change: ${t.message}")
                        requestRouteProbeOnce("Voice output", t.message, routeProbeRequested)
                        onHandoff(
                            VoiceHandoffEvent(
                                label = context.getString(R.string.voice_diag_waiting_for_route),
                                detail = t.message,
                                route = routeLabel(webSocket.request().url.toString()),
                                active = true,
                            )
                        )
                        return
                    }
                    if (session.resumeSupported && !session.resumeToken.isNullOrBlank() && resumeAttempted.compareAndSet(false, true)) {
                        try {
                            Log.i(TAG, "Voice output websocket failed; attempting resume: ${t.message}")
                            requestRouteProbeOnce("Voice output", t.message, routeProbeRequested)
                            onHandoff(
                                VoiceHandoffEvent(
                                    label = context.getString(R.string.voice_diag_connection_changed),
                                    detail = t.message,
                                    route = routeLabel(webSocket.request().url.toString()),
                                    active = true,
                                )
                            )
                            openSocket(resume = true)
                            return
                        } catch (e: Exception) {
                            completeFailure("Voice output resume failed: ${e.message ?: "network error"}", e)
                            return
                        }
                    }
                    completeFailure("Voice output websocket failed: ${t.message}", t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (completed.get()) return
                    val activeSocket = currentSocket.get()
                    if (resumeAttempted.get() && activeSocket != null && activeSocket !== webSocket) {
                        Log.i(TAG, "Voice output previous websocket closed during resume; ignoring: $code $reason")
                        return
                    }
                    if (code != 1000 && session.resumeSupported && !session.resumeToken.isNullOrBlank() && resumeAttempted.get()) {
                        Log.i(TAG, "Voice output resume websocket closed; waiting for route change: $code $reason")
                        requestRouteProbeOnce("Voice output", "Closed $code $reason", routeProbeRequested)
                        onHandoff(
                            VoiceHandoffEvent(
                                label = context.getString(R.string.voice_diag_waiting_for_route),
                                detail = "Closed $code $reason",
                                route = routeLabel(webSocket.request().url.toString()),
                                active = true,
                            )
                        )
                        return
                    }
                    if (code == 1000) {
                        completeFailure("Voice output websocket closed before completion: $code $reason")
                        return
                    }
                    if (session.resumeSupported && !session.resumeToken.isNullOrBlank() && resumeAttempted.compareAndSet(false, true)) {
                        try {
                            Log.i(TAG, "Voice output websocket closed code=$code; attempting resume")
                            requestRouteProbeOnce("Voice output", "Closed $code $reason", routeProbeRequested)
                            onHandoff(
                                VoiceHandoffEvent(
                                    label = context.getString(R.string.voice_diag_connection_changed),
                                    detail = "Closed $code $reason",
                                    route = routeLabel(webSocket.request().url.toString()),
                                    active = true,
                                )
                            )
                            openSocket(resume = true)
                            return
                        } catch (e: Exception) {
                            completeFailure("Voice output resume failed: ${e.message ?: "network error"}", e)
                            return
                        }
                    }
                    completeFailure("Voice output websocket closed before completion: $code $reason")
                }
            }
            val webSocket = openWebSocket(request, listener)
            activateSocket(webSocket, generation)
            return webSocket
        }

        val socket = openSocket(resume = false)
        val routeWatcher = startRouteResumeWatcher(
            surface = "Voice output",
            completed = completed,
            resumeAttempted = resumeAttempted,
            resumeSupported = session.resumeSupported,
            resumeToken = session.resumeToken,
            currentSocket = currentSocket,
            openResumeSocket = { route -> openSocket(resume = true, overrideWsBase = route) },
            onHandoff = onHandoff,
            completeFailure = ::completeFailure,
        )
        try {
            withTimeout(REALTIME_TIMEOUT_MS) {
                finished.await()
            }
        } catch (e: Exception) {
            currentSocket.get()?.close(1001, "timeout")
            socket.close(1001, "timeout")
            Result.failure(IOException("Voice output timed out", e))
        } finally {
            routeWatcher?.cancel()
        }
    }

    suspend fun runRealtimeDemo(
        prompt: String,
        inputPcm: ByteArray,
        inputSampleRate: Int = 16_000,
        toolScaffold: Boolean = true,
        renderMode: String? = null,
        onEvent: (RealtimeVoiceEvent) -> Unit,
    ): Result<RealtimeVoiceSummary> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val wsBase = resolveWebSocketBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }

        val sessionResult = createRealtimeSession(httpBase, token)
        if (sessionResult.isFailure) {
            return@withContext Result.failure(sessionResult.exceptionOrNull() ?: IOException("Session failed"))
        }
        val session = sessionResult.getOrThrow()
        val finished = CompletableDeferred<Result<RealtimeVoiceSummary>>()
        val completed = AtomicBoolean(false)
        var audioChunks = 0
        var audioBytes = 0

        val request = Request.Builder()
            .url("$wsBase${session.websocketPath}")
            .header("Authorization", "Bearer $token")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"session.start"}""")
                if (inputPcm.isNotEmpty()) {
                    var offset = 0
                    while (offset < inputPcm.size) {
                        val end = (offset + REALTIME_INPUT_CHUNK_BYTES).coerceAtMost(inputPcm.size)
                        val encoded = Base64.getEncoder()
                            .encodeToString(inputPcm.copyOfRange(offset, end))
                        webSocket.send(
                            """{"type":"input_audio.append","sample_rate":$inputSampleRate,"audio_base64":"$encoded"}"""
                        )
                        offset = end
                    }
                }
                webSocket.send(
                    buildRealtimeResponseCreate(
                        text = prompt,
                        toolScaffold = toolScaffold,
                        renderMode = renderMode,
                    )
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = parseRealtimeEvent(text)
                onEvent(event)
                if (event.isAudioDelta) {
                    audioChunks += 1
                    audioBytes += event.byteCount ?: 0
                }
                if (event.type == "voice.response.done") {
                    if (completed.compareAndSet(false, true)) {
                        finished.complete(
                            Result.success(
                                RealtimeVoiceSummary(
                                    provider = event.provider ?: session.provider,
                                    model = event.model ?: session.model,
                                    voice = event.voice ?: session.voice,
                                    sampleRate = session.sampleRate,
                                    audioChunks = audioChunks,
                                    audioBytes = audioBytes,
                                    firstAudioMs = event.firstAudioMs,
                                    responseDoneMs = event.responseDoneMs,
                                    eventLogPath = event.eventLogPath ?: session.eventLogPath,
                                )
                            )
                        )
                    }
                    webSocket.close(1000, "done")
                } else if (event.type == "voice.error") {
                    if (completed.compareAndSet(false, true)) {
                        finished.complete(
                            Result.failure(IOException(event.message ?: "Realtime voice error"))
                        )
                    }
                    webSocket.close(1011, "provider error")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (completed.compareAndSet(false, true)) {
                    finished.complete(Result.failure(IOException("Realtime voice websocket failed: ${t.message}", t)))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (completed.compareAndSet(false, true)) {
                    finished.complete(Result.failure(IOException("Realtime voice websocket closed before completion: $code $reason")))
                }
            }
        }

        val socket = openWebSocket(request, listener)
        try {
            withTimeout(REALTIME_TIMEOUT_MS) {
                finished.await()
            }
        } catch (e: Exception) {
            socket.close(1001, "timeout")
            Result.failure(IOException("Realtime voice demo timed out", e))
        }
    }

    /**
     * Run a Realtime Agent voice session.
     *
     * **One-shot (default):** with [turnInputs] null, this opens a session, sends
     * the single [inputPcm]/[prompt] turn, and completes + closes the socket on
     * `voice.response.done` — the historical per-utterance behavior used by the
     * Voice Lab and the one-shot fallback.
     *
     * **Persistent (ADR follow-up, see docs/plans/2026-05-24-realtime-persistent-session.md):**
     * with [turnInputs] non-null the socket stays open across turns. The first
     * turn is [inputPcm]/[prompt]; each subsequent [RealtimeTurnInput] read from
     * the channel is sent on the *same* socket, so the provider keeps a live
     * conversation. `voice.response.done` invokes [onTurnComplete] instead of
     * closing; the call returns only when the channel closes (voice-mode exit) or
     * a fatal socket/provider error occurs.
     */
    suspend fun runRealtimeAgent(
        prompt: String,
        inputPcm: ByteArray,
        inputSampleRate: Int = 16_000,
        chatSessionId: String? = null,
        conversationContext: List<RealtimeConversationContextMessage> = emptyList(),
        model: String? = null,
        voice: String? = null,
        onHandoff: (VoiceHandoffEvent) -> Unit = {},
        turnInputs: kotlinx.coroutines.channels.ReceiveChannel<RealtimeTurnInput>? = null,
        onTurnComplete: (RealtimeVoiceSummary) -> Unit = {},
        /**
         * Open the session + socket without a first turn (voice-mode entry
         * warm-up). No input is sent and no response is requested; the turn
         * guards stay disarmed until the first real utterance arrives on
         * [turnInputs]. Persistent mode only.
         */
        prewarm: Boolean = false,
        onEvent: (RealtimeVoiceEvent, RealtimeAgentSessionControl) -> Unit,
    ): Result<RealtimeVoiceSummary> = withContext(Dispatchers.IO) {
        val persistent = turnInputs != null
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val wsBase = resolveWebSocketBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }

        val sessionResult = createRealtimeAgentSession(
            httpBase = httpBase,
            token = token,
            chatSessionId = chatSessionId,
            conversationContext = conversationContext,
            model = model,
            voice = voice,
        )
        if (sessionResult.isFailure) {
            return@withContext Result.failure(sessionResult.exceptionOrNull() ?: IOException("Realtime agent session failed"))
        }
        val session = sessionResult.getOrThrow()
        val finished = CompletableDeferred<Result<RealtimeVoiceSummary>>()
        val completed = AtomicBoolean(false)
        val resumeAttempted = AtomicBoolean(false)
        val routeProbeRequested = AtomicBoolean(false)
        val currentSocket = AtomicReference<WebSocket?>()
        val readySocket = AtomicReference<WebSocket?>()
        val resumeStateLock = Any()
        val socketGeneration = AtomicLong(0L)
        val activeSocketGeneration = AtomicLong(0L)
        val openingSocketGeneration = AtomicLong(0L)
        val resumeEpisodeGeneration = AtomicLong(0L)
        val handoffTransitionRevision = AtomicLong(0L)
        val resumeEpisodeTransitionRevision = AtomicLong(0L)
        val lastEventId = AtomicLong(0L)
        val lastAudioEventId = AtomicLong(0L)
        val lastPlayedAudioEventId = AtomicLong(0L)
        val lastInputChunkId = AtomicLong(0L)
        val turnStartedAtMs = AtomicLong(System.currentTimeMillis())
        val lastEventAtMs = AtomicLong(turnStartedAtMs.get())
        // True while a turn is awaiting its response. In persistent mode the idle
        // guard only applies while a turn is active; between-turn idle is normal.
        // A prewarm open has no turn in flight, so its guards stay disarmed
        // until the first utterance arrives on the turn channel.
        val activeTurn = AtomicBoolean(!prewarm)
        // W3: set true once a turn is known to be a long/background Hermes run
        // (e.g. `hermes.run.promoted`). The relay can legitimately go quiet for
        // minutes while such a run executes, so the 90s idle guard would kill an
        // otherwise-healthy turn. When set, the idle check is paused the same way
        // persistent between-turn idle is — REALTIME_AGENT_MAX_TURN_MS remains
        // the absolute backstop. Reset at every turn boundary.
        val longRunningTurn = AtomicBoolean(false)
        // True from the first resume attempt until the relay confirms
        // voice.session.resumed. A bare WebSocket onOpen is not sufficient.
        val resumeWaiting = AtomicBoolean(false)
        // The retry budget belongs to a route-loss episode, not to the age of
        // the persistent voice session. Zero means the route is currently
        // healthy. Repeated failures within one episode must not extend it.
        val resumeRetryDeadlineMs = AtomicLong(0L)
        fun markResumeWaiting(expectedEpisode: Long? = null): RealtimeResumeWaitingState? =
            synchronized(resumeStateLock) {
                if (completed.get()) return@synchronized null
                if (expectedEpisode != null &&
                    (!resumeWaiting.get() || resumeEpisodeGeneration.get() != expectedEpisode)
                ) {
                    return@synchronized null
                }
                if (resumeWaiting.compareAndSet(false, true)) {
                    resumeEpisodeGeneration.incrementAndGet()
                    resumeEpisodeTransitionRevision.set(
                        handoffTransitionRevision.incrementAndGet(),
                    )
                    resumeRetryDeadlineMs.set(
                        System.currentTimeMillis() + realtimeResumeRetryWindowMs,
                    )
                }
                readySocket.set(null)
                RealtimeResumeWaitingState(
                    episode = resumeEpisodeGeneration.get(),
                    transitionRevision = resumeEpisodeTransitionRevision.get(),
                )
            }
        fun publishReadySocket(webSocket: WebSocket, generation: Long): Long? =
            synchronized(resumeStateLock) {
                if (completed.get() ||
                    currentSocket.get() !== webSocket ||
                    activeSocketGeneration.get() != generation
                ) {
                    return@synchronized null
                }
                resumeWaiting.set(false)
                resumeRetryDeadlineMs.set(0L)
                readySocket.set(webSocket)
                handoffTransitionRevision.incrementAndGet()
            }
        fun currentReadySocket(): WebSocket? = synchronized(resumeStateLock) {
            readySocket.get()?.takeIf {
                it === currentSocket.get() && !resumeWaiting.get()
            }
        }
        fun socketIsReady(webSocket: WebSocket): Boolean = synchronized(resumeStateLock) {
            currentSocket.get() === webSocket &&
                readySocket.get() === webSocket &&
                !resumeWaiting.get()
        }
        fun socketCallbackMatchesLocked(
            webSocket: WebSocket,
            generation: Long,
            wasOpening: Boolean,
            openingResumeEpisode: Long?,
        ): Boolean =
            (currentSocket.get() === webSocket && activeSocketGeneration.get() == generation) ||
                (wasOpening &&
                    generation > activeSocketGeneration.get() &&
                    (openingResumeEpisode == null ||
                        (resumeWaiting.get() &&
                            resumeEpisodeGeneration.get() == openingResumeEpisode)))
        fun claimResumeLoss(
            webSocket: WebSocket,
            generation: Long,
            wasOpening: Boolean,
            openingResumeEpisode: Long?,
        ): RealtimeResumeLossClaim? = synchronized(resumeStateLock) {
            if (completed.get() ||
                !socketCallbackMatchesLocked(
                    webSocket,
                    generation,
                    wasOpening,
                    openingResumeEpisode,
                )
            ) {
                return@synchronized null
            }
            if (currentSocket.get() === webSocket) {
                readySocket.compareAndSet(webSocket, null)
            }
            val startsEpisode = !resumeWaiting.get()
            val startsFirstResume = resumeAttempted.compareAndSet(false, true)
            val waiting = markResumeWaiting() ?: return@synchronized null
            RealtimeResumeLossClaim(
                // A later established route should recover immediately too.
                // The wasOpening guard only prevents recursive reopen from a
                // synchronous test/factory callback before onOpen unwinds.
                openImmediately = startsFirstResume || (startsEpisode && !wasOpening),
                waiting = waiting,
            )
        }
        fun claimTerminalSocket(
            webSocket: WebSocket,
            generation: Long,
            wasOpening: Boolean,
            openingResumeEpisode: Long?,
        ): Long? = synchronized(resumeStateLock) {
            if (completed.get() ||
                !socketCallbackMatchesLocked(
                    webSocket,
                    generation,
                    wasOpening,
                    openingResumeEpisode,
                ) ||
                !completed.compareAndSet(false, true)
            ) {
                return@synchronized null
            }
            readySocket.compareAndSet(webSocket, null)
            handoffTransitionRevision.incrementAndGet()
        }
        fun currentTransitionRevision(webSocket: WebSocket, generation: Long): Long? =
            synchronized(resumeStateLock) {
                handoffTransitionRevision.get().takeIf {
                    !completed.get() &&
                        currentSocket.get() === webSocket &&
                        activeSocketGeneration.get() == generation
                }
            }
        fun resumeEpisodeSnapshot(): Long? = synchronized(resumeStateLock) {
            resumeEpisodeGeneration.get().takeIf { resumeWaiting.get() }
        }
        val inputChunks = mutableListOf<BufferedRealtimeInputChunk>().apply {
            var offset = 0
            var chunkId = 1L
            while (offset < inputPcm.size) {
                val end = (offset + REALTIME_INPUT_CHUNK_BYTES).coerceAtMost(inputPcm.size)
                add(
                    BufferedRealtimeInputChunk(
                        id = chunkId,
                        pcm = inputPcm.copyOfRange(offset, end),
                        sampleRate = inputSampleRate,
                    )
                )
                chunkId += 1
                offset = end
            }
        }
        val inputChunksLock = Any()
        // Serializes buffer replacement and socket setup/replay. A reconnect
        // must never snapshot a follow-up utterance while it is only partially
        // buffered.
        val inputSendLock = Any()
        val inputCommitPending = AtomicBoolean(inputChunks.isNotEmpty())
        // Highest input chunk id sent on this session. The relay dedups by
        // input_chunk_seq, so subsequent turns must continue past this.
        val sessionMaxChunkId = AtomicLong(inputChunks.size.toLong())
        var audioChunks = 0
        var audioBytes = 0

        // Persistent-mode: chunk + send one more utterance on the open socket.
        fun sendTurnPcm(
            pcm: ByteArray,
            sampleRate: Int,
        ): BufferedRealtimeTurnSendResult {
            val turnChunks = mutableListOf<BufferedRealtimeInputChunk>()
            var offset = 0
            while (offset < pcm.size) {
                val end = (offset + REALTIME_INPUT_CHUNK_BYTES).coerceAtMost(pcm.size)
                val chunkId = sessionMaxChunkId.incrementAndGet()
                turnChunks.add(
                    BufferedRealtimeInputChunk(
                        id = chunkId,
                        pcm = pcm.copyOfRange(offset, end),
                        sampleRate = sampleRate,
                    )
                )
                offset = end
            }

            return synchronized(inputSendLock) {
                // The previous turn is already far enough along for the UI to
                // accept another utterance. Install the complete new buffer in
                // one critical section so any resume sees all or none of it.
                synchronized(inputChunksLock) {
                    inputChunks.clear()
                    inputChunks.addAll(turnChunks)
                }
                inputCommitPending.set(turnChunks.isNotEmpty())

                // A replacement socket may have become ready after the turn
                // reader captured [webSocket]. Prefer the current ready route.
                val targetSocket = currentReadySocket()
                if (targetSocket == null) {
                    return@synchronized BufferedRealtimeTurnSendResult(
                        accepted = false,
                        routeChanged = true,
                    )
                }

                var accepted = true
                for (chunk in turnChunks) {
                    val encoded = Base64.getEncoder().encodeToString(chunk.pcm)
                    if (!targetSocket.send(
                            """{"type":"input_audio.append","chunk_id":${chunk.id},"sample_rate":${chunk.sampleRate},"audio_base64":"$encoded"}"""
                        )
                    ) {
                        accepted = false
                        break
                    }
                }
                if (accepted && turnChunks.isNotEmpty()) {
                    accepted = targetSocket.send("""{"type":"input_audio.commit"}""")
                }
                if (accepted) {
                    turnStartedAtMs.set(System.currentTimeMillis())
                    lastEventAtMs.set(System.currentTimeMillis())
                    activeTurn.set(true)
                    longRunningTurn.set(false)
                }
                BufferedRealtimeTurnSendResult(
                    accepted = accepted,
                    routeChanged = !socketIsReady(targetSocket),
                )
            }
        }
        fun activateSocket(webSocket: WebSocket, generation: Long): Boolean {
            synchronized(resumeStateLock) {
                val activeGeneration = activeSocketGeneration.get()
                if (generation < activeGeneration) {
                    return false
                }
                if (generation > activeGeneration || currentSocket.get() !== webSocket) {
                    activeSocketGeneration.set(generation)
                    currentSocket.set(webSocket)
                    readySocket.set(null)
                }
                return true
            }
        }

        fun sendInputChunks(webSocket: WebSocket, afterChunkId: Long): Boolean {
            val pending = synchronized(inputChunksLock) {
                inputChunks.filter { it.id > afterChunkId }
            }
            for (chunk in pending) {
                val encoded = Base64.getEncoder().encodeToString(chunk.pcm)
                if (!webSocket.send(
                        """{"type":"input_audio.append","chunk_id":${chunk.id},"sample_rate":${chunk.sampleRate},"audio_base64":"$encoded"}"""
                    )
                ) {
                    return false
                }
            }
            if ((pending.isNotEmpty() || inputCommitPending.get()) &&
                !webSocket.send("""{"type":"input_audio.commit"}""")
            ) {
                return false
            }
            return true
        }

        fun reportClaimedFailure(
            message: String,
            throwable: Throwable? = null,
            transitionRevision: Long? = null,
        ) {
            if (resumeAttempted.get()) {
                onHandoff(
                    VoiceHandoffEvent(
                        label = "Voice handoff failed",
                        detail = message,
                        active = false,
                        transitionRevision = transitionRevision,
                    )
                )
            }
            finished.complete(Result.failure(IOException(message, throwable)))
        }

        fun completeFailure(message: String, throwable: Throwable? = null) {
            val transitionRevision = synchronized(resumeStateLock) {
                if (completed.compareAndSet(false, true)) {
                    handoffTransitionRevision.incrementAndGet()
                } else {
                    null
                }
            }
            if (transitionRevision != null) {
                reportClaimedFailure(message, throwable, transitionRevision)
            }
        }

        fun openSocket(
            resume: Boolean,
            overrideWsBase: String? = null,
            expectedResumeEpisode: Long? = null,
        ): WebSocket? {
            val generation = socketGeneration.incrementAndGet()
            if (!openingSocketGeneration.compareAndSet(0L, generation)) {
                Log.i(TAG, "Realtime agent websocket open coalesced; handshake already pending")
                return null
            }
            val resumeState = if (resume) {
                // A WebSocket returned by OkHttp is only an opening attempt.
                // Do not let persistent turns use it until the relay has
                // confirmed session.resume.
                markResumeWaiting(expectedResumeEpisode) ?: run {
                    openingSocketGeneration.compareAndSet(generation, 0L)
                    return null
                }
            } else {
                null
            }
            val (currentWsBase, request) = try {
                val base = overrideWsBase ?: resolveWebSocketBase()
                    ?: throw IOException("Relay URL not configured")
                val socketRequest = Request.Builder()
                    .url("$base${session.websocketPath}")
                    .header("Authorization", "Bearer $token")
                    .build()
                Log.i(
                    TAG,
                    "Realtime agent websocket opening resume=$resume url=${socketRequest.url}",
                )
                if (resume) {
                    onHandoff(
                        VoiceHandoffEvent(
                            label = "Trying voice route",
                            route = routeLabel(base),
                            active = true,
                            transitionRevision = resumeState?.transitionRevision,
                        )
                    )
                }
                base to socketRequest
            } catch (e: Exception) {
                openingSocketGeneration.compareAndSet(generation, 0L)
                throw e
            }
            val terminalCallbackSeen = AtomicBoolean(false)
            fun activateSocketIfLive(webSocket: WebSocket): Boolean =
                synchronized(resumeStateLock) {
                    if (terminalCallbackSeen.get()) {
                        false
                    } else {
                        activateSocket(webSocket, generation)
                    }
                }
            fun isStaleSocket(webSocket: WebSocket): Boolean {
                return synchronized(resumeStateLock) {
                    currentSocket.get() !== webSocket ||
                        activeSocketGeneration.get() != generation
                }
            }
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    try {
                        synchronized(inputSendLock) {
                            if (!activateSocketIfLive(webSocket)) {
                                Log.i(TAG, "Realtime agent stale websocket opened; closing")
                                if (!terminalCallbackSeen.get()) {
                                    webSocket.close(1000, "stale route")
                                }
                                return
                            }
                            val ready = if (resume) {
                                val resumeToken = session.resumeToken.orEmpty()
                                Log.i(
                                    TAG,
                                    "Realtime agent sending session.resume session=${session.session_id} " +
                                        "lastEvent=${lastEventId.get()} lastAudio=${lastAudioEventId.get()} " +
                                        "lastPlayed=${lastPlayedAudioEventId.get()} " +
                                        "lastInput=${lastInputChunkId.get()}",
                                )
                                onHandoff(
                                    VoiceHandoffEvent(
                                        label = "Resume sent",
                                        route = routeLabel(webSocket.request().url.toString()),
                                        active = true,
                                        transitionRevision = resumeState?.transitionRevision,
                                    )
                                )
                                val resumeSent = webSocket.send(
                                    """{"type":"session.resume","resume_token":"${escapeJson(resumeToken)}","last_event_id":${lastEventId.get()},"last_audio_event_id":${lastAudioEventId.get()},"last_played_audio_event_id":${lastPlayedAudioEventId.get()},"last_input_chunk_id":${lastInputChunkId.get()}}"""
                                )
                                resumeSent && sendInputChunks(webSocket, lastInputChunkId.get())
                            } else {
                                val started = webSocket.send("""{"type":"session.start"}""")
                                if (!started) {
                                    false
                                } else if (synchronized(inputChunksLock) { inputChunks.isNotEmpty() }) {
                                    sendInputChunks(webSocket, afterChunkId = 0L)
                                } else if (prompt.isNotBlank()) {
                                    webSocket.send(
                                        buildRealtimeResponseCreate(
                                            text = prompt,
                                            toolScaffold = false,
                                            renderMode = "verbatim",
                                        )
                                    )
                                } else {
                                    true
                                }
                            }
                            if (!ready) {
                                // A synchronous failure callback can replace this
                                // socket while setup sends are still unwinding. A
                                // stale attempt must not terminate the newer route.
                                if (currentSocket.get() !== webSocket || completed.get()) return
                                completeFailure("Realtime agent websocket rejected session setup")
                                webSocket.close(1011, "session setup failed")
                                return
                            }
                            if (currentSocket.get() !== webSocket || completed.get()) return
                            if (resume) {
                                Log.i(TAG, "Realtime agent resume sent; awaiting relay confirmation")
                            } else {
                                publishReadySocket(webSocket, generation)
                            }
                        }
                    } finally {
                        openingSocketGeneration.compareAndSet(generation, 0L)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (isStaleSocket(webSocket)) {
                        Log.i(TAG, "Realtime agent previous websocket message ignored")
                        return
                    }
                    lastEventAtMs.set(System.currentTimeMillis())
                    val event = parseRealtimeEvent(text)
                    val transitionRevision = if (resume && event.type == "voice.session.resumed") {
                        publishReadySocket(webSocket, generation) ?: run {
                            Log.i(TAG, "Realtime agent superseded resume confirmation ignored")
                            return
                        }
                    } else {
                        currentTransitionRevision(webSocket, generation) ?: run {
                            Log.i(TAG, "Realtime agent superseded websocket event ignored")
                            return
                        }
                    }
                    if (resume && event.type == "voice.session.resumed") {
                        Log.i(TAG, "Realtime agent relay-confirmed resume is ready")
                    }
                    logVoiceResumeEvent("Realtime agent", event)
                    reportVoiceHandoffEvent(
                        "Realtime agent",
                        webSocket,
                        event,
                        onHandoff,
                        transitionRevision = transitionRevision,
                    )
                    event.eventId
                        ?.takeIf { shouldAdvanceResumeEventCursor(event) }
                        ?.let { lastEventId.updateAndGet { current -> maxOf(current, it) } }
                    event.audioEventId?.let {
                        lastAudioEventId.updateAndGet { current -> maxOf(current, it) }
                    }
                    event.inputChunkId?.let { acknowledgedChunkId ->
                        lastInputChunkId.updateAndGet { current -> maxOf(current, acknowledgedChunkId) }
                        synchronized(inputChunksLock) {
                            inputChunks.removeAll { it.id <= acknowledgedChunkId }
                        }
                    }
                    if (event.type.startsWith("voice.input_transcript") && !event.replayed) {
                        // A provider transcript proves input_audio.commit was
                        // processed. Response events are intentionally excluded:
                        // a replayed background summary can overlap a newer turn.
                        inputCommitPending.set(false)
                    }
                    val control = RealtimeAgentSessionControl(webSocket) { playedAudioEventId ->
                        lastPlayedAudioEventId.updateAndGet { current -> maxOf(current, playedAudioEventId) }
                    }
                    onEvent(event, control)
                    // W3: a promoted (background) Hermes run can legitimately
                    // leave the socket quiet for minutes. Flag the turn so the
                    // idle guard relaxes; MAX_TURN_MS still bounds it.
                    if (event.type == "hermes.run.promoted") {
                        longRunningTurn.set(true)
                        Log.i(TAG, "Realtime agent turn marked long-running (run promoted); relaxing idle guard")
                    }
                    if (event.isAudioDelta) {
                        audioChunks += 1
                        val byteCount = event.byteCount ?: 0
                        audioBytes += byteCount
                    }
                    sendClientAck(
                        webSocket = webSocket,
                        eventId = event.eventId,
                        audioEventId = event.audioEventId,
                        playedAudioEventId = null,
                        inputChunkId = event.inputChunkId,
                    )
                    if (event.type == "voice.response.done") {
                        val summary = RealtimeVoiceSummary(
                            provider = event.provider ?: session.provider,
                            model = event.model ?: session.model,
                            voice = event.voice ?: session.voice,
                            sampleRate = session.sampleRate,
                            audioChunks = audioChunks,
                            audioBytes = audioBytes,
                            firstAudioMs = event.firstAudioMs,
                            responseDoneMs = event.responseDoneMs,
                            eventLogPath = event.eventLogPath ?: session.eventLogPath,
                        )
                        if (persistent) {
                            // Turn boundary, not session boundary: keep the socket
                            // open for the next utterance.
                            activeTurn.set(false)
                            longRunningTurn.set(false)
                            onTurnComplete(summary)
                        } else {
                            if (claimTerminalSocket(
                                    webSocket,
                                    generation,
                                    wasOpening = false,
                                    openingResumeEpisode = null,
                                ) != null
                            ) {
                                finished.complete(Result.success(summary))
                            }
                            webSocket.close(1000, "done")
                        }
                    } else if (event.type == "voice.session.resume_failed") {
                        claimTerminalSocket(
                            webSocket,
                            generation,
                            wasOpening = false,
                            openingResumeEpisode = null,
                        )?.let { terminalRevision ->
                            reportClaimedFailure(
                                event.message ?: "Realtime agent error",
                                transitionRevision = terminalRevision,
                            )
                        }
                        webSocket.close(1011, "provider error")
                    } else if (event.type == "voice.error") {
                        val msg = event.message ?: "Realtime agent error"
                        if (isTransientRealtimeProviderError(msg)) {
                            // A benign provider notice (e.g. a cancel with no
                            // active response) must not tear down a live turn —
                            // the reply is often still on its way. The relay now
                            // filters these too; this is defense in depth.
                            Log.i(TAG, "Realtime agent transient provider notice ignored: $msg")
                        } else {
                            claimTerminalSocket(
                                webSocket,
                                generation,
                                wasOpening = false,
                                openingResumeEpisode = null,
                            )?.let { terminalRevision ->
                                reportClaimedFailure(
                                    msg,
                                    transitionRevision = terminalRevision,
                                )
                            }
                            webSocket.close(1011, "provider error")
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    terminalCallbackSeen.set(true)
                    val wasOpening = openingSocketGeneration.compareAndSet(generation, 0L)
                    if (completed.get()) return
                    val detail = t.message
                    if (session.resumeSupported && !session.resumeToken.isNullOrBlank()) {
                        val claim = claimResumeLoss(
                            webSocket,
                            generation,
                            wasOpening,
                            resumeState?.episode,
                        )
                        if (claim == null) {
                            Log.i(
                                TAG,
                                "Realtime agent previous websocket failed; ignoring: $detail",
                            )
                            return
                        }
                        requestRouteProbeOnce("Realtime agent", detail, routeProbeRequested)
                        val label = if (claim.openImmediately) {
                            "Connection changed"
                        } else {
                            "Waiting for route"
                        }
                        Log.i(
                            TAG,
                            if (claim.openImmediately) {
                                "Realtime agent websocket failed; attempting resume: $detail"
                            } else {
                                "Realtime agent resume websocket failed; will retry: $detail"
                            },
                        )
                        onHandoff(
                            VoiceHandoffEvent(
                                label = label,
                                detail = detail,
                                route = routeLabel(webSocket.request().url.toString()),
                                active = true,
                                transitionRevision = claim.waiting.transitionRevision,
                            )
                        )
                        if (!claim.openImmediately) return
                        try {
                            openSocket(
                                resume = true,
                                expectedResumeEpisode = claim.waiting.episode,
                            )
                            return
                        } catch (e: Exception) {
                            completeFailure("Realtime agent resume failed: ${e.message ?: "network error"}", e)
                            return
                        }
                    }
                    val transitionRevision = claimTerminalSocket(
                        webSocket,
                        generation,
                        wasOpening,
                        resumeState?.episode,
                    )
                    if (transitionRevision == null) {
                        Log.i(TAG, "Realtime agent previous websocket failed; ignoring: $detail")
                        return
                    }
                    reportClaimedFailure(
                        "Realtime agent websocket failed: $detail",
                        t,
                        transitionRevision,
                    )
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    terminalCallbackSeen.set(true)
                    val wasOpening = openingSocketGeneration.compareAndSet(generation, 0L)
                    if (completed.get()) return
                    val detail = "Closed $code $reason"
                    if (code == 1000) {
                        val transitionRevision = claimTerminalSocket(
                            webSocket,
                            generation,
                            wasOpening,
                            resumeState?.episode,
                        )
                        if (transitionRevision == null) {
                            Log.i(
                                TAG,
                                "Realtime agent previous websocket closed; ignoring: $code $reason",
                            )
                            return
                        }
                        if (persistent && !activeTurn.get()) {
                            Log.i(
                                TAG,
                                "Realtime agent provider session closed while idle; next turn will reopen: $reason",
                            )
                            finished.complete(
                                Result.success(
                                    RealtimeVoiceSummary(
                                        provider = session.provider,
                                        model = session.model,
                                        voice = session.voice,
                                        sampleRate = session.sampleRate,
                                        audioChunks = audioChunks,
                                        audioBytes = audioBytes,
                                        firstAudioMs = null,
                                        responseDoneMs = null,
                                        eventLogPath = session.eventLogPath,
                                    )
                                )
                            )
                        } else {
                            reportClaimedFailure(
                                "Realtime agent websocket closed before completion: $code $reason",
                                transitionRevision = transitionRevision,
                            )
                        }
                        return
                    }
                    if (session.resumeSupported && !session.resumeToken.isNullOrBlank()) {
                        val claim = claimResumeLoss(
                            webSocket,
                            generation,
                            wasOpening,
                            resumeState?.episode,
                        )
                        if (claim == null) {
                            Log.i(
                                TAG,
                                "Realtime agent previous websocket closed; ignoring: $code $reason",
                            )
                            return
                        }
                        requestRouteProbeOnce("Realtime agent", detail, routeProbeRequested)
                        val label = if (claim.openImmediately) {
                            "Connection changed"
                        } else {
                            "Waiting for route"
                        }
                        Log.i(
                            TAG,
                            if (claim.openImmediately) {
                                "Realtime agent websocket closed code=$code; attempting resume"
                            } else {
                                "Realtime agent resume websocket closed; will retry: $code $reason"
                            },
                        )
                        onHandoff(
                            VoiceHandoffEvent(
                                label = label,
                                detail = detail,
                                route = routeLabel(webSocket.request().url.toString()),
                                active = true,
                                transitionRevision = claim.waiting.transitionRevision,
                            )
                        )
                        if (!claim.openImmediately) return
                        try {
                            openSocket(
                                resume = true,
                                expectedResumeEpisode = claim.waiting.episode,
                            )
                            return
                        } catch (e: Exception) {
                            completeFailure("Realtime agent resume failed: ${e.message ?: "network error"}", e)
                            return
                        }
                    }
                    val transitionRevision = claimTerminalSocket(
                        webSocket,
                        generation,
                        wasOpening,
                        resumeState?.episode,
                    )
                    if (transitionRevision == null) {
                        Log.i(
                            TAG,
                            "Realtime agent previous websocket closed; ignoring: $code $reason",
                        )
                        return
                    }
                    reportClaimedFailure(
                        "Realtime agent websocket closed before completion: $code $reason",
                        transitionRevision = transitionRevision,
                    )
                }
            }
            val webSocket = try {
                openWebSocket(request, listener)
            } catch (e: Exception) {
                openingSocketGeneration.compareAndSet(generation, 0L)
                throw e
            }
            return webSocket.takeIf { activateSocketIfLive(it) }
        }

        suspend fun awaitReadyTurnSocket(): WebSocket {
            currentReadySocket()?.let { return it }
            val deadline = System.currentTimeMillis() + REALTIME_TURN_SOCKET_READY_TIMEOUT_MS
            var nextResumeAttemptAtMs = 0L
            while (!completed.get() && System.currentTimeMillis() < deadline) {
                currentReadySocket()?.let { return it }
                val now = System.currentTimeMillis()
                val resumeEpisode = resumeEpisodeSnapshot()
                if (resumeEpisode != null &&
                    openingSocketGeneration.get() == 0L &&
                    now >= nextResumeAttemptAtMs
                ) {
                    nextResumeAttemptAtMs = now + REALTIME_TURN_RESUME_RETRY_THROTTLE_MS
                    try {
                        Log.i(TAG, "Realtime turn waiting for route; requesting resume")
                        openSocket(
                            resume = true,
                            expectedResumeEpisode = resumeEpisode,
                        )
                    } catch (e: Exception) {
                        Log.i(TAG, "Realtime turn resume attempt failed: ${e.message}")
                    }
                }
                delay(25L)
            }
            throw IOException("Realtime voice connection was not ready for the recorded turn")
        }

        suspend fun awaitRealtimeAgentCompletion(): Result<RealtimeVoiceSummary> {
            while (true) {
                val now = System.currentTimeMillis()
                // In persistent mode the turn/idle guards only apply while a turn
                // is actually in flight; between-turn idle is expected and must
                // not trip the stall timeout. The session ends when the turn
                // channel closes or a fatal error completes `finished`.
                val guardActive = !persistent || activeTurn.get()
                if (guardActive) {
                    val turnElapsedMs = now - turnStartedAtMs.get()
                    val idleElapsedMs = now - lastEventAtMs.get()
                    if (turnElapsedMs >= REALTIME_AGENT_MAX_TURN_MS) {
                        throw IOException("Realtime agent exceeded the turn limit")
                    }
                    // W3: for a known long/background run the relay can go quiet
                    // for minutes — pause the idle guard the same way persistent
                    // between-turn idle is paused, keeping only the MAX_TURN_MS
                    // backstop above.
                    val idleGuardActive = !longRunningTurn.get()
                    if (idleGuardActive && idleElapsedMs >= REALTIME_AGENT_IDLE_TIMEOUT_MS) {
                        throw IOException("Realtime agent stalled waiting for relay events")
                    }
                    val waitMs = if (idleGuardActive) {
                        minOf(
                            REALTIME_AGENT_WAIT_SLICE_MS,
                            REALTIME_AGENT_MAX_TURN_MS - turnElapsedMs,
                            REALTIME_AGENT_IDLE_TIMEOUT_MS - idleElapsedMs,
                        ).coerceAtLeast(1L)
                    } else {
                        minOf(
                            REALTIME_AGENT_WAIT_SLICE_MS,
                            REALTIME_AGENT_MAX_TURN_MS - turnElapsedMs,
                        ).coerceAtLeast(1L)
                    }
                    withTimeoutOrNull(waitMs) {
                        finished.await()
                    }?.let { return it }
                } else {
                    withTimeoutOrNull(REALTIME_AGENT_WAIT_SLICE_MS) {
                        finished.await()
                    }?.let { return it }
                }
            }
        }

        // Persistent mode: drain further utterances and feed each onto the open
        // socket as a new turn. Closing the channel (voice-mode exit) ends the
        // session by completing `finished`.
        val turnReader: Job? = if (turnInputs != null) {
            launch {
                try {
                    for (turn in turnInputs) {
                        if (turn.deliveryResult?.isCancelled == true) continue
                        val ws = try {
                            awaitReadyTurnSocket()
                        } catch (e: Exception) {
                            if (turn.deliveryResult?.isCancelled == true) continue
                            val failure = IOException(e.message ?: "Realtime voice connection unavailable", e)
                            turn.deliveryResult?.complete(Result.failure(failure))
                            completeFailure(failure.message.orEmpty(), failure)
                            return@launch
                        }
                        // Stop can cancel the ViewModel submission while this
                        // reader waits for a replacement route. Never send that
                        // abandoned recording after connectivity returns.
                        if (turn.deliveryResult?.isCancelled == true) continue
                        val sent = if (turn.prompt.isNotBlank() && turn.inputPcm.isEmpty()) {
                            val accepted = ws.send(
                                buildRealtimeResponseCreate(
                                    text = turn.prompt,
                                    toolScaffold = false,
                                    renderMode = "verbatim",
                                )
                            )
                            if (accepted) {
                                turnStartedAtMs.set(System.currentTimeMillis())
                                lastEventAtMs.set(System.currentTimeMillis())
                                activeTurn.set(true)
                                longRunningTurn.set(false)
                            }
                            accepted
                        } else {
                            val sendResult = sendTurnPcm(turn.inputPcm, turn.sampleRate)
                            if (sendResult.routeChanged) {
                                try {
                                    // Socket setup replays the atomically
                                    // buffered turn before publishing ready.
                                    awaitReadyTurnSocket()
                                    true
                                } catch (_: Exception) {
                                    false
                                }
                            } else {
                                sendResult.accepted
                            }
                        }
                        if (!sent) {
                            val failure = IOException("Realtime voice connection rejected the recorded turn")
                            turn.deliveryResult?.complete(Result.failure(failure))
                            completeFailure(failure.message.orEmpty(), failure)
                            return@launch
                        }
                        turn.deliveryResult?.complete(Result.success(Unit))
                    }
                } finally {
                    // Channel closed -> end the persistent session cleanly.
                    if (completed.compareAndSet(false, true)) {
                        finished.complete(
                            Result.success(
                                RealtimeVoiceSummary(
                                    provider = session.provider,
                                    model = session.model,
                                    voice = session.voice,
                                    sampleRate = session.sampleRate,
                                    audioChunks = audioChunks,
                                    audioBytes = audioBytes,
                                    firstAudioMs = null,
                                    responseDoneMs = null,
                                    eventLogPath = session.eventLogPath,
                                )
                            )
                        )
                    }
                    currentSocket.get()?.close(1000, "session ended")
                }
            }
        } else {
            null
        }

        val socket = openSocket(resume = false)
            ?: currentSocket.get()
            ?: throw IOException("Realtime agent websocket handshake was already pending")
        val routeWatcherResumeEpisode = AtomicLong(0L)
        val routeWatcher = startRouteResumeWatcher(
            surface = "Realtime agent",
            completed = completed,
            resumeAttempted = resumeAttempted,
            resumeSupported = session.resumeSupported,
            resumeToken = session.resumeToken,
            currentSocket = currentSocket,
            openResumeSocket = { route ->
                openSocket(
                    resume = true,
                    overrideWsBase = route,
                    expectedResumeEpisode = routeWatcherResumeEpisode
                        .getAndSet(0L)
                        .takeIf { it > 0L },
                )
            },
            onHandoff = routeHandoff@{ event ->
                val waiting = markResumeWaiting() ?: return@routeHandoff
                routeWatcherResumeEpisode.set(waiting.episode)
                onHandoff(
                    event.copy(transitionRevision = waiting.transitionRevision),
                )
            },
            completeFailure = ::completeFailure,
        )
        // Periodic resume retry: a failed resume used to park forever waiting
        // for a route-change signal, while the relay held the detached session
        // (and any background run's result) open for minutes. Keep knocking on
        // an interval until the retry window closes; the route watcher stays as
        // the fast path when the network actually switches.
        val resumeRetry: Job? = if (session.resumeSupported && !session.resumeToken.isNullOrBlank()) {
            launch {
                while (!completed.get()) {
                    delay(realtimeResumeRetryIntervalMs)
                    val resumeEpisode = resumeEpisodeSnapshot() ?: continue
                    var socketToCancel: WebSocket? = null
                    var failureTransitionRevision: Long? = null
                    val expired = synchronized(resumeStateLock) {
                        val deadline = resumeRetryDeadlineMs.get()
                        val sameEpisode = resumeWaiting.get() &&
                            resumeEpisodeGeneration.get() == resumeEpisode
                        if (!completed.get() &&
                            sameEpisode &&
                            deadline > 0L &&
                            System.currentTimeMillis() >= deadline &&
                            completed.compareAndSet(false, true)
                        ) {
                            socketToCancel = currentSocket.get()
                            failureTransitionRevision = handoffTransitionRevision.incrementAndGet()
                            true
                        } else {
                            false
                        }
                    }
                    if (expired) {
                        reportClaimedFailure(
                            "Realtime agent could not restore the voice route",
                            transitionRevision = failureTransitionRevision,
                        )
                        socketToCancel?.cancel()
                        return@launch
                    }
                    try {
                        Log.i(TAG, "Realtime agent periodic resume retry")
                        openSocket(
                            resume = true,
                            expectedResumeEpisode = resumeEpisode,
                        )
                    } catch (e: Exception) {
                        Log.i(TAG, "Realtime agent periodic resume retry failed: ${e.message}")
                    }
                }
            }
        } else {
            null
        }
        try {
            awaitRealtimeAgentCompletion()
        } catch (e: Exception) {
            currentSocket.get()?.close(1001, "timeout")
            socket.close(1001, "timeout")
            Result.failure(IOException(e.message ?: "Realtime agent timed out", e))
        } finally {
            routeWatcher?.cancel()
            resumeRetry?.cancel()
            turnReader?.cancel()
        }
    }

    private fun CoroutineScope.startRouteResumeWatcher(
        surface: String,
        completed: AtomicBoolean,
        resumeAttempted: AtomicBoolean,
        resumeSupported: Boolean,
        resumeToken: String?,
        currentSocket: AtomicReference<WebSocket?>,
        openResumeSocket: (String?) -> WebSocket?,
        onHandoff: (VoiceHandoffEvent) -> Unit,
        completeFailure: (String, Throwable?) -> Unit,
    ): Job? {
        val routeChanges = relayRouteChangesProvider?.invoke() ?: return null
        if (!resumeSupported || resumeToken.isNullOrBlank()) return null
        return launch {
            var lastWsBase = resolveWebSocketBase()
            routeChanges.collect { relayUrl ->
                val nextWsBase = toWebSocketBase(relayUrl) ?: resolveWebSocketBase() ?: return@collect
                val previousWsBase = lastWsBase
                lastWsBase = nextWsBase
                if (completed.get() || previousWsBase == null || previousWsBase == nextWsBase) {
                    return@collect
                }
                if (!resumeAttempted.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "$surface route changed $previousWsBase -> $nextWsBase; retrying resume on new route",
                    )
                }
                Log.i(
                    TAG,
                    "$surface route changed $previousWsBase -> $nextWsBase; proactively resuming voice websocket",
                )
                onHandoff(
                    VoiceHandoffEvent(
                        label = context.getString(R.string.voice_diag_route_changed),
                        previousRoute = routeLabel(previousWsBase),
                        nextRoute = routeLabel(nextWsBase),
                        route = routeLabel(nextWsBase),
                        active = true,
                    )
                )
                val oldSocket = currentSocket.get()
                try {
                    val newSocket = openResumeSocket(nextWsBase)
                    if (newSocket != null && oldSocket !== newSocket) {
                        oldSocket?.cancel()
                    }
                } catch (e: Exception) {
                    completeFailure("$surface resume failed after route change: ${e.message ?: "network error"}", e)
                }
            }
        }
    }

    private suspend fun getRealtimeConfigAt(
        path: String,
        label: String,
    ): Result<RealtimeVoiceConfig> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }
        val request = Request.Builder()
            .url(urlWithProfile("$httpBase$path"))
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                Result.success(json.decodeFromString(RealtimeVoiceConfig.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("$label failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getRealtimeProviderOptionsAt(
        providerId: String,
        pathPrefix: String,
        label: String,
    ): Result<VoiceProviderOptionsResponse> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }
        val provider = providerId.trim()
        if (provider.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Provider ID required"))
        }
        val request = Request.Builder()
            .url(urlWithProfile("$httpBase$pathPrefix/${pathSegment(provider)}/options"))
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body.string()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body.string()
                Result.success(json.decodeFromString(VoiceProviderOptionsResponse.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("$label failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun validateRealtimeProviderAt(
        providerId: String,
        model: String,
        voice: String,
        sampleRate: Int,
        pathPrefix: String,
        label: String,
    ): Result<VoiceProviderValidationResponse> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }
        val provider = providerId.trim()
        if (provider.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Provider ID required"))
        }
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model.trim()))
            put("voice", JsonPrimitive(voice.trim()))
            put("sample_rate", JsonPrimitive(sampleRate))
            putProfile()
        }
        val request = Request.Builder()
            .url(urlWithProfile("$httpBase$pathPrefix/${pathSegment(provider)}/validate"))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                Result.success(json.decodeFromString(VoiceProviderValidationResponse.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("$label failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun updateRealtimeConfigAt(
        enabled: Boolean? = null,
        provider: String? = null,
        model: String? = null,
        voice: String? = null,
        sampleRate: Int? = null,
        path: String,
        label: String,
    ): Result<RealtimeVoiceConfig> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = resolveBearerToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(missingAuthError())
        }
        val payload = buildJsonObject {
            enabled?.let { put("enabled", JsonPrimitive(it)) }
            provider?.takeIf { it.isNotBlank() }?.let {
                put("provider", JsonPrimitive(it.trim()))
            }
            model?.takeIf { it.isNotBlank() }?.let {
                put("model", JsonPrimitive(it.trim()))
            }
            voice?.takeIf { it.isNotBlank() }?.let {
                put("voice", JsonPrimitive(it.trim()))
            }
            sampleRate?.let { put("sample_rate", JsonPrimitive(it)) }
        }
        val request = Request.Builder()
            .url(urlWithProfile("$httpBase$path"))
            .patch(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                Result.success(json.decodeFromString(RealtimeVoiceConfig.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("$label failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun resolveHttpBase(): String? {
        val relayUrl = relayUrlProvider()?.trim().orEmpty()
        if (relayUrl.isEmpty()) return null
        val normalized = relayUrl
            .replace(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replace(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
            .trimEnd('/')
        // Reject a malformed base up front so callers' url("$base/…") can't throw
        // IllegalArgumentException on the IO dispatcher (relay half of #131).
        return if (normalized.toHttpUrlOrNull() != null) normalized else null
    }

    private fun resolveWebSocketBase(): String? {
        val relayUrl = relayUrlProvider()?.trim().orEmpty()
        return toWebSocketBase(relayUrl)
    }

    private fun toWebSocketBase(relayUrl: String?): String? {
        val trimmed = relayUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return trimmed
            .replace(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
            .replace(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
            .trimEnd('/')
    }

    private suspend fun resolveBearerToken(): String? {
        val sessionToken = sessionTokenProvider()?.trim()
        if (!sessionToken.isNullOrBlank()) return sessionToken
        val apiBearer = apiBearerTokenProvider()?.trim()
        return apiBearer?.takeIf { it.isNotBlank() }
    }

    private fun currentProfileName(): String? =
        profileNameProvider()?.trim()?.takeIf { it.isNotBlank() }

    private fun urlWithProfile(url: String): String {
        val profile = currentProfileName() ?: return url
        val encoded = URLEncoder.encode(profile, Charsets.UTF_8.name())
        val separator = if ("?" in url) "&" else "?"
        return "$url${separator}profile=$encoded"
    }

    private fun pathSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun missingAuthError(): IllegalStateException =
        IllegalStateException("Voice auth missing — pair with the relay or save a Hermes API key")

    private fun describeHttpError(code: Int, message: String, body: String = ""): String {
        // Voice routes return text/plain on error (see plugin/relay/voice_auth.py).
        // Prefer the server's own description when it's present — the 403 path
        // in particular has two very different causes ("session lacks active
        // voice:tts grant" vs "Hermes API bearer token requires HTTPS..."),
        // and we should not flatten them into one misleading "expired" string.
        val trimmed = body.trim().take(240)
        val fallback = when (code) {
            401 -> "Voice auth failed — re-pair to refresh the session"
            403 -> "Voice access denied — re-pair to restore voice grants"
            404 -> "Voice endpoint not available on this relay"
            413 -> "Audio too large for relay"
            503 -> "Voice provider unavailable (check relay config)"
            in 500..599 -> "Relay error (HTTP $code)"
            else -> "HTTP $code: ${message.ifBlank { "request failed" }}"
        }
        return if (trimmed.isNotEmpty()) "$fallback ($trimmed)" else fallback
    }

    private fun createRealtimeSession(httpBase: String, token: String): Result<RealtimeSessionResponse> {
        val body = buildJsonObject { putProfile() }.toString()
        val request = Request.Builder()
            .url("$httpBase/voice/realtime/session")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        return try {
            sessionClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                Result.success(json.decodeFromString(RealtimeSessionResponse.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("Realtime voice session failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun createRealtimeAgentSession(
        httpBase: String,
        token: String,
        chatSessionId: String?,
        conversationContext: List<RealtimeConversationContextMessage> = emptyList(),
        model: String? = null,
        voice: String? = null,
    ): Result<RealtimeSessionResponse> {
        val body = buildJsonObject {
            putProfile()
            model?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("model", JsonPrimitive(it))
            }
            voice?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("voice", JsonPrimitive(it))
            }
            chatSessionId?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("chat_session_id", JsonPrimitive(it))
            }
            if (conversationContext.isNotEmpty()) {
                put(
                    "context_messages",
                    buildJsonArray {
                        conversationContext
                            .mapNotNull { context ->
                                val content = context.content.trim()
                                if (content.isBlank()) null else context.copy(content = content.take(1_500))
                            }
                            .takeLast(14)
                            .forEach { context ->
                                addJsonObject {
                                    put(
                                        "role",
                                        when (context.role) {
                                            MessageRole.USER -> "user"
                                            MessageRole.ASSISTANT -> "assistant"
                                            else -> "system"
                                        },
                                    )
                                    put("content", context.content)
                                    context.source?.takeIf { it.isNotBlank() }?.let {
                                        put("source", it)
                                    }
                                }
                            }
                    },
                )
            }
        }.toString()
        val request = Request.Builder()
            .url("$httpBase/voice/realtime-agent/session")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        return try {
            sessionClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                Result.success(json.decodeFromString(RealtimeSessionResponse.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("Realtime agent session failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun createVoiceOutputSession(httpBase: String, token: String): Result<VoiceOutputSessionResponse> {
        val body = buildJsonObject { putProfile() }.toString()
        val request = Request.Builder()
            .url("$httpBase/voice/output/session")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        return try {
            sessionClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return Result.failure(
                        IOException(describeHttpError(response.code, response.message, body))
                    )
                }
                val raw = response.body?.string().orEmpty()
                Result.success(json.decodeFromString(VoiceOutputSessionResponse.serializer(), raw))
            }
        } catch (e: IOException) {
            Result.failure(IOException("Voice output session failed: ${e.message ?: "network error"}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun mediaTypeForAudioFile(file: File) = when (file.extension.lowercase()) {
        "wav", "wave" -> WAV_AUDIO
        "m4a", "mp4", "aac" -> MP4_AUDIO
        else -> OCTET_STREAM
    }

    private fun buildRealtimeResponseCreate(
        text: String,
        toolScaffold: Boolean,
        renderMode: String?,
    ): String {
        val mode = renderMode?.trim()?.takeIf { it.isNotEmpty() }
        val renderModeJson = mode?.let { ""","render_mode":"${escapeJson(it)}"""" }.orEmpty()
        return """{"type":"response.create","tool_scaffold":$toolScaffold,"text":"${escapeJson(text)}"$renderModeJson}"""
    }

    private fun sendClientAck(
        webSocket: WebSocket,
        eventId: Long?,
        audioEventId: Long?,
        playedAudioEventId: Long?,
        inputChunkId: Long?,
    ) {
        if (eventId == null && audioEventId == null && playedAudioEventId == null && inputChunkId == null) {
            return
        }
        val parts = mutableListOf<String>()
        eventId?.let { parts.add(""""event_id":$it""") }
        audioEventId?.let { parts.add(""""audio_event_id":$it""") }
        playedAudioEventId?.let { parts.add(""""played_audio_event_id":$it""") }
        inputChunkId?.let { parts.add(""""input_chunk_id":$it""") }
        webSocket.send("""{"type":"client.ack",${parts.joinToString(",")}}""")
    }

    private fun shouldAdvanceResumeEventCursor(event: RealtimeVoiceEvent): Boolean {
        if (event.type.startsWith("voice.session.")) return false
        if (event.type.startsWith("voice.replay.")) return false
        return event.eventId != null
    }

    private fun logVoiceResumeEvent(surface: String, event: RealtimeVoiceEvent) {
        when (event.type) {
            "voice.session.resumed" -> Log.i(
                TAG,
                "$surface session resumed session=${event.sessionId.orEmpty()} " +
                    "event=${event.eventId ?: 0} audio=${event.audioEventId ?: 0}",
            )
            "voice.session.resume_failed" -> Log.w(
                TAG,
                "$surface session resume failed session=${event.sessionId.orEmpty()} " +
                    "reason=${event.message ?: "unknown"}",
            )
            "voice.replay.started",
            "voice.replay.done" -> Log.i(
                TAG,
                "$surface ${event.type} session=${event.sessionId.orEmpty()} " +
                    "event=${event.eventId ?: 0} audio=${event.audioEventId ?: 0}",
            )
            else -> if (event.replayed) {
                Log.i(
                    TAG,
                    "$surface replayed ${event.type} event=${event.eventId ?: 0} " +
                        "audio=${event.audioEventId ?: 0}",
                )
            }
        }
    }

    private fun reportVoiceHandoffEvent(
        surface: String,
        webSocket: WebSocket,
        event: RealtimeVoiceEvent,
        onHandoff: (VoiceHandoffEvent) -> Unit,
        transitionRevision: Long? = null,
    ) {
        val route = routeLabel(webSocket.request().url.toString())
        when (event.type) {
            "voice.session.resumed" -> onHandoff(
                VoiceHandoffEvent(
                    label = context.getString(R.string.voice_diag_reconnected),
                    detail = surface,
                    route = route,
                    active = false,
                    success = true,
                    transitionRevision = transitionRevision,
                )
            )
            "voice.replay.started" -> onHandoff(
                VoiceHandoffEvent(
                    label = context.getString(R.string.voice_diag_replaying_audio),
                    route = route,
                    active = true,
                    transitionRevision = transitionRevision,
                )
            )
            "voice.replay.done" -> onHandoff(
                VoiceHandoffEvent(
                    label = context.getString(R.string.voice_diag_caught_up),
                    detail = surface,
                    route = route,
                    active = false,
                    success = true,
                    transitionRevision = transitionRevision,
                )
            )
            "voice.session.resume_failed" -> onHandoff(
                VoiceHandoffEvent(
                    label = context.getString(R.string.voice_diag_resume_rejected),
                    detail = event.message,
                    route = route,
                    active = false,
                    transitionRevision = transitionRevision,
                )
            )
        }
    }

    private fun routeLabel(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val withoutScheme = trimmed
            .replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^wss?://", RegexOption.IGNORE_CASE), "")
            .substringBefore("/")
        if (withoutScheme.isBlank()) return trimmed
        val host = withoutScheme.substringBefore(":")
        val prefix = when {
            host.startsWith("172.16.") || host.startsWith("192.168.") || host.startsWith("10.") -> "LAN"
            host.startsWith("100.") -> "Tailscale"
            else -> "Relay"
        }
        return "$prefix $withoutScheme"
    }

    private fun parseRealtimeEvent(raw: String): RealtimeVoiceEvent {
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            val metrics = obj["metrics"]?.jsonObject
            val textValue = (obj["text"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["final_text"] as? JsonPrimitive)?.contentOrNull
            val toolNameValue = (obj["tool_name"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["name"] as? JsonPrimitive)?.contentOrNull
            val toolCallIdValue = (obj["tool_call_id"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["call_id"] as? JsonPrimitive)?.contentOrNull
            val resultPreviewValue = (obj["result_preview"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["result"] as? JsonPrimitive)?.contentOrNull
            val reasonValue = (obj["reason"] as? JsonPrimitive)?.contentOrNull
            val errorValue = (obj["error"] as? JsonPrimitive)?.contentOrNull
            RealtimeVoiceEvent(
                type = (obj["type"] as? JsonPrimitive)?.content ?: "unknown",
                source = (obj["source"] as? JsonPrimitive)?.contentOrNull,
                message = (obj["message"] as? JsonPrimitive)?.contentOrNull
                    ?: errorValue
                    ?: reasonValue,
                reason = reasonValue,
                statusKey = (obj["status_key"] as? JsonPrimitive)?.contentOrNull,
                shouldSpeak = (obj["should_speak"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.toBooleanStrictOrNull()
                    ?: false,
                provider = (obj["provider"] as? JsonPrimitive)?.contentOrNull,
                model = (obj["model"] as? JsonPrimitive)?.contentOrNull,
                voice = (obj["voice"] as? JsonPrimitive)?.contentOrNull,
                eventId = (obj["event_id"] as? JsonPrimitive)?.longOrNull,
                audioEventId = (obj["audio_event_id"] as? JsonPrimitive)?.longOrNull,
                inputChunkId = (obj["input_chunk_id"] as? JsonPrimitive)?.longOrNull,
                replayed = (obj["replayed"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                sessionId = (obj["session_id"] as? JsonPrimitive)?.contentOrNull,
                chatSessionId = (obj["chat_session_id"] as? JsonPrimitive)?.contentOrNull,
                messageId = (obj["message_id"] as? JsonPrimitive)?.contentOrNull,
                runId = (obj["run_id"] as? JsonPrimitive)?.contentOrNull,
                confirmationId = (obj["confirmation_id"] as? JsonPrimitive)?.contentOrNull,
                delta = (obj["delta"] as? JsonPrimitive)?.contentOrNull,
                text = textValue,
                toolName = toolNameValue,
                toolCallId = toolCallIdValue,
                resultPreview = resultPreviewValue,
                success = realtimeEventSuccess(obj),
                audioBase64 = (obj["audio_base64"] as? JsonPrimitive)?.contentOrNull,
                byteCount = (obj["byte_count"] as? JsonPrimitive)?.intOrNull,
                sampleRate = (obj["sample_rate"] as? JsonPrimitive)?.intOrNull,
                timeoutMs = (obj["timeout_ms"] as? JsonPrimitive)?.longOrNull,
                peakLevel = (obj["peak_level"] as? JsonPrimitive)?.doubleOrNull?.toFloat(),
                rmsLevel = (obj["rms_level"] as? JsonPrimitive)?.doubleOrNull?.toFloat(),
                eventLogPath = (obj["event_log_path"] as? JsonPrimitive)?.contentOrNull,
                firstAudioMs = (metrics?.get("first_audio_ms") as? JsonPrimitive)?.doubleOrNull,
                responseDoneMs = (metrics?.get("response_done_ms") as? JsonPrimitive)?.doubleOrNull,
                tier = (obj["tier"] as? JsonPrimitive)?.contentOrNull,
                floor = (obj["floor"] as? JsonPrimitive)?.contentOrNull,
                activeToolName = (obj["active_tool_name"] as? JsonPrimitive)?.contentOrNull,
                completedToolCount = (obj["completed_tool_count"] as? JsonPrimitive)?.intOrNull
                    ?: (obj["tool_count"] as? JsonPrimitive)?.intOrNull,
                elapsedMs = (obj["elapsed_ms"] as? JsonPrimitive)?.longOrNull,
                queuedCount = (obj["queued_count"] as? JsonPrimitive)?.intOrNull,
                delivery = (obj["delivery"] as? JsonPrimitive)?.contentOrNull,
                raw = raw,
            )
        } catch (e: Exception) {
            RealtimeVoiceEvent(
                type = "parse.error",
                message = e.message,
                raw = raw,
            )
        }
    }

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private fun kotlinx.serialization.json.JsonObjectBuilder.putProfile() {
        currentProfileName()?.let { put("profile", JsonPrimitive(it)) }
    }
}

/**
 * Wire shape of `GET /voice/config`. Providers are returned as nested
 * objects describing the currently-active STT and TTS backend. Extra
 * fields are tolerated via `ignoreUnknownKeys = true`.
 */
@Serializable
data class VoiceConfig(
    val success: Boolean = false,
    val tts: VoiceProviderInfo? = null,
    val stt: VoiceProviderInfo? = null,
    val profile: String? = null,
    @SerialName("config_scope")
    val configScope: String? = null,
    @SerialName("config_path")
    val configPath: String? = null,
    @SerialName("fallback_to_global")
    val fallbackToGlobal: Boolean = false,
)

@Serializable
data class VoiceProviderInfo(
    val provider: String? = null,
    val model: String? = null,
    val voice: String? = null,
    @SerialName("voice_id")
    val voiceId: String? = null,
    val enabled: Boolean = false,
    val available: Boolean = true,
    /**
     * Provider-specific enhanced-voice capability hint. Present (non-null) only
     * for the TTS block when the relay's active provider supports per-request
     * enhanced control (today: Gemini and xAI).
     */
    val enhanced: EnhancedVoiceCapabilities? = null,
) {
    val displayVoice: String? get() = voice ?: voiceId
    val isEnabled: Boolean get() = enabled || (!provider.isNullOrBlank() && available)
}

/**
 * Wire shape of the `tts.enhanced` block on `GET /voice/config` — the relay's
 * provider-aware enhanced-voice capability advertisement. Mirrors
 * `plugin/relay/voice.py:_enhanced_voice_block`. `voices`/`models` may be empty
 * (e.g. xAI uses a free-text voice field); the UI renders from the flags.
 */
@Serializable
data class EnhancedVoiceCapabilities(
    val provider: String? = null,
    val supported: Boolean = false,
    val voices: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    @SerialName("audio_tag_models")
    val audioTagModels: List<String> = emptyList(),
    @SerialName("audio_tags_enabled")
    val audioTagsEnabled: Boolean = false,
    @SerialName("audio_tags_label")
    val audioTagsLabel: String = "Expressive tone tags",
    @SerialName("supports_persona")
    val supportsPersona: Boolean = false,
    @SerialName("supports_language")
    val supportsLanguage: Boolean = false,
    @SerialName("persona_prompt_file")
    val personaPromptFile: String? = null,
    val overrides: List<String> = emptyList(),
)

@Serializable
data class RealtimeVoiceConfig(
    val success: Boolean = false,
    val enabled: Boolean = false,
    val protocol: String? = null,
    val config_path: String? = null,
    val default_provider: String? = null,
    val default_model: String? = null,
    val default_voice: String? = null,
    val sample_rate: Int = 24000,
    val providers: List<RealtimeProviderInfo> = emptyList(),
    val auth: RealtimeVoiceAuth? = null,
    val profile: String? = null,
    @SerialName("config_scope")
    val configScope: String? = null,
    @SerialName("fallback_to_global")
    val fallbackToGlobal: Boolean = false,
    /** ADR 33 background-run promotion settings. */
    val promotion: RealtimeVoicePromotion? = null,
)

/** Wire shape of the `promotion` block on `GET /voice/realtime-agent/config`. */
@Serializable
data class RealtimeVoicePromotion(
    val enabled: Boolean = true,
    @SerialName("promote_after_ms")
    val promoteAfterMs: Int = 6000,
    @SerialName("background_default_mode")
    val backgroundDefaultMode: String = "promote",
    @SerialName("spoken_handoff")
    val spokenHandoff: Boolean = true,
    @SerialName("progress_spoken_after_ms")
    val progressSpokenAfterMs: Int = 15000,
    @SerialName("progress_repeat_ms")
    val progressRepeatMs: Int = 30000,
    @SerialName("result_delivery")
    val resultDelivery: String = "speak_verbatim",
    @SerialName("max_background_runs")
    val maxBackgroundRuns: Int = 1,
)

@Serializable
data class RealtimeProviderInfo(
    val id: String,
    val name: String? = null,
    val status: String? = null,
    val description: String? = null,
    val models: List<String> = emptyList(),
    val voices: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val sample_rates: List<Int> = emptyList(),
    val model_labels: Map<String, String> = emptyMap(),
    val voice_labels: Map<String, String> = emptyMap(),
    val language_labels: Map<String, String> = emptyMap(),
    val voice_groups: List<ProviderOptionGroup> = emptyList(),
    val voice_metadata: Map<String, ProviderOptionMetadata> = emptyMap(),
    val model_voice_compatibility: Map<String, List<String>> = emptyMap(),
    val recommended_voices: List<String> = emptyList(),
    val requires_manual_id: Boolean = false,
    val supports_tts: Boolean = false,
    val supports_stt: Boolean = false,
    val supports_speech_to_speech: Boolean = false,
    val supports_tool_use: Boolean = false,
    val supports_realtime: Boolean = false,
    val supports_realtime_agent_native: Boolean = false,
    val supports_interruption: Boolean = false,
    val supports_expression: Boolean = false,
)

@Serializable
data class ProviderOptionGroup(
    val id: String? = null,
    val label: String? = null,
    val values: List<String> = emptyList(),
    val source: String? = null,
    val recommended: List<String> = emptyList(),
    val custom: Boolean = false,
)

@Serializable
data class ProviderOptionMetadata(
    val label: String? = null,
    val source: String? = null,
    val custom: Boolean = false,
    val recommended: Boolean = false,
    val experimental: Boolean = false,
    val requires_manual_id: Boolean = false,
)

@Serializable
data class VoiceProviderOptionsResponse(
    val success: Boolean = false,
    val mode: String? = null,
    val protocol: String? = null,
    val schema_version: Int = 0,
    val provider_id: String? = null,
    val provider: RealtimeProviderInfo? = null,
    val default_provider: String? = null,
    val default_model: String? = null,
    val default_voice: String? = null,
    val sample_rate: Int = 24000,
    val language: String? = null,
    val profile: String? = null,
    @SerialName("config_scope")
    val configScope: String? = null,
    @SerialName("fallback_to_global")
    val fallbackToGlobal: Boolean = false,
    val dynamic: ProviderOptionsDynamic? = null,
)

@Serializable
data class ProviderOptionsDynamic(
    val attempted: Boolean = false,
    val status: String? = null,
    val source: String? = null,
    val message: String? = null,
    val voice_count: Int? = null,
    val custom_voice_count: Int? = null,
    val model_count: Int? = null,
    val cache: String? = null,
    val cache_ttl_seconds: Int? = null,
    val auth_source: String? = null,
)

@Serializable
data class VoiceProviderValidationResponse(
    val success: Boolean = false,
    val mode: String? = null,
    val protocol: String? = null,
    val provider_id: String? = null,
    val model: String? = null,
    val voice: String? = null,
    val sample_rate: Int? = null,
    val language: String? = null,
    val valid: Boolean = false,
    val summary: String? = null,
    val checks: List<ProviderValidationCheck> = emptyList(),
    val dynamic: ProviderOptionsDynamic? = null,
)

@Serializable
data class ProviderValidationCheck(
    val id: String? = null,
    val status: String? = null,
    val message: String? = null,
)

@Serializable
data class RealtimeVoiceAuth(
    val xai_env: Boolean = false,
    val xai_env_names: List<String> = emptyList(),
    val xai_oauth: Boolean = false,
    val xai_oauth_path: String? = null,
    val xai_oauth_source: String? = null,
    val openai_env: Boolean = false,
    val openai_env_names: List<String> = emptyList(),
)

@Serializable
data class VoiceOutputConfig(
    val success: Boolean = false,
    val enabled: Boolean = false,
    val protocol: String? = null,
    val config_path: String? = null,
    val default_provider: String? = null,
    val default_model: String? = null,
    val default_voice: String? = null,
    val sample_rate: Int = 24000,
    val language: String = "en",
    val codec: String = "pcm",
    val optimize_streaming_latency: Int = 1,
    val text_normalization: Boolean = false,
    /** xAI expressive speech tags on the streaming renderer (xai_tts only). */
    val auto_speech_tags: Boolean = false,
    val fallback_enabled: Boolean = true,
    val fallback_provider: String? = null,
    val providers: List<RealtimeProviderInfo> = emptyList(),
    val auth: VoiceOutputAuth? = null,
    val profile: String? = null,
    @SerialName("config_scope")
    val configScope: String? = null,
    @SerialName("fallback_to_global")
    val fallbackToGlobal: Boolean = false,
)

@Serializable
data class VoiceOutputAuth(
    val xai_env: Boolean = false,
    val xai_env_names: List<String> = emptyList(),
    val xai_oauth: Boolean = false,
    val xai_oauth_source: String? = null,
    val openai_env: Boolean = false,
    val openai_env_names: List<String> = emptyList(),
)

@Serializable
data class RealtimeSessionResponse(
    val success: Boolean = false,
    val session_id: String,
    val websocket_path: String,
    val resume_token: String? = null,
    val resume_supported: Boolean = false,
    val resume_ttl_ms: Long = 0L,
    val provider: String,
    val model: String,
    val voice: String,
    val sample_rate: Int = 24000,
    val event_log_path: String? = null,
    val profile: String? = null,
    @SerialName("config_scope")
    val configScope: String? = null,
) {
    val websocketPath: String get() = websocket_path
    val resumeToken: String? get() = resume_token
    val resumeSupported: Boolean get() = resume_supported
    val resumeTtlMs: Long get() = resume_ttl_ms
    val sampleRate: Int get() = sample_rate
    val eventLogPath: String? get() = event_log_path
}

@Serializable
data class VoiceOutputSessionResponse(
    val success: Boolean = false,
    val session_id: String,
    val websocket_path: String,
    val resume_token: String? = null,
    val resume_supported: Boolean = false,
    val resume_ttl_ms: Long = 0L,
    val provider: String,
    val model: String,
    val voice: String,
    val sample_rate: Int = 24000,
    val event_log_path: String? = null,
    val profile: String? = null,
    @SerialName("config_scope")
    val configScope: String? = null,
) {
    val websocketPath: String get() = websocket_path
    val resumeToken: String? get() = resume_token
    val resumeSupported: Boolean get() = resume_supported
    val resumeTtlMs: Long get() = resume_ttl_ms
    val sampleRate: Int get() = sample_rate
    val eventLogPath: String? get() = event_log_path
}

data class RealtimeVoiceEvent(
    val type: String,
    val source: String? = null,
    val message: String? = null,
    val reason: String? = null,
    val statusKey: String? = null,
    val shouldSpeak: Boolean = false,
    val provider: String? = null,
    val model: String? = null,
    val voice: String? = null,
    val eventId: Long? = null,
    val audioEventId: Long? = null,
    val inputChunkId: Long? = null,
    val replayed: Boolean = false,
    val sessionId: String? = null,
    val chatSessionId: String? = null,
    val messageId: String? = null,
    val runId: String? = null,
    val confirmationId: String? = null,
    val delta: String? = null,
    val text: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val resultPreview: String? = null,
    val success: Boolean? = null,
    val audioBase64: String? = null,
    val byteCount: Int? = null,
    val sampleRate: Int? = null,
    val timeoutMs: Long? = null,
    val peakLevel: Float? = null,
    val rmsLevel: Float? = null,
    val eventLogPath: String? = null,
    val firstAudioMs: Double? = null,
    val responseDoneMs: Double? = null,
    // ADR 33: background-run promotion fields.
    val tier: String? = null,
    val floor: String? = null,
    // hermes.run.progress extras — drive the live background-run chip.
    val activeToolName: String? = null,
    val completedToolCount: Int? = null,
    val elapsedMs: Long? = null,
    // Background-task queue depth (hermes.run.queued / promoted /
    // background_completed) — drives the chip's "+N queued" affix.
    val queuedCount: Int? = null,
    // Delivery responses (fallback TTS / respeak / visual-only text emit)
    // are hermes-sourced but carry the actual answer — the overlay renders
    // them where plain hermes run chatter stays suppressed.
    val delivery: String? = null,
    val raw: String,
) {
    val isAudioDelta: Boolean
        get() = type == "voice.audio.delta" || type == "voice.output_audio.delta"
}

internal fun realtimeEventSuccess(obj: JsonObject): Boolean? =
    (obj["success"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
        ?: (obj["ok"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

data class VoiceHandoffEvent(
    val label: String,
    val detail: String? = null,
    val route: String? = null,
    val previousRoute: String? = null,
    val nextRoute: String? = null,
    val active: Boolean = true,
    val success: Boolean = false,
    val transitionRevision: Long? = null,
)

class RealtimeAgentSessionControl(
    private val webSocket: WebSocket,
    private val onPlayedAudioEventId: (Long) -> Unit = {},
) {
    fun confirm(confirmationId: String, answer: String): Boolean {
        val id = confirmationId.trim()
        val normalized = answer.trim().lowercase()
        if (id.isEmpty() || normalized.isEmpty()) return false
        return webSocket.send(
            """{"type":"hermes.confirm","confirmation_id":"${escapeJsonForControl(id)}","answer":"${escapeJsonForControl(normalized)}"}"""
        )
    }

    fun cancel(): Boolean =
        webSocket.send("""{"type":"response.cancel"}""")

    /** Ask the relay to respeak the last delivered background result
     *  (DONE-chip tap). The relay answers with a hermes-sourced response
     *  rendered via relay TTS, or `hermes.result.respeak_unavailable`. */
    fun respeakLastResult(): Boolean =
        webSocket.send("""{"type":"hermes.result.respeak"}""")

    fun sendPlaybackDrained(
        callId: String?,
        playedAudioEventId: Long? = null,
    ): Boolean {
        val parts = mutableListOf<String>()
        callId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { parts.add(""""call_id":"${escapeJsonForControl(it)}"""") }
        playedAudioEventId
            ?.takeIf { it > 0L }
            ?.let {
                onPlayedAudioEventId(it)
                parts.add(""""played_audio_event_id":$it""")
            }
        return if (parts.isEmpty()) {
            webSocket.send("""{"type":"playback.drained"}""")
        } else {
            webSocket.send("""{"type":"playback.drained",${parts.joinToString(",")}}""")
        }
    }
}

private fun escapeJsonForControl(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

data class RealtimeVoiceSummary(
    val provider: String,
    val model: String,
    val voice: String,
    val sampleRate: Int,
    val audioChunks: Int,
    val audioBytes: Int,
    val firstAudioMs: Double?,
    val responseDoneMs: Double?,
    val eventLogPath: String?,
)

private data class BufferedRealtimeInputChunk(
    val id: Long,
    val pcm: ByteArray,
    val sampleRate: Int,
)

private data class BufferedRealtimeTurnSendResult(
    val accepted: Boolean,
    val routeChanged: Boolean,
)

private data class RealtimeResumeWaitingState(
    val episode: Long,
    val transitionRevision: Long,
)

private data class RealtimeResumeLossClaim(
    val openImmediately: Boolean,
    val waiting: RealtimeResumeWaitingState,
)

/**
 * One utterance fed into a persistent Realtime Agent session
 * (see [RelayVoiceClient.runRealtimeAgent] persistent mode). A blank [inputPcm]
 * with a non-blank [prompt] sends a text turn; otherwise the PCM is chunked and
 * committed as a spoken turn.
 */
data class RealtimeTurnInput(
    val inputPcm: ByteArray,
    val sampleRate: Int = 16_000,
    val prompt: String = "",
    val deliveryResult: CompletableDeferred<Result<Unit>>? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RealtimeTurnInput) return false
        return sampleRate == other.sampleRate &&
            prompt == other.prompt &&
            inputPcm.contentEquals(other.inputPcm)
    }

    override fun hashCode(): Int {
        var result = inputPcm.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + prompt.hashCode()
        return result
    }
}

data class VoiceOutputSummary(
    val provider: String,
    val model: String,
    val voice: String,
    val sampleRate: Int,
    val audioChunks: Int,
    val audioBytes: Int,
    val firstAudioMs: Double?,
    val responseDoneMs: Double?,
    val eventLogPath: String?,
)

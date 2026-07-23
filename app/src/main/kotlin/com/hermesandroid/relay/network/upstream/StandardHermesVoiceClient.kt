package com.hermesandroid.relay.network.upstream

import android.content.Context
import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.network.shared.VoiceAudioClient
import com.hermesandroid.relay.network.shared.VoiceSpeechStream
import com.hermesandroid.relay.network.shared.VoiceSpeechStreamCallbacks
import com.hermesandroid.relay.network.shared.VoiceSpeechStreamOutcome
import com.hermesandroid.relay.network.shared.VoiceSpeechStreamStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Standard (no-plugin) voice client — speaks the upstream **dashboard web
 * server** contract that hermes-desktop's voice mode uses:
 *
 *   POST {dashboard}/api/audio/transcribe  {data_url, mime_type} to {ok, transcript}
 *   POST {dashboard}/api/audio/speak       {text}                to {ok, data_url, mime_type}
 *
 * These routes live on `hermes_cli/web_server.py` (:9119 by convention), NOT
 * on the API server (:8642) — current upstream api_server advertises
 * `audio_api: false` and registers no audio routes. Auth is the dashboard
 * cookie session (gated_auth_middleware), so [okHttpClient] must carry the
 * same per-connection cookie jar the Manage tab signs in with; an API bearer
 * header is meaningless on this surface. Revisit when upstream PR #8199
 * lands the `/v1/audio` routes on the API server (docs/upstream-contributions.md section 6).
 * (No glob spellings in block comments — Kotlin block comments nest.)
 */
class StandardHermesVoiceClient(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val dashboardUrlProvider: () -> String?,
    // Active chat profile name (null = default/launch). Sent DEFENSIVELY on
    // /api/audio/speak: upstream `TTSSpeakRequest` is text-only and Pydantic
    // ignores extra fields, so this is harmless today and forward-compatible if
    // upstream ever adds profile-aware TTS. Until then, standard voice remains
    // the host's global TTS (see VoiceViewModel's standard-voice profile notice).
    private val profileProvider: () -> String? = { null },
    private val webSocketFactory: ((Request, WebSocketListener) -> WebSocket)? = null,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    },
) : VoiceAudioClient {
    override val route: VoiceAudioRoute = VoiceAudioRoute.Standard

    private val callClient: OkHttpClient =
        standardHermesDashboardAudioClient(okHttpClient)

    override suspend fun transcribe(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = dashboardBaseUrl()
            ?: return@withContext Result.failure(IllegalStateException("Hermes dashboard URL not configured"))
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(IOException("Audio file missing or empty: ${audioFile.name}"))
        }
        // Upstream caps decoded transcription audio at 25 MB (web_server.py
        // _MAX_TRANSCRIPTION_UPLOAD_BYTES → HTTP 413). The decoded size equals
        // the file size, so guard here to avoid a wasted ~33 MB base64 upload.
        if (audioFile.length() > MAX_TRANSCRIBE_BYTES) {
            return@withContext Result.failure(
                IOException("Recording too long for Hermes - try a shorter utterance"),
            )
        }

        // Resolve via toHttpUrlOrNull() — okhttp's url(String) THROWS on a
        // malformed dashboard URL (a non-address pasted into that field, #131),
        // and this runs before executeJson()'s try/catch, so the throw would
        // escape withContext(IO) onto the calling coroutine and crash the app.
        val httpUrl = "$baseUrl/api/audio/transcribe".toHttpUrlOrNull()
            ?: return@withContext Result.failure(IOException("Hermes dashboard URL is not a valid address: $baseUrl"))

        val dataUrl = buildAudioDataUrl(audioFile)
        val payload = buildJsonObject {
            put("data_url", dataUrl)
            put("mime_type", mediaTypeForAudioFile(audioFile))
        }
        val request = Request.Builder()
            .url(httpUrl)
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .build()

        executeJson(request, "Hermes audio transcribe").mapCatching { root ->
            val transcript = root.stringField("transcript")
                ?: root.stringField("text")
                ?: root.stringField("message")
            if (transcript.isNullOrBlank()) {
                throw IOException("Hermes audio transcribe returned an empty transcript")
            }
            transcript
        }
    }

    override suspend fun synthesize(text: String): Result<File> = withContext(Dispatchers.IO) {
        val baseUrl = dashboardBaseUrl()
            ?: return@withContext Result.failure(IllegalStateException("Hermes dashboard URL not configured"))
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Cannot synthesize blank text"))
        }

        // See transcribe(): guard the throwing url(String) so a malformed
        // dashboard URL is a clean Result.failure, never a Main-thread crash.
        val httpUrl = "$baseUrl/api/audio/speak".toHttpUrlOrNull()
            ?: return@withContext Result.failure(IOException("Hermes dashboard URL is not a valid address: $baseUrl"))

        val payload = buildJsonObject {
            put("text", cleanText)
            // Defensive only — upstream /api/audio/speak ignores it (text-only
            // TTSSpeakRequest). Omitted for the default profile.
            profileProvider()?.trim()?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
        }
        val request = Request.Builder()
            .url(httpUrl)
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .build()

        executeJson(request, "Hermes audio speak").mapCatching { root ->
            val dataUrl = root.stringField("data_url") ?: root.stringField("dataUrl")
            if (dataUrl.isNullOrBlank()) {
                throw IOException("Hermes audio speak returned no audio")
            }
            val mimeType = root.stringField("mime_type")
                ?: root.stringField("mimeType")
                ?: mimeTypeFromDataUrl(dataUrl)
                ?: "audio/mpeg"
            val bytes = decodeDataUrl(dataUrl)
            if (bytes.isEmpty()) throw IOException("Hermes audio speak returned empty audio")

            val extension = extensionForMimeType(mimeType)
            File(context.cacheDir, "hermes_voice_${System.currentTimeMillis()}.$extension")
                .also { it.writeBytes(bytes) }
        }
    }

    override suspend fun openSpeechStream(
        callbacks: VoiceSpeechStreamCallbacks,
    ): Result<VoiceSpeechStream?> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = dashboardBaseUrl()
                ?: throw IllegalStateException("Hermes dashboard URL not configured")
            val ticketUrl = "$baseUrl/api/auth/ws-ticket".toHttpUrlOrNull()
                ?: throw IOException("Hermes dashboard URL is not a valid address: $baseUrl")
            val ticketRequest = Request.Builder()
                .url(ticketUrl)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            val ticket = executeJson(ticketRequest, "Dashboard websocket ticket")
                .getOrThrow()
                .stringField("ticket")
                ?: throw IOException("Dashboard websocket ticket response missing ticket")
            val websocketUrl = DashboardApiClient.gatewayWebSocketUrl(
                baseUrl = baseUrl,
                ticket = ticket,
                path = "/api/audio/speak-stream",
            ) ?: throw IOException("Could not build Hermes speech stream URL")
            val request = Request.Builder().url(websocketUrl).build()
            StandardHermesSpeechStream(
                request = request,
                callbacks = callbacks,
                json = json,
                socketFactory = webSocketFactory ?: callClient::newWebSocket,
            ).also { it.connect() }
                .let { Result.success<VoiceSpeechStream?>(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun dashboardBaseUrl(): String? =
        dashboardUrlProvider()?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }

    private fun executeJson(request: Request, operation: String): Result<JsonObject> {
        return try {
            callClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(apiFailure(response, operation))
                }
                val body = response.body.string()
                if (body.isBlank()) {
                    return Result.failure(IOException("$operation returned an empty response"))
                }
                val root = json.decodeFromString<JsonObject>(body)
                val ok = (root["ok"] as? JsonPrimitive)?.contentOrNull
                    ?.toBooleanStrictOrNull()
                if (ok == false) {
                    val message = root.stringField("message")
                        ?: root.stringField("error")
                        ?: "$operation failed"
                    return Result.failure(IOException(message))
                }
                Result.success(root)
            }
        } catch (e: IOException) {
            Result.failure(IOException("$operation failed: ${e.message ?: "network error"}", e))
        } catch (e: Exception) {
            Result.failure(IOException("$operation failed: ${e.message ?: "parse error"}", e))
        }
    }

    private fun apiFailure(response: Response, operation: String): IOException {
        val body = runCatching { response.body.string() }.getOrDefault("")
        val detail = body.takeIf { it.isNotBlank() } ?: response.message
        val message = when (response.code) {
            400 -> "$operation rejected that input - ${detail.ifBlank { "bad request" }}"
            401, 403 -> "$operation needs dashboard sign-in - open Manage to sign in"
            404 -> "$operation unavailable on this Hermes build - update hermes-agent or use Relay"
            413 -> "Recording too long for Hermes - try a shorter utterance"
            in 500..599 -> "$operation failed - server error HTTP ${response.code}"
            else -> "$operation failed - HTTP ${response.code}: $detail"
        }
        return IOException(message)
    }

    private fun buildAudioDataUrl(audioFile: File): String {
        val mimeType = mediaTypeForAudioFile(audioFile)
        val encoded = Base64.getEncoder().encodeToString(audioFile.readBytes())
        return "data:$mimeType;base64,$encoded"
    }

    private fun mediaTypeForAudioFile(file: File): String =
        when (file.extension.lowercase()) {
            "wav" -> "audio/wav"
            "m4a", "mp4" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "webm" -> "audio/webm"
            else -> "application/octet-stream"
        }

    private fun decodeDataUrl(dataUrl: String): ByteArray {
        val comma = dataUrl.indexOf(',')
        val payload = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
        return Base64.getDecoder().decode(payload)
    }

    private fun mimeTypeFromDataUrl(dataUrl: String): String? {
        if (!dataUrl.startsWith("data:", ignoreCase = true)) return null
        val semi = dataUrl.indexOf(';')
        if (semi <= "data:".length) return null
        return dataUrl.substring("data:".length, semi).takeIf { it.isNotBlank() }
    }

    private fun extensionForMimeType(mimeType: String): String =
        when (mimeType.lowercase().substringBefore(';')) {
            "audio/wav", "audio/wave", "audio/x-wav" -> "wav"
            "audio/mp4", "audio/aac", "audio/m4a" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/webm" -> "webm"
            else -> "mp3"
        }

    private fun JsonObject.stringField(name: String): String? =
        ((this[name] as? JsonPrimitive)?.contentOrNull)?.trim()?.takeIf { it.isNotBlank() }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()

        // Matches upstream _MAX_TRANSCRIPTION_UPLOAD_BYTES (web_server.py): the
        // dashboard rejects decoded transcription audio above 25 MB with 413.
        const val MAX_TRANSCRIBE_BYTES = 25L * 1024 * 1024
    }
}

private class StandardHermesSpeechStream(
    private val request: Request,
    private val callbacks: VoiceSpeechStreamCallbacks,
    private val json: Json,
    private val socketFactory: (Request, WebSocketListener) -> WebSocket,
) : VoiceSpeechStream {
    private val lock = Any()
    private val outcome = CompletableDeferred<VoiceSpeechStreamOutcome>()
    private val pendingFrames = ArrayDeque<String>()
    private var socket: WebSocket? = null
    private var opened = false
    private var stopped = false
    private var finished = false
    private var audioStarted = false
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var oddByteCarry: Byte? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val frames = synchronized(lock) {
                if (stopped || outcome.isCompleted) {
                    webSocket.cancel()
                    return
                }
                socket = webSocket
                opened = true
                pendingFrames.toList().also { pendingFrames.clear() }
            }
            frames.forEach(webSocket::send)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val root = runCatching { json.decodeFromString<JsonObject>(text) }.getOrNull() ?: return
            when (root.stringField("type")) {
                "start" -> {
                    val nextRate = (root["sample_rate"] as? JsonPrimitive)?.contentOrNull
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
                        ?: DEFAULT_SAMPLE_RATE
                    val channels = (root["channels"] as? JsonPrimitive)?.contentOrNull
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
                        ?: 1
                    if (channels != 1) {
                        settle(
                            status = VoiceSpeechStreamStatus.Fallback,
                            error = IOException("Hermes speech stream returned $channels channels; mono required"),
                        )
                        webSocket.cancel()
                        return
                    }
                    synchronized(lock) { sampleRate = nextRate }
                    callbacks.onStart(nextRate, channels)
                }
                "fallback" -> {
                    val heardAudio = synchronized(lock) { audioStarted }
                    settle(
                        status = if (heardAudio) {
                            VoiceSpeechStreamStatus.Completed
                        } else {
                            VoiceSpeechStreamStatus.Fallback
                        },
                    )
                    webSocket.close(1000, "fallback")
                }
                "end" -> {
                    settle(VoiceSpeechStreamStatus.Completed)
                    webSocket.close(1000, "complete")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val delivery = synchronized(lock) {
                if (stopped || outcome.isCompleted) return
                var incoming = bytes.toByteArray()
                oddByteCarry?.let { carry ->
                    incoming = byteArrayOf(carry) + incoming
                    oddByteCarry = null
                }
                val usable = incoming.size - (incoming.size % 2)
                if (usable < incoming.size) oddByteCarry = incoming.last()
                if (usable == 0) return
                audioStarted = true
                incoming.copyOf(usable) to sampleRate
            }
            runCatching { callbacks.onPcm(delivery.first, delivery.second) }
                .onFailure { error ->
                    settle(VoiceSpeechStreamStatus.Failed, error)
                    webSocket.cancel()
                }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            settleForDisconnect(IOException("Hermes speech stream closed ($code): $reason"))
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            settleForDisconnect(IOException("Hermes speech stream closed ($code): $reason"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            settleForDisconnect(t)
        }
    }

    fun connect() {
        val created = socketFactory(request, listener)
        synchronized(lock) {
            if (socket == null) socket = created
            if (stopped) created.cancel()
        }
    }

    override fun append(text: String) {
        if (text.isEmpty()) return
        sendFrame(buildJsonObject { put("text", text) })
    }

    override fun finish() {
        synchronized(lock) {
            if (finished || stopped || outcome.isCompleted) return
            finished = true
        }
        sendFrame(buildJsonObject { put("done", true) })
    }

    override fun stop() {
        val current = synchronized(lock) {
            if (stopped) return
            stopped = true
            socket
        }
        if (current != null) {
            current.send(json.encodeToString(JsonObject.serializer(), buildJsonObject { put("stop", true) }))
            current.cancel()
        }
        settle(VoiceSpeechStreamStatus.Stopped)
    }

    override suspend fun awaitOutcome(): VoiceSpeechStreamOutcome = outcome.await()

    private fun sendFrame(frame: JsonObject) {
        val encoded = json.encodeToString(JsonObject.serializer(), frame)
        val current = synchronized(lock) {
            if (stopped || outcome.isCompleted) return
            if (!opened) {
                pendingFrames.addLast(encoded)
                return
            }
            socket
        }
        if (current?.send(encoded) != true) {
            settleForDisconnect(IOException("Hermes speech stream send failed"))
        }
    }

    private fun settleForDisconnect(error: Throwable) {
        val heardAudio = synchronized(lock) { audioStarted }
        settle(
            status = if (heardAudio) VoiceSpeechStreamStatus.Failed else VoiceSpeechStreamStatus.Fallback,
            error = error,
        )
    }

    private fun settle(status: VoiceSpeechStreamStatus, error: Throwable? = null) {
        val result = synchronized(lock) {
            if (outcome.isCompleted) return
            VoiceSpeechStreamOutcome(
                status = status,
                audioStarted = audioStarted,
                error = error,
            )
        }
        outcome.complete(result)
    }

    private fun JsonObject.stringField(name: String): String? =
        ((this[name] as? JsonPrimitive)?.contentOrNull)?.trim()?.takeIf { it.isNotBlank() }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 24_000
    }
}

internal const val STANDARD_HERMES_DASHBOARD_AUDIO_TIMEOUT_SECONDS = 180L

internal fun standardHermesDashboardAudioTimeoutSeconds(requestedSeconds: Long): Long =
    requestedSeconds.coerceIn(
        minimumValue = 180L,
        maximumValue = 600L,
    )

internal fun standardHermesDashboardAudioClient(
    baseClient: OkHttpClient,
    timeoutSeconds: Long = STANDARD_HERMES_DASHBOARD_AUDIO_TIMEOUT_SECONDS,
): OkHttpClient {
    val boundedTimeoutSeconds = standardHermesDashboardAudioTimeoutSeconds(timeoutSeconds)
    return baseClient.newBuilder()
        .callTimeout(boundedTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(boundedTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(boundedTimeoutSeconds, TimeUnit.SECONDS)
        .build()
}

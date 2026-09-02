package com.axiomlabs.hermesrelay.core.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.util.Base64

class RelayVoiceClient(
    private val context: Context,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val relayUrlProvider: () -> String?,
    private val sessionTokenProvider: () -> String?,
) {
    suspend fun synthesize(text: String): Result<File> = withContext(Dispatchers.IO) {
        val base = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = sessionTokenProvider()
            ?: return@withContext Result.failure(IllegalStateException("Relay session token missing"))
        if (text.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Text is blank"))
        }

        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        val request = Request.Builder()
            .url("$base/voice/synthesize")
            .post("{\"text\":\"$escaped\"}".toRequestBody(JSON))
            .header("Authorization", "Bearer $token")
            .header("Accept", "audio/mpeg")
            .build()

        runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty synthesize response")
                val outFile = File(context.cacheDir, "quest_voice_tts_${System.currentTimeMillis()}.mp3")
                body.byteStream().use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (outFile.length() == 0L) throw IOException("Synthesize returned 0 bytes")
                outFile
            }
        }
    }

    suspend fun getRealtimeConfig(): Result<RealtimeVoiceConfig> = withContext(Dispatchers.IO) {
        val base = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = sessionTokenProvider()
            ?: return@withContext Result.failure(IllegalStateException("Relay session token missing"))

        val request = Request.Builder()
            .url("$base/voice/realtime/config")
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException(describeHttpError(response))
                val raw = response.body?.string().orEmpty()
                JSON_CODEC.decodeFromString(RealtimeVoiceConfig.serializer(), raw)
            }
        }
    }

    suspend fun createRealtimeSession(): Result<RealtimeSessionResponse> = withContext(Dispatchers.IO) {
        val base = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = sessionTokenProvider()
            ?: return@withContext Result.failure(IllegalStateException("Relay session token missing"))

        val request = Request.Builder()
            .url("$base/voice/realtime/session")
            .post("{}".toRequestBody(JSON))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException(describeHttpError(response))
                val raw = response.body?.string().orEmpty()
                JSON_CODEC.decodeFromString(RealtimeSessionResponse.serializer(), raw)
            }
        }
    }

    fun openRealtimeSession(
        session: RealtimeSessionResponse,
        listener: RealtimeVoiceListener,
    ): Result<RealtimeVoiceSocket> {
        val base = resolveWebSocketBase()
            ?: return Result.failure(IllegalStateException("Relay URL not configured"))
        val token = sessionTokenProvider()
            ?: return Result.failure(IllegalStateException("Relay session token missing"))

        val request = Request.Builder()
            .url("$base${session.websocketPath}")
            .header("Authorization", "Bearer $token")
            .build()
        val socket = RealtimeVoiceSocket(session)
        val webSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket.attach(webSocket)
                    socket.start()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    listener.onEvent(parseRealtimeEvent(text))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onFailure(IOException("Realtime voice websocket failed: ${t.message}", t))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed(code, reason)
                }
            },
        )
        socket.attach(webSocket)
        return Result.success(socket)
    }

    private fun resolveHttpBase(): String? {
        val relayUrl = relayUrlProvider()?.trim().orEmpty()
        if (relayUrl.isBlank()) return null
        return relayUrl
            .replace(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replace(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
            .removeSuffix("/ws")
            .trimEnd('/')
    }

    private fun resolveWebSocketBase(): String? {
        val relayUrl = relayUrlProvider()?.trim().orEmpty()
        if (relayUrl.isBlank()) return null
        return relayUrl
            .replace(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
            .replace(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
            .removeSuffix("/ws")
            .trimEnd('/')
    }

    private fun describeHttpError(response: Response): String {
        val body = response.body?.string()?.trim().orEmpty().take(240)
        val fallback = "HTTP ${response.code}: ${response.message.ifBlank { "request failed" }}"
        return if (body.isBlank()) fallback else "$fallback ($body)"
    }

    private fun parseRealtimeEvent(raw: String): RealtimeVoiceEvent {
        return try {
            val obj = JSON_CODEC.parseToJsonElement(raw).jsonObject
            val metrics = obj["metrics"]?.jsonObject
            RealtimeVoiceEvent(
                type = (obj["type"] as? JsonPrimitive)?.content ?: "unknown",
                message = (obj["message"] as? JsonPrimitive)?.contentOrNull,
                provider = (obj["provider"] as? JsonPrimitive)?.contentOrNull,
                model = (obj["model"] as? JsonPrimitive)?.contentOrNull,
                voice = (obj["voice"] as? JsonPrimitive)?.contentOrNull,
                sessionId = (obj["session_id"] as? JsonPrimitive)?.contentOrNull,
                audioBase64 = (obj["audio_base64"] as? JsonPrimitive)?.contentOrNull,
                byteCount = (obj["byte_count"] as? JsonPrimitive)?.intOrNull,
                sampleRate = (obj["sample_rate"] as? JsonPrimitive)?.intOrNull,
                peakLevel = (obj["peak_level"] as? JsonPrimitive)?.doubleOrNull?.toFloat(),
                rmsLevel = (obj["rms_level"] as? JsonPrimitive)?.doubleOrNull?.toFloat(),
                eventLogPath = (obj["event_log_path"] as? JsonPrimitive)?.contentOrNull,
                firstAudioMs = (metrics?.get("first_audio_ms") as? JsonPrimitive)?.doubleOrNull,
                responseDoneMs = (metrics?.get("response_done_ms") as? JsonPrimitive)?.doubleOrNull,
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

    private companion object {
        val JSON = "application/json".toMediaType()
        val JSON_CODEC = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

class RealtimeVoiceSocket(
    val session: RealtimeSessionResponse,
) {
    private var webSocket: WebSocket? = null

    internal fun attach(socket: WebSocket) {
        webSocket = socket
    }

    fun start() {
        webSocket?.send("""{"type":"session.start"}""")
    }

    fun sendInputAudio(
        pcm: ByteArray,
        sampleRate: Int,
        chunkBytes: Int = 6_400,
    ) {
        if (pcm.isEmpty()) return
        var offset = 0
        while (offset < pcm.size) {
            val end = (offset + chunkBytes).coerceAtMost(pcm.size)
            val encoded = Base64.getEncoder().encodeToString(pcm.copyOfRange(offset, end))
            webSocket?.send(
                """{"type":"input_audio.append","sample_rate":$sampleRate,"audio_base64":"$encoded"}""",
            )
            offset = end
        }
    }

    fun requestResponse(
        prompt: String,
        toolScaffold: Boolean = true,
    ) {
        val escaped = escapeJson(prompt.ifBlank {
            "Testing the Quest realtime voice harness through the Hermes relay."
        })
        webSocket?.send(
            """{"type":"response.create","tool_scaffold":$toolScaffold,"text":"$escaped"}""",
        )
    }

    fun close() {
        webSocket?.send("""{"type":"session.close"}""")
        webSocket?.close(1000, "client close")
        webSocket = null
    }

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}

interface RealtimeVoiceListener {
    fun onEvent(event: RealtimeVoiceEvent)
    fun onFailure(error: Throwable)
    fun onClosed(code: Int, reason: String)
}

@Serializable
data class RealtimeVoiceConfig(
    val success: Boolean = false,
    val enabled: Boolean = false,
    val protocol: String? = null,
    @SerialName("default_provider")
    val defaultProvider: String? = null,
    @SerialName("default_model")
    val defaultModel: String? = null,
    @SerialName("default_voice")
    val defaultVoice: String? = null,
    @SerialName("sample_rate")
    val sampleRate: Int = 24_000,
)

@Serializable
data class RealtimeSessionResponse(
    val success: Boolean = false,
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("websocket_path")
    val websocketPath: String,
    val provider: String,
    val model: String,
    val voice: String,
    @SerialName("sample_rate")
    val sampleRate: Int = 24_000,
    @SerialName("event_log_path")
    val eventLogPath: String? = null,
)

data class RealtimeVoiceEvent(
    val type: String,
    val message: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val voice: String? = null,
    val sessionId: String? = null,
    val audioBase64: String? = null,
    val byteCount: Int? = null,
    val sampleRate: Int? = null,
    val peakLevel: Float? = null,
    val rmsLevel: Float? = null,
    val eventLogPath: String? = null,
    val firstAudioMs: Double? = null,
    val responseDoneMs: Double? = null,
    val raw: String,
)

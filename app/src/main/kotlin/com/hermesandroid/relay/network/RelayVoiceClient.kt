package com.hermesandroid.relay.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * HTTP client for the Hermes relay **voice** endpoints (V1 contract).
 *
 *   POST /voice/transcribe  — multipart upload, returns `{"text": "..."}`
 *   POST /voice/synthesize  — JSON `{"text": "..."}`, returns audio/mpeg bytes
 *
 * Auth, URL conversion (`ws` ↔ `http`), and error shape mirror
 * [RelayHttpClient] — same bearer token, same `{relayUrl, sessionToken}`
 * providers. We take the providers as constructor args instead of sharing
 * a [RelayHttpClient] instance so the classes stay decoupled.
 */
class RelayVoiceClient(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val relayUrlProvider: () -> String?,
    private val sessionTokenProvider: suspend () -> String?,
) {

    companion object {
        private const val TAG = "RelayVoiceClient"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val MP4_AUDIO = "audio/mp4".toMediaType()
    }

    /**
     * Upload [audioFile] to `/voice/transcribe` and return the transcribed
     * text. Expects a JSON response of the form
     *   `{"text": "...", "provider": "...", "success": true}`.
     */
    suspend fun transcribe(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = sessionTokenProvider()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Relay not paired — session token missing")
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
                body = audioFile.asRequestBody(MP4_AUDIO),
            )
            .build()

        val request = Request.Builder()
            .url("$httpBase/voice/transcribe")
            .post(body)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException(describeHttpError(response.code, response.message)))
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
    suspend fun synthesize(text: String): Result<File> = withContext(Dispatchers.IO) {
        val httpBase = resolveHttpBase()
            ?: return@withContext Result.failure(IllegalStateException("Relay URL not configured"))
        val token = sessionTokenProvider()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Relay not paired — session token missing")
            )
        }
        if (text.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("synthesize: text is blank"))
        }

        // Hand-rolled JSON — one field, easier to audit than pulling in
        // JsonObjectBuilder just for this call.
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        val bodyJson = "{\"text\":\"$escaped\"}"

        val request = Request.Builder()
            .url("$httpBase/voice/synthesize")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Accept", "audio/mpeg")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException(describeHttpError(response.code, response.message)))
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
        val token = sessionTokenProvider()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Relay not paired — session token missing")
            )
        }

        val request = Request.Builder()
            .url("$httpBase/voice/config")
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException(describeHttpError(response.code, response.message)))
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

    private fun resolveHttpBase(): String? {
        val relayUrl = relayUrlProvider()?.trim().orEmpty()
        if (relayUrl.isEmpty()) return null
        return relayUrl
            .replace(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replace(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
            .trimEnd('/')
    }

    private fun describeHttpError(code: Int, message: String): String = when (code) {
        401, 403 -> "Unauthorized — re-pair with the relay"
        404 -> "Voice endpoint not available on this relay"
        413 -> "Audio too large for relay"
        503 -> "Voice provider unavailable (check relay config)"
        in 500..599 -> "Relay error (HTTP $code)"
        else -> "HTTP $code: ${message.ifBlank { "request failed" }}"
    }
}

/**
 * Wire shape of `GET /voice/config`. Providers are returned as nested
 * objects describing the currently-active STT and TTS backend. Extra
 * fields are tolerated via `ignoreUnknownKeys = true`.
 */
@Serializable
data class VoiceConfig(
    val tts: VoiceProviderInfo? = null,
    val stt: VoiceProviderInfo? = null,
)

@Serializable
data class VoiceProviderInfo(
    val provider: String? = null,
    val model: String? = null,
    val voice: String? = null,
    val available: Boolean = true,
)

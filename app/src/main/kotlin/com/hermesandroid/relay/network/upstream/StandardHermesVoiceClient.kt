package com.hermesandroid.relay.network.upstream

import android.content.Context
import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.network.shared.VoiceAudioClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    },
) : VoiceAudioClient {
    override val route: VoiceAudioRoute = VoiceAudioRoute.Standard

    private val callClient: OkHttpClient =
        okHttpClient.newBuilder()
            .callTimeout(90, TimeUnit.SECONDS)
            .build()

    override suspend fun transcribe(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = dashboardBaseUrl()
            ?: return@withContext Result.failure(IllegalStateException("Hermes dashboard URL not configured"))
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(IOException("Audio file missing or empty: ${audioFile.name}"))
        }

        val dataUrl = buildAudioDataUrl(audioFile)
        val payload = buildJsonObject {
            put("data_url", dataUrl)
            put("mime_type", mediaTypeForAudioFile(audioFile))
        }
        val request = Request.Builder()
            .url("$baseUrl/api/audio/transcribe")
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

        val payload = buildJsonObject { put("text", cleanText) }
        val request = Request.Builder()
            .url("$baseUrl/api/audio/speak")
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
            401, 403 -> "$operation needs dashboard sign-in - open Manage to sign in"
            404 -> "$operation unavailable on this Hermes build - update hermes-agent or use Relay"
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
    }
}

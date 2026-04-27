package com.axiomlabs.hermesrelay.core.voice

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

class RelayVoiceClient(
    private val context: Context,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val relayUrlProvider: () -> String?,
    private val sessionTokenProvider: () -> String?,
) {
    suspend fun synthesize(text: String): Result<File> {
        val base = resolveHttpBase()
            ?: return Result.failure(IllegalStateException("Relay URL not configured"))
        val token = sessionTokenProvider()
            ?: return Result.failure(IllegalStateException("Relay session token missing"))
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Text is blank"))

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

        return runCatching {
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

    private fun resolveHttpBase(): String? {
        val relayUrl = relayUrlProvider()?.trim().orEmpty()
        if (relayUrl.isBlank()) return null
        return relayUrl
            .replace(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replace(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
            .removeSuffix("/ws")
            .trimEnd('/')
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

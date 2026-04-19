package com.hermesandroid.relay.network

import android.util.Log
import com.hermesandroid.relay.data.ProfileConfigResponse
import com.hermesandroid.relay.data.ProfileMemoryResponse
import com.hermesandroid.relay.data.ProfileSkillsResponse
import com.hermesandroid.relay.data.ProfileSoulResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * HTTP client for the read-only **Profile Inspector** endpoints added in
 * the v0.7.0 relay:
 *
 *  - `GET /api/profiles/{name}/config`   (already live)
 *  - `GET /api/profiles/{name}/skills`   (already live)
 *  - `GET /api/profiles/{name}/soul`     (Python worker)
 *  - `GET /api/profiles/{name}/memory`   (Python worker)
 *
 * Mirrors the constructor shape of [RelayHttpClient] — same OkHttpClient,
 * the same `wss://` → `https://` URL-flipping trick, and the same lazy
 * session-token provider so a paired bearer token from EncryptedSharedPrefs
 * is only read when actually needed.
 *
 * All IO hops over [Dispatchers.IO] — we had a `NetworkOnMainThreadException`
 * during v0.6.0 development when an earlier client went straight from a
 * Composable effect to OkHttp without a dispatcher hop, so every path here
 * starts with `withContext(Dispatchers.IO) { ... }`.
 *
 * Profile names are URL-encoded before being spliced into the path so
 * names containing spaces or non-ASCII characters don't produce malformed
 * URLs.
 */
class RelayProfileInspectorClient(
    private val okHttpClient: OkHttpClient,
    private val relayUrlProvider: () -> String?,
    private val sessionTokenProvider: suspend () -> String?,
) {

    companion object {
        private const val TAG = "RelayProfileInspector"
        // Lenient + ignore unknown keys so if the Python worker adds a
        // field later we don't fail to deserialize the whole payload.
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }

    /** Fetch `GET /api/profiles/{name}/config`. */
    suspend fun fetchConfig(profileName: String): Result<ProfileConfigResponse> =
        get(profileName, "config", ProfileConfigResponse.serializer())

    /** Fetch `GET /api/profiles/{name}/skills`. */
    suspend fun fetchSkills(profileName: String): Result<ProfileSkillsResponse> =
        get(profileName, "skills", ProfileSkillsResponse.serializer())

    /** Fetch `GET /api/profiles/{name}/soul`. */
    suspend fun fetchSoul(profileName: String): Result<ProfileSoulResponse> =
        get(profileName, "soul", ProfileSoulResponse.serializer())

    /** Fetch `GET /api/profiles/{name}/memory`. */
    suspend fun fetchMemory(profileName: String): Result<ProfileMemoryResponse> =
        get(profileName, "memory", ProfileMemoryResponse.serializer())

    /**
     * Shared GET-and-parse path for all four endpoints. Centralizing here
     * keeps the error-mapping consistent (404 profile-not-found, 401
     * re-pair, 5xx server error, etc.) without four near-identical copies.
     */
    private suspend fun <T> get(
        profileName: String,
        segment: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): Result<T> = withContext(Dispatchers.IO) {
        val relayUrl = relayUrlProvider()?.trim().orEmpty()
        if (relayUrl.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("Relay URL not configured")
            )
        }

        val sessionToken = sessionTokenProvider()
        if (sessionToken.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Relay not paired — session token missing")
            )
        }

        val httpBase = relayUrl
            .replace(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replace(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
            .trimEnd('/')

        // Percent-encode the profile name for splicing into the path —
        // profile names are typically ASCII identifiers but nothing
        // structurally forbids spaces or non-ASCII.
        // URLEncoder encodes spaces as `+` which is wrong for paths; swap
        // back to `%20` after encoding.
        val encodedName = URLEncoder.encode(profileName, "UTF-8").replace("+", "%20")

        val url = try {
            "$httpBase/api/profiles/$encodedName/$segment".toHttpUrl()
        } catch (e: IllegalArgumentException) {
            return@withContext Result.failure(
                IOException("Invalid relay URL: ${e.message}")
            )
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $sessionToken")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        401, 403 -> "Unauthorized — re-pair with the relay"
                        404 -> "Profile '$profileName' not found on relay"
                        in 500..599 -> "Relay error (HTTP ${response.code})"
                        else -> "HTTP ${response.code}: ${response.message.ifBlank { "request failed" }}"
                    }
                    return@withContext Result.failure(IOException(reason))
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext Result.failure(
                        IOException("Relay returned an empty response")
                    )
                }

                val parsed = try {
                    json.decodeFromString(deserializer, body)
                } catch (e: SerializationException) {
                    return@withContext Result.failure(
                        IOException("Malformed response from relay: ${e.message ?: "parse error"}")
                    )
                }
                Result.success(parsed)
            }
        } catch (e: IOException) {
            Log.w(TAG, "$segment fetch failed for $profileName: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "$segment fetch unexpected error for $profileName: ${e.message}")
            Result.failure(e)
        }
    }
}

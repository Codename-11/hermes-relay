package com.hermesandroid.relay.network

import android.util.Log
import com.hermesandroid.relay.data.ProfileConfigResponse
import com.hermesandroid.relay.data.ProfileMemoryResponse
import com.hermesandroid.relay.data.ProfileSkillsResponse
import com.hermesandroid.relay.data.ProfileSoulResponse
import com.hermesandroid.relay.data.ProfileSoulUpdateResponse
import com.hermesandroid.relay.data.ProfileMemoryUpdateResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

        /**
         * Server-side upload ceiling for SOUL.md and memory entries
         * (1 MiB). Kept as a named constant so the matching wire limit
         * in the Python worker can be moved in lockstep. We use it to
         * translate a generic 413 into a friendlier error message.
         */
        private const val SOUL_MAX_BYTES: Long = 1024L * 1024L

        /**
         * JSON media type used for all PUT requests. Hoisted to a
         * constant so we don't re-parse it on every write.
         */
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
     * `PUT /api/profiles/{name}/soul` with body `{"content": "..."}`.
     *
     * Server-side contract:
     *   - 200: content written; returns [ProfileSoulUpdateResponse]
     *   - 404: profile not found
     *   - 413: body exceeds 1 MiB size limit
     *   - 401/403: unauthorized — re-pair
     *
     * The [content] may be any UTF-8 string including empty (to blank the
     * file) — the server does not enforce non-emptiness. A `null` content
     * would be a protocol violation; we send empty-string for an empty
     * SOUL.
     */
    suspend fun updateSoul(
        profileName: String,
        content: String,
    ): Result<ProfileSoulUpdateResponse> = withContext(Dispatchers.IO) {
        val bodyPayload = buildJsonObject { put("content", content) }
        val bodyJson = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            bodyPayload,
        )
        put(
            profileName = profileName,
            segment = "soul",
            body = bodyJson,
            deserializer = ProfileSoulUpdateResponse.serializer(),
            maxBytesHint = SOUL_MAX_BYTES,
        )
    }

    /**
     * `PUT /api/profiles/{name}/memory/{filename}` with body
     * `{"content": "..."}`.
     *
     * Filenames are validated both locally (by the caller — we expect
     * `.md` suffix, no traversal) and server-side. A bad filename yields
     * a 400 from the relay.
     *
     * Used for both creating a new memory entry (the relay writes the
     * file if missing) and updating an existing entry.
     */
    suspend fun updateMemoryEntry(
        profileName: String,
        filename: String,
        content: String,
    ): Result<ProfileMemoryUpdateResponse> = withContext(Dispatchers.IO) {
        val bodyPayload = buildJsonObject { put("content", content) }
        val bodyJson = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            bodyPayload,
        )
        val encodedFilename = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        put(
            profileName = profileName,
            segment = "memory/$encodedFilename",
            body = bodyJson,
            deserializer = ProfileMemoryUpdateResponse.serializer(),
            // Memory entries share the same 1MiB ceiling server-side —
            // no documented difference, so we report the same soft hint
            // in the error message.
            maxBytesHint = SOUL_MAX_BYTES,
        )
    }

    /**
     * Shared PUT-body-and-parse path for the two update endpoints
     * (SOUL + memory). Centralized so we reuse the same URL builder,
     * session-token plumbing, and error mapping. Kept separate from
     * [get] rather than generalized over the HTTP method because the
     * body/response semantics (413, 400 invalid filename) are specific
     * to the update side.
     */
    private suspend fun <T> put(
        profileName: String,
        segment: String,
        body: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        maxBytesHint: Long = SOUL_MAX_BYTES,
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
            .put(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $sessionToken")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        400 -> {
                            // Relay emits 400 for invalid filename or
                            // malformed JSON. Surface the response body
                            // when present so the user sees the specific
                            // validation error.
                            val bodyText = response.body?.string().orEmpty()
                            if (bodyText.isNotBlank()) {
                                "Invalid request: ${extractErrorDetail(bodyText)}"
                            } else {
                                "Invalid request"
                            }
                        }
                        401, 403 -> "Unauthorized — re-pair with the relay"
                        404 -> "Profile '$profileName' not found on relay"
                        413 -> "Content too large — max ${maxBytesHint / 1024} KiB"
                        in 500..599 -> "Relay error (HTTP ${response.code})"
                        else -> "HTTP ${response.code}: ${response.message.ifBlank { "request failed" }}"
                    }
                    return@withContext Result.failure(IOException(reason))
                }

                val bodyText = response.body?.string().orEmpty()
                if (bodyText.isBlank()) {
                    return@withContext Result.failure(
                        IOException("Relay returned an empty response")
                    )
                }

                val parsed = try {
                    json.decodeFromString(deserializer, bodyText)
                } catch (e: SerializationException) {
                    return@withContext Result.failure(
                        IOException("Malformed response from relay: ${e.message ?: "parse error"}")
                    )
                }
                Result.success(parsed)
            }
        } catch (e: IOException) {
            Log.w(TAG, "$segment write failed for $profileName: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "$segment write unexpected error for $profileName: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * `PUT /api/skills/toggle` with body `{"name": "...", "enabled": true/false}`.
     *
     * Current relay stubs this out — returns 501 with
     * `{"error": "skill_toggle_not_implemented", "detail": "..."}`. The
     * UI uses the distinctive 501 to show a "not supported on this
     * server" snackbar and ghost out the toggle. When the real
     * implementation lands server-side, this method needs no change.
     */
    suspend fun updateSkillToggle(
        skillName: String,
        enabled: Boolean,
    ): Result<SkillToggleResult> = withContext(Dispatchers.IO) {
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

        val url = try {
            "$httpBase/api/skills/toggle".toHttpUrl()
        } catch (e: IllegalArgumentException) {
            return@withContext Result.failure(
                IOException("Invalid relay URL: ${e.message}")
            )
        }

        val payload = buildJsonObject {
            put("name", skillName)
            put("enabled", enabled)
        }
        val bodyJson = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            payload,
        )

        val request = Request.Builder()
            .url(url)
            .put(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $sessionToken")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> Result.success(SkillToggleResult.Ok)
                    501 -> Result.success(SkillToggleResult.NotImplemented)
                    401, 403 -> Result.failure(
                        IOException("Unauthorized — re-pair with the relay")
                    )
                    else -> Result.failure(
                        IOException("Relay returned HTTP ${response.code}")
                    )
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "skill toggle failed for $skillName: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Capability probe for the skill-toggle endpoint — HEAD / OPTIONS
     * would be the ideal choice but we need to know specifically if the
     * server responds 501 vs 200, which is only visible on PUT. We
     * send a no-op PUT with `enabled = true` against a placeholder
     * skill name that the server treats as a probe ping — relays that
     * implement the endpoint accept it; stubbed relays return 501.
     *
     * In practice we don't want this probe to have side effects, so we
     * use the HTTP OPTIONS verb instead and treat a 501 response as
     * "not implemented" and any 2xx as "supported". The relay serves
     * OPTIONS via aiohttp's CORS handling by default.
     */
    suspend fun probeSkillToggleSupported(): Boolean = withContext(Dispatchers.IO) {
        val relayUrl = relayUrlProvider()?.trim().orEmpty()
        if (relayUrl.isEmpty()) return@withContext false
        val sessionToken = sessionTokenProvider() ?: return@withContext false

        val httpBase = relayUrl
            .replace(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replace(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
            .trimEnd('/')

        val url = try {
            "$httpBase/api/skills/toggle".toHttpUrl()
        } catch (_: IllegalArgumentException) {
            return@withContext false
        }

        // OPTIONS probe. Relays that don't mount the handler return 404
        // or the default 405 method-not-allowed; stubbed-implementation
        // relays return 501 from the PUT handler but allow OPTIONS.
        // A 2xx/3xx OPTIONS does NOT confirm PUT works (the server
        // might still 501 on the real call), so a successful OPTIONS
        // here means "worth trying". A 501 response on OPTIONS (rare)
        // is definitive "not supported".
        val request = Request.Builder()
            .url(url)
            .method("OPTIONS", null)
            .header("Authorization", "Bearer $sessionToken")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    501 -> false
                    404, 405 -> false
                    // 401/403 — we don't know; default to true (don't
                    // ghost the toggle over an auth problem, let the
                    // PUT fail and snackbar through the normal path).
                    401, 403 -> true
                    else -> response.isSuccessful
                }
            }
        } catch (_: IOException) {
            // Network / offline — assume supported; PUT will surface
            // the real failure.
            true
        }
    }

    /**
     * Result of a skill-toggle PUT. Kept as a small sealed class so
     * the caller can distinguish "server accepted it" from "server
     * answered 501 — not implemented yet" without inventing magic
     * error strings.
     */
    sealed class SkillToggleResult {
        data object Ok : SkillToggleResult()
        data object NotImplemented : SkillToggleResult()
    }

    /**
     * Best-effort pull of a `detail` or `error` string out of a relay
     * 400 body. Falls back to the first 120 chars of the payload when
     * the response isn't JSON-shaped.
     */
    private fun extractErrorDetail(body: String): String {
        return try {
            val obj = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
                ?: return body.take(120)
            val detail = (obj["detail"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val error = (obj["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            detail ?: error ?: body.take(120)
        } catch (_: Exception) {
            body.take(120)
        }
    }

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

package com.hermesandroid.relay.network

import android.util.Log
import com.hermesandroid.relay.auth.PairedDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * HTTP client for the Hermes relay media endpoint.
 *
 * The chat SSE stream can emit tool output containing a marker of the form
 *   `MEDIA:hermes-relay://<opaque-token>`
 * [ChatHandler][com.hermesandroid.relay.network.handlers.ChatHandler] parses
 * the marker, and [ChatViewModel][com.hermesandroid.relay.viewmodel.ChatViewModel]
 * calls [fetchMedia] to pull the actual bytes over plain HTTP(S). The relay
 * base URL is the WSS relay URL with `ws`/`wss` swapped for `http`/`https`.
 *
 * Authentication reuses the relay session token (same token used to authorize
 * the WSS channel). It's supplied lazily via [sessionTokenProvider] because
 * the token is backed by EncryptedSharedPreferences and requires a suspend
 * call on first access.
 *
 * This client deliberately does NOT wire into the existing [HermesApiClient]
 * — that one is scoped to the Hermes API server (chat, sessions, etc.) and
 * uses a separate auth token (the optional Hermes Bearer API key). The relay
 * and the API server are independent services even when they're co-located.
 */
class RelayHttpClient(
    private val okHttpClient: OkHttpClient,
    private val relayUrlProvider: () -> String?,
    private val sessionTokenProvider: suspend () -> String?
) {

    companion object {
        private const val TAG = "RelayHttpClient"
        private val sessionsJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }

    /**
     * The result of a successful [fetchMedia] call.
     *
     * @property contentType MIME type parsed from the `Content-Type` header,
     *           falling back to `application/octet-stream` when absent.
     * @property bytes raw response body.
     * @property fileName best-effort filename parsed from
     *           `Content-Disposition: inline; filename="..."`, or null.
     */
    data class FetchedMedia(
        val contentType: String,
        val bytes: ByteArray,
        val fileName: String?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FetchedMedia) return false
            return contentType == other.contentType &&
                bytes.contentEquals(other.bytes) &&
                fileName == other.fileName
        }

        override fun hashCode(): Int {
            var result = contentType.hashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + (fileName?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Fetch `GET /media/<token>` from the relay over HTTP(S). Returns a
     * [Result] — success carries a [FetchedMedia], failure wraps the
     * underlying exception with a human-readable message suitable for
     * surfacing in the attachment's `errorMessage` field.
     */
    suspend fun fetchMedia(token: String): Result<FetchedMedia> = withContext(Dispatchers.IO) {
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

        val url = "$httpBase/media/$token"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $sessionToken")
            .header("Accept", "*/*")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        401, 403 -> "Unauthorized — re-pair with the relay"
                        404 -> "File expired or not found on relay"
                        413 -> "File too large for relay"
                        in 500..599 -> "Relay error (HTTP ${response.code})"
                        else -> "HTTP ${response.code}: ${response.message.ifBlank { "request failed" }}"
                    }
                    return@withContext Result.failure(IOException(reason))
                }

                val contentType = response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.ifBlank { null }
                    ?: "application/octet-stream"

                val fileName = parseContentDispositionFilename(
                    response.header("Content-Disposition")
                )

                val body = response.body
                if (body == null) {
                    return@withContext Result.failure(IOException("Empty response body"))
                }
                val bytes = body.bytes()
                Result.success(FetchedMedia(contentType, bytes, fileName))
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchMedia failed for $token: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "fetchMedia unexpected error for $token: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Fetch `GET /media/by-path?path=<abs>` from the relay.
     *
     * Used when the agent's LLM freeform-emits a bare `MEDIA:/abs/path.ext`
     * marker in its response text (upstream `prompt_builder.py` explicitly
     * instructs the LLM to emit this form). The relay validates the path
     * against the same sandbox that `/media/register` uses — no token
     * round-trip is needed because the file is identified by its absolute
     * path directly.
     *
     * Auth is the same relay session token used by [fetchMedia]. If the
     * fetch fails for any reason the returned [Result] wraps an [IOException]
     * with a human-readable message suitable for [com.hermesandroid.relay.data.Attachment.errorMessage].
     *
     * @param path absolute path on the relay host — passed verbatim as a
     *        query parameter (OkHttp URL-encodes it correctly).
     * @param contentTypeHint optional MIME hint. If null, the server guesses
     *        from the file extension via Python's [mimetypes].
     */
    suspend fun fetchMediaByPath(
        path: String,
        contentTypeHint: String? = null,
    ): Result<FetchedMedia> = withContext(Dispatchers.IO) {
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

        // Build the URL via OkHttp's HttpUrl builder so query-param encoding
        // handles paths with slashes, spaces, and non-ASCII characters
        // correctly. A naive string-concat would double-encode or mis-encode.
        val url = try {
            "$httpBase/media/by-path".toHttpUrl().newBuilder()
                .addQueryParameter("path", path)
                .apply {
                    if (contentTypeHint != null) {
                        addQueryParameter("content_type", contentTypeHint)
                    }
                }
                .build()
        } catch (e: IllegalArgumentException) {
            return@withContext Result.failure(
                IOException("Invalid relay URL: ${e.message}")
            )
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $sessionToken")
            .header("Accept", "*/*")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        401 -> "Unauthorized — re-pair with the relay"
                        403 -> "Path not allowed by relay sandbox"
                        404 -> "File not found on relay: $path"
                        400 -> "Bad request — missing path"
                        in 500..599 -> "Relay error (HTTP ${response.code})"
                        else -> "HTTP ${response.code}: ${response.message.ifBlank { "request failed" }}"
                    }
                    return@withContext Result.failure(IOException(reason))
                }

                val contentType = response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.ifBlank { null }
                    ?: "application/octet-stream"

                val fileName = parseContentDispositionFilename(
                    response.header("Content-Disposition")
                )

                val body = response.body
                if (body == null) {
                    return@withContext Result.failure(IOException("Empty response body"))
                }
                val bytes = body.bytes()
                Result.success(FetchedMedia(contentType, bytes, fileName))
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchMediaByPath failed for $path: ${e.message}")
            Result.failure(IOException("Relay unreachable: ${e.message ?: "IO error"}"))
        } catch (e: Exception) {
            Log.w(TAG, "fetchMediaByPath unexpected error for $path: ${e.message}")
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    // Paired-device management (2026-04-11 security overhaul)
    // ------------------------------------------------------------------
    //
    // The sibling Python agent is adding two new relay endpoints:
    //   GET    /sessions                 → list all paired devices
    //   DELETE /sessions/{token_prefix}  → revoke a specific device
    //
    // Both are bearer-auth'd with the same session token we use for the
    // WSS channel. These methods are *defensive* — if the server hasn't
    // been updated yet, they'll come back with 404 and the UI renders an
    // empty list instead of crashing. See [PairedDevicesScreen] for the
    // consumer.

    /**
     * Fetch the list of currently-paired devices from the relay.
     *
     * @return [Result.success] with a list of [PairedDeviceInfo] (possibly
     *         empty), or [Result.failure] with a diagnostic exception. A 404
     *         is treated as "endpoint not implemented yet" → empty list.
     */
    suspend fun listSessions(): Result<List<PairedDeviceInfo>> = withContext(Dispatchers.IO) {
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

        val url = "$httpBase/sessions"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $sessionToken")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    // Server hasn't shipped the endpoint yet — degrade to
                    // empty list so the UI can render "No paired devices"
                    // without exploding.
                    Log.i(TAG, "listSessions: relay returned 404, endpoint not implemented")
                    return@withContext Result.success(emptyList())
                }
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        401, 403 -> "Unauthorized — re-pair with the relay"
                        in 500..599 -> "Relay error (HTTP ${response.code})"
                        else -> "HTTP ${response.code}: ${response.message.ifBlank { "request failed" }}"
                    }
                    return@withContext Result.failure(IOException(reason))
                }
                val body = response.body?.string().orEmpty()
                val devices = sessionsJson.decodeFromString(
                    ListSerializer(PairedDeviceInfo.serializer()),
                    body
                )
                Result.success(devices)
            }
        } catch (e: IOException) {
            Log.w(TAG, "listSessions failed: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "listSessions parse error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Revoke a paired device by its token prefix.
     *
     * Token prefixes are the first N characters of the session token —
     * enough to uniquely identify a device without transmitting the full
     * token. The server looks up and deletes the matching record.
     *
     * Revoking the CURRENT device (i.e. the phone making the request) is
     * valid — the caller should follow up by wiping local state and
     * redirecting to the pairing screen.
     */
    suspend fun revokeSession(tokenPrefix: String): Result<Unit> = withContext(Dispatchers.IO) {
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
            "$httpBase/sessions/".toHttpUrl().newBuilder()
                .addPathSegment(tokenPrefix)
                .build()
        } catch (e: IllegalArgumentException) {
            return@withContext Result.failure(
                IOException("Invalid relay URL: ${e.message}")
            )
        }

        val request = Request.Builder()
            .url(url)
            .delete()
            .header("Authorization", "Bearer $sessionToken")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    // Already gone — treat as success so the UI can just
                    // drop the row on the next refresh.
                    return@withContext Result.success(Unit)
                }
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        401, 403 -> "Unauthorized — re-pair with the relay"
                        in 500..599 -> "Relay error (HTTP ${response.code})"
                        else -> "HTTP ${response.code}: ${response.message.ifBlank { "request failed" }}"
                    }
                    return@withContext Result.failure(IOException(reason))
                }
                Result.success(Unit)
            }
        } catch (e: IOException) {
            Log.w(TAG, "revokeSession failed: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "revokeSession unexpected error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Extend (or update) a paired device's session TTL and/or per-channel
     * grants.
     *
     * Backs the "Extend" button on the Paired Devices card. At least one
     * of [ttlSeconds] / [grants] must be non-null — both null is an
     * immediate `Result.failure` without hitting the network.
     *
     * * [ttlSeconds] — new session lifetime in seconds, `0` means never
     *   expire. When provided, the server restarts the clock from now
     *   (i.e. "extend by 30 days" = "30 days from now", not "add 30 days
     *   to the existing expiry"). `null` leaves session expiry alone.
     * * [grants] — seconds-from-now per channel. When provided, grants
     *   are re-materialized and clamped to the (possibly new) session
     *   lifetime. `null` leaves grants alone, though they'll be re-clamped
     *   server-side if [ttlSeconds] was provided and shortens the session.
     *
     * Returns [Result.success] on HTTP 200. 404 is a hard failure here
     * (unlike revoke — "already gone" is a surprise when you're trying to
     * extend an active session).
     */
    suspend fun extendSession(
        tokenPrefix: String,
        ttlSeconds: Long? = null,
        grants: Map<String, Long>? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (ttlSeconds == null && grants == null) {
            return@withContext Result.failure(
                IllegalArgumentException("extendSession requires at least one of ttlSeconds or grants")
            )
        }

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
            "$httpBase/sessions/".toHttpUrl().newBuilder()
                .addPathSegment(tokenPrefix)
                .build()
        } catch (e: IllegalArgumentException) {
            return@withContext Result.failure(
                IOException("Invalid relay URL: ${e.message}")
            )
        }

        // Hand-rolling the JSON body to keep the serializer tree thin —
        // kotlinx.serialization's JsonObjectBuilder would pull in another
        // dependency branch. The body is 1-2 fields so the hand form is
        // trivial and auditable.
        val bodyJson = buildString {
            append('{')
            var first = true
            if (ttlSeconds != null) {
                append("\"ttl_seconds\":").append(ttlSeconds)
                first = false
            }
            if (grants != null) {
                if (!first) append(',')
                append("\"grants\":{")
                var g = true
                for ((k, v) in grants) {
                    if (!g) append(',')
                    append('"').append(k.replace("\"", "\\\"")).append("\":").append(v)
                    g = false
                }
                append('}')
            }
            append('}')
        }

        val request = Request.Builder()
            .url(url)
            .patch(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $sessionToken")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        400 -> "Invalid extend request (check TTL/grants)"
                        401, 403 -> "Unauthorized — re-pair with the relay"
                        404 -> "Session not found — it may have expired"
                        409 -> "Ambiguous token prefix — retry with more chars"
                        in 500..599 -> "Relay error (HTTP ${response.code})"
                        else -> "HTTP ${response.code}: ${response.message.ifBlank { "request failed" }}"
                    }
                    return@withContext Result.failure(IOException(reason))
                }
                Result.success(Unit)
            }
        } catch (e: IOException) {
            Log.w(TAG, "extendSession failed: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "extendSession unexpected error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Extract `filename` from a `Content-Disposition` header. Handles the
     * common `inline; filename="foo.png"` and `attachment; filename=foo.png`
     * shapes. RFC 5987 `filename*` encoding is not supported — if the relay
     * ever needs non-ASCII names it'll need extending.
     */
    private fun parseContentDispositionFilename(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val match = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header)
        return match?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }
}

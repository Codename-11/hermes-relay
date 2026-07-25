package com.hermesandroid.relay.network.upstream

import android.content.Context
import com.hermesandroid.relay.auth.SessionTokenStore
import com.hermesandroid.relay.auth.SecureStoreCache
import com.hermesandroid.relay.auth.buildRawTokenStore
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.toByteString

private const val NATIVE_PKCE_FLOW = "native_pkce"
private const val CALLBACK_PATH = "/callback"
private const val TOKEN_KEY = "dashboard_native_tokens_json"
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

@Serializable
data class NativeDashboardTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_at") val expiresAt: Long = 0L,
    val provider: String = "",
    @SerialName("user_id") val userId: String = "",
)

interface NativeDashboardTokenStore {
    fun load(): NativeDashboardTokens?
    fun save(tokens: NativeDashboardTokens)
    fun clear()
}

class EncryptedNativeDashboardTokenStore(
    context: Context,
    tokenStoreKey: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : NativeDashboardTokenStore {
    private val store: SessionTokenStore = SecureStoreCache.getOrBuild(tokenStoreKey) {
        buildRawTokenStore(context.applicationContext, tokenStoreKey)
    }

    override fun load(): NativeDashboardTokens? =
        store.getString(TOKEN_KEY)?.let { raw ->
            runCatching { json.decodeFromString<NativeDashboardTokens>(raw) }.getOrNull()
        }

    override fun save(tokens: NativeDashboardTokens) {
        store.putString(TOKEN_KEY, json.encodeToString(tokens))
    }

    override fun clear() {
        store.remove(TOKEN_KEY)
    }
}

/**
 * Ephemeral authorization state. Keep this object in the sign-in coroutine:
 * its verifier and CSRF state must never be persisted, logged, or copied into
 * Compose/SavedState UI state.
 */
class NativeDashboardAuthorization internal constructor(
    val authorizationUrl: String,
    internal val verifier: String,
    internal val state: String,
)

class NativeDashboardAuthClient(
    baseUrl: String,
    private val tokenStore: NativeDashboardTokenStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val random: SecureRandom = SecureRandom(),
) {
    private val baseUrl = baseUrl.trim().trimEnd('/')

    fun supportsNativePkce(status: DashboardStatus): Boolean =
        NATIVE_PKCE_FLOW in status.authFlows

    fun beginAuthorization(
        redirectUri: String,
        provider: String? = null,
    ): NativeDashboardAuthorization {
        requireStrictLoopbackRedirect(redirectUri)
        val verifier = randomBytes(32).base64Url()
        val challenge = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
            .toByteString()
            .base64Url()
        val state = randomBytes(24).base64Url()
        val root = "$baseUrl/auth/native/authorize".toHttpUrlOrNull()
            ?: throw IOException("Dashboard URL is not a valid http(s) address")
        val url = root.newBuilder()
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", state)
            .apply { provider?.takeIf(String::isNotBlank)?.let { addQueryParameter("provider", it) } }
            .build()
            .toString()
        return NativeDashboardAuthorization(url, verifier, state)
    }

    fun exchangeCallback(
        authorization: NativeDashboardAuthorization,
        callbackTarget: String,
    ): NativeDashboardTokens {
        val callback = callbackTarget.toHttpUrlOrNull()
            ?: "http://127.0.0.1$callbackTarget".toHttpUrlOrNull()
            ?: throw IOException("Native sign-in callback was malformed")
        if (callback.host != "127.0.0.1" || callback.encodedPath != CALLBACK_PATH) {
            throw IOException("Native sign-in callback did not use the expected loopback path")
        }
        callback.queryParameter("error")?.let { throw IOException("Gateway rejected native sign-in") }
        val code = callback.queryParameter("code")
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("Native sign-in callback did not include an authorization code")
        if (callback.queryParameter("state") != authorization.state) {
            throw IOException("Native sign-in callback state did not match")
        }
        val payload = NativeTokenExchange(code = code, codeVerifier = authorization.verifier)
        return postTokens(
            path = "/auth/native/token",
            payload = json.encodeToString(payload),
            clearOnAuthFailure = false,
        )
    }

    fun refresh(tokens: NativeDashboardTokens = tokenStore.load()
        ?: throw IOException("No native dashboard session is stored")): NativeDashboardTokens {
        if (tokens.refreshToken.isBlank()) {
            tokenStore.clear()
            throw IOException("Native dashboard session cannot be refreshed")
        }
        val payload = NativeTokenRefresh(tokens.refreshToken, tokens.provider)
        return postTokens(
            path = "/auth/native/refresh",
            payload = json.encodeToString(payload),
            clearOnAuthFailure = true,
        )
    }

    private fun postTokens(
        path: String,
        payload: String,
        clearOnAuthFailure: Boolean,
    ): NativeDashboardTokens {
        val url = "$baseUrl$path".toHttpUrlOrNull()
            ?: throw IOException("Dashboard URL is not a valid http(s) address")
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val tokens = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (clearOnAuthFailure && (response.code == 400 || response.code == 401)) {
                    tokenStore.clear()
                }
                throw IOException("Dashboard native authentication failed (HTTP ${response.code})")
            }
            val body = response.body?.string().orEmpty()
            runCatching { json.decodeFromString<NativeDashboardTokens>(body) }
                .getOrElse { throw IOException("Dashboard token response was malformed", it) }
                .also {
                    if (it.accessToken.isBlank()) {
                        throw IOException("Dashboard token response did not include an access token")
                    }
                }
        }
        tokenStore.save(tokens)
        return tokens
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes).toByteString()

    companion object {
        fun requireStrictLoopbackRedirect(redirectUri: String) {
            val url = redirectUri.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Native redirect must be a valid loopback HTTP URL")
            require(url.scheme == "http" && url.host == "127.0.0.1") {
                "Native redirect must use the 127.0.0.1 loopback address"
            }
            require(url.port in 1..65535 && url.encodedPath == CALLBACK_PATH && url.query == null) {
                "Native redirect must use an ephemeral port and the exact /callback path"
            }
        }
    }
}

/**
 * Adds the native bearer to dashboard REST calls and rotates it before expiry
 * or after one 401. Refresh requests use a separate bare client, so neither a
 * stale bearer nor the authenticator can recurse into token rotation.
 */
class DashboardBearerAuth(
    baseUrl: String,
    private val tokenStore: NativeDashboardTokenStore,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) : Interceptor, Authenticator {
    private val authClient = NativeDashboardAuthClient(baseUrl, tokenStore)
    private val refreshLock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val tokens = usableTokens(forceRefresh = false)
        val request = tokens?.let {
            chain.request().newBuilder()
                .header("Authorization", "Bearer ${it.accessToken}")
                .build()
        } ?: chain.request()
        return chain.proceed(request)
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val previous = response.request.header("Authorization") ?: return null
        val tokens = usableTokens(forceRefresh = true) ?: return null
        val next = "Bearer ${tokens.accessToken}"
        if (next == previous) return null
        return response.request.newBuilder().header("Authorization", next).build()
    }

    private fun usableTokens(forceRefresh: Boolean): NativeDashboardTokens? = synchronized(refreshLock) {
        val current = tokenStore.load() ?: return@synchronized null
        val nearExpiry = current.expiresAt <= 0L || clockSeconds() >= current.expiresAt - 60L
        if (!forceRefresh && !nearExpiry) return@synchronized current
        runCatching { authClient.refresh(current) }.getOrNull()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count += 1
            prior = prior.priorResponse
        }
        return count
    }
}

@Serializable
private data class NativeTokenExchange(
    val code: String,
    @SerialName("code_verifier") val codeVerifier: String,
)

@Serializable
private data class NativeTokenRefresh(
    @SerialName("refresh_token") val refreshToken: String,
    val provider: String,
)

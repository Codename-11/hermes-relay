package com.hermesandroid.relay.network.upstream

import android.content.Context
import com.hermesandroid.relay.auth.SessionTokenStore
import com.hermesandroid.relay.auth.SecureStoreCache
import com.hermesandroid.relay.auth.buildRawTokenStore
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Dns
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
private const val NATIVE_AUTH_DNS_RETRY_BACKOFF_MILLIS = 75L
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
    /** Stable, non-secret identity used to serialize refresh-token rotation. */
    val coordinationKey: String
    fun load(): NativeDashboardTokens?
    fun save(tokens: NativeDashboardTokens)
    fun clear()
}

internal fun clearNativeDashboardTokens(store: NativeDashboardTokenStore) {
    NativeTokenRefreshCoordinator.clear(store)
}

class EncryptedNativeDashboardTokenStore(
    context: Context,
    tokenStoreKey: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : NativeDashboardTokenStore {
    override val coordinationKey: String = tokenStoreKey
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
    internal val generation: Long,
)

class NativeDashboardAuthClient(
    baseUrl: String,
    private val tokenStore: NativeDashboardTokenStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(RetryingNativeAuthDns())
        .retryOnConnectionFailure(false)
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
        // RFC 7636 uses unpadded Base64URL. Okio's base64Url() preserves
        // trailing "=", which makes Hermes' standards-compliant S256
        // comparison fail even though both sides hashed the same bytes.
        val verifier = randomBytes(32).base64Url().trimEnd('=')
        val challenge = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
            .toByteString()
            .base64Url()
            .trimEnd('=')
        val state = randomBytes(24).base64Url()
        val authorizationBaseUrl = resolveAuthorizationBaseUrl(provider)
        val root = "$authorizationBaseUrl/auth/native/authorize".toHttpUrlOrNull()
            ?: throw IOException("Dashboard URL is not a valid http(s) address")
        val url = root.newBuilder()
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", state)
            // Match the official Desktop client for Nous-hosted gateways: the
            // gateway selects its single native-eligible provider. The provider
            // name advertised to UI clients is presentation/configuration data,
            // not a stable native-broker identifier. Other providers retain the
            // explicit selector for direct client use and tests.
            .apply {
                provider
                    ?.takeIf { it.isNotBlank() && !it.equals("nous", ignoreCase = true) }
                    ?.let { addQueryParameter("provider", it) }
            }
            .build()
            .toString()
        val generation = NativeTokenRefreshCoordinator.beginAuthorization(
            tokenStore.coordinationKey,
        )
        return NativeDashboardAuthorization(url, verifier, state, generation)
    }

    /**
     * A private-route dashboard may be configured with a canonical HTTPS
     * callback origin for its provider. Starting the browser on the private
     * origin would scope Hermes' temporary PKCE cookie to the wrong host, so
     * discover the provider's declared callback and start native auth there.
     * Token exchange still uses [baseUrl], keeping the resulting bearer bound
     * to the active connection route.
     */
    private fun resolveAuthorizationBaseUrl(provider: String?): String {
        val configured = baseUrl.toHttpUrlOrNull() ?: return baseUrl
        if (
            !provider.equals("nous", ignoreCase = true) ||
            configured.scheme != "http" ||
            !isPrivateNetworkLiteral(configured.host)
        ) {
            return baseUrl
        }
        val loginUrl = configured.newBuilder()
            .addPathSegments("auth/login")
            .addQueryParameter("provider", provider)
            .addQueryParameter("next", "/")
            .build()
        val discoveryClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val location = discoveryClient.newCall(
            Request.Builder().url(loginUrl).get().build(),
        ).execute().use { response ->
            if (response.code !in 300..399) null else response.header("Location")
        }
        return canonicalDashboardBaseFromNousRedirect(location)
            ?: throw IOException("Dashboard did not advertise a secure Nous callback origin")
    }

    fun exchangeCallback(
        authorization: NativeDashboardAuthorization,
        callbackTarget: String,
        commitAllowed: () -> Boolean = { true },
    ): NativeDashboardTokens {
        val callback = callbackTarget.toHttpUrlOrNull()
            ?: "http://127.0.0.1$callbackTarget".toHttpUrlOrNull()
            ?: throw NativeDashboardCallbackException("Native sign-in callback was malformed")
        if (callback.host != "127.0.0.1" || callback.encodedPath != CALLBACK_PATH) {
            throw NativeDashboardCallbackException(
                "Native sign-in callback did not use the expected loopback path",
            )
        }
        if (callback.queryParameter("state") != authorization.state) {
            throw NativeDashboardCallbackException("Native sign-in callback state did not match")
        }
        callback.queryParameter("error")?.let {
            throw NativeDashboardCallbackException(
                message = "Gateway rejected native sign-in",
                retryable = false,
            )
        }
        val code = callback.queryParameter("code")
            ?.takeIf(String::isNotBlank)
            ?: throw NativeDashboardCallbackException(
                "Native sign-in callback did not include an authorization code",
            )
        val payload = NativeTokenExchange(code = code, codeVerifier = authorization.verifier)
        return postTokens(
            path = "/auth/native/token",
            payload = json.encodeToString(payload),
            clearOnAuthFailure = false,
            expectedGeneration = authorization.generation,
            commitAllowed = commitAllowed,
        )
    }

    internal fun cancelAuthorization(authorization: NativeDashboardAuthorization) {
        NativeTokenRefreshCoordinator.cancelAuthorization(
            tokenStore.coordinationKey,
            authorization.generation,
        )
    }

    fun clearStoredSession() {
        clearNativeDashboardTokens(tokenStore)
    }

    fun refresh(tokens: NativeDashboardTokens? = null): NativeDashboardTokens {
        return synchronized(NativeTokenRefreshCoordinator.lockFor(tokenStore.coordinationKey)) {
            val current = tokenStore.load()
                ?: tokens
                ?: throw IOException("No native dashboard session is stored")
            // A sibling client may already have rotated the single-use refresh
            // token while this caller was waiting. Adopt that winner instead
            // of replaying the stale token.
            if (tokens != null && current != tokens) return@synchronized current
            if (current.refreshToken.isBlank()) {
                clearIfUnchanged(current)
                throw IOException("Native dashboard session cannot be refreshed")
            }
            val payload = NativeTokenRefresh(current.refreshToken, current.provider)
            val generation = NativeTokenRefreshCoordinator.currentGeneration(
                tokenStore.coordinationKey,
            )
            try {
                postTokens(
                    path = "/auth/native/refresh",
                    payload = json.encodeToString(payload),
                    clearOnAuthFailure = false,
                    expectedGeneration = generation,
                )
            } catch (error: NativeDashboardAuthHttpException) {
                if (error.statusCode == 400 || error.statusCode == 401) {
                    clearIfUnchanged(current)
                }
                throw error
            }
        }
    }

    private fun postTokens(
        path: String,
        payload: String,
        clearOnAuthFailure: Boolean,
        expectedGeneration: Long,
        commitAllowed: () -> Boolean = { true },
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
                throw NativeDashboardAuthHttpException(response.code)
            }
            val body = response.body.string()
            runCatching { json.decodeFromString<NativeDashboardTokens>(body) }
                .getOrElse {
                    throw NativeDashboardTokenShapeException(
                        "Dashboard token response was malformed",
                        it,
                    )
                }
                .also {
                    if (it.accessToken.isBlank()) {
                        throw NativeDashboardTokenShapeException(
                            "Dashboard token response did not include an access token",
                        )
                    }
                }
        }
        synchronized(NativeTokenRefreshCoordinator.lockFor(tokenStore.coordinationKey)) {
            if (!commitAllowed() ||
                NativeTokenRefreshCoordinator.currentGeneration(tokenStore.coordinationKey) !=
                expectedGeneration
            ) {
                throw NativeDashboardInactiveAuthorizationException()
            }
            tokenStore.save(tokens)
        }
        return tokens
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes).toByteString()

    private fun clearIfUnchanged(expected: NativeDashboardTokens) {
        if (tokenStore.load() == expected) tokenStore.clear()
    }

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
 * Retries only the name lookup that precedes a native-auth request. The HTTP
 * call itself remains single-shot, so a one-time authorization code is never
 * replayed after the server may have consumed it.
 */
internal class RetryingNativeAuthDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val backoffMillis: Long = NATIVE_AUTH_DNS_RETRY_BACKOFF_MILLIS,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val firstFailure = try {
            return delegate.lookup(hostname)
        } catch (error: UnknownHostException) {
            error
        }

        if (backoffMillis > 0L) {
            try {
                sleeper(backoffMillis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                firstFailure.addSuppressed(interrupted)
                throw firstFailure
            }
        }

        return try {
            delegate.lookup(hostname)
        } catch (secondFailure: UnknownHostException) {
            secondFailure.addSuppressed(firstFailure)
            throw secondFailure
        }
    }
}

internal class NativeDashboardCallbackException(
    message: String,
    val retryable: Boolean = true,
) : IOException(message)

internal fun isNativeDashboardTransportEligible(baseUrl: String): Boolean {
    val url = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return false
    return url.scheme == "https" ||
        (
            url.scheme == "http" &&
                (url.host == "127.0.0.1" || isPrivateNetworkLiteral(url.host))
            )
}

/**
 * Hermes already permits explicitly configured HTTP dashboard sessions on
 * local routes. The brokered flow is no less protected than that cookie flow,
 * but remains unavailable to arbitrary cleartext Internet hosts.
 */
private fun isPrivateNetworkLiteral(host: String): Boolean {
    val octets = host.split('.').mapNotNull(String::toIntOrNull)
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    val first = octets[0]
    val second = octets[1]
    return first == 10 ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168) ||
        (first == 100 && second in 64..127)
}

internal fun canonicalDashboardBaseFromNousRedirect(location: String?): String? {
    val providerUrl = location?.toHttpUrlOrNull() ?: return null
    if (
        providerUrl.scheme != "https" ||
        !providerUrl.host.equals("portal.nousresearch.com", ignoreCase = true)
    ) {
        return null
    }
    val callback = providerUrl.queryParameter("redirect_uri")
        ?.toHttpUrlOrNull()
        ?: return null
    if (callback.scheme != "https") return null
    val callbackSuffix = "/auth/callback"
    if (!callback.encodedPath.endsWith(callbackSuffix)) return null
    val basePath = callback.encodedPath
        .removeSuffix(callbackSuffix)
        .ifBlank { "/" }
    return callback.newBuilder()
        .encodedPath(basePath)
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
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

    override fun intercept(chain: Interceptor.Chain): Response {
        val tokens = usableTokens(forceRefresh = false, failedAccessToken = null)
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
        val failedAccessToken = previous.removePrefix("Bearer ").takeIf { it != previous }
        val tokens = usableTokens(
            forceRefresh = true,
            failedAccessToken = failedAccessToken,
        ) ?: return null
        val next = "Bearer ${tokens.accessToken}"
        if (next == previous) return null
        return response.request.newBuilder().header("Authorization", next).build()
    }

    private fun usableTokens(
        forceRefresh: Boolean,
        failedAccessToken: String?,
    ): NativeDashboardTokens? =
        synchronized(NativeTokenRefreshCoordinator.lockFor(tokenStore.coordinationKey)) {
            val current = tokenStore.load() ?: return@synchronized null
            // A request can receive its 401 after another client already
            // rotated the token. Retry with the winner; do not rotate again.
            if (failedAccessToken != null && current.accessToken != failedAccessToken) {
                return@synchronized current
            }
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

internal class NativeDashboardAuthHttpException(
    val statusCode: Int,
) : IOException("Dashboard native authentication failed (HTTP $statusCode)")

internal class NativeDashboardTokenShapeException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class NativeDashboardInactiveAuthorizationException :
    IOException("Dashboard sign-in is no longer active")

internal fun nativeDashboardSignInFailureStage(error: Throwable): String {
    error.firstCauseOfType<NativeDashboardCallbackException>()?.let { return "callback_error" }
    error.firstCauseOfType<NativeDashboardAuthHttpException>()?.let {
        return "token_http_${it.statusCode}"
    }
    if (error.firstCauseOfType<NativeDashboardTokenShapeException>() != null) return "token_shape"
    if (error.firstCauseOfType<NativeDashboardInactiveAuthorizationException>() != null) {
        return "inactive_generation"
    }
    if (error.firstCauseOfType<InterruptedIOException>() != null) return "token_transport_timeout"
    if (error.firstCauseOfType<UnknownHostException>() != null) return "token_transport_dns"
    if (error.firstCauseOfType<ConnectException>() != null ||
        error.firstCauseOfType<NoRouteToHostException>() != null
    ) {
        return "token_transport_connect"
    }
    if (error.firstCauseOfType<SSLException>() != null) return "token_transport_tls"
    if (error.firstCauseOfType<SocketException>() != null ||
        error.firstCauseOfType<EOFException>() != null
    ) {
        return "token_transport_socket"
    }
    return if (error.firstCauseOfType<IOException>() != null) "token_transport" else "token_store"
}

internal fun nativeDashboardSignInFailureDiagnostic(error: Throwable): String =
    "dashboard_native_pkce_failed stage=${nativeDashboardSignInFailureStage(error)}"

private inline fun <reified T : Throwable> Throwable.firstCauseOfType(): T? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private object NativeTokenRefreshCoordinator {
    private val locks = ConcurrentHashMap<String, Any>()
    private val generations = ConcurrentHashMap<String, Long>()

    fun lockFor(key: String): Any = locks.computeIfAbsent(key) { Any() }

    fun currentGeneration(key: String): Long =
        synchronized(lockFor(key)) { generations[key] ?: 0L }

    fun beginAuthorization(key: String): Long =
        synchronized(lockFor(key)) {
            (generations[key] ?: 0L).plus(1L).also { generations[key] = it }
        }

    fun cancelAuthorization(key: String, expectedGeneration: Long) {
        synchronized(lockFor(key)) {
            if ((generations[key] ?: 0L) == expectedGeneration) {
                generations[key] = expectedGeneration + 1L
            }
        }
    }

    fun clear(store: NativeDashboardTokenStore) {
        synchronized(lockFor(store.coordinationKey)) {
            generations[store.coordinationKey] =
                (generations[store.coordinationKey] ?: 0L) + 1L
            store.clear()
        }
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

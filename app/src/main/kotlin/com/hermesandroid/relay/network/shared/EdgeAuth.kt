package com.hermesandroid.relay.network.shared

import android.content.Context
import com.hermesandroid.relay.auth.SecureStoreCache
import com.hermesandroid.relay.auth.SessionTokenStore
import com.hermesandroid.relay.auth.buildRawTokenStore
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Credential pair for an HTTP gate that sits **in front of** the Hermes host.
 *
 * The concrete deployment this exists for is Cloudflare Access with a service
 * token: the edge answers an unauthenticated request with a 302 to the login
 * page, so nothing in the app (probe, sign-in, `/api/ws` ticket, Manage) can
 * get through until the token headers ride every request. A VPN/overlay route
 * avoids the gate entirely and needs none of this.
 *
 * The pair is the operator's own secret. It is stored per connection in the
 * same Keystore-backed store as the session token, and it is only ever applied
 * to the exact origin it was entered for — [EdgeAuthInterceptor] compares
 * scheme, host and port, so a redirect or a second route on the same server
 * never receives it.
 */
data class EdgeAuthCredential(
    val clientId: String,
    val clientSecret: String,
) {
    /** Both halves must be present; a half-filled pair is treated as unconfigured. */
    val isComplete: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}

/** Header names used by Cloudflare Access service tokens. */
object EdgeAuthHeaders {
    const val CLIENT_ID: String = "CF-Access-Client-Id"
    const val CLIENT_SECRET: String = "CF-Access-Client-Secret"
}

private const val KEY_CLIENT_ID = "edge_auth_client_id"
private const val KEY_CLIENT_SECRET = "edge_auth_client_secret"

/**
 * Per-connection encrypted storage for [EdgeAuthCredential].
 *
 * Reuses the connection's existing [SessionTokenStore] keyset (same file, same
 * StrongBox/TEE-backed master key) so no second Keystore build is introduced on
 * cold start.
 */
class EncryptedEdgeAuthStore(
    context: Context,
    tokenStoreKey: String,
) {
    private val store: SessionTokenStore = SecureStoreCache.getOrBuild(tokenStoreKey) {
        buildRawTokenStore(context.applicationContext, tokenStoreKey)
    }

    // Checked on every request the connection makes, so the decrypt happens
    // once per process instead of once per call. save()/clear() refresh it.
    @Volatile
    private var cached: EdgeAuthCredential? = null
    @Volatile
    private var cacheLoaded: Boolean = false

    fun load(): EdgeAuthCredential? {
        if (cacheLoaded) return cached
        val clientId = store.getString(KEY_CLIENT_ID).orEmpty().trim()
        val clientSecret = store.getString(KEY_CLIENT_SECRET).orEmpty().trim()
        val credential = EdgeAuthCredential(clientId, clientSecret).takeIf { it.isComplete }
        cached = credential
        cacheLoaded = true
        return credential
    }

    fun save(credential: EdgeAuthCredential) {
        store.putString(KEY_CLIENT_ID, credential.clientId.trim())
        store.putString(KEY_CLIENT_SECRET, credential.clientSecret.trim())
        cached = credential
        cacheLoaded = true
    }

    /** Removes only the edge credential; the connection's other secrets stay put. */
    fun clear() {
        store.remove(KEY_CLIENT_ID)
        store.remove(KEY_CLIENT_SECRET)
        cached = null
        cacheLoaded = true
    }
}

/**
 * True when [candidate] targets exactly [scope]: same scheme, same host, same
 * port. Deliberately not a suffix match — `hermes.example.com` must not receive
 * a token entered for `example.com`.
 */
fun sameEdgeAuthScope(candidate: HttpUrl, scope: HttpUrl): Boolean =
    candidate.scheme.equals(scope.scheme, ignoreCase = true) &&
        candidate.host.equals(scope.host, ignoreCase = true) &&
        candidate.port == scope.port

/**
 * Adds the edge credential to requests the [lookup] claims, and does nothing at
 * all for every other request.
 *
 * Installed on the connection's shared OkHttp client, so it covers the status
 * probe, dashboard REST, file/media fetches and — because the gateway reuses
 * that client — the `/api/ws` upgrade. Malformed values are skipped rather than
 * thrown: a bad credential should surface as the edge's own 302, not as a crash
 * inside an unrelated request.
 */
class EdgeAuthInterceptor(
    private val lookup: (HttpUrl) -> EdgeAuthCredential?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val credential = runCatching { lookup(request.url) }.getOrNull()
        if (credential == null || !credential.isComplete) return chain.proceed(request)
        val authorized = runCatching {
            request.newBuilder()
                .credentialHeader(EdgeAuthHeaders.CLIENT_ID, credential.clientId, "Access client ID")
                .credentialHeader(EdgeAuthHeaders.CLIENT_SECRET, credential.clientSecret, "Access client secret")
                .build()
        }.getOrNull() ?: request
        return chain.proceed(authorized)
    }
}

package com.hermesandroid.relay.network

import android.util.Log
import com.hermesandroid.relay.auth.CertPinStore
import com.hermesandroid.relay.network.models.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting
}

class ConnectionManager(
    private val multiplexer: ChannelMultiplexer,
    /**
     * Optional TOFU certificate pin store. When provided, ConnectionManager
     * builds its [OkHttpClient] with a snapshot of the current pins each
     * connect — so subsequent wss connects refuse mismatched certs. On a
     * successful `onOpen` we call back to record the peer cert fingerprint
     * for the first-time TOFU case. See [CertPinStore] for the contract.
     *
     * Nullable for backwards-compat with unit tests that construct a bare
     * ConnectionManager without auth wiring.
     */
    private val certPinStore: CertPinStore? = null,
    /**
     * Defense-in-depth guard for the internal auto-reconnect loop. Called
     * from [scheduleReconnect] both before scheduling the delayed retry and
     * after the backoff delay expires — if it returns `false`, the retry is
     * silently dropped.
     *
     * The canonical wiring is `{ authManager.hasPairContext }` so the phone
     * never fires a reconnect with no session token and no pending pair
     * code. Without this gate, ConnectionManager's internal retry loop
     * completely bypasses [ConnectionViewModel.connectRelay]'s gate (the
     * primary gate introduced in the 2026-04-11 "Option B" commit), and
     * stale credentials get fed into the auth envelope after clearSession
     * wipes state → relay returns "Invalid pairing code or session token"
     * → rate limiter blocks the IP after 5 attempts → user can't re-pair.
     *
     * Defaults to always-allow for tests and legacy call sites. Production
     * wiring passes the AuthManager gate from [ConnectionViewModel].
     */
    private val reconnectGate: () -> Boolean = { true }
) {
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
        // Swap in the current pin snapshot on every connect. We DON'T hold a
        // long-lived OkHttpClient with a stale pinner — otherwise a re-pair
        // that wipes a pin would still be subject to the pre-wipe rules.
        certPinStore?.let { store ->
            try {
                builder.certificatePinner(store.buildPinnerSnapshot())
            } catch (e: Exception) {
                Log.w(TAG, "CertificatePinner build failed: ${e.message}")
                builder.certificatePinner(CertificatePinner.DEFAULT)
            }
        }
        return builder.build()
    }

    @Volatile
    private var client: OkHttpClient = buildClient()

    private var webSocket: WebSocket? = null
    private var serverUrl: String? = null
    private var reconnectAttempt = 0
    private var shouldReconnect = true

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** When true, allows ws:// connections for local dev/testing. */
    private val _insecureMode = MutableStateFlow(false)
    val insecureMode: StateFlow<Boolean> = _insecureMode.asStateFlow()

    /** True when the current connection is using ws:// instead of wss:// */
    private val _isInsecureConnection = MutableStateFlow(false)
    val isInsecureConnection: StateFlow<Boolean> = _isInsecureConnection.asStateFlow()

    companion object {
        private const val TAG = "ConnectionManager"
        private const val MAX_BACKOFF_MS = 30_000L
        private const val BASE_BACKOFF_MS = 1_000L
    }

    fun setInsecureMode(enabled: Boolean) {
        _insecureMode.value = enabled
        if (enabled) {
            Log.w(TAG, "⚠ INSECURE MODE ENABLED — ws:// connections allowed. Do NOT use in production.")
        }
    }

    fun connect(url: String) {
        val isInsecure = url.startsWith("ws://") && !url.startsWith("wss://")
        if (isInsecure && !_insecureMode.value) {
            Log.e(TAG, "Blocked ws:// connection — insecure mode is disabled. Use wss:// or enable insecure mode in Settings.")
            return
        }
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            Log.e(TAG, "Invalid URL scheme — must start with ws:// or wss://")
            return
        }

        // Normalize: append /ws if the user gave us a bare host:port with no
        // path. The relay routes the WebSocket handler at /ws; a bare URL
        // hits the HTTP root and comes back as 404 Not Found during the
        // upgrade handshake. We still accept an explicit path if present.
        val normalized = normalizeRelayUrl(url)

        _isInsecureConnection.value = isInsecure
        if (isInsecure) {
            Log.w(TAG, "⚠ Connecting over INSECURE ws:// to: $normalized")
        }

        serverUrl = normalized
        shouldReconnect = true
        reconnectAttempt = 0
        doConnect(normalized)
    }

    private fun normalizeRelayUrl(url: String): String {
        // Strip scheme to reason about the path portion cheaply.
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val afterScheme = url.substring(schemeEnd + 3)
        val pathStart = afterScheme.indexOf('/')
        return if (pathStart < 0) {
            // No path at all — append /ws
            "$url/ws"
        } else {
            val path = afterScheme.substring(pathStart)
            // Empty or root path — append ws
            if (path == "/" || path.isEmpty()) "${url.trimEnd('/')}/ws" else url
        }
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
        _isInsecureConnection.value = false
    }

    fun shutdown() {
        disconnect()
        supervisorJob.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    fun send(envelope: Envelope) {
        val text = json.encodeToString(envelope)
        webSocket?.send(text)
    }

    private fun doConnect(url: String) {
        _connectionState.value = if (reconnectAttempt > 0) {
            ConnectionState.Reconnecting
        } else {
            ConnectionState.Connecting
        }

        scope.launch { doConnectInternal(url) }
    }

    private fun doConnectInternal(url: String) {
        // Rebuild the client so the CertificatePinner picks up the current
        // pin store snapshot — crucial right after applyServerIssuedCodeAndReset
        // wipes a pin for re-pair. buildClient() does a tiny DataStore read
        // via runBlocking, so it runs on the IO dispatcher inside [scope].
        client = buildClient()

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                _connectionState.value = ConnectionState.Connected

                // TOFU: record the peer cert fingerprint if we don't have one
                // yet. OkHttp populates response.handshake when the connection
                // was upgraded over TLS; ws:// plaintext connections skip this.
                certPinStore?.let { store ->
                    val handshake = response.handshake
                    val peerCerts = handshake?.peerCertificates
                    if (peerCerts != null && peerCerts.isNotEmpty()) {
                        scope.launch {
                            try {
                                store.recordPinIfAbsent(url, peerCerts)
                            } catch (e: Exception) {
                                Log.w(TAG, "recordPinIfAbsent failed: ${e.message}")
                            }
                        }
                    }
                }

                multiplexer.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val envelope = json.decodeFromString<Envelope>(text)
                    multiplexer.route(envelope)
                } catch (e: Exception) {
                    Log.w(TAG, "Malformed relay envelope: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.Disconnected
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.Disconnected
                t.printStackTrace()
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        // Defense-in-depth: if auth state says we shouldn't be reconnecting
        // (no session token, no pending pair code), abort before we spend
        // an attempt. This catches the "clearSession wiped auth state but
        // the reconnect scheduler didn't get the memo" class of bug —
        // without this, we'd fire invalid-credential auth envelopes into
        // the rate limiter and block ourselves.
        if (!reconnectGate()) {
            Log.i(TAG, "scheduleReconnect: gate says no pair context — aborting retry")
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        val url = serverUrl ?: return
        reconnectAttempt++

        val backoffMs = (BASE_BACKOFF_MS * (1L shl minOf(reconnectAttempt - 1, 4)))
            .coerceAtMost(MAX_BACKOFF_MS)

        scope.launch {
            delay(backoffMs)
            // Re-check the gate after the backoff — by the time the delay
            // expires, auth state may have changed (e.g., user hit Revoke
            // during the retry window).
            if (shouldReconnect && reconnectGate()) {
                doConnect(url)
            } else if (!reconnectGate()) {
                Log.i(TAG, "scheduleReconnect: gate turned false during backoff — aborting retry")
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }
}

package com.hermesandroid.relay.network

import android.util.Log
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
    private val multiplexer: ChannelMultiplexer
) {
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

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

        _isInsecureConnection.value = isInsecure
        if (isInsecure) {
            Log.w(TAG, "⚠ Connecting over INSECURE ws:// to: $url")
        }

        serverUrl = url
        shouldReconnect = true
        reconnectAttempt = 0
        doConnect(url)
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

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                _connectionState.value = ConnectionState.Connected
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

        val url = serverUrl ?: return
        reconnectAttempt++

        val backoffMs = (BASE_BACKOFF_MS * (1L shl minOf(reconnectAttempt - 1, 4)))
            .coerceAtMost(MAX_BACKOFF_MS)

        scope.launch {
            delay(backoffMs)
            if (shouldReconnect) {
                doConnect(url)
            }
        }
    }
}

package com.axiomlabs.hermesrelay.core.transport

import android.util.Log
import com.axiomlabs.hermesrelay.core.wire.ChannelMultiplexer
import com.axiomlabs.hermesrelay.core.wire.Envelope
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
    Reconnecting,
}

class RelayConnection(
    private val multiplexer: ChannelMultiplexer,
    private val reconnectGate: () -> Boolean = { true },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var normalizedUrl: String? = null
    private var shouldReconnect = false
    private var reconnectAttempt = 0

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    init {
        multiplexer.setSendCallback { send(it) }
    }

    fun connect(url: String) {
        val normalized = normalizeRelayUrl(url)
        if (!normalized.startsWith("ws://") && !normalized.startsWith("wss://")) {
            Log.w(TAG, "connect ignored invalid relay url: $url")
            return
        }
        normalizedUrl = normalized
        shouldReconnect = true
        reconnectAttempt = 0
        doConnect(normalized)
    }

    fun disconnect(reason: String = "Client disconnect") {
        shouldReconnect = false
        webSocket?.close(1000, reason)
        webSocket = null
        _state.value = ConnectionState.Disconnected
    }

    fun send(envelope: Envelope) {
        val encoded = json.encodeToString(envelope)
        webSocket?.send(encoded)
    }

    private fun doConnect(url: String) {
        _state.value = if (reconnectAttempt > 0) {
            ConnectionState.Reconnecting
        } else {
            ConnectionState.Connecting
        }

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                _state.value = ConnectionState.Connected
                multiplexer.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    multiplexer.route(json.decodeFromString<Envelope>(text))
                } catch (e: Exception) {
                    Log.w(TAG, "Malformed relay envelope: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = ConnectionState.Disconnected
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Relay socket failed: ${t.message} (response=${response?.code})")
                _state.value = ConnectionState.Disconnected
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect || !reconnectGate()) return
        val url = normalizedUrl ?: return
        reconnectAttempt += 1
        val backoff = (1_000L * (1L shl minOf(reconnectAttempt - 1, 4))).coerceAtMost(30_000L)
        scope.launch {
            delay(backoff)
            if (shouldReconnect && reconnectGate()) doConnect(url)
        }
    }

    private fun normalizeRelayUrl(url: String): String {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return trimmed
        val afterScheme = trimmed.substring(schemeEnd + 3)
        val pathStart = afterScheme.indexOf('/')
        return if (pathStart < 0) "$trimmed/ws" else {
            val path = afterScheme.substring(pathStart)
            if (path == "/" || path.isEmpty()) "${trimmed.trimEnd('/')}/ws" else trimmed
        }
    }

    private companion object {
        const val TAG = "RelayConnection"
    }
}

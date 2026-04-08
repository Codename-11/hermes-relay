package com.hermesandroid.relay.network

import com.hermesandroid.relay.network.models.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes incoming WebSocket envelopes to the appropriate channel handler.
 *
 * Envelope format:
 * ```json
 * {
 *   "channel": "chat" | "terminal" | "bridge" | "system",
 *   "type": "<event_type>",
 *   "id": "<message_uuid>",
 *   "payload": { ... }
 * }
 * ```
 */
class ChannelMultiplexer {

    fun interface ChannelHandler {
        fun onMessage(envelope: Envelope)
    }

    private val handlers = ConcurrentHashMap<String, ChannelHandler>()

    private var onConnectedCallback: (() -> Unit)? = null
    private var sendCallback: ((Envelope) -> Unit)? = null

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Register a handler for a specific channel.
     */
    fun registerHandler(channel: String, handler: ChannelHandler) {
        handlers[channel] = handler
    }

    /**
     * Set callback for when WSS connection is established.
     */
    fun setOnConnectedCallback(callback: () -> Unit) {
        onConnectedCallback = callback
    }

    /**
     * Set the send function (provided by ConnectionManager).
     */
    fun setSendCallback(callback: (Envelope) -> Unit) {
        sendCallback = callback
    }

    /**
     * Route an incoming envelope to the appropriate channel handler.
     */
    fun route(envelope: Envelope) {
        when (envelope.channel) {
            "system" -> handleSystem(envelope)
            "chat" -> handlers["chat"]?.onMessage(envelope)
            "terminal" -> {
                // TODO: Phase 2 — terminal channel handler
                handlers["terminal"]?.onMessage(envelope)
            }
            "bridge" -> {
                // TODO: Phase 3 — bridge channel handler
                handlers["bridge"]?.onMessage(envelope)
            }
            else -> {
                // Unknown channel — ignore
            }
        }
    }

    /**
     * Called by ConnectionManager when the WebSocket connection is established.
     */
    fun onConnected() {
        onConnectedCallback?.invoke()
    }

    /**
     * Send an envelope through the WebSocket connection.
     */
    fun send(envelope: Envelope) {
        sendCallback?.invoke(envelope)
    }

    /**
     * Handle system channel messages (auth, ping/pong).
     */
    private fun handleSystem(envelope: Envelope) {
        when (envelope.type) {
            "ping" -> {
                val pong = Envelope(
                    channel = "system",
                    type = "pong",
                    payload = buildJsonObject {
                        put("ts", System.currentTimeMillis())
                    }
                )
                send(pong)
            }
            "auth.ok", "auth.fail" -> {
                // Delegate to system handler if registered
                handlers["system"]?.onMessage(envelope)
            }
        }
    }
}

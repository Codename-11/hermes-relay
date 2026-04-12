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
            // === PHASE3-accessibility: bridge channel routing ===
            // bridge.command envelopes come FROM the server and are
            // dispatched to a [BridgeCommandHandler] which hands them to
            // the [HermesAccessibilityService]'s [ActionExecutor]. Responses
            // (bridge.response / bridge.status) flow back through [send]
            // directly — the handler never consumes its own responses.
            //
            // This branch is intentionally symmetric with "chat" and
            // "terminal": route inbound envelopes to whatever handler is
            // registered. The handler registration itself happens in
            // [ConnectionViewModel] so the ViewModel controls whether
            // bridge routing is active (Bridge can be gated by build
            // flavor or by the master enable toggle in the UI).
            "bridge" -> handlers["bridge"]?.onMessage(envelope)
            // === END PHASE3-accessibility ===
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

    // === PHASE3-notif-listener: notification outbound routing ===
    //
    // `HermesNotificationCompanion` is a system-bound
    // `NotificationListenerService` that lives outside the ViewModel
    // scope. To push posted-notification envelopes onto the WSS
    // connection, it grabs the live multiplexer reference (set by
    // `ConnectionViewModel` via the static companion `multiplexer`
    // slot on the service) and calls [sendNotification].
    //
    // This is a thin wrapper over [send] with a no-op fast path when
    // no send callback is wired yet (relay disconnected). We drop on
    // the floor at this layer rather than buffering — the service
    // owns the cold-start buffer in its `pendingEnvelopes` queue, and
    // dropping when the relay is offline matches the smartwatch
    // companion semantics (a wearable doesn't replay notifications
    // it missed while out of range either).
    fun sendNotification(envelope: Envelope) {
        val cb = sendCallback ?: return
        cb.invoke(envelope)
    }
    // === END PHASE3-notif-listener ===

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

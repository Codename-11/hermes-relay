package com.axiomlabs.hermesrelay.core.wire

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

class ChannelMultiplexer {
    fun interface ChannelHandler {
        fun onMessage(envelope: Envelope)
    }

    private val handlers = ConcurrentHashMap<String, ChannelHandler>()
    private var onConnectedCallback: (() -> Unit)? = null
    private var sendCallback: ((Envelope) -> Unit)? = null

    fun registerHandler(channel: String, handler: ChannelHandler) {
        handlers[channel] = handler
    }

    fun setOnConnectedCallback(callback: () -> Unit) {
        onConnectedCallback = callback
    }

    fun setSendCallback(callback: (Envelope) -> Unit) {
        sendCallback = callback
    }

    fun route(envelope: Envelope) {
        when (envelope.channel) {
            "system" -> handleSystem(envelope)
            else -> handlers[envelope.channel]?.onMessage(envelope)
        }
    }

    fun onConnected() {
        onConnectedCallback?.invoke()
    }

    fun send(envelope: Envelope) {
        sendCallback?.invoke(envelope)
    }

    private fun handleSystem(envelope: Envelope) {
        when (envelope.type) {
            "ping" -> send(
                Envelope(
                    channel = "system",
                    type = "pong",
                    payload = buildJsonObject { put("ts", System.currentTimeMillis()) },
                )
            )
            "auth.ok", "auth.fail" -> handlers["system"]?.onMessage(envelope)
            else -> handlers["system"]?.onMessage(envelope)
        }
    }
}

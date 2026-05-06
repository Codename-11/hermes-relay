package com.hermesandroid.relay.network

import java.net.URI

/**
 * Derives the conventional Hermes-Relay WSS/WS URL from a Hermes API URL.
 *
 * Hermes API and Relay are separate processes, but normal installs expose
 * them on the same host with API on 8642 and Relay on 8767. Keeping this
 * logic centralized lets setup flows treat the Relay URL as "Auto" by
 * default while still allowing a manual override for custom reverse proxies.
 */
object RelayUrlDeriver {
    const val DEFAULT_RELAY_PORT: Int = 8767

    fun deriveFromApiUrl(apiUrl: String, relayPort: Int = DEFAULT_RELAY_PORT): String? {
        val trimmed = apiUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val relayScheme = when (uri.scheme?.lowercase()) {
            "http" -> "ws"
            "https" -> "wss"
            else -> return null
        }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val hostPart = if (host.contains(":") && !host.startsWith("[")) {
            "[$host]"
        } else {
            host
        }
        return "$relayScheme://$hostPart:$relayPort"
    }

    fun isAutoManagedRelayUrl(relayUrl: String, apiUrl: String): Boolean {
        val trimmed = relayUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return true
        if (isDefaultLocalRelayUrl(trimmed)) return true

        val derived = deriveFromApiUrl(apiUrl) ?: return false
        return trimmed.equals(derived, ignoreCase = true)
    }

    private fun isDefaultLocalRelayUrl(relayUrl: String): Boolean {
        val uri = runCatching { URI(relayUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "ws" && scheme != "wss") return false
        val host = uri.host?.lowercase() ?: return false
        val port = if (uri.port == -1) {
            when (scheme) {
                "ws" -> 80
                "wss" -> 443
                else -> -1
            }
        } else {
            uri.port
        }
        return port == DEFAULT_RELAY_PORT &&
            (host == "localhost" || host == "127.0.0.1" || host == "::1")
    }
}

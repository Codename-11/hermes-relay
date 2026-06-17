package com.hermesandroid.relay.network.shared

import java.net.URI

/**
 * Resolves profile-scoped Hermes API URLs for phone use.
 *
 * Relays and Hermes gateways often bind profile API servers to loopback or
 * 0.0.0.0 on the host machine. Those addresses are correct for the relay
 * process but wrong on Android, where 127.0.0.1 means the phone. When the
 * active connection uses a reachable LAN/Tailscale host, replace loopback
 * profile hosts with that same host while preserving the profile port.
 */
object ProfileApiUrlResolver {
    fun normalize(url: String?): String? =
        url?.trim()?.takeIf { it.isNotBlank() }?.trimEnd('/')

    fun resolveForConnection(profileApiUrl: String?, baseApiUrl: String?): String? {
        val profile = normalize(profileApiUrl) ?: return null
        val base = normalize(baseApiUrl) ?: return profile

        val profileUri = runCatching { URI(profile) }.getOrNull() ?: return profile
        val profileHost = profileUri.host?.takeIf { it.isNotBlank() } ?: return profile
        if (!isLocalBindHost(profileHost)) return profile

        val baseUri = runCatching { URI(base) }.getOrNull() ?: return profile
        val baseHost = baseUri.host?.takeIf { it.isNotBlank() } ?: return profile
        if (isLocalBindHost(baseHost)) return profile

        val scheme = baseUri.scheme?.takeIf { it.isNotBlank() }
            ?: profileUri.scheme?.takeIf { it.isNotBlank() }
            ?: return profile
        val hostPart = if (baseHost.contains(":") && !baseHost.startsWith("[")) {
            "[$baseHost]"
        } else {
            baseHost
        }
        val portPart = profileUri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
        val pathPart = profileUri.rawPath?.takeIf { it.isNotBlank() && it != "/" }.orEmpty()
        val queryPart = profileUri.rawQuery?.let { "?$it" }.orEmpty()
        val fragmentPart = profileUri.rawFragment?.let { "#$it" }.orEmpty()

        return "$scheme://$hostPart$portPart$pathPart$queryPart$fragmentPart".trimEnd('/')
    }

    private fun isLocalBindHost(host: String): Boolean {
        return when (host.lowercase().trim('[', ']')) {
            "localhost", "127.0.0.1", "0.0.0.0", "::1", "::" -> true
            else -> false
        }
    }
}

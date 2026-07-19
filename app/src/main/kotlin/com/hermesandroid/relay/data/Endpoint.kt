package com.hermesandroid.relay.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * One entry in a pairing payload's `endpoints` array (ADR 24 — multi-endpoint
 * pairing, 2026-04-19).
 *
 * Pairing QRs can now carry an ordered list of candidate endpoints so a single
 * pairing works across LAN / Tailscale / public-reverse-proxy networks. The
 * phone picks the highest-priority reachable candidate at connect time and
 * re-evaluates on network change.
 *
 * Wire contract (v3 pairing payload):
 * ```json
 * {
 *   "role": "lan",
 *   "priority": 0,
 *   "api":   { "host": "192.168.1.100", "port": 8642, "tls": false },
 *   "relay": { "url": "ws://192.168.1.100:8767", "transport_hint": "ws" }
 * }
 * ```
 *
 * **Semantics (locked by ADR 24):**
 *  - [role] is an open string. Known values `lan` / `tailscale` / `public`
 *    get styled labels; anything else renders generically (`Custom VPN (<role>)`).
 *    No enum, no normalization — the raw role string must round-trip exactly
 *    so HMAC canonicalization holds.
 *  - [priority] is strict, `0 = highest`. Reachability never promotes a lower
 *    priority over a higher one; it only breaks ties among equal priorities.
 *  - The per-endpoint [RelayEndpoint] intentionally carries **only** the URL
 *    and transport hint. The pairing `code`, `ttl_seconds`, and `grants` stay
 *    at the top level of the pairing payload because they're per-pair
 *    artifacts — not per-endpoint.
 */
@Serializable
data class EndpointCandidate(
    val role: String,
    val priority: Int = 0,
    /** Optional legacy/API-server surface. Dashboard-only routes omit it. */
    val api: ApiEndpoint? = null,
    /** Optional Hermes-Relay bridge surface. Standard upstream routes omit it. */
    val relay: RelayEndpoint? = null,
    val dashboard: DashboardEndpoint? = null,
    val proxy: ProxyEndpoint? = null,
    val security: String? = null,
    val recommended: Boolean = false,
)

/**
 * The API-server half of an [EndpointCandidate] — the HTTP/SSE target the
 * phone uses for `/v1/runs`, `/v1/chat/completions`, `/api/sessions/...`, etc.
 *
 * Note: [tls] defaults to false so a v2-synthesized candidate (built from a
 * legacy QR with no `endpoints` field and no top-level `tls`) still
 * deserializes cleanly.
 */
@Serializable
data class ApiEndpoint(
    val host: String,
    val port: Int,
    val tls: Boolean = false,
) {
    /** Build the full API server URL from host, port, and tls flag. */
    val url: String
        get() = "${if (tls) "https" else "http"}://$host:$port"
}

/**
 * Dashboard/admin surface for an [EndpointCandidate]. This is optional so
 * older v3 payloads that only carried API + Relay endpoints keep
 * deserializing; when absent, Android derives the conventional same-host
 * `:9119` dashboard URL from [ApiEndpoint].
 */
@Serializable
data class DashboardEndpoint(
    val url: String,
)

/**
 * The relay-server half of an [EndpointCandidate] — the WSS URL the phone
 * opens for the bridge + terminal channels.
 *
 * @property url full WebSocket URL, e.g. `ws://192.168.1.100:8767` (dev) or
 *           `wss://hermes.example.com/relay` (fronted by a reverse proxy).
 * @property transportHint `"wss"` / `"ws"` / `null`. Drives the plaintext-ws
 *           consent gate and the transport-security UI badge. Never gates
 *           behavior on its own — the scheme of [url] is authoritative.
 */
@Serializable
data class RelayEndpoint(
    val url: String,
    @SerialName("transport_hint")
    val transportHint: String? = null,
)

/**
 * Optional plugin-owned secure proxy route. Unlike [api], [dashboard], and
 * [relay], this is one app-facing base that can cover all Hermes-Relay
 * supported traffic after pairing. It is deliberately optional so plugin
 * proxy support can be advertised by newer payloads without changing the
 * standard upstream connection model.
 */
@Serializable
data class ProxyEndpoint(
    val url: String,
    @SerialName("transport_hint")
    val transportHint: String? = null,
    @SerialName("pin_sha256")
    val pinSha256: String? = null,
)

/**
 * Returns true when [EndpointCandidate.role] is one of the built-in, styled
 * roles: `lan`, `tailscale`, or `public`. Case-insensitive match — but the
 * role string itself is still preserved verbatim for HMAC canonicalization.
 *
 * Unknown roles (`"wireguard"`, `"zerotier"`, `"netbird-eu"`, operator-defined
 * labels) return false so the UI can fall back to [displayLabel]'s generic
 * "Custom VPN" treatment.
 */
fun EndpointCandidate.isKnownRole(): Boolean {
    return when (role.lowercase()) {
        "lan", "tailscale", "public", "plugin_proxy", "plugin-proxy", "https" -> true
        else -> false
    }
}

/**
 * Human-readable label for the UI. Known roles get fixed-case styled labels;
 * unknown roles render as `"Custom VPN (<role>)"` with the raw role preserved
 * so an operator can see exactly what they labeled it.
 *
 * The raw [role] on the [EndpointCandidate] is NOT modified — it stays in its
 * emitted form for HMAC canonicalization. This is a display-only transform.
 */
fun EndpointCandidate.displayLabel(): String {
    return when (role.lowercase()) {
        "lan" -> "LAN"
        "tailscale" -> "Tailscale"
        "public" -> if (primaryRouteUrl()?.startsWith("https://", ignoreCase = true) == true) {
            "HTTPS"
        } else {
            "Public"
        }
        "https" -> "HTTPS"
        "plugin_proxy", "plugin-proxy" -> "Plugin proxy"
        else -> "Custom VPN ($role)"
    }
}

/** Dashboard-first URL identity for routing, diagnostics, and UI labels. */
fun EndpointCandidate.primaryRouteUrl(): String? =
    dashboard?.url?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        ?: api?.url
        ?: relay?.url?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        ?: proxy?.url?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }

/** Stable host/port identity without assuming that an API surface exists. */
fun EndpointCandidate.routeAuthority(): String? {
    val rawUrl = primaryRouteUrl() ?: return null
    val httpUrl = when {
        rawUrl.startsWith("ws://", ignoreCase = true) -> "http://${rawUrl.substringAfter("://")}"
        rawUrl.startsWith("wss://", ignoreCase = true) -> "https://${rawUrl.substringAfter("://")}"
        else -> rawUrl
    }
    val uri = runCatching { URI(httpUrl) }.getOrNull() ?: return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val port = when {
        uri.port > 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
    return "$host:$port"
}

fun EndpointCandidate.hasSecureProxy(): Boolean =
    proxy?.url?.startsWith("https://", ignoreCase = true) == true ||
        proxy?.url?.startsWith("wss://", ignoreCase = true) == true ||
        role.equals("plugin_proxy", ignoreCase = true) ||
        role.equals("plugin-proxy", ignoreCase = true)

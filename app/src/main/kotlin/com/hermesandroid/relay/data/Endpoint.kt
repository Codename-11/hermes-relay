package com.hermesandroid.relay.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val api: ApiEndpoint,
    val relay: RelayEndpoint,
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
        "lan", "tailscale", "public" -> true
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
        "public" -> "Public"
        else -> "Custom VPN ($role)"
    }
}

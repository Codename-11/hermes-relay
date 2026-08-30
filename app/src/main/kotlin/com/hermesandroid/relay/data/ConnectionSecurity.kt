package com.hermesandroid.relay.data

/**
 * Single source of truth for "is this connection encrypted, and by what?"
 *
 * Security is **per-surface**: a single paired connection fans out to several
 * transports (chat/gateway + Manage over the dashboard, API/sessions, relay
 * tools) and each can independently be TLS, overlay-encrypted, or plain (see
 * [computeConnectionSecurity]). Every UI surface — the chat status chip, the
 * connection header, the route picker, the detail sheet — renders the same
 * derived [ConnectionSecurity] so no two places disagree about what "secure"
 * means.
 *
 * Crucially, **"encrypted" includes overlay transports** (Tailscale/WireGuard,
 * the plugin secure proxy), not just TLS. A `ws://` link over a tailnet is
 * WireGuard-encrypted end-to-end — genuinely secure, just not TLS — so it is
 * never labelled "insecure". Only a plain scheme with no overlay warns.
 */
enum class SurfaceSecurityKind { Tls, Overlay, Plain }

/** Whether a configured surface currently contributes traffic to the connection. */
enum class SurfaceUseState { InUse, Available, Unavailable }

/** Connection-level rollup across the surfaces actually in use. */
enum class ConnectionSecurityLevel { Tls, Overlay, Mixed, Plain, Unknown }

/** Security verdict for one transport surface of a connection. */
data class SurfaceSecurity(
    val label: String,
    val kind: SurfaceSecurityKind,
    /** Human mechanism: "TLS", "Tailscale", "WireGuard", "Proxy", "Plain". */
    val mechanism: String,
    val url: String,
    val useState: SurfaceUseState = SurfaceUseState.InUse,
)

data class ConnectionSecurity(
    val level: ConnectionSecurityLevel,
    /** Dominant mechanism for the at-a-glance label. */
    val mechanism: String,
    val surfaces: List<SurfaceSecurity>,
) {
    /** True when every in-use surface is encrypted (TLS or overlay). */
    val isEncrypted: Boolean
        get() = level == ConnectionSecurityLevel.Tls || level == ConnectionSecurityLevel.Overlay

    companion object {
        val UNKNOWN = ConnectionSecurity(ConnectionSecurityLevel.Unknown, "", emptyList())
    }
}

/** True when the URL scheme is TLS (`wss://` / `https://`). */
fun isTlsUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.trim().lowercase()
    return lower.startsWith("wss://") || lower.startsWith("https://")
}

/**
 * True when the active route is encrypted by an overlay network (Tailscale /
 * WireGuard) or the plugin secure proxy, even if its scheme is plain. Mirrors
 * the logic that previously lived privately in `ActiveConnectionSections`.
 */
fun EndpointCandidate?.isEncryptedOverlayRoute(isTailscaleDetected: Boolean): Boolean {
    if (this == null) return false
    val r = role.lowercase()
    val hint = security.orEmpty().lowercase()
    return r == "tailscale" ||
        (isTailscaleDetected && hint.contains("tailscale")) ||
        hint.contains("wireguard") ||
        (!hasSecureProxy() && (hint.contains("https") || hint.contains("tls")))
}

/** Human label for the overlay mechanism encrypting a route. */
fun EndpointCandidate?.overlayMechanism(isTailscaleDetected: Boolean): String {
    if (this == null) return "Encrypted"
    val r = role.lowercase()
    val hint = security.orEmpty().lowercase()
    return when {
        r == "tailscale" || (isTailscaleDetected && hint.contains("tailscale")) -> "Tailscale"
        hint.contains("wireguard") -> "WireGuard"
        hint.contains("https") || hint.contains("tls") -> "TLS"
        else -> "Encrypted"
    }
}

/** Classify a single surface URL against the active route. */
fun classifySurfaceSecurity(
    label: String,
    url: String,
    activeEndpoint: EndpointCandidate?,
    isTailscaleDetected: Boolean,
    useState: SurfaceUseState = SurfaceUseState.InUse,
): SurfaceSecurity {
    val secureLinkProtected = activeEndpoint.secureLinkProtects(label, url)
    val (kind, mechanism) = when {
        secureLinkProtected -> SurfaceSecurityKind.Tls to
            if (activeEndpoint?.hasHermesReach() == true) "Hermes Reach" else "Hermes Secure Link"
        isTlsUrl(url) -> SurfaceSecurityKind.Tls to "TLS"
        activeEndpoint.isEncryptedOverlayRoute(isTailscaleDetected) ->
            SurfaceSecurityKind.Overlay to activeEndpoint.overlayMechanism(isTailscaleDetected)
        else -> SurfaceSecurityKind.Plain to "Plain"
    }
    return SurfaceSecurity(
        label = label,
        kind = kind,
        mechanism = mechanism,
        url = url,
        useState = useState,
    )
}

private fun EndpointCandidate?.secureLinkProtects(label: String, url: String): Boolean {
    val candidate = this ?: return false
    val routes = candidate.proxy?.takeIf { candidate.hasSecureProxy() }
        ?.let { proxy ->
            val base = proxy.url.trim().trimEnd('/')
            Triple(
                "$base/dashboard",
                "$base/api",
                "wss://${base.substringAfter("://")}/relay/ws",
            )
        } ?: return false
    val normalized = url.trim().trimEnd('/')
    val service = when (label) {
        "Chat & Manage", "Dashboard & Gateway" -> "dashboard"
        "API / sessions", "API fallback" -> "api"
        "Relay tools" -> "relay"
        else -> return false
    }
    if (service !in candidate.secureLinkServices()) return false
    val expected = when (service) {
        "dashboard" -> routes.first
        "api" -> routes.second
        else -> routes.third
    }
    return normalized.equals(expected, ignoreCase = true)
}

/**
 * Roll up the per-surface verdicts into one connection-level [ConnectionSecurity].
 * Pure + side-effect free so it is unit-testable without Android.
 */
fun computeConnectionSecurity(
    apiUrl: String,
    dashboardUrl: String,
    relayUrl: String,
    relayConfigured: Boolean,
    activeEndpoint: EndpointCandidate?,
    isTailscaleDetected: Boolean,
    dashboardInUse: Boolean = true,
    apiInUse: Boolean = true,
    apiAvailable: Boolean = apiInUse,
    relayInUse: Boolean = relayConfigured,
    apiEndpoint: EndpointCandidate? = activeEndpoint,
    relayEndpoint: EndpointCandidate? = activeEndpoint,
): ConnectionSecurity {
    val surfaces = buildList {
        dashboardUrl.trim().takeIf { it.isNotBlank() }?.let {
            add(
                classifySurfaceSecurity(
                    label = "Dashboard & Gateway",
                    url = it,
                    activeEndpoint = activeEndpoint,
                    isTailscaleDetected = isTailscaleDetected,
                    useState = if (dashboardInUse) SurfaceUseState.InUse else SurfaceUseState.Unavailable,
                )
            )
        }
        apiUrl.trim().takeIf { it.isNotBlank() }?.let {
            add(
                classifySurfaceSecurity(
                    label = "API fallback",
                    url = it,
                    activeEndpoint = apiEndpoint,
                    isTailscaleDetected = isTailscaleDetected,
                    useState = when {
                        apiInUse -> SurfaceUseState.InUse
                        apiAvailable -> SurfaceUseState.Available
                        else -> SurfaceUseState.Unavailable
                    },
                )
            )
        }
        if (relayConfigured) {
            relayUrl.trim().takeIf { it.isNotBlank() }?.let {
                add(
                    classifySurfaceSecurity(
                        label = "Relay tools",
                        url = it,
                        activeEndpoint = relayEndpoint,
                        isTailscaleDetected = isTailscaleDetected,
                        useState = if (relayInUse) SurfaceUseState.InUse else SurfaceUseState.Unavailable,
                    )
                )
            }
        }
    }
    if (surfaces.isEmpty()) return ConnectionSecurity.UNKNOWN

    // Configured-but-unavailable fallbacks remain visible in the breakdown,
    // but do not make the active transport look insecure.
    val activeSurfaces = surfaces.filter { it.useState == SurfaceUseState.InUse }
    if (activeSurfaces.isEmpty()) {
        return ConnectionSecurity(
            level = ConnectionSecurityLevel.Unknown,
            mechanism = "",
            surfaces = surfaces,
        )
    }
    val kinds = activeSurfaces.map { it.kind }.toSet()
    val hasPlain = SurfaceSecurityKind.Plain in kinds
    val hasSecure = kinds.any { it != SurfaceSecurityKind.Plain }

    val level = when {
        !hasSecure -> ConnectionSecurityLevel.Plain
        hasPlain -> ConnectionSecurityLevel.Mixed
        kinds == setOf(SurfaceSecurityKind.Tls) -> ConnectionSecurityLevel.Tls
        else -> ConnectionSecurityLevel.Overlay
    }

    val mechanism = when (level) {
        ConnectionSecurityLevel.Tls -> "TLS"
        ConnectionSecurityLevel.Overlay ->
            activeSurfaces.firstOrNull { it.kind == SurfaceSecurityKind.Overlay }?.mechanism ?: "Encrypted"
        ConnectionSecurityLevel.Mixed -> "Mixed"
        ConnectionSecurityLevel.Plain -> when (activeEndpoint?.role?.lowercase()) {
            "lan" -> "LAN"
            "public" -> "Public"
            null, "" -> "Plain"
            else -> activeEndpoint.role
        }
        ConnectionSecurityLevel.Unknown -> ""
    }
    return ConnectionSecurity(level = level, mechanism = mechanism, surfaces = surfaces)
}

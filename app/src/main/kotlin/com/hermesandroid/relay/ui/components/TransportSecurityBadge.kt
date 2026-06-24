package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.ConnectionSecurity
import com.hermesandroid.relay.data.ConnectionSecurityLevel
import com.hermesandroid.relay.data.SurfaceSecurityKind

/**
 * Visual badge for the current relay transport security posture.
 *
 * Three states:
 *
 *  - **Secure (TLS)** — rendered in green when the relay URL is `wss://`
 *    and either the connection is live or the user just configured it.
 *    Users see the shield and understand that traffic is encrypted +
 *    optionally TOFU-pinned.
 *
 *  - **Insecure (reason)** — amber when `isSecure == false` and the user has
 *    acknowledged the [InsecureConnectionAckDialog] with a known reason
 *    (`"lan_only"`, `"tailscale_vpn"`, `"local_dev"`). The reason comes from
 *    [PairingPreferences.insecureReason].
 *
 *  - **Insecure (network unknown)** — red when `isSecure == false` and the
 *    user has NOT yet acknowledged the dialog. This is a louder warning to
 *    prompt them through the ack flow.
 *
 * Three size variants:
 *
 *  - [TransportSecuritySize.Chip] — small pill used inline in Settings rows
 *  - [TransportSecuritySize.Row] — full-width row for [ConnectionInfoSheet]
 *  - [TransportSecuritySize.Large] — prominent version for
 *    [PairedDevicesScreen] card headers
 *
 * Colors are chosen to match existing [ConnectionStatusBadge] palette so
 * the UI feels consistent — green ≈ connected, amber ≈ connecting/stale,
 * red ≈ error.
 */
enum class TransportSecuritySize { Chip, Row, Large }

/**
 * Tri-state security posture across a *set* of endpoints/routes. Used for
 * multi-endpoint pairing QRs (ADR 24) where a single binary label lies about
 * the truth — e.g. LAN + Tailscale where LAN is `ws://` but Tailscale is
 * `wss://`. Old binary callers keep working via the `isSecure: Boolean`
 * overload.
 *
 *  - [AllSecure]   — every route is TLS (wss:// or https://). Green.
 *  - [Mixed]       — at least one secure + at least one plain route. Amber.
 *    The app will pick the secure one automatically when the plain one
 *    isn't reachable, so this is informational, not alarming.
 *  - [AllInsecure] — every route is plain (ws:// / http://). Red, dev-only.
 */
enum class TransportSecurityState { AllSecure, Mixed, AllInsecure }

/**
 * Tri-state variant of [TransportSecurityBadge] for multi-endpoint contexts.
 * Pass [TransportSecurityState] directly so the badge can distinguish
 * "all routes secure" from "some routes secure" — the original binary
 * overload collapses Mixed into Insecure, which is the bug we're fixing.
 */
@Composable
fun TransportSecurityBadge(
    state: TransportSecurityState,
    modifier: Modifier = Modifier,
    size: TransportSecuritySize = TransportSecuritySize.Chip,
) {
    val (label, bg, fg) = resolveStateAppearance(state)
    val icon = when (state) {
        TransportSecurityState.AllSecure -> Icons.Filled.Lock
        TransportSecurityState.Mixed -> Icons.Filled.Shield
        TransportSecurityState.AllInsecure -> Icons.Filled.LockOpen
    }
    RenderBadge(
        label = label,
        bg = bg,
        fg = fg,
        icon = icon,
        size = size,
        modifier = modifier,
    )
}

@Composable
fun TransportSecurityBadge(
    isSecure: Boolean,
    reason: String?,
    modifier: Modifier = Modifier,
    size: TransportSecuritySize = TransportSecuritySize.Chip,
    activeRole: String? = null,
) {
    val (label, bg, fg) = resolveAppearance(isSecure, reason, activeRole)
    val icon = if (isSecure) Icons.Filled.Lock else Icons.Filled.LockOpen
    RenderBadge(
        label = label,
        bg = bg,
        fg = fg,
        icon = icon,
        size = size,
        modifier = modifier,
    )
}

@Composable
private fun RenderBadge(
    label: String,
    bg: Color,
    fg: Color,
    icon: ImageVector,
    size: TransportSecuritySize,
    modifier: Modifier,
) {

    val shape = RoundedCornerShape(
        when (size) {
            TransportSecuritySize.Chip -> 8.dp
            TransportSecuritySize.Row -> 10.dp
            TransportSecuritySize.Large -> 12.dp
        }
    )

    val verticalPad = when (size) {
        TransportSecuritySize.Chip -> 4.dp
        TransportSecuritySize.Row -> 8.dp
        TransportSecuritySize.Large -> 10.dp
    }
    val horizontalPad = when (size) {
        TransportSecuritySize.Chip -> 8.dp
        TransportSecuritySize.Row -> 12.dp
        TransportSecuritySize.Large -> 14.dp
    }
    val iconSize = when (size) {
        TransportSecuritySize.Chip -> 14.dp
        TransportSecuritySize.Row -> 18.dp
        TransportSecuritySize.Large -> 20.dp
    }

    Row(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.35f), shape)
            .padding(horizontal = horizontalPad, vertical = verticalPad),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(iconSize)
        )
        Text(
            text = label,
            color = fg,
            fontWeight = FontWeight.Medium,
            style = when (size) {
                TransportSecuritySize.Chip -> MaterialTheme.typography.labelSmall
                TransportSecuritySize.Row -> MaterialTheme.typography.bodySmall
                TransportSecuritySize.Large -> MaterialTheme.typography.bodyMedium
            }
        )
    }
}

/**
 * Build the user-facing label for an insecure transport.
 *
 * Prefers the **live** endpoint role (`activeRole`) because it reflects
 * current reality — the stored `reason` only captures user intent at the
 * moment they toggled the insecure-connection ack dialog, and for LAN QRs
 * the user never had to toggle anything (the QR was already `ws://`).
 *
 * Label copy uses "Plain" rather than "Insecure" — amber, not red, and
 * matches the UX pass where plaintext LAN is treated as informational.
 */
fun insecureReasonLabel(reason: String?, activeRole: String? = null): String {
    val roleLabel = when (activeRole?.lowercase()) {
        "lan" -> "Plain (on LAN)"
        "tailscale" -> "Plain (on Tailscale)"
        "public" -> "Plain (on public URL)"
        null, "" -> null
        else -> "Plain (on $activeRole)"
    }
    if (roleLabel != null) return roleLabel
    return when (reason) {
        "lan_only" -> "Plain (LAN only)"
        "tailscale_vpn" -> "Plain (Tailscale)"
        "local_dev" -> "Plain (dev only)"
        else -> "Plain (no TLS)"
    }
}

private data class BadgeAppearance(val label: String, val bg: Color, val fg: Color)

@Composable
private fun resolveStateAppearance(state: TransportSecurityState): BadgeAppearance {
    return when (state) {
        TransportSecurityState.AllSecure -> {
            val green = Color(0xFF2E7D32)
            BadgeAppearance(
                label = "Secure \u2014 TLS",
                bg = green.copy(alpha = 0.14f),
                fg = green,
            )
        }
        TransportSecurityState.Mixed -> {
            // Amber (not red): secure fallback exists, so this is informational.
            val amber = Color(0xFFF9A825)
            BadgeAppearance(
                label = "Mixed \u2014 secure fallback available",
                bg = amber.copy(alpha = 0.16f),
                fg = amber,
            )
        }
        TransportSecurityState.AllInsecure -> {
            val red = MaterialTheme.colorScheme.error
            BadgeAppearance(
                label = "All routes plain \u2014 dev only",
                bg = red.copy(alpha = 0.16f),
                fg = red,
            )
        }
    }
}

@Composable
private fun resolveAppearance(
    isSecure: Boolean,
    reason: String?,
    activeRole: String? = null,
): BadgeAppearance {
    // Secure — green, matches ConnectionStatusBadge connected palette.
    if (isSecure) {
        val green = Color(0xFF2E7D32)
        return BadgeAppearance(
            label = "Secure (TLS)",
            bg = green.copy(alpha = 0.14f),
            fg = green,
        )
    }

    // A known live role is just as informative as a stored ack reason —
    // if the resolver knows we're on LAN/Tailscale/etc., treat it as amber
    // rather than red, because we have a meaningful label to show.
    val hasKnownRole = !activeRole.isNullOrBlank()
    val hasKnownReason = when (reason) {
        "lan_only", "tailscale_vpn", "local_dev" -> true
        else -> false
    }

    return if (hasKnownRole || hasKnownReason) {
        // Amber — we have a specific context to show, UX is informational.
        val amber = Color(0xFFF9A825)
        BadgeAppearance(
            label = insecureReasonLabel(reason, activeRole),
            bg = amber.copy(alpha = 0.16f),
            fg = amber,
        )
    } else {
        // Red — nothing known; louder warning.
        val red = MaterialTheme.colorScheme.error
        BadgeAppearance(
            label = insecureReasonLabel(reason, activeRole),
            bg = red.copy(alpha = 0.16f),
            fg = red,
        )
    }
}

/**
 * Utility: given a URL (ws/wss/http/https), return whether it's secure.
 * Used by callers that want to derive a badge state from a raw URL string.
 */
fun isUrlSecure(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.trim().lowercase()
    return lower.startsWith("wss://") || lower.startsWith("https://")
}

// ---------------------------------------------------------------------------
// ConnectionSecurity-driven badge (single source of truth — see
// data/ConnectionSecurity.kt). Mechanism-first copy: a Tailscale/WireGuard
// route reads "Encrypted · Tailscale", NOT "Secure — TLS". Both TLS and
// overlay are green; only true plaintext-without-overlay warns.
// ---------------------------------------------------------------------------

private data class ConnSecAppearance(
    val label: String,
    val icon: ImageVector,
    val bg: Color,
    val fg: Color,
)

@Composable
private fun connSecAppearance(security: ConnectionSecurity): ConnSecAppearance {
    val green = Color(0xFF2E7D32)
    val amber = Color(0xFFF9A825)
    val red = MaterialTheme.colorScheme.error
    return when (security.level) {
        ConnectionSecurityLevel.Tls -> ConnSecAppearance(
            label = "Encrypted · TLS",
            icon = Icons.Filled.Lock,
            bg = green.copy(alpha = 0.14f),
            fg = green,
        )
        ConnectionSecurityLevel.Overlay -> ConnSecAppearance(
            label = "Encrypted · ${security.mechanism}",
            icon = Icons.Filled.Shield,
            bg = green.copy(alpha = 0.14f),
            fg = green,
        )
        ConnectionSecurityLevel.Mixed -> ConnSecAppearance(
            label = "Mixed routes",
            icon = Icons.Filled.Shield,
            bg = amber.copy(alpha = 0.16f),
            fg = amber,
        )
        ConnectionSecurityLevel.Plain -> ConnSecAppearance(
            label = if (security.mechanism.isNotBlank() && security.mechanism != "Plain") {
                "Not encrypted · ${security.mechanism}"
            } else {
                "Not encrypted"
            },
            icon = Icons.Filled.LockOpen,
            bg = red.copy(alpha = 0.16f),
            fg = red,
        )
        ConnectionSecurityLevel.Unknown -> ConnSecAppearance(
            label = "Checking…",
            icon = Icons.Filled.Shield,
            bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            fg = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The connection-level security badge every surface should use. Renders the
 * rollup from [ConnectionSecurity]; tap (when [onClick] is set) opens the
 * per-surface detail sheet. Renders nothing while the verdict is Unknown.
 */
@Composable
fun ConnectionSecurityBadge(
    security: ConnectionSecurity,
    modifier: Modifier = Modifier,
    size: TransportSecuritySize = TransportSecuritySize.Chip,
    onClick: (() -> Unit)? = null,
) {
    if (security.level == ConnectionSecurityLevel.Unknown) return
    val a = connSecAppearance(security)
    RenderBadge(
        label = a.label,
        bg = a.bg,
        fg = a.fg,
        icon = a.icon,
        size = size,
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
    )
}

/** Icon-only security marker for tight spots (chat status strip). */
@Composable
fun ConnectionSecurityGlyph(
    security: ConnectionSecurity,
    modifier: Modifier = Modifier,
) {
    if (security.level == ConnectionSecurityLevel.Unknown) return
    val a = connSecAppearance(security)
    Icon(
        imageVector = a.icon,
        contentDescription = a.label,
        tint = a.fg,
        modifier = modifier.size(14.dp),
    )
}

/** Per-route security glyph for the route picker (one [SurfaceSecurityKind]). */
@Composable
fun SurfaceSecurityGlyph(
    kind: SurfaceSecurityKind,
    modifier: Modifier = Modifier,
) {
    val green = Color(0xFF2E7D32)
    val amber = Color(0xFFF9A825)
    val (icon, tint, desc) = when (kind) {
        SurfaceSecurityKind.Tls -> Triple(Icons.Filled.Lock, green, "Encrypted (TLS)")
        SurfaceSecurityKind.Overlay -> Triple(Icons.Filled.Shield, green, "Encrypted")
        // Per-route plaintext is amber (informational), not red — a secure
        // route may exist alongside it.
        SurfaceSecurityKind.Plain -> Triple(Icons.Filled.LockOpen, amber, "Not encrypted")
    }
    Icon(
        imageVector = icon,
        contentDescription = desc,
        tint = tint,
        modifier = modifier.size(14.dp),
    )
}

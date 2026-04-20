package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.ui.components.BadgeState

/**
 * Resolved UI state for the relay WSS connection, surfaced by
 * [ConnectionViewModel.relayUiState].
 *
 * Raw inputs ([ConnectionViewModel.authState] +
 * [ConnectionViewModel.relayConnectionState] + relay URL) produce this
 * single derived state so every screen renders the relay row identically.
 * Before this type existed, `SettingsScreen`, `ConnectionSettingsScreen`,
 * and a couple of ad-hoc helpers each implemented their own "is it stale,
 * is it reconnecting, should the badge flash red" rules and ended up
 * disagreeing — the Active Connection card said red/Disconnected while
 * the Connection sub-screen said amber/Reconnecting for the same
 * underlying moment.
 *
 * Grace-window behavior: when the session is paired but the WSS is
 * currently [com.hermesandroid.relay.network.ConnectionState.Disconnected],
 * the VM emits [Connecting] for a short grace window (see
 * `RELAY_RECONNECT_GRACE_MS` in [ConnectionViewModel]) and only promotes
 * to [Stale] if the WSS doesn't come up in time. This avoids the "flash
 * of red" on cold start / resume.
 *
 * ### ADR 24 — activeEndpointRole
 *
 * Multi-endpoint pairing (ADR 24, 2026-04-19) means a single paired
 * device can be reachable at multiple URLs (LAN / Tailscale / public).
 * The currently-selected candidate's `role` is attached to the state via
 * [RelayRowState.activeEndpointRole] so the connection chip can render
 * "Connected · Tailscale" instead of just "Connected".
 *
 * Role is **not** part of this sealed interface itself — that would have
 * broken every `== RelayUiState.Connected` and `when (this)` call site in
 * the app. It's carried on [RelayRowState], the wrapper the VM actually
 * emits. Screens that only need the phase keep reading [RelayUiState];
 * screens that want the chip read [RelayRowState].
 */
sealed interface RelayUiState {
    /** Relay URL is blank — nothing to talk to yet. */
    data object NotConfigured : RelayUiState

    /** WSS handshake succeeded, auth envelope was accepted, healthy. */
    data object Connected : RelayUiState

    /**
     * Either an in-flight [Connecting]/[com.hermesandroid.relay.network.ConnectionState.Reconnecting]
     * WSS attempt OR the grace window right after a Paired-but-Disconnected
     * transition. UI renders this in amber — "we're trying, hold on."
     */
    data object Connecting : RelayUiState

    /**
     * Paired session token is still valid but the WSS has been down past
     * the grace window. Tapping the row should call
     * [ConnectionViewModel.connectRelay] to force a fresh attempt — no
     * re-pair needed. Rendered amber with a "tap to reconnect" hint.
     */
    data object Stale : RelayUiState

    /**
     * No paired session (or auth failed). User action required — usually
     * re-pair via the Connections sub-screen.
     */
    data object Disconnected : RelayUiState
}

/**
 * The connection row's full render input — a [RelayUiState] "phase" plus
 * ADR 24's active endpoint role.
 *
 * Separating the phase from the role means every existing `when (state)`
 * and `state == RelayUiState.Connected` comparison keeps working unchanged;
 * screens that want the multi-endpoint chip read [activeEndpointRole]
 * alongside [phase].
 *
 * @property phase the resolved WSS state. Never null.
 * @property activeEndpointRole role of the currently-picked endpoint
 *           candidate (`lan`, `tailscale`, `public`, or operator-defined
 *           custom label). Null when no resolver has run yet, when the
 *           device's stored list is empty, or when multi-endpoint mode
 *           isn't wired in.
 */
data class RelayRowState(
    val phase: RelayUiState,
    val activeEndpointRole: String? = null,
)

/**
 * Map a [RelayUiState] onto the four-state [BadgeState] the existing
 * [com.hermesandroid.relay.ui.components.ConnectionStatusRow] renders.
 * [RelayUiState.Stale] is amber because it's recoverable by a tap, not a
 * dead-end error.
 */
fun RelayUiState.asBadgeState(): BadgeState = when (this) {
    RelayUiState.Connected -> BadgeState.Connected
    RelayUiState.Connecting, RelayUiState.Stale -> BadgeState.Connecting
    RelayUiState.Disconnected, RelayUiState.NotConfigured -> BadgeState.Disconnected
}

/** Shortcut — delegate to the phase. */
fun RelayRowState.asBadgeState(): BadgeState = phase.asBadgeState()

/**
 * Human-readable status text for the relay row. Callers pass the label
 * they want shown in the [RelayUiState.Connected] case — some screens
 * want the actual URL (Settings overview card), others want a simple
 * "Connected" word (Connection sub-screen).
 */
fun RelayUiState.statusText(connectedLabel: String): String = when (this) {
    RelayUiState.NotConfigured -> "Not configured"
    RelayUiState.Connected -> connectedLabel
    RelayUiState.Connecting -> "Reconnecting…"
    RelayUiState.Stale -> "Stale — tap to reconnect"
    RelayUiState.Disconnected -> "Disconnected"
}

/**
 * [RelayRowState] version of [statusText]. When the active endpoint role is
 * known and the phase is one that actually uses the network, the chip
 * shows a middle-dot-separated "Connected · Tailscale" style. Stale /
 * Disconnected lines keep the last-known role hint too ("Stale · Tailscale
 * — tap to reconnect") so users understand *which* network transport went
 * quiet. Null role falls back to the bare phase text.
 */
fun RelayRowState.statusText(connectedLabel: String): String {
    val base = phase.statusText(connectedLabel)
    val role = activeEndpointRole?.trim()?.takeIf { it.isNotBlank() } ?: return base
    // Display-friendly role — match EndpointCandidate.displayLabel() shape
    // without pulling the import into this file.
    val display = when (role.lowercase()) {
        "lan" -> "LAN"
        "tailscale" -> "Tailscale"
        "public" -> "Public"
        else -> role
    }
    return when (phase) {
        RelayUiState.Connected -> "$base \u00B7 $display"
        RelayUiState.Connecting -> "$base \u00B7 $display"
        RelayUiState.Stale -> "Stale \u00B7 $display — tap to reconnect"
        RelayUiState.Disconnected -> "$base (last via $display)"
        RelayUiState.NotConfigured -> base
    }
}

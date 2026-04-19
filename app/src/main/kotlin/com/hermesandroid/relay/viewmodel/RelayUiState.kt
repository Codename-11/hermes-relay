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

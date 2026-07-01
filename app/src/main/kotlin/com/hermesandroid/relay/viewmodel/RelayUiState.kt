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
 * currently [com.hermesandroid.relay.network.relay.ConnectionState.Disconnected],
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
     * Either an in-flight [Connecting]/[com.hermesandroid.relay.network.relay.ConnectionState.Reconnecting]
     * WSS attempt OR the grace window right after a Paired-but-Disconnected
     * transition. UI renders this in amber — "we're trying, hold on."
     */
    data object Connecting : RelayUiState

    /**
     * Paired session token is still valid but the WSS has been down past
     * the grace window. Tapping the row should call
     * [ConnectionViewModel.connectRelay] to force a fresh attempt — no
     * re-pair needed. Rendered as unreachable with a "tap to reconnect" hint.
     */
    data object Stale : RelayUiState

    /**
     * The relay rejected our session token (revoked, or wiped by a relay
     * restart) — i.e. [com.hermesandroid.relay.auth.AuthState.Failed]. Unlike
     * [Stale], reconnecting won't help: the fix is to pair again. Rendered red
     * with a "tap to pair again" hint, and the row's tap opens the relay info /
     * re-pair surface rather than firing another doomed reconnect. This is the
     * highest-frequency real failure because the relay's session store is
     * in-memory and wiped on every restart.
     */
    data object Expired : RelayUiState

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

data class ConnectionHandoffStatus(
    val title: String,
    val route: String? = null,
    val active: Boolean = true,
    val success: Boolean = false,
    val entries: List<ConnectionHandoffTraceEntry> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Per-step status for the connection toast's live stepper. Mirrors the
 * cold-start sphere's check vocabulary (pending · spinner · ✓ · ✕) so the two
 * surfaces read as one family.
 *
 * Null on a [ConnectionHandoffTraceEntry] means "let the renderer infer it"
 * from list position + the parent snapshot (last entry follows the snapshot's
 * active/success/error; earlier entries are Done). Producers that know a
 * surface's real verdict — e.g. the probe-entry builder — stamp it explicitly.
 */
enum class ConnectionStepState { Pending, Active, Done, Failed }

data class ConnectionHandoffTraceEntry(
    val label: String,
    val detail: String? = null,
    val state: ConnectionStepState? = null,
)

enum class ConnectionStatusTone {
    Info,
    Success,
    Warning,
    Error,
}

data class ConnectionStatusSnapshot(
    val title: String,
    val route: String? = null,
    val actionLabel: String? = null,
    val active: Boolean = false,
    val success: Boolean = false,
    val tone: ConnectionStatusTone = ConnectionStatusTone.Info,
    val entries: List<ConnectionHandoffTraceEntry> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Which top-of-app chrome surface a [ConnectionStatusSnapshot] should occupy.
 *
 * Tiered by **persistence, not severity**, so the most frequent event — a routine
 * reconnect — is the *least* disruptive:
 *
 * - [None] — an in-progress connect / reconnect / checking pass. Nothing renders
 *   up top; the always-visible bottom
 *   [com.hermesandroid.relay.ui.components.RelayStatusStrip] flips to a
 *   "Reconnecting…" cue instead, so chat content never shifts for a routine
 *   reconnect (the common case).
 * - [Passive] — any non-critical positive/informational delta: connected,
 *   reconnected, or a route swap. A **slim** strip in the take-space stack
 *   *above* the top bar — it does NOT cover the profile icon / nav — that
 *   self-dismisses. These are FYI, not interruptions, so they never float over
 *   the content the user is looking at.
 * - [Banner] — a sustained problem the user should act on (no connection, no
 *   internet, API/relay unreachable). A take-space banner honestly holds space
 *   below the status bar until the condition clears or is dismissed.
 *
 * There is deliberately no "float over content" surface: a status important
 * enough to interrupt is important enough to hold space ([Banner]); everything
 * else is passive. This replaces the earlier severity split (all non-error →
 * take-space banner). See the Connections-UI decisions in DEVLOG (2026-06-30 /
 * 2026-07-01).
 */
enum class ConnectionStatusSurface { None, Passive, Banner }

fun ConnectionStatusSnapshot.presentationSurface(): ConnectionStatusSurface = when {
    // In-flight connect/reconnect/checking → the bottom strip carries it.
    active -> ConnectionStatusSurface.None
    // Sustained, actionable problem → persistent take-space banner.
    tone == ConnectionStatusTone.Warning || tone == ConnectionStatusTone.Error ->
        ConnectionStatusSurface.Banner
    // Everything else — connected / reconnected / route swap / bare info →
    // a slim, self-dismissing strip above the top bar. Never a float.
    else -> ConnectionStatusSurface.Passive
}

fun ConnectionHandoffStatus.asConnectionStatusSnapshot(): ConnectionStatusSnapshot =
    ConnectionStatusSnapshot(
        title = title,
        route = route,
        actionLabel = null,
        active = active,
        success = success,
        tone = when {
            success -> ConnectionStatusTone.Success
            active -> ConnectionStatusTone.Info
            else -> ConnectionStatusTone.Info
        },
        entries = entries,
        updatedAtMs = updatedAtMs,
    )

/**
 * Map a [RelayUiState] onto the four-state [BadgeState] the existing
 * [com.hermesandroid.relay.ui.components.ConnectionStatusRow] renders.
 * [RelayUiState.Stale] is red because the paired session may still be valid
 * but the live relay path is not usable until reconnect succeeds.
 */
fun RelayUiState.asBadgeState(): BadgeState = when (this) {
    RelayUiState.Connected -> BadgeState.Connected
    RelayUiState.Connecting -> BadgeState.Connecting
    RelayUiState.Stale, RelayUiState.Expired, RelayUiState.Disconnected, RelayUiState.NotConfigured -> BadgeState.Disconnected
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
    RelayUiState.Stale -> "Relay unreachable - tap to reconnect"
    RelayUiState.Expired -> "Pairing expired — tap to pair again"
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
        RelayUiState.Stale -> "Unreachable \u00B7 $display - tap to reconnect"
        RelayUiState.Expired -> base
        RelayUiState.Disconnected -> "$base (last via $display)"
        RelayUiState.NotConfigured -> base
    }
}

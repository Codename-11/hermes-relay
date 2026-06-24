package com.hermesandroid.relay.network.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.hermesandroid.relay.auth.CertPinStore
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.PairingPreferences
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.network.relay.models.Envelope
import com.hermesandroid.relay.network.shared.EndpointResolver
import com.hermesandroid.relay.network.shutdownOffMainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting
}

class ConnectionManager(
    private val multiplexer: ChannelMultiplexer,
    /**
     * Optional TOFU certificate pin store. When provided, ConnectionManager
     * builds its [OkHttpClient] with a snapshot of the current pins each
     * connect — so subsequent wss connects refuse mismatched certs. On a
     * successful `onOpen` we call back to record the peer cert fingerprint
     * for the first-time TOFU case. See [CertPinStore] for the contract.
     *
     * Nullable for backwards-compat with unit tests that construct a bare
     * ConnectionManager without auth wiring.
     */
    private val certPinStore: CertPinStore? = null,
    /**
     * Defense-in-depth guard for the internal auto-reconnect loop. Called
     * from [scheduleReconnect] both before scheduling the delayed retry and
     * after the backoff delay expires — if it returns `false`, the retry is
     * silently dropped.
     *
     * The canonical wiring is `{ authManager.hasPairContext }` so the phone
     * never fires a reconnect with no session token and no pending pair
     * code. Without this gate, ConnectionManager's internal retry loop
     * completely bypasses [ConnectionViewModel.connectRelay]'s gate (the
     * primary gate introduced in the 2026-04-11 "Option B" commit), and
     * stale credentials get fed into the auth envelope after clearSession
     * wipes state → relay returns "Invalid pairing code or session token"
     * → rate limiter blocks the IP after 5 attempts → user can't re-pair.
     *
     * Defaults to always-allow for tests and legacy call sites. Production
     * wiring passes the AuthManager gate from [ConnectionViewModel].
     */
    private val reconnectGate: () -> Boolean = { true },
    /**
     * Application context used to register the [ConnectivityManager
     * .NetworkCallback] that drives ADR 24's network-aware re-resolution.
     * Nullable for legacy call sites / tests — when null, the callback is
     * never registered and the manager degrades to single-URL behavior.
     */
    private val context: Context? = null,
    /**
     * ADR 24 multi-endpoint resolver. When provided alongside [context] and
     * either [endpointCandidatesProvider] or a non-null [deviceIdProvider],
     * every call to [connect] first consults the resolver before opening the
     * WSS; on network changes the resolver is re-run and we hot-swap to the
     * new winner. When null the manager uses the caller-supplied URL verbatim
     * (pre-ADR-24 behavior).
     */
    private val endpointResolver: EndpointResolver? = null,
    /**
     * Candidate supplier for the active saved connection. This is the
     * standard-Hermes route source: it works before Relay pairing, so API,
     * dashboard, voice, and future Relay calls can hand off between LAN and
     * Tailscale using the same resolver. If it returns an empty list, we fall
     * back to the legacy per-device PairingPreferences source below.
     */
    private val endpointCandidatesProvider: (suspend () -> List<EndpointCandidate>)? = null,
    /**
     * Suspending supplier for the active device id. Used to key into
     * [PairingPreferences.getDeviceEndpoints] during resolution. `null`
     * disables multi-endpoint resolution even when [endpointResolver] is
     * non-null — the manager falls back to the single-URL path.
     */
    private val deviceIdProvider: (suspend () -> String?)? = null,
) {
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
        // Swap in the current pin snapshot on every connect. We DON'T hold a
        // long-lived OkHttpClient with a stale pinner — otherwise a re-pair
        // that wipes a pin would still be subject to the pre-wipe rules.
        certPinStore?.let { store ->
            try {
                builder.certificatePinner(store.buildPinnerSnapshot())
            } catch (e: Exception) {
                Log.w(TAG, "CertificatePinner build failed: ${e.message}")
                builder.certificatePinner(CertificatePinner.DEFAULT)
            }
        }
        return builder.build()
    }

    @Volatile
    private var client: OkHttpClient = buildClient()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var serverUrl: String? = null
    private var reconnectAttempt = 0
    private var shouldReconnect = true
    // Last HTTP status seen during WSS upgrade, captured in onFailure.
    // Used by scheduleReconnect() to pick an appropriate backoff — notably
    // a much longer one when the server is rate-limiting us (HTTP 429) so
    // we don't re-fill the ban bucket and brick our own auth window.
    @Volatile
    private var lastUpgradeResponseCode: Int? = null

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** When true, allows ws:// connections for local dev/testing. */
    private val _insecureMode = MutableStateFlow(false)
    val insecureMode: StateFlow<Boolean> = _insecureMode.asStateFlow()

    /** True when the current connection is using ws:// instead of wss:// */
    private val _isInsecureConnection = MutableStateFlow(false)
    val isInsecureConnection: StateFlow<Boolean> = _isInsecureConnection.asStateFlow()

    // ADR 24 — currently-active endpoint candidate. Null when the manager is
    // running in legacy single-URL mode (no resolver wired, no candidates in
    // DataStore, or resolve() returned null and we fell back to the caller's
    // URL). Surfaced through [activeEndpoint] for the UI status chip + the
    // Endpoints card in Settings.
    private val _activeEndpoint = MutableStateFlow<EndpointCandidate?>(null)
    val activeEndpoint: StateFlow<EndpointCandidate?> = _activeEndpoint.asStateFlow()

    /**
     * Manual role override. When non-null, the resolver's output is replaced
     * with whichever candidate in the stored list matches this role (case-
     * insensitive) — provided it's reachable. Reachability still gates: a
     * user-preferred endpoint that doesn't respond to HEAD /health falls
     * back through the normal priority chain.
     *
     * Two writers feed this: a sticky [Connection.preferredRouteRole] is
     * restored into it on connection load, and the Routes card's transient
     * "Use now" writes it directly without persisting. Cleared on
     * [disconnect] per ADR 24's "clears on disconnect" semantics.
     *
     * Exposed as [manualRoleOverrideFlow] so the Routes card can label the
     * current route as automatic / preferred / manually switched.
     */
    private val _manualRoleOverride = MutableStateFlow<String?>(null)
    val manualRoleOverrideFlow: StateFlow<String?> = _manualRoleOverride.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Debounce job for network-change re-resolution. Android fires one
     * onAvailable per satisfying network (Wi-Fi + cell + VPN can land within
     * milliseconds of each other, and registration itself replays every
     * current network), so each event cancels the previous pending resolve
     * and the last one wins after a short settle window.
     */
    @Volatile
    private var networkResolveJob: kotlinx.coroutines.Job? = null

    /** Deferred reaction to a network loss — cancelled if a network returns within the grace. */
    private var networkLossJob: kotlinx.coroutines.Job? = null

    /**
     * Set when a network loss outlives [NETWORK_LOSS_GRACE_MS] — only then may
     * a re-resolve switch DOWN to a lower-priority endpoint. Prevents a
     * transient probe miss (Wi-Fi settling) from switching routes and
     * cancelling an in-flight turn. Cleared once a resolution is published.
     */
    @Volatile
    private var sustainedLossDeclared = false

    init {
        // Register at construction, not on first connect(). Standard
        // (no-Relay) connections never open the WSS socket, but their HTTP
        // surfaces (chat, dashboard, voice) still need [activeEndpoint] to
        // follow LAN/Tailscale handoffs — leaving registration inside
        // connect() left the whole ADR 24 network-aware path dormant for
        // exactly those users. No-op when [context] is null (tests).
        ensureNetworkCallbackRegistered()
    }

    companion object {
        private const val TAG = "ConnectionManager"
        private const val MAX_BACKOFF_MS = 30_000L
        private const val BASE_BACKOFF_MS = 1_000L
        // Settle window before re-resolving after a network event. Long
        // enough to coalesce the onAvailable burst of a handoff, short
        // enough that a route swap still feels immediate.
        private const val NETWORK_RESOLVE_DEBOUNCE_MS = 300L

        /**
         * Grace before reacting to a network loss. A transient blip (Wi-Fi
         * power-save/roam, a brief drop, the OS swapping radios) recovers
         * within this window and must NOT mark the active endpoint unreachable
         * or switch routes — doing so rebuilds the chat client and cancels an
         * in-flight turn. Only a loss sustained past the grace switches.
         */
        private const val NETWORK_LOSS_GRACE_MS = 6_000L
        // Matches plugin.relay.auth._BLOCK_SECONDS (5 min). If we see 429
        // on the WSS upgrade, we're IP-banned server-side — retrying at
        // our normal 1-30s cadence re-fills the ban bucket and keeps us
        // banned forever. Waiting at least as long as the server's block
        // duration lets the ban expire naturally.
        private const val RATE_LIMIT_BACKOFF_MS = 300_000L
    }

    fun setInsecureMode(enabled: Boolean) {
        if (_insecureMode.value == enabled) {
            return
        }
        _insecureMode.value = enabled
        if (enabled) {
            Log.w(TAG, "⚠ INSECURE MODE ENABLED — ws:// connections allowed. Do NOT use in production.")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Relay,
                severity = DiagnosticSeverity.Warning,
                title = "Insecure relay mode enabled",
                detail = "ws:// connections are allowed",
            )
        }
    }

    fun connect(url: String) {
        // Register the network callback on the first connect attempt. We
        // only do this once per manager lifetime; [shutdown] tears it down.
        ensureNetworkCallbackRegistered()

        // ADR 24: if we have a resolver + device id, try the multi-endpoint
        // path first. Fall back to the caller-supplied URL whenever the
        // resolver returns nothing — preserving pre-ADR-24 single-URL
        // behavior for freshly-upgraded installs and for v1/v2 QRs where
        // the synthesized list just collapses to the same URL anyway.
        scope.launch {
            val resolved = resolveBestEndpointSafe()
            val targetUrl = resolved?.relay?.url ?: url
            if (resolved != null) {
                _activeEndpoint.value = resolved
                Log.i(TAG, "connect: resolver picked role=${resolved.role} " +
                    "relay=${resolved.relay.url} (fallback would have been $url)")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Relay,
                    severity = DiagnosticSeverity.Info,
                    title = "Relay route selected",
                    endpointRole = resolved.role,
                    url = resolved.relay.url,
                )
            } else {
                _activeEndpoint.value = null
                Log.d(TAG, "connect: no resolver winner — using supplied url $url")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Relay,
                    severity = DiagnosticSeverity.Warning,
                    title = "Using configured relay URL",
                    detail = "No resolver winner",
                    url = url,
                )
            }
            connectToUrlOnMainPath(targetUrl)
        }
    }

    /**
     * Same as [connect] but bypasses the resolver — used by the network-
     * change callback when we've already picked a winner and just want to
     * reopen the socket against that URL. Keeping this separate prevents
     * the callback from re-running the resolve loop inside another
     * resolve loop.
     */
    private fun connectToUrlOnMainPath(
        url: String,
        replaceReason: String = "Relay socket replaced",
    ) {
        val isInsecure = url.startsWith("ws://") && !url.startsWith("wss://")
        if (isInsecure && !_insecureMode.value) {
            Log.e(TAG, "Blocked ws:// connection — insecure mode is disabled. Use wss:// or enable insecure mode in Settings.")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Relay,
                severity = DiagnosticSeverity.Error,
                title = "Relay socket blocked",
                detail = "ws:// is disabled",
                url = url,
            )
            return
        }
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            Log.e(TAG, "Invalid URL scheme — must start with ws:// or wss://")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Relay,
                severity = DiagnosticSeverity.Error,
                title = "Relay socket URL invalid",
                detail = "URL must start with ws:// or wss://",
                url = url,
            )
            return
        }

        // Normalize: append /ws if the user gave us a bare host:port with no
        // path. The relay routes the WebSocket handler at /ws; a bare URL
        // hits the HTTP root and comes back as 404 Not Found during the
        // upgrade handshake. We still accept an explicit path if present.
        val normalized = normalizeRelayUrl(url)
        val existingState = _connectionState.value
        if (serverUrl == normalized &&
            (existingState == ConnectionState.Connecting ||
                existingState == ConnectionState.Connected ||
                existingState == ConnectionState.Reconnecting)
        ) {
            Log.i(TAG, "connect: already ${existingState.name.lowercase()} to $normalized — skipping duplicate open")
            return
        }
        val previousSocket = webSocket

        _isInsecureConnection.value = isInsecure
        if (isInsecure) {
            Log.w(TAG, "⚠ Connecting over INSECURE ws:// to: $normalized")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Relay,
                severity = DiagnosticSeverity.Warning,
                title = "Opening insecure relay socket",
                url = normalized,
            )
        } else {
            DiagnosticsLog.record(
                category = DiagnosticCategory.Relay,
                severity = DiagnosticSeverity.Info,
                title = "Opening relay socket",
                url = normalized,
            )
        }

        serverUrl = normalized
        shouldReconnect = true
        reconnectAttempt = 0
        doConnect(normalized, previousSocket, replaceReason)
    }

    // ----- ADR 24 — multi-endpoint resolution --------------------------------

    /**
     * Load the device's stored [EndpointCandidate] list and hand it to
     * [EndpointResolver.resolve]. Returns `null` when any precondition is
     * missing (no resolver wired, no context, no device id, empty list) OR
     * when no candidate was reachable — caller then falls back to the
     * legacy single-URL path.
     *
     * Wraps the DataStore read in a 1-second timeout; if DataStore stalls
     * for any reason we don't block the connect loop forever.
     */
    suspend fun resolveBestEndpoint(): EndpointCandidate? = resolveBestEndpointSafe()

    private suspend fun resolveBestEndpointSafe(): EndpointCandidate? {
        val resolver = endpointResolver ?: return null
        val ctx = context ?: return null

        val endpoints = try {
            withTimeoutOrNull(1_000L) {
                endpointCandidatesProvider?.invoke()
                    ?.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        } ?: run {
            val devicePull = deviceIdProvider ?: return null
            val deviceId = try {
                withTimeoutOrNull(1_000L) { devicePull() }
            } catch (_: Exception) {
                null
            } ?: return null

            try {
                withTimeoutOrNull(1_000L) {
                    PairingPreferences.getDeviceEndpoints(ctx, deviceId).first()
                }
            } catch (_: Exception) {
                null
            } ?: emptyList()
        }

        if (endpoints.isEmpty()) return null

        // Manual override: if the user pinned a role in the Endpoints card,
        // try that one first; fall through to the strict-priority algorithm
        // if it isn't reachable.
        _manualRoleOverride.value?.let { preferredRole ->
            val preferred = endpoints.firstOrNull {
                it.role.equals(preferredRole, ignoreCase = true)
            }
            if (preferred != null) {
                // Single-element list still respects the 2s probe gate.
                val winner = resolver.resolve(listOf(preferred))
                if (winner != null) return winner
                Log.i(TAG, "manualRoleOverride=$preferredRole not reachable — " +
                    "falling through to strict-priority resolve")
            }
        }

        return resolver.resolve(endpoints)
    }

    /**
     * User-triggered re-probe. Forces a fresh resolve + reconnect regardless
     * of cache state. Backs the "Probe now" row action in the Endpoints card.
     * Fire-and-forget wrapper around [probeAndReconnectNow] for callers that
     * don't need the outcome.
     */
    fun probeAndReconnect() {
        scope.launch { probeAndReconnectNow() }
    }

    /**
     * Awaitable body of [probeAndReconnect]. Returns the resolved winner —
     * or null when no candidate answered — so callers (probe-status UI) can
     * report the outcome instead of guessing with a fixed delay.
     *
     * Unlike the pre-2026-06 version this ALWAYS publishes the resolve
     * outcome to [activeEndpoint]: a standard (no relay socket) connection
     * whose probes all failed used to early-return before publishing,
     * leaving the Routes card stuck on "Resolving" with no feedback. The
     * only exception is the live-socket transient-miss guard shared with
     * [refreshActiveEndpoint].
     */
    suspend fun probeAndReconnectNow(): EndpointCandidate? {
        endpointResolver?.clearCache()
        val current = serverUrl
        val resolved = resolveBestEndpointSafe()
        if (resolved == null && _connectionState.value == ConnectionState.Connected) {
            // Transient probe miss while the relay socket is demonstrably up
            // — keep the live route published rather than downgrading every
            // HTTP surface to the saved URL. Mirrors refreshActiveEndpoint.
            return _activeEndpoint.value
        }
        _activeEndpoint.value = resolved
        val targetUrl = resolved?.relay?.url ?: current ?: return resolved
        val normalizedTarget = normalizeRelayUrl(targetUrl)
        // Reconnect when the winner changed, and also when the socket is
        // stale/disconnected on the same winner. The latter makes the
        // "Use now" route action an actual recovery path after Wi-Fi drop
        // instead of a no-op that only updates preference state.
        if (current == null) {
            if (shouldReconnect && reconnectGate()) {
                Log.i(TAG, "probeAndReconnect: no current socket — connecting to $normalizedTarget")
                connectToUrlOnMainPath(targetUrl)
            }
        } else if (normalizedTarget != current) {
            Log.i(TAG, "probeAndReconnect: swapping $current → $normalizedTarget")
            connectToUrlOnMainPath(targetUrl, "Endpoint re-probe")
        } else if (_connectionState.value == ConnectionState.Disconnected &&
            shouldReconnect &&
            reconnectGate()
        ) {
            Log.i(TAG, "probeAndReconnect: current route is stale — reconnecting $current")
            doConnect(current)
        }
        return resolved
    }

    /**
     * Re-run endpoint resolution and publish the winner without forcing a
     * WSS reconnect. Used by HTTP-only surfaces (chat/voice/relay HTTP)
     * so they can follow LAN/Tailscale/VPN route changes even when the relay
     * socket is currently disconnected or intentionally not paired.
     *
     * @param clearProbeCache wipe the resolver's probe cache first. Pass
     *   `true` from "the world may have changed" triggers (app resume,
     *   network change) — otherwise a route that died within the positive
     *   cache TTL (60s) can still be returned as the winner.
     */
    suspend fun refreshActiveEndpoint(clearProbeCache: Boolean = false): EndpointCandidate? {
        if (clearProbeCache) endpointResolver?.clearCache()
        val resolved = resolveBestEndpointSafe()
        if (resolved == null && _connectionState.value == ConnectionState.Connected) {
            // Transient probe miss while the relay socket is demonstrably up
            // (slow resume, mid-handoff blip) — keep publishing the live
            // route instead of downgrading every HTTP surface to the saved
            // URL. Mirrors scheduleNetworkReResolve's guard.
            return _activeEndpoint.value
        }
        _activeEndpoint.value = resolved
        return resolved
    }

    /**
     * Pin a specific role as the preferred endpoint. Cleared on [disconnect]
     * per the Endpoints-card contract. No-op until the next connect / probe
     * cycle — call [probeAndReconnect] to apply immediately.
     */
    fun setManualRoleOverride(role: String?) {
        _manualRoleOverride.value = role?.takeIf { it.isNotBlank() }
        Log.i(TAG, "manualRoleOverride now=${_manualRoleOverride.value ?: "(cleared)"}")
    }

    fun getManualRoleOverride(): String? = _manualRoleOverride.value

    private fun markActiveEndpointUnreachable(reason: String) {
        val active = _activeEndpoint.value ?: return
        endpointResolver?.markUnreachable(active)
        Log.i(TAG, "marked endpoint role=${active.role} unreachable ($reason)")
    }

    /**
     * Debounced network-change re-resolution, shared by both NetworkCallback
     * events. Re-runs the resolver and publishes the winner to
     * [activeEndpoint] so HTTP-only surfaces (chat, dashboard, standard
     * voice) follow the route change even when no relay socket exists. When
     * a socket IS up, additionally swaps it to a differing winner, or
     * reconnects a disconnected socket on the same winner — preserving the
     * pre-refactor relay-path behavior.
     */
    private fun scheduleNetworkReResolve(closeReason: String) {
        if (endpointResolver == null) return
        networkResolveJob?.cancel()
        networkResolveJob = scope.launch {
            delay(NETWORK_RESOLVE_DEBOUNCE_MS)
            val current = serverUrl
            val resolved = resolveBestEndpointSafe()
            if (resolved == null) {
                // Don't clear a live socket's endpoint on a transient probe
                // miss — only drop the published route when nothing is
                // actually connected.
                if (_connectionState.value != ConnectionState.Connected) {
                    _activeEndpoint.value = null
                }
                return@launch
            }
            // Endpoint hysteresis: a transient blip can make the active
            // (higher-priority) endpoint's health probe miss, so the resolver
            // falls through to a LOWER-priority fallback. Switching on that
            // transient miss rebuilds the chat client and CANCELS an in-flight
            // turn. Don't switch DOWN in priority unless a sustained loss was
            // actually declared (the onLost grace elapsed). Same/upgrade
            // winners always publish.
            val active = _activeEndpoint.value
            if (active != null && resolved.priority > active.priority && !sustainedLossDeclared) {
                Log.i(
                    TAG,
                    "re-resolve picked lower-priority ${resolved.role}(p${resolved.priority}) over " +
                        "active ${active.role}(p${active.priority}) not confirmed dead — keeping active",
                )
                return@launch
            }
            sustainedLossDeclared = false
            _activeEndpoint.value = resolved
            if (current == null) return@launch
            // After an explicit disconnect() the route still publishes above
            // (HTTP surfaces keep roaming), but no socket action: without
            // this gate a network event whose winner differs from the last
            // URL would resurrect a socket the user deliberately closed.
            // (connectToUrlOnMainPath force-sets shouldReconnect = true, so
            // the swap path never re-checked it.)
            if (!shouldReconnect) return@launch
            val normalizedNew = normalizeRelayUrl(resolved.relay.url)
            if (normalizedNew != current) {
                Log.i(TAG, "network change: swapping $current → $normalizedNew")
                connectToUrlOnMainPath(resolved.relay.url, closeReason)
            } else if (_connectionState.value == ConnectionState.Disconnected &&
                reconnectGate()
            ) {
                Log.i(TAG, "network change: same winner is disconnected — reconnecting $current")
                doConnect(current)
            }
        }
    }

    private fun ensureNetworkCallbackRegistered() {
        val ctx = context ?: return
        if (networkCallback != null) return
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network onAvailable — re-evaluating endpoint")
                // A network returned — cancel any pending loss reaction: the
                // drop was transient, so don't switch routes / rebuild the chat
                // client / cancel an in-flight turn. Re-resolve to pick the best
                // route (usually the same one); the rebuild only fires if the
                // URL actually moved.
                networkLossJob?.cancel()
                endpointResolver?.clearCache()
                scheduleNetworkReResolve("Network change — switching endpoint")
            }

            override fun onLost(network: Network) {
                // Defer the reaction: a transient blip recovers within the grace
                // (onAvailable cancels this job). Reacting immediately — marking
                // the active endpoint unreachable + re-resolving to a fallback —
                // switches routes mid-blip, which rebuilds the chat client and
                // CANCELS the in-flight turn. The gateway client already handles
                // its own socket reconnect across the blip.
                Log.i(TAG, "network onLost — deferring fallback re-resolve by ${NETWORK_LOSS_GRACE_MS}ms")
                networkLossJob?.cancel()
                networkLossJob = scope.launch {
                    delay(NETWORK_LOSS_GRACE_MS)
                    Log.i(TAG, "network loss sustained past grace — marking active endpoint unreachable and resolving fallback")
                    sustainedLossDeclared = true
                    endpointResolver?.clearCache()
                    markActiveEndpointUnreachable("network lost (sustained)")
                    scheduleNetworkReResolve("Network lost — switching endpoint")
                }
            }
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.i(TAG, "registered NetworkCallback for ADR 24 re-resolution")
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        networkLossJob?.cancel()
        networkLossJob = null
        val ctx = context ?: return
        val cb = networkCallback ?: return
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterNetworkCallback failed: ${e.message}")
        } finally {
            networkCallback = null
        }
    }

    private fun normalizeRelayUrl(url: String): String {
        // Strip scheme to reason about the path portion cheaply.
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val afterScheme = url.substring(schemeEnd + 3)
        val pathStart = afterScheme.indexOf('/')
        return if (pathStart < 0) {
            // No path at all — append /ws
            "$url/ws"
        } else {
            val path = afterScheme.substring(pathStart)
            // Empty or root path — append ws
            if (path == "/" || path.isEmpty()) "${url.trimEnd('/')}/ws" else url
        }
    }

    fun disconnect() {
        shouldReconnect = false
        DiagnosticsLog.record(
            category = DiagnosticCategory.Relay,
            severity = DiagnosticSeverity.Info,
            title = "Relay socket disconnect requested",
            url = serverUrl,
        )
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
        _isInsecureConnection.value = false
        // ADR 24: clear manual override on explicit disconnect — a "Use
        // now" switch lasts until the user disconnects, then resets to
        // resolver-picked. A sticky preferredRouteRole is re-installed by
        // the ViewModel on the next connection load.
        _manualRoleOverride.value = null
        _activeEndpoint.value = null
    }

    fun shutdown() {
        disconnect()
        unregisterNetworkCallback()
        supervisorJob.cancel()
        // evictAll() closes live wss sockets synchronously; on a TLS keep-alive
        // that close is a network write, so keep it off the main thread.
        shutdownOffMainThread("ConnectionManager-shutdown") {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    fun send(envelope: Envelope) {
        val text = json.encodeToString(envelope)
        webSocket?.send(text)
    }

    private fun isActiveSocket(socket: WebSocket): Boolean = webSocket === socket

    private fun doConnect(
        url: String,
        previousSocketToClose: WebSocket? = null,
        replaceReason: String = "Relay socket replaced",
    ) {
        val existingState = _connectionState.value
        if (previousSocketToClose == null &&
            serverUrl == url &&
            (existingState == ConnectionState.Connecting ||
                existingState == ConnectionState.Connected ||
                existingState == ConnectionState.Reconnecting)
        ) {
            Log.i(TAG, "doConnect: already ${existingState.name.lowercase()} to $url — skipping duplicate open")
            return
        }

        _connectionState.value = if (reconnectAttempt > 0) {
            ConnectionState.Reconnecting
        } else {
            ConnectionState.Connecting
        }

        scope.launch { doConnectInternal(url, previousSocketToClose, replaceReason) }
    }

    private fun doConnectInternal(
        url: String,
        previousSocketToClose: WebSocket? = null,
        replaceReason: String = "Relay socket replaced",
    ) {
        // Rebuild the client so the CertificatePinner picks up the current
        // pin store snapshot — crucial right after applyServerIssuedCodeAndReset
        // wipes a pin for re-pair. buildClient() does a tiny DataStore read
        // via runBlocking, so it runs on the IO dispatcher inside [scope].
        client = buildClient()

        val request = Request.Builder()
            .url(url)
            .build()

        Log.i(TAG, "doConnect: opening WSS to $url")
        val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isActiveSocket(webSocket)) {
                    Log.i(TAG, "onOpen: stale WSS handshake ignored ($url)")
                    runCatching { webSocket.close(1000, "Stale relay socket") }
                    webSocket.cancel()
                    return
                }
                reconnectAttempt = 0
                lastUpgradeResponseCode = null
                _connectionState.value = ConnectionState.Connected
                Log.i(TAG, "onOpen: WSS handshake complete ($url)")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Relay,
                    severity = DiagnosticSeverity.Info,
                    title = "Relay socket connected",
                    url = url,
                )

                // TOFU: record the peer cert fingerprint if we don't have one
                // yet. OkHttp populates response.handshake when the connection
                // was upgraded over TLS; ws:// plaintext connections skip this.
                certPinStore?.let { store ->
                    val handshake = response.handshake
                    val peerCerts = handshake?.peerCertificates
                    if (peerCerts != null && peerCerts.isNotEmpty()) {
                        scope.launch {
                            try {
                                store.recordPinIfAbsent(url, peerCerts)
                            } catch (e: Exception) {
                                Log.w(TAG, "recordPinIfAbsent failed: ${e.message}")
                            }
                        }
                    }
                }

                multiplexer.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isActiveSocket(webSocket)) {
                    Log.i(TAG, "onMessage: stale WSS envelope ignored ($url)")
                    return
                }
                try {
                    val envelope = json.decodeFromString<Envelope>(text)
                    multiplexer.route(envelope)
                } catch (e: Exception) {
                    Log.w(TAG, "Malformed relay envelope: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "onClosing: code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isActiveSocket(webSocket)) {
                    Log.i(TAG, "onClosed: stale WSS close ignored ($url code=$code reason=$reason)")
                    return
                }
                Log.i(TAG, "onClosed: code=$code reason=$reason")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Relay,
                    severity = DiagnosticSeverity.Warning,
                    title = "Relay socket closed",
                    detail = "code=$code reason=$reason",
                    url = url,
                )
                _connectionState.value = ConnectionState.Disconnected
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isActiveSocket(webSocket)) {
                    Log.i(TAG, "onFailure: stale WSS failure ignored ($url ${t.javaClass.simpleName}: ${t.message})")
                    return
                }
                val code = response?.code
                Log.w(TAG, "onFailure: ${t.javaClass.simpleName}: ${t.message} (responseCode=$code)")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Relay,
                    severity = DiagnosticSeverity.Error,
                    title = "Relay socket failed",
                    detail = listOfNotNull(
                        t.javaClass.simpleName,
                        t.message,
                        code?.let { "HTTP $it" },
                    ).joinToString(": "),
                    url = url,
                )
                lastUpgradeResponseCode = code
                if (response == null) {
                    markActiveEndpointUnreachable("socket failure")
                }
                _connectionState.value = ConnectionState.Disconnected
                scheduleReconnect()
            }
        })
        webSocket = newSocket
        previousSocketToClose
            ?.takeIf { it !== newSocket }
            ?.let { staleSocket ->
                runCatching { staleSocket.close(1000, replaceReason) }
                staleSocket.cancel()
            }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        // Defense-in-depth: if auth state says we shouldn't be reconnecting
        // (no session token, no pending pair code), abort before we spend
        // an attempt. This catches the "clearSession wiped auth state but
        // the reconnect scheduler didn't get the memo" class of bug —
        // without this, we'd fire invalid-credential auth envelopes into
        // the rate limiter and block ourselves.
        if (!reconnectGate()) {
            Log.i(TAG, "scheduleReconnect: gate says no pair context — aborting retry")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Session,
                severity = DiagnosticSeverity.Warning,
                title = "Relay reconnect skipped",
                detail = "No paired session or pending pair code",
                url = serverUrl,
            )
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        val url = serverUrl ?: return
        reconnectAttempt++

        // Server-issued 429 means we're IP-banned — keep retrying at our
        // normal exponential cadence and we'll re-fill the ban bucket on
        // every attempt, extending the ban indefinitely. Wait out the
        // server's full block window instead.
        val backoffMs = if (lastUpgradeResponseCode == 429) {
            Log.i(TAG, "scheduleReconnect: rate-limited (429) — backing off ${RATE_LIMIT_BACKOFF_MS}ms")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Relay,
                severity = DiagnosticSeverity.Warning,
                title = "Relay reconnect delayed",
                detail = "Rate limited; retrying in ${RATE_LIMIT_BACKOFF_MS / 1000}s",
                url = url,
            )
            RATE_LIMIT_BACKOFF_MS
        } else {
            (BASE_BACKOFF_MS * (1L shl minOf(reconnectAttempt - 1, 4)))
                .coerceAtMost(MAX_BACKOFF_MS)
        }
        if (lastUpgradeResponseCode != 429) {
            DiagnosticsLog.record(
                category = DiagnosticCategory.Relay,
                severity = DiagnosticSeverity.Info,
                title = "Relay reconnect scheduled",
                detail = "Retrying in ${backoffMs / 1000}s",
                url = url,
            )
        }

        scope.launch {
            delay(backoffMs)
            // Re-check the gate after the backoff — by the time the delay
            // expires, auth state may have changed (e.g., user hit Revoke
            // during the retry window).
            if (shouldReconnect && reconnectGate()) {
                val resolved = resolveBestEndpointSafe()
                val targetUrl = resolved?.relay?.url
                if (resolved != null) {
                    _activeEndpoint.value = resolved
                } else {
                    _activeEndpoint.value = null
                }
                if (targetUrl != null && normalizeRelayUrl(targetUrl) != url) {
                    Log.i(TAG, "scheduleReconnect: switching $url → ${normalizeRelayUrl(targetUrl)}")
                    connectToUrlOnMainPath(targetUrl)
                } else {
                    doConnect(url)
                }
            } else if (!reconnectGate()) {
                Log.i(TAG, "scheduleReconnect: gate turned false during backoff — aborting retry")
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }
}

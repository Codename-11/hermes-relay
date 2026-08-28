package com.hermesandroid.relay.network.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import com.hermesandroid.relay.R
import com.hermesandroid.relay.auth.CertPinStore
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpointContract
import com.hermesandroid.relay.data.isDashboardRelayIngressUrl
import com.hermesandroid.relay.data.primaryRouteUrl
import com.hermesandroid.relay.data.PairingPreferences
import com.hermesandroid.relay.network.shared.pluginProxyRoutesOrNull
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.diagnostics.NetworkDiagnosticGuidance
import com.hermesandroid.relay.network.relay.models.Envelope
import com.hermesandroid.relay.network.shared.EndpointResolver
import com.hermesandroid.relay.network.shared.EndpointSurface
import com.hermesandroid.relay.network.shared.fullJitterDelayMs
import com.hermesandroid.relay.network.shutdownOffMainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting
}

internal fun isRelayRateLimitBackoffActive(untilMs: Long, nowMs: Long): Boolean =
    untilMs > nowMs

internal fun canOverrideScheduledRelayReconnect(
    state: ConnectionState,
    backoffWaiting: Boolean,
    rateLimitBackoffActive: Boolean,
): Boolean = !rateLimitBackoffActive && when (state) {
    ConnectionState.Disconnected -> true
    ConnectionState.Reconnecting -> backoffWaiting
    ConnectionState.Connecting,
    ConnectionState.Connected -> false
}

/**
 * Build an OkHttp request for a relay socket URL, or `null` if the URL is
 * malformed. OkHttp's [Request.Builder.url] throws [IllegalArgumentException]
 * on an invalid host; the relay connect runs on a background coroutine, so an
 * uncaught throw crashes the app (the #131 "Invalid URL host" class). Callers
 * treat `null` as a connection failure instead of letting it propagate.
 */
internal fun buildRelayRequestOrNull(url: String): Request? =
    try {
        Request.Builder().url(url).build()
    } catch (e: IllegalArgumentException) {
        null
    }

private fun EndpointCandidate.relayWebSocketUrl(): String? =
    pluginProxyRoutesOrNull()?.relayWebSocketUrl ?: relay?.url

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
    /** Random source for ordinary reconnect full-jitter; exact backoffs never use it. */
    private val reconnectJitterUnit: () -> Double = { kotlin.random.Random.nextDouble() },
    /** Exact-authority pinned client for a plugin-proxy WSS URL. */
    private val proxyClientProvider: ((String) -> OkHttpClient?)? = null,
    /** Test seam for observing lifecycle teardown without opening a socket. */
    private val okHttpClientFactory: (() -> OkHttpClient)? = null,
    /**
     * Builds a Dashboard-authorized WebSocket request for plugin ingress.
     * Implementations mint a fresh single-use Dashboard WS ticket on every
     * invocation. Direct Relay listeners never call this provider.
     */
    private val dashboardRelayRequestProvider: (suspend (String) -> Request?)? = null,
    /** Deterministic race seam immediately before an ingress failure may poison route state. */
    private val beforeIngressFailureCommit: suspend () -> Unit = {},
) {
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun buildClient(url: String? = null): OkHttpClient {
        okHttpClientFactory?.let { return it() }
        val builder = OkHttpClient.Builder()
            // OkHttp's 10s default connectTimeout is LAN-tuned; a Tailscale
            // DERP-relayed cold-start handshake can exceed it, and a failed
            // connect feeds the onFailure → markUnreachable → route-flap loop.
            // Give the remote first-handshake room to complete.
            .connectTimeout(20, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
        // Swap in the current pin snapshot on every connect. We DON'T hold a
        // long-lived OkHttpClient with a stale pinner — otherwise a re-pair
        // that wipes a pin would still be subject to the pre-wipe rules.
        certPinStore?.let { store ->
            try {
                builder.certificatePinner(
                    url?.let(store::buildPinnerSnapshotFor) ?: store.buildPinnerSnapshot(),
                )
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
    private val socketGeneration = AtomicLong(0L)
    @Volatile
    private var activeSocketGeneration: Long = 0L

    @Volatile
    private var serverUrl: String? = null
    private val reconnectState = RelayReconnectState()
    @Volatile
    private var reconnectJob: Job? = null
    @Volatile
    private var reconnectBackoffWaiting = false
    private var shouldReconnect = true
    // Last HTTP status seen during WSS upgrade, captured in onFailure.
    // Used by scheduleReconnect() to pick an appropriate backoff — notably
    // a much longer one when the server is rate-limiting us (HTTP 429) so
    // we don't re-fill the ban bucket and brick our own auth window.
    @Volatile
    private var lastUpgradeResponseCode: Int? = null
    @Volatile
    private var rateLimitBackoffUntilMs: Long = 0L

    // The relay requires the FIRST frame on a socket to be `system/auth` and
    // rejects the whole connection otherwise ("expected system/auth, got
    // <channel>/<type>"). `authenticated` gates [send] so nothing (notably the
    // periodic bridge.status reporter) can race the auth handshake on a fresh
    // or reconnecting socket. False from the start of every connect until the
    // server confirms `auth.ok`; reset on close/failure/disconnect.
    @Volatile
    private var authenticated = false

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
    private val _activeApiEndpoint = MutableStateFlow<EndpointCandidate?>(null)
    val activeApiEndpoint: StateFlow<EndpointCandidate?> = _activeApiEndpoint.asStateFlow()

    /** Relay-only winner, deliberately separate from the standard route. */
    private val _activeRelayEndpoint = MutableStateFlow<EndpointCandidate?>(null)
    val activeRelayEndpoint: StateFlow<EndpointCandidate?> = _activeRelayEndpoint.asStateFlow()

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

    /** Optional API discovery is never part of Dashboard/Gateway readiness. */
    @Volatile
    private var apiResolveJob: Job? = null
    private var apiResolveRevision: Long = 0L

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
        // How many consecutive failures on one relay socket URL before we mark
        // that candidate's Relay surface unreachable. Standard Dashboard/API
        // reachability is cached independently and remains healthy.
        private const val MARK_UNREACHABLE_AFTER_FAILURES = 2
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

        // Slow-poll tier. Against a paired-but-genuinely-dead server the
        // exponential backoff otherwise caps at ~16s and retries forever, which
        // is steady battery + log noise for no benefit. After this many
        // consecutive failed attempts (~5 min of continuous failure at the cap)
        // we drop to a 5-min poll until the server recovers. A network change
        // re-resolves + reconnects immediately regardless of this delay (see the
        // onAvailable callback), and reconnectAttempt resets to 0 on a
        // successful onOpen, so recovery is never gated on the slow interval.
        private const val SLOW_POLL_AFTER_ATTEMPTS = 20
        private const val SLOW_POLL_BACKOFF_MS = 300_000L
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
                title = context?.getString(R.string.conn_diag_insecure_mode) ?: "Insecure relay mode enabled",
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
            val resolved = resolveBestEndpointSafe(EndpointSurface.Dashboard)
                ?: resolveLegacyStandardFallbackSafe()
            scheduleApiResolution()
            val relayResolved = resolveBestEndpointSafe(EndpointSurface.Relay)
            val resolvedRelayUrl = relayResolved?.relayWebSocketUrl()?.takeIf { it.isNotBlank() }
            val targetUrl = resolvedRelayUrl ?: url.takeIf { it.isNotBlank() }
            _activeRelayEndpoint.value = relayResolved
            if (resolved != null) {
                _activeEndpoint.value = resolved
                Log.i(TAG, "connect: standard resolver picked role=${resolved.role} " +
                    "route=${resolved.primaryRouteUrl()}")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Endpoint,
                    severity = DiagnosticSeverity.Info,
                    title = context?.getString(R.string.conn_diag_route_selected) ?: "Route selected",
                    endpointRole = resolved.role,
                    url = resolved.primaryRouteUrl(),
                )
            } else {
                _activeEndpoint.value = null
                Log.d(TAG, "connect: no resolver winner — using supplied url $url")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Relay,
                    severity = DiagnosticSeverity.Warning,
                    title = context?.getString(R.string.conn_diag_using_configured_url) ?: "Using configured relay URL",
                    detail = "No resolver winner",
                    url = url,
                )
            }
            relayResolved?.let { relayRoute ->
                Log.i(
                    TAG,
                    "connect: relay resolver picked role=${relayRoute.role} " +
                        "url=${relayRoute.relayWebSocketUrl()}",
                )
            }
            if (targetUrl != null) {
                connectToUrlOnMainPath(targetUrl)
            } else {
                Log.d(TAG, "connect: selected route has no Relay surface; route published for HTTP/Gateway clients")
            }
        }
    }

    /**
     * Replace an ordinary scheduled reconnect with an immediate attempt.
     *
     * Foregrounding the app or opening Relay status is an explicit signal that
     * the route may be usable again, so exponential/slow-poll backoff should not
     * make the user wait. A server-issued 429 is different: retrying early would
     * extend the server block, so that protected backoff is never overridden.
     */
    fun reconnectNowIfAllowed(url: String): Boolean {
        val rateLimitActive = isRelayRateLimitBackoffActive(
            rateLimitBackoffUntilMs,
            SystemClock.elapsedRealtime(),
        )
        if (!canOverrideScheduledRelayReconnect(
                state = _connectionState.value,
                backoffWaiting = reconnectBackoffWaiting,
                rateLimitBackoffActive = rateLimitActive,
            )
        ) {
            if (rateLimitActive) {
                Log.i(TAG, "reconnectNowIfAllowed: preserving rate-limit backoff")
            }
            return false
        }
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectBackoffWaiting = false
        // connectToUrlOnMainPath suppresses duplicate opens while the manager is
        // Reconnecting. Move to the honest idle state before starting the fresh
        // resolver/open path; the ViewModel's grace window prevents UI flicker.
        _connectionState.value = ConnectionState.Disconnected
        connect(url)
        return true
    }

    /**
     * Reopen the current authenticated Relay socket without discarding pair
     * state. Used only as a compatibility fallback when an older Relay does
     * not acknowledge a post-auth metadata update; the replacement socket's
     * normal `system/auth` frame carries the latest metadata.
     */
    fun reconnectForAuthenticatedMetadataUpdate(): Boolean {
        val targetUrl = serverUrl?.takeIf { it.isNotBlank() } ?: return false
        if (isRelayRateLimitBackoffActive(
                rateLimitBackoffUntilMs,
                SystemClock.elapsedRealtime(),
            )
        ) {
            Log.i(TAG, "metadata reconnect: preserving active rate-limit backoff")
            return false
        }
        val previousSocket = webSocket
        if (previousSocket == null) {
            connect(targetUrl)
        } else {
            doConnect(
                targetUrl,
                previousSocketToClose = previousSocket,
                replaceReason = "Relay metadata compatibility refresh",
            )
        }
        return true
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
        preserveReconnectBackoff: Boolean = false,
    ) {
        val endpoints = RelayEndpointContract.parseOrNull(url)
        if (endpoints == null) {
            Log.e(TAG, "Invalid Relay URL")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Relay,
                severity = DiagnosticSeverity.Error,
                title = context?.getString(R.string.conn_diag_url_invalid) ?: "Relay socket URL invalid",
                detail = "Malformed or unsafe Relay URL",
                operation = "Open Relay WebSocket",
                configuredUrl = url,
                suggestion = "Use a Relay URL with no credentials, query, or fragment.",
            )
            return
        }
        val normalized = endpoints.webSocketUrl
        val isInsecure = normalized.startsWith("ws://", ignoreCase = true)
        if (isInsecure && !_insecureMode.value) {
            Log.e(TAG, "Blocked ws:// connection — insecure mode is disabled. Use wss:// or enable insecure mode in Settings.")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Relay,
                severity = DiagnosticSeverity.Error,
                title = context?.getString(R.string.conn_diag_socket_blocked) ?: "Relay socket blocked",
                detail = "ws:// is disabled",
                operation = "Open Relay WebSocket",
                configuredUrl = url,
                suggestion = "Use wss:// or explicitly allow plain ws:// for a trusted LAN or VPN.",
            )
            return
        }
        if (isRelayRateLimitBackoffActive(
                rateLimitBackoffUntilMs,
                SystemClock.elapsedRealtime(),
            )
        ) {
            Log.i(TAG, "connect: preserving active rate-limit backoff")
            return
        }
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
                title = context?.getString(R.string.conn_diag_opening_insecure) ?: "Opening insecure relay socket",
                operation = "Open Relay WebSocket",
                configuredUrl = url,
                requestUrl = normalized,
            )
        } else {
            DiagnosticsLog.record(
                category = DiagnosticCategory.Relay,
                severity = DiagnosticSeverity.Info,
                title = context?.getString(R.string.conn_diag_opening_socket) ?: "Opening relay socket",
                operation = "Open Relay WebSocket",
                configuredUrl = url,
                requestUrl = normalized,
            )
        }

        serverUrl = normalized
        shouldReconnect = true
        if (preserveReconnectBackoff) {
            reconnectState.beginAutomaticRouteSwap(normalized)
        } else {
            reconnectState.beginExplicitConnect(normalized)
        }
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
    suspend fun resolveBestEndpoint(): EndpointCandidate? =
        resolveBestEndpointSafe(EndpointSurface.Dashboard)
            ?: resolveLegacyStandardFallbackSafe()

    private suspend fun resolveLegacyStandardFallbackSafe(): EndpointCandidate? =
        resolveBestEndpointSafe(EndpointSurface.Standard) { candidate ->
            candidate.dashboard?.url.isNullOrBlank() &&
                candidate.pluginProxyRoutesOrNull()?.dashboardBaseUrl == null
        }

    private suspend fun resolveBestEndpointSafe(
        surface: EndpointSurface,
        candidateFilter: (EndpointCandidate) -> Boolean = { true },
    ): EndpointCandidate? {
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

        val eligibleEndpoints = endpoints.filter(candidateFilter)
        if (eligibleEndpoints.isEmpty()) return null

        // Manual override: if the user pinned a role in the Endpoints card,
        // try that one first; fall through to the strict-priority algorithm
        // if it isn't reachable.
        _manualRoleOverride.value?.let { preferredRole ->
            val preferred = eligibleEndpoints.firstOrNull {
                it.role.equals(preferredRole, ignoreCase = true)
            }
            if (preferred != null) {
                // Single-element list still respects the 2s probe gate.
                val winner = resolver.resolve(listOf(preferred), surface)
                if (winner != null) return winner
                Log.i(TAG, "manualRoleOverride=$preferredRole not reachable — " +
                    "falling through to strict-priority resolve")
            }
        }

        return resolver.resolve(eligibleEndpoints, surface)
    }

    /**
     * Discover the optional API fallback without holding up the standard
     * Dashboard/Gateway route. A single manager-level job coalesces lifecycle
     * callers; [EndpointResolver] additionally shares an in-flight request per
     * route/surface. The negative cache keeps ordinary profile changes cheap,
     * while network callbacks and explicit probes still invalidate it.
     */
    private fun scheduleApiResolution() {
        synchronized(this) {
            apiResolveRevision += 1L
            if (apiResolveJob?.isActive != true) {
                startApiResolutionLocked(apiResolveRevision)
            }
        }
    }

    /** Caller must hold this manager's monitor. */
    private fun startApiResolutionLocked(revision: Long) {
        apiResolveJob = scope.launch {
            try {
                val resolved = resolveBestEndpointSafe(EndpointSurface.Api)
                synchronized(this@ConnectionManager) {
                    // A route/connection refresh may have arrived while this
                    // optional probe was waiting. Never publish its stale
                    // winner over the newer connection's API ownership.
                    if (revision == apiResolveRevision) {
                        _activeApiEndpoint.value = resolved
                    }
                }
            } finally {
                synchronized(this@ConnectionManager) {
                    apiResolveJob = null
                    if (revision != apiResolveRevision && supervisorJob.isActive) {
                        startApiResolutionLocked(apiResolveRevision)
                    }
                }
            }
        }
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
        val resolved = resolveBestEndpointSafe(EndpointSurface.Dashboard)
            ?: resolveLegacyStandardFallbackSafe()
        scheduleApiResolution()
        val relayResolved = resolveBestEndpointSafe(EndpointSurface.Relay)
        if (resolved == null && _connectionState.value == ConnectionState.Connected) {
            // Transient probe miss while the relay socket is demonstrably up
            // — keep the live route published rather than downgrading every
            // HTTP surface to the saved URL. Mirrors refreshActiveEndpoint.
            return _activeEndpoint.value
        }
        _activeEndpoint.value = resolved
        if (relayResolved != null) _activeRelayEndpoint.value = relayResolved
        val targetUrl = relayResolved?.relayWebSocketUrl() ?: current ?: return resolved
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
        val resolved = resolveBestEndpointSafe(EndpointSurface.Dashboard)
            ?: resolveLegacyStandardFallbackSafe()
        scheduleApiResolution()
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

    private fun markActiveRelayEndpointUnreachable(reason: String) {
        val active = _activeRelayEndpoint.value ?: return
        endpointResolver?.markUnreachable(active, EndpointSurface.Relay)
        Log.i(TAG, "marked endpoint role=${active.role} unreachable ($reason)")
    }

    /** Admission is stronger evidence than `/transport/health`: reject this ingress and retain direct fallback. */
    private suspend fun fallbackFromBrokenDashboardIngress(
        url: String,
        reason: String,
        failingSocket: WebSocket? = null,
        failingGeneration: Long? = null,
    ): Boolean {
        if (!isDashboardRelayIngressUrl(url)) return false
        if (failingGeneration != null) {
            beforeIngressFailureCommit()
            if (activeSocketGeneration != failingGeneration || webSocket !== failingSocket) {
                Log.i(TAG, "Ignoring stale Dashboard ingress failure ($reason)")
                return false
            }
        }
        val failed = _activeRelayEndpoint.value ?: return false
        val failedUrl = failed.relayWebSocketUrl()?.let(::normalizeRelayUrl)
        if (failedUrl != normalizeRelayUrl(url)) return false
        endpointResolver?.markUnreachable(failed, EndpointSurface.Relay) ?: return false
        val replacement = resolveBestEndpointSafe(EndpointSurface.Relay) ?: return false
        val replacementUrl = replacement.relayWebSocketUrl()?.takeIf(String::isNotBlank) ?: return false
        if (normalizeRelayUrl(replacementUrl) == normalizeRelayUrl(url)) return false
        _activeRelayEndpoint.value = replacement
        Log.i(TAG, "Dashboard Relay ingress rejected; switching to ${replacement.role} ($reason)")
        DiagnosticsLog.record(
            category = DiagnosticCategory.Relay,
            severity = DiagnosticSeverity.Warning,
            title = "Relay ingress unavailable",
            detail = "Dashboard admission failed; using retained direct Relay route.",
            operation = "Select Relay transport after admission failure",
            endpointRole = failed.role,
            requestUrl = url,
        )
        connectToUrlOnMainPath(
            replacementUrl,
            replaceReason = "Dashboard Relay ingress admission failed",
        )
        return true
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
    private fun scheduleNetworkReResolve(closeReason: String, wipeCache: Boolean) {
        if (endpointResolver == null) return
        networkResolveJob?.cancel()
        networkResolveJob = scope.launch {
            delay(NETWORK_RESOLVE_DEBOUNCE_MS)
            // Wipe the probe cache INSIDE the debounced job (not synchronously in
            // onAvailable) so a burst of network/VPN-interface callbacks —
            // Tailscale's tun churns onAvailable repeatedly — coalesces into a
            // single cache wipe + re-probe instead of one per event. onLost
            // manages its own cache (clear + markUnreachable) and passes false.
            if (wipeCache) endpointResolver.clearCache()
            val current = serverUrl
            val resolved = resolveBestEndpointSafe(EndpointSurface.Dashboard)
                ?: resolveLegacyStandardFallbackSafe()
            scheduleApiResolution()
            if (resolved == null) {
                // Hysteresis for the AUTOMATIC (network-callback) path. A
                // transient cold-route probe miss must NOT null the published
                // endpoint: effectiveApiServerUrl/effectiveDashboardUrl then fall
                // back to the saved (home-LAN) host — dead for a remote device —
                // and rebuild the chat client against it. That is the Tailscale
                // reconnect loop. The old guard keyed on the relay socket being
                // Connected, which the standard (no-relay) chat path never
                // reaches, so it protected nobody there. Keep the last-known
                // route unless a sustained loss was actually declared (onLost
                // grace elapsed) or there was never a route to keep.
                if (sustainedLossDeclared || _activeEndpoint.value == null) {
                    _activeEndpoint.value = null
                } else {
                    Log.i(TAG, "re-resolve miss but ${_activeEndpoint.value?.role} was live and loss not sustained — keeping route")
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
            val relayResolved = resolveBestEndpointSafe(EndpointSurface.Relay)
            if (relayResolved != null) _activeRelayEndpoint.value = relayResolved
            val relayUrl = relayResolved?.relayWebSocketUrl()?.takeIf { it.isNotBlank() }
                ?: return@launch
            if (isRelayRateLimitBackoffActive(
                    rateLimitBackoffUntilMs,
                    SystemClock.elapsedRealtime(),
                )
            ) {
                // Keep publishing the newly resolved standard/Relay routes, but
                // leave the protected retry job intact. It will resolve the
                // latest Relay winner again when the server cooldown expires.
                Log.i(TAG, "network change: preserving rate-limit retry job")
                return@launch
            }
            val normalizedNew = normalizeRelayUrl(relayUrl)
            if (normalizedNew != current) {
                Log.i(TAG, "network change: swapping $current → $normalizedNew")
                if (reconnectBackoffWaiting) {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    reconnectBackoffWaiting = false
                }
                connectToUrlOnMainPath(
                    relayUrl,
                    closeReason,
                    preserveReconnectBackoff = true,
                )
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
                // Cache wipe happens inside the debounced re-resolve so a burst
                // of onAvailable (VPN tun churn) coalesces into one wipe+probe.
                scheduleNetworkReResolve("Network change — switching endpoint", wipeCache = true)
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
                    _activeEndpoint.value?.let { active ->
                        endpointResolver?.markUnreachable(active, EndpointSurface.Standard)
                    }
                    // wipeCache=false: we just cleared + poisoned the dead route
                    // above; re-wiping inside the job would drop that negative
                    // entry and let the dead route win the resolve again.
                    scheduleNetworkReResolve("Network lost — switching endpoint", wipeCache = false)
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

    private fun normalizeRelayUrl(url: String): String =
        RelayEndpointContract.parseOrNull(url)?.webSocketUrl ?: url

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectBackoffWaiting = false
        rateLimitBackoffUntilMs = 0L
        lastUpgradeResponseCode = null
        DiagnosticsLog.record(
            category = DiagnosticCategory.Relay,
            severity = DiagnosticSeverity.Info,
            title = context?.getString(R.string.conn_diag_disconnect_requested) ?: "Relay socket disconnect requested",
            url = serverUrl,
        )
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        authenticated = false
        _connectionState.value = ConnectionState.Disconnected
        _isInsecureConnection.value = false
        // ADR 24: clear manual override on explicit disconnect — a "Use
        // now" switch lasts until the user disconnects, then resets to
        // resolver-picked. A sticky preferredRouteRole is re-installed by
        // the ViewModel on the next connection load.
        _manualRoleOverride.value = null
        _activeEndpoint.value = null
        _activeApiEndpoint.value = null
        _activeRelayEndpoint.value = null
        reconnectState.reset()
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
        // Hold every non-auth frame until the server has accepted our
        // `system/auth` envelope. Otherwise a sender that fires on its own
        // cadence — e.g. BridgeStatusReporter's 30s/immediate tick — can beat
        // the auth handshake on a fresh socket, and the relay rejects the
        // whole connection (forcing a reconnect). Dropping a periodic frame is
        // harmless: the next tick re-sends once authenticated.
        val isAuthFrame = envelope.channel == "system" && envelope.type == "auth"
        if (!authenticated && !isAuthFrame) {
            Log.d(TAG, "send: holding ${envelope.channel}/${envelope.type} until auth.ok")
            return
        }
        val text = json.encodeToString(envelope)
        webSocket?.send(text)
    }

    private fun isActiveSocket(socket: WebSocket, generation: Long): Boolean =
        webSocket === socket && activeSocketGeneration == generation

    private fun doConnect(
        url: String,
        previousSocketToClose: WebSocket? = null,
        replaceReason: String = "Relay socket replaced",
        scheduledReconnect: Boolean = false,
    ) {
        if (isRelayRateLimitBackoffActive(
                rateLimitBackoffUntilMs,
                SystemClock.elapsedRealtime(),
            )
        ) {
            Log.i(TAG, "doConnect: preserving active rate-limit backoff")
            return
        }
        val existingState = _connectionState.value
        if (previousSocketToClose == null &&
            serverUrl == url &&
            (existingState == ConnectionState.Connecting ||
                existingState == ConnectionState.Connected ||
                (existingState == ConnectionState.Reconnecting && !scheduledReconnect))
        ) {
            Log.i(TAG, "doConnect: already ${existingState.name.lowercase()} to $url — skipping duplicate open")
            return
        }

        _connectionState.value = if (reconnectState.reconnectAttempt > 0) {
            ConnectionState.Reconnecting
        } else {
            ConnectionState.Connecting
        }

        scope.launch { doConnectInternal(url, previousSocketToClose, replaceReason) }
    }

    private suspend fun doConnectInternal(
        url: String,
        previousSocketToClose: WebSocket? = null,
        replaceReason: String = "Relay socket replaced",
    ) {
        // Rebuild the client so the CertificatePinner picks up the current
        // pin store snapshot — crucial right after applyServerIssuedCodeAndReset
        // wipes a pin for re-pair. buildClient() does a tiny DataStore read
        // via runBlocking, so it runs on the IO dispatcher inside [scope].
        // Every new socket starts unauthenticated — the send-gate stays closed
        // (auth frame excepted) until this socket's own auth.ok arrives.
        authenticated = false
        val isPluginProxyUrl = _activeRelayEndpoint.value?.pluginProxyRoutesOrNull()
            ?.relayWebSocketUrl
            ?.equals(url, ignoreCase = true) == true
        client = if (isPluginProxyUrl) {
            proxyClientProvider?.invoke(url) ?: run {
                Log.e(TAG, "Pinned plugin proxy client unavailable — refusing generic TLS fallback")
                _connectionState.value = ConnectionState.Disconnected
                return
            }
        } else {
            buildClient(url)
        }

        val request = if (isDashboardRelayIngressUrl(url)) {
            dashboardRelayRequestProvider?.invoke(url)
        } else {
            buildRelayRequestOrNull(url)
        }
        if (request == null) {
            // A malformed relay URL (an invalid/empty host from a corrupt or
            // hand-edited pairing payload) can't be built into a request. This
            // runs on a background coroutine, so letting OkHttp's url() throw
            // would crash the app — the #131 "Invalid URL host" class, relay-
            // socket half. Route it through the same path onFailure uses.
            Log.e(TAG, "doConnect: malformed relay URL '$url' — not connecting")
            DiagnosticsLog.record(
                category = DiagnosticCategory.Relay,
                severity = DiagnosticSeverity.Error,
                title = "Invalid relay URL",
                detail = if (isDashboardRelayIngressUrl(url)) {
                    "Dashboard authorization could not prepare the Relay WebSocket request."
                } else {
                    "The relay address could not be parsed; re-pair to refresh it."
                },
                operation = if (isDashboardRelayIngressUrl(url)) {
                    "Mint Dashboard Relay WebSocket ticket"
                } else {
                    "Build Relay WebSocket request"
                },
                configuredUrl = url,
                suggestion = "Edit or re-pair the Relay route to replace the invalid address.",
            )
            authenticated = false
            _connectionState.value = ConnectionState.Disconnected
            previousSocketToClose?.let { stale ->
                runCatching { stale.close(1000, replaceReason) }
                stale.cancel()
            }
            if (!fallbackFromBrokenDashboardIngress(url, "request provider or ticket unavailable")) {
                scheduleReconnect()
            }
            return
        }

        Log.i(TAG, "doConnect: opening WSS to $url")
        val generation = socketGeneration.incrementAndGet()
        activeSocketGeneration = generation
        val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isActiveSocket(webSocket, generation)) {
                    Log.i(TAG, "onOpen: stale WSS handshake ignored ($url)")
                    runCatching { webSocket.close(1000, "Stale relay socket") }
                    webSocket.cancel()
                    return
                }
                reconnectState.connected(url)
                reconnectJob?.cancel()
                reconnectJob = null
                reconnectBackoffWaiting = false
                rateLimitBackoffUntilMs = 0L
                lastUpgradeResponseCode = null
                _connectionState.value = ConnectionState.Connected
                Log.i(TAG, "onOpen: WSS handshake complete ($url)")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Relay,
                    severity = DiagnosticSeverity.Info,
                    title = context?.getString(R.string.conn_diag_connected) ?: "Relay socket connected",
                    operation = "Relay WebSocket handshake",
                    requestUrl = url,
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
                if (!isActiveSocket(webSocket, generation)) {
                    Log.i(TAG, "onMessage: stale WSS envelope ignored ($url)")
                    return
                }
                try {
                    val envelope = json.decodeFromString<Envelope>(text)
                    // Open the send-gate the instant the server confirms auth,
                    // BEFORE routing — so anything handleAuthOk triggers
                    // (e.g. proactive.subscribe) is allowed through.
                    if (envelope.channel == "system") {
                        when (envelope.type) {
                            "auth.ok" -> authenticated = true
                            "auth.fail" -> authenticated = false
                        }
                    }
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
                if (!isActiveSocket(webSocket, generation)) {
                    Log.i(TAG, "onClosed: stale WSS close ignored ($url code=$code reason=$reason)")
                    return
                }
                Log.i(TAG, "onClosed: code=$code reason=$reason")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Relay,
                    severity = DiagnosticSeverity.Warning,
                    title = context?.getString(R.string.conn_diag_closed) ?: "Relay socket closed",
                    detail = "code=$code reason=$reason",
                    operation = "Relay WebSocket session",
                    requestUrl = url,
                    suggestion = if (code == 1000) null else "Check the Relay server logs for the matching close code and reason.",
                )
                val admitted = authenticated
                authenticated = false
                _connectionState.value = ConnectionState.Disconnected
                if (isDashboardRelayIngressUrl(url) && !admitted && code != 1000) {
                    scope.launch {
                        if (!fallbackFromBrokenDashboardIngress(
                                url,
                                "pre-auth close $code",
                                webSocket,
                                generation,
                            ) && activeSocketGeneration == generation
                        ) {
                            scheduleReconnect()
                        }
                    }
                } else {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isActiveSocket(webSocket, generation)) {
                    Log.i(TAG, "onFailure: stale WSS failure ignored ($url ${t.javaClass.simpleName}: ${t.message})")
                    return
                }
                val code = response?.code
                Log.w(TAG, "onFailure: ${t.javaClass.simpleName}: ${t.message} (responseCode=$code)")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Relay,
                    severity = DiagnosticSeverity.Error,
                    title = context?.getString(R.string.conn_diag_failed) ?: "Relay socket failed",
                    detail = listOfNotNull(
                        t.javaClass.simpleName,
                        t.message,
                        code?.let { "HTTP $it" },
                    ).joinToString(": "),
                    operation = "Relay WebSocket handshake",
                    requestUrl = url,
                    suggestion = code?.let {
                        NetworkDiagnosticGuidance.forHttpStatus(it, "Relay")
                    } ?: NetworkDiagnosticGuidance.forThrowable(t, "Relay"),
                )
                lastUpgradeResponseCode = code
                if (isDashboardRelayIngressUrl(url) && response != null) {
                    authenticated = false
                    _connectionState.value = ConnectionState.Disconnected
                    scope.launch {
                        if (!fallbackFromBrokenDashboardIngress(
                                url,
                                "HTTP admission ${response.code}",
                                webSocket,
                                generation,
                            ) && activeSocketGeneration == generation
                        ) {
                            scheduleReconnect()
                        }
                    }
                    return
                }
                if (response == null) {
                    // Transport-level failure (no HTTP upgrade response): on a
                    // remote (Tailscale) link the first handshake can fail cold.
                    // Don't evict this Relay surface on a single blip — wait
                    // for the same socket URL to fail again. Standard route
                    // health is separate and is never poisoned here.
                    val failureCount = reconnectState.recordSocketFailure(url)
                    if (failureCount >= MARK_UNREACHABLE_AFTER_FAILURES) {
                        markActiveRelayEndpointUnreachable("socket failure x$failureCount")
                    } else {
                        Log.i(TAG, "relay socket failure $failureCount/$MARK_UNREACHABLE_AFTER_FAILURES — not yet poisoning route")
                    }
                }
                authenticated = false
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
                title = context?.getString(R.string.conn_diag_reconnect_skipped) ?: "Relay reconnect skipped",
                detail = "No paired session or pending pair code",
                url = serverUrl,
            )
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        val url = serverUrl ?: return
        val reconnectAttempt = reconnectState.nextReconnectAttempt()
        // Keep the socket lifecycle visibly in-flight for the whole backoff
        // window. Callers such as reconnectIfStale() treat Disconnected as an
        // invitation to call connect() again; leaving this state Disconnected
        // let screen entry restart both the route resolve and the retry counter.
        _connectionState.value = ConnectionState.Reconnecting

        // Server-issued 429 means we're IP-banned — keep retrying at our
        // normal exponential cadence and we'll re-fill the ban bucket on
        // every attempt, extending the ban indefinitely. Wait out the
        // server's full block window instead.
        val backoffMs = when {
            // Server-issued 429 means we're IP-banned — wait out the full
            // block window instead of re-filling the ban bucket at our normal
            // cadence.
            lastUpgradeResponseCode == 429 -> {
                rateLimitBackoffUntilMs = SystemClock.elapsedRealtime() + RATE_LIMIT_BACKOFF_MS
                Log.i(TAG, "scheduleReconnect: rate-limited (429) — backing off ${RATE_LIMIT_BACKOFF_MS}ms")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Relay,
                    severity = DiagnosticSeverity.Warning,
                    title = context?.getString(R.string.conn_diag_reconnect_delayed) ?: "Relay reconnect delayed",
                    detail = "Rate limited; retrying in ${RATE_LIMIT_BACKOFF_MS / 1000}s",
                    url = url,
                )
                RATE_LIMIT_BACKOFF_MS
            }
            // Sustained failure against a paired-but-dead server: stop hammering
            // every ~16s forever; drop to a slow poll until it recovers.
            reconnectAttempt >= SLOW_POLL_AFTER_ATTEMPTS -> {
                Log.i(TAG, "scheduleReconnect: sustained failure (attempt $reconnectAttempt) — slow-polling every ${SLOW_POLL_BACKOFF_MS / 1000}s")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Relay,
                    severity = DiagnosticSeverity.Warning,
                    title = context?.getString(R.string.conn_diag_reconnect_slow_poll) ?: "Relay reconnect slow-polling",
                    detail = "Server unreachable for a while; retrying every ${SLOW_POLL_BACKOFF_MS / 1000}s until it recovers (a network change reconnects immediately)",
                    url = url,
                )
                SLOW_POLL_BACKOFF_MS
            }
            else -> {
                val capMs = (BASE_BACKOFF_MS * (1L shl minOf(reconnectAttempt - 1, 4)))
                    .coerceAtMost(MAX_BACKOFF_MS)
                val ms = fullJitterDelayMs(capMs, reconnectJitterUnit())
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Relay,
                    severity = DiagnosticSeverity.Info,
                    title = context?.getString(R.string.conn_diag_reconnect_scheduled) ?: "Relay reconnect scheduled",
                    detail = "Retrying in ${ms / 1000}s",
                    url = url,
                )
                ms
            }
        }

        reconnectJob?.cancel()
        reconnectBackoffWaiting = true
        val scheduledJob = scope.launch {
            delay(backoffMs)
            reconnectBackoffWaiting = false
            // Re-check the gate after the backoff — by the time the delay
            // expires, auth state may have changed (e.g., user hit Revoke
            // during the retry window).
            if (shouldReconnect && reconnectGate()) {
                val resolved = resolveBestEndpointSafe(EndpointSurface.Relay)
                val targetUrl = resolved?.relayWebSocketUrl()
                if (resolved != null) {
                    // Mirror scheduleNetworkReResolve: clear the sustained-loss
                    // latch on a successful resolve so a later transient miss
                    // doesn't null a route we just reconnected. (The latch is set
                    // in onLost's grace job but can be cleared on EITHER success
                    // edge — network-callback or relay-timer.)
                    sustainedLossDeclared = false
                    _activeRelayEndpoint.value = resolved
                }
                if (targetUrl != null && normalizeRelayUrl(targetUrl) != url) {
                    Log.i(TAG, "scheduleReconnect: switching $url → ${normalizeRelayUrl(targetUrl)}")
                    connectToUrlOnMainPath(
                        targetUrl,
                        preserveReconnectBackoff = true,
                    )
                } else {
                    doConnect(url, scheduledReconnect = true)
                }
            } else if (!reconnectGate()) {
                Log.i(TAG, "scheduleReconnect: gate turned false during backoff — aborting retry")
                _connectionState.value = ConnectionState.Disconnected
            }
        }
        reconnectJob = scheduledJob
        scheduledJob.invokeOnCompletion {
            if (reconnectJob === scheduledJob) {
                reconnectJob = null
                reconnectBackoffWaiting = false
            }
        }
    }
}

package com.hermesandroid.relay.viewmodel.connection

import android.content.Context
import com.hermesandroid.relay.network.upstream.ChatMode
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.DashboardCookieStore
import com.hermesandroid.relay.network.upstream.EncryptedDashboardCookieStore
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.InMemoryDashboardCookieStore
import com.hermesandroid.relay.network.upstream.ServerCapabilities
import com.hermesandroid.relay.network.upstream.resolveStreamingEndpointPreference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the **upstream dashboard/gateway transport clients** and the
 * capability-driven **transport resolution** for
 * [com.hermesandroid.relay.viewmodel.ConnectionViewModel]:
 *
 *  - the per-connection encrypted [DashboardCookieStore] cache (one instance
 *    per connection, process-wide, to avoid Tink/Keystore lock churn);
 *  - the consolidated [DashboardApiClient] factory (previously 4+ scattered
 *    build sites);
 *  - the cached [GatewayChatClient] keyed by connection + resolved dashboard
 *    URL, with the LAN⇄Tailscale mid-turn retarget logic;
 *  - the [gatewayAvailability] tier and the sticky-Unsupported verdict;
 *  - the per-endpoint capability snapshot + [chatMode], and the
 *    `streamingEndpoint`-preference resolution that reads them.
 *
 * Extracted as part of the ConnectionViewModel decomposition (ADR 34
 * follow-up). Pure mechanical lift — every method body is identical to the
 * original; the ViewModel keeps its public getters/functions and delegates.
 *
 * **Deliberately NOT moved here:** the `HermesApiClient` SSE/runs client
 * (`_apiClient`/`_chatApiClient`), the API-server reachability/health flows,
 * and `rebuildApiClient()`/`rebuildChatApiClient()`. Those are written
 * *inline* by several ViewModel-level orchestrators (`saveStandardApiConnection`,
 * `saveApiAndProbeVoice`, `testApiConnection`, `updateApiServerUrl`,
 * `revalidate`) interleaved with diagnostics + result callbacks; lifting them
 * would require a wide mutable interface that relocates the coupling rather
 * than removing it. They stay in the ViewModel by design (see the
 * decomposition plan's "stop if too entangled" rule). This controller exposes
 * [setCapabilitiesAndMode] / [resetCapabilitiesAndMode] so the ViewModel's
 * `rebuildApiClient` can push the capability snapshot it probes.
 *
 * Dependencies are injected as providers/scope (mirroring the
 * [com.hermesandroid.relay.viewmodel.ConnectionSwitchCoordinator] precedent) so
 * resolution follows the live LAN/Tailscale route without this collaborator
 * holding a back-reference to the ViewModel.
 */
class UpstreamTransportController(
    private val context: Context,
    /** Active connection id, or null before any connection is seeded. */
    private val activeConnectionIdProvider: () -> String?,
    /**
     * Dashboard URL for the active connection **on the currently-resolved
     * route** — the snapshot twin of `ConnectionViewModel.effectiveDashboardUrl`.
     * Returns null/blank off the dashboard surface.
     */
    private val dashboardUrlProvider: () -> String?,
    /** Current keep-alive-in-background preference, read per gateway-client build. */
    private val gatewayKeepAliveProvider: () -> Boolean,
    /**
     * Resolves a connection id to its TOKEN-store file key so the dashboard
     * cookie store can ride the connection's already-built token keyset instead
     * of building a second one on cold start. Returns null when the connection
     * isn't known yet — the cookie store then falls back to its own stand-alone
     * `hermes_dashboard_<id>` file (original behavior).
     */
    private val tokenStoreKeyProvider: (String) -> String? = { null },
) {

    // --- Per-connection dashboard cookie stores ----------------------------

    /** Per-connection encrypted cookie stores, cached to avoid Keystore churn. */
    private val dashboardCookieStores =
        ConcurrentHashMap<String, EncryptedDashboardCookieStore>()

    /**
     * Cookie store for [connectionId] — ONE instance per connection,
     * process-wide. Every dashboard-surface consumer (Manage, standard
     * voice, connection validation, app-start pre-warm) must come through
     * here: each EncryptedDashboardCookieStore instance lazily builds its
     * own Keystore-backed prefs, and that build serializes through a
     * process-global Tink lock — N instances for the same file means N
     * multi-second lock holds instead of one.
     */
    fun dashboardCookieStoreFor(connectionId: String): DashboardCookieStore =
        dashboardCookieStores.getOrPut(connectionId) {
            EncryptedDashboardCookieStore(
                context = context,
                connectionId = connectionId,
                tokenStoreKey = tokenStoreKeyProvider(connectionId),
            )
        }

    /**
     * Cookie store for the active connection — the same encrypted store the
     * Manage tab's sign-in flow writes, so a dashboard session established
     * there authenticates voice (and any other dashboard-surface client).
     */
    fun activeDashboardCookieStore(): DashboardCookieStore? {
        val connectionId = activeConnectionIdProvider() ?: return null
        return dashboardCookieStoreFor(connectionId)
    }

    // --- DashboardApiClient factory ----------------------------------------

    /**
     * Build a [DashboardApiClient] for [connectionId] against [dashboardUrl],
     * wired to that connection's shared encrypted cookie store. The single
     * factory the dashboard-surface callers (profile lists, session/message
     * scoping, the gateway client, standard-API setup probe) route through.
     */
    fun dashboardClientFor(connectionId: String, dashboardUrl: String): DashboardApiClient =
        DashboardApiClient(
            baseUrl = dashboardUrl,
            okHttpClient = DashboardApiClient.defaultClient(
                cookieStore = dashboardCookieStoreFor(connectionId),
            ),
        )

    /**
     * Build a [DashboardApiClient] for the active connection against
     * [dashboardUrl], falling back to an in-memory cookie store when there is
     * no active connection (the standard-voice probe path).
     */
    fun dashboardClientForActive(dashboardUrl: String): DashboardApiClient =
        DashboardApiClient(
            baseUrl = dashboardUrl,
            okHttpClient = DashboardApiClient.defaultClient(
                cookieStore = activeDashboardCookieStore() ?: InMemoryDashboardCookieStore(),
            ),
        )

    // --- Gateway availability ----------------------------------------------

    private val _gatewayAvailability = MutableStateFlow(GatewayAvailability.Unknown)
    val gatewayAvailability: StateFlow<GatewayAvailability> = _gatewayAvailability.asStateFlow()

    /**
     * Sticky downgrade fired by [GatewayChatClient] when the WS upgrade is
     * rejected outright (404/403 — dashboard build without `/api/ws`). Stops
     * auto-resolution from re-picking gateway until a connection switch
     * resets it.
     */
    fun markGatewayUnsupported() {
        _gatewayAvailability.value = GatewayAvailability.Unsupported
    }

    /** Probe-driven update that respects the sticky [markGatewayUnsupported] verdict. */
    fun updateGatewayAvailability(probed: GatewayAvailability) {
        val current = _gatewayAvailability.value
        if (current == GatewayAvailability.Unsupported && probed == GatewayAvailability.Ready) return
        _gatewayAvailability.value = probed
    }

    // --- Gateway chat client -----------------------------------------------

    /** Cached gateway client, keyed by connection + resolved dashboard URL. */
    private var gatewayClientCache: Triple<String, String, GatewayChatClient>? = null

    /**
     * Gateway chat client for the active connection — built lazily, rebuilt
     * when the connection or its resolved dashboard URL changes (LAN ↔
     * Tailscale handoff), sharing the Manage tab's encrypted cookie store so
     * a dashboard sign-in there authenticates chat here.
     */
    @Synchronized
    fun activeGatewayChatClient(): GatewayChatClient? {
        val connectionId = activeConnectionIdProvider() ?: return null
        val dashboardUrl = dashboardUrlProvider() ?: return null
        gatewayClientCache?.let { (cachedConnection, cachedUrl, client) ->
            if (cachedConnection == connectionId && cachedUrl == dashboardUrl) return client
            // Same connection, the resolved dashboard URL moved (a LAN⇄Tailscale
            // route change) WHILE a turn is in flight: RETARGET the live client
            // to the new route so the turn FOLLOWS it (reconnect + keep the live
            // session id — the session is server-side and the same shared
            // gateway sits behind both routes), instead of tearing the client
            // down (which would call activeTurn.cancel()) or stranding the turn
            // on the dead route until the watchdog.
            if (cachedConnection == connectionId && client.hasActiveTurn()) {
                android.util.Log.i(
                    "ConnectionViewModel",
                    "gateway route changed mid-turn — retargeting active client to follow the route",
                )
                client.retarget(
                    dashboardClientFor(connectionId, dashboardUrl),
                )
                gatewayClientCache = Triple(connectionId, dashboardUrl, client)
                return client
            }
        }
        gatewayClientCache?.third?.shutdown()
        val client = GatewayChatClient(
            initialDashboardClient = dashboardClientFor(connectionId, dashboardUrl),
            onGatewayUnsupported = { markGatewayUnsupported() },
        )
        // Carry the current keep-alive preference onto the fresh client so a
        // connection/route switch doesn't lose the no-background-close flag.
        client.setKeepAliveInBackground(gatewayKeepAliveProvider())
        gatewayClientCache = Triple(connectionId, dashboardUrl, client)
        return client
    }

    /**
     * Apply the keep-alive-in-background flag to the cached gateway client, if
     * one exists. Driven by the ViewModel's `gatewayKeepAlive` collector.
     * Unsynchronized to match the original (the cache read here never raced
     * the @Synchronized builder in practice).
     */
    fun applyGatewayKeepAlive(enabled: Boolean) {
        gatewayClientCache?.third?.setKeepAliveInBackground(enabled)
    }

    /**
     * Per-connection gateway reset on a connection switch: drop the sticky
     * Unsupported verdict and tear down the old socket so the next probe/send
     * evaluates the new connection fresh.
     */
    fun resetGatewayForConnectionSwitch() {
        _gatewayAvailability.value = GatewayAvailability.Unknown
        synchronized(this) {
            gatewayClientCache?.third?.shutdown()
            gatewayClientCache = null
        }
    }

    // --- Capabilities + transport resolution -------------------------------

    // Per-endpoint capability snapshot from the most recent probe. Used by
    // ChatViewModel to resolve `streamingEndpoint = "auto"` to a concrete
    // sessions/runs choice without round-tripping to the network on every
    // send. Pushed by the ViewModel's `rebuildApiClient()`.
    private val _serverCapabilities = MutableStateFlow(ServerCapabilities.DISCONNECTED)
    val serverCapabilities: StateFlow<ServerCapabilities> = _serverCapabilities.asStateFlow()

    private val _chatMode = MutableStateFlow(ChatMode.DISCONNECTED)
    val chatMode: StateFlow<ChatMode> = _chatMode.asStateFlow()

    /** Push a freshly-probed capability snapshot (and the derived chat mode). */
    fun setCapabilitiesAndMode(caps: ServerCapabilities) {
        _serverCapabilities.value = caps
        _chatMode.value = caps.toChatMode()
    }

    /** Reset capability + chat mode to their disconnected sentinels. */
    fun resetCapabilitiesAndMode() {
        _serverCapabilities.value = ServerCapabilities.DISCONNECTED
        _chatMode.value = ChatMode.DISCONNECTED
    }

    /**
     * Resolve the user's `streamingEndpoint` preference to a concrete value
     * based on the latest capability probe. Returns a concrete endpoint
     * (never "auto"). Used by ChatViewModel right before kicking off a stream.
     *
     * - "sessions" / "completions" / "runs" pass through unchanged (manual override wins).
     * - "auto" → reads `serverCapabilities.value.preferredChatEndpoint()`.
     */
    fun resolveStreamingEndpoint(preference: String): String =
        resolveStreamingEndpointPreference(
            preference = preference,
            gateway = _gatewayAvailability.value,
            capabilities = _serverCapabilities.value,
        )

    /**
     * Capability-resolved SSE endpoint, ignoring the gateway tier — wired to
     * [com.hermesandroid.relay.viewmodel.ChatViewModel.sseFallbackEndpoint] for
     * per-turn gateway fallbacks.
     */
    fun resolveSseStreamingEndpoint(): String =
        _serverCapabilities.value.preferredChatEndpoint()
}

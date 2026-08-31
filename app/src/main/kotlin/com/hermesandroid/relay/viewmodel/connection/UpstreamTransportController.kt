package com.hermesandroid.relay.viewmodel.connection

import android.content.Context
import com.hermesandroid.relay.data.BotGatewayRouteKey
import com.hermesandroid.relay.network.upstream.ChatMode
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.DashboardCookieStore
import com.hermesandroid.relay.network.upstream.DashboardBearerAuth
import com.hermesandroid.relay.network.upstream.EncryptedNativeDashboardTokenStore
import com.hermesandroid.relay.network.upstream.NativeDashboardTokenStore
import com.hermesandroid.relay.network.upstream.EncryptedDashboardCookieStore
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.GatewayConnectionState
import com.hermesandroid.relay.network.upstream.InMemoryDashboardCookieStore
import com.hermesandroid.relay.network.upstream.NativeDashboardAuthClient
import com.hermesandroid.relay.network.upstream.clearNativeDashboardTokens
import com.hermesandroid.relay.network.upstream.isNativeDashboardTransportEligible
import com.hermesandroid.relay.network.upstream.ServerCapabilities
import com.hermesandroid.relay.network.upstream.resolveStreamingEndpointPreference
import com.hermesandroid.relay.network.upstream.trustedDashboardBearerAuthOrNull
import com.hermesandroid.relay.network.shutdownOffMainThread
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun reconcileGatewayAvailability(
    current: GatewayAvailability,
    probed: GatewayAvailability,
    liveState: GatewayConnectionState?,
    liveRouteMatches: Boolean = true,
): GatewayAvailability = when {
    liveRouteMatches && liveState == GatewayConnectionState.Ready -> GatewayAvailability.Ready
    current == GatewayAvailability.Unsupported &&
        (probed == GatewayAvailability.Ready || probed == GatewayAvailability.Unknown) -> current
    else -> probed
}

internal fun isCurrentGatewayClientCallback(
    connectionId: String,
    client: GatewayChatClient,
    cached: Triple<String, String, GatewayChatClient>?,
): Boolean = cached?.first == connectionId && cached.third === client

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
    /** Exact trusted Dashboard base for any saved connection, active or not. */
    private val trustedDashboardUrlProvider: (String) -> String? = { null },
    /** False when the host's advertised auth topology requires cookie compatibility mode. */
    private val nativeDashboardBearerEligibleProvider: (String) -> Boolean = { true },
    /** Applies pairing-bound TLS to a standard authenticated client when needed. */
    private val pinnedClientProvider: (String, okhttp3.OkHttpClient) -> okhttp3.OkHttpClient? =
        { _, _ -> null },
    private val dashboardHttpClientFactory:
        (DashboardCookieStore, DashboardBearerAuth?) -> okhttp3.OkHttpClient = { cookieStore, bearerAuth ->
            DashboardApiClient.defaultClient(cookieStore, bearerAuth)
        },
    private val dashboardTokenStoreFactory: (String) -> NativeDashboardTokenStore = { tokenStoreKey ->
        EncryptedNativeDashboardTokenStore(context, tokenStoreKey)
    },
    private val dashboardCookieStoreFactory: (String, String?) -> DashboardCookieStore =
        { connectionId, tokenStoreKey ->
            EncryptedDashboardCookieStore(
                context = context,
                connectionId = connectionId,
                tokenStoreKey = tokenStoreKey,
            )
        },
) {

    // --- Per-connection dashboard cookie stores ----------------------------

    /** Per-connection encrypted cookie stores, cached to avoid Keystore churn. */
    private val dashboardCookieStores =
        ConcurrentHashMap<String, DashboardCookieStore>()
    private val dashboardTokenStores =
        ConcurrentHashMap<String, NativeDashboardTokenStore>()
    /** Shared authenticated transport per exact connection + Dashboard route. */
    private val dashboardRestHttpClients =
        mutableMapOf<Pair<String, String>, okhttp3.OkHttpClient>()
    private var dashboardHttpClientCache:
        Triple<String, String, okhttp3.OkHttpClient>? = null
    private var dashboardSessionClientCache:
        Triple<String, String, DashboardApiClient>? = null
    private data class RouteGatewayEntry(
        var dashboardUrl: String,
        var dashboardClient: DashboardApiClient,
        val client: GatewayChatClient,
        var activeRequests: Int = 0,
        var retained: Int = 0,
        var retired: Boolean = false,
    )
    private val routeGatewayClients = mutableMapOf<BotGatewayRouteKey, RouteGatewayEntry>()

    class RouteGatewayLease internal constructor(
        val client: GatewayChatClient,
        private val releaseAction: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) releaseAction()
        }
    }

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
            dashboardCookieStoreFactory(connectionId, tokenStoreKeyProvider(connectionId))
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

    private fun dashboardTokenStoreFor(connectionId: String): NativeDashboardTokenStore {
        val key = tokenStoreKeyProvider(connectionId)
            ?: com.hermesandroid.relay.data.Connection.buildTokenStoreKey(connectionId)
        return dashboardTokenStores.getOrPut(connectionId) {
            dashboardTokenStoreFactory(key)
        }
    }

    private fun bearerAuthForTrustedDashboard(
        connectionId: String,
        dashboardUrl: String,
    ): DashboardBearerAuth? {
        if (!nativeDashboardBearerEligibleProvider(connectionId)) return null
        if (!isNativeDashboardTransportEligible(dashboardUrl)) return null
        val trustedDashboardUrl = trustedDashboardUrlProvider(connectionId)
            ?: (if (activeConnectionIdProvider() == connectionId) dashboardUrlProvider() else null)
            ?: return null
        return trustedDashboardBearerAuthOrNull(
            candidate = dashboardUrl,
            trusted = trustedDashboardUrl,
            tokenStoreProvider = { dashboardTokenStoreFor(connectionId) },
        )
    }

    // --- DashboardApiClient factory ----------------------------------------

    /**
     * Build a [DashboardApiClient] for [connectionId] against [dashboardUrl],
     * wired to that connection's shared encrypted cookie store. The single
     * factory the dashboard-surface callers (profile lists, session/message
     * scoping, the gateway client, standard-API setup probe) route through.
     */
    @Synchronized
    fun dashboardClientFor(connectionId: String, dashboardUrl: String): DashboardApiClient {
        val normalizedUrl = dashboardUrl.trim().trimEnd('/')
        val key = connectionId to normalizedUrl
        val sharedClient = dashboardRestHttpClients.getOrPut(key) {
            val base = dashboardHttpClientFactory(
                dashboardCookieStoreFor(connectionId),
                bearerAuthForTrustedDashboard(connectionId, normalizedUrl),
            )
            pinnedClientProvider(normalizedUrl, base) ?: base
        }
        return DashboardApiClient(
            baseUrl = normalizedUrl,
            okHttpClient = sharedClient,
            ownsHttpClient = false,
        )
    }

    /**
     * Reuse one authenticated REST client for profile/session directory work.
     * Exact connection + route identity owns the warm TLS/HTTP connection and
     * its retirement, matching Desktop's shared HTTP-stack behavior.
     */
    @Synchronized
    fun dashboardSessionClientFor(
        connectionId: String,
        dashboardUrl: String,
    ): DashboardApiClient {
        dashboardSessionClientCache?.let { (cachedConnection, cachedUrl, client) ->
            if (cachedConnection == connectionId && cachedUrl == dashboardUrl) return client
            client.shutdown()
            dashboardSessionClientCache = null
        }
        return dashboardClientFor(connectionId, dashboardUrl).also { client ->
            dashboardSessionClientCache = Triple(connectionId, dashboardUrl, client)
        }
    }

    /**
     * Build a [DashboardApiClient] for the active connection against
     * [dashboardUrl], falling back to an in-memory cookie store when there is
     * no active connection (the standard-voice probe path).
     */
    fun dashboardClientForActive(dashboardUrl: String): DashboardApiClient {
        val base = dashboardHttpClientFactory(
            activeDashboardCookieStore() ?: InMemoryDashboardCookieStore(),
            activeConnectionIdProvider()?.let {
                bearerAuthForTrustedDashboard(it, dashboardUrl)
            },
        )
        return DashboardApiClient(
            baseUrl = dashboardUrl,
            okHttpClient = pinnedClientProvider(dashboardUrl, base) ?: base,
        )
    }

    /**
     * Cookie-only client for proving a newly imported WebView session. A saved
     * native bearer must not override the cookie provider during that proof.
     */
    fun dashboardCookieClientForActive(dashboardUrl: String): DashboardApiClient {
        val base = dashboardHttpClientFactory(
            activeDashboardCookieStore() ?: InMemoryDashboardCookieStore(),
            null,
        )
        return DashboardApiClient(
            baseUrl = dashboardUrl,
            okHttpClient = pinnedClientProvider(dashboardUrl, base) ?: base,
        )
    }

    /**
     * Native PKCE client for the active connection's exact trusted dashboard
     * base. Setup probes and stale routes never receive the encrypted bearer
     * store.
     */
    fun nativeDashboardAuthClientForActive(dashboardUrl: String): NativeDashboardAuthClient? {
        val connectionId = activeConnectionIdProvider() ?: return null
        val trustedDashboardUrl = dashboardUrlProvider() ?: return null
        if (!isNativeDashboardTransportEligible(dashboardUrl)) {
            return null
        }
        if (!com.hermesandroid.relay.network.upstream.sameDashboardBase(
                candidate = dashboardUrl,
                trusted = trustedDashboardUrl,
            )
        ) {
            return null
        }
        val base = okhttp3.OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return NativeDashboardAuthClient(
            baseUrl = dashboardUrl,
            tokenStore = dashboardTokenStoreFor(connectionId),
            client = pinnedClientProvider(dashboardUrl, base) ?: base,
        )
    }

    /** Exact-origin authenticated HTTP client for non-REST dashboard consumers such as voice. */
    @Synchronized
    fun dashboardHttpClientForActive(dashboardUrl: String): okhttp3.OkHttpClient {
        val connectionId = activeConnectionIdProvider() ?: "unassociated"
        dashboardHttpClientCache?.let { (cachedConnection, cachedUrl, client) ->
            if (cachedConnection == connectionId && cachedUrl == dashboardUrl) return client
            disposeDashboardHttpClient(client)
            dashboardHttpClientCache = null
        }
        val base = dashboardHttpClientFactory(
            activeDashboardCookieStore() ?: InMemoryDashboardCookieStore(),
            activeConnectionIdProvider()?.let { activeId ->
                bearerAuthForTrustedDashboard(activeId, dashboardUrl)
            },
        )
        return (pinnedClientProvider(dashboardUrl, base) ?: base)
            .also { dashboardHttpClientCache = Triple(connectionId, dashboardUrl, it) }
    }

    @Synchronized
    fun clearDashboardAuthentication(connectionId: String) {
        dashboardCookieStoreFor(connectionId).clear()
        retireNativeDashboardAuthentication(connectionId)
    }

    /**
     * Transfers credential ownership to a verified cookie flow. This retires
     * only native PKCE tokens and clients that may have captured their bearer;
     * newly imported Dashboard cookies remain intact.
     */
    @Synchronized
    fun retireNativeDashboardAuthentication(connectionId: String) {
        clearNativeDashboardTokens(dashboardTokenStoreFor(connectionId))
        dashboardHttpClientCache
            ?.takeIf { it.first == connectionId }
            ?.third
            ?.let(::disposeDashboardHttpClient)
        if (dashboardHttpClientCache?.first == connectionId) {
            dashboardHttpClientCache = null
        }
        gatewayClientCache
            ?.takeIf { it.first == connectionId }
            ?.third
            ?.shutdown()
        if (gatewayClientCache?.first == connectionId) {
            gatewayClientCache = null
        }
        disposeConnectionRouteClients(connectionId)
    }

    private fun disposeDashboardHttpClient(client: okhttp3.OkHttpClient) {
        shutdownOffMainThread("DashboardHttpClient-shutdown") {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            runCatching { client.cache?.close() }
            client.dispatcher.executorService.shutdown()
        }
    }

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

    /** Dashboard REST rejected the active session even if the old socket is still open. */
    fun markGatewaySignInRequired() {
        _gatewayAvailability.value = GatewayAvailability.SignInRequired
    }

    /** Probe-driven update that respects the sticky [markGatewayUnsupported] verdict. */
    fun updateGatewayAvailability(probed: GatewayAvailability) {
        val current = _gatewayAvailability.value
        val live = synchronized(this) { gatewayClientCache }
        val activeConnectionId = activeConnectionIdProvider()
        val activeDashboardUrl = dashboardUrlProvider()?.trim()?.trimEnd('/')
        val liveRouteMatches = live != null && activeConnectionId != null &&
            live.first == activeConnectionId &&
            live.second.trim().trimEnd('/') == activeDashboardUrl
        val liveState = live?.third?.connectionState?.value
        _gatewayAvailability.value = reconcileGatewayAvailability(
            current,
            probed,
            liveState,
            liveRouteMatches,
        )
    }

    // --- Gateway chat client -----------------------------------------------

    /** Cached gateway client, keyed by connection + resolved dashboard URL. */
    private var gatewayClientCache: Triple<String, String, GatewayChatClient>? = null

    @Synchronized
    private fun updateGatewayAvailabilityIfCurrent(
        connectionId: String,
        client: GatewayChatClient,
        availability: GatewayAvailability,
    ) {
        val current = gatewayClientCache
        if (isCurrentGatewayClientCallback(connectionId, client, current)) {
            updateGatewayAvailability(availability)
        }
    }

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
        lateinit var client: GatewayChatClient
        client = GatewayChatClient(
            initialDashboardClient = dashboardClientFor(connectionId, dashboardUrl),
            onGatewayUnsupported = {
                updateGatewayAvailabilityIfCurrent(
                    connectionId,
                    client,
                    GatewayAvailability.Unsupported,
                )
            },
            onGatewaySignInRequired = {
                updateGatewayAvailabilityIfCurrent(
                    connectionId,
                    client,
                    GatewayAvailability.SignInRequired,
                )
            },
            onGatewayUnreachable = {
                updateGatewayAvailabilityIfCurrent(
                    connectionId,
                    client,
                    GatewayAvailability.Unreachable,
                )
            },
            onGatewayReady = {
                updateGatewayAvailabilityIfCurrent(
                    connectionId,
                    client,
                    GatewayAvailability.Ready,
                )
            },
        )
        // Carry the current keep-alive preference onto the fresh client so a
        // connection/route switch doesn't lose the no-background-close flag.
        client.setKeepAliveInBackground(gatewayKeepAliveProvider())
        gatewayClientCache = Triple(connectionId, dashboardUrl, client)
        return client
    }

    /**
     * Bot/agent Gateway client owned by one immutable connection + profile.
     * It never consults or changes the foreground connection and never shares
     * live-session state with the standard Chat client's dynamic profile.
     */
    @Synchronized
    fun acquireGatewayRoute(
        connectionId: String,
        dashboardUrl: String,
        profileName: String,
        retain: Boolean = false,
    ): RouteGatewayLease {
        val profile = profileName.trim().ifBlank { "default" }
        val key = BotGatewayRouteKey(connectionId.trim(), profile)
        var entry = routeGatewayClients[key]
        entry?.let { cached ->
            if (cached.dashboardUrl == dashboardUrl) {
                if (retain) cached.retained += 1 else cached.activeRequests += 1
                return routeLease(key, cached, retain)
            }
            if (cached.client.hasActiveTurn()) {
                val replacementDashboard = dashboardClientFor(connectionId, dashboardUrl)
                val previousDashboard = cached.dashboardClient
                cached.client.retarget(replacementDashboard)
                cached.dashboardClient = replacementDashboard
                cached.dashboardUrl = dashboardUrl
                previousDashboard.shutdown()
                if (retain) cached.retained += 1 else cached.activeRequests += 1
                return routeLease(key, cached, retain)
            }
            cached.retired = true
            routeGatewayClients.remove(key)
            if (cached.activeRequests == 0 && cached.retained == 0) shutdownRouteEntry(cached)
        }
        val dashboardClient = dashboardClientFor(connectionId, dashboardUrl)
        entry = RouteGatewayEntry(
            dashboardUrl = dashboardUrl,
            dashboardClient = dashboardClient,
            client = GatewayChatClient(
                initialDashboardClient = dashboardClient,
                fixedSessionProfile = profile,
            ).also { it.setKeepAliveInBackground(gatewayKeepAliveProvider()) },
        )
        if (retain) entry.retained = 1 else entry.activeRequests = 1
        routeGatewayClients[key] = entry
        return routeLease(key, entry, retain)
    }

    private fun routeLease(
        key: BotGatewayRouteKey,
        entry: RouteGatewayEntry,
        retained: Boolean,
    ): RouteGatewayLease = RouteGatewayLease(entry.client) {
        releaseGatewayRoute(key, entry, retained)
    }

    @Synchronized
    private fun releaseGatewayRoute(
        key: BotGatewayRouteKey,
        entry: RouteGatewayEntry,
        retained: Boolean,
    ) {
        if (retained) entry.retained = (entry.retained - 1).coerceAtLeast(0)
        else entry.activeRequests = (entry.activeRequests - 1).coerceAtLeast(0)
        if ((entry.retired || routeGatewayClients[key] !== entry) &&
            entry.activeRequests == 0 && entry.retained == 0
        ) {
            shutdownRouteEntry(entry)
        }
    }

    private fun shutdownRouteEntry(entry: RouteGatewayEntry) {
        entry.client.shutdown()
        entry.dashboardClient.shutdown()
    }

    @Synchronized
    fun disposeConnectionRouteClients(connectionId: String) {
        dashboardSessionClientCache
            ?.takeIf { it.first == connectionId }
            ?.third
            ?.shutdown()
        if (dashboardSessionClientCache?.first == connectionId) {
            dashboardSessionClientCache = null
        }
        dashboardRestHttpClients.keys
            .filter { it.first == connectionId }
            .forEach { key ->
                dashboardRestHttpClients.remove(key)?.let(::disposeDashboardHttpClient)
            }
        routeGatewayClients.entries
            .filter { it.key.connectionId == connectionId }
            .forEach { (key, entry) ->
                entry.retired = true
                shutdownRouteEntry(entry)
                routeGatewayClients.remove(key)
            }
    }

    @Synchronized
    fun disposeAllRouteClients() {
        dashboardSessionClientCache?.third?.shutdown()
        dashboardSessionClientCache = null
        dashboardRestHttpClients.values.forEach(::disposeDashboardHttpClient)
        dashboardRestHttpClients.clear()
        routeGatewayClients.values.forEach(::shutdownRouteEntry)
        routeGatewayClients.clear()
    }

    /**
     * Apply the keep-alive-in-background flag to the cached gateway client, if
     * one exists. Driven by the ViewModel's `gatewayKeepAlive` collector.
     * Unsynchronized to match the original (the cache read here never raced
     * the @Synchronized builder in practice).
     */
    fun applyGatewayKeepAlive(enabled: Boolean) {
        gatewayClientCache?.third?.setKeepAliveInBackground(enabled)
        synchronized(this) {
            routeGatewayClients.values.forEach { it.client.setKeepAliveInBackground(enabled) }
        }
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
            dashboardHttpClientCache?.third?.let(::disposeDashboardHttpClient)
            dashboardHttpClientCache = null
            dashboardSessionClientCache?.third?.shutdown()
            dashboardSessionClientCache = null
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

package com.hermesandroid.relay.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.auth.AuthManager
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.ui.theme.AppFont
import com.hermesandroid.relay.ui.theme.AppThemes
import com.hermesandroid.relay.ui.theme.normalizeAccentHex
import com.hermesandroid.relay.ui.theme.AppearanceShape
import com.hermesandroid.relay.ui.components.avatar.PetImporter
import com.hermesandroid.relay.ui.components.avatar.PetImportResult
import com.hermesandroid.relay.ui.components.avatar.PetLoader
import com.hermesandroid.relay.ui.components.avatar.SphereAvatar
import com.hermesandroid.relay.ui.components.SphereSkinImportResult
import com.hermesandroid.relay.ui.components.SphereSkinImporter
import com.hermesandroid.relay.ui.components.pet.PetLogicalEdge
import com.hermesandroid.relay.ui.components.pet.PetPlacement
import com.hermesandroid.relay.auth.PairedDeviceInfo
import com.hermesandroid.relay.auth.PairedSession
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.AppearancePreferences
import com.hermesandroid.relay.data.CustomThemePreset
import com.hermesandroid.relay.data.DataManager
import com.hermesandroid.relay.data.DemoContent
import com.hermesandroid.relay.data.DemoMode
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.data.hasSecureProxy
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.MediaSettingsRepository
import com.hermesandroid.relay.data.PairingPreferences
import com.hermesandroid.relay.data.DEFAULT_PET_TEMPERAMENT
import com.hermesandroid.relay.data.DEFAULT_PET_SIZE_SCALE
import com.hermesandroid.relay.data.PetBehaviorPreferences
import com.hermesandroid.relay.data.PetBehaviorPreferencesRepository
import com.hermesandroid.relay.data.PetTemperament
import com.hermesandroid.relay.data.ChatInputPreferencesRepository
import com.hermesandroid.relay.data.PhysicalKeyboardEnterBehavior
import com.hermesandroid.relay.data.RelayEndpoint
import com.hermesandroid.relay.data.DASHBOARD_RELAY_INGRESS_PATH
import com.hermesandroid.relay.data.isDashboardRelayIngressUrl
import com.hermesandroid.relay.data.primaryRouteUrl
import com.hermesandroid.relay.data.routeAuthority
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.capabilities
import com.hermesandroid.relay.data.ConnectionSecurity
import com.hermesandroid.relay.data.ConnectionStore
import com.hermesandroid.relay.data.LEGACY_AUTHENTICATED_DASHBOARD_ROUTE_ROLE
import com.hermesandroid.relay.data.ConnectionValidation
import com.hermesandroid.relay.data.computeConnectionSecurity
import com.hermesandroid.relay.data.normalizeCredentialFreeAuthenticatedDashboardOrigin
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.data.BotChatTarget
import com.hermesandroid.relay.data.BotModeState
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.ProfilePresentation
import com.hermesandroid.relay.data.SessionTransport
import com.hermesandroid.relay.data.relayDataStore
import com.hermesandroid.relay.data.proactiveEnabledFlow
import com.hermesandroid.relay.data.setProactiveEnabled
import com.hermesandroid.relay.data.DEFAULT_HIDDEN_SOURCES
import com.hermesandroid.relay.data.ProactiveInboxEntry
import com.hermesandroid.relay.data.ProactiveInboxRepository
import com.hermesandroid.relay.data.SessionSourcePrefs
import com.hermesandroid.relay.data.ThreadNameStore
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.util.TailscaleDetector
import com.hermesandroid.relay.network.relay.ChannelMultiplexer
import com.hermesandroid.relay.network.shared.ConnectivityObserver
import com.hermesandroid.relay.network.upstream.ChatMode
import com.hermesandroid.relay.network.relay.ConnectionManager
import com.hermesandroid.relay.network.relay.ConnectionState
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.DashboardChatDisplaySettings
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.network.upstream.SessionMessageLoadMode
import com.hermesandroid.relay.network.upstream.models.SessionItem
import com.hermesandroid.relay.network.upstream.DashboardAuthSession
import com.hermesandroid.relay.network.upstream.DashboardCookieStore
import com.hermesandroid.relay.network.upstream.DashboardStatus
import com.hermesandroid.relay.network.upstream.sameDashboardBase
import com.hermesandroid.relay.network.upstream.multiplexServedProfiles
import com.hermesandroid.relay.network.upstream.NativeDashboardAuthClient
import com.hermesandroid.relay.network.upstream.ToolsetInfo
import com.hermesandroid.relay.network.shared.EndpointResolver
import com.hermesandroid.relay.network.shared.EndpointSurface
import com.hermesandroid.relay.network.shared.buildPluginProxyClient
import com.hermesandroid.relay.network.shared.buildHermesReachClient
import com.hermesandroid.relay.network.shared.hermesReachRouteOrNull
import com.hermesandroid.relay.network.shared.pluginProxyRoutesOrNull
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.upstream.ActiveTurnKeepAliveRegistry
import com.hermesandroid.relay.data.KEY_GATEWAY_KEEP_ALIVE
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.GatewayPetGalleryItem
import com.hermesandroid.relay.network.upstream.GatewayKeepAliveService
import com.hermesandroid.relay.network.upstream.HermesApiClient
import com.hermesandroid.relay.network.shared.RouteProbeOutcome
import com.hermesandroid.relay.network.shared.ProfileApiUrlResolver
import com.hermesandroid.relay.network.shared.normalizeCredentialForHeader
import com.hermesandroid.relay.network.upstream.ServerCapabilities
import com.hermesandroid.relay.network.relay.RelayHttpClient
import com.hermesandroid.relay.network.relay.RelayUrlDeriver
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.network.relay.VoiceHandoffEvent
import com.hermesandroid.relay.network.upstream.ChatHandler
// === PHASE3-accessibility: bridge channel wiring ===
import com.hermesandroid.relay.accessibility.BridgeStatusReporter
import com.hermesandroid.relay.accessibility.ScreenCapture
import com.hermesandroid.relay.network.relay.BridgeCommandHandler
import com.hermesandroid.relay.network.relay.ProactiveMessageHandler
import com.hermesandroid.relay.network.relay.models.Envelope
// === END PHASE3-accessibility ===
import com.hermesandroid.relay.util.AppForegroundTracker
import com.hermesandroid.relay.util.MediaCacheWriter
import com.hermesandroid.relay.viewmodel.connection.PairingController
import com.hermesandroid.relay.viewmodel.connection.BotModeController
import com.hermesandroid.relay.viewmodel.connection.ProfileController
import com.hermesandroid.relay.viewmodel.connection.UpstreamTransportController
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

internal data class RelayUiInputs(
    val auth: AuthState,
    val conn: ConnectionState,
    val url: String,
    val configured: Boolean,
)

/** Exact Dashboard origin that may authorize a namespaced Relay ingress URL. */
internal fun dashboardOriginForRelayIngress(
    dashboardUrl: String?,
    relayUrl: String?,
): String? {
    val dashboard = runCatching { URI(dashboardUrl?.trim().orEmpty()) }.getOrNull()
        ?: return null
    val relay = runCatching { URI(relayUrl?.trim().orEmpty()) }.getOrNull()
        ?: return null
    if (!isDashboardRelayIngressUrl(relayUrl)) return null
    val dashboardScheme = dashboard.scheme?.lowercase() ?: return null
    val relayDashboardScheme = when (relay.scheme?.lowercase()) {
        "wss", "https" -> "https"
        "ws", "http" -> "http"
        else -> return null
    }
    fun effectivePort(uri: URI, scheme: String): Int = when {
        uri.port >= 0 -> uri.port
        scheme == "https" -> 443
        else -> 80
    }
    if (
        dashboardScheme != relayDashboardScheme ||
        !dashboard.host.equals(relay.host, ignoreCase = true) ||
        effectivePort(dashboard, dashboardScheme) != effectivePort(relay, relayDashboardScheme)
    ) {
        return null
    }
    val dashboardPath = dashboard.rawPath.orEmpty().trimEnd('/').takeUnless { it == "/" }.orEmpty()
    val expectedIngressPath = "$dashboardPath$DASHBOARD_RELAY_INGRESS_PATH"
    val relayPath = relay.rawPath.orEmpty().trimEnd('/')
    if (relayPath != expectedIngressPath && !relayPath.startsWith("$expectedIngressPath/")) {
        return null
    }
    return dashboardUrl?.trim()?.trimEnd('/')
}

internal fun dashboardRelayWebSocketRequest(relayUrl: String, ticket: String): Request? {
    val base = runCatching { Request.Builder().url(relayUrl).build() }.getOrNull()
        ?: return null
    val authorizedUrl = base.url.newBuilder()
        .removeAllQueryParameters("ticket")
        .addQueryParameter("ticket", ticket)
        .build()
    return base.newBuilder().url(authorizedUrl).build()
}

data class HostResourcePressureStatus(
    val memoryPressure: String? = null,
    val memoryAvailableMb: Int? = null,
    val diskPressure: String? = null,
    val diskFreeMb: Int? = null,
    val lastBootSuspectedOom: Boolean = false,
) {
    val needsAttention: Boolean
        get() = memoryPressure in setOf("elevated", "critical") ||
            diskPressure in setOf("elevated", "critical") ||
            lastBootSuspectedOom

    val critical: Boolean
        get() = memoryPressure == "critical" || diskPressure == "critical" || lastBootSuspectedOom
}

internal fun DashboardStatus.hostResourcePressure(): HostResourcePressureStatus =
    HostResourcePressureStatus(
        memoryPressure = memory?.pressure,
        memoryAvailableMb = memory?.systemAvailableMb,
        diskPressure = disk?.pressure,
        diskFreeMb = disk?.freeMb,
        lastBootSuspectedOom = memory?.lastBootSuspectedOom == true,
    )

internal fun RelayUiInputs.requiresReconnectGrace(): Boolean =
    configured &&
        url.isNotBlank() &&
        auth is AuthState.Paired &&
        (conn == ConnectionState.Disconnected || conn == ConnectionState.Reconnecting)

internal fun RelayUiInputs.resolveRelayUiState(graceElapsed: Boolean = false): RelayUiState = when {
    !configured || url.isBlank() -> RelayUiState.NotConfigured
    auth is AuthState.Failed -> RelayUiState.Expired
    auth is AuthState.Paired && conn == ConnectionState.Connected -> RelayUiState.Connected
    conn == ConnectionState.Connecting || auth is AuthState.Pairing -> RelayUiState.Connecting
    requiresReconnectGrace() -> if (graceElapsed) RelayUiState.Stale else RelayUiState.Connecting
    else -> RelayUiState.Disconnected
}

private data class ConnectionHealthInputs(
    val connection: Connection?,
    val relayRow: RelayRowState,
    val apiHealth: ConnectionViewModel.HealthStatus,
    val relayHealth: ConnectionViewModel.HealthStatus,
    val network: ConnectivityObserver.Status,
)

/**
 * Why the standard (no-plugin) voice route is or isn't usable right now.
 * Drives the mic gate, the Auto route ordering, and the Voice Settings
 * status line + CTA ("Sign in via Manage" / "Update hermes-agent").
 */
enum class StandardVoiceAvailability {
    /** No probe has completed yet (startup, connection switch). */
    Unknown,

    /** Dashboard reachable, authenticated (or auth not required), audio routes present. */
    Ready,

    /** Dashboard reachable and gated, but no signed-in session — Manage sign-in unlocks it. */
    SignInRequired,

    /** Dashboard URL configured but `/api/status` did not answer. */
    Unreachable,

    /** Dashboard answered but has no audio routes (`/api/audio`) — hermes-agent build too old. */
    Unsupported,
}

/**
 * Coarse connection state for the chat empty-state, derived in
 * [ConnectionViewModel.chatConnectState]. Lets the UI hold a neutral
 * "Connecting…" placeholder during cold-start hydration instead of flashing
 * the "Connect to Hermes" CTA before we know whether anything is configured.
 */
enum class ChatConnectState {
    /** Store not hydrated yet, or an active connection is still coming up. */
    Connecting,

    /** Chat client built and the API server is reachable. */
    Ready,

    /** Configured chat transports have settled unavailable; show retry, not an endless loader. */
    Unavailable,

    /** Hydration complete and no connection is configured — show the CTA. */
    NeedsConnection,
}

internal fun resolveChatConnectState(
    hydrated: Boolean,
    connection: Connection?,
    ready: Boolean,
    gatewayAvailability: GatewayAvailability,
    apiHealth: ConnectionViewModel.HealthStatus,
): ChatConnectState {
    if (ready) return ChatConnectState.Ready
    if (!hydrated) return ChatConnectState.Connecting
    val active = connection ?: return ChatConnectState.NeedsConnection
    val gatewayStillSettling = active.capabilities.dashboardGatewayConfigured &&
        gatewayAvailability in setOf(
            GatewayAvailability.Unknown,
            GatewayAvailability.SignInRequired,
        )
    val apiStillSettling = active.capabilities.apiServerConfigured &&
        apiHealth in setOf(
            ConnectionViewModel.HealthStatus.Unknown,
            ConnectionViewModel.HealthStatus.Probing,
        )
    return if (gatewayStillSettling || apiStillSettling) {
        ChatConnectState.Connecting
    } else {
        ChatConnectState.Unavailable
    }
}

/** Runtime chat readiness independent of which upstream transport is primary. */
internal fun isChatTransportReady(
    apiClientPresent: Boolean,
    apiReachable: Boolean,
    gatewayAvailability: GatewayAvailability,
): Boolean =
    gatewayAvailability == GatewayAvailability.Ready ||
        (apiClientPresent && apiReachable)

internal fun recordDashboardGatewayFailure(
    dashboardUrl: String,
    detail: String,
) {
    val now = System.currentTimeMillis()
    val duplicate = DiagnosticsLog.entries.value.lastOrNull {
        it.category == DiagnosticCategory.Endpoint &&
            it.operation == "Probe Dashboard / Gateway status"
    }?.let { now - it.timestampMs < 60_000L } == true
    if (duplicate) return
    DiagnosticsLog.record(
        category = DiagnosticCategory.Endpoint,
        severity = DiagnosticSeverity.Error,
        title = "Dashboard / Gateway is unavailable",
        detail = detail,
        operation = "Probe Dashboard / Gateway status",
        endpointRole = "gateway",
        configuredUrl = dashboardUrl,
        requestUrl = "${dashboardUrl.trimEnd('/')}/api/status",
        suggestion = "Open the active connection and verify its Dashboard route and sign-in state.",
    )
}

internal fun hasConfiguredHermesConnection(connection: Connection?): Boolean =
    connection?.capabilities?.anySurfaceConfigured == true

internal fun resolveEffectiveRelayUrl(
    savedRelayUrl: String,
    savedApiUrl: String,
    activeRelayEndpoint: EndpointCandidate?,
    relayConfigured: Boolean,
): String {
    if (!relayConfigured) return ""
    activeRelayEndpoint?.pluginProxyRoutesOrNull()?.relayWebSocketUrl?.let { return it }
    activeRelayEndpoint?.relay?.url?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    return if (RelayUrlDeriver.isAutoManagedRelayUrl(savedRelayUrl, savedApiUrl)) {
        RelayUrlDeriver.deriveFromApiUrl(savedApiUrl) ?: savedRelayUrl
    } else {
        savedRelayUrl
    }
}

internal fun reusablePlaceholderForAdd(
    preAllocatedId: String?,
    connections: List<Connection>,
): Connection? {
    if (preAllocatedId != null) return null
    return connections.firstOrNull { connection ->
        connection.pairedAt == null &&
            connection.apiServerUrl.isBlank() &&
            connection.label == ConnectionViewModel.PLACEHOLDER_LABEL
    }
}

/**
 * Resolve the Dashboard/Gateway surface for the route the resolver selected.
 *
 * A saved dashboard URL describes the primary route; it must not pin every
 * runtime route to that host. When a LAN connection has an explicit dashboard
 * URL and the resolver selects Tailscale, keeping the saved LAN URL makes
 * Gateway sessions, Manage, and standard voice all appear offline even though
 * the selected Tailscale candidate is reachable.
 */
internal fun resolveEffectiveDashboardUrl(
    connection: Connection?,
    endpoint: EndpointCandidate?,
): String {
    if (connection == null) return ""
    connection.authenticatedDashboardOrigin
        ?.let(::normalizeCredentialFreeAuthenticatedDashboardOrigin)
        ?.let { return it }
    endpoint?.pluginProxyRoutesOrNull()?.dashboardBaseUrl?.let { return it }
    endpoint?.dashboard?.url
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    endpoint?.api?.url?.let { apiUrl ->
        connection.dashboardUrl
            ?.takeIf { it.isNotBlank() && Connection.urlsShareHost(it, apiUrl) }
            ?.let { return it }
        Connection.deriveDefaultDashboardUrl(apiUrl)?.let { return it }
    }
    return connection.resolvedDashboardUrl
}

internal fun isCurrentDashboardProbe(
    requestConnectionId: String,
    requestDashboardUrl: String,
    activeConnectionId: String?,
    activeDashboardUrl: String?,
): Boolean =
    activeConnectionId == requestConnectionId &&
        !activeDashboardUrl.isNullOrBlank() &&
        sameDashboardBase(activeDashboardUrl, requestDashboardUrl)

/** Accept HTTPS, plus explicit cleartext loopback/private-overlay Dashboard bases. */
internal fun normalizeAuthenticatedDashboardOrigin(raw: String): String? {
    return normalizeCredentialFreeAuthenticatedDashboardOrigin(raw)
}

internal fun normalizeDashboardAddressForEdit(raw: String): String? {
    val normalized = Connection.normalizeDashboardUrlInput(raw)
    val parsed = runCatching { URI(normalized) }.getOrNull() ?: return null
    if (parsed.scheme?.lowercase() !in setOf("http", "https")) return null
    if (parsed.host.isNullOrBlank() || parsed.userInfo != null) return null
    if (parsed.query != null || parsed.fragment != null) return null
    val clean = normalized.trimEnd('/').takeIf { it.isNotBlank() } ?: return null
    val httpUrl = clean.toHttpUrlOrNull() ?: return null
    if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) return null
    return clean
}

internal fun withExplicitDashboardAddress(
    connection: Connection,
    normalizedAddress: String,
): Connection {
    return connection.copy(
        dashboardUrl = normalizedAddress,
        // The explicit address now owns Dashboard/Gateway routing. When it is
        // the same canonical base this merely removes a redundant override;
        // the caller preserves valid host-scoped credentials.
        authenticatedDashboardOrigin = null,
        routeCandidates = Connection.reconcileDashboardRoutes(
            dashboardUrl = normalizedAddress,
            candidates = connection.routeCandidates,
        ),
    )
}

/** The exact Dashboard origin that currently owns cookie/bearer credentials. */
internal fun dashboardCredentialOrigin(connection: Connection): String =
    connection.authenticatedDashboardOrigin ?: connection.resolvedDashboardUrl

/** Credentials never survive a change to their exact Dashboard owner. */
internal fun dashboardCredentialsMustBeRetired(
    previous: Connection,
    next: Connection,
): Boolean = !sameDashboardBase(
    dashboardCredentialOrigin(previous),
    dashboardCredentialOrigin(next),
)

internal enum class DashboardInstallIdentityDecision { Match, Mismatch, Missing }

internal fun dashboardInstallIdentityDecision(
    enteredInstallId: String?,
    candidateInstallId: String?,
): DashboardInstallIdentityDecision {
    val entered = enteredInstallId?.trim()?.takeIf { it.isNotEmpty() }
    val candidate = candidateInstallId?.trim()?.takeIf { it.isNotEmpty() }
    if (entered == null || candidate == null) return DashboardInstallIdentityDecision.Missing
    return if (entered == candidate) {
        DashboardInstallIdentityDecision.Match
    } else {
        DashboardInstallIdentityDecision.Mismatch
    }
}

/**
 * Persist a verified public Dashboard/Gateway origin independently from the
 * network route pool. Existing candidates and route preference remain owned
 * by API/Relay routing.
 */
internal fun withAuthenticatedDashboardOrigin(
    connection: Connection,
    normalizedOrigin: String,
): Connection {
    return connection.copy(
        authenticatedDashboardOrigin = normalizedOrigin,
        routeCandidates = connection.routeCandidates
            .filterNot {
                it.role.equals(
                    LEGACY_AUTHENTICATED_DASHBOARD_ROUTE_ROLE,
                    ignoreCase = true,
                )
            },
        preferredRouteRole = connection.preferredRouteRole?.takeUnless {
            it.equals(
                LEGACY_AUTHENTICATED_DASHBOARD_ROUTE_ROLE,
                ignoreCase = true,
            )
        },
    )
}

/** Persist an authenticated origin atomically from the caller's perspective. */
internal suspend fun persistAuthenticatedDashboardOriginWithRollback(
    previous: Connection,
    normalizedOrigin: String,
    persist: suspend (Connection) -> Unit,
    activated: suspend () -> Boolean,
): Boolean {
    val promoted = withAuthenticatedDashboardOrigin(previous, normalizedOrigin)
    var persisted = false
    val success = try {
        persist(promoted)
        persisted = true
        activated()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        if (persisted) persist(previous)
        throw cancelled
    } catch (_: Exception) {
        false
    }
    if (!success && persisted) persist(previous)
    return success
}

/**
 * Resolve the runtime API route only after the optional fallback was explicitly
 * configured. Discovery may attach a conventional same-host API candidate to a
 * Dashboard route, but that candidate alone must not enable API traffic.
 */
internal fun resolveEffectiveApiServerUrl(
    savedUrl: String,
    endpoint: EndpointCandidate?,
): String {
    if (savedUrl.isBlank()) return ""
    endpoint?.pluginProxyRoutesOrNull()?.apiBaseUrl?.let { return it }
    return endpoint?.api?.url?.takeIf { it.isNotBlank() } ?: savedUrl
}

/**
 * Add Relay transport metadata to the connection's existing standard routes
 * without adopting the Relay QR's API/Dashboard identity.
 */
internal fun mergeRelayTransportIntoStandardRoutes(
    standardRoutes: List<EndpointCandidate>,
    relayRoutes: List<EndpointCandidate>,
): List<EndpointCandidate> {
    if (standardRoutes.isEmpty()) {
        return relayRoutes.sortedWith(
            compareBy<EndpointCandidate> { it.priority }.thenBy { it.role },
        )
    }

    val standardRoles = standardRoutes.map { it.role.lowercase() }.toSet()
    val merged = standardRoutes.map { standard ->
        val paired = relayRoutes
            .firstOrNull { it.role.equals(standard.role, ignoreCase = true) }
            ?: return@map standard
        val sameHost = standard.routeHost()?.equals(paired.routeHost(), ignoreCase = true) == true
        // The Standard connection remains authoritative for any surface it
        // already configured. A Relay QR fills missing surfaces and refreshes
        // its own transport, so a manually-added dashboard-only Tailscale
        // route gains the QR's same-host API fallback instead of remaining
        // Gateway-only. Never graft a QR API from a different host.
        standard.copy(
            api = standard.api ?: paired.api.takeIf { sameHost },
            dashboard = standard.dashboard ?: paired.dashboard.takeIf { sameHost },
            relay = paired.relay ?: standard.relay,
            proxy = standard.proxy ?: paired.proxy,
            security = standard.security ?: paired.security,
            recommended = standard.recommended || paired.recommended,
        )
    }
    // Relay-only pairing used to map over Standard's existing roles and drop
    // every unmatched QR role. The common LAN-only Standard + LAN/Tailscale QR
    // therefore looked paired while it had no remote route. Preserve the full
    // signed QR route set, appending roles Standard did not know yet.
    val missingQrRoles = relayRoutes.filterNot {
        it.role.lowercase() in standardRoles
    }
    return (merged + missingQrRoles)
        .map(EndpointCandidate::withDerivedDashboard)
        .distinctBy { "${it.role.lowercase()}|${it.routeAuthority()}" }
        .sortedWith(compareBy<EndpointCandidate> { it.priority }.thenBy { it.role })
}

private fun EndpointCandidate.withDerivedDashboard(): EndpointCandidate {
    if (dashboard != null) return this
    val derived = api?.url
        ?.let(Connection::deriveDefaultDashboardUrl)
        ?.let(::DashboardEndpoint)
        ?: return this
    return copy(dashboard = derived)
}

private fun EndpointCandidate.routeHost(): String? {
    val raw = primaryRouteUrl() ?: return null
    val normalized = when {
        raw.startsWith("ws://", ignoreCase = true) ->
            "http://${raw.substringAfter("://")}"
        raw.startsWith("wss://", ignoreCase = true) ->
            "https://${raw.substringAfter("://")}"
        else -> raw
    }
    return runCatching { URI(normalized).host }.getOrNull()
}

/** Pure persistence policy used by Relay-only QR apply and its regression tests. */
internal fun preserveStandardConnectionWhileApplyingRelay(
    current: Connection,
    relayUrl: String,
    relayRoutes: List<EndpointCandidate>,
): Connection = current.copy(
    relayUrl = relayUrl,
    routeCandidates = mergeRelayTransportIntoStandardRoutes(
        standardRoutes = current.routeCandidates,
        relayRoutes = relayRoutes,
    ),
)

internal fun profileApiCredential(
    usesMultiplexProfileKey: Boolean,
    profileKey: String?,
    connectionKey: String,
): String = if (usesMultiplexProfileKey) profileKey.orEmpty() else connectionKey

internal fun migratedBackgroundAvatar(
    storedBackgroundAvatar: String?,
    legacyAgentAvatar: String?,
): String = storedBackgroundAvatar ?: legacyAgentAvatar ?: SphereAvatar.id

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication()

    companion object {
        // Shared log tag for connection lifecycle + handoff tracing. Pairs with
        // ConnectionManager's "ConnectionManager" tag so a single
        // `logcat -s ConnectionVM ConnectionManager` shows the full relay
        // socket → derived-state → surfaced-banner story.
        private const val TAG = "ConnectionVM"

        // API Server (direct chat)
        private val KEY_API_SERVER_URL = stringPreferencesKey("api_server_url")
        private const val DEFAULT_API_URL = "http://localhost:8642"

        // Relay Server (bridge/terminal)
        private val KEY_RELAY_URL = stringPreferencesKey("relay_url")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url") // legacy migration
        private const val DEFAULT_RELAY_URL = "ws://localhost:8767"

        // How long the derived [relayUiState] shows `Connecting` before
        // promoting a Paired-but-Disconnected pose to `Stale`. Tuned to
        // cover a typical WSS handshake (~500-2000 ms) plus a cushion so
        // we don't flash "Stale" during a normal post-resume reconnect,
        // while still surfacing a tap-to-retry affordance when the
        // reconnect genuinely fails (server down, bad network).
        private const val RELAY_RECONNECT_GRACE_MS = 5_000L

        // A brief switch-away (glance at another app) doesn't need the full
        // cache-clearing re-probe [revalidateOnResume] normally does — the
        // sockets keep 30s pings and the connection was healthy moments ago.
        // Only pay the re-probe (+ Probing badge flash) when we were away long
        // enough that the connection could have gone stale, or when it isn't
        // already healthy. Network *changes* are handled independently by the
        // ConnectivityObserver/network callbacks, not by revalidate(), so
        // skipping here can't miss a Wi-Fi↔cellular flip.
        private const val BRIEF_RESUME_REVALIDATE_MS = 15_000L

        /**
         * Placeholder label written by [beginAddConnection] before the
         * user has scanned a QR. The pair-success watcher treats a
         * connection whose label is still this exact string as
         * "unlabeled" and auto-renames to the API-host-derived default
         * once pairing completes. Exposed as a constant so tests and
         * the watcher share one source of truth.
         */
        const val PLACEHOLDER_LABEL = "New connection…"

        // Shared
        // Selected app theme id (palette/personality). Orthogonal to KEY_THEME,
        // which is the light/dark/auto mode axis honored by BOTH-mode themes.
        // Optional RGB accent override applied on top of the selected preset.
        // Null means the preset's authored accent remains authoritative.
        // Selected app font id (body typeface). Resolved against AppFont at the
        // Compose theme root; defaults to Inter. Orthogonal to KEY_FONT_SCALE
        // (which scales sizes); this picks the family.
        // Selected sphere skin id. "auto" (SphereRegistry.AUTO_ID) follows the
        // active theme's preferred skin; any other id pins a specific skin.
        private val KEY_SPHERE_SKIN = stringPreferencesKey("sphere_skin")
        // Legacy combined sphere/pet choice. Read during migration so existing
        // users retain both their prior central visual and companion choice.
        private val KEY_AGENT_AVATAR = stringPreferencesKey("agent_avatar")
        // Optional floating companion id. The explicit sentinel distinguishes
        // "no pet" from a preference that has not yet been migrated.
        private val KEY_FLOATING_PET = stringPreferencesKey("floating_pet")
        // Central visualization selection, independent from the floating pet.
        private val KEY_BACKGROUND_AVATAR = stringPreferencesKey("background_avatar")
        // Reserved storage sentinel. Pet ids may legitimately be ordinary words
        // such as "none", so keep the no-selection marker outside that space.
        private const val NO_FLOATING_PET = "__none__"
        private val KEY_PET_ROAMING_ENABLED = booleanPreferencesKey("pet_roaming_enabled")
        private val KEY_PET_PLACEMENT_EDGE = stringPreferencesKey("pet_placement_edge")
        private val KEY_PET_PLACEMENT_FRACTION = floatPreferencesKey("pet_placement_fraction")
        private val DEFAULT_PET_PLACEMENT = PetPlacement(PetLogicalEdge.End, 0.82f)
        private val KEY_PET_SPEED = floatPreferencesKey("pet_speed")
        private val KEY_PET_STABILIZE = booleanPreferencesKey("pet_stabilize")
        const val DEFAULT_FONT_SCALE: Float = 1.0f
        private val KEY_INSECURE_MODE = booleanPreferencesKey("insecure_mode")
        private val KEY_LAST_SEEN_VERSION = stringPreferencesKey("last_seen_version")
        private val KEY_LAST_SESSION_ID = stringPreferencesKey("last_session_id")
        private val KEY_SHOW_THINKING = booleanPreferencesKey("show_thinking")
        private val KEY_TOOL_DISPLAY = stringPreferencesKey("tool_display")
        private val KEY_THINKING_INDICATOR_STYLE = stringPreferencesKey("thinking_indicator_style")
        private val KEY_THINKING_MATRIX_PATTERN = stringPreferencesKey("thinking_matrix_pattern")
        private val KEY_THINKING_MATRIX_COLOR = stringPreferencesKey("thinking_matrix_color")
        private val KEY_APP_CONTEXT = booleanPreferencesKey("app_context_prompt")
        // === PHASE3-status: granular phone-status sub-toggles ===
        // Gated by the master KEY_APP_CONTEXT. Privacy-sensitive fields
        // (current_app, battery) default false; everything else defaults true.
        private val KEY_APP_CONTEXT_BRIDGE_STATE = booleanPreferencesKey("app_context_bridge_state")
        private val KEY_APP_CONTEXT_CURRENT_APP = booleanPreferencesKey("app_context_current_app")
        private val KEY_APP_CONTEXT_BATTERY = booleanPreferencesKey("app_context_battery")
        private val KEY_APP_CONTEXT_SAFETY_STATUS = booleanPreferencesKey("app_context_safety_status")
        // === END PHASE3-status ===
        private val KEY_STREAMING_ENDPOINT = stringPreferencesKey("streaming_endpoint")
        private val KEY_PARSE_TOOL_ANNOTATIONS = booleanPreferencesKey("parse_tool_annotations")
        private val KEY_SHOW_SYSTEM_MESSAGES = booleanPreferencesKey("show_system_messages")
        private val KEY_MAX_ATTACHMENT_MB = intPreferencesKey("max_attachment_mb")
        private val KEY_MAX_MESSAGE_LENGTH = intPreferencesKey("max_message_length")

        // Animation
        private val KEY_ANIMATION_ENABLED = booleanPreferencesKey("animation_enabled")
        private val KEY_ANIMATION_BEHIND_CHAT = booleanPreferencesKey("animation_behind_chat")
        private val KEY_BACKGROUND_VISUALIZATION_ENABLED =
            booleanPreferencesKey("background_visualization_enabled")
        private val KEY_IMAGE_GENERATION_STYLE = stringPreferencesKey("image_generation_style")
        private val KEY_CHAT_RECENT_PROMPTS = booleanPreferencesKey("chat_recent_prompts")

        // Chat scroll behavior
        private val KEY_SMOOTH_AUTO_SCROLL = booleanPreferencesKey("smooth_auto_scroll")
        private val KEY_CLOSE_DRAWER_ON_SEND = booleanPreferencesKey("close_drawer_on_send")
        private val KEY_KEEP_COMPOSER_FOCUSED_ON_SEND =
            booleanPreferencesKey("keep_composer_focused_on_send")

        // Turn-complete notification ("Notify when Hermes finishes")
        private val KEY_NOTIFY_TURN_COMPLETE = booleanPreferencesKey("notify_turn_complete")

        // One-shot "Live voice conversation" hint on the input bar's voice slot
        private val KEY_VOICE_HINT_SEEN = booleanPreferencesKey("voice_mode_hint_seen")
    }

    private val petBehaviorPreferencesRepository =
        PetBehaviorPreferencesRepository(application)
    private val chatInputPreferencesRepository =
        ChatInputPreferencesRepository(application)

    // --- Core networking components ---

    // Relay (bridge/terminal)
    val multiplexer = ChannelMultiplexer()
    val chatHandler = ChatHandler()

    // --- Offline Demo / Explore mode ------------------------------------
    // Additive, network-free path layered on top of the real connection
    // model: "Try the demo" loads a canned transcript through the real chat
    // pipeline so a fresh install (or a Play reviewer) can see the app work
    // with zero setup. While active, the network entry points below
    // (reconnectIfStale / revalidate / connectRelay) early-return so demo
    // runs with airplane mode on. State lives in the pure-JVM [DemoMode]
    // holder for testability; we delegate `isDemoMode` to it.
    private val demoMode = DemoMode()
    val isDemoMode: StateFlow<Boolean> = demoMode.active

    /**
     * Enter offline Demo mode: load the canned transcript into the chat
     * handler and flip the demo flag. Does NOT mark onboarding complete and
     * does NOT start any connection. [com.hermesandroid.relay.ui.RelayApp]
     * binds the chat handler + navigates to Chat after calling this.
     */
    fun enterDemoMode() {
        demoMode.enter()
        chatHandler.loadDemoTranscript(DemoContent.transcript())
    }

    /**
     * Exit Demo mode: clear the demo flag and wipe the canned transcript,
     * returning the chat surface to a clean "no connection" state. The caller
     * routes the user back to the real Connect flow.
     */
    fun exitDemoMode() {
        demoMode.exit()
        chatHandler.clearMessages()
    }

    // Multi-connection: the ConnectionStore is the source of truth for the
    // list of Hermes server connections and which one is active. Constructed
    // before AuthManager so the init-time migrateLegacyConnectionIfNeeded()
    // call can see the existing URL preferences and seed connection 0.
    val connectionStore: ConnectionStore = ConnectionStore(application)

    /**
     * Multi-connection: id of the currently-active [Connection], or null if
     * no connection has been seeded yet (cold boot before
     * migrateLegacyConnectionIfNeeded runs). Exposed through
     * [connectionStore] directly for cheap access.
     */
    val connections: StateFlow<List<Connection>> = connectionStore.connections
    val activeConnection: StateFlow<Connection?> = connectionStore.activeConnection
    val activeConnectionId: StateFlow<String?> = connectionStore.activeConnectionId
    val startupConnectionId: StateFlow<String?> = connectionStore.startupConnectionId

    // AuthManager owns the CertPinStore; ConnectionManager takes a snapshot
    // of the store's current pins on every connect so re-pair wipes land.
    // AuthManager must be constructed before ConnectionManager so the pin
    // store is available for the certificate pinner.
    //
    // Multi-connection: AuthManager is now a `var` — switchConnection()
    // rebuilds it bound to the newly-active connection id. Every call site
    // that touches this field goes through `this.authManager` so the
    // reconnect gate, the multiplexer sendCallback, and the
    // media-projection screen-capture token provider all read the current
    // instance after a swap.
    //
    // Initial value uses the active connection id if ConnectionStore has
    // already hydrated by the time this field initializer runs — which it
    // normally hasn't, since hydration is async. We fall back to the legacy
    // sentinel so the very first boot before migration mid-flight still
    // binds to the legacy token store. The init block below then observes
    // the first connection emission and rebuilds against the migrated id.
    var authManager: AuthManager = AuthManager(
        context = application,
        multiplexer = multiplexer,
        scope = viewModelScope,
        connectionId = AuthManager.CONNECTION_ID_LEGACY,
        // Sentinel: replaced the moment the active connection hydrates
        // (restorePersistedActiveConnectionContext), so skip the eager keyset
        // decrypt it would otherwise do just to be thrown away.
        eagerHydrate = false,
    )
        private set

    // Multi-connection: authState, pairingCode, and currentPairedSession
    // were previously direct references to the initial AuthManager's
    // backing flows. After a connection switch, `authManager` is replaced
    // but a captured reference still points at the OLD instance —
    // Compose's collectAsState caches the flow on first composition, so
    // consumers would show the previous connection's auth state forever.
    // We flatMapLatest over this MutableStateFlow so the public flows
    // re-subscribe to the new manager whenever installAuthManager() swaps
    // it.
    private val _authManagerFlow = MutableStateFlow(authManager)

    // Pass hasPairContext as the reconnect gate — the auto-reconnect loop
    // inside ConnectionManager bypasses ConnectionViewModel.connectRelay's
    // primary gate, so we plumb the same AuthManager check directly into
    // the internal scheduler. Without this, clearSession leaves a live
    // reconnect loop that fires stale auth envelopes and rate-limits us.
    //
    // Multi-connection: the gate reads `this.authManager.hasPairContext` on
    // every invocation so a connection switch's freshly-built AuthManager
    // is honored without having to plumb a new gate through
    // ConnectionManager. CertPinStore is process-wide (not per-connection)
    // so holding a reference to the legacy AuthManager's pin store is
    // still correct after a swap.
    // OkHttp used by [endpointResolver] for HEAD /health probes. Keep it
    // distinct from the relay HTTP client (long read timeout for media
    // downloads) so probe timeouts stay tight and don't pick up a 2-minute
    // stream inheritance from the shared pool. See ADR 24.
    private val endpointProbeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    private val endpointResolver = EndpointResolver(
        httpClient = endpointProbeClient,
        clientForCandidate = { candidate ->
            candidate.pluginProxyRoutesOrNull()?.let { proxy ->
                val tokenProvider = { (authManager.authState.value as? AuthState.Paired)?.token }
                if (candidate.hermesReachRouteOrNull() != null) {
                    buildHermesReachClient(
                        baseBuilder = endpointProbeClient.newBuilder(),
                        outerClient = endpointProbeClient,
                        candidate = candidate,
                        sessionTokenProvider = tokenProvider,
                    )
                } else {
                    buildPluginProxyClient(
                        baseBuilder = endpointProbeClient.newBuilder(),
                        routes = proxy,
                        sessionTokenProvider = tokenProvider,
                    )
                }
            }
        },
        context = application,
    )

    private val connectionManager = ConnectionManager(
        multiplexer,
        authManager.certPinStore,
        reconnectGate = { authManager.hasPairContext },
        context = application,
        endpointResolver = endpointResolver,
        endpointCandidatesProvider = { activeRouteCandidatesSnapshot() },
        proxyClientProvider = { url -> pluginProxyClientForUrl(url) },
        // Pull the active device id through AuthManager — it's the same id
        // PairingPreferences keys the endpoint list on. Nullable wrapper
        // because AuthManager.getOrCreateDeviceId() is suspending.
        deviceIdProvider = { runCatching { authManager.getOrCreateDeviceId() }.getOrNull() },
        dashboardRelayRequestProvider = { relayUrl ->
            dashboardRelayRequestForIngress(relayUrl)
        },
    )

    // Data management — ConnectionStore flows through so exportSettings()
    // can include the current multi-connection snapshot in the backup blob.
    val dataManager = DataManager(application, connectionStore)

    // --- Inbound media pipeline ------------------------------------------------
    //
    // Three singletons that together let tool output emit `MEDIA:hermes-relay://<token>`
    // markers which the phone turns into inline attachments:
    //   - [mediaSettingsRepo] exposes user-tunable limits (max size, cache cap, etc.)
    //   - [mediaCacheWriter]  writes fetched bytes to `cacheDir/hermes-media/` and
    //                         hands out `content://` URIs via the FileProvider.
    //   - [relayHttpClient]   pulls bytes from `GET /media/<token>` on the relay
    //                         using the same session token as the WSS channel.
    //
    // These are owned by the ConnectionViewModel so they share lifetime with the
    // rest of the networking stack and get torn down on onCleared().
    val mediaSettingsRepo = MediaSettingsRepository(application)

    val mediaCacheWriter = MediaCacheWriter(
        context = application,
        cachedMediaCapMbProvider = {
            // Read from the current DataStore snapshot — the repo exposes a Flow,
            // but the writer calls this from a suspend context synchronously
            // during cache() and we want the latest value without blocking. Use
            // a tiny cached state that the DataStore collect loop updates.
            _cachedMediaCapMb
        }
    )

    /** Mirrored cap (MB) kept in sync with DataStore so [mediaCacheWriter] reads cheaply. */
    @Volatile
    private var _cachedMediaCapMb: Int = MediaSettingsRepository.DEFAULT_CACHED_MEDIA_CAP_MB

    /**
     * Shared OkHttp instance for the relay HTTP client. Separate from the one
     * inside [HermesApiClient] so API-server and relay connections don't
     * interfere, but configured the same way (long read timeout to handle
     * slow mobile connections + large files).
     */
    private val relayOkHttp: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(2, TimeUnit.MINUTES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    val relayHttpClient = RelayHttpClient(
        okHttpClient = relayOkHttp,
        relayUrlProvider = { effectiveRelayUrlSnapshot() },
        context = application,
        sessionTokenProvider = {
            // AuthManager holds the paired session token in EncryptedSharedPrefs.
            // Pull it out via the authState StateFlow snapshot — if we're not
            // paired yet, return null and the fetch fails with a clean error.
            (authManager.authState.value as? AuthState.Paired)?.token
        },
        pairedTokenSnapshot = {
            // Same synchronous paired-token read the fetch uses, so the media-
            // capability badge agrees with whether /media/by-path can actually
            // fetch (no current paired token → the fetch can't authenticate).
            (authManager.authState.value as? AuthState.Paired)?.token
        },
        dashboardHttpClientProvider = ::dashboardHttpClientForRelayIngress,
    )

    // Pairing-management collaborator — owns the paired-devices list
    // (GET /sessions) and the insecure-ack DataStore flags. Extracted from
    // this ViewModel (ADR 34 decomposition); the public getters/functions
    // below delegate here unchanged.
    private val pairingController = PairingController(
        context = application,
        scope = viewModelScope,
        relayHttpClient = relayHttpClient,
    )

    // Upstream dashboard/gateway transport collaborator — owns the per-connection
    // dashboard cookie stores, the consolidated DashboardApiClient factory, the
    // cached GatewayChatClient + its availability tier, and the capability-driven
    // streaming-endpoint resolution. The providers read live ViewModel state
    // lazily so resolution follows the active LAN/Tailscale route.
    private val upstreamTransport = UpstreamTransportController(
        context = application,
        activeConnectionIdProvider = { connectionStore.activeConnectionId.value },
        dashboardUrlProvider = { activeDashboardUrl() },
        gatewayKeepAliveProvider = {
            gatewayKeepAlive.value || ActiveTurnKeepAliveRegistry.snapshot.value.required
        },
        // Lets the dashboard cookie store ride the connection's token keyset
        // (one keyset build instead of two on cold start).
        tokenStoreKeyProvider = { cid ->
            connectionStore.connections.value.firstOrNull { it.id == cid }?.tokenStoreKey
        },
        trustedDashboardUrlProvider = { cid ->
            if (connectionStore.activeConnectionId.value == cid) {
                activeDashboardUrl()
            } else {
                connectionStore.connections.value.firstOrNull { it.id == cid }
                    ?.resolvedDashboardUrl
                    ?.takeIf(String::isNotBlank)
            }
        },
        pinnedClientProvider = { url, base ->
            pluginProxyClientForUrl(url, base, includeRelaySessionHeader = false)
        },
    )

    // Agent-profiles collaborator — owns the merged profile list, the
    // per-connection selected-profile state machine + its persistence stores,
    // the profile display alias, and the per-profile last-session restore.
    // Lifecycle hooks are driven by this ViewModel's init observers (below).
    private val profileController = ProfileController(
        context = application,
        scope = viewModelScope,
        authManagerFlow = _authManagerFlow,
        activeConnectionId = connectionStore.activeConnectionId,
        activeDashboardUrlProvider = { activeDashboardUrl() },
        dashboardClientFactory = { cid, url -> upstreamTransport.dashboardClientFor(cid, url) },
        streamingEndpointProvider = { streamingEndpoint.value },
        gatewayAvailabilityProvider = { upstreamTransport.gatewayAvailability.value },
        setLastSessionId = { _lastSessionId.value = it },
        legacyDefaultSessionId = {
            getApplication<Application>().relayDataStore.data.first()[KEY_LAST_SESSION_ID]
        },
        rebuildChatApiClient = { rebuildChatApiClient() },
        relayHttpClient = relayHttpClient,
        gatewayClientProvider = { upstreamTransport.activeGatewayChatClient() },
    )

    private val botModeController = BotModeController(
        scope = viewModelScope,
        connections = connectionStore.connections,
        activeConnectionId = connectionStore.activeConnectionId,
        dashboardUrlProvider = { connection ->
            if (connectionStore.activeConnectionId.value == connection.id) {
                activeDashboardUrl().orEmpty()
            } else {
                connection.resolvedDashboardUrl
            }
        },
        dashboardClientFactory = upstreamTransport::dashboardClientFor,
        gatewayLeaseFactory = upstreamTransport::acquireGatewayRoute,
    )

    // --- Relay connection state ---
    val relayConnectionState: StateFlow<ConnectionState> = connectionManager.connectionState

    // Resolved UI state for the relay row — the single source of truth all
    // three connection-related screens consume. Driven by a coroutine in
    // `init` that combines authState + relayConnectionState + relayUrl and
    // applies a grace window before promoting Paired-but-Disconnected to
    // Stale. See [RelayUiState] kdoc for the full rationale.
    private val _relayUiState = MutableStateFlow<RelayUiState>(RelayUiState.NotConfigured)
    val relayUiState: StateFlow<RelayUiState> = _relayUiState.asStateFlow()
    private val _connectionHandoffStatus = MutableStateFlow<ConnectionHandoffStatus?>(null)
    val connectionHandoffStatus: StateFlow<ConnectionHandoffStatus?> =
        _connectionHandoffStatus.asStateFlow()
    private var connectionHandoffClearJob: Job? = null
    // Timestamp of the last app foreground resume (cold start counts). A relay
    // reconnect within RELAY_RECONNECT_GRACE_MS of this is almost always the
    // same connection re-handshaking after the OS dropped the socket in the
    // background — we suppress its transient banner so the user isn't shown a
    // misleading "Reconnecting"/"Connection changed" flash on every app switch.
    @Volatile
    private var lastForegroundResumeAtMs: Long = 0L
    // True while a just-resumed reconnect's banner is being withheld. Lets the
    // subsequent "Connection restored" pair stay silent too if we never showed
    // the reconnecting banner. Touched only from the main-thread state collector.
    private var suppressedTransientReconnect = false
    private var transientReconnectJob: Job? = null
    // True for RELAY_RECONNECT_GRACE_MS right after a foreground resume. The
    // handoff path suppresses its own "Reconnecting" banner on resume, but the
    // *health*-derived cue ("Connecting to Hermes", active) leaks the bottom-strip
    // "Reconnecting…" cue independently — so a benign resume flashed the cue and
    // then cleared with no "Connected" toast (handoff stayed suppressed). The UI
    // gates the bottom-strip cue on this so a benign resume is fully silent; if
    // the socket is still down past the window it un-gates and the cue shows.
    private val _postResumeQuiet = MutableStateFlow(false)
    val postResumeQuiet: StateFlow<Boolean> = _postResumeQuiet.asStateFlow()
    private var postResumeQuietJob: Job? = null
    private val _serverChatDisplaySettings =
        MutableStateFlow<DashboardChatDisplaySettings?>(null)

    /**
     * ADR 24 — [relayUiState] bundled with the currently-active endpoint
     * role so connection chips can render "Connected · Tailscale" etc.
     * New screens should prefer [relayRowState] over [relayUiState] when
     * they want to surface the transport role; existing `== RelayUiState.X`
     * comparisons keep working off [relayUiState].
     */
    val relayRowState: StateFlow<RelayRowState> = combine(
        _relayUiState,
        connectionManager.activeRelayEndpoint,
    ) { phase, endpoint ->
        RelayRowState(phase = phase, activeEndpointRole = endpoint?.role)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        RelayRowState(phase = RelayUiState.NotConfigured, activeEndpointRole = null),
    )

    /** Currently-active endpoint, for the Endpoints card. */
    val activeEndpoint = connectionManager.activeEndpoint
    val activeRelayEndpoint = connectionManager.activeRelayEndpoint

    private suspend fun activeRouteCandidatesSnapshot(): List<EndpointCandidate> {
        val activeId = connectionStore.activeConnectionId.value ?: return emptyList()
        val current = connectionStore.connections.value
            .firstOrNull { it.id == activeId }
            ?: return emptyList()
        val deviceId = runCatching { authManager.getOrCreateDeviceId() }.getOrNull()
        val pairedRoutes = deviceId?.let { id ->
            runCatching {
                PairingPreferences.getDeviceEndpoints(getApplication(), id).first()
            }.getOrDefault(emptyList())
        }.orEmpty()
        val recovered = mergeRelayTransportIntoStandardRoutes(
            standardRoutes = current.routeCandidates,
            relayRoutes = pairedRoutes,
        )
        if (recovered != current.routeCandidates) {
            connectionStore.updateConnection(current.copy(routeCandidates = recovered))
        }
        return recovered
    }

    private fun normalizeStandardRouteCandidates(
        candidates: List<EndpointCandidate>,
    ): List<EndpointCandidate> {
        return candidates.map { candidate ->
            val relayUrl = candidate.relay?.url?.takeIf { it.isNotBlank() }
                ?: return@map candidate
            val transportHint = when {
                relayUrl.startsWith("wss://", ignoreCase = true) -> "wss"
                relayUrl.startsWith("ws://", ignoreCase = true) -> "ws"
                else -> candidate.relay?.transportHint
            }
            candidate.copy(
                relay = RelayEndpoint(
                    url = relayUrl,
                    transportHint = transportHint,
                ),
            )
        }
    }

    /**
     * Rebuild the primary route candidate from freshly-saved URLs while
     * preserving the active connection's extra routes (priority > 0 — e.g.
     * the setup wizard's Tailscale URL, or extra endpoints from a pairing
     * payload). Editing the API or Relay URL used to call
     * [Connection.buildRouteCandidates] with no extras, silently collapsing
     * the stored list to a single candidate and killing roaming.
     */
    private fun mergedRouteCandidates(
        apiServerUrl: String,
        relayUrl: String,
        extraApiUrls: List<Pair<String, String>> = emptyList(),
    ): List<EndpointCandidate> = Connection.mergeRouteCandidates(
        rebuilt = Connection.buildRouteCandidates(
            apiServerUrl = apiServerUrl,
            relayUrl = relayUrl,
            extraApiUrls = extraApiUrls,
            dashboardUrl = activeConnection.value?.resolvedDashboardUrl,
        ),
        existing = activeConnection.value?.routeCandidates.orEmpty(),
    )

    private fun effectiveApiServerUrlSnapshot(): String =
        resolveEffectiveApiServerUrl(
            savedUrl = _apiServerUrl.value,
            endpoint = connectionManager.activeApiEndpoint.value,
        )

    private fun effectiveRelayUrlSnapshot(): String =
        resolveEffectiveRelayUrl(
            savedRelayUrl = _relayUrl.value,
            savedApiUrl = _apiServerUrl.value,
            activeRelayEndpoint = connectionManager.activeRelayEndpoint.value,
            relayConfigured = activeRelayConfiguredSnapshot(),
        )

    private fun effectiveRelayWebSocketUrlSnapshot(): String =
        if (!activeRelayConfiguredSnapshot()) "" else
            connectionManager.activeRelayEndpoint.value?.pluginProxyRoutesOrNull()?.relayWebSocketUrl
            ?: connectionManager.activeRelayEndpoint.value?.relay?.url
            ?: autoRelayUrlSnapshot()

    private fun pluginProxyClientForUrl(
        url: String,
        baseClient: OkHttpClient? = null,
        includeRelaySessionHeader: Boolean = true,
    ): OkHttpClient? {
        val requestAuthority = runCatching {
            val parsed = java.net.URI(url)
            val port = if (parsed.port > 0) parsed.port else 443
            "${parsed.host?.lowercase()}:$port"
        }.getOrNull() ?: return null
        val candidate = activeConnection.value?.routeCandidates.orEmpty()
            .firstOrNull { it.pluginProxyRoutesOrNull()?.authority == requestAuthority }
            ?: return null
        val routes = candidate.pluginProxyRoutesOrNull() ?: return null
        val configuredBuilder = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
        val sessionTokenProvider = {
            (authManager.authState.value as? AuthState.Paired)?.token
        }
        if (candidate.hermesReachRouteOrNull() != null) {
            return buildHermesReachClient(
                baseBuilder = configuredBuilder,
                outerClient = endpointProbeClient,
                candidate = candidate,
                sessionTokenProvider = sessionTokenProvider,
                includeRelaySessionHeader = includeRelaySessionHeader,
            )
        }
        return buildPluginProxyClient(
            baseBuilder = configuredBuilder,
            routes = routes,
            sessionTokenProvider = sessionTokenProvider,
            includeRelaySessionHeader = includeRelaySessionHeader,
        )
    }

    private fun autoRelayUrlSnapshot(): String {
        val savedRelay = _relayUrl.value
        val savedApi = _apiServerUrl.value
        return if (RelayUrlDeriver.isAutoManagedRelayUrl(savedRelay, savedApi)) {
            RelayUrlDeriver.deriveFromApiUrl(savedApi) ?: savedRelay
        } else {
            savedRelay
        }
    }

    private fun isRelayConfiguredFor(connection: Connection?, auth: AuthState): Boolean {
        if (auth is AuthState.Paired) return true
        if (connection?.pairedAt != null) return true
        val relayUrl = connection?.relayUrl?.trim().orEmpty()
        return relayUrl.isNotBlank() &&
            !RelayUrlDeriver.isAutoManagedRelayUrl(relayUrl, connection?.apiServerUrl.orEmpty())
    }

    private fun activeRelayConfiguredSnapshot(): Boolean =
        isRelayConfiguredFor(activeConnection.value, authState.value)

    /**
     * Signal stream for `profiles.updated` pushes — emits when the
     * server's in-memory profile list has changed in a way the user
     * should know about (a different set of names or a different
     * count). The UI layer collects this flow to show a brief
     * "Profiles updated" snackbar.
     *
     * Sourced from [AuthManager.profilesUpdatedEvents] so the filter
     * logic ("actually changed") is centralised there rather than
     * duplicated per-subscriber.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val profilesUpdatedEvents: kotlinx.coroutines.flow.SharedFlow<Unit> =
        _authManagerFlow
            .flatMapLatest { it.profilesUpdatedEvents }
            .shareIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                replay = 0,
            )

    /**
     * Per-connection auth-success signal, sourced from the *current*
     * [AuthManager] so it follows connection switches (the `var authManager`
     * is rebuilt on switch). Drives proactive re-subscribe on every reconnect.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val authOkEvents: kotlinx.coroutines.flow.SharedFlow<Unit> =
        _authManagerFlow
            .flatMapLatest { it.authOkEvents }
            .shareIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                replay = 0,
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    val authState: StateFlow<AuthState> = _authManagerFlow
        .flatMapLatest { it.authState }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authManager.authState.value)

    @OptIn(ExperimentalCoroutinesApi::class)
    val apiKeyPresent: StateFlow<Boolean> = _authManagerFlow
        .flatMapLatest { it.apiKeyPresent }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authManager.apiKeyPresent.value)

    val insecureMode: StateFlow<Boolean> = connectionManager.insecureMode
    val isInsecureConnection: StateFlow<Boolean> = connectionManager.isInsecureConnection

    // --- API Server state ---
    // Blank is the unhydrated sentinel. Seeding this with the legacy localhost
    // default made a discovered remote API candidate look explicitly configured
    // during the first DataStore frame, briefly building an unauthenticated
    // Sessions client before a Dashboard-only connection restored its saved
    // blank URL.
    private val _apiServerUrl = MutableStateFlow("")
    val apiServerUrl: StateFlow<String> = _apiServerUrl.asStateFlow()

    private val _apiServerReachable = MutableStateFlow(false)
    val apiServerReachable: StateFlow<Boolean> = _apiServerReachable.asStateFlow()

    /**
     * Tri-state health for the API server. Distinct from
     * [apiServerReachable] (a Boolean) because the UI needs to render a
     * dedicated "Probing" pose right after foreground / network change so
     * the badge doesn't flash a stale Connected/Disconnected from the last
     * session. The boolean stays in place for legacy callers; new code
     * should consume this flow.
     */
    enum class HealthStatus { Unknown, Probing, Reachable, Unreachable }

    private val _apiServerHealth = MutableStateFlow(HealthStatus.Unknown)
    val apiServerHealth: StateFlow<HealthStatus> = _apiServerHealth.asStateFlow()

    private val _relayServerHealth = MutableStateFlow(HealthStatus.Unknown)
    val relayServerHealth: StateFlow<HealthStatus> = _relayServerHealth.asStateFlow()

    private val _apiClient = MutableStateFlow<HermesApiClient?>(null)
    val apiClient: StateFlow<HermesApiClient?> = _apiClient.asStateFlow()

    private val _chatApiClient = MutableStateFlow<HermesApiClient?>(null)
    val chatApiClient: StateFlow<HermesApiClient?> = _chatApiClient.asStateFlow()
    private var profileChatApiClient: HermesApiClient? = null
    private var profileChatApiClientUrl: String? = null
    private var profileChatApiClientKey: String? = null

    // Chat mode + per-endpoint capability snapshot — owned by
    // [upstreamTransport]; getters delegate. `rebuildApiClient()` pushes the
    // freshly-probed snapshot via `setCapabilitiesAndMode`.
    val chatMode: StateFlow<ChatMode> get() = upstreamTransport.chatMode

    val serverCapabilities: StateFlow<ServerCapabilities> get() = upstreamTransport.serverCapabilities

    /**
     * Standard (no-plugin) voice rides the upstream **dashboard web server**
     * (`/api/audio/transcribe` + `/api/audio/speak`, the hermes-desktop voice
     * contract) — not the API server, whose current upstream advertises
     * `audio_api: false`. Availability therefore tracks dashboard state:
     * reachability via the public `/api/status`, auth via `/api/auth/me`
     * against the same per-connection cookie store Manage signs in with, and
     * route presence via a HEAD probe (405 = exists, 404 = build too old).
     */
    private val _standardVoiceAvailability =
        MutableStateFlow(StandardVoiceAvailability.Unknown)
    val standardVoiceAvailability: StateFlow<StandardVoiceAvailability> =
        _standardVoiceAvailability.asStateFlow()

    private val _hostResourcePressure = MutableStateFlow(HostResourcePressureStatus())
    val hostResourcePressure: StateFlow<HostResourcePressureStatus> =
        _hostResourcePressure.asStateFlow()

    private val _standardAudioApiReachable = MutableStateFlow(false)
    val standardAudioApiReachable: StateFlow<Boolean> = _standardAudioApiReachable.asStateFlow()

    /**
     * Gateway chat transport (tui_gateway over the dashboard's `/api/ws`)
     * availability. Piggybacks on [probeStandardVoice] — same surface, same
     * `/api/status` + `/api/auth/me` checks — minus the audio-route HEAD:
     * `/api/ws` ships with every embedded-chat dashboard build, so route
     * absence is only discovered (and made sticky) at WS-upgrade time via
     * [markGatewayUnsupported].
     */
    // Gateway availability + cached gateway client + per-connection dashboard
    // cookie stores are owned by [upstreamTransport]; these getters/functions
    // preserve the public surface and the private-call sites unchanged.
    val gatewayAvailability: StateFlow<GatewayAvailability> get() = upstreamTransport.gatewayAvailability

    fun markGatewayUnsupported() = upstreamTransport.markGatewayUnsupported()

    private fun updateGatewayAvailability(probed: GatewayAvailability) =
        upstreamTransport.updateGatewayAvailability(probed)

    fun activeGatewayChatClient(): GatewayChatClient? = upstreamTransport.activeGatewayChatClient()

    /**
     * Dashboard URL for the active connection **on the currently-resolved
     * route** — snapshot twin of [effectiveDashboardUrl], which it delegates
     * to. Standard voice and the availability probe read this per call, so
     * an auto-managed dashboard URL follows LAN/Tailscale handoffs the same
     * way Manage does; an explicit dashboard override stays pinned. (This
     * used to read the persisted `resolvedDashboardUrl`, which kept voice
     * aimed at the LAN host after the resolver had moved chat to Tailscale.)
     */
    fun activeDashboardUrl(): String? =
        effectiveDashboardUrl.value.takeIf { it.isNotBlank() }

    /**
     * Promote the exact reviewed Dashboard origin that completed cookie/OIDC
     * authentication. Public origins require HTTPS; literal private-overlay
     * HTTP origins retain upstream's local/VPN allowance. This is intentionally
     * Dashboard-only: API fallback and Relay routes retain their ownership.
     *
     * Persistence is rolled back unless the effective Dashboard/Gateway URL
     * observes this exact origin and install identity validation succeeds,
     * preventing a provider-controlled callback from rebinding the connection
     * to a different Hermes installation.
     */
    suspend fun promoteAuthenticatedDashboardOrigin(
        origin: String,
        allowMissingInstallIdentity: Boolean = false,
    ): Boolean {
        val normalized = normalizeAuthenticatedDashboardOrigin(origin) ?: return false
        val activeId = connectionStore.activeConnectionId.value ?: return false
        val previous = connectionStore.connections.value
            .firstOrNull { it.id == activeId }
            ?: return false
        val enteredDashboardUrl = activeDashboardUrl() ?: previous.resolvedDashboardUrl
        val enteredClient = upstreamTransport.dashboardClientForActive(enteredDashboardUrl)
        val enteredInstallId = try {
            withTimeoutOrNull(8_000L) {
                enteredClient.getStatus().getOrNull()?.installId
            }
        } finally {
            enteredClient.shutdown()
        }
        val verificationClient = upstreamTransport.dashboardCookieClientForActive(normalized)
        var candidateInstallId: String? = null
        val verifiedHermes = try {
            withTimeoutOrNull(8_000L) {
                val status = verificationClient.getStatus().getOrNull() ?: return@withTimeoutOrNull false
                candidateInstallId = status.installId
                !status.authRequired || verificationClient.currentSession().getOrNull()?.authenticated == true
            } == true
        } finally {
            verificationClient.shutdown()
        }
        if (!verifiedHermes) return false
        when (dashboardInstallIdentityDecision(enteredInstallId, candidateInstallId)) {
            DashboardInstallIdentityDecision.Match -> Unit
            DashboardInstallIdentityDecision.Mismatch -> return false
            DashboardInstallIdentityDecision.Missing -> if (!allowMissingInstallIdentity) return false
        }
        return persistAuthenticatedDashboardOriginWithRollback(
            previous = previous,
            normalizedOrigin = normalized,
            persist = connectionStore::updateConnection,
            activated = {
                if (connectionStore.activeConnectionId.value != activeId) {
                    false
                } else {
                    val effective = withTimeoutOrNull(2_000L) {
                        effectiveDashboardUrl.first {
                            it.trimEnd('/').equals(normalized, ignoreCase = true)
                        }
                    }
                    effective != null && connectionStore.activeConnectionId.value == activeId
                }
            },
        )
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
        upstreamTransport.dashboardCookieStoreFor(connectionId)

    /**
     * Cookie store for the active connection — the same encrypted store the
     * Manage tab's sign-in flow writes, so a dashboard session established
     * there authenticates voice (and any other dashboard-surface client).
     */
    fun activeDashboardCookieStore(): DashboardCookieStore? =
        upstreamTransport.activeDashboardCookieStore()

    /** Trusted active-connection clients used by the shared dashboard sign-in route. */
    fun dashboardClientForActive(dashboardUrl: String): DashboardApiClient =
        upstreamTransport.dashboardClientForActive(dashboardUrl)

    fun dashboardCookieClientForActive(dashboardUrl: String): DashboardApiClient =
        upstreamTransport.dashboardCookieClientForActive(dashboardUrl)

    fun retireNativeDashboardAuthentication(connectionId: String) =
        upstreamTransport.retireNativeDashboardAuthentication(connectionId)

    fun nativeDashboardAuthClientForActive(dashboardUrl: String): NativeDashboardAuthClient? =
        upstreamTransport.nativeDashboardAuthClientForActive(dashboardUrl)

    fun dashboardHttpClientForActive(dashboardUrl: String): okhttp3.OkHttpClient =
        upstreamTransport.dashboardHttpClientForActive(dashboardUrl)

    /** Dashboard outer-auth transport for the namespaced Relay ingress only. */
    fun dashboardHttpClientForRelayIngress(relayUrl: String): okhttp3.OkHttpClient? {
        val dashboardUrl = dashboardOriginForRelayIngress(activeDashboardUrl(), relayUrl)
            ?: return null
        return upstreamTransport.dashboardHttpClientForActive(dashboardUrl)
    }

    /** Mint a new single-use Dashboard ticket for every Relay ingress dial. */
    suspend fun dashboardRelayRequestForIngress(relayUrl: String): Request? {
        val dashboardUrl = dashboardOriginForRelayIngress(activeDashboardUrl(), relayUrl)
            ?: return null
        val client = upstreamTransport.dashboardClientForActive(dashboardUrl)
        return try {
            val ticket = client.requestWsTicket().getOrNull()?.ticket ?: return null
            dashboardRelayWebSocketRequest(relayUrl, ticket)
        } finally {
            client.shutdown()
        }
    }

    /** Authenticated Dashboard config for dashboard-primary feature catalogs. */
    suspend fun loadActiveDashboardConfig(): Result<JsonObject>? {
        val connectionId = connectionStore.activeConnectionId.value ?: return null
        val dashboardUrl = activeDashboardUrl() ?: return null
        return upstreamTransport.dashboardClientFor(connectionId, dashboardUrl).getConfig()
    }

    // Gateway/Dashboard is the standard path; API remains an optional fallback.
    val chatReady: StateFlow<Boolean> = combine(
        _chatApiClient,
        _apiServerReachable,
        upstreamTransport.gatewayAvailability,
    ) { client, apiReachable, gateway ->
        isChatTransportReady(
            apiClientPresent = client != null,
            apiReachable = apiReachable,
            gatewayAvailability = gateway,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Three-way gate for the chat empty-state, so a cold start doesn't flash
     * the loud "Connect to Hermes" CTA while DataStore is still hydrating.
     *
     * - [ChatConnectState.Ready] — chat client built + server reachable.
     * - [ChatConnectState.Connecting] — either the connection store hasn't
     *   hydrated yet (we don't yet know if anything is configured), OR an
     *   active connection exists but chat isn't reachable yet (cold connect /
     *   route resolve). Show a quiet spinner, never the connect button.
     * - [ChatConnectState.NeedsConnection] — hydration finished and there is
     *   genuinely no connection to use. The only state that shows the CTA.
     *
     * Seeds [ChatConnectState.Connecting] so the very first composed frame —
     * before any flow emits — is the neutral state, not the CTA.
     */
    val chatConnectState: StateFlow<ChatConnectState> = combine(
        connectionStore.isHydrated,
        activeConnection,
        chatReady,
        upstreamTransport.gatewayAvailability,
        _apiServerHealth,
    ) { hydrated, active, ready, gateway, apiHealth ->
        resolveChatConnectState(hydrated, active, ready, gateway, apiHealth)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatConnectState.Connecting)
    // NOTE: [relayReady] / [voiceReady] are declared below the [_relayUrl]
    // MutableStateFlow,
    // further down this file, because Kotlin class-body initializers run
    // top-to-bottom and a forward reference to _relayUrl here would read
    // a null backing field at construction time. Look for the `val
    // relayReady:` / `voiceReady:` declarations near [_relayUrl].

    // --- Relay URL ---
    private val _relayUrl = MutableStateFlow(DEFAULT_RELAY_URL)
    val relayUrl: StateFlow<String> = _relayUrl.asStateFlow()

    /**
     * Runtime route for chat/API traffic. Once API fallback is explicitly
     * configured, a resolver-selected endpoint temporarily wins so paired
     * devices can roam without rewriting stored settings. Discovery alone
     * never enables the optional API surface.
     */
    val effectiveApiServerUrl: StateFlow<String> = combine(
        _apiServerUrl,
        connectionManager.activeApiEndpoint,
    ) { savedUrl, endpoint ->
        resolveEffectiveApiServerUrl(savedUrl, endpoint)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Whether a chat turn is currently streaming — mirrored from
     * [com.hermesandroid.relay.viewmodel.ChatViewModel.isStreaming] by RelayApp.
     * While true, an [effectiveApiServerUrl] route change DEFERS its chat-client
     * rebuild: rebuilding mid-turn replaces the client and cancels the in-flight
     * turn, whereas the gateway socket rides a transient route blip via its own
     * reconnect (keeping the live session). The deferred rebuild applies once
     * the turn ends.
     */
    private val _chatStreaming = MutableStateFlow(false)

    fun setChatStreaming(streaming: Boolean) {
        _chatStreaming.value = streaming
    }

    /** A route change arrived mid-turn and its chat-client rebuild was deferred. */
    @Volatile
    private var pendingApiClientRebuild = false

    /**
     * True only when Relay is an intentional part of the active connection:
     * a paired session exists, the connection has previously paired Relay,
     * or the saved Relay URL is a manual override. Auto-derived same-host
     * `:8767` URLs support setup hints, but they are not enough to nag
     * standard API/dashboard users about Relay health on app resume.
     */
    val relayConfigured: StateFlow<Boolean> = combine(
        activeConnection,
        authState,
    ) { connection, auth ->
        isRelayConfiguredFor(connection, auth)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Runtime Relay route. Blank until Relay is explicitly configured or paired. */
    val effectiveRelayUrl: StateFlow<String> = combine(
        _relayUrl,
        _apiServerUrl,
        connectionManager.activeRelayEndpoint,
        relayConfigured,
    ) { savedRelayUrl, savedApiUrl, endpoint, configured ->
        resolveEffectiveRelayUrl(savedRelayUrl, savedApiUrl, endpoint, configured)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Runtime dashboard route. A selected endpoint's Dashboard/Gateway URL
     * wins because it is part of that route just like API and Relay. When an
     * older candidate has only API metadata, auto-managed dashboards can still
     * derive the conventional Dashboard URL; otherwise the saved URL remains
     * the fallback.
     */
    val effectiveDashboardUrl: StateFlow<String> = combine(
        activeConnection,
        connectionManager.activeEndpoint,
    ) { connection, endpoint ->
        resolveEffectiveDashboardUrl(connection, endpoint)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Relay is ready when we have a configured URL, the WSS socket is
     * Connected, AND the AuthManager is in a Paired state. All three
     * matter:
     *  - URL blank → nothing to connect to (post-teardown state after
     *    removing the last connection).
     *  - WSS not Connected → transport is down; bridge command send would
     *    throw immediately.
     *  - authState not Paired → the server would reject bridge calls at
     *    the bearer-token check, so even a live socket won't help.
     *
     * Consumers:
     *  - BridgeScreen — surfaces a "Relay not connected" nag banner so
     *    the user doesn't flip the master toggle on expecting commands
     *    to flow.
     * Symmetric in shape to [chatReady] above so call sites read
     * consistently; intentionally does NOT collapse with chatReady
     * because the two features have orthogonal network dependencies —
     * Chat runs over direct HTTP to the API server while Bridge rides the
     * relay WSS. Voice has its own [voiceReady] gate because it uses Relay
     * HTTP routes and can authenticate with a Hermes API key.
     */
    val relayReady: StateFlow<Boolean> = combine(
        connectionManager.connectionState,
        authState,
        effectiveRelayUrl,
        relayConfigured,
    ) { connState, auth, url, configured ->
        configured &&
            url.isNotBlank() &&
            connState == ConnectionState.Connected &&
            auth is AuthState.Paired
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Standard voice follows official Hermes Desktop: STT/TTS go through the
     * dashboard web server's `/api/audio` routes. Ready means the dashboard
     * answered `/api/status`, the cookie session satisfies its auth gate (or
     * none is required), and the audio routes exist on this build — the
     * `/api/status` discovery endpoint is designed for preflight, unlike the
     * old API-server HEAD probe this replaces.
     */
    val standardVoiceReady: StateFlow<Boolean> = standardVoiceAvailability
        .map { it == StandardVoiceAvailability.Ready }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Non-null (the active endpoint's display label, e.g. `"Tailscale"`)
     * when the resolver has moved the dashboard surface off the connection's
     * persisted URL. Dashboard session cookies are host-scoped, so a sign-in
     * performed on the LAN host does not authenticate the Tailscale host —
     * every dashboard-riding surface (Manage, standard voice) uses this to
     * say *which* route needs its own sign-in; cookies are never mirrored.
     */
    val dashboardRouteMovedHint: StateFlow<String?> = combine(
        effectiveDashboardUrl,
        activeConnection,
        connectionManager.activeEndpoint,
    ) { dashboardUrl, connection, endpoint ->
        val persisted = connection?.resolvedDashboardUrl?.trim()?.trimEnd('/').orEmpty()
        val effective = dashboardUrl.trim().trimEnd('/')
        if (effective.isBlank() || persisted.isBlank() || effective.equals(persisted, ignoreCase = true)) {
            null
        } else {
            endpoint?.displayLabel() ?: "fallback"
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Route context for the standard-voice sign-in gate. Non-null only when
     * voice is gated on dashboard sign-in AND the route has moved (see
     * [dashboardRouteMovedHint]) — the UI uses this to explain that a
     * one-time sign-in *on this route* unlocks voice, instead of a bare
     * "sign-in required" that looks broken to someone who already signed in
     * at home.
     */
    val standardVoiceSignInRouteHint: StateFlow<String?> = combine(
        standardVoiceAvailability,
        dashboardRouteMovedHint,
    ) { availability, hint ->
        hint.takeIf { availability == StandardVoiceAvailability.SignInRequired }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Relay voice remains available for users who paired or explicitly
     * configured Relay, and for Relay-only realtime/provider paths.
     */
    val relayVoiceReady: StateFlow<Boolean> = combine(
        authState,
        apiKeyPresent,
        effectiveRelayUrl,
        relayConfigured,
    ) { auth, hasApiKey, url, configured ->
        configured && url.isNotBlank() && (auth is AuthState.Paired || hasApiKey)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val voiceReady: StateFlow<Boolean> = combine(
        standardVoiceReady,
        relayVoiceReady,
    ) { standardReady, relayReady ->
        standardReady || relayReady
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Backward compat: expose as serverUrl for any remaining references
    @Deprecated("Use relayUrl or apiServerUrl", replaceWith = ReplaceWith("relayUrl"))
    val serverUrl: StateFlow<String> = _relayUrl

    // Backward compat: expose relay state as connectionState
    @Deprecated("Use relayConnectionState", replaceWith = ReplaceWith("relayConnectionState"))
    val connectionState: StateFlow<ConnectionState> = relayConnectionState

    // Theme preference — light/dark/auto mode axis.
    val theme: StateFlow<String> = application.relayDataStore.data
        .map { preferences ->
            preferences[AppearancePreferences.themeKey] ?: "auto"
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    // Selected app theme id (palette identity). Defaults to the Hermes Relay
    // brand. Resolved against AppThemes.byId at the Compose theme root.
    val appTheme: StateFlow<String> = application.relayDataStore.data
        .map { preferences ->
            preferences[AppearancePreferences.appThemeKey] ?: AppThemes.DEFAULT_ID
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppThemes.DEFAULT_ID)

    val customThemes: StateFlow<List<CustomThemePreset>> = AppearancePreferences.customThemes(application)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeCustomTheme: StateFlow<CustomThemePreset?> = combine(appTheme, customThemes) { themeId, presets ->
        CustomThemePreset.idFromAppTheme(themeId)?.let { id -> presets.firstOrNull { it.id == id } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val appearanceAccent: StateFlow<String?> = application.relayDataStore.data
        .map { preferences -> normalizeAccentHex(preferences[AppearancePreferences.accentKey]) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val appearanceShape: StateFlow<String> = application.relayDataStore.data
        .map { preferences -> AppearanceShape.fromId(preferences[AppearancePreferences.shapeKey]).id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppearanceShape.DEFAULT.id)

    // Selected app font id (body typeface). Defaults to Inter. Resolved against
    // AppFont.byId at the Compose theme root, which rebuilds Typography so the
    // whole app re-themes live when this changes.
    val appFont: StateFlow<String> = application.relayDataStore.data
        .map { preferences ->
            preferences[AppearancePreferences.appFontKey] ?: AppFont.DEFAULT.id
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppFont.DEFAULT.id)

    // Selected sphere skin id ("auto" follows the theme). Resolved against
    // SphereRegistry + loaded user skins at the Compose root.
    val sphereSkin: StateFlow<String> = application.relayDataStore.data
        .map { preferences ->
            preferences[KEY_SPHERE_SKIN] ?: "auto"
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    // Optional floating companion. An absent split key reads the legacy combined
    // selection: sphere -> no companion; a pet id -> companion.
    val floatingPet: StateFlow<String?> = application.relayDataStore.data
        .map { preferences ->
            val selected = preferences[KEY_FLOATING_PET]
                ?: preferences[KEY_AGENT_AVATAR]
                ?: NO_FLOATING_PET
            selected.takeUnless { it == NO_FLOATING_PET || it == SphereAvatar.id }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Sphere or a validated pet-format asset rendered on central/ambient surfaces. */
    val backgroundAvatar: StateFlow<String> = application.relayDataStore.data
        .map { preferences ->
            migratedBackgroundAvatar(
                storedBackgroundAvatar = preferences[KEY_BACKGROUND_AVATAR],
                legacyAgentAvatar = preferences[KEY_AGENT_AVATAR],
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SphereAvatar.id)

    /** Whether the selected companion may autonomously move between safe perches. */
    val petRoamingEnabled: StateFlow<Boolean> = application.relayDataStore.data
        .map { preferences -> preferences[KEY_PET_ROAMING_ENABLED] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Stable preference seam for the floating-pet behavior director. */
    val petBehaviorPreferences: StateFlow<PetBehaviorPreferences> =
        petBehaviorPreferencesRepository.flow.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            PetBehaviorPreferences(),
        )

    val petTemperament: StateFlow<PetTemperament> = petBehaviorPreferences
        .map { preferences -> preferences.temperament }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_PET_TEMPERAMENT)

    val petSizeScale: StateFlow<Float> = petBehaviorPreferences
        .map { preferences -> preferences.sizeScale }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_PET_SIZE_SCALE)

    /** Durable logical placement; pixels are resolved from the current safe viewport. */
    val petPlacement: StateFlow<PetPlacement> = application.relayDataStore.data
        .map { preferences ->
            PetPlacement(
                edge = preferences[KEY_PET_PLACEMENT_EDGE]
                    ?.let { stored -> PetLogicalEdge.entries.firstOrNull { it.name == stored } }
                    ?: DEFAULT_PET_PLACEMENT.edge,
                verticalFraction = preferences[KEY_PET_PLACEMENT_FRACTION]
                    ?: DEFAULT_PET_PLACEMENT.verticalFraction,
            ).sanitized()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_PET_PLACEMENT)

    /**
     * One-release source compatibility for callers still using the old combined
     * selector. New code should use [floatingPet].
     */
    @Deprecated("Use floatingPet")
    val agentAvatar: StateFlow<String> = floatingPet
        .map { it ?: SphereAvatar.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SphereAvatar.id)

    // Bumped to force the Compose root to re-scan the pets/ directory — after an
    // in-app import or delete, or when the Appearance screen opens (so a pack
    // added out-of-band, e.g. via adb, shows without an app restart). RelayApp
    // keys the avatar produceState on this.
    private val _avatarsRefreshTick = MutableStateFlow(0)
    val avatarsRefreshTick: StateFlow<Int> = _avatarsRefreshTick.asStateFlow()

    // One-shot, user-facing results of avatar add/remove for a snackbar.
    private val _avatarEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val avatarEvents: SharedFlow<String> = _avatarEvents.asSharedFlow()

    fun refreshAgentAvatars() {
        _avatarsRefreshTick.value = _avatarsRefreshTick.value + 1
    }

    // Global pet playback-speed multiplier (1.0 = authored fps). Tunable in
    // Appearance; applied to the active pet's clips via LocalPetPlaybackSpeed.
    // The sphere avatar ignores it.
    val petSpeed: StateFlow<Float> = application.relayDataStore.data
        .map { preferences -> preferences[KEY_PET_SPEED] ?: 1f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

    // Re-center each pet frame on its own content (cancels AI-sheet drift).
    // Default on; consumed by PetAvatar via LocalPetStabilize.
    val petStabilize: StateFlow<Boolean> = application.relayDataStore.data
        .map { preferences -> preferences[KEY_PET_STABILIZE] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Global font scale (1.0 = system default). Applied at the Compose theme
    // root via LocalDensity.fontScale and pushed into the xterm WebView via
    // TerminalWebView's LaunchedEffect on this flow.
    val fontScale: StateFlow<Float> = application.relayDataStore.data
        .map { preferences ->
            preferences[AppearancePreferences.fontScaleKey] ?: DEFAULT_FONT_SCALE
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_FONT_SCALE)

    // Onboarding state
    private val _onboardingCompleted = MutableStateFlow(true) // default true to avoid flash
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted.asStateFlow()

    // Pairing code from AuthManager — see _authManagerFlow comment above for
    // why this flatMapLatches rather than referencing authManager directly.
    @OptIn(ExperimentalCoroutinesApi::class)
    val pairingCode: StateFlow<String> = _authManagerFlow
        .flatMapLatest { it.pairingCode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authManager.pairingCode.value)

    // Paired-session snapshot (expires_at, grants, transport hint) from AuthManager.
    // Exposed straight through so SettingsScreen + PairedDevicesScreen can
    // render expiry + grant chips without poking at prefs.
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentPairedSession: StateFlow<PairedSession?> = _authManagerFlow
        .flatMapLatest { it.currentPairedSession }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authManager.currentPairedSession.value)

    // --- Agent profiles (Pass 2) — owned by [profileController] -------------
    //
    // The merged profile list, the per-connection selected-profile state
    // machine + its persistence stores, the display alias, and the per-profile
    // last-session restore now live in ProfileController; these getters/
    // functions delegate. The state machine's lifecycle hooks are driven by
    // this ViewModel's init observers (connection switch / active-connection
    // change / agent-profile arrival / gateway-availability settle) further
    // down, calling profileController.* in their original order.

    val agentProfiles: StateFlow<List<Profile>> get() = profileController.agentProfiles
    val botModeState: StateFlow<BotModeState> get() = botModeController.state

    fun refreshBotMode() = botModeController.refresh()

    suspend fun ensureCanonicalBotChat(route: com.hermesandroid.relay.data.BotGatewayRoute): Result<BotChatTarget> =
        botModeController.ensureCanonicalBotChat(route)

    suspend fun createBot(
        connectionId: String,
        name: String,
        title: String,
        description: String,
    ): Result<String> = botModeController.createBot(connectionId, name, title, description)

    fun acquireBotGateway(
        route: com.hermesandroid.relay.data.BotGatewayRoute,
    ): Result<com.hermesandroid.relay.viewmodel.connection.UpstreamTransportController.RouteGatewayLease> =
        botModeController.acquireGateway(route)

    fun botDashboardClient(
        route: com.hermesandroid.relay.data.BotGatewayRoute,
    ): Result<DashboardApiClient> = botModeController.dashboardClient(route)

    /**
     * Session namespace after resolving the Server-default UI sentinel through
     * upstream `/api/profiles/active`. Explicit named selections are unchanged.
     */
    val effectiveSessionProfileName: StateFlow<String?>
        get() = profileController.effectiveSessionProfileName
    val effectiveDisplayProfile: StateFlow<Profile?>
        get() = profileController.effectiveDisplayProfile

    fun refreshDashboardProfiles() = profileController.refreshDashboardProfiles()

    suspend fun listProfileScopedSessions(limit: Int = 200): Result<List<SessionItem>>? =
        profileController.listProfileScopedSessions(limit)

    suspend fun listProfileScopedSessions(
        profileName: String?,
        limit: Int = 200,
    ): Result<List<SessionItem>>? =
        profileController.listProfileScopedSessions(profileName, limit)

    suspend fun listAllProfileSessions(limit: Int = 200): Result<List<SessionItem>>? =
        profileController.listAllProfileSessions(limit)

    suspend fun deleteSession(
        profileName: String?,
        sessionId: String,
        expectedContextKey: String? = null,
    ): Boolean = profileController.deleteSession(profileName, sessionId, expectedContextKey)

    suspend fun renameSession(
        profileName: String?,
        sessionId: String,
        title: String,
        expectedContextKey: String? = null,
    ): Boolean = profileController.renameSession(profileName, sessionId, title, expectedContextKey)

    suspend fun setSessionPinned(
        profileName: String?,
        sessionId: String,
        pinned: Boolean,
        expectedContextKey: String? = null,
    ): Boolean = profileController.setSessionPinned(profileName, sessionId, pinned, expectedContextKey)

    suspend fun setSessionArchived(
        profileName: String?,
        sessionId: String,
        archived: Boolean,
        expectedContextKey: String? = null,
    ): Boolean = profileController.setSessionArchived(profileName, sessionId, archived, expectedContextKey)

    suspend fun loadProfileScopedMessages(
        sessionId: String,
        mode: SessionMessageLoadMode = SessionMessageLoadMode.COMPLETE,
    ): Result<List<MessageItem>>? = profileController.loadProfileScopedMessages(sessionId, mode)

    suspend fun loadProfileScopedMessages(
        profileName: String?,
        sessionId: String,
        mode: SessionMessageLoadMode = SessionMessageLoadMode.COMPLETE,
    ): Result<List<MessageItem>>? =
        profileController.loadProfileScopedMessages(profileName, sessionId, mode)

    /**
     * Delete a session scoped to the ACTIVE PROFILE via the dashboard
     * `DELETE /api/sessions/{id}?profile=` surface — the write twin of
     * [listProfileScopedSessions]. A non-default profile's sessions live in that
     * profile's own `state.db`, so the unscoped api_server delete leaves the row
     * intact and the next profile-scoped list resurrects it. Resolves the active
     * connection + dashboard URL + profile name exactly as the lister does;
     * returns `false` when there's no dashboard surface so the caller can fall
     * back to the shared api_server delete.
     */
    suspend fun deleteProfileScopedSession(sessionId: String): Boolean {
        return profileController.deleteProfileScopedSession(sessionId)
    }

    /**
     * Rename a session scoped to the ACTIVE PROFILE via the dashboard
     * `PATCH /api/sessions/{id}?profile=` surface — the write twin of
     * [deleteProfileScopedSession]. Without this, a manual (or auto-) rename on
     * a non-default gateway profile patches the shared api_server DB and the new
     * title never lands in the profile's own `state.db`. Returns `false` when
     * there's no dashboard surface so the caller can fall back to the shared
     * api_server rename.
     */
    suspend fun renameProfileScopedSession(sessionId: String, title: String): Boolean {
        return profileController.renameProfileScopedSession(sessionId, title)
    }

    suspend fun setProfileScopedSessionPinned(
        sessionId: String,
        pinned: Boolean,
        expectedContextKey: String?,
    ): Boolean = profileController.setProfileScopedSessionPinned(sessionId, pinned, expectedContextKey)

    suspend fun setProfileScopedSessionArchived(
        sessionId: String,
        archived: Boolean,
        expectedContextKey: String?,
    ): Boolean = profileController.setProfileScopedSessionArchived(sessionId, archived, expectedContextKey)

    val selectedProfile: StateFlow<Profile?> get() = profileController.selectedProfile

    val profilePresentation: StateFlow<ProfilePresentation> get() = profileController.profilePresentation

    fun moveProfile(profileName: String?, delta: Int) = profileController.moveProfile(profileName, delta)

    fun setProfileHidden(profileName: String?, hidden: Boolean) =
        profileController.setProfileHidden(profileName, hidden)

    fun setProfileColor(profileName: String, colorHex: String?) =
        profileController.setProfileColor(profileName, colorHex)

    fun resetProfilePresentation() = profileController.resetProfilePresentation()

    /**
     * True once the active connection's persisted profile selection has settled,
     * so cold-start profile-scoped reads (e.g. the session drawer + restored
     * session context) don't race the restore and load the server-default
     * profile. See [ProfileController.selectionSettled].
     */
    val profileSelectionSettled: StateFlow<Boolean> get() = profileController.selectionSettled

    val profileDisplayAlias: StateFlow<String?> get() = profileController.profileDisplayAlias

    fun setProfileDisplayAlias(alias: String?) = profileController.setProfileDisplayAlias(alias)

    /** The active profile's local agent-icon path (client-side, never sent to Hermes). */
    val profileIcon: StateFlow<String?> get() = profileController.profileIcon
    val localProfileIcon: StateFlow<String?> get() = profileController.localProfileIcon
    val serverProfileAvatar: StateFlow<String?> get() = profileController.serverProfileAvatar
    val useLocalProfileIconOverride: StateFlow<Boolean>
        get() = profileController.useLocalProfileIconOverride
    val sharedProfileAvatarState: StateFlow<ProfileController.SharedAvatarState>
        get() = profileController.sharedAvatarState
    val hermesPetState: StateFlow<ProfileController.HermesPetState>
        get() = profileController.hermesPetState

    /** A local icon path for a specific profile identity on the active connection. */
    fun profileIconFlow(profileName: String?) = profileController.profileIconFlow(profileName)
    fun profileIconFlow(connectionId: String, profileName: String) =
        profileController.profileIconFlow(connectionId, profileName)

    val hostProfileIconImportState: StateFlow<ProfileController.HostIconImportState>
        get() = profileController.hostIconImportState

    fun setProfileIcon(uri: Uri) = profileController.setProfileIcon(uri)

    fun setSharedProfileAvatar(uri: Uri) = profileController.setSharedProfileAvatar(uri)

    fun setUseLocalProfileIconOverride(enabled: Boolean) =
        profileController.setUseLocalProfileIconOverride(enabled)

    fun importProfileIconFromHost() = profileController.importProfileIconFromHost()

    fun clearProfileIcon() = profileController.clearProfileIcon()

    fun uploadLocalProfileIconToHermes() = profileController.uploadLocalProfileIconToHermes()

    fun clearSharedProfileAvatar() = profileController.clearSharedProfileAvatar()

    fun refreshHermesPet() = profileController.refreshHermesPet()

    fun loadHermesPetGallery() = profileController.loadHermesPetGallery()

    fun selectHermesPet(slug: String) = profileController.selectHermesPet(slug)

    fun loadHermesPetThumbnail(pet: GatewayPetGalleryItem) =
        profileController.loadHermesPetThumbnail(pet)

    fun disableHermesPet() = profileController.disableHermesPet()

    fun refreshGatewayProfiles() = profileController.refreshGatewayProfiles()

    fun selectProfile(profile: Profile?) = profileController.selectProfile(profile)

    fun isProfileSelectionAllowed(profileName: String?): Boolean =
        profileController.isProfileSelectionAllowed(profileName)

    // --- Profile lock (per-connection pin to one profile) ------------------
    //
    // When set, the profile pickers/switchers across the app collapse to a
    // single locked state; only the dedicated Settings control still lists
    // every profile (to change the lock target or unlock). `lockedProfileName`
    // is the raw stored token (the SERVER_DEFAULT_PROFILE_KEY sentinel means
    // "locked to Server default"); `isProfileLocked` is the convenience boolean.

    val lockedProfileName: StateFlow<String?> get() = profileController.lockedProfileName

    val isProfileLocked: StateFlow<Boolean> get() = profileController.isProfileLocked

    /** Lock the active connection to [profile] (null = Server default). */
    fun lockProfile(profile: Profile?) {
        profileController.lockProfile(profile)
    }

    /** Remove the active connection's profile lock. */
    fun unlockProfile() {
        viewModelScope.launch { profileController.unlockProfile() }
    }

    // --- Paired devices list (GET /sessions) -------------------------------
    //
    // Loaded on-demand from PairedDevicesScreen. Owned by [pairingController];
    // these getters preserve the public surface unchanged.

    val pairedDevices: StateFlow<List<PairedDeviceInfo>> get() = pairingController.pairedDevices
    val pairedDevicesLoading: StateFlow<Boolean> get() = pairingController.pairedDevicesLoading
    val pairedDevicesError: StateFlow<String?> get() = pairingController.pairedDevicesError

    // --- Tailscale detection (informational) -------------------------------
    //
    // Purely for UI labeling. Does NOT auto-change TTLs or flip insecure mode.
    // Exposed to SettingsScreen + SessionTtlPickerDialog.
    private val tailscaleDetector = TailscaleDetector(
        context = application,
        scope = viewModelScope,
        relayUrlProvider = { effectiveRelayUrlSnapshot() },
    )
    val isTailscaleDetected: StateFlow<Boolean> = tailscaleDetector.isTailscaleDetected

    /**
     * Single source of truth for the connection-security indicator (chat
     * status chip, connection header, route picker, detail sheet). Rolls up
     * the per-surface scheme of API / dashboard / relay against the active
     * route — overlay transports (Tailscale/WireGuard/proxy) count as
     * encrypted, not just TLS. Declared after [isTailscaleDetected] because it
     * reads it. See `data/ConnectionSecurity.kt`.
     */
    val connectionSecurity: StateFlow<ConnectionSecurity> = combine(
        effectiveApiServerUrl,
        effectiveDashboardUrl,
        effectiveRelayUrl,
        relayConfigured,
        combine(
            activeEndpoint,
            connectionManager.activeApiEndpoint,
            connectionManager.activeRelayEndpoint,
        ) { dashboardEndpoint, apiEndpoint, relayEndpoint ->
            arrayOf(dashboardEndpoint, apiEndpoint, relayEndpoint)
        },
    ) { api, dashboard, relay, relayCfg, endpoints ->
        arrayOf(api, dashboard, relay, relayCfg, endpoints)
    }.combine(
        combine(
            activeConnection,
            apiServerHealth,
            relayRowState,
            gatewayAvailability,
            isTailscaleDetected,
        ) { connection, apiHealth, relayRow, gateway, tailscale ->
            arrayOf(connection, apiHealth, relayRow, gateway, tailscale)
        }
    ) { values, runtime ->
        val connection = runtime[0] as Connection?
        val apiHealth = runtime[1] as HealthStatus
        val relayRow = runtime[2] as RelayRowState
        val gateway = runtime[3] as GatewayAvailability
        val tailscale = runtime[4] as Boolean
        computeConnectionSecurity(
            apiUrl = values[0] as String,
            dashboardUrl = values[1] as String,
            relayUrl = values[2] as String,
            relayConfigured = values[3] as Boolean,
            activeEndpoint = (values[4] as Array<*>)[0] as EndpointCandidate?,
            isTailscaleDetected = tailscale,
            dashboardInUse = gateway == GatewayAvailability.Ready ||
                gateway == GatewayAvailability.SignInRequired ||
                connection?.dashboardLastStatus?.reachable == true,
            apiInUse = apiHealth == HealthStatus.Reachable && gateway != GatewayAvailability.Ready,
            apiAvailable = apiHealth == HealthStatus.Reachable,
            relayInUse = relayRow.phase == RelayUiState.Connected,
            apiEndpoint = (values[4] as Array<*>)[1] as EndpointCandidate?,
            relayEndpoint = (values[4] as Array<*>)[2] as EndpointCandidate?,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionSecurity.UNKNOWN)

    // What's New tracking
    private val _showWhatsNew = MutableStateFlow(false)
    val showWhatsNew: StateFlow<Boolean> = _showWhatsNew.asStateFlow()

    // Last session ID persistence
    private val _lastSessionId = MutableStateFlow<String?>(null)
    val lastSessionId: StateFlow<String?> = _lastSessionId.asStateFlow()

    // Connectivity
    private val connectivityObserver = ConnectivityObserver(application)
    val networkStatus: StateFlow<ConnectivityObserver.Status> = connectivityObserver.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectivityObserver.Status.Available)

    private val connectionHealthInputs = combine(
        activeConnection,
        relayRowState,
        apiServerHealth,
        relayServerHealth,
        networkStatus,
    ) { activeConnection, relayRow, apiHealth, relayHealth, network ->
        ConnectionHealthInputs(activeConnection, relayRow, apiHealth, relayHealth, network)
    }

    private val connectionHealthStatus: StateFlow<ConnectionStatusSnapshot?> = combine(
        connectionHealthInputs,
        gatewayAvailability,
    ) { inputs, gateway ->
        buildGlobalConnectionStatus(
            handoff = null,
            activeConnection = inputs.connection,
            relayRow = inputs.relayRow,
            apiHealth = inputs.apiHealth,
            relayHealth = inputs.relayHealth,
            network = inputs.network,
            gatewayAvailability = gateway,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val globalConnectionStatus: StateFlow<ConnectionStatusSnapshot?> = combine(
        connectionHandoffStatus,
        connectionHealthStatus,
    ) { handoff, healthStatus ->
        handoff?.asConnectionStatusSnapshot() ?: healthStatus
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Splash readiness — true once initial DataStore load + onboarding check is done
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val localShowThinking = application.relayDataStore.data
        .map { it[KEY_SHOW_THINKING] ?: true }

    // Reasoning visibility follows the Hermes dashboard config when available.
    // The local DataStore value remains an offline/legacy fallback.
    val showThinking: StateFlow<Boolean> = combine(
        localShowThinking,
        _serverChatDisplaySettings,
    ) { local, server ->
        server?.showReasoning ?: local
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowThinking(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_SHOW_THINKING] = enabled
            }
        }
    }

    private val localToolDisplay = application.relayDataStore.data
        .map { normalizeToolDisplayMode(it[KEY_TOOL_DISPLAY]) ?: "detailed" }

    // Tool call display mode: "off", "compact", "detailed". Prefer the
    // server display.tool_progress config so Android matches desktop.
    val toolDisplay: StateFlow<String> = combine(
        localToolDisplay,
        _serverChatDisplaySettings,
    ) { local, server ->
        server?.toolDisplay ?: local
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "detailed")

    fun setToolDisplay(mode: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_TOOL_DISPLAY] = normalizeToolDisplayMode(mode) ?: "detailed"
            }
        }
    }

    private fun normalizeToolDisplayMode(mode: String?): String? =
        when (mode?.trim()?.lowercase()) {
            "off", "compact", "detailed" -> mode.trim().lowercase()
            else -> null
        }

    // App context prompt toggle
    val appContextEnabled: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_APP_CONTEXT] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAppContext(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_APP_CONTEXT] = enabled
            }
        }
    }

    // === PHASE3-status: granular sub-toggle flows + setters ===
    // Mirror the appContextEnabled pattern above. All four are gated by the
    // master toggle in PhoneStatusPromptBuilder.buildPromptBlock(), so when
    // master is off these values don't matter — we still expose them as
    // StateFlow so the ChatSettingsScreen preview card stays in sync.
    val appContextBridgeState: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_APP_CONTEXT_BRIDGE_STATE] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAppContextBridgeState(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_APP_CONTEXT_BRIDGE_STATE] = enabled
            }
        }
    }

    val appContextCurrentApp: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_APP_CONTEXT_CURRENT_APP] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAppContextCurrentApp(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_APP_CONTEXT_CURRENT_APP] = enabled
            }
        }
    }

    val appContextBattery: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_APP_CONTEXT_BATTERY] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAppContextBattery(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_APP_CONTEXT_BATTERY] = enabled
            }
        }
    }

    val appContextSafetyStatus: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_APP_CONTEXT_SAFETY_STATUS] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAppContextSafetyStatus(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_APP_CONTEXT_SAFETY_STATUS] = enabled
            }
        }
    }
    // === END PHASE3-status ===

    // Streaming endpoint preference. Four values:
    //   "auto"     — pick based on per-endpoint capability detection (default
    //                for new installs as of v0.3.0). Resolves to "sessions"
    //                when the server has /api/sessions/{id}/chat/stream
    //                (native upstream or legacy fork), then "completions" when
    //                /v1/chat/completions is available.
    //   "sessions" — force /api/sessions/{id}/chat/stream
    //   "completions" — force /v1/chat/completions with stream=true
    //   "runs"     — force /v1/runs for servers known to stream that route
    //
    // Existing users keep whatever they previously chose. Only fresh installs
    // (no value persisted yet) get the new "auto" default.
    val streamingEndpoint: StateFlow<String> = application.relayDataStore.data
        .map { it[KEY_STREAMING_ENDPOINT] ?: "auto" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    fun setStreamingEndpoint(endpoint: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_STREAMING_ENDPOINT] = endpoint
            }
        }
    }

    // --- Opt-in "keep gateway connected in background" (sideload) ---

    /**
     * Off by default. When on, the gateway socket stays open in the background
     * (the process is held up by [GatewayKeepAliveService]) until the app is
     * killed, so replies stay instant instead of paying a cold rejoin.
     */
    val gatewayKeepAlive: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_GATEWAY_KEEP_ALIVE] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setGatewayKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_GATEWAY_KEEP_ALIVE] = enabled
            }
        }
    }

    init {
        authManager.setActiveEndpointProvider { connectionManager.activeRelayEndpoint.value }
        // Materialize the independent central and floating preferences. Legacy
        // users retain the prior visual in both roles until they choose otherwise.
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                if (preferences[KEY_FLOATING_PET] == null) {
                    preferences[KEY_FLOATING_PET] = preferences[KEY_AGENT_AVATAR]
                        ?.takeUnless { it == SphereAvatar.id }
                        ?: NO_FLOATING_PET
                }
                if (preferences[KEY_BACKGROUND_AVATAR] == null) {
                    preferences[KEY_BACKGROUND_AVATAR] = migratedBackgroundAvatar(
                        storedBackgroundAvatar = null,
                        legacyAgentAvatar = preferences[KEY_AGENT_AVATAR],
                    )
                }
                // Before presence and motion were split, animation_enabled also
                // controlled whether the Sphere was shown. Preserve that choice
                // on upgrade; users can then re-enable a static Sphere separately.
                if (preferences[KEY_BACKGROUND_VISUALIZATION_ENABLED] == null) {
                    preferences[KEY_BACKGROUND_VISUALIZATION_ENABLED] =
                        preferences[KEY_ANIMATION_ENABLED] ?: true
                }
            }
        }

        // Drive the keep-alive from either the user's always-on preference or
        // work the user already started. Active-turn leases are session scoped,
        // so sibling turns release independently. Both flavors — the
        // GatewayKeepAliveService is declared in the main manifest (Play permits
        // this Home-Assistant-class persistent-connection use case). Mirrors
        // BridgeViewModel's masterToggle → BridgeForegroundService driver.
        viewModelScope.launch {
            combine(gatewayKeepAlive, ActiveTurnKeepAliveRegistry.snapshot) { persistent, turns ->
                persistent to turns
            }.distinctUntilChanged().collect { (persistent, turns) ->
                upstreamTransport.applyGatewayKeepAlive(persistent || turns.required)
                val ctx = getApplication<Application>()
                runCatching { GatewayKeepAliveService.update(ctx, persistent, turns) }
            }
        }
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
        upstreamTransport.resolveStreamingEndpoint(preference)

    /**
     * Capability-resolved SSE endpoint, ignoring the gateway tier — wired to
     * [ChatViewModel.sseFallbackEndpoint] for per-turn gateway fallbacks.
     */
    fun resolveSseStreamingEndpoint(): String = upstreamTransport.resolveSseStreamingEndpoint()

    // Parse tool annotations from text markers toggle
    val parseToolAnnotations: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_PARSE_TOOL_ANNOTATIONS] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setParseToolAnnotations(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_PARSE_TOOL_ANNOTATIONS] = enabled
            }
        }
    }

    // Show server-injected role:system steering markers ("[System: …]" model /
    // personality-change notes) in the transcript. Default off (TUI/desktop
    // parity); on for debugging. Synced to ChatHandler.showSystemMarkers.
    val showSystemMessages: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_SHOW_SYSTEM_MESSAGES] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setShowSystemMessages(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_SHOW_SYSTEM_MESSAGES] = enabled
            }
        }
    }

    // Animation settings
    val animationEnabled: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_ANIMATION_ENABLED] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val animationBehindChat: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_ANIMATION_BEHIND_CHAT] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Controls Sphere/background visibility independently of motion. */
    val backgroundVisualizationEnabled: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_BACKGROUND_VISUALIZATION_ENABLED] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val imageGenerationStyle: StateFlow<String> = application.relayDataStore.data
        .map { it[KEY_IMAGE_GENERATION_STYLE] ?: "rotate" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "rotate")

    fun setAnimationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_ANIMATION_ENABLED] = enabled
            }
        }
    }

    /**
     * Show recent-prompt recall chips above the composer. OFF by default —
     * it's an opt-in convenience, not something to surface unprompted.
     */
    val chatRecentPromptsEnabled: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_CHAT_RECENT_PROMPTS] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setChatRecentPromptsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_CHAT_RECENT_PROMPTS] = enabled
            }
        }
    }

    fun setAnimationBehindChat(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_ANIMATION_BEHIND_CHAT] = enabled
            }
        }
    }

    fun setBackgroundVisualizationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_BACKGROUND_VISUALIZATION_ENABLED] = enabled
            }
        }
    }

    fun setBackgroundAvatar(avatarId: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_BACKGROUND_AVATAR] = avatarId.ifBlank { SphereAvatar.id }
                preferences[KEY_BACKGROUND_VISUALIZATION_ENABLED] = true
            }
        }
    }

    fun setImageGenerationStyle(value: String) {
        val normalized = value.takeIf {
            it in setOf("rotate", "grid", "sphere", "nodes")
        } ?: "rotate"
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_IMAGE_GENERATION_STYLE] = normalized
            }
        }
    }

    // Smooth auto-scroll during chat streaming.
    // When enabled, the chat list smoothly follows new tokens, tool cards, and
    // reasoning deltas as they stream in — but only while the user is at the
    // bottom of the conversation. Scrolling up to read history disables the
    // auto-follow until the user returns to the bottom (or taps the FAB).
    val smoothAutoScroll: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_SMOOTH_AUTO_SCROLL] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setSmoothAutoScroll(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_SMOOTH_AUTO_SCROLL] = enabled
            }
        }
    }

    // In-bubble streaming "thinking" indicator style: "dots" (classic three
    // fading bullets) or "matrix" (the DotMatrixIndicator grid). Local-only
    // display pref; defaults to "matrix".
    val thinkingIndicatorStyle: StateFlow<String> = application.relayDataStore.data
        .map { it[KEY_THINKING_INDICATOR_STYLE] ?: "matrix" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "matrix")

    fun setThinkingIndicatorStyle(value: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_THINKING_INDICATOR_STYLE] = if (value == "matrix") "matrix" else "dots"
            }
        }
    }

    // Which authored motion the "matrix" thinking indicator plays: "wave"
    // (procedural sweep), "pulse", "bounce", or "sparkle". Local-only display
    // pref; unknown values resolve to wave at the UI layer.
    val thinkingMatrixPattern: StateFlow<String> = application.relayDataStore.data
        .map { it[KEY_THINKING_MATRIX_PATTERN] ?: "wave" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "wave")

    fun setThinkingMatrixPattern(value: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_THINKING_MATRIX_PATTERN] = value
            }
        }
    }

    // Which color the "matrix" thinking indicator paints with: "auto" (follow
    // the bubble text) or a brand accent ("relay"/"cyan"/"green"/"amber"/
    // "purple"/"pink"). Local-only; unknown values resolve to auto at the UI.
    val thinkingMatrixColor: StateFlow<String> = application.relayDataStore.data
        .map { it[KEY_THINKING_MATRIX_COLOR] ?: "auto" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    fun setThinkingMatrixColor(value: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_THINKING_MATRIX_COLOR] = value
            }
        }
    }

    // Close the session drawer after a successful send. Default ON because
    // sending should return focus to the live conversation; users who use the
    // drawer as a pinned session navigator can keep it open.
    val closeDrawerOnSend: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_CLOSE_DRAWER_ON_SEND] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setCloseDrawerOnSend(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_CLOSE_DRAWER_ON_SEND] = enabled
            }
        }
    }

    // Keep the text composer focused after send. Default ON matches mobile
    // chat convention: quick follow-up messages should not require retapping
    // the input. Turning it off drops the keyboard after a successful send.
    val keepComposerFocusedOnSend: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_KEEP_COMPOSER_FOCUSED_ON_SEND] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setKeepComposerFocusedOnSend(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_KEEP_COMPOSER_FOCUSED_ON_SEND] = enabled
            }
        }
    }

    val physicalKeyboardEnterBehavior: StateFlow<PhysicalKeyboardEnterBehavior> =
        chatInputPreferencesRepository.physicalKeyboardEnterBehavior
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                PhysicalKeyboardEnterBehavior.SendMessage,
            )

    fun setPhysicalKeyboardEnterBehavior(behavior: PhysicalKeyboardEnterBehavior) {
        viewModelScope.launch {
            chatInputPreferencesRepository.setPhysicalKeyboardEnterBehavior(behavior)
        }
    }

    val convertLargePastesToAttachments: StateFlow<Boolean> =
        chatInputPreferencesRepository.convertLargePastesToAttachments
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setConvertLargePastesToAttachments(enabled: Boolean) {
        viewModelScope.launch {
            chatInputPreferencesRepository.setConvertLargePastesToAttachments(enabled)
        }
    }

    val showGitWorkspaceInChat: StateFlow<Boolean> =
        chatInputPreferencesRepository.showGitWorkspaceInChat
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowGitWorkspaceInChat(enabled: Boolean) {
        viewModelScope.launch {
            chatInputPreferencesRepository.setShowGitWorkspaceInChat(enabled)
        }
    }

    // Turn-complete notification (default ON). RelayApp mirrors this into
    // ChatViewModel.notifyOnTurnComplete; ChatSettingsScreen owns the toggle
    // + the POST_NOTIFICATIONS runtime request on first enable.
    val notifyTurnComplete: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_NOTIFY_TURN_COMPLETE] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setNotifyTurnComplete(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_NOTIFY_TURN_COMPLETE] = enabled
            }
        }
    }

    // One-shot voice hint on the input bar. Initial stateIn value is TRUE
    // (treated as already-seen) so returning users never get a flash of the
    // hint while DataStore hydrates; fresh installs flip to false once the
    // (absent) preference loads and the hint shows exactly once.
    val voiceHintSeen: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_VOICE_HINT_SEEN] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setVoiceHintSeen(seen: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_VOICE_HINT_SEEN] = seen
            }
        }
    }

    // Max attachment size in MB (default 10)
    val maxAttachmentMb: StateFlow<Int> = application.relayDataStore.data
        .map { it[KEY_MAX_ATTACHMENT_MB] ?: 10 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 10)

    fun setMaxAttachmentMb(mb: Int) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_MAX_ATTACHMENT_MB] = mb
            }
        }
    }

    // Max message character length (default 4096)
    val maxMessageLength: StateFlow<Int> = application.relayDataStore.data
        .map { it[KEY_MAX_MESSAGE_LENGTH] ?: 4096 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 4096)

    fun setMaxMessageLength(length: Int) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_MAX_MESSAGE_LENGTH] = length
            }
        }
    }

    // === PHASE3-accessibility: bridge channel wiring ===
    // ScreenCapture needs a MediaProjection grant from the Bridge UI
    // (MediaProjectionHolder) — it's nullable here so the handler can be
    // constructed before the user has consented to screen capture.
    // [BridgeCommandHandler] will surface a 503 error for /screenshot
    // requests until [MediaProjectionHolder.projection] is non-null.
    private val screenCapture = if (BuildFlavor.isSideload) ScreenCapture(
        context = application,
        httpClient = relayOkHttp,
        relayUrlProvider = { effectiveRelayUrlSnapshot() },
        sessionTokenProvider = {
            (authManager.authState.value as? AuthState.Paired)?.token
        },
        mediaProjectionProvider = {
            com.hermesandroid.relay.accessibility.MediaProjectionHolder.projection
        },
    ) else null

    // === PHASE3-safety-rails: safety manager + overlay wiring ===
    // Process-wide singletons — install() is idempotent and the overlay
    // host wires itself into ConfirmationOverlayHost.instance so the
    // safety manager can reach it without a hard ref.
    private val bridgeSafetyManager =
        if (BuildFlavor.isSideload) com.hermesandroid.relay.bridge.BridgeSafetyManager.install(
            context = application,
            scope = viewModelScope,
            activeConnectionId = connectionStore.activeConnectionId,
        ).also {
            com.hermesandroid.relay.bridge.BridgeStatusOverlay.install(application)
        } else null

    /** Exposed for BridgeScreen → safety summary card. */
    val bridgeSafety: com.hermesandroid.relay.bridge.BridgeSafetyManager? get() = bridgeSafetyManager
    // === END PHASE3-safety-rails ===

    // v0.4.1 polish: bridge activity log sink. BridgeCommandHandler posts
    // one BridgeActivityEntry per dispatched command (except high-frequency
    // polls) which lands here and gets persisted to DataStore via
    // BridgePreferencesRepository. BridgeViewModel reads the same DataStore
    // flow to render the Activity Log card on the Bridge tab.
    private val bridgeActivityPrefsRepo =
        com.hermesandroid.relay.data.BridgePreferencesRepository(application)

    // Public so RelayApp can hand the local-dispatch entry point to
    // VoiceViewModel. The voice intent handler calls handleLocalCommand()
    // for in-process action dispatch (see BridgeCommandHandler KDoc).
    val bridgeCommandHandler: BridgeCommandHandler = BridgeCommandHandler(
        multiplexer = multiplexer,
        scope = viewModelScope,
        screenCapture = screenCapture,
        relayHttpClient = relayHttpClient,
        mediaCacheWriter = mediaCacheWriter,
        // === PHASE3-safety-rails: safety enforcement ===
        safetyManager = bridgeSafetyManager,
        // === END PHASE3-safety-rails ===
        // === v0.4.1 polish: activity-log sink ===
        onActivity = { entry ->
            viewModelScope.launch {
                runCatching { bridgeActivityPrefsRepo.appendEntry(entry) }
                    .onFailure {
                        android.util.Log.w(
                            "ConnectionViewModel",
                            "activity log append failed: ${it.message}",
                        )
                    }
            }
        },
        // === END v0.4.1 polish ===
    )

    val bridgeStatusReporter = BridgeStatusReporter(
        context = application,
        multiplexer = multiplexer,
        scope = viewModelScope,
    )
    // === END PHASE3-accessibility (plus safety-rails wiring above) ===

    // === Proactive (agent → phone) messages ===
    // Handles inbound `phone.message` envelopes. Receiving is gated by
    // [proactiveEnabled]: the relay only pushes when the app has sent
    // `proactive.subscribe`, which we only do when the toggle is on. Off by
    // default.

    /** Persisted "Hermes" inbox of agent-initiated messages (Phase 2a). */
    val proactiveInbox = ProactiveInboxRepository(application)

    val inboxMessages: StateFlow<List<ProactiveInboxEntry>> =
        proactiveInbox.entries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // The handler centralizes surfacing (notification / inbox / session). The
    // inbox sink persists messages here; the session sink lands in Phase 2b.
    val proactiveMessageHandler = ProactiveMessageHandler(
        context = application,
        toInbox = { msg ->
            viewModelScope.launch {
                proactiveInbox.add(
                    ProactiveInboxEntry(
                        id = msg.messageId ?: java.util.UUID.randomUUID().toString(),
                        title = msg.title ?: "Hermes",
                        text = msg.text,
                        receivedAt = msg.sentAt ?: System.currentTimeMillis(),
                        chatId = msg.chatId,
                        connectionId = connectionStore.activeConnectionId.value,
                        arrivedWhileAway = msg.arrivedWhileAway,
                    ),
                )
            }
        },
    )

    /** "Let Hermes message me" — off by default. */
    val proactiveEnabled: StateFlow<Boolean> = application.proactiveEnabledFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Drawer source visibility — which gateway sources are hidden (default:
    // the noisy automation lanes cron + webhook). Edited from the drawer source
    // filter + Chat settings; both write the same persisted set.
    private val sessionSourcePrefs = SessionSourcePrefs(application)
    val hiddenSources: StateFlow<Set<String>> = sessionSourcePrefs.hiddenSources
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_HIDDEN_SOURCES)

    fun setSourceHidden(source: String, hidden: Boolean) {
        viewModelScope.launch { sessionSourcePrefs.setHidden(source, hidden) }
    }

    // Persisted user-chosen Thread names (sessionId → name) — applied to the
    // drawer so a named Thread keeps its name across restarts and beats the
    // gateway's async auto-title. Wired to ChatViewModel via RelayApp.
    private val threadNameStore = ThreadNameStore(application)
    val threadNames: StateFlow<Map<String, String>> = threadNameStore.names
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun saveThreadName(sessionId: String, name: String) {
        viewModelScope.launch { threadNameStore.setName(sessionId, name) }
    }

    // Phone Thread session_id → chat_id, fetched from the relay's /phone/threads
    // (the gateway store has chat_id but /api/sessions doesn't). Seeds the chat
    // composer's reply routing so a Thread the app didn't create — or any Thread
    // after restart — routes to the right conversation. Fail-soft: empty on an
    // older relay / fetch error, and the client's learned map still applies.
    private val _phoneThreadChatIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val phoneThreadChatIds: StateFlow<Map<String, String>> = _phoneThreadChatIds.asStateFlow()

    fun refreshPhoneThreadChatIds() {
        viewModelScope.launch {
            relayHttpClient.fetchPhoneThreads().onSuccess { threads ->
                val map = threads
                    .filter { it.sessionId.isNotBlank() && it.chatId.isNotBlank() }
                    .associate { it.sessionId to it.chatId }
                if (map.isNotEmpty()) _phoneThreadChatIds.value = map
            }
        }
    }

    // Relay plugin update status from /relay/update-check (the relay compares its
    // installed version to the latest plugin-v* release, cached an hour). Drives
    // the Settings version readout + a soft, dismissible "relay is behind" nudge.
    // Fail-soft: stays null on an older relay (no route) or a fetch error.
    private val _relayUpdateInfo = MutableStateFlow<RelayHttpClient.RelayUpdateInfo?>(null)
    val relayUpdateInfo: StateFlow<RelayHttpClient.RelayUpdateInfo?> = _relayUpdateInfo.asStateFlow()

    private val _relayInfo = MutableStateFlow<RelayHttpClient.RelayInfo?>(null)
    val relayInfo: StateFlow<RelayHttpClient.RelayInfo?> = _relayInfo.asStateFlow()
    private val _toolsetInventory = MutableStateFlow<List<ToolsetInfo>?>(null)
    val toolsetInventory: StateFlow<List<ToolsetInfo>?> = _toolsetInventory.asStateFlow()
    private val _diagnosticsCheckedAt = MutableStateFlow<Long?>(null)
    val diagnosticsCheckedAt: StateFlow<Long?> = _diagnosticsCheckedAt.asStateFlow()
    private val _diagnosticsRefreshing = MutableStateFlow(false)
    val diagnosticsRefreshing: StateFlow<Boolean> = _diagnosticsRefreshing.asStateFlow()

    fun refreshRelayUpdateInfo() {
        viewModelScope.launch {
            _relayUpdateInfo.value = null
            relayHttpClient.fetchUpdateCheck()
                .onSuccess { info -> _relayUpdateInfo.value = info }
                .onFailure { _relayUpdateInfo.value = null }
        }
    }

    fun refreshDiagnostics() {
        _diagnosticsRefreshing.value = true
        probeNow()
        viewModelScope.launch {
            val info = relayHttpClient.fetchRelayInfo().getOrNull()
            _relayInfo.value = info
            // Toolsets are profile-scoped upstream. Use the same routed client
            // as Chat so Diagnostics cannot pair a selected profile's Relay
            // state with the base/default profile's tool inventory.
            _toolsetInventory.value = _chatApiClient.value?.getToolsets()?.getOrNull()
            relayHttpClient.fetchUpdateCheck().onSuccess { _relayUpdateInfo.value = it }
            _diagnosticsCheckedAt.value = System.currentTimeMillis()
            _diagnosticsRefreshing.value = false
        }
    }

    private fun sendProactiveSubscribe() {
        multiplexer.send(Envelope(channel = "proactive", type = "proactive.subscribe"))
    }

    private fun sendProactiveUnsubscribe() {
        multiplexer.send(Envelope(channel = "proactive", type = "proactive.unsubscribe"))
    }

    /**
     * Flip the "Let Hermes message me" preference. The actual
     * subscribe/unsubscribe over the WSS is driven reactively by the
     * [proactiveEnabled] collector in init, so this only persists the flag.
     */
    fun setProactiveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().setProactiveEnabled(enabled)
        }
    }

    /** Clear the Hermes inbox of agent-initiated messages. */
    fun clearProactiveInbox() {
        viewModelScope.launch { proactiveInbox.clear() }
    }

    /**
     * Send a reply to a proactive message back to the agent (Phase 2c). The
     * relay buffers it and the gateway adapter long-polls it, turning it into
     * an inbound platform message that continues the originating conversation.
     *
     * Best-effort over the live relay WS (dropped if disconnected — the same
     * semantics as [sendProactiveSubscribe]). Used by the Hermes inbox reply
     * box; the notification inline-reply path goes through
     * [com.hermesandroid.relay.notifications.ProactiveReplyReceiver] instead.
     *
     * @param chatId the conversation to continue (from the original message).
     * @param replyTo the answered message's id (anchors the reply).
     */
    fun sendProactiveReply(
        text: String,
        chatId: String?,
        replyTo: String?,
        messageId: String? = null,
    ) {
        val body = text.trim()
        if (body.isEmpty()) return
        multiplexer.send(
            Envelope(
                channel = "proactive",
                type = "proactive.reply",
                payload = buildJsonObject {
                    put("text", body)
                    if (!chatId.isNullOrBlank()) put("chat_id", chatId)
                    if (!replyTo.isNullOrBlank()) put("reply_to", replyTo)
                    // The app-minted id the relay echoes back in
                    // `proactive.reply.ack`, so a Thread reply bubble can settle
                    // SENDING → DELIVERED. Omitted by the inbox/notification
                    // paths (they don't track per-bubble status).
                    if (!messageId.isNullOrBlank()) put("message_id", messageId)
                    put("ts", System.currentTimeMillis())
                },
            ),
        )
    }
    // === END Proactive ===

    // --- Connection switch orchestration ----------------------------------
    //
    // Multi-connection v0.5.0: the coordinator owns the heavy swap sequence
    // (cancel stream → stop voice → disconnect WSS → rebuild AuthManager +
    // api client → reconnect WSS if paired). Extracted so the swap is
    // unit-testable without having to mock AndroidViewModel / DataStore.
    private fun createAuthManagerForConnectionId(connectionId: String): AuthManager {
        val connection = connectionStore.connections.value.firstOrNull { it.id == connectionId }
        return AuthManager(
            context = getApplication<Application>(),
            multiplexer = multiplexer,
            scope = viewModelScope,
            connectionId = connectionId,
            tokenStoreKey = connection?.tokenStoreKey
                ?: Connection.buildTokenStoreKey(connectionId),
        )
    }

    private fun installAuthManager(am: AuthManager) {
        am.setActiveEndpointProvider { connectionManager.activeRelayEndpoint.value }
        am.setSupervisedMetadataReconnectFallback {
            connectionManager.reconnectForAuthenticatedMetadataUpdate()
        }
        authManager = am
        // Push into the flow so the flatMapLatest chains on authState /
        // pairingCode / currentPairedSession repoint to the new manager.
        // Without this, existing Compose collectors stay bound to the
        // previous AuthManager's backing flows forever.
        _authManagerFlow.value = am
    }

    private suspend fun restorePersistedActiveConnectionContext(connection: Connection) {
        if (connectionStore.activeConnectionId.value != connection.id) return

        val restoredAuth = createAuthManagerForConnectionId(connection.id)
        installAuthManager(restoredAuth)

        val restoredRelayUrl = if (
            RelayUrlDeriver.isAutoManagedRelayUrl(connection.relayUrl, connection.apiServerUrl)
        ) {
            RelayUrlDeriver.deriveFromApiUrl(connection.apiServerUrl) ?: connection.relayUrl
        } else {
            connection.relayUrl
        }

        _apiServerUrl.value = connection.apiServerUrl
        _relayUrl.value = restoredRelayUrl
        connectionManager.setManualRoleOverride(connection.preferredRouteRole)
        getApplication<Application>().relayDataStore.edit { prefs ->
            prefs[KEY_API_SERVER_URL] = connection.apiServerUrl
            prefs[KEY_RELAY_URL] = restoredRelayUrl
        }
        connectionManager.refreshActiveEndpoint()
        rebuildApiClient()

        withTimeoutOrNull(ConnectionSwitchCoordinator.AUTH_HYDRATE_TIMEOUT_MS) {
            restoredAuth.authState.first { it is AuthState.Paired || it is AuthState.Failed }
        }

        if (
            connectionStore.activeConnectionId.value == connection.id &&
            restoredAuth.hasPairContext &&
            restoredRelayUrl.isNotBlank()
        ) {
            connectionManager.connect(restoredRelayUrl)
        }
    }

    private val connectionSwitchCoordinator = ConnectionSwitchCoordinator(
        connectionStore = connectionStore,
        connectionManager = connectionManager,
        scope = viewModelScope,
        authManagerFactory = { cid -> createAuthManagerForConnectionId(cid) },
        installAuthManager = { am -> installAuthManager(am) },
        setApiServerUrl = { url -> _apiServerUrl.value = url },
        setRelayUrl = { url -> _relayUrl.value = url },
        persistUrls = { apiUrl, relayUrl ->
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_API_SERVER_URL] = apiUrl
                prefs[KEY_RELAY_URL] = relayUrl
            }
        },
        rebuildApiClient = { rebuildApiClient() },
    )

    /**
     * Multi-connection: emits the id of the newly-active connection right
     * after the connection teardown half of the switch completes but before
     * the rebuilt API/relay clients start serving requests. [ChatViewModel]
     * subscribes to clear its per-connection local state (messages, session
     * id, queued sends).
     */
    val connectionSwitchEvents: SharedFlow<String> = connectionSwitchCoordinator.connectionSwitchEvents

    /**
     * Multi-connection: start the full connection swap sequence. See
     * [ConnectionSwitchCoordinator] for the ordered steps and why the order
     * matters. No-ops when [connectionId] equals the current
     * [activeConnectionId]. Fires and forgets — UI watches
     * [activeConnection] for the completed state.
     */
    fun switchConnection(connectionId: String): Job =
        connectionSwitchCoordinator.switchConnection(connectionId)

    /** `null` keeps the recommended restore-last-used startup behavior. */
    fun setStartupConnection(connectionId: String?) {
        viewModelScope.launch {
            connectionStore.setStartupConnection(connectionId)
        }
    }

    /**
     * Multi-connection: RelayApp calls this at composition time with a
     * callback that tears down [ChatViewModel]'s in-flight stream. The
     * coordinator invokes it first in the switch sequence so the stream
     * doesn't keep scribbling into `_messages` after the handler's API
     * client reference gets rebuilt under it.
     */
    fun registerStreamCancelCallback(callback: () -> Unit) {
        connectionSwitchCoordinator.registerStreamCancelCallback(callback)
    }

    fun recordVoiceHandoff(event: VoiceHandoffEvent) {
        val detail = when {
            !event.previousRoute.isNullOrBlank() && !event.nextRoute.isNullOrBlank() ->
                "${event.previousRoute} -> ${event.nextRoute}"
            !event.route.isNullOrBlank() && !event.detail.isNullOrBlank() ->
                "${event.route} / ${event.detail}"
            !event.route.isNullOrBlank() -> event.route
            else -> event.detail?.take(96)
        }
        recordConnectionHandoff(
            title = event.label,
            route = event.nextRoute ?: event.route,
            detail = detail,
            active = event.active,
            success = event.success,
        )
    }

    private fun recordConnectionHandoff(
        title: String,
        route: String?,
        detail: String?,
        active: Boolean,
        success: Boolean,
    ) {
        val cleanTitle = title.trim().takeIf { it.isNotBlank() } ?: "Connection changed"
        val cleanRoute = route?.trim()?.takeIf { it.isNotBlank() }
        val cleanDetail = detail?.trim()?.takeIf { it.isNotBlank() }?.take(120)
        // Trace which strip/banner actually surfaced (and why). Best-practice
        // permanent logging: handoffs are infrequent, and this is the single
        // line that answers "what made that status appear?" during triage.
        android.util.Log.i(
            TAG,
            "handoff: '$cleanTitle' active=$active success=$success " +
                "route=${cleanRoute ?: "-"} detail=${cleanDetail ?: "-"}",
        )
        val entry = ConnectionHandoffTraceEntry(
            label = cleanTitle,
            detail = cleanDetail,
        )
        val now = System.currentTimeMillis()
        connectionHandoffClearJob?.cancel()
        val previousEntries = _connectionHandoffStatus.value?.entries.orEmpty()
        val nextEntries = if (previousEntries.lastOrNull() == entry) {
            previousEntries
        } else {
            (previousEntries + entry).takeLast(4)
        }
        _connectionHandoffStatus.value = ConnectionHandoffStatus(
            title = cleanTitle,
            route = cleanRoute,
            active = active,
            success = success,
            entries = nextEntries,
            updatedAtMs = now,
        )
        connectionHandoffClearJob = viewModelScope.launch {
            delay(
                when {
                    // Live, in-progress handoff: keep the spinner up as a backstop
                    // until it resolves to success/error (which then clears fast).
                    active -> 30_000L
                    // Resolved states auto-dismiss within 5s — anything longer
                    // reads as a stuck overlay.
                    success -> 5_000L
                    else -> 5_000L
                }
            )
            if (_connectionHandoffStatus.value?.updatedAtMs == now) {
                _connectionHandoffStatus.value = null
            }
        }
    }

    private fun buildGlobalConnectionStatus(
        handoff: ConnectionHandoffStatus?,
        activeConnection: Connection?,
        relayRow: RelayRowState,
        apiHealth: HealthStatus,
        relayHealth: HealthStatus,
        network: ConnectivityObserver.Status,
        gatewayAvailability: GatewayAvailability,
    ): ConnectionStatusSnapshot? {
        handoff?.let { return it.asConnectionStatusSnapshot() }

        if (!hasConfiguredHermesConnection(activeConnection)) {
            return ConnectionStatusSnapshot(
                title = ctx.getString(R.string.conn_status_no_hermes),
                actionLabel = ctx.getString(R.string.conn_label_connect),
                tone = ConnectionStatusTone.Warning,
                entries = listOf(
                    ConnectionHandoffTraceEntry(
                        label = ctx.getString(R.string.conn_label_setup),
                        detail = ctx.getString(R.string.conn_detail_add_connection),
                    ),
                ),
            )
        }

        if (network is ConnectivityObserver.Status.Lost ||
            network is ConnectivityObserver.Status.Unavailable
        ) {
            return ConnectionStatusSnapshot(
                title = ctx.getString(R.string.conn_status_no_internet),
                actionLabel = ctx.getString(R.string.conn_label_connections),
                tone = ConnectionStatusTone.Warning,
                entries = listOf(
                    ConnectionHandoffTraceEntry(
                        label = ctx.getString(R.string.conn_label_network),
                        detail = ctx.getString(R.string.conn_detail_waiting_network),
                    ),
                ),
            )
        }

        val route = displayEndpointRole(relayRow.activeEndpointRole)
        val probeEntries = buildGlobalConnectionProbeEntries(
            relayRow = relayRow,
            apiHealth = apiHealth,
            relayHealth = relayHealth,
            route = route,
        )

        val dashboardConfigured = activeConnection?.capabilities?.dashboardGatewayConfigured == true
        val apiConfigured = activeConnection?.capabilities?.apiServerConfigured == true

        if (
            dashboardConfigured &&
            gatewayAvailability == GatewayAvailability.SignInRequired &&
            (!apiConfigured || apiHealth != HealthStatus.Reachable)
        ) {
            return ConnectionStatusSnapshot(
                title = ctx.getString(R.string.cw_dashboard_sign_in_required),
                actionLabel = ctx.getString(R.string.conn_label_connections),
                tone = ConnectionStatusTone.Warning,
                entries = listOf(
                    ConnectionHandoffTraceEntry(
                        label = ctx.getString(R.string.cw_dashboard),
                        detail = ctx.getString(R.string.cw_sign_in_hint),
                    ),
                ),
            )
        }

        if (
            dashboardConfigured &&
            gatewayAvailability == GatewayAvailability.Unreachable &&
            (!apiConfigured || apiHealth != HealthStatus.Reachable)
        ) {
            return ConnectionStatusSnapshot(
                title = ctx.getString(R.string.cw_dashboard_not_reachable),
                actionLabel = ctx.getString(R.string.conn_label_connections),
                tone = ConnectionStatusTone.Warning,
                entries = listOf(
                    ConnectionHandoffTraceEntry(
                        label = ctx.getString(R.string.cw_dashboard),
                        detail = ctx.getString(R.string.conn_detail_chat_unavailable),
                    ),
                ),
            )
        }

        return when {
            apiConfigured &&
                apiHealth == HealthStatus.Unreachable &&
                gatewayAvailability != GatewayAvailability.Ready -> {
                // Diagnose, don't just report: for a single-route connection
                // the most likely cause is "phone left the server's network
                // and there's no remote route to roam to" — say so and point
                // at the fix. Multi-route connections already roam; tell the
                // user every route was tried instead.
                val routeCount = activeConnection?.routeCandidates.orEmpty().size
                val routesEntry = if (routeCount <= 1) {
                    ConnectionHandoffTraceEntry(
                        label = ctx.getString(R.string.conn_label_routes),
                        detail = if (tailscaleDetector.isTailscaleDetected.value) {
                            "Phone is on Tailscale — add your server's Tailscale " +
                                "URL under Connections → Routes"
                        } else {
                            "Away from the server's network? Add a Tailscale or " +
                                "public route under Connections → Routes"
                        },
                    )
                } else {
                    ConnectionHandoffTraceEntry(
                        label = ctx.getString(R.string.conn_label_routes),
                        detail = "None of the $routeCount configured routes " +
                            "responded — fallbacks are retried automatically",
                    )
                }
                ConnectionStatusSnapshot(
                    title = ctx.getString(R.string.conn_status_api_unreachable),
                    route = route,
                    actionLabel = ctx.getString(R.string.conn_label_connections),
                    tone = ConnectionStatusTone.Warning,
                    entries = listOf(
                        ConnectionHandoffTraceEntry(
                            label = ctx.getString(R.string.conn_label_api),
                            detail = ctx.getString(R.string.conn_detail_chat_unavailable),
                        ),
                        routesEntry,
                    ),
                )
            }

            relayRow.phase == RelayUiState.Connecting &&
                apiHealth != HealthStatus.Reachable -> ConnectionStatusSnapshot(
                title = ctx.getString(R.string.conn_status_connecting),
                route = route,
                actionLabel = ctx.getString(R.string.conn_label_connections),
                active = true,
                tone = ConnectionStatusTone.Info,
                entries = probeEntries,
            )

            apiHealth == HealthStatus.Probing ||
                (relayHealth == HealthStatus.Probing &&
                    apiHealth != HealthStatus.Reachable) ->
                ConnectionStatusSnapshot(
                    title = ctx.getString(R.string.conn_status_checking),
                    route = route,
                    actionLabel = ctx.getString(R.string.conn_label_connections),
                    active = true,
                    tone = ConnectionStatusTone.Info,
                    entries = probeEntries,
                )

            apiHealth != HealthStatus.Reachable &&
                (relayRow.phase == RelayUiState.Stale ||
                (relayHealth == HealthStatus.Unreachable &&
                    relayRow.phase != RelayUiState.Connected)) -> ConnectionStatusSnapshot(
                    title = ctx.getString(R.string.conn_status_relay_unreachable),
                    route = route,
                    actionLabel = ctx.getString(R.string.conn_label_connections),
                    tone = ConnectionStatusTone.Warning,
                    entries = listOfNotNull(
                        route?.let {
                            ConnectionHandoffTraceEntry(
                                label = ctx.getString(R.string.conn_label_route),
                                detail = "Last route: $it",
                            )
                        },
                        ConnectionHandoffTraceEntry(
                                                label = ctx.getString(R.string.conn_label_status),
                            detail = "Waiting for reconnect or a network change",
                        ),
                    ),
                )

            else -> null
        }
    }

    private fun buildGlobalConnectionProbeEntries(
        relayRow: RelayRowState,
        apiHealth: HealthStatus,
        relayHealth: HealthStatus,
        route: String?,
    ): List<ConnectionHandoffTraceEntry> = buildList {
        route?.let {
            add(
                ConnectionHandoffTraceEntry(
                    label = ctx.getString(R.string.conn_label_route),
                    detail = it,
                    state = ConnectionStepState.Done,
                )
            )
        }
        when (apiHealth) {
            HealthStatus.Probing -> add(
                ConnectionHandoffTraceEntry(
                    label = ctx.getString(R.string.conn_label_api),
                    detail = "Checking Hermes health",
                    state = ConnectionStepState.Active,
                )
            )
            HealthStatus.Unreachable -> add(
                ConnectionHandoffTraceEntry(
                    label = ctx.getString(R.string.conn_label_api),
                    detail = ctx.getString(R.string.conn_detail_health_check_failed),
                    state = ConnectionStepState.Failed,
                )
            )
            HealthStatus.Reachable -> add(
                ConnectionHandoffTraceEntry(
                    label = ctx.getString(R.string.conn_label_api),
                    detail = ctx.getString(R.string.conn_detail_ready),
                    state = ConnectionStepState.Done,
                )
            )
            HealthStatus.Unknown -> Unit
        }
        when (relayHealth) {
            HealthStatus.Probing -> add(
                ConnectionHandoffTraceEntry(
                    label = ctx.getString(R.string.conn_label_relay),
                    detail = ctx.getString(R.string.conn_detail_checking_relay),
                    state = ConnectionStepState.Active,
                )
            )
            HealthStatus.Unreachable -> add(
                ConnectionHandoffTraceEntry(
                    label = ctx.getString(R.string.conn_label_relay),
                    detail = ctx.getString(R.string.conn_detail_health_check_failed),
                    state = ConnectionStepState.Failed,
                )
            )
            HealthStatus.Reachable -> add(
                ConnectionHandoffTraceEntry(
                    label = ctx.getString(R.string.conn_label_relay),
                    detail = ctx.getString(R.string.conn_detail_ready),
                    state = ConnectionStepState.Done,
                )
            )
            HealthStatus.Unknown -> Unit
        }
        if (relayRow.phase == RelayUiState.Connecting) {
            add(
                ConnectionHandoffTraceEntry(
                    label = ctx.getString(R.string.conn_label_session),
                    detail = ctx.getString(R.string.conn_detail_opening_socket),
                    state = ConnectionStepState.Active,
                )
            )
        }
    }.takeLast(3)

    private fun displayEndpointRole(role: String?): String? {
        val cleaned = role?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when (cleaned.lowercase()) {
            "lan" -> "LAN"
            "tailscale" -> "Tailscale"
            "public" -> "Public"
            else -> cleaned
        }
    }

    /**
     * Multi-connection: RelayApp calls this at composition time with a
     * callback that stops any active voice turn. Kept as a registration
     * hook (rather than a direct VoiceViewModel field) so
     * [ConnectionViewModel] doesn't take on a hard voice dependency —
     * voice is an optional feature wired per build flavor.
     */
    fun registerVoiceStopCallback(callback: () -> Unit) {
        connectionSwitchCoordinator.registerVoiceStopCallback(callback)
    }

    // --- Connection CRUD helpers (Worker B2) ------------------------------
    //
    // These wrap [ConnectionStore] so callers (currently RelayApp's
    // ConnectionsSettings + Pair routes) don't have to reach directly into
    // the store for common mutations. Each method documents its scope and
    // v1 constraints.

    /**
     * Start an Add-connection draft with an id-scoped auth owner. The draft is
     * neither persisted nor made active, so Connections never exposes a fake
     * empty card and the outgoing connection cannot receive its credentials.
     * [commitConnectionDraft] persists/activates the finished snapshot once;
     * [discardPlaceholderConnection] securely drops an abandoned draft.
     */
    /**
     * Serializes concurrent `Add connection` flows. Without this, a fast
     * double-tap on the Connections FAB (two `connectionSwitchScope.launch`
     * blocks in [ui.RelayApp]) would create two placeholders and switch
     * to the second one, leaving the first as a blank orphan — which the
     * `init`-time orphan sweep would only pick up on the NEXT app launch.
     *
     * The mutex also enables the in-body "reuse existing placeholder"
     * short-circuit below — two racing callers share the same id instead
     * of each creating their own.
     */
    private val addConnectionMutex = Mutex()

    private data class PendingConnectionDraft(
        val id: String,
        val previousConnectionId: String?,
        var pairingPayload: com.hermesandroid.relay.ui.components.HermesPairingPayload? = null,
    )

    private var pendingConnectionDraft: PendingConnectionDraft? = null
    private val _connectionDraftId = MutableStateFlow<String?>(null)
    val connectionDraftId: StateFlow<String?> = _connectionDraftId.asStateFlow()

    /**
     * @param preAllocatedId when non-null, use this id for the transient draft
     *   instead of generating a fresh UUID. Lets the caller navigate to
     *   [ui.screens.PairScreen] synchronously with a known id and run the
     *   auth-owner setup in the background while the wizard opens.
     *
     *   When a connection with this id already exists (re-entry from a
     *   double-tap racing the first call), this call becomes a no-op
     *   switch and returns the same id — safe to invoke twice for the
     *   same pre-allocated id.
     *
     *   When null, falls back to the original placeholder-reuse scan
     *   (for any legacy caller that still wants the old behavior).
     */
    suspend fun beginAddConnection(preAllocatedId: String? = null): String = addConnectionMutex.withLock {
        // Fast path for the pre-allocated-id caller (RelayApp's
        // onAddConnection). If a connection with this id already exists
        // in the store, we're the second call of a double-tap — just
        // ensure the switch landed and return. Otherwise create the
        // placeholder under the caller-supplied id so navigation and
        // persistence converge on the same handle.
        if (preAllocatedId != null) {
            if (pendingConnectionDraft?.id == preAllocatedId) {
                return@withLock preAllocatedId
            }
            val existing = connectionStore.connections.value.firstOrNull { it.id == preAllocatedId }
            if (existing != null) {
                android.util.Log.i(
                    "ConnectionViewModel",
                    "beginAddConnection: pre-allocated id=$preAllocatedId already exists, ensuring switch",
                )
                if (connectionStore.activeConnectionId.value != existing.id) {
                    switchConnection(existing.id).join()
                }
                return@withLock existing.id
            }

            pendingConnectionDraft = PendingConnectionDraft(
                id = preAllocatedId,
                previousConnectionId = connectionStore.activeConnectionId.value,
            )
            _connectionDraftId.value = preAllocatedId
            installAuthManager(createAuthManagerForConnectionId(preAllocatedId))
            return@withLock preAllocatedId
        }

        // Legacy path: reuse an existing unpaired placeholder from a
        // prior aborted attempt if one is lying around. Cheaper than
        // creating a second placeholder + expecting the init-time
        // orphan sweep to clean it up later, and correctly idempotent
        // under rapid double-tap: both callers converge on the same
        // id, the second `switchConnection` is a no-op (coordinator
        // short-circuits when id == activeConnectionId), and we
        // return the same string both times.
        val existing = reusablePlaceholderForAdd(
            preAllocatedId = null,
            connections = connectionStore.connections.value,
        )
        if (existing != null) {
            android.util.Log.i(
                "ConnectionViewModel",
                "beginAddConnection: reusing existing placeholder id=${existing.id}",
            )
            if (connectionStore.activeConnectionId.value != existing.id) {
                switchConnection(existing.id).join()
            }
            return@withLock existing.id
        }

        val id = java.util.UUID.randomUUID().toString()
        val placeholder = Connection(
            id = id,
            label = PLACEHOLDER_LABEL,
            apiServerUrl = "",
            relayUrl = "",
            tokenStoreKey = Connection.buildTokenStoreKey(id),
            pairedAt = null,
            lastActiveSessionId = null,
            transportHint = null,
            expiresAt = null,
        )
        connectionStore.addConnection(placeholder)
        // Join so the fresh authManager is bound before the caller
        // navigates into the Pair wizard — otherwise applyPairingPayload
        // could fire against the outgoing auth store if the user scans
        // faster than the switch coroutine completes.
        switchConnection(id).join()
        id
    }

    /**
     * Ensure first-run/onboarding setup has a real active [Connection] row
     * before credentials are written. Settings → Add connection already calls
     * [beginAddConnection] before showing the wizard, but onboarding embeds the
     * wizard directly; without this guard an API-only QR could configure the
     * live client and load profiles while leaving no durable connection card.
     */
    suspend fun ensureActiveConnectionForSetup(
        apiServerUrl: String = "",
        relayUrl: String = "",
        routeCandidates: List<EndpointCandidate>? = null,
    ): String = addConnectionMutex.withLock {
        val activeId = connectionStore.activeConnectionId.value
        val active = activeId?.let { id ->
            connectionStore.connections.value.firstOrNull { it.id == id }
        }
        if (active != null) return@withLock active.id

        val reusable = connectionStore.connections.value.firstOrNull { c ->
            c.pairedAt == null &&
                c.apiServerUrl.isBlank() &&
                c.label == PLACEHOLDER_LABEL
        } ?: connectionStore.connections.value.firstOrNull()

        if (reusable != null) {
            android.util.Log.i(
                "ConnectionViewModel",
                "ensureActiveConnectionForSetup: activating existing connection id=${reusable.id}",
            )
            switchConnection(reusable.id).join()
            return@withLock reusable.id
        }

        val trimmedApiUrl = apiServerUrl.trim()
        val trimmedRelayUrl = relayUrl.trim()
            .ifBlank { Connection.deriveDefaultRelayUrl(trimmedApiUrl).orEmpty() }
        val id = java.util.UUID.randomUUID().toString()
        val routes = routeCandidates
            ?.takeIf { it.isNotEmpty() }
            ?: trimmedApiUrl.takeIf { it.isNotBlank() }?.let {
                Connection.buildRouteCandidates(
                    apiServerUrl = trimmedApiUrl,
                    relayUrl = trimmedRelayUrl,
                )
            }.orEmpty()
        val connection = Connection(
            id = id,
            label = trimmedApiUrl.takeIf { it.isNotBlank() }
                ?.let(Connection::extractDefaultLabel)
                ?: PLACEHOLDER_LABEL,
            apiServerUrl = trimmedApiUrl,
            relayUrl = trimmedRelayUrl,
            tokenStoreKey = Connection.buildTokenStoreKey(id),
            dashboardUrl = Connection.deriveDefaultDashboardUrl(trimmedApiUrl),
            routeCandidates = routes,
            pairedAt = null,
            lastActiveSessionId = null,
            transportHint = null,
            expiresAt = null,
        )
        connectionStore.addConnection(connection)
        switchConnection(id).join()
        android.util.Log.i(
            "ConnectionViewModel",
            "ensureActiveConnectionForSetup: created first-run connection id=$id api=$trimmedApiUrl",
        )
        id
    }

    /**
     * Remove a placeholder [Connection] created by [beginAddConnection]
     * when the user cancels before a successful pair. No-op when the
     * connection has been paired (pairedAt != null) or its placeholder label
     * has already been promoted. A Dashboard-only connection can legitimately
     * remain unpaired, so pairedAt alone must never classify it as disposable.
     * Also no-op when [connectionId] isn't found.
     *
     * Called from the Pair route's onCancel handler. Safe to call on
     * every cancel without checking state; the internal guard handles
     * the "real connection" case.
     */
    suspend fun discardPlaceholderConnection(connectionId: String) {
        val draft = pendingConnectionDraft
        if (draft?.id == connectionId) {
            authManager.clearSession()
            runCatching { authManager.clearApiKey() }
            AuthManager.importStoredSecrets(
                getApplication(),
                Connection.buildTokenStoreKey(connectionId),
                com.hermesandroid.relay.auth.ConnectionAuthSecrets(),
            )
            pendingConnectionDraft = null
            _connectionDraftId.value = null
            val previous = draft.previousConnectionId?.let { previousId ->
                connectionStore.connections.value.firstOrNull { it.id == previousId }
            }
            if (previous != null) restorePersistedActiveConnectionContext(previous)
            return
        }
        val existing = connectionStore.connections.value.firstOrNull { it.id == connectionId }
            ?: return
        if (existing.pairedAt != null) return
        // Dashboard-only connections intentionally have no Relay pairing, so
        // pairedAt == null no longer identifies a disposable add-flow row.
        // Only the explicit label written by beginAddConnection is safe to
        // treat as a placeholder. This still removes partially-applied Relay
        // setup rows because their label is promoted only after auth succeeds.
        if (existing.label != PLACEHOLDER_LABEL) return
        // An unpaired connection with a populated API URL means the
        // scan hit applyPairingPayload (which writes the URL) but the
        // handshake didn't complete to Paired. We still remove it —
        // the user pressed cancel, the record is garbage.
        removeConnection(connectionId)
    }

    /**
     * Updates the label on a stored connection. Persists via
     * [ConnectionStore.updateConnection]. Does not touch auth state; does
     * not trigger a switch. No-op when the connection id is unknown.
     */
    suspend fun renameConnection(connectionId: String, newLabel: String): Result<Unit> {
        val existing = connectionStore.connections.value.firstOrNull { it.id == connectionId }
            ?: return Result.failure(NoSuchElementException("No connection with id=$connectionId"))
        ConnectionValidation.validateLabel(newLabel)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        connectionStore.updateConnection(existing.copy(label = newLabel.trim()))
        return Result.success(Unit)
    }

    /**
     * Revokes the connection's server-side session
     * (`DELETE /sessions/{tokenPrefix}`) and clears the local auth material.
     *
     * **v1 constraint — active connection only:** if [connectionId] does
     * not match [activeConnectionId] this returns [Result.failure] with a
     * clear message and does nothing. Revoking an inactive connection
     * would require loading its
     * [com.hermesandroid.relay.auth.SessionTokenStore] out-of-band to read
     * the bearer for the DELETE call, which is a bigger change — the
     * current singleton [authManager] only reads the active connection's
     * store. Deferred; documented as a follow-up.
     *
     * On success, marks the connection as unpaired (`pairedAt = null`,
     * `expiresAt = null`, `transportHint = null`) via [ConnectionStore] so
     * the UI reflects it. Also disconnects the relay and wipes the local
     * session token via [AuthManager.clearSession].
     */
    suspend fun revokeConnection(connectionId: String): Result<Unit> {
        if (connectionId != activeConnectionId.value) {
            return Result.failure(
                IllegalStateException(
                    "revoke is limited to the active connection in v1",
                ),
            )
        }
        val existing = connectionStore.connections.value.firstOrNull { it.id == connectionId }
            ?: return Result.failure(
                IllegalStateException("connection $connectionId not found"),
            )
        // Token prefix = first 8 chars of the current session token.
        // Matches the relay's `session.token[:8]` convention (see
        // plugin/relay/server.py `token_prefix` sites).
        val paired = authManager.currentPairedSession.value
        if (paired != null) {
            val prefix = paired.token.take(8)
            val result = relayHttpClient.revokeSession(prefix)
            if (result.isFailure) {
                return Result.failure(
                    result.exceptionOrNull()
                        ?: IllegalStateException("revokeSession failed"),
                )
            }
        }
        // Clear local auth material + tear down the WSS so the reconnect
        // loop doesn't keep re-authing with the just-revoked token.
        clearSession()
        connectionStore.updateConnection(
            existing.copy(
                pairedAt = null,
                expiresAt = null,
                transportHint = null,
            ),
        )
        return Result.success(Unit)
    }

    /**
     * Removes a connection and its stored auth material. [ConnectionStore]
     * handles the [android.content.Context.deleteSharedPreferences] side
     * effect for the connection's EncryptedSharedPreferences file.
     *
     * If the removed connection was active, this switches to another
     * connection (if one exists) first so the app lands on a valid
     * context; otherwise [activeConnectionId] ends up null and the top bar
     * renders "No connection" until the user adds one via Settings.
     */
    suspend fun removeConnection(connectionId: String) {
        val removed = connectionStore.connections.value.firstOrNull { it.id == connectionId }
            ?: return
        val wasActive = connectionId == activeConnectionId.value
        val removedDeviceId = readStoredDeviceIdForRemoval(removed, wasActive)
        if (wasActive) {
            val other = connectionStore.connections.value.firstOrNull { it.id != connectionId }
            if (other != null) {
                // Await the full switch before deleting the store file.
                // switchConnection launches on viewModelScope; if we
                // proceeded to ConnectionStore.removeConnection (which
                // calls Context.deleteSharedPreferences) before the
                // coordinator finished tearing down the old AuthManager,
                // the old manager's in-flight init/hydrate coroutine could
                // fault reading a file that was just deleted.
                switchConnection(other.id).join()
            } else {
                // No successor to switch into — the removed connection
                // WAS the last one. Before the teardown was wired here,
                // `removeConnection` in this branch only cleared the
                // store entry, which left the API client, the WSS socket,
                // and the reachable/health flags alive and pointed at
                // the just-removed URL: status chips kept saying "Paired
                // · Reachable" for a ghost connection.
                //
                // teardownActive() runs the transport half of
                // switchConnection (stream cancel → voice stop → WSS
                // disconnect → switch-event emit) against the active
                // coordinator mutex, so a concurrent Add-connection flow
                // from the UI serialises cleanly.
                connectionSwitchCoordinator.teardownActive().join()

                // Clear the in-memory AuthManager state for the
                // about-to-be-removed connection. Without this the
                // Session row keeps reading `AuthState.Paired(token)`
                // (which the ConnectionManager.disconnect() call inside
                // teardownActive doesn't touch — it only wipes the WSS
                // socket) and the UI shows a green "Paired" dot for a
                // ghost connection. `clearSession()` also nulls
                // `currentPairedSession`, which the relay UI state
                // resolver needs in order to flip its row off
                // Connected.
                authManager.clearSession()

                // URL flows + persisted DataStore entries point at the
                // removed URL. Blank them out so the 30 s periodic
                // health probes (which only run when `_apiClient.value
                // != null` and `_relayUrl.value.isNotBlank()`) stop
                // firing, and cold relaunch doesn't re-seed connection
                // 0 from stale legacy keys.
                _apiServerUrl.value = ""
                _relayUrl.value = ""
                getApplication<Application>().relayDataStore.edit { prefs ->
                    prefs[KEY_API_SERVER_URL] = ""
                    prefs[KEY_RELAY_URL] = ""
                    prefs.remove(KEY_LAST_SESSION_ID)
                }
                // rebuildApiClient() with blank URL nulls _apiClient,
                // flips _apiServerReachable / _apiServerHealth /
                // _chatMode / _serverCapabilities to their disconnected
                // poses (see the `else` branch of rebuildApiClient).
                // This is what actually drives the status badges back
                // to "Not configured".
                rebuildApiClient()
            }
        }
        scrubConnectionArtifacts(removed, removedDeviceId)
        upstreamTransport.disposeConnectionRouteClients(connectionId)
        connectionStore.removeConnection(connectionId)
        botModeController.connectionRemoved(connectionId)
        // Clear the persisted profile selection for the removed connection
        // AFTER the switch-away above has finished. Ordering matters: if
        // we cleared first, any in-flight hydration from the just-swapped
        // AuthManager could race with the delete. Safe because
        // ProfileSelectionStore is a separate DataStore file from
        // ConnectionStore's EncryptedSharedPrefs.
        profileController.profileSelectionStore.clear(connectionId)
        profileController.profileLockStore.clear(connectionId)
        com.hermesandroid.relay.data.SupervisedModeStore(getApplication<Application>())
            .clear(connectionId)
        profileController.profilePresentationStore.clear(connectionId)
        profileController.profileSessionStore.clearConnection(connectionId)
        profileController.profileDisplayAliasStore.clearConnection(connectionId)
        profileController.profileIconStore.clearConnection(connectionId)
        com.hermesandroid.relay.data.BridgeCapabilityPolicyRepository(getApplication())
            .clearConnection(connectionId)
    }

    private suspend fun readStoredDeviceIdForRemoval(
        connection: Connection,
        wasActive: Boolean,
    ): String? {
        if (wasActive) {
            runCatching { authManager.getExistingDeviceId() }
                .getOrNull()
                ?.let { return it }
        }
        return runCatching {
            AuthManager.readStoredDeviceId(getApplication(), connection.tokenStoreKey)
        }.getOrNull()
    }

    private suspend fun scrubConnectionArtifacts(
        connection: Connection,
        deviceId: String?,
    ) {
        if (deviceId != null) {
            runCatching {
                PairingPreferences.removeDeviceEndpoints(getApplication(), deviceId)
            }.onFailure {
                android.util.Log.w(
                    "ConnectionViewModel",
                    "removeConnection: failed to remove endpoints for ${connection.id}: ${it.message}",
                )
            }
        }
    }

    init {
        authManager.setSupervisedMetadataReconnectFallback {
            connectionManager.reconnectForAuthenticatedMetadataUpdate()
        }
        // Wire multiplexer to connection manager (for relay/bridge/terminal)
        multiplexer.setSendCallback { envelope ->
            connectionManager.send(envelope)
        }

        // Auto-authenticate on relay connect
        multiplexer.setOnConnectedCallback {
            authManager.authenticate()
        }

        // (The networkStatus → revalidate() collector lives further down in
        // this init block, next to the other health-probe wiring — a second
        // identical copy used to sit here; one is enough.)

        // === PHASE3-accessibility: bridge handler registration ===
        // Device Control commands are sideload-only. Google Play still
        // registers the bridge channel so direct route probes fail closed with
        // a clear 403 instead of waiting for a command timeout.
        multiplexer.registerHandler("bridge") { envelope ->
            bridgeCommandHandler.onMessage(envelope)
        }
        bridgeStatusReporter.start()

        // === Proactive (agent → phone) wiring ===
        // Inbound `phone.message` → notification handler.
        multiplexer.registerHandler("proactive") { envelope ->
            proactiveMessageHandler.onMessage(envelope)
        }
        // Re-send `proactive.subscribe` on every auth.ok (the relay tracks the
        // subscription per-WebSocket, so it must be re-established on each
        // reconnect). Only when the user opted in. Sent AFTER auth.ok so it
        // never races ahead of the auth handshake.
        viewModelScope.launch {
            authOkEvents.collect {
                if (proactiveEnabled.value) sendProactiveSubscribe()
                // Pull the phone Thread → chat_id map so replies route correctly
                // (covers Threads the app didn't create + survives restart).
                refreshPhoneThreadChatIds()
                // Check whether the relay's plugin is behind the latest release.
                refreshRelayUpdateInfo()
            }
        }
        // React to the toggle flipping while already connected. drop(1) skips
        // the initial DataStore replay (a fresh connect's auth.ok handles the
        // first subscribe). Best-effort: a send while disconnected is dropped,
        // and the auth.ok collector re-subscribes on the next connect.
        viewModelScope.launch {
            proactiveEnabled
                .drop(1)
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) sendProactiveSubscribe() else sendProactiveUnsubscribe()
                }
        }
        // === END Proactive wiring ===

        // === PHASE3-status: push status immediately on master toggle flip ===
        // The periodic tick is 30 s, but the relay-side cache (and the
        // agent's `android_phone_status()` tool that reads it) should see
        // the new master_enabled value right away — not up to 30 s later.
        // drop(1) skips the initial DataStore replay so we don't double
        // up with the first periodic tick on boot.
        if (BuildFlavor.isSideload) {
            viewModelScope.launch {
                com.hermesandroid.relay.accessibility.HermesAccessibilityService
                    .masterEnabledFlow(application)
                    .distinctUntilChanged()
                    .drop(1)
                    .collect {
                        bridgeStatusReporter.pushNow()
                    }
            }
        }
        // === END PHASE3-status ===

        if (BuildFlavor.isSideload) {
            // === v0.4.1 polish: auto-return to Hermes on run.completed ===
            // BridgeRunTracker wires Chat (SSE run.completed) to Bridge
            // (foreground-shifting dispatch) via a shared singleton. When
            // both signals converge in a run, fire a local /return_to_hermes
            // so the user is never stranded on another app after the agent
            // finishes — even if the LLM forgot to call the return tool
            // itself. See BridgeRunTracker KDoc for the full contract.
            com.hermesandroid.relay.bridge.BridgeRunTracker.registerAutoReturnCallback {
                viewModelScope.launch {
                    runCatching {
                        val envelope = com.hermesandroid.relay.network.relay.models.Envelope(
                            channel = "bridge",
                            type = "bridge.command",
                            payload = kotlinx.serialization.json.buildJsonObject {
                                put(
                                    "request_id",
                                    kotlinx.serialization.json.JsonPrimitive(
                                        java.util.UUID.randomUUID().toString(),
                                    ),
                                )
                                put("method", kotlinx.serialization.json.JsonPrimitive("POST"))
                                put("path", kotlinx.serialization.json.JsonPrimitive("/return_to_hermes"))
                                put(
                                    "body",
                                    kotlinx.serialization.json.buildJsonObject { },
                                )
                                put(
                                    "source",
                                    kotlinx.serialization.json.JsonPrimitive("auto_return"),
                                )
                            },
                        )
                        bridgeCommandHandler.handleLocalCommand(envelope)
                    }.onFailure {
                        android.util.Log.w(
                            "ConnectionViewModel",
                            "auto-return dispatch failed: ${it.message}",
                        )
                    }
                }
            }
            // === END v0.4.1 polish ===

            // === v0.4.1 polish: push on unattended-access toggle flip ===
            // Same rationale as the master-toggle push above — the host-side
            // agent reading `/bridge/status` needs to see the new unattended
            // state within a second of the user flipping the toggle, not up
            // to 30 s later. `UnattendedAccessManager.enabled` is a StateFlow
            // so distinctUntilChanged is implicit (per the StateFlow
            // "Operator Fusion" rule) — drop(1) still needed to skip the
            // initial value replay so we don't double up with the first
            // periodic tick on boot.
            viewModelScope.launch {
                com.hermesandroid.relay.bridge.UnattendedAccessManager.enabled
                    .drop(1)
                    .collect {
                        bridgeStatusReporter.pushNow()
                    }
            }
            // === END v0.4.1 polish ===
        }
        // === END PHASE3-accessibility ===

        // === PHASE3-notif-listener-followup: notification companion multiplexer wiring ===
        // The bound NotificationListenerService instance buffers up to 50
        // envelopes in its own pendingEnvelopes queue while this slot is
        // null, so wiring it from here (rather than at service-bind time)
        // is safe — the buffer drains on the next onNotificationPosted
        // once the slot is set. Set unconditionally; the multiplexer's
        // own sendCallback gating handles the relay-disconnected case.
        com.hermesandroid.relay.notifications.HermesNotificationCompanion
            .multiplexer = multiplexer
        // === END PHASE3-notif-listener-followup ===

        // Proactive notification inline-reply (Phase 2c) — the BroadcastReceiver
        // lives outside the ViewModel scope, so it reads the live multiplexer
        // from this static slot (same pattern as the notification companion
        // above). Replies sent while the relay is disconnected drop best-effort.
        com.hermesandroid.relay.notifications.ProactiveReplyReceiver
            .multiplexer = multiplexer

        // Resolve [relayUiState] from the three raw inputs (authState,
        // relayConnectionState, relayUrl) with a grace-window transition
        // to Stale. Lifted here from three separate ad-hoc helpers across
        // SettingsScreen / ConnectionSettingsScreen so every screen renders
        // the relay row consistently. `pendingStaleJob` is the single
        // in-flight grace timer; each new raw-state change cancels any
        // prior timer, so we never get two "promote to Stale" jobs racing.
        viewModelScope.launch {
            var pendingStaleJob: Job? = null
            combine(
                authState,
                relayConnectionState,
                effectiveRelayUrl,
                relayConfigured,
            ) { auth, conn, url, configured -> RelayUiInputs(auth, conn, url, configured) }
                .collect { inputs ->
                    pendingStaleJob?.cancel()
                    pendingStaleJob = null
                    _relayUiState.value = if (inputs.requiresReconnectGrace()) {
                            // Start the grace-window timer. If the WSS
                            // doesn't come up within RELAY_RECONNECT_GRACE_MS,
                            // we promote to Stale so the UI stops lying
                            // ("Connecting…" forever) and surfaces a
                            // tap-to-retry affordance.
                            pendingStaleJob = launch {
                                delay(RELAY_RECONNECT_GRACE_MS)
                                _relayUiState.value = inputs.resolveRelayUiState(graceElapsed = true)
                                DiagnosticsLog.record(
                                    category = DiagnosticCategory.Relay,
                                    severity = DiagnosticSeverity.Warning,
                                    title = ctx.getString(R.string.conn_status_relay_stale),
                                    detail = "Paired session is present, but the live relay socket did not connect",
                                    url = inputs.url,
                                )
                            }
                            RelayUiState.Connecting
                        } else {
                            inputs.resolveRelayUiState()
                        }
                }
        }

        // Stamp the resume timestamp whenever the process returns to the
        // foreground (and on the initial start). Read by the reconnect handoff
        // logic below to decide whether a reconnect is a benign post-resume
        // re-handshake worth suppressing.
        viewModelScope.launch {
            AppForegroundTracker.isForeground.collect { foreground ->
                if (foreground) {
                    lastForegroundResumeAtMs = System.currentTimeMillis()
                    // Open the quiet window: hide the reconnect cue while a benign
                    // post-resume re-handshake settles. Reopen (un-gate) after the
                    // grace window so a genuine outage still surfaces.
                    _postResumeQuiet.value = true
                    postResumeQuietJob?.cancel()
                    postResumeQuietJob = launch {
                        delay(RELAY_RECONNECT_GRACE_MS)
                        _postResumeQuiet.value = false
                    }
                }
            }
        }

        viewModelScope.launch {
            var previousState: ConnectionState? = null
            var previousRole: String? = null
            // Role at the last time we surfaced a "connected" handoff. Lets the
            // single connect message decide "Connected" vs "Connection changed
            // X → Y" at the moment the socket is actually up — so a route swap is
            // ONE message, never "Connection changed" followed by a redundant
            // "Connected". (previousRole can't do this: the endpoint often
            // republishes the new role mid-swap, before the reconnect completes.)
            var lastConnectedRole: String? = null
            combine(
                relayConnectionState,
                connectionManager.activeRelayEndpoint,
            ) { state, endpoint -> state to endpoint?.role }
                .distinctUntilChanged()
                .collect { (state, role) ->
                    val priorState = previousState
                    val priorRole = previousRole
                    previousState = state
                    previousRole = role
                    if (priorState == null) return@collect

                    // Every relay socket-state / endpoint-role transition that
                    // *could* surface a handoff banner. This is the "both sides"
                    // client trace: pair it with the server's connection log to
                    // tell a real drop (server saw a close) from a client-only
                    // role flip (server saw nothing → spurious "route changed").
                    android.util.Log.i(
                        TAG,
                        "relay transition: $priorState→$state " +
                            "role=${priorRole ?: "-"}→${role ?: "-"}",
                    )

                    when {
                        state == ConnectionState.Reconnecting -> {
                            // Same connection re-handshaking — never imply a switch
                            // ("Connection changed" used to mislead here; a genuine
                            // route switch is handled by its own branch below).
                            val reconnectHandoff = {
                                recordConnectionHandoff(
                                    title = ctx.getString(R.string.conn_status_reconnecting),
                                    route = displayEndpointRole(role ?: priorRole),
                                    detail = "Re-establishing the relay socket",
                                    active = true,
                                    success = false,
                                )
                            }
                            val resumeAgeMs = System.currentTimeMillis() - lastForegroundResumeAtMs
                            val justResumed = resumeAgeMs in 0 until RELAY_RECONNECT_GRACE_MS
                            transientReconnectJob?.cancel()
                            if (justResumed) {
                                // Withhold the banner: on a foreground resume the OS
                                // commonly drops + re-handshakes the socket. Only
                                // surface "Reconnecting" if it's still down past the
                                // grace window (a real outage, not an app switch).
                                suppressedTransientReconnect = true
                                transientReconnectJob = launch {
                                    delay(RELAY_RECONNECT_GRACE_MS)
                                    if (relayConnectionState.value == ConnectionState.Reconnecting) {
                                        suppressedTransientReconnect = false
                                        reconnectHandoff()
                                    }
                                }
                            } else {
                                suppressedTransientReconnect = false
                                reconnectHandoff()
                            }
                        }
                        state == ConnectionState.Connected &&
                            priorState != ConnectionState.Connected -> {
                            // ONE message for every transition into Connected
                            // (from Reconnecting / Connecting / Disconnected). If
                            // the route changed since we were last connected it's
                            // "Connection changed · LAN → Tailscale" (which itself
                            // implies connected — no redundant "Connected" after);
                            // otherwise just "Connected to Hermes". This single
                            // point replaces the old three positive branches
                            // ("restored" + "connected" + "route changed") that
                            // fired in pairs on a flap or a swap.
                            transientReconnectJob?.cancel()
                            val fromRole = lastConnectedRole
                            if (suppressedTransientReconnect) {
                                // Benign post-resume recovery we kept silent — stay
                                // silent on the restore too.
                                suppressedTransientReconnect = false
                            } else {
                                val routeSwapped = !fromRole.isNullOrBlank() &&
                                    !role.isNullOrBlank() &&
                                    !fromRole.equals(role, ignoreCase = true)
                                if (routeSwapped) {
                                    val from = displayEndpointRole(fromRole)
                                    val to = displayEndpointRole(role)
                                    recordConnectionHandoff(
                                        title = ctx.getString(R.string.conn_status_connection_changed),
                                        route = listOfNotNull(from, to).joinToString(" → "),
                                        detail = null,
                                        active = false,
                                        success = true,
                                    )
                                } else {
                                    recordConnectionHandoff(
                                        title = ctx.getString(R.string.conn_status_connected),
                                        route = displayEndpointRole(role),
                                        detail = ctx.getString(R.string.conn_detail_relay_path_ready),
                                        active = false,
                                        success = true,
                                    )
                                }
                            }
                            lastConnectedRole = role
                        }
                        state == ConnectionState.Disconnected &&
                            priorState == ConnectionState.Connected -> {
                            recordConnectionHandoff(
                                title = ctx.getString(R.string.conn_status_interrupted),
                                route = displayEndpointRole(priorRole),
                                detail = ctx.getString(R.string.conn_detail_looking_for_route),
                                active = true,
                                success = false,
                            )
                        }
                    }
                }
        }

        // Multi-connection: stamp the active Connection with pairing metadata
        // when AuthManager surfaces a fresh PairedSession. Closes the bug
        // where Connections list showed "Not paired" even though the
        // Settings card correctly said "Paired" — previously `markPaired`
        // existed on the store but was never called after the
        // Profile → Connection rename.
        //
        // Stamp only when `pairedAt` is still null so we don't churn
        // DataStore writes on every auth reload. Re-pair flows explicitly
        // clear `pairedAt` via the revoke path (see revokeConnection),
        // so the next auth.ok will stamp again cleanly.
        viewModelScope.launch {
            combine(
                connectionStore.activeConnectionId,
                currentPairedSession,
            ) { id, paired -> id to paired }
                .distinctUntilChanged()
                .collect { (connId, paired) ->
                    if (connId == null || paired == null) return@collect
                    // A transient Add-connection AuthManager is intentionally
                    // not owned by the still-active persisted connection.
                    // commitConnectionDraft snapshots its paired metadata.
                    if (pendingConnectionDraft != null) return@collect
                    val current = connectionStore.connections.value
                        .firstOrNull { it.id == connId } ?: return@collect
                    if (current.pairedAt != null) return@collect
                    // Stale-emission guard: during a connection switch,
                    // `currentPairedSession` can briefly carry the OUTGOING
                    // connection's session while `activeConnectionId` has
                    // already flipped to the incoming id (flatMapLatest re-
                    // subscribes asynchronously). Without this check we'd
                    // `markPaired` the new placeholder connection using the
                    // old connection's session data — stamping pairedAt on
                    // a Connection that still has blank URLs, which then
                    // locks out the real rename-on-pair path below because
                    // current.pairedAt is no longer null.
                    //
                    // A real pair payload writes the API URL (via
                    // `applyPairingPayload` → per-Connection store mirror)
                    // BEFORE the WSS handshake that emits auth.ok, so by
                    // the time a genuine session lands, apiServerUrl is
                    // always populated. A blank URL here therefore implies
                    // a premature/stale fire — safe to ignore.
                    if (current.apiServerUrl.isBlank()) {
                        android.util.Log.d(
                            "ConnectionVM",
                            "pair-success watcher: skipping stale emission " +
                                "(connId=$connId has blank apiServerUrl — " +
                                "likely cross-connection flow leak during switch)",
                        )
                        return@collect
                    }
                    connectionStore.markPaired(
                        connectionId = connId,
                        pairedAtMillis = System.currentTimeMillis(),
                        transportHint = paired.transportHint,
                        // PairedSession.expiresAt is epoch seconds; the
                        // store docs are explicit that it expects millis —
                        // passing seconds would render as "Paired decades ago".
                        expiresAtMillis = paired.expiresAt?.let { it * 1000L },
                    )

                    // Duplicate-server merge. Pairing to a server the user
                    // already has a connection to (e.g. `Add connection` →
                    // scan the same QR twice across different sessions)
                    // would otherwise produce two cards on the Connections
                    // screen pointing at the same API URL. The new pair is
                    // authoritative (fresh session token + TTL), so we
                    // collapse by deleting the older duplicate.
                    //
                    // Deferred to AFTER markPaired rather than folded into
                    // `applyPairingPayload`'s URL-mirror step on purpose:
                    // if the WSS handshake had failed between the URL
                    // write and auth.ok, an applyPairingPayload-side merge
                    // would have already deleted the user's working
                    // connection and replaced it with an unpaired ghost.
                    // Running here guarantees the new session is good
                    // before we touch the old record.
                    //
                    // Label carry-over: if the old duplicate had a
                    // user-customized label (i.e. not the placeholder),
                    // prefer it over the host-derived default the rename
                    // path would otherwise pick. Preserves user intent
                    // across a re-scan of the same server. Resolved into
                    // `carriedLabel` so the rename block below can fall
                    // through to a single `updateConnection` call.
                    var carriedLabel: String? = null
                    val duplicates = connectionStore.connections.value
                        .filter { other ->
                            other.id != connId &&
                                other.apiServerUrl.isNotBlank() &&
                                other.apiServerUrl == current.apiServerUrl
                        }
                    for (duplicate in duplicates) {
                        // Take the first custom label we encounter. If
                        // several duplicates all have custom labels (would
                        // indicate the user has been renaming same-URL
                        // entries for a while), we pick the first in
                        // store order rather than trying to reconcile —
                        // a one-line snackbar on the caller could surface
                        // this if it turns out to be a real footgun later.
                        if (carriedLabel == null && duplicate.label != PLACEHOLDER_LABEL) {
                            carriedLabel = duplicate.label
                        }
                        android.util.Log.i(
                            "ConnectionVM",
                            "pair-success dedupe: merging duplicate " +
                                "id=${duplicate.id} (same apiServerUrl as active " +
                                "connId=$connId) — preserveLabel=$carriedLabel",
                        )
                        // Direct store call rather than the public
                        // ConnectionViewModel.removeConnection — the latter
                        // does a switch-first if the target is active,
                        // which would thrash here since the duplicate is
                        // by construction NOT the active connection. Also
                        // clears the per-connection profile pick so the
                        // removed connection's EncryptedPrefs + route list
                        // + profile pointer go together.
                        scrubConnectionArtifacts(
                            duplicate,
                            readStoredDeviceIdForRemoval(duplicate, wasActive = false),
                        )
                        connectionStore.removeConnection(duplicate.id)
                        profileController.profileSelectionStore.clear(duplicate.id)
                        profileController.profileLockStore.clear(duplicate.id)
                        com.hermesandroid.relay.data.SupervisedModeStore(getApplication<Application>())
                            .clear(duplicate.id)
                        profileController.profilePresentationStore.clear(duplicate.id)
                        profileController.profileSessionStore.clearConnection(duplicate.id)
                    }

                    // Auto-rename the placeholder label created by
                    // [beginAddConnection]. We only touch the label when
                    // it's still the exact placeholder string — if the
                    // user typed a custom name during pairing we leave
                    // it alone. Label source preference:
                    //   1. [carriedLabel] from a de-duped predecessor, so
                    //      a re-pair to the same server keeps the user's
                    //      prior custom name.
                    //   2. Otherwise the host-derived default — same
                    //      formula as [Connection.extractDefaultLabel]
                    //      used for legacy-seeded connections.
                    if (current.label == PLACEHOLDER_LABEL &&
                        current.apiServerUrl.isNotBlank()
                    ) {
                        val newLabel = carriedLabel
                            ?: Connection.extractDefaultLabel(current.apiServerUrl)
                        val refreshed = connectionStore.connections.value
                            .firstOrNull { it.id == connId }
                        if (refreshed != null) {
                            connectionStore.updateConnection(
                                refreshed.copy(label = newLabel),
                            )
                        }
                    }

                    // ADR 24 — on fresh pair, endpoints land in DataStore
                    // inside handleAuthOk. The initial connect() call
                    // ran BEFORE that persistence and therefore gave up
                    // on the resolver (no endpoints stored yet) → set
                    // activeEndpoint to null. Kick a re-probe now that
                    // the candidate list is on disk; probeAndReconnect
                    // only swaps the socket if the winner's URL differs
                    // from the currently-connected URL, so for a
                    // same-endpoint pair (the common case — LAN won
                    // during pair, LAN still wins post-pair) this is a
                    // zero-disruption activeEndpoint flow update.
                    connectionManager.probeAndReconnect()
                }
        }

        // Multi-connection: on cold boot, if no connections are persisted
        // yet, seed connection 0 from whatever URLs/session id the
        // pre-multi-connection install left in DataStore. Idempotent — a
        // no-op once connection 0 (or any user-created connection) is
        // already in the list.
        //
        // Runs on its own coroutine so it doesn't block the DataStore
        // collect loop below. A race where the collect-loop writes a new
        // URL value mid-migration is benign — migrateLegacyConnectionIfNeeded
        // is guarded by ConnectionStore.writeMutex and early-returns once
        // any connection exists, so a second call observing the
        // freshly-seeded connection just exits.
        viewModelScope.launch {
            try {
                val prefs = application.relayDataStore.data.first()
                val hasLegacyConnectionState =
                    prefs[KEY_API_SERVER_URL] != null ||
                        prefs[KEY_RELAY_URL] != null ||
                        prefs[KEY_SERVER_URL] != null ||
                        prefs[KEY_LAST_SESSION_ID] != null
                val legacyApiUrl = if (hasLegacyConnectionState) {
                    prefs[KEY_API_SERVER_URL] ?: DEFAULT_API_URL
                } else {
                    null
                }
                val legacyRelayUrl = if (hasLegacyConnectionState) {
                    prefs[KEY_RELAY_URL]
                        ?: prefs[KEY_SERVER_URL]
                        ?: DEFAULT_RELAY_URL
                } else {
                    null
                }
                val legacySessionId = prefs[KEY_LAST_SESSION_ID]
                connectionStore.migrateLegacyConnectionIfNeeded(
                    legacyApiServerUrl = legacyApiUrl,
                    legacyRelayUrl = legacyRelayUrl,
                    legacyLastSessionId = legacySessionId,
                )
            } catch (e: Exception) {
                android.util.Log.w(
                    "ConnectionViewModel",
                    "migrateLegacyConnectionIfNeeded failed: ${e.message}",
                )
            }
        }

        // Defensive: sweep orphaned placeholders left behind by a prior
        // "Add connection" flow the user abandoned via system back / gesture
        // back (the pre-fix code path only cleaned up on explicit Cancel).
        // An orphan is an unpaired connection with no URL ever written AND
        // the exact PLACEHOLDER_LABEL — that tuple cannot be produced by any
        // real pairing, so it's safe to delete unconditionally.
        //
        // Runs once on VM init AFTER the legacy migration completes so a
        // freshly-seeded connection (which also has pairedAt == null until
        // its first auth.ok) isn't misidentified — legacy seed uses the
        // host-derived default label, never PLACEHOLDER_LABEL.
        //
        // If the currently-active id points at a placeholder we're about to
        // remove, switch to whichever real connection comes first in the
        // list before deleting so we don't leave activeConnectionId pointing
        // at a dead record.
        viewModelScope.launch {
            try {
                // StateFlow is seeded empty, so reading connections.first()
                // here can win the initial DataStore read and permanently
                // miss persisted placeholders. Wait until the store has
                // completed its initial read before deciding what is orphaned.
                connectionStore.isHydrated.first { it }
                val connections = connectionStore.connections.value
                val orphans = connections.filter {
                    it.pairedAt == null &&
                        it.apiServerUrl.isBlank() &&
                        it.label == PLACEHOLDER_LABEL
                }
                if (orphans.isEmpty()) return@launch

                val activeId = connectionStore.activeConnectionId.value
                if (activeId != null && orphans.any { it.id == activeId }) {
                    val successor = connections.firstOrNull {
                        it.id != activeId && it !in orphans
                    }
                    if (successor != null) {
                        switchConnection(successor.id).join()
                    }
                }
                for (orphan in orphans) {
                    android.util.Log.i(
                        "ConnectionViewModel",
                        "Removing orphan placeholder connection id=${orphan.id}",
                    )
                    connectionStore.removeConnection(orphan.id)
                }
            } catch (e: Exception) {
                android.util.Log.w(
                    "ConnectionViewModel",
                    "Orphan placeholder sweep failed: ${e.message}",
                )
            }
        }

        // Cold-start context restore. The field initializer above has to build
        // an AuthManager before ConnectionStore has hydrated, so it starts on
        // the legacy token store. Once the persisted active connection is known,
        // rebuild against that connection's tokenStoreKey so session/API-key
        // state survives process death.
        viewModelScope.launch {
            try {
                val connection = activeConnection.first { it != null } ?: return@launch
                restorePersistedActiveConnectionContext(connection)
            } catch (e: Exception) {
                android.util.Log.w(
                    "ConnectionViewModel",
                    "restorePersistedActiveConnectionContext failed: ${e.message}",
                )
            }
        }

        // Load saved state — split into fast (UI-blocking) and slow (network) paths
        viewModelScope.launch {
            _onboardingCompleted.value = dataManager.isOnboardingCompleted()

            var prevApiUrl: String? = null
            var prevApiKey: String? = null

            application.relayDataStore.data.collect { preferences ->
                // Restore insecure mode
                val insecure = preferences[KEY_INSECURE_MODE] ?: false
                connectionManager.setInsecureMode(insecure)

                // Load API server URL
                val savedApiUrl = preferences[KEY_API_SERVER_URL]
                if (savedApiUrl != null) {
                    _apiServerUrl.value = savedApiUrl
                }

                // Load relay URL (with migration from old server_url key)
                val savedRelayUrl = preferences[KEY_RELAY_URL]
                    ?: preferences[KEY_SERVER_URL] // legacy migration
                if (savedRelayUrl != null) {
                    _relayUrl.value = savedRelayUrl
                }

                // Last-session restore is profile-scoped now. Keep that
                // state owned by refreshLastSessionForProfile(); otherwise
                // any unrelated relayDataStore emission can overwrite an
                // active profile's session with the legacy default id.

                // Check if this is a new version → show What's New
                val currentVersion = getAppVersionName()
                val lastSeen = preferences[KEY_LAST_SEEN_VERSION]
                if (lastSeen != null && lastSeen != currentVersion) {
                    _showWhatsNew.value = true
                }

                // Mark ready after first DataStore emission (UI can render)
                if (!_isReady.value) {
                    _isReady.value = true
                }

                // Rebuild API client in a separate coroutine so it doesn't block
                // the DataStore flow. apiKeyForClientBuild() skips the Tink
                // crypto-init wait entirely on known-key-less connections —
                // this launch is the FIRST client build at cold start, so
                // awaiting the full decrypt here used to hold chat, health,
                // and capabilities hostage to the StrongBox keyset marathon.
                val currentUrl = effectiveApiServerUrlSnapshot()
                launch {
                    val currentKey = apiKeyForClientBuild()
                    if (currentUrl != prevApiUrl || currentKey != prevApiKey) {
                        prevApiUrl = currentUrl
                        prevApiKey = currentKey
                        rebuildApiClient()
                    }
                }
            }
        }

        // Periodic API health check — only runs when an API client is configured.
        // 30s cadence matches the prior loop. Updates both the legacy boolean
        // and the new tri-state HealthStatus flow so existing callers don't break.
        //
        // Escalation: two consecutive Unreachable probes kick a full route
        // re-resolve with the probe cache cleared. This is the safety net for
        // network changes the NetworkCallback never saw (e.g. an always-on
        // VPN keeping "internet available" true through a Wi-Fi → cell
        // handoff) — without it, an open app keeps probing the dead LAN URL
        // forever. The effectiveApiServerUrl collector below rebuilds the
        // HTTP clients when the resolved route actually moves.
        viewModelScope.launch {
            var consecutiveApiFailures = 0
            while (true) {
                delay(30_000)
                if (_apiClient.value == null) {
                    consecutiveApiFailures = 0
                    continue
                }
                probeApiHealth()
                if (_apiServerHealth.value == HealthStatus.Unreachable) {
                    consecutiveApiFailures++
                    if (consecutiveApiFailures >= 2) {
                        consecutiveApiFailures = 0
                        connectionManager.refreshActiveEndpoint(clearProbeCache = true)
                    }
                } else {
                    consecutiveApiFailures = 0
                }
            }
        }

        // Fast-retry burst on an Unreachable verdict. The periodic loop
        // above ticks every 30s — a single transient miss (cold-start race
        // with the route resolver, Wi-Fi still settling, mid-route-swap)
        // used to park "offline" for a full tick, which the startup gate
        // (and the demo-video camera) measured as a 6–28s wildly variable
        // launch against the same healthy LAN server. One bounded burst per
        // failure episode: three quick re-probes, re-armed only by a
        // Reachable verdict — a genuinely down server fails one burst and
        // settles back to the 30s cadence (where the 2-consecutive-failures
        // escalation still owns route re-resolution). StateFlow dedup means
        // repeat Unreachable verdicts can't re-trigger the burst.
        viewModelScope.launch {
            var burstArmed = true
            _apiServerHealth.collect { verdict ->
                when (verdict) {
                    HealthStatus.Reachable -> burstArmed = true
                    HealthStatus.Unreachable -> {
                        if (!burstArmed) return@collect
                        burstArmed = false
                        for (retryDelayMs in listOf(2_500L, 5_000L, 7_500L)) {
                            delay(retryDelayMs)
                            if (_apiServerHealth.value != HealthStatus.Unreachable) {
                                return@collect
                            }
                            if (_apiClient.value == null) return@collect
                            probeApiHealth()
                        }
                    }
                    else -> Unit
                }
            }
        }

        // Periodic relay health check — same cadence. Only fires when a relay
        // URL is configured. Does NOT touch the WSS channel; this is a pure
        // /health probe via RelayHttpClient (3s timeout, no auth needed).
        // Plugs the historical gap where relay status was only verified by
        // WSS heartbeat or manual Save & Test taps.
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                if (activeRelayConfiguredSnapshot() && effectiveRelayUrlSnapshot().isNotBlank()) {
                    probeRelayHealth()
                } else {
                    _relayServerHealth.value = HealthStatus.Unknown
                }
            }
        }

        // Runtime endpoint changes should move all HTTP clients, not only
        // the WSS relay socket. Stored URLs remain untouched; this rebuilds
        // chat/API against the currently resolved route.
        viewModelScope.launch {
            effectiveApiServerUrl
                .drop(1)
                .distinctUntilChanged()
                .collect {
                    if (_chatStreaming.value) {
                        // Rebuilding the chat client mid-turn replaces it and
                        // CANCELS the in-flight turn. The gateway socket rides a
                        // transient route blip via its own reconnect (keeping the
                        // live session), so defer the rebuild until the turn ends.
                        pendingApiClientRebuild = true
                        android.util.Log.i(
                            "ConnectionViewModel",
                            "route changed mid-turn — deferring chat client rebuild",
                        )
                    } else {
                        rebuildApiClient()
                    }
                }
        }

        // Dashboard/Gateway can move independently of the optional API
        // fallback (for example, a custom dashboard-only route). Do not carry
        // the previous host's availability or cookie-backed status into the
        // new route while its probe is in flight. collectLatest cancels an
        // obsolete probe if the resolver changes routes again.
        viewModelScope.launch {
            effectiveDashboardUrl
                .drop(1)
                .distinctUntilChanged()
                .collectLatest {
                    _standardVoiceAvailability.value = StandardVoiceAvailability.Unknown
                    _standardAudioApiReachable.value = false
                    _hostResourcePressure.value = HostResourcePressureStatus()
                    _serverChatDisplaySettings.value = null
                    updateGatewayAvailability(GatewayAvailability.Unknown)
                    probeStandardVoice()
                }
        }

        // Apply a route change that was deferred because a turn was streaming.
        // (StateFlow already conflates/dedups, so no distinctUntilChanged.)
        viewModelScope.launch {
            _chatStreaming
                .collect { streaming ->
                    if (!streaming && pendingApiClientRebuild) {
                        pendingApiClientRebuild = false
                        android.util.Log.i(
                            "ConnectionViewModel",
                            "turn ended — applying deferred chat client rebuild",
                        )
                        rebuildApiClient()
                    }
                }
        }

        viewModelScope.launch {
            combine(effectiveRelayUrl, relayConfigured) { url, configured -> url to configured }
                .drop(1)
                .distinctUntilChanged()
                .collect { (url, configured) ->
                    if (!configured || url.isBlank()) {
                        _relayServerHealth.value = HealthStatus.Unknown
                    } else {
                        _relayServerHealth.value = HealthStatus.Probing
                        probeRelayHealth()
                    }
                }
        }

        // React to network changes — ConnectivityObserver was previously
        // observed but never read. Now any "network back" transition kicks
        // a fresh revalidation so badges flip from Probing to a verified
        // state without waiting for the next periodic tick.
        //
        // drop(1) skips the StateFlow's seed value (Available) so we don't
        // double-probe on first composition; the init block already triggers
        // the initial probe via rebuildApiClient().
        viewModelScope.launch {
            networkStatus
                .drop(1)
                .distinctUntilChanged()
                .collect { status ->
                    if (status is ConnectivityObserver.Status.Available) {
                        revalidate()
                    }
                }
        }

        // Mirror the media cache cap into a plain volatile field so the
        // cache writer can read it synchronously from its enforceCap() loop.
        viewModelScope.launch {
            mediaSettingsRepo.settings.collect { settings ->
                _cachedMediaCapMb = settings.cachedMediaCapMb
            }
        }

        // Drop the current Profile object as soon as a switch begins. The
        // persisted destination name is loaded only after activeConnectionId
        // changes, then resolved by the agentProfiles collector below.
        viewModelScope.launch {
            connectionSwitchEvents.collect {
                // Profile + pending state + dashboard list are per-connection —
                // drop them so the pending persisted name can't resolve against
                // the previous connection's profiles before the new list arrives.
                profileController.resetForConnectionSwitch()
                // Gateway state is per-connection: drop the sticky
                // Unsupported verdict and tear down the old socket so the
                // next probe/send evaluates the new connection fresh.
                upstreamTransport.resetGatewayForConnectionSwitch()
            }
        }

        // Profile selections are persisted per connection. Loading just the
        // name here keeps the UI/send path in "server default" until this
        // active connection's profile list arrives and can resolve it.
        viewModelScope.launch {
            activeConnectionId.collect { connectionId ->
                val connection = connectionId?.let { cid ->
                    connectionStore.connections.value.firstOrNull { it.id == cid }
                }
                connectionManager.setManualRoleOverride(connection?.preferredRouteRole)
                profileController.clearSelectedProfile()
                _lastSessionId.value = null
                profileController.setPendingConnectionId(connectionId)
                profileController.setPendingName(
                    connectionId?.let { cid ->
                        profileController.profileSelectionStore.selectedProfileFlow(cid).first()
                    }
                )
                profileController.resolvePendingProfileFrom(profileController.agentProfiles.value)
                profileController.refreshLastSessionForProfile(
                    connectionId,
                    profileController.selectedProfile.value?.name,
                )
                val apiRouteBefore = effectiveApiServerUrlSnapshot()
                connectionManager.refreshActiveEndpoint()
                launch { probeActiveRouteSurfaces() }
                if (effectiveApiServerUrlSnapshot() != apiRouteBefore) {
                    rebuildApiClient()
                }
                rebuildChatApiClient()
                // Hydrate the host's agent profiles eagerly (not lazily on
                // agent-sheet open). On a dashboard/gateway connection the relay
                // `auth.ok` profile list is empty, so without this the persisted
                // profile selection can't resolve until the user opens the picker
                // — the header shows the default agent on cold start, then visibly
                // snaps to the real profile (and re-scopes the chat) the moment the
                // sheet fetches the list. Best-effort; the agentProfiles collector
                // resolves the pending name once the list lands.
                refreshDashboardProfiles()
            }
        }

        // Resolve or refresh the selected Profile only from the active
        // connection's current agentProfiles list. A missing persisted name
        // remains pending so it can recover if the server advertises it later.
        viewModelScope.launch {
            profileController.agentProfiles.collect { list ->
                if (profileController.resolvePendingProfileFrom(list)) {
                    profileController.refreshLastSessionForProfile(
                        activeConnectionId.value,
                        profileController.selectedProfile.value?.name,
                    )
                    rebuildChatApiClient()
                }
            }
        }

        // Re-resolve when the per-connection profile lock changes — locking,
        // unlocking, and the lock flow repointing after a connection switch all
        // funnel through here so the active profile always reflects the lock
        // target (or holds null + a banner when it's missing). resolvePending
        // is lock-aware, so on unlock it falls back to the persisted selection.
        viewModelScope.launch {
            profileController.lockedProfileName.collect {
                if (profileController.resolvePendingProfileFrom(
                        profileController.agentProfiles.value,
                    )
                ) {
                    profileController.refreshLastSessionForProfile(
                        activeConnectionId.value,
                        profileController.selectedProfile.value?.name,
                    )
                    rebuildChatApiClient()
                }
            }
        }

        // Cold-start restore timing: the gateway probe is async, so the first
        // refreshLastSessionForProfile at connection-activate can run while
        // availability is still Unknown — [activeSessionTransport] defers, leaving
        // no session restored. Re-run once the probe settles, but only when
        // nothing has been restored or started yet, so we never yank a session
        // the user is already in.
        viewModelScope.launch {
            upstreamTransport.gatewayAvailability.collect { availability ->
                if (availability == GatewayAvailability.Unknown) return@collect
                if (_lastSessionId.value != null) return@collect
                val connectionId = activeConnectionId.value ?: return@collect
                profileController.refreshLastSessionForProfile(
                    connectionId,
                    profileController.selectedProfile.value?.name,
                )
            }
        }
    }

    // --- Revalidation ----------------------------------------------------
    //
    // Single entry point for "the world might have changed — re-check
    // everything." Called from RelayApp's ON_RESUME observer, from the
    // ConnectivityObserver collector above, and any future surface that
    // wants the badges to refresh on demand. Guarded by [revalidationJob]
    // so rapid-fire resumes don't pile up parallel probes.

    @Volatile
    private var revalidationJob: Job? = null

    /**
     * Re-probe API + relay health in parallel. Both flows immediately flip
     * to [HealthStatus.Probing] so the UI can render a "checking status"
     * pose without waiting for the network round-trip — that's the fix for
     * the resume-lag flash where badges showed stale Connected/Disconnected
     * for up to 30 seconds after foregrounding.
     *
     * Idempotent: if a revalidation is already in flight, we skip and let
     * the existing one finish. Cheap enough that callers don't need to
     * debounce themselves.
     */
    /**
     * Resume-path entry to [revalidate], debounced by how long the app was
     * away. A quick app-switch with an already-healthy API connection skips
     * the cache-clearing re-probe (and the Probing badge flash) entirely;
     * a longer absence — or an unhealthy connection — re-probes as usual.
     *
     * @param awayMs milliseconds since the Activity was last paused. Callers
     *   that can't measure it (or want to force a probe) pass [Long.MAX_VALUE].
     */
    fun revalidateOnResume(awayMs: Long) {
        // Relay recovery is independent of standard API health. Even a brief
        // resume should replace ordinary WSS backoff with an immediate attempt.
        reconnectIfStale()
        val healthy = _apiServerHealth.value == HealthStatus.Reachable
        if (awayMs in 0 until BRIEF_RESUME_REVALIDATE_MS && healthy) {
            return
        }
        revalidate()
    }

    fun revalidate() {
        if (isDemoMode.value) return // Demo mode is offline — skip all probes.
        if (revalidationJob?.isActive == true) return
        revalidationJob = viewModelScope.launch {
            val apiRouteBefore = effectiveApiServerUrlSnapshot()
            // Clear the probe cache: revalidate() fires on resume / network
            // change, where a cached-reachable entry for the route we just
            // walked away from would win the resolve for up to 60s.
            connectionManager.refreshActiveEndpoint(clearProbeCache = true)
            if (effectiveApiServerUrlSnapshot() != apiRouteBefore) {
                rebuildApiClient()
            }
            // Flip to Probing immediately so the UI doesn't flash whatever
            // stale value the previous session left in the flow.
            if (_apiClient.value != null) {
                _apiServerHealth.value = HealthStatus.Probing
            }
            val shouldProbeRelay = activeRelayConfiguredSnapshot() &&
                effectiveRelayUrlSnapshot().isNotBlank()
            if (shouldProbeRelay) {
                _relayServerHealth.value = HealthStatus.Probing
            } else {
                _relayServerHealth.value = HealthStatus.Unknown
            }

            // Dashboard/Gateway, optional API, and optional Relay are separate
            // surfaces. Probe them as siblings so an API timeout cannot delay
            // authentication/session readiness and Relay never gates Chat.
            val dashboardProbe = launch { probeStandardVoice() }
            val apiProbe = launch { probeApiHealth(includeDashboardProbe = false) }
            val relayProbe = if (shouldProbeRelay) {
                launch { probeRelayHealth() }
            } else {
                null
            }
            val routeSurfaceProbe = launch { probeActiveRouteSurfaces() }
            dashboardProbe.join()
            apiProbe.join()
            relayProbe?.join()
            routeSurfaceProbe.join()

            // Also kick a stale-WSS reconnect — if we hold a paired session
            // but the WSS is down (the most common post-resume state), bring
            // it back without forcing the user to tap into Settings.
            reconnectIfStale()
        }
    }

    /**
     * Run a single API /health probe and update both the legacy boolean
     * and the tri-state flow. Safe to call from anywhere; no-ops cleanly
     * when the client isn't configured.
     */
    private suspend fun probeApiHealth(includeDashboardProbe: Boolean = true) {
        if (isDemoMode.value) {
            // Demo mode is offline — report Unknown without touching the network.
            _apiServerHealth.value = HealthStatus.Unknown
            _apiServerReachable.value = false
            return
        }
        val client = _apiClient.value
        if (client == null) {
            _apiServerHealth.value = HealthStatus.Unknown
            _apiServerReachable.value = false
            if (includeDashboardProbe) probeStandardVoice()
            return
        }
        val ok = client.checkHealth()
        _apiServerReachable.value = ok
        _apiServerHealth.value = if (ok) HealthStatus.Reachable else HealthStatus.Unreachable
        // Standard voice lives on the dashboard surface, not the API server,
        // so its probe runs regardless of API health.
        if (includeDashboardProbe) probeStandardVoice()
    }

    /**
     * Probe the standard (dashboard-surface) voice route and update
     * [standardVoiceAvailability]. Cheap: GET `/api/status` (public by
     * design), GET `/api/auth/me` only when the dashboard is gated, then a
     * HEAD existence check on the audio route (405 = present, 404 = absent).
     * Also refreshes the persisted dashboard status snapshot when it
     * materially changed, so the Manage header stays honest without the user
     * visiting the tab.
     */
    private suspend fun probeStandardVoice() {
        val connectionId = connectionStore.activeConnectionId.value
        val dashboardUrl = activeDashboardUrl()
        if (connectionId == null || dashboardUrl.isNullOrBlank()) {
            _standardVoiceAvailability.value = StandardVoiceAvailability.Unknown
            _standardAudioApiReachable.value = false
            _hostResourcePressure.value = HostResourcePressureStatus()
            _serverChatDisplaySettings.value = null
            updateGatewayAvailability(GatewayAvailability.Unknown)
            return
        }
        val client = upstreamTransport.dashboardClientForActive(dashboardUrl)
        try {
            val status = client.getStatus().getOrNull()
            if (!ownsDashboardProbe(connectionId, dashboardUrl)) return
            if (status == null) {
                recordDashboardGatewayFailure(
                    dashboardUrl = dashboardUrl,
                    detail = "Dashboard status probe returned no response.",
                )
                updateDashboardTopology(connectionId, null)
                if (!ownsDashboardProbe(connectionId, dashboardUrl)) return
                _standardVoiceAvailability.value = StandardVoiceAvailability.Unreachable
                _standardAudioApiReachable.value = false
                _hostResourcePressure.value = HostResourcePressureStatus()
                _serverChatDisplaySettings.value = null
                updateGatewayAvailability(GatewayAvailability.Unreachable)
                recordDashboardStatusIfChanged(connectionId, status = null, session = null)
                return
            }
            val session = if (status.authRequired) client.currentSession().getOrNull() else null
            if (!ownsDashboardProbe(connectionId, dashboardUrl)) return
            val authed = !status.authRequired || session?.authenticated == true
            val (chatDisplaySettings, audioRoutesPresent) = if (authed) {
                coroutineScope {
                    val display = async { loadChatDisplaySettings(client) }
                    val audio = async { client.audioRoutesPresent() }
                    display.await() to audio.await()
                }
            } else {
                null to false
            }
            if (!ownsDashboardProbe(connectionId, dashboardUrl)) return

            updateDashboardTopology(connectionId, status)
            if (!ownsDashboardProbe(connectionId, dashboardUrl)) return
            _hostResourcePressure.value = status.hostResourcePressure()
            recordDashboardStatusIfChanged(connectionId, status, session)
            _serverChatDisplaySettings.value = chatDisplaySettings
            // Gateway chat shares the voice probe's dashboard checks; it has
            // no audio-route requirement.
            updateGatewayAvailability(
                if (authed) GatewayAvailability.Ready else GatewayAvailability.SignInRequired,
            )
            val availability = when {
                !authed -> StandardVoiceAvailability.SignInRequired
                audioRoutesPresent -> StandardVoiceAvailability.Ready
                else -> StandardVoiceAvailability.Unsupported
            }
            _standardVoiceAvailability.value = availability
            _standardAudioApiReachable.value = availability == StandardVoiceAvailability.Ready
            if (availability == StandardVoiceAvailability.SignInRequired) {
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Voice,
                    severity = DiagnosticSeverity.Warning,
                    title = ctx.getString(R.string.conn_status_voice_signin),
                    detail = "The encrypted dashboard session could not be reused on this " +
                        "trusted route; open Manage to refresh the session",
                    url = dashboardUrl,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!ownsDashboardProbe(connectionId, dashboardUrl)) return
            // Defense-in-depth: this runs in a viewModelScope (Main) coroutine,
            // so an unexpected throw from any probe sub-call would crash the
            // app (see the currentSession() stale-connection crash). A probe
            // failure must only degrade the UI, never be fatal.
            android.util.Log.w("ConnectionVM", "probeStandardVoice failed: ${e.message}")
            recordDashboardGatewayFailure(
                dashboardUrl = dashboardUrl,
                detail = "Dashboard status probe failed (${e.javaClass.simpleName}).",
            )
            _standardVoiceAvailability.value = StandardVoiceAvailability.Unreachable
            _standardAudioApiReachable.value = false
            _hostResourcePressure.value = HostResourcePressureStatus()
            _serverChatDisplaySettings.value = null
            updateGatewayAvailability(GatewayAvailability.Unreachable)
        } finally {
            client.shutdown()
        }
    }

    private fun ownsDashboardProbe(connectionId: String, dashboardUrl: String): Boolean {
        return isCurrentDashboardProbe(
            requestConnectionId = connectionId,
            requestDashboardUrl = dashboardUrl,
            activeConnectionId = connectionStore.activeConnectionId.value,
            activeDashboardUrl = activeDashboardUrl(),
        )
    }

    private var topologyConnectionId: String? = null
    private var topologyGatewayMode: String? = null
    private var topologyProfiles: List<String> = emptyList()

    fun selectedProfileUsesIsolatedApiRoute(): Boolean {
        val profile = profileController.selectedProfile.value ?: return false
        if (profile.hasIsolatedApi) return true
        val activeConnectionId = connectionStore.activeConnectionId.value
        val persisted = connectionStore.connections.value
            .firstOrNull { it.id == activeConnectionId }
            ?.dashboardLastStatus
        val liveTopology = topologyConnectionId == activeConnectionId
        val mode = if (liveTopology) topologyGatewayMode else persisted?.gatewayMode
        val profiles = if (liveTopology) topologyProfiles else persisted?.servedProfiles.orEmpty()
        return mode.equals("multiplex", ignoreCase = true) && profile.name in profiles
    }

    /** Keep chat routing synchronized with the latest public dashboard topology. */
    private suspend fun updateDashboardTopology(connectionId: String, status: DashboardStatus?) {
        val nextMode = status?.gatewayMode
        val nextProfiles = status?.multiplexServedProfiles().orEmpty()
        val changed = topologyConnectionId != connectionId ||
            topologyGatewayMode != nextMode ||
            topologyProfiles != nextProfiles
        topologyConnectionId = connectionId
        topologyGatewayMode = nextMode
        topologyProfiles = nextProfiles
        if (changed && connectionStore.activeConnectionId.value == connectionId) {
            rebuildChatApiClient()
        }
    }

    private suspend fun loadChatDisplaySettings(
        client: DashboardApiClient,
    ): DashboardChatDisplaySettings? = client.getChatDisplaySettings().getOrNull()

    /**
     * [recordDashboardStatus] persists to the ConnectionStore; the voice probe
     * runs on every health cycle, so gate the write on a material change to
     * avoid chatty DataStore commits that would only refresh a timestamp.
     */
    private fun recordDashboardStatusIfChanged(
        connectionId: String,
        status: DashboardStatus?,
        session: DashboardAuthSession?,
    ) {
        val previous = connectionStore.connections.value
            .firstOrNull { it.id == connectionId }
            ?.dashboardLastStatus
        val reachable = status != null
        val materiallySame = previous != null &&
            previous.reachable == reachable &&
            previous.authRequired == status?.authRequired &&
            previous.authenticated == session?.authenticated &&
            previous.gatewayMode == status?.gatewayMode &&
            previous.servedProfiles == status?.multiplexServedProfiles().orEmpty() &&
            previous.profiles == status?.profiles.orEmpty()
        if (!materiallySame) {
            recordDashboardStatus(status = status, session = session, reachable = reachable)
        }
    }

    /**
     * Run a single relay /health probe and update [relayServerHealth].
     * Uses the existing [RelayHttpClient.probeHealth] path (unauthenticated,
     * 3s timeout, no impact on the rate limiter). Distinct from
     * [testRelayReachable] which is the user-facing Save & Test action.
     */
    private suspend fun probeRelayHealth(force: Boolean = false) {
        if (isDemoMode.value) {
            // Demo mode is offline — never probe the relay.
            _relayServerHealth.value = HealthStatus.Unknown
            return
        }
        if (!force && !activeRelayConfiguredSnapshot()) {
            _relayServerHealth.value = HealthStatus.Unknown
            return
        }
        val url = effectiveRelayUrlSnapshot()
        if (url.isBlank()) {
            _relayServerHealth.value = HealthStatus.Unknown
            return
        }
        val result = relayHttpClient.probeHealth(url, logSuccess = false)
        _relayServerHealth.value = if (result.isSuccess) {
            HealthStatus.Reachable
        } else {
            HealthStatus.Unreachable
        }
    }

    suspend fun verifyRelayForVoice(): Result<Unit> {
        if (!activeRelayConfiguredSnapshot()) {
            _relayServerHealth.value = HealthStatus.Unknown
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Warning,
                title = ctx.getString(R.string.conn_status_voice_blocked),
                detail = "Relay is not configured for this connection",
            )
            return Result.failure(IllegalStateException("Relay is not configured for this connection"))
        }
        val url = effectiveRelayUrlSnapshot()
        if (url.isBlank()) {
            _relayServerHealth.value = HealthStatus.Unknown
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Error,
                title = ctx.getString(R.string.conn_status_voice_blocked),
                detail = "Relay URL is not configured",
            )
            return Result.failure(IllegalStateException("Relay URL is not configured"))
        }

        _relayServerHealth.value = HealthStatus.Probing
        DiagnosticsLog.record(
            category = DiagnosticCategory.Voice,
            severity = DiagnosticSeverity.Info,
            title = ctx.getString(R.string.conn_status_checking_relay_voice),
            url = url,
        )
        val result = relayHttpClient.probeHealth(url)
        _relayServerHealth.value = if (result.isSuccess) {
            HealthStatus.Reachable
        } else {
            HealthStatus.Unreachable
        }
        return result.map { Unit }
    }

    // --- Unified pairing apply ----------------------------------------------
    //
    // Single entry point for "user just confirmed a scanned QR + chose a TTL".
    // Used by both [com.hermesandroid.relay.ui.components.ConnectionWizard]
    // (the new shared wizard) and any other surface that wants to apply a
    // pairing payload without duplicating the apply-API-URL/apply-relay-URL/
    // apply-grants/apply-TTL/wipe-pin/connect dance.
    //
    // The payload's relay block is optional — when null, only the API server
    // side is configured (matches the legacy "API-only" QR flow).
    fun applyPairingPayload(
        payload: com.hermesandroid.relay.ui.components.HermesPairingPayload,
        ttlSeconds: Long,
        preserveStandardConfig: Boolean = false,
    ) {
        pendingConnectionDraft?.pairingPayload = payload
        // SYNCHRONOUS RESET — must happen before this function returns so any
        // wizard / verify watcher that observes authState immediately after
        // sees Unpaired, not a stale Paired(token) from a prior install.
        // applyServerIssuedCodeAndReset writes _authState.value synchronously,
        // setPendingGrants/setPendingTtlSeconds are also sync. Putting these
        // inside the coroutine below let the wizard race ahead and trip
        // onComplete() against the stale Paired before the new pair started.
        payload.relay?.let { relay ->
            authManager.applyServerIssuedCodeAndReset(
                code = relay.code,
                relayUrl = relay.url,
            )
            authManager.setPendingGrants(relay.grants)
        }
        authManager.setPendingTtlSeconds(ttlSeconds)
        val existingStandardRoutes = if (preserveStandardConfig) {
            activeConnection.value?.routeCandidates.orEmpty()
        } else {
            emptyList()
        }
        val endpointsToPersist = if (preserveStandardConfig) {
            mergeRelayTransportIntoStandardRoutes(
                standardRoutes = existingStandardRoutes,
                relayRoutes = payload.endpoints.orEmpty(),
            )
        } else {
            payload.endpoints.orEmpty()
        }
        // General pairing stages the QR candidates for auth.ok persistence.
        // Relay-only pairing deliberately leaves AuthManager's pending
        // standard endpoints untouched; the merged connection routes are
        // pre-persisted below without replacing an in-flight standard setup.
        if (!preserveStandardConfig) {
            authManager.setPendingEndpoints(payload.endpoints)
        }

        viewModelScope.launch {
            // A connection-scoped Relay add must not replace the already-saved
            // Dashboard/API identity. New-connection and general QR flows keep
            // the historical behavior of applying the QR's standard surface.
            if (!preserveStandardConfig) {
                updateApiServerUrl(payload.serverUrl)
                if (payload.key.isNotBlank()) {
                    updateApiKey(payload.key)
                }
            }

            // Relay side — only when the QR carried a relay block.
            payload.relay?.let { relay ->
                if (preserveStandardConfig) {
                    // updateRelayUrl() rebuilds standard candidates from the
                    // current API URL. Relay-only pairing must not do that.
                    _relayUrl.value = relay.url.trim()
                    _relayServerHealth.value = HealthStatus.Probing
                    getApplication<Application>().relayDataStore.edit { preferences ->
                        preferences[KEY_RELAY_URL] = relay.url.trim()
                    }
                } else {
                    updateRelayUrl(relay.url)
                }
                if (relay.url.startsWith("ws://")) {
                    setInsecureMode(true)
                }
            }

            // Persist route candidates before the first WSS attempt. On a
            // no-LAN pair, waiting until auth.ok means the socket tries the
            // LAN relay URL from the QR, never receives auth.ok, and therefore
            // never stores the Tailscale fallback it needed to connect.
            endpointsToPersist.takeIf { it.isNotEmpty() }?.let { endpoints ->
                try {
                    val ctx = getApplication<Application>()
                    val deviceId = authManager.getOrCreateDeviceId()
                    PairingPreferences.setDeviceEndpoints(ctx, deviceId, endpoints)
                    android.util.Log.i(
                        "ConnectionVM",
                        "applyPairingPayload: pre-persisted ${endpoints.size} endpoint(s) " +
                            "for device=$deviceId roles=${endpoints.map { it.role }}"
                    )
                } catch (e: Exception) {
                    android.util.Log.w(
                        "ConnectionVM",
                        "applyPairingPayload: endpoint pre-persist failed; " +
                            "initial connect will use relay.url (${e.message})"
                    )
                }
            }

            // ADR 24 — auto-stamp / clear PairingPreferences.insecureReason
            // based on the resolved endpoint's role so the Transport Security
            // badge reads correctly even when the user paired from a plain
            // LAN QR directly (and thus never had to toggle the insecure-ack
            // dialog that would otherwise be the only writer of this key).
            //
            //  - Secure (wss/https) → clear any stale stored reason so a
            //    post-Tailscale-upgrade connection doesn't keep showing
            //    "Insecure (LAN)" from a prior plain-LAN pair.
            //  - Plain + role=lan       → stamp "lan_only"
            //  - Plain + role=tailscale → stamp "tailscale_vpn" (rare — usually
            //    wss on Tailscale, but possible)
            //  - Plain + role=public / other / absent → leave blank; the user
            //    should consciously ack via the insecure dialog for those.
            //
            // Only overwrite when the stored reason is currently blank so we
            // never clobber a user-selected choice.
            run {
                val ctx = getApplication<Application>()
                val relayUrl = payload.relay?.url
                val isSecure = relayUrl?.let {
                    it.startsWith("wss://") || it.startsWith("https://")
                } ?: false
                if (isSecure) {
                    // Clear any stale "Insecure (LAN)" stamp left over from
                    // a prior plain pair on the same Connection.
                    if (insecureReason.value.isNotBlank()) {
                        PairingPreferences.setInsecureReason(ctx, "")
                    }
                } else if (relayUrl != null && insecureReason.value.isBlank()) {
                    // Find the candidate whose relay.url matches what we're
                    // about to connect to; fall back to the first candidate
                    // (priority-0) if the payload has endpoints but none
                    // match exactly.
                    val matched = payload.endpoints?.firstOrNull {
                        it.relay?.url == relayUrl
                    } ?: payload.endpoints?.firstOrNull()
                    val autoReason = when (matched?.role?.lowercase()) {
                        "lan" -> "lan_only"
                        "tailscale" -> "tailscale_vpn"
                        else -> null // public / unknown / absent → let user ack
                    }
                    if (autoReason != null) {
                        PairingPreferences.setInsecureReason(ctx, autoReason)
                    }
                }
            }

            // Mirror the just-applied URLs into the active Connection's
            // store entry. [updateApiServerUrl] / [updateRelayUrl] above
            // only touch the APP-WIDE flows (_apiServerUrl, _relayUrl) +
            // the legacy relayDataStore keys — they do NOT write
            // Connection.apiServerUrl / Connection.relayUrl in the
            // ConnectionStore. Without this mirror:
            //   - connections list renders "New connection…" + blank
            //     endpoints forever (the rename-on-pair guard at the
            //     markPaired site checks current.apiServerUrl.isNotBlank
            //     and fails silently since the per-Connection field is
            //     still "");
            //   - a subsequent switch-away + switch-back reads
            //     Connection.apiServerUrl back into _apiServerUrl (step 8
            //     of ConnectionSwitchCoordinator) and clobbers the live
            //     URL with an empty string, breaking chat.
            // Must happen BEFORE connectRelay below so the auth.ok that
            // lands from that handshake sees populated URLs when the
            // pair-success watcher reconciles.
            val activeId = connectionStore.activeConnectionId.value
                .takeIf { pendingConnectionDraft == null }
            if (activeId != null) {
                val current = connectionStore.connections.value
                    .firstOrNull { it.id == activeId }
                if (current != null) {
                    val newRelayUrl = payload.relay?.url ?: current.relayUrl
                    if (preserveStandardConfig) {
                        val preserved = preserveStandardConnectionWhileApplyingRelay(
                            current = current,
                            relayUrl = newRelayUrl,
                            relayRoutes = payload.endpoints.orEmpty(),
                        )
                        if (preserved != current) {
                            connectionStore.updateConnection(preserved)
                        }
                    } else {
                        val payloadDashboardUrl = payload.dashboardUrl
                            ?.trim()
                            ?.trimEnd('/')
                            ?.takeIf { it.isNotBlank() }
                        val newDashboardUrl = payloadDashboardUrl
                            ?: if (
                                Connection.isAutoManagedDashboardUrl(current.dashboardUrl, current.apiServerUrl)
                            ) {
                                Connection.deriveDefaultDashboardUrl(payload.serverUrl)
                            } else {
                                current.dashboardUrl
                            }
                        val newRouteCandidates = Connection.reconcileDashboardRoutes(
                            dashboardUrl = newDashboardUrl,
                            candidates = payload.endpoints.orEmpty(),
                        )
                        val needsUpdate = current.apiServerUrl != payload.serverUrl ||
                            current.relayUrl != newRelayUrl ||
                            current.dashboardUrl != newDashboardUrl ||
                            current.routeCandidates != newRouteCandidates
                        if (needsUpdate) {
                            connectionStore.updateConnection(
                                current.copy(
                                    apiServerUrl = payload.serverUrl,
                                    relayUrl = newRelayUrl,
                                    dashboardUrl = newDashboardUrl,
                                    authenticatedDashboardOrigin = current.authenticatedDashboardOrigin
                                        ?.takeIf {
                                            it.trimEnd('/').equals(
                                                newDashboardUrl?.trimEnd('/'),
                                                ignoreCase = true,
                                            )
                                        },
                                    routeCandidates = newRouteCandidates,
                                    preferredRouteRole = current.preferredRouteRole
                                        ?.takeIf { preferred ->
                                            payload.endpoints.orEmpty().any {
                                                it.role.equals(preferred, ignoreCase = true)
                                            }
                                        },
                                )
                            )
                        }
                    }
                }
            }

            // Kick the WSS handshake now if we have a relay. AuthManager is
            // holding a fresh server-issued code so the pair-context gate on
            // connectRelay will let it through.
            payload.relay?.let { relay ->
                android.util.Log.i(
                    "ConnectionVM",
                    "applyPairingPayload: disconnecting old relay + connecting to ${relay.url}"
                )
                disconnectRelay()
                if (preserveStandardConfig) {
                    // connectRelay(url) also rebuilds standard candidates.
                    connectRelayInternal(relay.url)
                } else {
                    connectRelay(relay.url)
                }
            } ?: android.util.Log.w(
                "ConnectionVM",
                "applyPairingPayload: NO relay block in QR — relay/session will NOT pair"
            )

            // Fresh probe so the badges update without waiting for the next
            // periodic tick.
            revalidate()
        }
    }

    // --- API Server methods ---

    fun updateApiServerUrl(url: String) {
        // Same bare-host normalization as the wizard: `192.168.1.10`
        // becomes `http://192.168.1.10:8642`; explicit schemes pass verbatim.
        val trimmed = Connection.normalizeApiUrlInput(url)
        val previousApiUrl = _apiServerUrl.value
        val previousRelayUrl = _relayUrl.value
        val derivedRelayUrl = RelayUrlDeriver.deriveFromApiUrl(trimmed)
        val refreshedRelayUrl = derivedRelayUrl?.takeIf {
            RelayUrlDeriver.isAutoManagedRelayUrl(previousRelayUrl, previousApiUrl)
        }

        _apiServerUrl.value = trimmed
        if (refreshedRelayUrl != null) {
            _relayUrl.value = refreshedRelayUrl
            _relayServerHealth.value = if (activeRelayConfiguredSnapshot()) {
                HealthStatus.Probing
            } else {
                HealthStatus.Unknown
            }
        }
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_API_SERVER_URL] = trimmed
                if (refreshedRelayUrl != null) {
                    preferences[KEY_RELAY_URL] = refreshedRelayUrl
                }
            }
            persistActiveConnectionUrls(
                apiServerUrl = trimmed,
                relayUrl = if (refreshedRelayUrl != null) {
                    refreshedRelayUrl
                } else {
                    _relayUrl.value
                },
                routeCandidates = mergedRouteCandidates(
                    apiServerUrl = trimmed,
                    relayUrl = refreshedRelayUrl ?: _relayUrl.value,
                ),
            )
            connectionManager.refreshActiveEndpoint()
            rebuildApiClient()
            if (refreshedRelayUrl != null) {
                probeRelayHealth()
            }
        }
    }

    data class StandardApiSetupResult(
        val ok: Boolean,
        val message: String,
        val apiReachable: Boolean = ok,
        val dashboardReachable: Boolean? = null,
        val dashboardSignInRequired: Boolean = false,
        val dashboardAuthenticated: Boolean? = null,
        /** Standard (dashboard-surface) voice readiness, probed in the same pass. */
        val voiceAvailability: StandardVoiceAvailability = StandardVoiceAvailability.Unknown,
        val relayPaired: Boolean = false,
        /**
         * True when the saved connection has at least one fallback route
         * (priority > 0 — Tailscale, public, custom VPN). Drives the setup
         * result card's "Remote" readiness line so a LAN-only connection is
         * called out at the moment of maximum user attention, not discovered
         * the first time the phone leaves home.
         */
        val remoteRouteConfigured: Boolean = false,
    )

    /** Dashboard-first setup probe used by the normal connection wizard. */
    data class DashboardSetupResult(
        val ok: Boolean,
        val dashboardUrl: String,
        val message: String,
        val signInRequired: Boolean = false,
        val authenticated: Boolean = false,
        val voiceAvailability: StandardVoiceAvailability = StandardVoiceAvailability.Unknown,
    )

    /**
     * Inspect an upstream Hermes Dashboard/Gateway without persisting it.
     * API and Relay are deliberately absent: they are optional surfaces and
     * belong to the wizard's Advanced path.
     */
    fun probeHermesDashboard(
        address: String,
        onResult: (DashboardSetupResult) -> Unit,
    ) {
        val dashboardUrl = Connection.normalizeApiUrlInput(
            address,
            defaultPort = Connection.DEFAULT_DASHBOARD_PORT,
        )
        if (dashboardUrl.isBlank()) {
            onResult(
                DashboardSetupResult(
                    ok = false,
                    dashboardUrl = "",
                    message = "Enter a Hermes address",
                ),
            )
            return
        }
        viewModelScope.launch {
            val client = upstreamTransport.dashboardClientForActive(dashboardUrl)
            try {
                val statusResult = client.getStatus()
                val status = statusResult.getOrNull()
                if (status == null) {
                    onResult(
                        DashboardSetupResult(
                            ok = false,
                            dashboardUrl = dashboardUrl,
                            message = statusResult.exceptionOrNull()?.message
                                ?: "Hermes was not found at this address",
                        ),
                    )
                    return@launch
                }
                val session = if (status.authRequired) {
                    client.currentSession().getOrNull()
                } else {
                    null
                }
                val authenticated = !status.authRequired || session?.authenticated == true
                val voice = when {
                    !authenticated -> StandardVoiceAvailability.SignInRequired
                    client.audioRoutesPresent() -> StandardVoiceAvailability.Ready
                    else -> StandardVoiceAvailability.Unsupported
                }
                onResult(
                    DashboardSetupResult(
                        ok = true,
                        dashboardUrl = dashboardUrl,
                        message = status.message ?: "Hermes is ready",
                        signInRequired = status.authRequired && !authenticated,
                        authenticated = authenticated,
                        voiceAvailability = voice,
                    ),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(
                    DashboardSetupResult(
                        ok = false,
                        dashboardUrl = dashboardUrl,
                        message = e.message ?: "Hermes was not found at this address",
                    ),
                )
            } finally {
                client.shutdown()
            }
        }
    }

    /** Persist a Dashboard/Gateway-only standard Hermes connection. */
    fun saveDashboardConnection(
        dashboardUrl: String,
        discoveredHostname: String? = null,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val normalized = Connection.normalizeApiUrlInput(
            dashboardUrl,
            defaultPort = Connection.DEFAULT_DASHBOARD_PORT,
        )
        if (normalized.isBlank()) {
            onComplete(Result.failure(IllegalArgumentException("Hermes address is required")))
            return
        }
        viewModelScope.launch {
            runCatching {
                ensureActiveConnectionForSetup()
                val activeId = connectionStore.activeConnectionId.value
                    ?: error("No active connection")
                val current = connectionStore.connections.value
                    .firstOrNull { it.id == activeId }
                    ?: error("Active connection is missing")
                val primaryHost = Connection.extractDefaultLabel(normalized)
                val nextLabel = if (current.label == PLACEHOLDER_LABEL || current.label.isBlank()) {
                    discoveredHostname?.trim()?.takeIf { it.isNotBlank() } ?: primaryHost
                } else {
                    Connection.chooseDiscoveredLabel(
                        currentLabel = current.label,
                        primaryHost = primaryHost,
                        discoveredHostname = discoveredHostname,
                    )
                }
                val next = current.copy(
                        label = nextLabel,
                        dashboardUrl = normalized,
                        authenticatedDashboardOrigin = current.authenticatedDashboardOrigin
                            ?.takeIf {
                                it.trimEnd('/').equals(normalized.trimEnd('/'), ignoreCase = true)
                            },
                        routeCandidates = Connection.reconcileDashboardRoutes(
                            dashboardUrl = normalized,
                            candidates = current.routeCandidates.ifEmpty {
                                listOfNotNull(
                                    Connection.endpointCandidateFromDashboardUrl(
                                        role = Connection.inferRouteRole(normalized),
                                        priority = 0,
                                        dashboardUrl = normalized,
                                        apiServerUrl = current.apiServerUrl
                                            .takeIf { it.isNotBlank() },
                                        relayUrl = current.relayUrl
                                            .takeIf { it.isNotBlank() },
                                    ),
                                )
                            },
                        ),
                    )
                connectionStore.updateConnection(next)
                if (dashboardCredentialsMustBeRetired(current, next)) {
                    upstreamTransport.clearDashboardAuthentication(activeId)
                }
                probeStandardVoice()
            }.also(onComplete)
        }
    }

    /**
     * Validate and probe an explicit Dashboard/Gateway address before saving
     * it. Moving to a different origin clears connection-scoped Dashboard
     * cookies and native bearer tokens so credentials cannot bleed across
     * self-hosted deployments.
     */
    fun updateDashboardAddress(
        url: String,
        onResult: (String?) -> Unit,
    ) {
        val normalized = normalizeDashboardAddressForEdit(url)
        if (normalized == null) {
            onResult("Enter an http:// or https:// Hermes Dashboard address")
            return
        }
        viewModelScope.launch {
            val activeId = connectionStore.activeConnectionId.value
            val current = connectionStore.connections.value.firstOrNull { it.id == activeId }
            if (activeId == null || current == null) {
                onResult("No active connection")
                return@launch
            }
            val probe = probeDashboardAddress(normalized)
            val failure = probe.exceptionOrNull()
            if (failure != null) {
                onResult(failure.message ?: "Hermes was not found at this address")
                return@launch
            }
            val originChanged = !sameDashboardBase(current.resolvedDashboardUrl, normalized)
            val next = withExplicitDashboardAddress(current, normalized)
            connectionStore.updateConnection(next)
            if (originChanged) {
                withContext(Dispatchers.IO) {
                    upstreamTransport.clearDashboardAuthentication(activeId)
                }
            }
            val (status, session) = probe.getOrThrow()
            persistDashboardProbeStatus(activeId, status, session)
            probeStandardVoice()
            onResult(null)
        }
    }

    /** Persist and activate a successfully configured Add-connection draft exactly once. */
    suspend fun commitConnectionDraft(connectionId: String): Boolean = addConnectionMutex.withLock {
        val draft = pendingConnectionDraft?.takeIf { it.id == connectionId } ?: return@withLock true
        val payload = draft.pairingPayload
        val apiUrl = payload?.serverUrl?.trim().orEmpty().ifBlank { _apiServerUrl.value.trim() }
        val relayUrl = payload?.relay?.url?.trim().orEmpty().ifBlank { _relayUrl.value.trim() }
        val dashboardUrl = payload?.dashboardUrl?.trim()?.trimEnd('/')
            ?: Connection.deriveDefaultDashboardUrl(apiUrl)
        val routes = Connection.reconcileDashboardRoutes(
            dashboardUrl = dashboardUrl,
            candidates = payload?.endpoints.orEmpty().ifEmpty {
                Connection.buildRouteCandidates(apiUrl, relayUrl)
            },
        )
        val paired = authManager.authState.value as? AuthState.Paired
        val pairedSession = authManager.currentPairedSession.value
        val connection = Connection(
            id = connectionId,
            label = apiUrl.takeIf(String::isNotBlank)
                ?.let(Connection::extractDefaultLabel)
                ?: dashboardUrl?.let(Connection::extractDefaultLabel)
                ?: "Hermes",
            apiServerUrl = apiUrl,
            relayUrl = relayUrl,
            tokenStoreKey = Connection.buildTokenStoreKey(connectionId),
            dashboardUrl = dashboardUrl,
            routeCandidates = routes,
            pairedAt = paired?.let { System.currentTimeMillis() },
            transportHint = pairedSession?.transportHint,
            expiresAt = pairedSession?.expiresAt?.let { it * 1000L },
        )
        connectionStore.addConnection(connection)
        switchConnection(connectionId).join()
        pendingConnectionDraft = null
        _connectionDraftId.value = null
        true
    }

    /** Re-check only the active Dashboard/Gateway surface and its auth state. */
    fun recheckDashboard(onResult: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val dashboardUrl = activeDashboardUrl()
            if (dashboardUrl.isNullOrBlank()) {
                onResult("No Dashboard address is configured")
                return@launch
            }
            val result = probeDashboardAddress(dashboardUrl)
            if (result.isFailure) {
                persistDashboardProbeFailure(
                    connectionId = connectionStore.activeConnectionId.value,
                    message = result.exceptionOrNull()?.message ?: "Dashboard is unreachable",
                )
                probeStandardVoice()
                onResult(result.exceptionOrNull()?.message ?: "Dashboard is unreachable")
                return@launch
            }
            val connectionId = connectionStore.activeConnectionId.value
            val (status, session) = result.getOrThrow()
            if (connectionId != null) persistDashboardProbeStatus(connectionId, status, session)
            probeStandardVoice()
            onResult(null)
        }
    }

    private suspend fun probeDashboardAddress(
        dashboardUrl: String,
    ): Result<Pair<DashboardStatus, DashboardAuthSession?>> {
        val client = upstreamTransport.dashboardClientForActive(dashboardUrl)
        return try {
            withTimeoutOrNull(8_000L) {
                val status = client.getStatus().getOrElse { throw it }
                val session = if (status.authRequired) {
                    client.currentSession().getOrNull()
                } else {
                    null
                }
                Result.success(status to session)
            } ?: Result.failure(java.net.SocketTimeoutException("Dashboard check timed out"))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            client.shutdown()
        }
    }

    private suspend fun persistDashboardProbeStatus(
        connectionId: String,
        status: DashboardStatus,
        session: DashboardAuthSession?,
    ) {
        connectionStore.setDashboardStatus(
            connectionId,
            com.hermesandroid.relay.data.DashboardConnectionStatus(
                checkedAtMillis = System.currentTimeMillis(),
                reachable = true,
                authRequired = status.authRequired,
                authProviders = status.authProviders,
                authenticated = if (status.authRequired) session?.authenticated else true,
                authProvider = session?.provider,
                message = status.message,
                gatewayMode = status.gatewayMode,
                servedProfiles = status.multiplexServedProfiles(),
                profiles = status.profiles,
            ),
        )
    }

    private suspend fun persistDashboardProbeFailure(
        connectionId: String?,
        message: String,
    ) {
        if (connectionId == null) return
        val previous = connectionStore.connections.value
            .firstOrNull { it.id == connectionId }
            ?.dashboardLastStatus
        connectionStore.setDashboardStatus(
            connectionId,
            (previous ?: com.hermesandroid.relay.data.DashboardConnectionStatus()).copy(
                checkedAtMillis = System.currentTimeMillis(),
                reachable = false,
                message = message,
            ),
        )
    }

    fun recordDashboardStatus(
        status: DashboardStatus?,
        session: DashboardAuthSession? = null,
        reachable: Boolean = status != null,
        gatewayTicketAvailable: Boolean? = null,
        message: String? = status?.message,
    ) {
        val connectionId = connectionStore.activeConnectionId.value ?: return
        viewModelScope.launch {
            connectionStore.setDashboardStatus(
                connectionId = connectionId,
                status = com.hermesandroid.relay.data.DashboardConnectionStatus(
                    checkedAtMillis = System.currentTimeMillis(),
                    reachable = reachable,
                    authRequired = status?.authRequired,
                    authProviders = status?.authProviders.orEmpty(),
                    authenticated = session?.authenticated,
                    authProvider = session?.provider,
                    gatewayTicketAvailable = gatewayTicketAvailable,
                    message = message,
                    gatewayMode = status?.gatewayMode,
                    servedProfiles = status?.multiplexServedProfiles().orEmpty(),
                    profiles = status?.profiles.orEmpty(),
                ),
            )
        }
    }

    /**
     * Re-run the standard-voice probe outside the periodic health cycle.
     * Call after dashboard sign-in/sign-out so the mic gate and Voice
     * Settings status react immediately instead of on the next probe tick.
     */
    fun refreshStandardVoice() {
        viewModelScope.launch { probeStandardVoice() }
    }

    fun clearDashboardSession(onComplete: (() -> Unit)? = null) {
        val connectionId = connectionStore.activeConnectionId.value ?: return
        val active = connectionStore.connections.value.firstOrNull { it.id == connectionId }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                upstreamTransport.clearDashboardAuthentication(connectionId)
            }
            connectionStore.setDashboardStatus(
                connectionId = connectionId,
                status = com.hermesandroid.relay.data.DashboardConnectionStatus(
                    checkedAtMillis = System.currentTimeMillis(),
                    reachable = active?.dashboardLastStatus?.reachable ?: false,
                    authRequired = active?.dashboardLastStatus?.authRequired,
                    authProviders = active?.dashboardLastStatus?.authProviders.orEmpty(),
                    authenticated = false,
                    authProvider = null,
                    gatewayTicketAvailable = false,
                    message = "Dashboard session cleared",
                    gatewayMode = active?.dashboardLastStatus?.gatewayMode,
                    servedProfiles = active?.dashboardLastStatus?.servedProfiles.orEmpty(),
                    profiles = active?.dashboardLastStatus?.profiles.orEmpty(),
                ),
            )
            probeStandardVoice()
            onComplete?.invoke()
        }
    }

    /**
     * Persist the standard Hermes API/dashboard connection without requiring
     * a Relay pairing session. This is the first-run path for Chat + Manage:
     * save the API URL/key, refresh the derived dashboard URL, then verify
     * both `/health` and `/api/sessions` so missing API keys surface clearly.
     */
    fun saveStandardApiConnection(
        apiUrl: String,
        apiKey: String,
        tailscaleApiUrl: String = "",
        dashboardUrl: String = "",
        routeCandidatesOverride: List<EndpointCandidate>? = null,
        onResult: (StandardApiSetupResult) -> Unit,
    ) {
        val normalizedApiKey = runCatching {
            normalizeCredentialForHeader(apiKey, "API credential")
        }.getOrElse {
            onResult(
                StandardApiSetupResult(
                    ok = false,
                    message = it.message ?: "Invalid API credential",
                    apiReachable = false,
                    relayPaired = authState.value is AuthState.Paired,
                ),
            )
            return
        }
        // Bare host/IP input gets http:// and the surface's default port —
        // typing `192.168.1.10` or a Tailscale `100.x.y.z` without a scheme
        // is the common case and used to either block the wizard or silently
        // drop the route. Explicitly-schemed URLs pass through verbatim.
        val trimmedApiUrl = Connection.normalizeApiUrlInput(apiUrl)
        val trimmedTailscaleApiUrl = Connection.normalizeApiUrlInput(tailscaleApiUrl)
        val trimmedDashboardUrl = Connection.normalizeApiUrlInput(
            dashboardUrl,
            defaultPort = Connection.DEFAULT_DASHBOARD_PORT,
        )
        if (trimmedApiUrl.isBlank()) {
            onResult(
                StandardApiSetupResult(
                    ok = false,
                    message = "API server URL is required",
                    apiReachable = false,
                    relayPaired = authState.value is AuthState.Paired,
                ),
            )
            return
        }

        val previousApiUrl = _apiServerUrl.value
        val previousRelayUrl = _relayUrl.value
        val derivedRelayUrl = RelayUrlDeriver.deriveFromApiUrl(trimmedApiUrl)
        val refreshedRelayUrl = derivedRelayUrl?.takeIf {
            RelayUrlDeriver.isAutoManagedRelayUrl(previousRelayUrl, previousApiUrl)
        }

        _apiServerUrl.value = trimmedApiUrl
        val routeRelayUrl = refreshedRelayUrl ?: derivedRelayUrl ?: _relayUrl.value
        // No override → rebuild from the typed URLs but keep any stored
        // extra routes: the wizard does NOT pre-fill the Tailscale field, so
        // a blank field on a re-run means "unchanged", not "remove it".
        val routeCandidates = routeCandidatesOverride
            ?.takeIf { it.isNotEmpty() }
            ?.let { normalizeStandardRouteCandidates(it) }
            ?: mergedRouteCandidates(
                apiServerUrl = trimmedApiUrl,
                relayUrl = routeRelayUrl,
                extraApiUrls = listOfNotNull(
                    trimmedTailscaleApiUrl
                        .takeIf { it.isNotBlank() && !it.equals(trimmedApiUrl, ignoreCase = true) }
                        ?.let { "tailscale" to it },
                ),
            )
        if (refreshedRelayUrl != null) {
            _relayUrl.value = refreshedRelayUrl
            _relayServerHealth.value = if (activeRelayConfiguredSnapshot()) {
                HealthStatus.Probing
            } else {
                HealthStatus.Unknown
            }
        }

        viewModelScope.launch {
            ensureActiveConnectionForSetup(
                apiServerUrl = trimmedApiUrl,
                relayUrl = routeRelayUrl,
                routeCandidates = routeCandidates,
            )
            if (normalizedApiKey.isNotBlank()) {
                authManager.setApiKey(normalizedApiKey)
            }

            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_API_SERVER_URL] = trimmedApiUrl
                if (refreshedRelayUrl != null) {
                    preferences[KEY_RELAY_URL] = refreshedRelayUrl
                }
            }

            persistActiveConnectionUrls(
                apiServerUrl = trimmedApiUrl,
                relayUrl = refreshedRelayUrl ?: _relayUrl.value,
                dashboardUrlOverride = trimmedDashboardUrl,
                routeCandidates = routeCandidates,
            )

            val activeId = connectionStore.activeConnectionId.value
            val active = activeId?.let { id ->
                connectionStore.connections.value.firstOrNull { it.id == id }
            }
            if (
                active != null &&
                active.label == PLACEHOLDER_LABEL &&
                active.apiServerUrl.isNotBlank()
            ) {
                connectionStore.updateConnection(
                    active.copy(label = Connection.extractDefaultLabel(trimmedApiUrl)),
                )
            }

            connectionManager.refreshActiveEndpoint()
            rebuildApiClient()
            if (refreshedRelayUrl != null) {
                probeRelayHealth()
            }

            val client = _apiClient.value
            if (client == null) {
                _apiServerReachable.value = false
                _apiServerHealth.value = HealthStatus.Unknown
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Api,
                    severity = DiagnosticSeverity.Warning,
                    title = ctx.getString(R.string.conn_status_setup_skipped),
                    detail = ctx.getString(R.string.conn_detail_no_api_client),
                    url = effectiveApiServerUrlSnapshot(),
                )
                onResult(
                    StandardApiSetupResult(
                        ok = false,
                        message = ctx.getString(R.string.conn_detail_no_api_client),
                        apiReachable = false,
                        relayPaired = authState.value is AuthState.Paired,
                    ),
                )
                return@launch
            }

            val diagnosticApiUrl = effectiveApiServerUrlSnapshot()
            _apiServerHealth.value = HealthStatus.Probing
            DiagnosticsLog.record(
                category = DiagnosticCategory.Api,
                severity = DiagnosticSeverity.Info,
                title = ctx.getString(R.string.conn_status_testing_standard),
                operation = "Hermes API health check",
                configuredUrl = diagnosticApiUrl,
                requestUrl = "${diagnosticApiUrl.trimEnd('/')}/health",
            )

            val health = client.checkHealthDetailed()
            if (health is com.hermesandroid.relay.network.upstream.HealthCheckResult.Unhealthy) {
                _apiServerReachable.value = false
                _apiServerHealth.value = HealthStatus.Unreachable
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Api,
                    severity = DiagnosticSeverity.Error,
                    title = ctx.getString(R.string.conn_status_setup_health_failed),
                    detail = health.message,
                    operation = "Hermes API health check",
                    configuredUrl = diagnosticApiUrl,
                    requestUrl = "${diagnosticApiUrl.trimEnd('/')}/health",
                )
                onResult(
                    StandardApiSetupResult(
                        ok = false,
                        message = health.message,
                        apiReachable = false,
                        relayPaired = authState.value is AuthState.Paired,
                    ),
                )
                return@launch
            }

            val sessions = client.checkSessionsAuthDetailed()
            val reachable = sessions is com.hermesandroid.relay.network.upstream.HealthCheckResult.Healthy
            _apiServerReachable.value = reachable
            _apiServerHealth.value = if (reachable) HealthStatus.Reachable else HealthStatus.Unreachable
            var dashboardSignInRequired = false
            var dashboardReachable: Boolean? = null
            var dashboardAuthenticated: Boolean? = null
            var voiceAvailability = StandardVoiceAvailability.Unknown
            if (reachable) {
                val connectionId = connectionStore.activeConnectionId.value
                val dashboardUrlForProbe = connectionId?.let { id ->
                    connectionStore.connections.value
                        .firstOrNull { it.id == id }
                        ?.resolvedDashboardUrl
                        ?.takeIf { it.isNotBlank() }
                }
                if (connectionId != null && dashboardUrlForProbe != null) {
                    val dashboardClient =
                        upstreamTransport.dashboardClientFor(connectionId, dashboardUrlForProbe)
                    try {
                        val status = dashboardClient.getStatus().getOrNull()
                        val session = if (status?.authRequired == true) {
                            dashboardClient.currentSession().getOrNull()
                        } else {
                            null
                        }
                        dashboardSignInRequired =
                            status?.authRequired == true && session?.authenticated != true
                        dashboardReachable = status != null
                        dashboardAuthenticated = session?.authenticated
                        // Voice rides this same surface — settle availability in
                        // the same pass so the wizard's capability card and the
                        // mic gate are correct the moment setup completes.
                        voiceAvailability = when {
                            status == null -> StandardVoiceAvailability.Unreachable
                            dashboardSignInRequired -> StandardVoiceAvailability.SignInRequired
                            dashboardClient.audioRoutesPresent() -> StandardVoiceAvailability.Ready
                            else -> StandardVoiceAvailability.Unsupported
                        }
                        _standardVoiceAvailability.value = voiceAvailability
                        _standardAudioApiReachable.value =
                            voiceAvailability == StandardVoiceAvailability.Ready
                        recordDashboardStatus(
                            status = status,
                            session = session,
                            reachable = status != null,
                        )
                    } finally {
                        dashboardClient.shutdown()
                    }
                }
            }
            val message = when (sessions) {
                is com.hermesandroid.relay.network.upstream.HealthCheckResult.Healthy -> {
                    if (dashboardSignInRequired) {
                        "Connected to Hermes API. Dashboard sign-in required for Manage."
                    } else {
                        "Connected to Hermes API and sessions"
                    }
                }
                is com.hermesandroid.relay.network.upstream.HealthCheckResult.Unhealthy ->
                    sessions.message
            }
            DiagnosticsLog.record(
                category = DiagnosticCategory.Api,
                severity = if (reachable) DiagnosticSeverity.Info else DiagnosticSeverity.Error,
                title = if (reachable) ctx.getString(R.string.conn_status_hermes_ok) else ctx.getString(R.string.conn_status_auth_failed),
                detail = message,
                operation = "Hermes API sessions authentication check",
                configuredUrl = diagnosticApiUrl,
                requestUrl = "${diagnosticApiUrl.trimEnd('/')}/api/sessions",
            )
            onResult(
                StandardApiSetupResult(
                    ok = reachable,
                    message = message,
                    apiReachable = reachable,
                    dashboardReachable = dashboardReachable,
                    dashboardSignInRequired = dashboardSignInRequired,
                    dashboardAuthenticated = dashboardAuthenticated,
                    voiceAvailability = voiceAvailability,
                    relayPaired = authState.value is AuthState.Paired,
                    remoteRouteConfigured = routeCandidates.any { it.priority > 0 },
                ),
            )
        }
    }

    data class ApiVoiceSetupResult(
        val apiReachable: Boolean,
        val relayUrl: String?,
        val relayAutoDerived: Boolean,
        val voiceConfigReachable: Boolean,
        val voiceConfigError: String?,
        val voiceRoute: String,
    )

    /**
     * Persist API URL/key, resolve the optional Relay URL, then verify the
     * best available stable voice route. Standard Hermes audio is preferred;
     * Relay voice is only probed as a fallback when Relay is intentionally
     * configured for the active connection.
     */
    fun saveApiAndProbeVoice(
        apiUrl: String,
        apiKey: String,
        manualRelayUrlOverride: String?,
        onResult: (ApiVoiceSetupResult) -> Unit,
    ) {
        val normalizedApiKey = runCatching {
            normalizeCredentialForHeader(apiKey, "API credential")
        }.getOrElse {
            onResult(
                ApiVoiceSetupResult(
                    apiReachable = false,
                    relayUrl = null,
                    relayAutoDerived = false,
                    voiceConfigReachable = false,
                    voiceConfigError = it.message,
                    voiceRoute = "none",
                ),
            )
            return
        }
        val trimmedApiUrl = apiUrl.trim()
        val trimmedOverride = manualRelayUrlOverride?.trim().orEmpty()
        val derivedRelayUrl = RelayUrlDeriver.deriveFromApiUrl(trimmedApiUrl)
        val nextRelayUrl = trimmedOverride.ifBlank { derivedRelayUrl.orEmpty() }
        val usingAutoRelay = trimmedOverride.isBlank()
        val shouldProbeRelay = trimmedOverride.isNotBlank() || activeRelayConfiguredSnapshot()

        _apiServerUrl.value = trimmedApiUrl
        if (nextRelayUrl.isNotBlank()) {
            _relayUrl.value = nextRelayUrl
            _relayServerHealth.value = if (shouldProbeRelay) {
                HealthStatus.Probing
            } else {
                HealthStatus.Unknown
            }
        }

        viewModelScope.launch {
            if (normalizedApiKey.isNotBlank()) {
                authManager.setApiKey(normalizedApiKey)
            }
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_API_SERVER_URL] = trimmedApiUrl
                if (nextRelayUrl.isNotBlank()) {
                    preferences[KEY_RELAY_URL] = nextRelayUrl
                }
            }
            persistActiveConnectionUrls(
                apiServerUrl = trimmedApiUrl,
                relayUrl = nextRelayUrl,
                routeCandidates = mergedRouteCandidates(
                    apiServerUrl = trimmedApiUrl,
                    relayUrl = nextRelayUrl,
                ),
            )

            connectionManager.refreshActiveEndpoint()
            rebuildApiClient()
            val apiReachable = _apiServerReachable.value

            val standardVoiceResult = if (_standardAudioApiReachable.value) {
                Result.success(Unit)
            } else {
                Result.failure(
                    IllegalStateException(
                        when (_standardVoiceAvailability.value) {
                            StandardVoiceAvailability.SignInRequired ->
                                "Vanilla Hermes voice needs dashboard sign-in (Manage tab)"
                            StandardVoiceAvailability.Unsupported ->
                                "This Hermes build has no dashboard audio routes"
                            else -> "Vanilla Hermes voice is not available"
                        },
                    ),
                )
            }
            val voiceResult = if (standardVoiceResult.isSuccess) {
                standardVoiceResult
            } else if (nextRelayUrl.isNotBlank() && shouldProbeRelay) {
                RelayVoiceClient(
                    context = getApplication(),
                    okHttpClient = relayOkHttp,
                    relayUrlProvider = { nextRelayUrl },
                    sessionTokenProvider = {
                        (authManager.authState.value as? AuthState.Paired)?.token
                    },
                    apiBearerTokenProvider = { authManager.getApiKey() },
                    dashboardHttpClientProvider = ::dashboardHttpClientForRelayIngress,
                    dashboardIngressWebSocketRequestProvider = ::dashboardRelayRequestForIngress,
                ).getVoiceConfig()
            } else {
                standardVoiceResult
            }

            if (standardVoiceResult.isFailure && nextRelayUrl.isNotBlank() && shouldProbeRelay) {
                _relayServerHealth.value = if (voiceResult.isSuccess) {
                    HealthStatus.Reachable
                } else {
                    HealthStatus.Unreachable
                }
            }

            onResult(
                ApiVoiceSetupResult(
                    apiReachable = apiReachable,
                    relayUrl = nextRelayUrl.ifBlank { null },
                    relayAutoDerived = usingAutoRelay,
                    voiceConfigReachable = voiceResult.isSuccess,
                    voiceConfigError = voiceResult.exceptionOrNull()?.message,
                    voiceRoute = when {
                        standardVoiceResult.isSuccess -> "standard"
                        voiceResult.isSuccess -> "relay"
                        else -> "none"
                    },
                ),
            )
        }
    }

    fun updateApiKey(key: String) {
        viewModelScope.launch {
            runCatching {
                authManager.setApiKey(key)
                rebuildApiClient()
            }.onFailure {
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Api,
                    severity = DiagnosticSeverity.Warning,
                    title = "API credential was not saved",
                    detail = it.message,
                )
            }
        }
    }

    suspend fun getApiKey(): String? = authManager.getApiKey()

    suspend fun getProfileApiKey(profileName: String): String? =
        authManager.getProfileApiKey(profileName)

    fun selectedProfileUsesMultiplexApiKey(): Boolean {
        val selectedProfile = profileController.selectedProfile.value
        val activeConnectionId = connectionStore.activeConnectionId.value
        val topology = connectionStore.connections.value
            .firstOrNull { it.id == activeConnectionId }
            ?.dashboardLastStatus
        val liveTopology = topologyConnectionId == activeConnectionId
        return ProfileApiUrlResolver.usesMultiplexProfileKey(
            profileApiUrl = selectedProfile?.apiServerUrl,
            selectedProfileName = selectedProfile?.name,
            gatewayMode = if (liveTopology) topologyGatewayMode else topology?.gatewayMode,
            servedProfiles = if (liveTopology) topologyProfiles else topology?.servedProfiles.orEmpty(),
        )
    }

    fun updateProfileApiKey(
        profileName: String,
        key: String,
        onComplete: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching {
                authManager.setProfileApiKey(profileName, key)
                rebuildChatApiClient()
            }.onSuccess {
                onComplete(true)
            }.onFailure {
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Api,
                    severity = DiagnosticSeverity.Error,
                    title = ctx.getString(R.string.conn_info_profile_api_key_save_failed),
                    detail = it.message,
                )
                onComplete(false)
            }
        }
    }

    /**
     * The bearer key for client construction, WITHOUT paying the Keystore
     * decrypt when this connection is known key-less (the common local
     * setup). [AuthManager.apiKeyKnownAbsent] is a plain-prefs hint that
     * defaults to "unknown ⇒ wait", so keyed connections always take the
     * real [AuthManager.getApiKey] path. This is what keeps the API client
     * — and everything behind it: health, capabilities, chat history —
     * from queueing behind a multi-second StrongBox keyset marathon at
     * cold start (measured 15s on an S25 Ultra before this fast path).
     */
    private suspend fun apiKeyForClientBuild(): String =
        if (authManager.apiKeyKnownAbsent()) "" else authManager.getApiKey() ?: ""

    fun checkApiHealth() {
        viewModelScope.launch {
            val client = _apiClient.value
            _apiServerReachable.value = client?.checkHealth() == true
        }
    }

    fun testApiConnection(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val client = _apiClient.value
            if (client == null) {
                _apiServerReachable.value = false
                _apiServerHealth.value = HealthStatus.Unknown
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Api,
                    severity = DiagnosticSeverity.Warning,
                    title = ctx.getString(R.string.conn_status_api_test_skipped),
                    detail = ctx.getString(R.string.conn_detail_no_api_client),
                    url = effectiveApiServerUrlSnapshot(),
                )
                onResult(false, ctx.getString(R.string.conn_detail_no_api_client))
                return@launch
            }

            val diagnosticApiUrl = effectiveApiServerUrlSnapshot()
            _apiServerHealth.value = HealthStatus.Probing
            DiagnosticsLog.record(
                category = DiagnosticCategory.Api,
                severity = DiagnosticSeverity.Info,
                title = ctx.getString(R.string.conn_status_testing_api),
                operation = "Hermes API health check",
                configuredUrl = diagnosticApiUrl,
                requestUrl = "${diagnosticApiUrl.trimEnd('/')}/health",
            )
            val health = client.checkHealthDetailed()
            if (health is com.hermesandroid.relay.network.upstream.HealthCheckResult.Unhealthy) {
                _apiServerReachable.value = false
                _apiServerHealth.value = HealthStatus.Unreachable
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Api,
                    severity = DiagnosticSeverity.Error,
                    title = ctx.getString(R.string.conn_status_api_health_failed),
                    detail = health.message,
                    operation = "Hermes API health check",
                    configuredUrl = diagnosticApiUrl,
                    requestUrl = "${diagnosticApiUrl.trimEnd('/')}/health",
                )
                onResult(false, health.message)
                return@launch
            }

            val sessions = client.checkSessionsAuthDetailed()
            val reachable = sessions is com.hermesandroid.relay.network.upstream.HealthCheckResult.Healthy
            _apiServerReachable.value = reachable
            _apiServerHealth.value = if (reachable) HealthStatus.Reachable else HealthStatus.Unreachable
            val message = when (sessions) {
                is com.hermesandroid.relay.network.upstream.HealthCheckResult.Healthy ->
                    "Connection OK - health and sessions auth passed"
                is com.hermesandroid.relay.network.upstream.HealthCheckResult.Unhealthy ->
                    sessions.message
            }
            DiagnosticsLog.record(
                category = DiagnosticCategory.Api,
                severity = if (reachable) DiagnosticSeverity.Info else DiagnosticSeverity.Error,
                title = if (reachable) ctx.getString(R.string.conn_status_api_test_ok) else ctx.getString(R.string.conn_status_api_test_failed),
                detail = message,
                operation = "Hermes API sessions authentication check",
                configuredUrl = diagnosticApiUrl,
                requestUrl = "${diagnosticApiUrl.trimEnd('/')}/api/sessions",
            )
            onResult(reachable, message)
        }
    }

    private suspend fun rebuildApiClient() {
        val url = effectiveApiServerUrlSnapshot()
        val key = apiKeyForClientBuild()

        val oldClient = _apiClient.value

        if (url.isNotBlank()) {
            // Show Probing while the new client spins up so the badge has
            // a coherent in-flight pose instead of flashing the previous
            // result through.
            _apiServerHealth.value = HealthStatus.Probing
            val client = HermesApiClient(
                baseUrl = url,
                apiKey = key,
                httpClient = pluginProxyClientForUrl(
                    url, includeRelaySessionHeader = false
                ),
            )
            _apiClient.value = client
            shutdownClientOffMain(oldClient)
            val ok = client.checkHealth()
            _apiServerReachable.value = ok
            _apiServerHealth.value = if (ok) HealthStatus.Reachable else HealthStatus.Unreachable

            // Probe per-endpoint capabilities. The result drives both the
            // legacy chatMode flow and the auto-resolver in ChatViewModel.
            val caps = client.probeCapabilities()
            upstreamTransport.setCapabilitiesAndMode(caps)
            probeStandardVoice()
        } else {
            _apiClient.value = null
            shutdownClientOffMain(oldClient)
            _apiServerReachable.value = false
            _apiServerHealth.value = HealthStatus.Unknown
            upstreamTransport.resetCapabilitiesAndMode()
            probeStandardVoice()
        }
        rebuildChatApiClient()
    }

    private suspend fun rebuildChatApiClient() {
        val baseApiUrl = ProfileApiUrlResolver.normalize(effectiveApiServerUrlSnapshot())
        val selectedProfile = profileController.selectedProfile.value
        val activeConnectionId = connectionStore.activeConnectionId.value
        val topology = connectionStore.connections.value
            .firstOrNull { it.id == activeConnectionId }
            ?.dashboardLastStatus
        val liveTopology = topologyConnectionId == activeConnectionId
        val gatewayMode = if (liveTopology) topologyGatewayMode else topology?.gatewayMode
        val servedProfiles = if (liveTopology) topologyProfiles else topology?.servedProfiles.orEmpty()
        val usesMultiplexProfileKey = ProfileApiUrlResolver.usesMultiplexProfileKey(
            profileApiUrl = selectedProfile?.apiServerUrl,
            selectedProfileName = selectedProfile?.name,
            gatewayMode = gatewayMode,
            servedProfiles = servedProfiles,
        )
        val profileApiUrl = ProfileApiUrlResolver.resolveChatBase(
            profileApiUrl = selectedProfile?.apiServerUrl,
            baseApiUrl = baseApiUrl,
            selectedProfileName = selectedProfile?.name,
            gatewayMode = gatewayMode,
            servedProfiles = servedProfiles,
        )
        val baseClient = _apiClient.value
        val profileKey = if (usesMultiplexProfileKey) {
            selectedProfile?.name?.let { authManager.getProfileApiKey(it) }.orEmpty()
        } else {
            null
        }
        val connectionKey = if (usesMultiplexProfileKey) "" else apiKeyForClientBuild()
        val key = profileApiCredential(
            usesMultiplexProfileKey = usesMultiplexProfileKey,
            profileKey = profileKey,
            connectionKey = connectionKey,
        )

        if (profileApiUrl == null || profileApiUrl == baseApiUrl) {
            val oldProfileClient = profileChatApiClient
            profileChatApiClient = null
            profileChatApiClientUrl = null
            profileChatApiClientKey = null
            _chatApiClient.value = baseClient
            shutdownClientOffMain(oldProfileClient)
            return
        }

        val existingProfileClient = profileChatApiClient
        if (
            existingProfileClient != null &&
            profileChatApiClientUrl == profileApiUrl &&
            profileChatApiClientKey == key
        ) {
            _chatApiClient.value = existingProfileClient
            return
        }

        val nextProfileClient = HermesApiClient(
            baseUrl = profileApiUrl,
            apiKey = key,
            httpClient = pluginProxyClientForUrl(
                profileApiUrl, includeRelaySessionHeader = false
            ),
        )
        profileChatApiClient = nextProfileClient
        profileChatApiClientUrl = profileApiUrl
        profileChatApiClientKey = key
        _chatApiClient.value = nextProfileClient
        shutdownClientOffMain(existingProfileClient)
    }

    /**
     * [HermesApiClient.shutdown] eventually drives OkHttp's
     * [okhttp3.ConnectionPool.evictAll], which closes live SSL sockets
     * synchronously — i.e. it performs network writes. Running that on
     * the main thread trips StrictMode's `NetworkOnMainThreadException`
     * on any keep-alive connection (observed on connection switch +
     * updateApiServerUrl). Always hand off to IO.
     */
    private suspend fun shutdownClientOffMain(client: HermesApiClient?) {
        if (client == null) return
        runCatching { withContext(Dispatchers.IO) { client.shutdown() } }
            .onFailure {
                android.util.Log.w(
                    "ConnectionVM",
                    "HermesApiClient.shutdown failed: ${it.message}",
                )
            }
    }

    // --- Relay methods ---

    fun connectRelay(url: String) {
        val trimmed = url.trim()
        _relayUrl.value = trimmed
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_RELAY_URL] = trimmed
            }
            persistActiveConnectionUrls(
                apiServerUrl = _apiServerUrl.value,
                relayUrl = trimmed,
                routeCandidates = mergedRouteCandidates(
                    apiServerUrl = _apiServerUrl.value,
                    relayUrl = trimmed,
                ),
            )
        }
        connectRelayInternal(trimmed)
    }

    fun connectRelay() {
        connectRelayInternal(effectiveRelayWebSocketUrlSnapshot())
    }

    /**
     * Pair-context-gated connect. WSS connect attempts without a pair
     * context (no session token, no pending server-issued code, not
     * mid-pair) are silently skipped — the auth envelope would just fail
     * and tick the relay's rate limiter toward its 5-min IP block, which
     * is a trap users can fall into by fumbling with Manual Configuration.
     *
     * Legitimate pair-context sources (any one is enough):
     *   * [AuthManager.authState] is [AuthState.Paired] → session token
     *   * [AuthManager.authState] is [AuthState.Pairing] → mid-handshake
     *   * [AuthManager.hasPairContext] → fresh server-issued code is
     *     stashed from a QR scan and about to ride the next auth envelope
     *
     * For the **reachability check** case (user wants to verify a manually-
     * typed URL before pairing), call [testRelayReachable] instead — it
     * probes `GET /health` without touching the WSS channel.
     */
    private fun connectRelayInternal(url: String) {
        if (isDemoMode.value) return // Demo mode is offline — never open the WSS channel.
        if (!authManager.hasPairContext) {
            android.util.Log.i(
                "ConnectionVM",
                "connectRelay: no pair context — skipping WSS connect to avoid auth-failure rate-limit (use testRelayReachable for reachability checks)"
            )
            DiagnosticsLog.record(
                category = DiagnosticCategory.Session,
                severity = DiagnosticSeverity.Warning,
                title = ctx.getString(R.string.conn_status_relay_connect_skipped),
                detail = ctx.getString(R.string.conn_detail_no_paired_session),
                url = url,
            )
            return
        }
        DiagnosticsLog.record(
            category = DiagnosticCategory.Relay,
            severity = DiagnosticSeverity.Info,
            title = ctx.getString(R.string.conn_status_relay_connect_requested),
            url = url,
        )
        connectionManager.connect(url)
        viewModelScope.launch { probeActiveRouteSurfaces() }
    }

    fun disconnectRelay() {
        DiagnosticsLog.record(
            category = DiagnosticCategory.Relay,
            severity = DiagnosticSeverity.Info,
            title = ctx.getString(R.string.conn_status_relay_disconnect_requested),
            url = effectiveRelayUrlSnapshot(),
        )
        connectionManager.disconnect()
    }

    // --- ADR 24 — multi-endpoint exposure ------------------------------------

    /**
     * Observe the per-device endpoint-candidate list. Emits an empty list
     * for freshly-upgraded installs (no `endpoints` persisted yet) or for
     * pre-ADR-24 v1/v2 QRs whose synthesizer produced a single candidate
     * — in which case the UI renders a one-row card.
     *
     * The device id is looked up via [AuthManager.getOrCreateDeviceId] so
     * the flow hot-rebinds when the active connection swaps.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDeviceEndpoints(): kotlinx.coroutines.flow.Flow<List<EndpointCandidate>> {
        return activeConnection.flatMapLatest { connection ->
            val savedRoutes = connection?.routeCandidates.orEmpty()
            kotlinx.coroutines.flow.flow {
                val deviceId = runCatching { authManager.getOrCreateDeviceId() }.getOrNull()
                val pairedRoutes = deviceId?.let { id ->
                    runCatching {
                        PairingPreferences.getDeviceEndpoints(getApplication(), id).first()
                    }.getOrDefault(emptyList())
                }.orEmpty()
                emit(
                    mergeRelayTransportIntoStandardRoutes(
                        standardRoutes = savedRoutes,
                        relayRoutes = pairedRoutes,
                    ),
                )
            }
        }
    }

    /**
     * Add or replace an extra fallback route on the active connection — the
     * standard path's manual equivalent of a v3 pairing QR's `endpoints`
     * array. The primary route (priority 0) mirrors the connection's main
     * Dashboard/Gateway address and is edited through the connection detail,
     * never here.
     *
     * Legacy candidate sources (per-device PairingPreferences from old QR
     * pairings, or a bare single-URL config) are seeded onto the connection
     * first, so an edit never hides routes the card was already showing.
     *
     * @param original non-null = edit-in-place: the matching stored entry is
     *   replaced and keeps its priority; null = append after the last route.
     * @param onResult called with a user-facing error string, or null on
     *   success — drives the dialog's inline error text.
     */
    fun saveExtraRoute(
        role: String,
        dashboardUrl: String,
        original: EndpointCandidate? = null,
        onResult: (String?) -> Unit,
    ) {
        if (original?.priority == 0) {
            onResult("The primary route mirrors the connection's Dashboard/Gateway address — edit that instead")
            return
        }
        viewModelScope.launch {
            val current = activeConnectionSnapshot()
            if (current == null) {
                onResult("No active connection")
                return@launch
            }
            // Accept bare hosts/IPs — http:// is assumed and the standard
            // Dashboard/Gateway port defaults to 9119.
            val trimmedUrl = Connection.normalizeDashboardUrlInput(dashboardUrl)
            val existing = seedRouteCandidates(current)
            if (existing.isEmpty()) {
                onResult("Set the connection's Dashboard/Gateway URL first")
                return@launch
            }
            val withoutOriginal = if (original != null) {
                existing.filterNot { it.sameRouteAs(original) }
            } else {
                existing
            }
            val candidate = Connection.endpointCandidateFromDashboardUrl(
                role = role.trim().ifBlank { Connection.inferRouteRole(trimmedUrl) },
                priority = original?.priority
                    ?: ((withoutOriginal.maxOfOrNull { it.priority } ?: 0) + 1),
                dashboardUrl = trimmedUrl,
                apiServerUrl = Connection.deriveDefaultApiUrl(trimmedUrl),
                relayUrl = if (
                    current.relayUrl.isNotBlank() ||
                    original?.relay != null ||
                    existing.any { it.relay != null }
                ) {
                    Connection.deriveDefaultApiUrl(trimmedUrl)
                        ?.let(Connection::deriveDefaultRelayUrl)
                } else {
                    null
                },
            )
            if (candidate == null) {
                onResult(
                    "Enter the Dashboard/Gateway host — e.g. 100.64.0.1 or " +
                        "http://host:9119 (http/https only; port defaults to 9119)",
                )
                return@launch
            }
            val collision = withoutOriginal.firstOrNull {
                it.routeAuthority() == candidate.routeAuthority()
            }
            if (collision != null) {
                onResult(
                    if (collision.priority == 0) {
                        "That host is already the primary route"
                    } else {
                        "The ${collision.displayLabel()} route already uses that host"
                    },
                )
                return@launch
            }
            val next = (withoutOriginal + candidate)
                .sortedWith(compareBy<EndpointCandidate> { it.priority }.thenBy { it.role })
            connectionStore.updateConnection(current.copy(routeCandidates = next))
            // Full probe cycle (not a bare refresh) so the just-saved route
            // immediately shows a reachability verdict in the Routes card.
            probeNow()
            onResult(null)
        }
    }

    /**
     * Remove an extra fallback route. The primary route (priority 0) is
     * protected — it mirrors the connection's Dashboard/Gateway address. Clears a preferred-
     * route override that pointed at the removed route, mirroring
     * [persistActiveConnectionUrls]' stale-preference handling.
     */
    fun removeExtraRoute(candidate: EndpointCandidate, onResult: (String?) -> Unit = {}) {
        if (candidate.priority == 0) {
            onResult("The primary route can't be removed — edit the connection's Dashboard/Gateway address instead")
            return
        }
        viewModelScope.launch {
            val current = activeConnectionSnapshot()
            if (current == null) {
                onResult("No active connection")
                return@launch
            }
            val existing = seedRouteCandidates(current)
            val next = existing.filterNot { it.sameRouteAs(candidate) }
            if (next.size == existing.size) {
                onResult(null)
                return@launch
            }
            val preferredNowStale = current.preferredRouteRole != null &&
                next.none { it.role.equals(current.preferredRouteRole, ignoreCase = true) }
            connectionStore.updateConnection(
                current.copy(
                    routeCandidates = next,
                    preferredRouteRole = if (preferredNowStale) null else current.preferredRouteRole,
                ),
            )
            if (preferredNowStale) {
                connectionManager.setManualRoleOverride(null)
            }
            // Same visible probe cycle as saveExtraRoute — removing the
            // active route should immediately re-resolve and show where the
            // app landed.
            probeNow()
            onResult(null)
        }
    }

    private fun activeConnectionSnapshot(): Connection? {
        val activeId = connectionStore.activeConnectionId.value ?: return null
        return connectionStore.connections.value.firstOrNull { it.id == activeId }
    }

    /**
     * The connection's stored candidates, or — mirroring
     * [observeDeviceEndpoints]' fallback chain — the per-device pairing
     * endpoints, or a primary synthesized from the saved URLs. Whatever the
     * Routes card is currently displaying is what an edit starts from.
     */
    private suspend fun seedRouteCandidates(current: Connection): List<EndpointCandidate> {
        val fromPairing = runCatching {
            val deviceId = authManager.getOrCreateDeviceId()
            PairingPreferences.getDeviceEndpoints(getApplication(), deviceId).first()
        }.getOrDefault(emptyList())
        val recovered = mergeRelayTransportIntoStandardRoutes(
            standardRoutes = current.routeCandidates,
            relayRoutes = fromPairing,
        )
        if (recovered.isNotEmpty()) return recovered
        val dashboardUrl = current.configuredDashboardUrl.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return listOfNotNull(
            Connection.endpointCandidateFromDashboardUrl(
                role = Connection.inferRouteRole(dashboardUrl),
                priority = 0,
                dashboardUrl = dashboardUrl,
                apiServerUrl = current.apiServerUrl.takeIf { it.isNotBlank() },
                relayUrl = current.relayUrl.takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun EndpointCandidate.sameRouteAs(other: EndpointCandidate): Boolean =
        role.equals(other.role, ignoreCase = true) &&
            routeAuthority() == other.routeAuthority()

    /**
     * Sticky route policy — the Routes card's "Prefer this route". Persists
     * [Connection.preferredRouteRole] (restored as the live override on every
     * connection load / app start) AND installs it via
     * [ConnectionManager.setManualRoleOverride], then kicks [probeNow] so the
     * swap takes effect immediately if the route is reachable. Reachability
     * still gates: an unreachable preferred route falls back through strict
     * priority. Passing `null` clears both the persisted preference and the
     * live override.
     *
     * For a one-time switch that should NOT survive disconnects or restarts,
     * use [useRouteNow] instead.
     */
    fun setPreferredEndpointRole(role: String?) {
        connectionManager.setManualRoleOverride(role)
        viewModelScope.launch {
            val activeId = connectionStore.activeConnectionId.value ?: return@launch
            val current = connectionStore.connections.value.firstOrNull { it.id == activeId }
                ?: return@launch
            connectionStore.updateConnection(
                current.copy(preferredRouteRole = role?.takeIf { it.isNotBlank() }),
            )
        }
        probeNow()
    }

    /**
     * One-time route switch — the Routes card's "Use now". Installs a
     * transient [ConnectionManager.setManualRoleOverride] and re-probes, but
     * does NOT persist anything: the switch dies on the next explicit
     * disconnect and is replaced by the persisted preference (if any) on the
     * next connection load. Passing `null` cancels a manual switch and
     * restores the sticky preference, or full automatic resolution when no
     * preference is set.
     *
     * "Use now" used to route through [setPreferredEndpointRole], so a
     * one-time switch silently became a persistent preference — this split
     * keeps "act now" and "policy" separate.
     */
    fun useRouteNow(role: String?) {
        connectionManager.setManualRoleOverride(
            role ?: activeConnection.value?.preferredRouteRole,
        )
        probeNow()
    }

    /**
     * Live transient override — what [useRouteNow] / preference restoration
     * installed. The Routes card compares this against the persisted
     * preference to label the current route automatic / preferred / manual.
     */
    val manualRouteOverride: StateFlow<String?>
        get() = connectionManager.manualRoleOverrideFlow

    /** The persisted sticky preference only — never the transient override. */
    fun getPreferredEndpointRole(): String? =
        activeConnection.value?.preferredRouteRole

    /**
     * Route-probe lifecycle for the Routes card. [Probing] disables the
     * Re-check affordances and shows progress; [Done] carries the winner (or
     * null when every saved route failed its probe) so the UI can say "no
     * route reachable" out loud instead of sitting on "Resolving" forever.
     */
    sealed interface RouteProbeStatus {
        data object Idle : RouteProbeStatus
        data object Probing : RouteProbeStatus
        data class Done(
            val winner: EndpointCandidate?,
            val atMillis: Long,
        ) : RouteProbeStatus
    }

    private val _routeProbeStatus = MutableStateFlow<RouteProbeStatus>(RouteProbeStatus.Idle)
    val routeProbeStatus: StateFlow<RouteProbeStatus> = _routeProbeStatus.asStateFlow()

    /** Last probe verdict per route — see [EndpointResolver.probeOutcomes]. */
    val routeProbeOutcomes: StateFlow<Map<String, RouteProbeOutcome>> =
        endpointResolver.probeOutcomes

    /** Key into [routeProbeOutcomes] for one route surface. */
    fun routeOutcomeKey(
        candidate: EndpointCandidate,
        surface: EndpointSurface = EndpointSurface.Standard,
    ): String = EndpointResolver.outcomeKey(candidate, surface)

    /** Probe configured surfaces per route without changing the selected route. */
    private suspend fun probeActiveRouteSurfaces() {
        val current = activeConnectionSnapshot() ?: return
        val candidates = seedRouteCandidates(current)
        coroutineScope {
            candidates.map { candidate ->
                async { endpointResolver.probeSurfaces(candidate) }
            }.awaitAll()
        }
    }

    // Set when probeNow() is requested while a probe is already in flight
    // (e.g. "Use now" tapped mid-Re-check) — the override the caller just
    // installed must still win, so the finished probe immediately re-runs
    // once instead of silently dropping the request. Main-thread confined.
    private var routeProbeRerunRequested = false

    /** User-triggered re-probe — Endpoints card's "Probe now" row action. */
    fun probeNow() {
        if (_routeProbeStatus.value is RouteProbeStatus.Probing) {
            routeProbeRerunRequested = true
            return
        }
        _routeProbeStatus.value = RouteProbeStatus.Probing
        viewModelScope.launch {
            try {
                val apiRouteBefore = effectiveApiServerUrlSnapshot()
                // Await the actual resolve (LAN timing out can take 4s+)
                // instead of the old fixed 100ms guess, which always lost the
                // race and left the health probes pointed at the stale route.
                val winner = connectionManager.probeAndReconnectNow()
                if (effectiveApiServerUrlSnapshot() != apiRouteBefore) {
                    rebuildApiClient()
                }
                val dashboardProbe = launch { probeStandardVoice() }
                val apiProbe = launch { probeApiHealth(includeDashboardProbe = false) }
                val relayProbe = launch { probeRelayHealth() }
                val routeSurfaceProbe = launch { probeActiveRouteSurfaces() }
                dashboardProbe.join()
                apiProbe.join()
                relayProbe.join()
                routeSurfaceProbe.join()
                _routeProbeStatus.value = RouteProbeStatus.Done(
                    winner = winner,
                    atMillis = System.currentTimeMillis(),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                _routeProbeStatus.value = RouteProbeStatus.Idle
                throw e
            } catch (e: Exception) {
                // Never strand the UI in Probing — surface "nothing won" and
                // let the per-route outcomes explain the details.
                _routeProbeStatus.value = RouteProbeStatus.Done(
                    winner = null,
                    atMillis = System.currentTimeMillis(),
                )
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Endpoint,
                    severity = DiagnosticSeverity.Warning,
                    title = ctx.getString(R.string.conn_status_route_recheck_failed),
                    detail = e.javaClass.simpleName,
                )
            }
            if (routeProbeRerunRequested) {
                routeProbeRerunRequested = false
                probeNow()
            }
        }
    }

    /**
     * Fetch the TOFU SPKI pin stored for this endpoint's host:port, if any.
     * Used by the Endpoints card's "View pin" row action. Returns null when
     * no pin has been recorded (not-yet-TOFU'd host) or when the cert store
     * is unavailable.
     */
    suspend fun lookupEndpointPin(candidate: com.hermesandroid.relay.data.EndpointCandidate): String? {
        candidate.proxy?.pinSha256?.takeIf { candidate.hasSecureProxy() }?.let { return it }
        val hostPort = candidate.routeAuthority() ?: return null
        val pins = PairingPreferences.getTofuPins(getApplication())
        return pins[hostPort]
    }

    /**
     * If we have a paired session token but the WS isn't currently open,
     * kick the relay connection back up. Called on Settings screen entry
     * and from the "Reconnect" button / tap-to-reconnect action on the
     * Relay status row.
     *
     * No-op when not paired, already connected, or actively handshaking. A
     * scheduled ordinary reconnect is replaced with an immediate attempt; the
     * connection manager preserves server-issued rate-limit backoff.
     */
    fun reconnectIfStale() {
        if (isDemoMode.value) return // Demo mode is offline — never open a socket.
        val paired = authState.value is AuthState.Paired
        val retryableState = relayConnectionState.value == ConnectionState.Disconnected ||
            relayConnectionState.value == ConnectionState.Reconnecting
        val relayUrl = effectiveRelayUrlSnapshot()
        val hasUrl = relayUrl.isNotBlank()
        if (paired && retryableState && hasUrl) {
            connectionManager.reconnectNowIfAllowed(relayUrl)
        }
    }

    fun updateRelayUrl(url: String) {
        val trimmed = url.trim()
        _relayUrl.value = trimmed
        // The new URL hasn't been verified yet — flip to Probing so the
        // health badge doesn't show stale Reachable/Unreachable from the
        // old URL while the next periodic tick (or revalidate()) lands.
        _relayServerHealth.value = HealthStatus.Probing
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_RELAY_URL] = trimmed
            }
            persistActiveConnectionUrls(
                apiServerUrl = _apiServerUrl.value,
                relayUrl = trimmed,
                routeCandidates = mergedRouteCandidates(
                    apiServerUrl = _apiServerUrl.value,
                    relayUrl = trimmed,
                ),
            )
            // Kick a fresh probe right now rather than waiting up to 30s
            // for the periodic loop.
            probeRelayHealth(force = true)
        }
    }

    /**
     * Result of the **Save & Test** button's reachability probe. One-shot
     * state: null = idle, [RelayReachable.Probing] = in-flight,
     * [RelayReachable.Ok] = live hermes-relay responded, [RelayReachable.Fail]
     * = doesn't look right. UI reads this to render a chip / icon next to
     * the button.
     */
    sealed interface RelayReachable {
        data object Probing : RelayReachable
        data class Ok(val version: String, val clients: Int, val sessions: Int) : RelayReachable
        data class Fail(val message: String) : RelayReachable
    }

    private val _relayReachableResult = MutableStateFlow<RelayReachable?>(null)
    val relayReachableResult: StateFlow<RelayReachable?> = _relayReachableResult.asStateFlow()

    /**
     * Test if [url] points at a live hermes-relay via an unauthenticated
     * `GET /health` probe.
     *
     * Also saves [url] to the persisted relay URL — this is the "Save" half
     * of the Settings → Manual configuration → **Save & Test** button. The
     * save happens before the probe so a subsequent failure still leaves
     * the URL in place for the user to edit.
     *
     * Does NOT touch the WSS channel. Does NOT require a pair context. Does
     * NOT count against the relay's rate limiter (no auth envelope is sent).
     *
     * On return [relayReachableResult] is [RelayReachable.Ok] or
     * [RelayReachable.Fail].
     */
    fun testRelayReachable(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _relayReachableResult.value = RelayReachable.Fail("Enter a relay URL first")
            return
        }
        // Persist the typed URL immediately — that's the "Save" half.
        _relayUrl.value = trimmed
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_RELAY_URL] = trimmed
            }
            persistActiveConnectionUrls(
                apiServerUrl = _apiServerUrl.value,
                relayUrl = trimmed,
                routeCandidates = mergedRouteCandidates(
                    apiServerUrl = _apiServerUrl.value,
                    relayUrl = trimmed,
                ),
            )
        }

        _relayReachableResult.value = RelayReachable.Probing
        viewModelScope.launch {
            val result = relayHttpClient.probeHealth(trimmed)
            _relayReachableResult.value = result.fold(
                onSuccess = { health ->
                    RelayReachable.Ok(
                        version = health.version,
                        clients = health.clients,
                        sessions = health.sessions,
                    )
                },
                onFailure = { err ->
                    RelayReachable.Fail(err.message ?: "Unknown error")
                }
            )
        }
    }

    /**
     * Clear the [relayReachableResult] state (e.g. after the user edits the
     * URL field — the previous probe result is no longer relevant).
     */
    fun clearRelayReachableResult() {
        _relayReachableResult.value = null
    }

    private suspend fun persistActiveConnectionUrls(
        apiServerUrl: String,
        relayUrl: String,
        dashboardUrlOverride: String? = null,
        routeCandidates: List<EndpointCandidate>? = null,
        preferredRouteRole: String? = null,
    ) {
        // Add-connection setup owns a transient auth/config draft. Until the
        // wizard completes, never mirror its URLs into the still-active saved
        // connection; [commitConnectionDraft] persists the finished snapshot.
        if (pendingConnectionDraft != null) return
        val activeId = connectionStore.activeConnectionId.value ?: return
        val current = connectionStore.connections.value.firstOrNull { it.id == activeId } ?: return
        val nextDashboardUrl = when {
            dashboardUrlOverride != null -> {
                dashboardUrlOverride
                    .trim()
                    .trimEnd('/')
                    .takeIf { it.isNotBlank() }
                    ?: Connection.deriveDefaultDashboardUrl(apiServerUrl)
            }
            Connection.isAutoManagedDashboardUrl(current.dashboardUrl, current.apiServerUrl) -> {
                Connection.deriveDefaultDashboardUrl(apiServerUrl)
            }
            else -> current.dashboardUrl
        }
        val nextRouteCandidates = Connection.reconcileDashboardRoutes(
            dashboardUrl = nextDashboardUrl,
            candidates = routeCandidates ?: current.routeCandidates,
        )
        val nextPreferredRouteRole = when {
            preferredRouteRole != null -> preferredRouteRole.takeIf { it.isNotBlank() }
            routeCandidates != null &&
                current.preferredRouteRole != null &&
                nextRouteCandidates.none {
                    it.role.equals(current.preferredRouteRole, ignoreCase = true)
                } -> null
            else -> current.preferredRouteRole
        }
        val nextAuthenticatedDashboardOrigin = current.authenticatedDashboardOrigin
            ?.takeIf {
                dashboardUrlOverride == null ||
                    (nextDashboardUrl != null && sameDashboardBase(it, nextDashboardUrl))
            }
        if (
            current.apiServerUrl == apiServerUrl &&
            current.relayUrl == relayUrl &&
            current.dashboardUrl == nextDashboardUrl &&
            current.authenticatedDashboardOrigin == nextAuthenticatedDashboardOrigin &&
            current.routeCandidates == nextRouteCandidates &&
            current.preferredRouteRole == nextPreferredRouteRole
        ) {
            return
        }
        val next = current.copy(
                apiServerUrl = apiServerUrl,
                relayUrl = relayUrl,
                dashboardUrl = nextDashboardUrl,
                authenticatedDashboardOrigin = nextAuthenticatedDashboardOrigin,
                routeCandidates = nextRouteCandidates,
                preferredRouteRole = nextPreferredRouteRole,
            )
        connectionStore.updateConnection(next)
        if (dashboardCredentialsMustBeRetired(current, next)) {
            upstreamTransport.clearDashboardAuthentication(activeId)
        }
    }

    // Backward compat wrappers
    @Deprecated("Use connectRelay", replaceWith = ReplaceWith("connectRelay(url)"))
    fun connect(url: String) = connectRelay(url)

    @Deprecated("Use connectRelay", replaceWith = ReplaceWith("connectRelay()"))
    fun connect() = connectRelay()

    @Deprecated("Use disconnectRelay", replaceWith = ReplaceWith("disconnectRelay()"))
    fun disconnect() = disconnectRelay()

    @Deprecated("Use updateRelayUrl", replaceWith = ReplaceWith("updateRelayUrl(url)"))
    fun updateServerUrl(url: String) = updateRelayUrl(url)

    // --- What's New + Version tracking ---

    /** Dev/test hook (Developer options → Test harness): show What's New now. */
    fun showWhatsNewNow() {
        _showWhatsNew.value = true
    }

    fun dismissWhatsNew() {
        _showWhatsNew.value = false
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_LAST_SEEN_VERSION] = getAppVersionName()
            }
        }
    }

    fun markVersionSeen() {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_LAST_SEEN_VERSION] = getAppVersionName()
            }
        }
    }

    private fun getAppVersionName(): String {
        return try {
            val app = getApplication<Application>()
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    // --- Session persistence ---

    fun saveLastSessionId(sessionId: String?) {
        _lastSessionId.value = sessionId
        val connectionId = activeConnectionId.value
        // Last-session persistence follows the selected UI profile identity.
        // The null Server-default sentinel stays separate from whichever named
        // profile the server's sticky default currently resolves to.
        val profileName = profileController.selectedProfile.value?.name
        viewModelScope.launch {
            if (connectionId != null) {
                if (sessionId != null) {
                    // Bucket by the id's own namespace: the prefix is the server's
                    // ground truth about which transport can resume it, robust to a
                    // turn that fell back from gateway to SSE.
                    val transport = SessionTransport.forSessionId(sessionId)
                    profileController.profileSessionStore.setSessionId(
                        connectionId,
                        profileName,
                        transport,
                        sessionId,
                    )
                } else {
                    // A null clears only the ACTIVE transport's slot, and only when
                    // that transport is known. A null while the gateway probe is
                    // still pending (transport == null) — or right after a switch,
                    // when availability is reset to Unknown — is a deferred-restore
                    // transient forwarded by switchProfileContext, NOT a user clear;
                    // clearing then would wipe a still-valid session before the
                    // availability collector restores it.
                    profileController.activeSessionTransport()?.let { transport ->
                        profileController.profileSessionStore.setSessionId(
                            connectionId,
                            profileName,
                            transport,
                            null,
                        )
                    }
                }
            }
            if (profileName == null) {
                getApplication<Application>().relayDataStore.edit { preferences ->
                    if (sessionId != null) {
                        preferences[KEY_LAST_SESSION_ID] = sessionId
                    } else {
                        preferences.remove(KEY_LAST_SESSION_ID)
                    }
                }
            }
        }
    }

    // --- Shared methods ---

    fun setTheme(theme: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[AppearancePreferences.themeKey] = theme
            }
        }
    }

    fun setAppTheme(themeId: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[AppearancePreferences.appThemeKey] = themeId
            }
        }
    }

    /** Persist the selected body font; the Compose root re-themes live. */
    fun setAppFont(fontId: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[AppearancePreferences.appFontKey] = fontId
            }
        }
    }

    fun setSphereSkin(skinId: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_SPHERE_SKIN] = skinId
            }
        }
    }

    fun setFloatingPet(avatarId: String?) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_FLOATING_PET] = avatarId
                    ?.takeUnless { it == SphereAvatar.id }
                    ?: NO_FLOATING_PET
            }
        }
    }

    fun setPetRoamingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_PET_ROAMING_ENABLED] = enabled
            }
        }
    }

    fun setPetTemperament(temperament: PetTemperament) {
        viewModelScope.launch {
            petBehaviorPreferencesRepository.setTemperament(temperament)
        }
    }

    fun setPetSizeScale(sizeScale: Float) {
        viewModelScope.launch {
            petBehaviorPreferencesRepository.setSizeScale(sizeScale)
        }
    }

    fun setPetPlacement(placement: PetPlacement) {
        val safe = placement.sanitized()
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_PET_PLACEMENT_EDGE] = safe.edge.name
                preferences[KEY_PET_PLACEMENT_FRACTION] = safe.verticalFraction
            }
        }
    }

    fun resetPetPlacement() = setPetPlacement(DEFAULT_PET_PLACEMENT)

    /** One-release compatibility shim for the former combined picker. */
    @Deprecated("Use setFloatingPet")
    fun setAgentAvatar(avatarId: String) = setFloatingPet(avatarId)

    /** Set the global pet playback-speed multiplier (clamped 0.5×–1.5×). */
    fun setPetSpeed(speed: Float) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_PET_SPEED] = speed.coerceIn(0.5f, 1.5f)
            }
        }
    }

    /** Toggle per-frame stabilization (auto-recenter) for pets. */
    fun setPetStabilize(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_PET_STABILIZE] = enabled
            }
        }
    }

    /** Import a pet from a user-picked `.zip` pack or a single image, then refresh. */
    fun importPet(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                PetImporter.importUri(getApplication<Application>(), uri)
            }
            when (result) {
                is PetImportResult.Success -> {
                    refreshAgentAvatars()
                    _avatarEvents.tryEmit("Imported “${result.label}”")
                }
                is PetImportResult.Failure -> _avatarEvents.tryEmit(result.reason)
            }
        }
    }

    /** Apply or reset the local accent override. Invalid values safely reset. */
    fun setAppearanceAccent(accentHex: String?) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                val normalized = normalizeAccentHex(accentHex)
                if (normalized == null) preferences.remove(AppearancePreferences.accentKey)
                else preferences[AppearancePreferences.accentKey] = normalized
            }
        }
    }

    fun setAppearanceShape(shapeId: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[AppearancePreferences.shapeKey] = AppearanceShape.fromId(shapeId).id
            }
        }
    }

    fun saveCustomTheme(preset: CustomThemePreset, select: Boolean = true) {
        val normalized = preset.normalized() ?: return
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                val current = AppearancePreferences.decodeCustomThemes(
                    preferences[AppearancePreferences.customThemesKey],
                )
                val updated = AppearancePreferences.upsertCustomTheme(current, normalized) ?: return@edit
                preferences[AppearancePreferences.customThemesKey] =
                    AppearancePreferences.encodeCustomThemes(updated)
                if (select) {
                    preferences[AppearancePreferences.appThemeKey] = normalized.appThemeId
                    preferences[AppearancePreferences.themeKey] = normalized.mode
                    preferences[AppearancePreferences.shapeKey] = normalized.shapeId
                    preferences.remove(AppearancePreferences.accentKey)
                }
            }
        }
    }

    fun duplicateCustomTheme(preset: CustomThemePreset) {
        saveCustomTheme(
            preset.copy(
                id = UUID.randomUUID().toString(),
                name = "${preset.name} copy".take(CustomThemePreset.MAX_NAME_LENGTH),
            ),
        )
    }

    fun deleteCustomTheme(id: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                val updated = AppearancePreferences.decodeCustomThemes(
                    preferences[AppearancePreferences.customThemesKey],
                ).filterNot { it.id == id }
                preferences[AppearancePreferences.customThemesKey] =
                    AppearancePreferences.encodeCustomThemes(updated)
                if (CustomThemePreset.idFromAppTheme(preferences[AppearancePreferences.appThemeKey]) == id) {
                    preferences[AppearancePreferences.appThemeKey] = AppThemes.DEFAULT_ID
                    preferences[AppearancePreferences.themeKey] = "auto"
                    preferences[AppearancePreferences.shapeKey] = AppearanceShape.DEFAULT.id
                    preferences.remove(AppearancePreferences.accentKey)
                }
            }
        }
    }

    fun selectCustomTheme(id: String) {
        customThemes.value.firstOrNull { it.id == id }?.let { saveCustomTheme(it) }
    }

    fun resetAppearanceTheme() {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[AppearancePreferences.appThemeKey] = AppThemes.DEFAULT_ID
                preferences[AppearancePreferences.themeKey] = "auto"
                preferences.remove(AppearancePreferences.accentKey)
                preferences[AppearancePreferences.shapeKey] = AppearanceShape.DEFAULT.id
            }
        }
    }

    /** Import one declarative sphere-skin JSON file, select it, then hot-refresh Appearance. */
    fun importSphereSkin(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                SphereSkinImporter.importUri(getApplication<Application>(), uri)
            }
            when (result) {
                is SphereSkinImportResult.Success -> {
                    setSphereSkin(result.id)
                    refreshAgentAvatars()
                    _avatarEvents.tryEmit("Imported “${result.label}”")
                }
                is SphereSkinImportResult.Failure -> _avatarEvents.tryEmit(result.reason)
            }
        }
    }

    /** Import a validated pet-format asset for the central/background renderer. */
    fun importBackgroundAnimation(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                PetImporter.importUri(getApplication<Application>(), uri)
            }
            when (result) {
                is PetImportResult.Success -> {
                    setBackgroundAvatar(result.id)
                    refreshAgentAvatars()
                    _avatarEvents.tryEmit("Imported background “${result.label}”")
                }
                is PetImportResult.Failure -> _avatarEvents.tryEmit(result.reason)
            }
        }
    }

    /** Delete a user pet by id; clear it if it was the selected companion. */
    fun deleteUserAvatar(avatarId: String, label: String) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                PetLoader.deletePet(getApplication<Application>(), avatarId)
            }
            if (deleted) {
                if (floatingPet.value == avatarId) setFloatingPet(null)
                if (backgroundAvatar.value == avatarId) setBackgroundAvatar(SphereAvatar.id)
                refreshAgentAvatars()
                _avatarEvents.tryEmit("Removed “$label”")
            } else {
                _avatarEvents.tryEmit("Couldn’t remove “$label”")
            }
        }
    }

    fun setFontScale(scale: Float) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[AppearancePreferences.fontScaleKey] = scale
            }
        }
    }

    fun setInsecureMode(enabled: Boolean) {
        connectionManager.setInsecureMode(enabled)
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_INSECURE_MODE] = enabled
            }
        }
    }

    fun completeOnboarding() {
        _onboardingCompleted.value = true
        viewModelScope.launch {
            dataManager.setOnboardingCompleted(true)
        }
    }

    fun resetOnboarding(onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = dataManager.resetOnboarding()
            if (success) {
                _onboardingCompleted.value = false
            }
            onResult(success)
        }
    }

    fun resetAppData(onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = runCatching {
                disconnectRelay()
                authManager.clearSession()
                authManager.clearApiKey()
                check(dataManager.resetAppData()) { "App data store reset failed" }
                profileController.profileSelectionStore.clearAll()
                profileController.profileLockStore.clearAll()
                com.hermesandroid.relay.data.SupervisedModeStore(getApplication<Application>())
                    .clearAll()
                profileController.profilePresentationStore.clearAll()
                profileController.profileSessionStore.clearAll()
                _apiServerUrl.value = ""
                _relayUrl.value = ""
                rebuildApiClient()
                shutdownClientOffMain(profileChatApiClient)
                profileChatApiClient = null
                profileChatApiClientUrl = null
                profileChatApiClientKey = null
                profileController.clearSelectionState()
                _lastSessionId.value = null
            }.onFailure {
                android.util.Log.e("ConnectionVM", "Failed to reset app data", it)
            }.isSuccess
            onResult(success)
        }
    }

    fun exportSettings(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val json = runCatching {
                dataManager.exportSettings(
                    serverUrl = _relayUrl.value,
                    theme = theme.value,
                    onboardingCompleted = _onboardingCompleted.value,
                    // Pass 2: AuthManager.sessionLabels is gone — replaced by
                    // `agentProfiles: StateFlow<List<Profile>>`. The DataManager
                    // param is marked @Suppress("UNUSED_PARAMETER") and isn't
                    // written to the backup anyway, so an empty list keeps the
                    // signature stable until the param is removed in a later pass.
                    sessionLabels = emptyList(),
                    apiServerUrl = _apiServerUrl.value,
                    relayUrl = _relayUrl.value
                )
            }.onFailure {
                android.util.Log.e("ConnectionVM", "Failed to prepare settings backup", it)
            }.getOrNull()
            onResult(json)
        }
    }

    fun writeBackupToUri(uri: Uri, backup: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = dataManager.writeBackupToUri(uri, backup)
            onResult(success)
        }
    }

    fun importFromUri(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val jsonString = dataManager.readBackupFromUri(uri) ?: run {
                onResult(false)
                return@launch
            }
            val backup = dataManager.importSettings(jsonString) ?: run {
                onResult(false)
                return@launch
            }
            val success = runCatching {
                val importedRelayUrl = if (backup.connections.isEmpty()) {
                    backup.relayUrl ?: backup.serverUrl
                } else {
                    null
                }
                // A backup is a replacement snapshot, including when it
                // intentionally contains zero connections.
                dataManager.restoreConnectionBackup(backup)
                getApplication<Application>().relayDataStore.edit { preferences ->
                    preferences[AppearancePreferences.themeKey] = backup.theme
                    importedRelayUrl?.let { preferences[KEY_RELAY_URL] = it }
                    backup.apiServerUrl
                        ?.takeIf { backup.connections.isEmpty() }
                        ?.let { preferences[KEY_API_SERVER_URL] = it }
                }
                connectionStore.activeConnection.value?.let { restored ->
                    restorePersistedActiveConnectionContext(restored)
                }
                if (backup.connections.isEmpty()) {
                    // Preserve v1/v2 compatibility after clearing the current
                    // multi-connection snapshot.
                    importedRelayUrl?.let { updateRelayUrl(it) }
                    backup.apiServerUrl?.let { updateApiServerUrl(it) }
                }
                check(dataManager.setOnboardingCompleted(backup.onboardingCompleted)) {
                    "Failed to restore onboarding state"
                }
                _onboardingCompleted.value = backup.onboardingCompleted
            }.onFailure {
                android.util.Log.e("ConnectionVM", "Failed to import settings backup", it)
            }.isSuccess
            onResult(success)
        }
    }

    fun regeneratePairingCode() {
        authManager.regeneratePairingCode()
    }

    /**
     * Wipe the stored session token and tear down the relay connection.
     *
     * **Order matters**: we must stop the [ConnectionManager] reconnect loop
     * *before* wiping auth state, otherwise the auto-reconnect can fire a
     * WS handshake with whatever's left in the auth envelope (most commonly
     * the freshly-regenerated *local* pairing code, which isn't registered
     * on the relay) and trigger the rate limiter after 5 attempts — blocking
     * the device's IP for 5 minutes and making the next QR re-pair attempt
     * fail with 429.
     *
     * This is the fix for the 2026-04-11 "can't re-pair same device after
     * self-revoke" bug: user hits Revoke on their own entry in Paired
     * Devices → clearSession was called but the reconnect loop kept firing
     * with stale credentials → IP blocked → fresh QR scan bounced off the
     * rate limiter until app restart.
     */
    fun clearSession() {
        disconnectRelay()
        authManager.clearSession()
    }

    // --- Paired devices management ----------------------------------------
    //
    // Owned by [pairingController]; these functions preserve the public
    // surface unchanged.

    fun loadPairedDevices() = pairingController.loadPairedDevices()

    suspend fun revokeDevice(tokenPrefix: String): Boolean =
        pairingController.revokeDevice(tokenPrefix)

    suspend fun extendDevice(tokenPrefix: String, ttlSeconds: Long): Boolean =
        pairingController.extendDevice(tokenPrefix, ttlSeconds)

    suspend fun revokeChannelGrant(
        tokenPrefix: String,
        channel: String,
    ): Boolean = pairingController.revokeChannelGrant(tokenPrefix, channel)

    // --- Insecure-ack helpers ---------------------------------------------
    //
    // Owned by [pairingController]; getters/function preserve the public
    // surface unchanged. [applyPairingPayload] reads [insecureReason].value.

    val insecureAckSeen: StateFlow<Boolean> get() = pairingController.insecureAckSeen

    val insecureReason: StateFlow<String> get() = pairingController.insecureReason

    fun setInsecureAckComplete(reason: String) = pairingController.setInsecureAckComplete(reason)

    override fun onCleared() {
        super.onCleared()
        // onCleared runs on the main thread, but every client's shutdown()
        // routes ConnectionPool.evictAll() (a synchronous TLS socket close /
        // network write) off the main thread internally via
        // shutdownOffMainThread, so these direct calls can't trip
        // NetworkOnMainThreadException on live SSL sockets.
        connectionManager.shutdown()
        _apiClient.value?.shutdown()
        profileChatApiClient?.shutdown()
        upstreamTransport.disposeAllRouteClients()
        tailscaleDetector.shutdown()
        // Release the cached VirtualDisplay + ImageReader + HandlerThread
        // built by ScreenCapture on the first /screenshot call. Without
        // this, a process-rare VM teardown would leak the capture pipeline
        // until the OS cleans up on exit.
        runCatching { screenCapture?.releaseCache() }
    }
}

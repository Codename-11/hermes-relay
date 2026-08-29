package com.hermesandroid.relay.ui

import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import com.hermesandroid.relay.data.VoicePresentationMode
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.viewmodel.ChatRuntimeStatus
import com.hermesandroid.relay.viewmodel.ChatConnectState
import com.hermesandroid.relay.viewmodel.ChatTransportPath
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.resolveChatConnectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayAppStatusTest {

    @Test
    fun `footer model omits provider and context window suffix`() {
        assertEquals("gpt-5.6-sol", compactFooterModelLabel("openai/gpt-5.6-sol-900k"))
        assertEquals("claude-opus", compactFooterModelLabel("claude-opus-1M"))
    }

    @Test
    fun `pet roaming supports chat terminal and curated status chrome routes`() {
        assertEquals("chat", petSurfaceOwnerForRoute(Screen.Chat.route))
        assertEquals("terminal", petSurfaceOwnerForRoute(Screen.Terminal.route))
        assertEquals(Screen.Settings.route, petSurfaceOwnerForRoute(Screen.Settings.route))
        assertEquals(
            Screen.AppearanceSettings.route,
            petSurfaceOwnerForRoute(Screen.AppearanceSettings.route),
        )
        assertEquals(Screen.About.route, petSurfaceOwnerForRoute(Screen.About.route))
        assertNull(petSurfaceOwnerForRoute(Screen.Manage.route))
    }

    @Test
    fun `status chrome route allowlist stays intentionally small`() {
        assertEquals(
            setOf(
                Screen.Settings.route,
                Screen.AppearanceSettings.route,
                Screen.About.route,
            ),
            APP_STATUS_PET_ROUTES,
        )
    }

    @Test
    fun `Petdex gallery uses its own previews without the global companion overlay`() {
        assertFalse(floatingPetAllowedOnRoute(Screen.PetdexBrowse.route))
        assertTrue(floatingPetAllowedOnRoute(Screen.Chat.route))
        assertTrue(floatingPetAllowedOnRoute(Screen.AppearanceSettings.route))
    }

    @Test
    fun `connection footer remains visible in conversation voice only`() {
        assertTrue(shouldShowConnectionFooter(voiceMode = false, VoicePresentationMode.Focus))
        assertTrue(shouldShowConnectionFooter(voiceMode = true, VoicePresentationMode.Conversation))
        assertFalse(shouldShowConnectionFooter(voiceMode = true, VoicePresentationMode.Focus))
    }

    @Test
    fun `full-screen connect routes suppress connection-dependent app chrome`() {
        assertTrue(
            shouldSuppressGlobalChrome(
                onboardingCompleted = true,
                isDemoMode = false,
                currentRoute = Screen.Pair.route,
            ),
        )
        assertTrue(
            shouldSuppressGlobalChrome(
                onboardingCompleted = true,
                isDemoMode = false,
                currentRoute = Screen.Onboarding.route,
            ),
        )
        assertFalse(
            shouldSuppressGlobalChrome(
                onboardingCompleted = true,
                isDemoMode = false,
                currentRoute = Screen.ConnectionsSettings.route,
            ),
        )
        assertFalse(
            shouldSuppressGlobalChrome(
                onboardingCompleted = false,
                isDemoMode = true,
                currentRoute = Screen.Chat.route,
            ),
        )
    }

    @Test
    fun `pair setup permits explicitly authorized duplicate renew handoff`() {
        val initiallyReady = resolvePairSetupReady(
            storeHydrated = true,
            connectionId = "placeholder",
            authorizedHandoffId = null,
            activeConnectionId = "placeholder",
            connectionIds = setOf("placeholder", "existing"),
        )

        assertTrue(initiallyReady)
        assertTrue(
            resolvePairSetupReady(
                storeHydrated = true,
                connectionId = "placeholder",
                authorizedHandoffId = "existing",
                activeConnectionId = "existing",
                connectionIds = setOf("placeholder", "existing"),
            ),
        )
    }

    @Test
    fun `pair setup waits for its exact route target`() {
        assertFalse(
            resolvePairSetupReady(
                storeHydrated = true,
                connectionId = "new-placeholder",
                authorizedHandoffId = null,
                activeConnectionId = "stale-placeholder",
                connectionIds = setOf("stale-placeholder"),
            ),
        )
    }

    @Test
    fun `pair setup never trusts a prior latch before store hydration`() {
        assertFalse(
            resolvePairSetupReady(
                storeHydrated = false,
                connectionId = "placeholder",
                authorizedHandoffId = "existing",
                activeConnectionId = "existing",
                connectionIds = emptySet(),
            ),
        )
    }

    @Test
    fun `pair setup retry replaces a still active preparation attempt`() {
        assertFalse(shouldStartPairPreparation(hasActiveJob = true, retryRequested = false))
        assertTrue(shouldStartPairPreparation(hasActiveJob = true, retryRequested = true))
        assertTrue(shouldStartPairPreparation(hasActiveJob = false, retryRequested = false))
    }

    @Test
    fun `replaced pair preparation completion does not evict current job`() {
        val oldJob = Any()
        val replacementJob = Any()

        assertFalse(isCurrentPairPreparation(replacementJob, oldJob))
        assertTrue(isCurrentPairPreparation(replacementJob, replacementJob))
    }

    @Test
    fun `dashboard-only connection counts as configured startup chat`() {
        assertTrue(hasConfiguredStartupChat(connection(dashboardUrl = "https://host.ts.net:9119")))
        assertFalse(hasConfiguredStartupChat(connection(relayUrl = "wss://host.ts.net:8767")))
    }

    @Test
    fun `gateway readiness owns chat status when optional surfaces are unavailable`() {
        val status = resolveAppChatRuntimeStatus(
            connection = connection(
                dashboardUrl = "https://host.ts.net:9119",
                apiServerUrl = "https://host.ts.net:8642",
                relayUrl = "wss://host.ts.net:8767",
            ),
            gatewayAvailability = GatewayAvailability.Ready,
            apiHealth = ConnectionViewModel.HealthStatus.Unreachable,
        )

        assertEquals(
            ChatRuntimeStatus.Connected(ChatTransportPath.Gateway, fallback = false),
            status,
        )
    }

    @Test
    fun `configured gateway probe appears connecting without Relay involvement`() {
        val status = resolveAppChatRuntimeStatus(
            connection = connection(dashboardUrl = "https://host.ts.net:9119"),
            gatewayAvailability = GatewayAvailability.Unknown,
            apiHealth = ConnectionViewModel.HealthStatus.Unknown,
        )

        assertEquals(ChatRuntimeStatus.Connecting, status)
    }

    @Test
    fun `committed pair target stays ready while public inventory catches up`() {
        assertTrue(
            resolvePairSetupReady(
                storeHydrated = true,
                connectionId = "committed-draft",
                authorizedHandoffId = null,
                activeConnectionId = "committed-draft",
                connectionIds = emptySet(),
                draftConnectionId = null,
            ),
        )
    }

    @Test
    fun `configured chat settles unavailable only from transport verdict`() {
        assertTrue(
            shouldSettleStartupUnreachable(
                hasConfiguredChat = true,
                runtimeStatus = ChatRuntimeStatus.Unavailable,
            ),
        )
        assertFalse(
            shouldSettleStartupUnreachable(
                hasConfiguredChat = true,
                runtimeStatus = ChatRuntimeStatus.Connecting,
            ),
        )
    }

    @Test
    fun `settled chat outage is unavailable instead of connecting forever`() {
        val configured = connection(dashboardUrl = "https://host.ts.net:9119")
        assertEquals(
            ChatConnectState.Unavailable,
            resolveChatConnectState(
                hydrated = true,
                connection = configured,
                ready = false,
                gatewayAvailability = GatewayAvailability.Unreachable,
                apiHealth = ConnectionViewModel.HealthStatus.Unknown,
            ),
        )
        assertEquals(
            ChatConnectState.Connecting,
            resolveChatConnectState(
                hydrated = true,
                connection = configured,
                ready = false,
                gatewayAvailability = GatewayAvailability.Unknown,
                apiHealth = ConnectionViewModel.HealthStatus.Unknown,
            ),
        )
    }

    @Test
    fun `gateway footer derives Dashboard route without an API endpoint`() {
        val connection = connection(dashboardUrl = "https://host.ts.net:9119")
        val unrelatedApiRoute = EndpointCandidate(
            role = "lan",
            api = ApiEndpoint("192.168.1.20", 8642),
            relay = RelayEndpoint("ws://192.168.1.20:8767"),
        )

        val route = resolveFooterRouteCandidate(
            runtimeStatus = ChatRuntimeStatus.Connected(
                transport = ChatTransportPath.Gateway,
                fallback = false,
            ),
            activeEndpoint = unrelatedApiRoute,
            connection = connection,
            effectiveDashboardUrl = connection.resolvedDashboardUrl,
        )

        assertEquals("tailscale", route?.role)
        assertEquals("https://host.ts.net:9119", route?.dashboard?.url)
        assertNull(route?.api)
    }

    @Test
    fun `transient add draft is ready without a persisted fake connection`() {
        assertTrue(
            resolvePairSetupReady(
                storeHydrated = true,
                connectionId = "draft-id",
                authorizedHandoffId = null,
                activeConnectionId = "saved-id",
                connectionIds = setOf("saved-id"),
                draftConnectionId = "draft-id",
            ),
        )
    }

    @Test
    fun `gateway footer calls the authenticated Dashboard origin HTTP`() {
        val connection = connection(
            dashboardUrl = "http://192.168.1.20:9119",
            authenticatedDashboardOrigin = "https://hermes.example.com",
        )
        val status = connected(ChatTransportPath.Gateway)
        val route = resolveFooterRouteCandidate(
            runtimeStatus = status,
            activeEndpoint = EndpointCandidate(
                role = "lan",
                dashboard = DashboardEndpoint("http://192.168.1.20:9119"),
            ),
            connection = connection,
            effectiveDashboardUrl = connection.resolvedDashboardUrl,
        )

        assertEquals("HTTP", resolveFooterRouteLabel(status, route, fallbackLabel = "Hermes"))
    }

    @Test
    fun `gateway footer preserves meaningful LAN and Tailscale labels`() {
        assertEquals(
            "LAN",
            resolveFooterRouteLabel(
                runtimeStatus = connected(ChatTransportPath.Gateway),
                route = EndpointCandidate(
                    role = "lan",
                    dashboard = DashboardEndpoint("http://192.168.1.20:9119"),
                ),
                fallbackLabel = "Hermes",
            ),
        )
        assertEquals(
            "Tailscale",
            resolveFooterRouteLabel(
                runtimeStatus = connected(ChatTransportPath.Gateway),
                route = EndpointCandidate(
                    role = "tailscale",
                    dashboard = DashboardEndpoint("https://host.tailnet.ts.net"),
                ),
                fallbackLabel = "Hermes",
            ),
        )
    }

    @Test
    fun `offline footer omits stale route identity`() {
        assertEquals(
            "",
            resolveFooterRouteLabel(
                runtimeStatus = ChatRuntimeStatus.Unavailable,
                route = EndpointCandidate(
                    role = "lan",
                    dashboard = DashboardEndpoint("http://192.168.1.20:9119"),
                ),
                fallbackLabel = "Hermes",
            ),
        )
    }

    @Test
    fun `API fallback footer keeps its actual route label`() {
        assertEquals(
            "Tailscale",
            resolveFooterRouteLabel(
                runtimeStatus = connected(ChatTransportPath.ApiSse),
                route = EndpointCandidate(
                    role = "tailscale",
                    api = ApiEndpoint("100.75.1.2", 8642, tls = true),
                ),
                fallbackLabel = "Hermes",
            ),
        )
    }

    private fun connected(path: ChatTransportPath) =
        ChatRuntimeStatus.Connected(transport = path, fallback = path == ChatTransportPath.ApiSse)

    private fun connection(
        dashboardUrl: String? = null,
        authenticatedDashboardOrigin: String? = null,
        apiServerUrl: String = "",
        relayUrl: String = "",
    ) = Connection(
        id = "connection",
        label = "Hermes",
        apiServerUrl = apiServerUrl,
        relayUrl = relayUrl,
        tokenStoreKey = "hermes_auth_connection",
        dashboardUrl = dashboardUrl,
        authenticatedDashboardOrigin = authenticatedDashboardOrigin,
    )
}

package com.hermesandroid.relay.network.usage

import com.hermesandroid.relay.network.relay.RelayHttpClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** Relay-enhanced usage with an upstream fallback for hosts without Relay support. */
class ProviderUsageRepository(
    private val gatewayClientProvider: () -> GatewayChatClient?,
    private val dashboardClientProvider: () -> DashboardApiClient? = { null },
    private val relayHttpClient: RelayHttpClient,
    private val profileProvider: () -> String? = { null },
    private val sessionProvider: () -> String? = { null },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    suspend fun fetch(): Result<ProviderUsageResponse?> {
        val profile = profileProvider()
        val session = sessionProvider()
        val dashboard = dashboardClientProvider()
        if (dashboard != null) {
            val enhanced = dashboard.getProviderUsage(profile, session)
            if (enhanced.isSuccess && enhanced.getOrNull() != null) return enhanced
        }

        val relay = relayHttpClient.fetchProviderUsage(
            profile = profile,
            sessionId = session,
        )
        if (relay.isSuccess && relay.getOrNull() != null) return relay

        val gateway = gatewayClientProvider()
        if (gateway != null) {
            val upstream = gateway.providerUsage()
                .mapCatching { json.decodeFromJsonElement<ProviderUsageResponse>(it) }
            if (upstream.isSuccess) return upstream
        }
        return relay
    }
}

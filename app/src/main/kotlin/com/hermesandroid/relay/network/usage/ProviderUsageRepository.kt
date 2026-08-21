package com.hermesandroid.relay.network.usage

import com.hermesandroid.relay.network.relay.RelayHttpClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** Upstream-first account usage with an optional Relay compatibility fallback. */
class ProviderUsageRepository(
    private val gatewayClientProvider: () -> GatewayChatClient?,
    private val relayHttpClient: RelayHttpClient,
    private val profileProvider: () -> String? = { null },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    suspend fun fetch(): Result<ProviderUsageResponse?> {
        val gateway = gatewayClientProvider()
        if (gateway != null) {
            val upstream = gateway.providerUsage()
                .mapCatching { json.decodeFromJsonElement<ProviderUsageResponse>(it) }
            if (upstream.isSuccess) return upstream
        }
        return relayHttpClient.fetchProviderUsage(profileProvider())
    }
}

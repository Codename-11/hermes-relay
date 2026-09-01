package com.hermesandroid.relay.network.usage

import com.hermesandroid.relay.network.relay.RelayHttpClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Upstream usage enriched by the optional Relay provider/pool surface. */
class ProviderUsageRepository(
    private val gatewayClientProvider: () -> GatewayChatClient?,
    private val dashboardClientProvider: () -> DashboardApiClient? = { null },
    private val relayHttpClient: RelayHttpClient,
    private val profileProvider: () -> String? = { null },
    private val sessionProvider: () -> String? = { null },
) {
    suspend fun fetch(): Result<ProviderUsageResponse?> {
        val profile = profileProvider()
        val session = sessionProvider()
        val upstream: Result<ProviderUsageResponse?> = gatewayClientProvider()
            ?.usageBars()
            ?.mapCatching(::providerUsageFromUpstreamBars)
            ?: Result.success(null)

        val dashboard = dashboardClientProvider()
        var enhancement: Result<ProviderUsageResponse?>? = null
        if (dashboard != null) {
            enhancement = dashboard.getProviderUsage(profile, session)
        }

        if (enhancement?.getOrNull() == null) {
            enhancement = relayHttpClient.fetchProviderUsage(
                profile = profile,
                sessionId = session,
            )
        }

        val merged = mergeProviderUsage(
            upstream = upstream.getOrNull(),
            enhancement = enhancement?.getOrNull(),
        )
        if (merged != null) return Result.success(merged)

        return when {
            upstream.isFailure && enhancement?.isFailure == true ->
                Result.failure(enhancement?.exceptionOrNull()!!)
            enhancement?.isFailure == true -> Result.failure(enhancement?.exceptionOrNull()!!)
            else -> Result.success(null)
        }
    }
}

internal fun providerUsageFromUpstreamBars(root: JsonObject): ProviderUsageResponse? {
    if (root.boolean("available") != true) return null

    val renewsAt = root.string("renews_at")
    val windows = listOfNotNull(
        root.usageWindow("plan", "Plan", renewsAt),
        root.usageWindow("topup", "Top-up", null),
    )
    val details = listOfNotNull(
        root.string("subscription_remaining_display")?.let { "Subscription remaining: $it" },
        root.string("topup_remaining_display")?.let { "Top-up remaining: $it" },
        root.string("total_spendable_display")?.let { "Total spendable: $it" },
    )

    return ProviderUsageResponse(
        providers = listOf(
            ProviderUsageProvider(
                id = "nous",
                displayName = "Nous",
                status = ProviderUsageProvider.STATUS_AVAILABLE,
                source = "upstream:usage.bars",
                plan = root.string("plan_name"),
                windows = windows,
                details = details,
                renewsAt = renewsAt,
            ),
        ),
    )
}

internal fun mergeProviderUsage(
    upstream: ProviderUsageResponse?,
    enhancement: ProviderUsageResponse?,
): ProviderUsageResponse? {
    if (upstream == null) return enhancement
    if (enhancement == null) return upstream

    val providers = linkedMapOf<String, ProviderUsageProvider>()
    upstream.providers.forEach { providers[it.id] = it }
    enhancement.providers.forEach { enhanced ->
        val standard = providers[enhanced.id]
        providers[enhanced.id] = when {
            standard == null -> enhanced
            standard.available -> standard.copy(
                // Official usage.bars stays authoritative for every field it
                // supplies. Relay enriches the row with pool/balance metadata
                // and fills only gaps that upstream left absent.
                fetchedAt = standard.fetchedAt ?: enhanced.fetchedAt,
                plan = standard.plan ?: enhanced.plan,
                windows = standard.windows.ifEmpty { enhanced.windows },
                details = (standard.details + enhanced.details).distinct(),
                balances = enhanced.balances,
                renewsAt = standard.renewsAt ?: enhanced.renewsAt,
                actionUrl = standard.actionUrl ?: enhanced.actionUrl,
                credentials = enhanced.credentials,
                activeCredentialId = enhanced.activeCredentialId,
                activeCredentialState = enhanced.activeCredentialState,
                activeObservedAt = enhanced.activeObservedAt,
                message = standard.message ?: enhanced.message,
            )
            enhanced.available -> enhanced
            else -> enhanced
        }
    }
    return ProviderUsageResponse(
        schemaVersion = maxOf(upstream.schemaVersion, enhancement.schemaVersion),
        fetchedAt = enhancement.fetchedAt ?: upstream.fetchedAt,
        capabilities = upstream.capabilities + enhancement.capabilities,
        providers = providers.values.toList(),
    )
}

private fun JsonObject.usageWindow(
    id: String,
    label: String,
    resetAt: String?,
): ProviderUsageWindow? {
    val bar = this["${id}_bar"] as? JsonObject ?: return null
    val remaining = bar.string("remaining_display")
    val total = bar.string("total_display")
    val detail = when {
        remaining != null && total != null -> "$remaining remaining of $total"
        remaining != null -> "$remaining remaining"
        total != null -> "$total total"
        else -> null
    }
    return ProviderUsageWindow(
        id = id,
        label = label,
        usedPercent = bar.double("pct_used"),
        resetAt = resetAt,
        detail = detail,
    )
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

package com.hermesandroid.relay.network.usage

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderUsageRepositoryTest {
    @Test
    fun mapsOfficialUpstreamUsageBars() {
        val response = providerUsageFromUpstreamBars(buildJsonObject {
            put("ok", true)
            put("available", true)
            put("plan_name", "Pro")
            put("renews_at", "2026-09-15T00:00:00Z")
            put("subscription_remaining_display", "\$12.50")
            put("topup_remaining_display", "\$3.00")
            put("total_spendable_display", "\$15.50")
            put("plan_bar", buildJsonObject {
                put("remaining_display", "\$12.50")
                put("total_display", "\$20.00")
                put("pct_used", 37.5)
            })
            put("topup_bar", buildJsonObject {
                put("remaining_display", "\$3.00")
                put("total_display", "\$5.00")
                put("pct_used", 40.0)
            })
        })!!

        val nous = response.providers.single()
        assertEquals("nous", nous.id)
        assertEquals("upstream:usage.bars", nous.source)
        assertEquals("Pro", nous.plan)
        assertEquals("2026-09-15T00:00:00Z", nous.renewsAt)
        assertEquals(37.5, nous.windows.first().usedPercent!!, 0.001)
        assertEquals("\$12.50 remaining of \$20.00", nous.windows.first().detail)
        assertEquals(3, nous.details.size)
    }

    @Test
    fun unavailableUpstreamUsageIsCapabilityAbsence() {
        assertNull(providerUsageFromUpstreamBars(buildJsonObject {
            put("ok", true)
            put("available", false)
        }))
    }

    @Test
    fun relayEnhancementAddsProvidersAndEnrichesNous() {
        val upstream = ProviderUsageResponse(
            providers = listOf(
                ProviderUsageProvider(
                    id = "nous",
                    displayName = "Nous",
                    status = ProviderUsageProvider.STATUS_AVAILABLE,
                    source = "upstream:usage.bars",
                    plan = "Pro",
                    windows = listOf(ProviderUsageWindow("plan", "Plan", usedPercent = 25.0)),
                    details = listOf("Total spendable: \$10.00"),
                ),
            ),
        )
        val enhanced = ProviderUsageResponse(
            schemaVersion = 2,
            capabilities = ProviderUsageResponse.RELAY_ENHANCED_CAPABILITIES,
            providers = listOf(
                ProviderUsageProvider(
                    id = "nous",
                    displayName = "Nous",
                    status = ProviderUsageProvider.STATUS_AVAILABLE,
                    source = "relay",
                    balances = listOf(ProviderUsageBalance("total", "Total usable", 10.0)),
                ),
                ProviderUsageProvider(
                    id = "openai-codex",
                    displayName = "Codex",
                    status = ProviderUsageProvider.STATUS_AVAILABLE,
                ),
            ),
        )

        val merged = mergeProviderUsage(upstream, enhanced)!!

        assertTrue(merged.relayEnhanced)
        assertEquals(listOf("nous", "openai-codex"), merged.providers.map { it.id })
        val nous = merged.providers.first()
        assertEquals("Pro", nous.plan)
        assertEquals(25.0, nous.windows.single().usedPercent!!, 0.001)
        assertEquals(10.0, nous.balances.single().amount, 0.001)
        assertEquals(listOf("Total spendable: \$10.00"), nous.details)
    }

    @Test
    fun unavailableEnhancementDoesNotReplaceAvailableUpstream() {
        val upstreamProvider = ProviderUsageProvider(
            id = "nous",
            displayName = "Nous",
            status = ProviderUsageProvider.STATUS_AVAILABLE,
            source = "upstream:usage.bars",
        )
        val merged = mergeProviderUsage(
            ProviderUsageResponse(providers = listOf(upstreamProvider)),
            ProviderUsageResponse(providers = listOf(
                upstreamProvider.copy(
                    status = ProviderUsageProvider.STATUS_UNAVAILABLE,
                    source = "relay",
                ),
            )),
        )!!

        assertEquals("upstream:usage.bars", merged.providers.single().source)
    }
}

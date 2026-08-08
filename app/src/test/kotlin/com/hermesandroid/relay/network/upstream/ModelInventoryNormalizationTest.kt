package com.hermesandroid.relay.network.upstream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInventoryNormalizationTest {

    @Test
    fun exactReportedIdentityIsPublishedOnceWithinItsProvider() {
        val provider = parseGatewayModelProvider(
            Json.parseToJsonElement(
                """
                {
                  "slug": "deepseek",
                  "name": "DeepSeek",
                  "models": ["deepseek-v4-flash", "deepseek-v4-flash", " deepseek-v4-flash "]
                }
                """.trimIndent(),
            ).jsonObject,
        )

        assertEquals(listOf("deepseek-v4-flash"), provider?.models)
    }

    @Test
    fun repeatedProviderRowsMergeModelsAndReasoningCapabilities() {
        val providers = normalizeGatewayModelProviders(
            listOf(
                provider(
                    slug = "DeepSeek",
                    models = listOf("deepseek-v4-flash"),
                    capabilities = mapOf(
                        "deepseek-v4-flash" to GatewayModelCapabilities(reasoning = true),
                    ),
                    authenticated = false,
                ),
                provider(
                    slug = "deepseek",
                    models = listOf("deepseek-v4-flash", "deepseek-v4-pro"),
                    capabilities = mapOf(
                        "deepseek-v4-flash" to GatewayModelCapabilities(
                            reasoningEfforts = listOf("low", "high"),
                            reasoningEffortsExact = true,
                        ),
                    ),
                    authenticated = true,
                ),
            ),
        )

        assertEquals(1, providers.size)
        assertEquals(listOf("deepseek-v4-flash", "deepseek-v4-pro"), providers.single().models)
        assertTrue(providers.single().authenticated)
        val capability = providers.single().capabilities.getValue("deepseek-v4-flash")
        assertEquals(true, capability.reasoning)
        assertEquals(listOf("low", "high"), capability.reasoningEfforts)
        assertEquals(true, capability.reasoningEffortsExact)
    }

    @Test
    fun sameModelUnderDistinctProvidersRemainsTwoChoices() {
        val providers = normalizeGatewayModelProviders(
            listOf(
                provider("deepseek", listOf("deepseek-v4-flash")),
                provider("opencode-go", listOf("deepseek-v4-flash")),
            ),
        )

        assertEquals(listOf("deepseek", "opencode-go"), providers.map { it.slug })
        assertTrue(providers.all { it.models == listOf("deepseek-v4-flash") })
    }

    @Test
    fun refreshNormalizationIsDeterministicAndDoesNotAccumulateRows() {
        val payload = listOf(
            provider("deepseek", listOf("deepseek-v4-flash", "deepseek-v4-flash")),
            provider("deepseek", listOf("deepseek-v4-pro")),
        )

        val first = normalizeGatewayModelProviders(payload)
        val reloaded = normalizeGatewayModelProviders(first + payload)

        assertEquals(first, reloaded)
        assertEquals(listOf("deepseek-v4-flash", "deepseek-v4-pro"), reloaded.single().models)
    }

    private fun provider(
        slug: String,
        models: List<String>,
        capabilities: Map<String, GatewayModelCapabilities> = emptyMap(),
        authenticated: Boolean = true,
    ) = GatewayModelProvider(
        name = "DeepSeek",
        slug = slug,
        models = models,
        isCurrent = false,
        warning = null,
        authenticated = authenticated,
        capabilities = capabilities,
    )
}

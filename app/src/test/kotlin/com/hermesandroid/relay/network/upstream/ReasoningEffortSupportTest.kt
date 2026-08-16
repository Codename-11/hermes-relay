package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.viewmodel.reconcilePendingReasoningEffort
import com.hermesandroid.relay.viewmodel.isCurrentReasoningResponse
import com.hermesandroid.relay.viewmodel.isCurrentReasoningCapabilityOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEffortSupportTest {
    @Test
    fun canonicalNormalizationPreservesMaxAndUltra() {
        assertEquals(
            listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"),
            ReasoningEfforts.canonical,
        )
        assertEquals("max", ReasoningEfforts.normalize(" MAX "))
        assertEquals("ultra", ReasoningEfforts.normalize("Ultra"))
        assertEquals("medium", ReasoningEfforts.normalize("future-value"))
    }

    @Test
    fun exactProviderModelEffortsAreAuthoritative() {
        val provider = provider(
            slug = "opencode",
            model = "deepseek-v3",
            capabilities = GatewayModelCapabilities(
                reasoning = true,
                reasoningEfforts = listOf("low", "high", "max", "max", "unknown"),
                reasoningEffortsExact = true,
            ),
        )

        val result = resolveReasoningEffortAvailability(
            providers = listOf(provider),
            provider = "opencode",
            model = "deepseek-v3",
        )

        assertEquals(listOf("low", "high", "max"), result.choices)
        assertTrue(result.exact)
        assertTrue(result.accepts("max"))
        assertFalse(result.accepts("ultra"))
    }

    @Test
    fun explicitUpstreamReasoningFalseDisablesWithoutAnExactOverlay() {
        val result = resolveReasoningEffortAvailability(
            providers = listOf(
                provider(
                    slug = "local",
                    model = "plain-model",
                    capabilities = GatewayModelCapabilities(
                        reasoning = false,
                    ),
                ),
            ),
            provider = "local",
            model = "plain-model",
        )

        assertEquals(false, result.supported)
        assertTrue(result.choices.isEmpty())
        assertFalse(result.accepts("low"))
    }

    @Test
    fun relayExactOverlayPrecedesUpstreamFalseButNotUpstreamExact() {
        val identity = ReasoningEffortIdentity(provider = "opencode", model = "deepseek-v3")
        val upstreamFalse = provider(
            slug = identity.provider,
            model = identity.model,
            capabilities = GatewayModelCapabilities(reasoning = false),
        )
        val relayOverlay = mapOf(
            identity to GatewayModelCapabilities(
                reasoning = true,
                reasoningEfforts = listOf("low", "max"),
                reasoningEffortsExact = true,
            ),
        )

        val relayWins = resolveReasoningEffortAvailability(
            providers = listOf(upstreamFalse),
            provider = identity.provider,
            model = identity.model,
            relayCapabilities = relayOverlay,
        )
        assertEquals(listOf("low", "max"), relayWins.choices)
        assertTrue(relayWins.exact)

        val upstreamExact = upstreamFalse.copy(
            capabilities = mapOf(
                identity.model to GatewayModelCapabilities(
                    reasoning = true,
                    reasoningEfforts = listOf("high", "ultra"),
                    reasoningEffortsExact = true,
                ),
            ),
        )
        val upstreamWins = resolveReasoningEffortAvailability(
            providers = listOf(upstreamExact),
            provider = identity.provider,
            model = identity.model,
            relayCapabilities = relayOverlay,
        )
        assertEquals(listOf("high", "ultra"), upstreamWins.choices)
    }

    @Test
    fun missingOrNonExactCapabilityFallsBackToCanonicalUnknownContract() {
        val missing = resolveReasoningEffortAvailability(emptyList(), "openai", "gpt-5.5")
        assertNull(missing.supported)
        assertEquals(ReasoningEfforts.canonical, missing.choices)

        val advisory = resolveReasoningEffortAvailability(
            providers = listOf(
                provider(
                    slug = "openai",
                    model = "gpt-5.5",
                    capabilities = GatewayModelCapabilities(
                        reasoningEfforts = listOf("high", "max"),
                        reasoningEffortsExact = false,
                    ),
                ),
            ),
            provider = "openai",
            model = "gpt-5.5",
        )
        assertFalse(advisory.exact)
        assertEquals(ReasoningEfforts.canonical, advisory.choices)
    }

    @Test
    fun capabilityLookupNeverBorrowsSameModelFromAnotherProvider() {
        val openCode = provider(
            slug = "opencode",
            model = "deepseek-v3",
            capabilities = GatewayModelCapabilities(
                reasoning = true,
                reasoningEfforts = listOf("low", "max"),
                reasoningEffortsExact = true,
            ),
        )
        val openRouter = provider(
            slug = "openrouter",
            model = "deepseek-v3",
            capabilities = GatewayModelCapabilities(
                reasoning = true,
                reasoningEfforts = listOf("low", "ultra"),
                reasoningEffortsExact = true,
            ),
        )

        val result = resolveReasoningEffortAvailability(
            providers = listOf(openCode, openRouter),
            provider = "openrouter",
            model = "deepseek-v3",
        )

        assertEquals(listOf("low", "ultra"), result.choices)
        val incompleteIdentity = resolveReasoningEffortAvailability(
            providers = listOf(openCode, openRouter),
            provider = null,
            model = "deepseek-v3",
        )
        assertNull(incompleteIdentity.supported)
        assertEquals(ReasoningEfforts.canonical, incompleteIdentity.choices)
    }

    @Test
    fun modelChangeClearsOnlyIncompatiblePendingEffort() {
        val availability = ReasoningEffortAvailability(
            supported = true,
            choices = listOf("low", "high", "max"),
            exact = true,
        )
        val modelA = ReasoningEffortIdentity(provider = "openrouter", model = "model-a")
        val modelB = ReasoningEffortIdentity(provider = "opencode", model = "model-b")

        assertNull(
            reconcilePendingReasoningEffort(
                value = "ultra",
                confirmedIdentity = null,
                activeIdentity = modelB,
                availability = availability,
            ),
        )
        assertFalse(
            isCurrentReasoningResponse(
                capturedRevision = 7L,
                currentRevision = 8L,
                capturedIdentity = modelA,
                activeIdentity = modelB,
            ),
        )
        assertEquals(
            "max",
            reconcilePendingReasoningEffort(
                value = "max",
                confirmedIdentity = null,
                activeIdentity = modelB,
                availability = availability,
            ),
        )
        assertEquals(
            "ultra",
            reconcilePendingReasoningEffort(
                value = "ultra",
                confirmedIdentity = modelB,
                activeIdentity = modelB,
                availability = availability,
            ),
        )
        assertNull(
            reconcilePendingReasoningEffort(
                value = "ultra",
                confirmedIdentity = modelA,
                activeIdentity = modelB,
                availability = availability,
            ),
        )
    }

    @Test
    fun relayOverlayResponseRequiresCurrentGenerationAndProfileContext() {
        assertTrue(isCurrentReasoningCapabilityOverlay(4L, 4L, "conn::profile", "conn::profile"))
        assertFalse(isCurrentReasoningCapabilityOverlay(3L, 4L, "conn::profile", "conn::profile"))
        assertFalse(isCurrentReasoningCapabilityOverlay(4L, 4L, "conn::a", "conn::b"))
    }

    private fun provider(
        slug: String,
        model: String,
        capabilities: GatewayModelCapabilities,
    ) = GatewayModelProvider(
        name = slug,
        slug = slug,
        models = listOf(model),
        isCurrent = false,
        warning = null,
        capabilities = mapOf(model to capabilities),
    )
}

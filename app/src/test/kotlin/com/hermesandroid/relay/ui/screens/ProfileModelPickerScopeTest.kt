package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.network.upstream.GatewayModelProvider
import com.hermesandroid.relay.network.upstream.ApiModelOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileModelPickerScopeTest {
    @Test fun gatewayFallbackIgnoresInstalledBotDefaultsAndUnroutedServerModel() {
        val serverDefaults = listOf("gpt-6.1-terra", "chatgpt-web/gpt-6-sol",
            "gpt-6-luna", "reflection/beam-1.0-501b-a23b:free")
        // A root API server advertising its default is not a list of other bots.
        val fallback = fallbackModelPickerOptions(
            apiModelOptions = listOf(ApiModelOption("gpt-6.1-terra")),
            selectedModel = null,
            isGatewayTransport = true,
        )
        assertTrue(fallback.isEmpty())
        assertTrue(serverDefaults.none { model -> fallback.any { it.id == model } })
    }

    @Test fun genuineApiRouteSurvivesGatewayFallback() {
        val options = fallbackModelPickerOptions(
            listOf(ApiModelOption("gpt-6.1-terra"),
                ApiModelOption("my-route", root = "provider/model")),
            selectedModel = null,
            isGatewayTransport = true,
        )
        assertEquals(listOf("my-route"), options.map { it.id })
    }

    @Test fun directApiRetainsAdvertisedRequestIds() {
        val options = fallbackModelPickerOptions(
            listOf(ApiModelOption("my-direct-model")),
            selectedModel = null,
            isGatewayTransport = false,
        )
        assertEquals(listOf("my-direct-model"), options.map { it.id })
    }
    private val sol = GatewayModelProvider("GPT Web", "codex-web-gpt",
        listOf("chatgpt-web/gpt-6-sol", "chatgpt-web/gpt-6-sol-instant"),
        isCurrent = true, warning = null)
    private val terra = GatewayModelProvider("Codex", "openai-codex",
        listOf("gpt-6.1-terra", "gpt-6-luna"), isCurrent = false, warning = null)

    @Test fun gptProfileDisplaysExactlyItsTwoModelsWhenCatalogIsAvailable() {
        val scope = scopeProfileModelPicker("chatgpt", "chatgpt-web/gpt-6-sol",
            listOf(terra, sol), listOf("gpt-6.1-terra", "gpt-6-luna", "chatgpt-web/gpt-6-sol"))
        assertEquals(listOf(sol), scope.providers)
        assertEquals(listOf("chatgpt-web/gpt-6-sol"), scope.fallbackModelIds)
        assertFalse(scope.showDefaultRow)
        assertTrue(scope.isGptWebProfile)
    }

    @Test fun staleGatewayDoesNotShowOtherProfilesModels() {
        val scope = scopeProfileModelPicker("chatgpt", "chatgpt-web/gpt-6-sol",
            emptyList(), listOf("gpt-6.1-terra", "gpt-6-luna", "reflection/beam"))
        assertTrue(scope.providers.isEmpty())
        assertEquals(listOf("chatgpt-web/gpt-6-sol"), scope.fallbackModelIds)
        assertTrue(scope.showDefaultRow)
    }

    @Test fun mainAndOtherProfilesRetainTheirExistingOptions() {
        for (profile in listOf(null, "freelancer", "crypto-trader")) {
            val defaults = listOf("gpt-6.1-terra", "gpt-6-luna")
            val scope = scopeProfileModelPicker(profile, "gpt-6.1-terra", listOf(terra, sol), defaults)
            assertEquals(listOf(terra, sol), scope.providers)
            assertEquals(defaults, scope.fallbackModelIds)
            assertTrue(scope.showDefaultRow)
            assertFalse(scope.isGptWebProfile)
        }
    }
}

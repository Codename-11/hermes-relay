package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.network.upstream.ApiModelOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerFallbackTest {
    @Test fun gatewayFallbackIgnoresInstalledBotDefaultsAndUnroutedServerModel() {
        val serverDefaults = listOf("provider-a/model-one", "provider-b/model-two",
            "provider-c/model-three")
        // A root API server advertising its default is not a list of other bots.
        val fallback = fallbackModelPickerOptions(
            apiModelOptions = listOf(ApiModelOption("provider-a/model-one")),
            selectedModel = null,
            isGatewayTransport = true,
        )
        assertTrue(fallback.isEmpty())
        assertTrue(serverDefaults.none { model -> fallback.any { it.id == model } })
    }

    @Test fun genuineApiRouteSurvivesGatewayFallback() {
        val options = fallbackModelPickerOptions(
            listOf(ApiModelOption("provider-a/model-one"),
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
}

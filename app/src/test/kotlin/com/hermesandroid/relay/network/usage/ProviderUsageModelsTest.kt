package com.hermesandroid.relay.network.usage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderUsageModelsTest {
    @Test
    fun completeRelayCapabilitySetIsEnhanced() {
        assertTrue(
            ProviderUsageResponse(
                capabilities = ProviderUsageResponse.RELAY_ENHANCED_CAPABILITIES,
            ).relayEnhanced,
        )
    }

    @Test
    fun missingOrPartialCapabilitiesRemainBasic() {
        assertFalse(ProviderUsageResponse().relayEnhanced)
        assertFalse(
            ProviderUsageResponse(
                capabilities = setOf("credential_pools", "structured_balances"),
            ).relayEnhanced,
        )
    }
}

package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.data.GatewayProfileAuthChoice
import com.hermesandroid.relay.data.GatewayProfileCreateRequest
import com.hermesandroid.relay.data.GatewayProfileManagementUnsupportedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCreateFallbackPolicyTest {
    private val unsupported = GatewayProfileManagementUnsupportedException("profiles.create")

    @Test
    fun `legacy shared create may fall back to authenticated Dashboard`() {
        assertTrue(
            shouldUseLegacyDashboardProfileCreate(
                GatewayProfileCreateRequest(name = "operator", cloneFrom = "default"),
                unsupported,
                explicitlyAllowed = true,
            ),
        )
    }

    @Test
    fun `explicit isolation and extended fields never degrade to legacy mutation`() {
        assertFalse(
            shouldUseLegacyDashboardProfileCreate(
                GatewayProfileCreateRequest(
                    name = "operator",
                    authChoice = GatewayProfileAuthChoice.Isolated,
                ),
                unsupported,
                explicitlyAllowed = true,
            ),
        )
        assertFalse(
            shouldUseLegacyDashboardProfileCreate(
                GatewayProfileCreateRequest(name = "operator", noSkills = true),
                unsupported,
                explicitlyAllowed = true,
            ),
        )
        assertFalse(
            shouldUseLegacyDashboardProfileCreate(
                GatewayProfileCreateRequest(name = "operator", cloneAll = true),
                unsupported,
                explicitlyAllowed = true,
            ),
        )
        assertFalse(
            shouldUseLegacyDashboardProfileCreate(
                GatewayProfileCreateRequest(name = "operator", model = "model", provider = "provider"),
                unsupported,
                explicitlyAllowed = true,
            ),
        )
        assertFalse(
            shouldUseLegacyDashboardProfileCreate(
                GatewayProfileCreateRequest(name = "operator"),
                IllegalStateException("network failed"),
                explicitlyAllowed = true,
            ),
        )
        assertFalse(
            shouldUseLegacyDashboardProfileCreate(
                GatewayProfileCreateRequest(name = "operator"),
                unsupported,
                explicitlyAllowed = false,
            ),
        )
    }
}

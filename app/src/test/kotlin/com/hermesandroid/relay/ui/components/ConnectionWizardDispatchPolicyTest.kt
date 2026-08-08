package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionWizardDispatchPolicyTest {
    @Test
    fun dashboardOnlyQrUsesDashboardOwner() {
        val payload = HermesPairingPayload(
            dashboardUrl = "https://agent.example.com/hermes",
        )

        assertEquals(SetupQrDispatch.Dashboard, setupQrDispatch(payload))
    }

    @Test
    fun apiQrKeepsStandardApiOwnerEvenWhenDashboardIsIncluded() {
        val payload = HermesPairingPayload(
            host = "api.example.com",
            tls = true,
            dashboardUrl = "https://agent.example.com/hermes",
        )

        assertEquals(SetupQrDispatch.StandardApi, setupQrDispatch(payload))
    }

    @Test
    fun relayQrKeepsRelayOwnerWithOrWithoutApi() {
        val relay = RelayPairing(
            url = "wss://relay.example.com:8767",
            code = "ABC123",
        )

        assertEquals(
            SetupQrDispatch.Relay,
            setupQrDispatch(HermesPairingPayload(host = "api.example.com", relay = relay)),
        )
        assertEquals(
            SetupQrDispatch.Relay,
            setupQrDispatch(
                HermesPairingPayload(
                    dashboardUrl = "https://agent.example.com/hermes",
                    relay = relay,
                ),
            ),
        )
    }

    @Test
    fun wizardMotionRequiresOsAnimationsWithoutTouchExploration() {
        assertTrue(
            shouldAnimateWizardTransitions(
                AccessibleMotionState(osAnimations = true, touchExploration = false),
            ),
        )
        assertFalse(
            shouldAnimateWizardTransitions(
                AccessibleMotionState(osAnimations = false, touchExploration = false),
            ),
        )
        assertFalse(
            shouldAnimateWizardTransitions(
                AccessibleMotionState(osAnimations = true, touchExploration = true),
            ),
        )
    }
}

package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.bridge.BridgeCapability
import com.hermesandroid.relay.bridge.BridgeCapabilityPolicy
import com.hermesandroid.relay.viewmodel.BridgePermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeAccessPresentationTest {
    @Test
    fun presetsMapToStableCapabilitySets() {
        assertEquals(
            BridgeAccessPreset.READ_ONLY,
            BridgeCapabilityPolicy(
                permanentGrants = READ_ONLY_BRIDGE_CAPABILITIES,
            ).displayPreset(),
        )
        assertEquals(
            BridgeAccessPreset.READ_CONFIRMED,
            BridgeCapabilityPolicy(
                permanentGrants = READ_CONFIRMED_BRIDGE_CAPABILITIES,
            ).displayPreset(),
        )
        assertEquals(
            BridgeAccessPreset.CUSTOM,
            BridgeCapabilityPolicy(
                permanentGrants = setOf(BridgeCapability.CLIPBOARD_READ),
            ).displayPreset(),
        )
    }

    @Test
    fun androidSummaryIncludesOnlySelectedCapabilityRequirements() {
        val summary = bridgeAndroidAccessSummary(
            policy = BridgeCapabilityPolicy(
                permanentGrants = READ_ONLY_BRIDGE_CAPABILITIES,
            ),
            status = BridgePermissionStatus(
                accessibilityServiceEnabled = true,
                contactsPermitted = true,
                locationPermitted = false,
                microphonePermitted = false,
                cameraPermitted = false,
            ),
            nowMs = 100L,
        )

        assertEquals(
            setOf(
                BridgeAndroidRequirement.ACCESSIBILITY,
                BridgeAndroidRequirement.CONTACTS,
                BridgeAndroidRequirement.LOCATION,
            ),
            summary.required,
        )
        assertEquals(setOf(BridgeAndroidRequirement.LOCATION), summary.missing)
        assertFalse(summary.allReady)
    }

    @Test
    fun screenControlRequiresOverlayButInspectionDoesNot() {
        val inspection = bridgeAndroidAccessSummary(
            policy = BridgeCapabilityPolicy(
                timedExpiriesMs = mapOf(BridgeCapability.SCREEN_INSPECTION to 1_000L),
            ),
            status = BridgePermissionStatus(accessibilityServiceEnabled = true),
            nowMs = 100L,
        )
        assertTrue(BridgeAndroidRequirement.ACCESSIBILITY in inspection.required)
        assertFalse(BridgeAndroidRequirement.OVERLAY in inspection.required)

        val control = bridgeAndroidAccessSummary(
            policy = BridgeCapabilityPolicy(
                timedExpiriesMs = mapOf(BridgeCapability.SCREEN_CONTROL to 1_000L),
            ),
            status = BridgePermissionStatus(
                accessibilityServiceEnabled = true,
                overlayPermitted = false,
            ),
            nowMs = 100L,
        )
        assertEquals(setOf(BridgeAndroidRequirement.OVERLAY), control.missing)
    }

    @Test
    fun confirmedActionsExposeOverlayAndRuntimeRequirements() {
        val summary = bridgeAndroidAccessSummary(
            policy = BridgeCapabilityPolicy(
                permanentGrants = READ_CONFIRMED_BRIDGE_CAPABILITIES,
            ),
            status = BridgePermissionStatus(
                accessibilityServiceEnabled = true,
                contactsPermitted = true,
                locationPermitted = true,
                smsPermitted = false,
                phonePermitted = false,
                overlayPermitted = false,
            ),
            nowMs = 0L,
        )
        assertTrue(BridgeAndroidRequirement.SMS in summary.missing)
        assertTrue(BridgeAndroidRequirement.PHONE in summary.missing)
        assertTrue(BridgeAndroidRequirement.OVERLAY in summary.missing)
    }

    @Test
    fun expiredScreenGrantDoesNotAffectPermanentSummary() {
        val policy = BridgeCapabilityPolicy(
            permanentGrants = setOf(BridgeCapability.CONTACTS_READ),
            timedExpiriesMs = mapOf(BridgeCapability.SCREEN_CONTROL to 100L),
        )
        assertEquals(emptySet<BridgeCapability>(), policy.activeTimedCapabilities(100L))
        assertTrue(policy.hasAnyGrant(100L))
    }
}

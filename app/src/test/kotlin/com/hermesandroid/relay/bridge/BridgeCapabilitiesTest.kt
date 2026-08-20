package com.hermesandroid.relay.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeCapabilitiesTest {
    @Test
    fun registryCoversTheCompleteAndroidBridgeRouteInventory() {
        val expected = setOf(
            "GET" to "/ping", "POST" to "/setup", "POST" to "/wait",
            "GET" to "/current_app", "GET" to "/get_apps", "GET" to "/apps",
            "POST" to "/search_contacts", "GET" to "/location",
            "GET" to "/clipboard", "POST" to "/clipboard", "POST" to "/media",
            "POST" to "/call", "POST" to "/send_sms", "POST" to "/share_media",
            "POST" to "/send_mms", "GET" to "/screen", "GET" to "/screenshot",
            "GET" to "/screen_hash", "GET" to "/events", "POST" to "/find_nodes",
            "POST" to "/describe_node", "POST" to "/diff_screen",
            "POST" to "/events/stream", "POST" to "/tap", "POST" to "/tap_text",
            "POST" to "/long_press", "POST" to "/type", "POST" to "/swipe",
            "POST" to "/drag", "POST" to "/scroll", "POST" to "/press_key",
            "POST" to "/open_app", "POST" to "/return_to_hermes",
            "POST" to "/send_intent", "POST" to "/broadcast",
        )
        assertEquals(expected, BridgeCommandRegistry.registeredRoutes())
    }

    @Test
    fun clipboardMethodCannotCrossReadWriteAuthority() {
        assertEquals(
            BridgeCapability.CLIPBOARD_READ,
            BridgeCommandRegistry.resolve("/clipboard", "GET")?.capability,
        )
        assertEquals(
            BridgeCapability.CLIPBOARD_WRITE,
            BridgeCommandRegistry.resolve("/clipboard", "post")?.capability,
        )
    }

    @Test
    fun aliasesResolveToTheSameCapability() {
        assertEquals(
            BridgeCommandRegistry.resolve("/get_apps", "GET"),
            BridgeCommandRegistry.resolve("/apps", "GET"),
        )
    }

    @Test
    fun unknownPathAndWrongMethodFailClosed() {
        assertNull(BridgeCommandRegistry.resolve("/future_command", "POST"))
        assertNull(BridgeCommandRegistry.resolve("/send_sms", "GET"))
    }

    @Test
    fun policySeparatesPermanentAndTimedAuthority() {
        val now = 10_000L
        val policy = BridgeCapabilityPolicy(
            permanentGrants = setOf(BridgeCapability.CONTACTS_READ),
            timedExpiriesMs = mapOf(BridgeCapability.SCREEN_CONTROL to now + 1),
        )
        assertTrue(policy.allows(BridgeCapability.CONTACTS_READ, now))
        assertFalse(policy.allows(BridgeCapability.CLIPBOARD_READ, now))
        assertTrue(policy.allows(BridgeCapability.SCREEN_CONTROL, now))
        assertFalse(policy.allows(BridgeCapability.SCREEN_CONTROL, now + 1))
        assertFalse(policy.allows(BridgeCapability.SCREEN_INSPECTION, now))
    }

    @Test
    fun unlimitedScreenAuthorityRemainsActiveUntilExplicitlyRevoked() {
        val policy = BridgeCapabilityPolicy(
            timedExpiriesMs = mapOf(
                BridgeCapability.SCREEN_CONTROL to BridgeCapabilityPolicy.NEVER_EXPIRES_AT_MS,
            ),
        )
        assertTrue(policy.allows(BridgeCapability.SCREEN_CONTROL, Long.MAX_VALUE - 1))
        assertTrue(policy.isUnlimited(BridgeCapability.SCREEN_CONTROL))
        assertFalse(policy.isUnlimited(BridgeCapability.SCREEN_INSPECTION))
    }
}

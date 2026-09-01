package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.network.upstream.ToolsetInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextCapabilityTest {
    @Test
    fun mediaHint_prefersUpstreamAndFramesRelayAsEnhancement() {
        val hint = buildMediaCapabilityHint(
            upstreamAvailable = true,
            relayAvailable = true,
        )

        assertTrue(hint!!.startsWith("Media display: this client supports standard upstream Hermes"))
        assertTrue(hint.contains("Relay enhancement:"))
        assertTrue(hint.contains("Relay is not required"))
    }

    @Test
    fun mediaHint_supportsStandardUpstreamWithoutRelay() {
        val hint = buildMediaCapabilityHint(
            upstreamAvailable = true,
            relayAvailable = false,
        )

        assertTrue(hint!!.contains("authenticated upstream Dashboard file routes"))
        assertFalse(hint.contains("Relay enhancement:"))
    }

    @Test
    fun mediaHint_isAbsentWithoutAnyDeliveryRoute() {
        assertNull(
            buildMediaCapabilityHint(
                upstreamAvailable = false,
                relayAvailable = false,
            ),
        )
    }

    @Test
    fun sseToolCatalog_includesOnlyEnabledConfiguredToolsets() {
        val names = eligibleSseToolNames(
            listOf(
                ToolsetInfo(
                    name = "android",
                    enabled = true,
                    configured = true,
                    tools = listOf("android_phone_status", "android_tap"),
                ),
                ToolsetInfo(
                    name = "desktop",
                    enabled = true,
                    configured = false,
                    tools = listOf("desktop_screenshot"),
                ),
                ToolsetInfo(
                    name = "terminal",
                    enabled = false,
                    configured = true,
                    tools = listOf("terminal"),
                ),
            ),
        )

        assertTrue(names == setOf("android_phone_status", "android_tap"))
    }
}

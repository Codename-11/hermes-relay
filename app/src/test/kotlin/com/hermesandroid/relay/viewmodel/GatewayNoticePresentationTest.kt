package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.network.upstream.GatewayAgentNotice
import com.hermesandroid.relay.ui.UiMessageBus
import com.hermesandroid.relay.ui.UiMessageSeverity
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayNoticePresentationTest {
    @Test
    fun stickyWarningStripsDuplicateGlyphAndKeepsKey() {
        val presentation = gatewayNoticePresentation(
            GatewayAgentNotice(
                text = "⚠ Credits depleted",
                level = "warn",
                kind = "sticky",
                key = "credits.depleted",
            ),
        )

        assertEquals("Credits depleted", presentation.text)
        assertEquals(UiMessageSeverity.Warning, presentation.severity)
        assertEquals(0L, presentation.ttlMillis)
        assertEquals("credits.depleted", presentation.key)
    }

    @Test
    fun ttlNoticeUsesIdFallbackAndBoundsLifetime() {
        val presentation = gatewayNoticePresentation(
            GatewayAgentNotice(
                text = "  ✓ Credits restored  ",
                level = "success",
                kind = "ttl",
                ttlMs = 600_000L,
                id = "notice-2",
            ),
        )

        assertEquals("Credits restored", presentation.text)
        assertEquals(UiMessageSeverity.Success, presentation.severity)
        assertEquals(60_000L, presentation.ttlMillis)
        assertEquals("notice-2", presentation.key)
    }

    @Test
    fun ttlWithoutDurationUsesExistingBannerDefault() {
        val presentation = gatewayNoticePresentation(
            GatewayAgentNotice(text = "Account update", kind = "ttl"),
        )

        assertEquals(UiMessageBus.DEFAULT_TTL_MS, presentation.ttlMillis)
    }

    @Test
    fun agentNoticePersistsUntilGatewayClearsItsKey() {
        val presentation = gatewayNoticePresentation(
            GatewayAgentNotice(
                text = "Still starting agent",
                kind = "agent",
                key = "agent-startup",
            ),
        )

        assertEquals(0L, presentation.ttlMillis)
        assertEquals("agent-startup", presentation.key)
    }
}

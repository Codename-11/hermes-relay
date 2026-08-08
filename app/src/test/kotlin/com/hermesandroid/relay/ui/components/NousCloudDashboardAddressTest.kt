package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NousCloudDashboardAddressTest {
    @Test
    fun hostedSlugResolvesToCanonicalHttpsOrigin() {
        assertEquals(
            "https://demo-agent-3974.agents.nousresearch.com",
            resolveNousCloudDashboardAddress("demo-agent-3974"),
        )
    }

    @Test
    fun pastedHostedHostnameDoesNotDuplicateSuffix() {
        assertEquals(
            "https://demo-agent-3974.agents.nousresearch.com",
            resolveNousCloudDashboardAddress("demo-agent-3974.agents.nousresearch.com"),
        )
    }

    @Test
    fun explicitCustomUrlRemainsAuthoritative() {
        assertEquals(
            "https://agents.example.com/hermes",
            resolveNousCloudDashboardAddress("https://agents.example.com/hermes"),
        )
    }

    @Test
    fun hostedSlugValidationRejectsUnsafeOrAmbiguousInputs() {
        assertTrue(isValidNousCloudSlug("demo-agent-3974"))
        assertTrue(isValidNousCloudAddressInput("demo-agent-3974.agents.nousresearch.com"))
        assertFalse(isValidNousCloudSlug("https://example.com"))
        assertFalse(isValidNousCloudSlug("agent.example.com"))
        assertFalse(isValidNousCloudSlug("-agent"))
        assertFalse(isValidNousCloudSlug("agent-"))
    }
}

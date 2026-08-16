package com.hermesandroid.relay.viewmodel.connection

import com.hermesandroid.relay.data.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAvatarRefreshPolicyTest {
    @Test
    fun `only latest avatar refresh for exact connection may publish`() {
        assertTrue(isCurrentProfileAvatarRefresh("conn-1", "conn-1", 4, 4))
        assertFalse(isCurrentProfileAvatarRefresh("conn-1", "conn-2", 4, 4))
        assertFalse(isCurrentProfileAvatarRefresh("conn-1", "conn-1", 3, 4))
    }

    @Test
    fun `gateway roster keeps relay only routing fields by exact profile name`() {
        val gateway = Profile(
            name = "operator",
            model = "gpt-5.6",
            provider = "openai",
            description = "Current",
            hasAvatar = true,
        )
        val relay = Profile(
            name = "operator",
            model = "old",
            description = "Old",
            systemMessage = "SOUL",
            gatewayRunning = true,
            apiServerEnabled = true,
            apiServerUrl = "https://host/p/operator",
        )

        val merged = mergeGatewayProfileRoster(listOf(gateway), listOf(relay)).single()
        assertEquals("gpt-5.6", merged.model)
        assertEquals("Current", merged.description)
        assertTrue(merged.hasAvatar)
        assertEquals("SOUL", merged.systemMessage)
        assertEquals("https://host/p/operator", merged.apiServerUrl)
    }

    @Test
    fun `successful empty gateway roster does not resurrect fallback profiles`() {
        val fallback = listOf(Profile(name = "stale", model = "old"))

        assertTrue(selectProfileRoster(true, emptyList(), fallback).isEmpty())
        assertEquals(fallback, selectProfileRoster(false, emptyList(), fallback))
    }
}

package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfilePresenceTest {
    private fun profile(running: Boolean) = Profile(
        name = "coder",
        model = "test-model",
        gatewayRunning = running,
    )

    @Test
    fun `running gateway is online`() {
        assertEquals(ProfilePresence.ONLINE, ProfilePresenceResolver.resolve(profile(true)))
    }

    @Test
    fun `selectable profile without gateway is available on demand`() {
        assertEquals(ProfilePresence.AVAILABLE, ProfilePresenceResolver.resolve(profile(false)))
    }

    @Test
    fun `unreachable host makes profile offline even if last probe said running`() {
        assertEquals(
            ProfilePresence.OFFLINE,
            ProfilePresenceResolver.resolve(profile(true), hostReachable = false),
        )
    }
}

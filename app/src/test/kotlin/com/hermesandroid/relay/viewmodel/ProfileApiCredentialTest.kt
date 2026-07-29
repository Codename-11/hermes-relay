package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileApiCredentialTest {
    @Test
    fun multiplexRouteNeverFallsBackToConnectionKey() {
        assertEquals(
            "",
            profileApiCredential(
                usesMultiplexProfileKey = true,
                profileKey = null,
                connectionKey = "connection-secret",
            ),
        )
        assertEquals(
            "profile-secret",
            profileApiCredential(
                usesMultiplexProfileKey = true,
                profileKey = "profile-secret",
                connectionKey = "connection-secret",
            ),
        )
    }

    @Test
    fun nonMultiplexRouteRetainsConnectionKey() {
        assertEquals(
            "connection-secret",
            profileApiCredential(
                usesMultiplexProfileKey = false,
                profileKey = "profile-secret",
                connectionKey = "connection-secret",
            ),
        )
    }
}

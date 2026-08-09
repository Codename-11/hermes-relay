package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.ui.components.avatar.SphereAvatar
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionViewModelBackgroundAvatarTest {
    @Test
    fun `legacy pet remains the central background on migration`() {
        assertEquals(
            "legacy-pet",
            migratedBackgroundAvatar(
                storedBackgroundAvatar = null,
                legacyAgentAvatar = "legacy-pet",
            ),
        )
    }

    @Test
    fun `new background preference wins and empty history falls back to sphere`() {
        assertEquals("new-background", migratedBackgroundAvatar("new-background", "legacy-pet"))
        assertEquals(SphereAvatar.id, migratedBackgroundAvatar(null, null))
    }
}

package com.hermesandroid.relay.ui

import com.hermesandroid.relay.data.SupervisedAppearance
import com.hermesandroid.relay.data.SupervisedModePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisedAppearancePolicyTest {
    private val policy = SupervisedModePolicy(
        enabled = true,
        pinnedProfileName = "willow",
        appearance = SupervisedAppearance(
            appThemeId = "rose",
            themePreference = "dark",
            showPet = false,
        ),
    )

    @Test fun `locked supervised root uses only its own theme`() {
        val resolved = resolveSupervisedTheme(policy, false, "midnight", "light")

        assertEquals("rose", resolved.appThemeId)
        assertEquals("dark", resolved.themePreference)
        assertFalse(resolved.useGlobalCustomTheme)
    }

    @Test fun `parent access restores ordinary app theme`() {
        val resolved = resolveSupervisedTheme(policy, true, "midnight", "light")

        assertEquals("midnight", resolved.appThemeId)
        assertEquals("light", resolved.themePreference)
        assertTrue(resolved.useGlobalCustomTheme)
    }

    @Test fun `pet visibility follows supervised policy only while locked`() {
        assertFalse(shouldShowPetInSupervisedMode(policy, false))
        assertTrue(shouldShowPetInSupervisedMode(policy, true))
        assertTrue(
            shouldShowPetInSupervisedMode(
                policy.copy(appearance = policy.appearance.copy(showPet = true)),
                false,
            ),
        )
    }

    @Test fun `enabled recovery policy stays on restricted appearance defaults`() {
        val recovery = SupervisedModePolicy(enabled = true)
        val resolved = resolveSupervisedTheme(recovery, false, "rose", "dark")

        assertEquals("hermes-relay", resolved.appThemeId)
        assertEquals("auto", resolved.themePreference)
        assertFalse(resolved.useGlobalCustomTheme)
        assertFalse(shouldShowPetInSupervisedMode(recovery, false))
    }
}

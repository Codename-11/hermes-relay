package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.ProfilePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileShelfPolicyTest {
    @Test
    fun resolvedDefaultGroupsOnlyExactIdentityAndRetainsSelectedRequestKey() {
        val victor = Profile("victor", "", displayName = "Victor")
        val duplicate = Profile("other", "", displayName = "Victor")
        val roster = listOf(Profile("default", ""), victor, duplicate)
        for (selected in listOf(null, "victor", "default", "other")) {
            val choices = ProfileShelfPolicy.choices(roster, ProfilePresentation(), selected, "victor")
            assertEquals(3, choices.size)
            assertTrue(choices.any { ProfileShelfPolicy.isSelected(it, selected) })
            assertTrue(choices.any { it.key == "default" })
            assertTrue(choices.any { it.key == "other" })
            val grouped = choices.first { it.isServerDefault || it.key == "victor" }
            assertEquals(if (selected == "victor") "victor" else null, grouped.profile?.name)
        }
    }

    @Test
    fun unknownDefaultNeverCollapsesRootOrNamesWithMatchingLabels() {
        val roster = listOf(Profile("default", ""), Profile("one", "", displayName = "Same"),
            Profile("two", "", displayName = "Same"))
        assertEquals(4, ProfileShelfPolicy.choices(roster, ProfilePresentation(), null).size)
        assertEquals(4, ProfileShelfPolicy.choices(roster, ProfilePresentation(), null, "missing").size)
    }

    private val profiles = listOf(
        Profile(name = "default", model = "root"),
        Profile(name = "alpha", model = "a"),
        Profile(name = "beta", model = "b"),
    )

    @Test
    fun choicesHonorOrderHiddenAndSelectedException() {
        val choices = ProfileShelfPolicy.choices(
            profiles = profiles,
            presentation = ProfilePresentation(
                order = listOf("beta", "alpha", AgentDisplay.SERVER_DEFAULT_PROFILE_KEY),
                hidden = setOf("alpha", "beta"),
            ),
            selectedProfileName = "beta",
        )

        assertEquals(
            listOf("beta", AgentDisplay.SERVER_DEFAULT_PROFILE_KEY, "default"),
            choices.map { it.key },
        )
        assertTrue(ProfileShelfPolicy.isSelected(choices.first(), "beta"))
    }

    @Test
    fun serverDefaultAndLiteralDefaultAreSeparateChoices() {
        val choices = ProfileShelfPolicy.choices(
            profiles = listOf(Profile(name = "default", model = "root")),
            presentation = ProfilePresentation(),
            selectedProfileName = null,
        )

        assertEquals(
            listOf(AgentDisplay.SERVER_DEFAULT_PROFILE_KEY, "default"),
            choices.map { it.key },
        )
        assertTrue(choices.first().isServerDefault)
        assertFalse(choices.last().isServerDefault)
    }

    @Test
    fun switchingIsBlockedOnlyForLiveSseTurns() {
        assertTrue(ProfileShelfPolicy.canSwitch(isStreaming = false, streamingEndpoint = "sessions"))
        assertTrue(ProfileShelfPolicy.canSwitch(isStreaming = true, streamingEndpoint = "gateway"))
        assertFalse(ProfileShelfPolicy.canSwitch(isStreaming = true, streamingEndpoint = "sessions"))
        assertFalse(ProfileShelfPolicy.canSwitch(isStreaming = true, streamingEndpoint = "runs"))
        assertFalse(ProfileShelfPolicy.canSwitch(isStreaming = true, streamingEndpoint = "completions"))
    }

    @Test
    fun serverDefaultUsesItsPresentationIconWithoutConflatingLiteralDefaultProfile() {
        val serverDefault = ProfileChoice(AgentDisplay.SERVER_DEFAULT_PROFILE_KEY, null)
        val literalDefault = ProfileChoice("default", Profile(name = "default", model = "root"))

        assertEquals(null, ProfileShelfPolicy.iconProfileName(serverDefault))
        assertEquals("default", ProfileShelfPolicy.iconProfileName(literalDefault))
    }
}

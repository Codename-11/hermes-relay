package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.ProfilePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileShelfPolicyTest {
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
    fun serverDefaultUsesResolvedAvatarWithoutConflatingLiteralDefaultProfile() {
        val resolved = Profile(name = "victor", model = "gpt-5.6-sol")
        val serverDefault = ProfileChoice(AgentDisplay.SERVER_DEFAULT_PROFILE_KEY, null)
        val literalDefault = ProfileChoice("default", Profile(name = "default", model = "root"))

        assertEquals(resolved, ProfileShelfPolicy.avatarProfile(serverDefault, resolved))
        assertEquals(literalDefault.profile, ProfileShelfPolicy.avatarProfile(literalDefault, resolved))
    }
}

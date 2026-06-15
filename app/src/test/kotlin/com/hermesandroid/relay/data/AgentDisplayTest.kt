package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentDisplayTest {

    private val defaultProfile = Profile(
        name = "default",
        model = "grok-default",
        description = "House Agent",
    )

    private val mizu = Profile(
        name = "mizu",
        model = "grok-mizu",
        description = "Mizu",
    )

    @Test
    fun effectiveProfile_prefersSelectedProfileOverDefaultProfile() {
        val effective = AgentDisplay.effectiveProfile(
            selectedProfile = mizu,
            profiles = listOf(defaultProfile, mizu),
        )

        assertEquals(mizu, effective)
    }

    @Test
    fun effectiveProfile_isNullWithoutAnExplicitPick() {
        // No fallback to the advertised "default" profile — its verbose SOUL
        // summary must not replace the personality-derived agent name.
        val effective = AgentDisplay.effectiveProfile(
            selectedProfile = null,
            profiles = listOf(mizu, defaultProfile),
        )

        assertEquals(null, effective)
    }

    @Test
    fun agentName_usesProfileNameNotVerboseDescription() {
        // The name slot shows the NAME, even when a (verbose) description exists.
        assertEquals(
            "Mizu",
            AgentDisplay.agentName(
                profile = mizu.copy(description = "Builds and maintains the codebase"),
                selectedPersonality = "friendly",
                defaultPersonality = "default-persona",
                connectionLabel = "Lab",
            ),
        )

        assertEquals(
            "Coder",
            AgentDisplay.agentName(
                profile = mizu.copy(name = "coder", description = ""),
                selectedPersonality = "friendly",
                defaultPersonality = "default-persona",
                connectionLabel = "Lab",
            ),
        )
    }

    @Test
    fun agentName_fallsBackToPersonalityConnectionThenHermes() {
        assertEquals(
            "Research",
            AgentDisplay.agentName(
                profile = null,
                selectedPersonality = "research",
                defaultPersonality = "default",
                connectionLabel = "Lab",
            ),
        )
        assertEquals(
            "Default persona",
            AgentDisplay.agentName(
                profile = null,
                selectedPersonality = "default",
                defaultPersonality = "default persona",
                connectionLabel = "Lab",
            ),
        )
        assertEquals(
            "Lab",
            AgentDisplay.agentName(
                profile = null,
                selectedPersonality = "default",
                defaultPersonality = "",
                connectionLabel = "Lab",
            ),
        )
        assertEquals(
            "Hermes",
            AgentDisplay.agentName(
                profile = null,
                selectedPersonality = "default",
                defaultPersonality = "",
                connectionLabel = "",
            ),
        )
    }

    @Test
    fun profileRequestName_normalizesDefaultAliasAndDropsBlankOrNull() {
        assertNull(AgentDisplay.profileRequestName(null))
        assertNull(AgentDisplay.profileRequestName("  "))
        assertNull(AgentDisplay.profileRequestName(" default "))
        assertEquals("mizu", AgentDisplay.profileRequestName("mizu"))
    }

    @Test
    fun normalizeSelection_collapsesSyntheticDefaultProfile() {
        assertNull(AgentDisplay.normalizeSelection(defaultProfile))
        assertEquals(mizu, AgentDisplay.normalizeSelection(mizu))
    }

    @Test
    fun profileContextKey_treatsDefaultAliasAsServerDefault() {
        assertEquals(
            "conn::__server_default__",
            AgentDisplay.profileContextKey("conn", "default"),
        )
        assertEquals("conn::mizu", AgentDisplay.profileContextKey("conn", "mizu"))
    }
}

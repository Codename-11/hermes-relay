package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisedSessionPolicyTest {
    @Test fun `session action summary derives none mixed and all`() {
        val none = SupervisedSessionActions()
        val mixed = none.copy(rename = true, delete = true)
        val all = none.withAll(true)

        assertTrue(none.noneEnabled)
        assertEquals(2, mixed.enabledCount)
        assertFalse(mixed.noneEnabled)
        assertFalse(mixed.allEnabled)
        assertTrue(all.allEnabled)
        assertEquals(SupervisedSessionActions.TOTAL, all.enabledCount)
    }

    @Test fun `supervised history and granular flag are both required`() {
        val base = SupervisedModePolicy(
            enabled = true,
            pinnedProfileName = "willow",
            capabilities = SupervisedCapabilities(
                conversationHistory = true,
                sessionActions = SupervisedSessionActions(rename = true),
            ),
        )

        assertTrue(base.allowsSessionAction(SupervisedSessionAction.Rename))
        assertFalse(base.allowsSessionAction(SupervisedSessionAction.Delete))
        assertFalse(
            base.copy(
                capabilities = base.capabilities.copy(conversationHistory = false),
            ).allowsSessionAction(SupervisedSessionAction.Rename),
        )
        assertTrue(SupervisedModePolicy().allowsSessionAction(SupervisedSessionAction.Delete))
    }
}

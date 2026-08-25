package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBindingControllerTest {
    private val controller = ConversationBindingController()

    @Test
    fun explicitSessionOwnsProfileAndRejectsLifecycleReconciliation() {
        val alpha = Profile("alpha", "model-a", "Alpha")
        assertTrue(
            controller.openExplicit(
                contextKey = "connection::alpha",
                profileName = alpha.name,
                sessionId = "alpha-session",
                displayProfile = alpha,
                lockedProfileToken = null,
            ),
        )

        assertFalse(
            controller.reconcileGlobal(
                contextKey = "connection::beta",
                profileName = "beta",
                sessionId = "beta-session",
            ),
        )
        assertEquals("alpha", controller.state.value.profileName)
        assertEquals("alpha-session", controller.state.value.sessionId)
    }

    @Test
    fun selectingAnotherOwnerAtomicallyReplacesTheBinding() {
        val alpha = Profile("alpha", "model-a", "Alpha")
        val beta = Profile("beta", "model-b", "Beta")
        controller.openExplicit("c::alpha", alpha.name, "a1", alpha, null)
        controller.openExplicit("c::beta", beta.name, "b1", beta, null)

        val state = controller.state.value
        assertEquals("c::beta", state.contextKey)
        assertEquals("beta", state.profileName)
        assertEquals("b1", state.sessionId)
        assertEquals(beta, state.displayProfile)
    }

    @Test
    fun siblingSessionKeepsTheCurrentOwner() {
        controller.openExplicit("c::alpha", "alpha", "a1", null, null)
        controller.switchSession("a2")

        assertEquals("alpha", controller.state.value.profileName)
        assertEquals("a2", controller.state.value.sessionId)
    }

    @Test
    fun profileLockRejectsOtherOwnersAndAllowsTheLockedOwner() {
        val locked = AgentDisplay.profileSessionKey("beta")
        assertFalse(controller.openExplicit("c::alpha", "alpha", "a1", null, locked))
        assertNull(controller.state.value.contextKey)
        assertTrue(controller.openExplicit("c::beta", "beta", "b1", null, locked))
    }

    @Test
    fun releasingExplicitOwnerAllowsGlobalReconciliation() {
        controller.openExplicit("c::alpha", "alpha", "a1", null, null)
        controller.releaseExplicitOwner()

        assertTrue(controller.reconcileGlobal("c::beta", "beta", "b1"))
        assertEquals(ConversationBindingOrigin.GlobalSelection, controller.state.value.origin)
        assertEquals("beta", controller.state.value.profileName)
    }

    @Test
    fun matchingPersistedSelectionConvergesExplicitBindingToGlobalState() {
        controller.openExplicit("c::alpha", "alpha", "a1", null, null)

        assertTrue(controller.reconcileGlobal("c::alpha", "alpha", "a1"))
        assertFalse(controller.state.value.hasExplicitOwner)
        assertEquals("alpha", controller.state.value.profileName)
        assertEquals("a1", controller.state.value.sessionId)
    }
}

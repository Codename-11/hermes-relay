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
        val lucy = Profile("lucy", "gpt-5.6-sol", "Lucy")
        assertTrue(
            controller.openExplicit(
                contextKey = "connection::lucy",
                profileName = lucy.name,
                sessionId = "lucy-session",
                displayProfile = lucy,
                lockedProfileToken = null,
            ),
        )

        assertFalse(
            controller.reconcileGlobal(
                contextKey = "connection::victor",
                profileName = "victor",
                sessionId = "victor-session",
            ),
        )
        assertEquals("lucy", controller.state.value.profileName)
        assertEquals("lucy-session", controller.state.value.sessionId)
    }

    @Test
    fun selectingAnotherOwnerAtomicallyReplacesTheBinding() {
        val lucy = Profile("lucy", "gpt-5.6-sol", "Lucy")
        val victor = Profile("victor", "gpt-5.6-sol", "Victor")
        controller.openExplicit("c::lucy", lucy.name, "l1", lucy, null)
        controller.openExplicit("c::victor", victor.name, "v1", victor, null)

        val state = controller.state.value
        assertEquals("c::victor", state.contextKey)
        assertEquals("victor", state.profileName)
        assertEquals("v1", state.sessionId)
        assertEquals(victor, state.displayProfile)
    }

    @Test
    fun siblingSessionKeepsTheCurrentOwner() {
        controller.openExplicit("c::lucy", "lucy", "l1", null, null)
        controller.switchSession("l2")

        assertEquals("lucy", controller.state.value.profileName)
        assertEquals("l2", controller.state.value.sessionId)
    }

    @Test
    fun profileLockRejectsOtherOwnersAndAllowsTheLockedOwner() {
        val locked = AgentDisplay.profileSessionKey("victor")
        assertFalse(controller.openExplicit("c::lucy", "lucy", "l1", null, locked))
        assertNull(controller.state.value.contextKey)
        assertTrue(controller.openExplicit("c::victor", "victor", "v1", null, locked))
    }

    @Test
    fun releasingExplicitOwnerAllowsGlobalReconciliation() {
        controller.openExplicit("c::lucy", "lucy", "l1", null, null)
        controller.releaseExplicitOwner()

        assertTrue(controller.reconcileGlobal("c::victor", "victor", "v1"))
        assertEquals(ConversationBindingOrigin.GlobalSelection, controller.state.value.origin)
        assertEquals("victor", controller.state.value.profileName)
    }

    @Test
    fun matchingPersistedSelectionConvergesExplicitBindingToGlobalState() {
        controller.openExplicit("c::lucy", "lucy", "l1", null, null)

        assertTrue(controller.reconcileGlobal("c::lucy", "lucy", "l1"))
        assertFalse(controller.state.value.hasExplicitOwner)
        assertEquals("lucy", controller.state.value.profileName)
        assertEquals("l1", controller.state.value.sessionId)
    }
}

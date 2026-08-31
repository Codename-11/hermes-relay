package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.SessionTransport
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
    fun newDraftKeepsExplicitAllProfilesOwnerAndRejectsStaleRestore() {
        val alpha = Profile("alpha", "model-a", "Alpha")
        controller.openExplicit("c::alpha", alpha.name, "a1", alpha, null)

        controller.startFreshDraft()

        assertEquals("c::alpha", controller.state.value.contextKey)
        assertEquals("alpha", controller.state.value.profileName)
        assertNull(controller.state.value.sessionId)
        assertEquals(alpha, controller.state.value.displayProfile)
        assertTrue(controller.state.value.hasExplicitOwner)
        assertFalse(controller.reconcileGlobal("c::alpha", "alpha", "a1"))
        assertNull(controller.state.value.sessionId)
    }

    @Test
    fun newDraftPromotesGlobalOwnerAndRejectsItsStoredSession() {
        controller.forceGlobal("c::alpha", "alpha", "a1")

        controller.startFreshDraft()

        assertTrue(controller.state.value.hasExplicitOwner)
        assertNull(controller.state.value.sessionId)
        assertFalse(controller.reconcileGlobal("c::alpha", "alpha", "a1"))
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

    @Test
    fun gatewayOwnerSurvivesSessionClearAndLifecycleReconciliation() {
        controller.forceGlobal(
            contextKey = "c::victor",
            profileName = "victor",
            sessionId = "20260831_120000_deadbeef",
            transport = SessionTransport.GATEWAY,
        )

        controller.startFreshDraft()
        controller.reconcileGlobal(
            contextKey = "c::victor",
            profileName = "victor",
            sessionId = null,
            transport = SessionTransport.GATEWAY,
        )

        assertEquals(SessionTransport.GATEWAY, controller.state.value.transport)
        assertNull(controller.state.value.sessionId)
    }

    @Test
    fun explicitApiSessionKeepsItsCompatibilityOwner() {
        controller.openExplicit(
            contextKey = "c::default",
            profileName = null,
            sessionId = "api_1788192000_deadbeef",
            displayProfile = null,
            lockedProfileToken = null,
        )

        assertEquals(SessionTransport.SSE, controller.state.value.transport)
        controller.switchSession(null)
        assertEquals(SessionTransport.SSE, controller.state.value.transport)
    }
}

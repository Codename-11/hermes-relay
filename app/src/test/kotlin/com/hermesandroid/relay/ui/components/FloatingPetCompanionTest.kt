package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingPetCompanionTest {
    @Test
    fun `keyboard and short screens use compact 40dp pet`() {
        assertTrue(shouldCompactFloatingPet(imeVisible = true, screenHeightDp = 844))
        assertTrue(shouldCompactFloatingPet(imeVisible = false, screenHeightDp = 640))
        assertFalse(shouldCompactFloatingPet(imeVisible = false, screenHeightDp = 844))
        assertEquals(40, floatingPetVisualSizeDp(compact = true))
        assertEquals(48, floatingPetVisualSizeDp(compact = false))
    }

    @Test
    fun `scrolling and reduced motion pause pet animation`() {
        assertTrue(
            shouldPauseFloatingPet(
                alreadyPaused = false,
                animationEnabled = true,
                isScrolling = true,
            ),
        )
        assertTrue(
            shouldPauseFloatingPet(
                alreadyPaused = false,
                animationEnabled = false,
                isScrolling = false,
            ),
        )
        assertTrue(
            shouldPauseFloatingPet(
                alreadyPaused = true,
                animationEnabled = true,
                isScrolling = false,
            ),
        )
        assertFalse(
            shouldPauseFloatingPet(
                alreadyPaused = false,
                animationEnabled = true,
                isScrolling = false,
            ),
        )
    }

    @Test
    fun `scrolling pet remains present but subdued`() {
        assertEquals(0.6f, floatingPetAlpha(isScrolling = true), 0f)
        assertEquals(1f, floatingPetAlpha(isScrolling = false), 0f)
    }

    @Test
    fun `roaming requires idle safe foreground motion`() {
        fun allowed(
            state: SphereState = SphereState.Idle,
            osAnimations: Boolean = true,
            touchExploration: Boolean = false,
            scrolling: Boolean = false,
        ) = shouldRoamFloatingPet(
            roamingEnabled = true,
            roamingAllowed = true,
            hasWalkRegion = true,
            state = state,
            animationEnabled = true,
            appForeground = true,
            osAnimations = osAnimations,
            touchExploration = touchExploration,
            paused = false,
            isScrolling = scrolling,
            dragging = false,
            menuExpanded = false,
        )

        assertTrue(allowed())
        assertFalse(allowed(state = SphereState.Thinking))
        assertFalse(allowed(osAnimations = false))
        assertFalse(allowed(touchExploration = true))
        assertFalse(allowed(scrolling = true))
    }

    @Test
    fun `profile identity is shown only for first assistant message in group`() {
        assertTrue(
            shouldShowMessageGroupAvatar(
                isUser = false,
                isSystem = false,
                isFirstInGroup = true,
                agentName = "Hermes",
            ),
        )
        assertFalse(
            shouldShowMessageGroupAvatar(
                isUser = false,
                isSystem = false,
                isFirstInGroup = false,
                agentName = "Hermes",
            ),
        )
        assertFalse(
            shouldShowMessageGroupAvatar(
                isUser = true,
                isSystem = false,
                isFirstInGroup = true,
                agentName = "You",
            ),
        )
        assertFalse(
            shouldShowMessageGroupAvatar(
                isUser = false,
                isSystem = true,
                isFirstInGroup = true,
                agentName = "System",
            ),
        )
    }
}

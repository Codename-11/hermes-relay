package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.ui.components.avatar.PetLocomotion
import com.hermesandroid.relay.ui.components.pet.PetLogicalEdge
import com.hermesandroid.relay.ui.components.pet.PetPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingPetCompanionTest {
    @Test
    fun `roaming starts immediately then uses the normal repeat delay`() {
        assertEquals(0L, floatingPetRoamDelayMs(hasMoved = false))
        assertEquals(4_800L, floatingPetRoamDelayMs(hasMoved = true))
    }

    @Test
    fun `temperament pacing controls repeat roam delay`() {
        assertEquals(
            12_000L,
            floatingPetRoamDelayMs(hasMoved = true, roamIntervalMs = 12_000L),
        )
        assertEquals(
            0L,
            floatingPetRoamDelayMs(hasMoved = false, roamIntervalMs = 12_000L),
        )
    }

    @Test
    fun `ambient roaming cycles through hop wave and rest`() {
        assertEquals(PetAmbientAction.Hop, petAmbientAction(0))
        assertEquals(PetAmbientAction.Wave, petAmbientAction(1))
        assertEquals(PetAmbientAction.Rest, petAmbientAction(2))
        assertEquals(PetAmbientAction.Hop, petAmbientAction(3))
    }

    @Test
    fun `visible bubble is attempted immediately then after bounded patrol cycles`() {
        assertTrue(shouldAttemptAmbientBubbleVisit(cyclesUntilVisit = 0, hasVisibleBubble = true))
        assertFalse(shouldAttemptAmbientBubbleVisit(cyclesUntilVisit = 1, hasVisibleBubble = true))
        assertFalse(shouldAttemptAmbientBubbleVisit(cyclesUntilVisit = 0, hasVisibleBubble = false))
    }

    @Test
    fun `temporary movement pauses do not dock the overlay`() {
        assertFalse(shouldDockFloatingPet(roamingEnabled = true, roamingAllowed = true))
        assertTrue(shouldDockFloatingPet(roamingEnabled = false, roamingAllowed = true))
        assertTrue(shouldDockFloatingPet(roamingEnabled = true, roamingAllowed = false))
    }

    @Test
    fun `behavior director applies user agent response roam idle priority`() {
        assertEquals(
            PetBehaviorPriority.UserInteraction,
            petBehaviorPriority(true, true, true, true),
        )
        assertEquals(
            PetBehaviorPriority.AgentActivity,
            petBehaviorPriority(false, true, true, true),
        )
        assertEquals(
            PetBehaviorPriority.ResponseVisit,
            petBehaviorPriority(false, false, true, true),
        )
        assertEquals(
            PetBehaviorPriority.Roam,
            petBehaviorPriority(false, false, false, true),
        )
        assertEquals(
            PetBehaviorPriority.Idle,
            petBehaviorPriority(false, false, false, false),
        )
    }

    @Test
    fun `vertical transfer distinguishes jump from fall`() {
        assertEquals(PetLocomotion.Jump, petVerticalLocomotion(fromY = 200f, toY = 100f))
        assertEquals(PetLocomotion.Fall, petVerticalLocomotion(fromY = 100f, toY = 200f))
        assertEquals(PetLocomotion.Jump, petVerticalLocomotion(fromY = 100f, toY = 100f))
    }

    @Test
    fun `user manipulation wins while agent activity suppresses ambient travel`() {
        assertEquals(
            PetLocomotion.Held,
            presentedPetLocomotion(true, false, SphereState.Streaming, PetLocomotion.WalkRight),
        )
        assertEquals(
            PetLocomotion.Fall,
            presentedPetLocomotion(false, true, SphereState.Streaming, PetLocomotion.WalkRight),
        )
        assertEquals(
            PetLocomotion.None,
            presentedPetLocomotion(false, false, SphereState.Streaming, PetLocomotion.WalkRight),
        )
        assertEquals(
            PetLocomotion.WalkRight,
            presentedPetLocomotion(false, false, SphereState.Idle, PetLocomotion.WalkRight),
        )
    }

    @Test
    fun `pending drop releases only after local snap and both prop acknowledgements`() {
        val oldPlacement = PetPlacement(PetLogicalEdge.Start, 0.2f)
        val droppedPlacement = PetPlacement(PetLogicalEdge.End, 0.7f)

        assertFalse(
            shouldReleasePendingPetDrop(
                expectedPlacement = droppedPlacement,
                positionSettled = false,
                animationFinished = false,
                roamingEnabled = false,
                observedPlacement = droppedPlacement,
            ),
        )
        assertFalse(
            shouldReleasePendingPetDrop(
                expectedPlacement = droppedPlacement,
                positionSettled = true,
                animationFinished = true,
                roamingEnabled = true,
                observedPlacement = droppedPlacement,
            ),
        )
        assertFalse(
            shouldReleasePendingPetDrop(
                expectedPlacement = droppedPlacement,
                positionSettled = true,
                animationFinished = true,
                roamingEnabled = false,
                observedPlacement = oldPlacement,
            ),
        )
        assertFalse(
            shouldReleasePendingPetDrop(
                expectedPlacement = droppedPlacement,
                positionSettled = true,
                animationFinished = false,
                roamingEnabled = false,
                observedPlacement = droppedPlacement,
            ),
        )
        assertTrue(
            shouldReleasePendingPetDrop(
                expectedPlacement = droppedPlacement,
                positionSettled = true,
                animationFinished = true,
                roamingEnabled = false,
                observedPlacement = droppedPlacement,
            ),
        )
    }

    @Test
    fun `walk timing lands on complete Petdex gait cycles`() {
        assertEquals(480, petWalkDurationMs(1f))
        assertEquals(960, petWalkDurationMs(44f))
        assertEquals(1_920, petWalkDurationMs(88f))
        assertEquals(480, petWalkDurationMs(Float.NaN))
    }

    @Test
    fun `airborne shadow shrinks and softens`() {
        assertEquals(1f, petShadowScale(0f), 0f)
        assertEquals(0.58f, petShadowScale(1f), 0.0001f)
        assertEquals(0.20f, petShadowAlpha(0f), 0.0001f)
        assertEquals(0.08f, petShadowAlpha(1f), 0.0001f)
    }

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
    fun `keyboard compacts pet and host pause signal dims it`() {
        assertTrue(shouldCompactFloatingPet(imeVisible = true, screenHeightDp = 844))
        assertEquals(40, floatingPetVisualSizeDp(compact = true))
        assertEquals(0.6f, floatingPetAlpha(isScrolling = true), 0f)
    }

    @Test
    fun `eligible roam gate reopens immediately when drag hold ends`() {
        fun canRoam(dragging: Boolean) = shouldRoamFloatingPet(
            roamingEnabled = true,
            roamingAllowed = true,
            hasWalkRegion = true,
            state = SphereState.Idle,
            animationEnabled = true,
            appForeground = true,
            osAnimations = true,
            touchExploration = false,
            paused = false,
            isScrolling = false,
            dragging = dragging,
            menuExpanded = false,
        )

        assertFalse(canRoam(dragging = true))
        assertTrue(canRoam(dragging = false))
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

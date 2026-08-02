package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.PetTemperament
import com.hermesandroid.relay.ui.components.avatar.PetLocomotion
import com.hermesandroid.relay.ui.components.pet.PetLogicalEdge
import com.hermesandroid.relay.ui.components.pet.PetFootprint
import com.hermesandroid.relay.ui.components.pet.PetObstacle
import com.hermesandroid.relay.ui.components.pet.PetPlacement
import com.hermesandroid.relay.ui.components.pet.PetPoint
import com.hermesandroid.relay.ui.components.pet.PetRailExplorationMode
import com.hermesandroid.relay.ui.components.pet.PetRoamingRail
import com.hermesandroid.relay.ui.components.pet.PetSafeBounds
import com.hermesandroid.relay.ui.components.pet.PetSettledChatHabitat
import com.hermesandroid.relay.ui.components.pet.PetSettledChatMode
import com.hermesandroid.relay.ui.components.pet.findOverlayRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingPetCompanionTest {
    @Test
    fun `terrain lookahead plans multiple levels before movement starts`() {
        val composer = PetRoamingRail(
            key = "composer:0",
            perchKey = "composer",
            bounds = PetSafeBounds(20f, 700f, 180f, 700f),
        )
        val recent = PetRoamingRail(
            key = "message:recent",
            perchKey = "message-recent",
            bounds = PetSafeBounds(20f, 500f, 180f, 500f),
        )
        val older = PetRoamingRail(
            key = "message:older",
            perchKey = "message-older",
            bounds = PetSafeBounds(20f, 300f, 180f, 300f),
        )

        val lookahead = requireNotNull(
            planPetTerrainLookahead(
                current = PetPoint(100f, 700f),
                activeRailKey = composer.key,
                rails = listOf(composer, recent, older),
                bounds = PetSafeBounds(0f, 0f, 400f, 800f),
                uiObstacles = emptyList(),
                footprint = PetFootprint(width = 20f, height = 20f),
                maximumStepLength = 240f,
                maxExtraStops = 3,
                mode = PetRailExplorationMode.Ascending,
            ),
        )

        assertEquals(
            listOf("composer", "message-recent", "message-older"),
            lookahead.orderedRails.map { it.perchKey },
        )
        assertEquals(2, lookahead.continuation.size)
    }

    @Test
    fun `terrain lookahead waits for a supported waypoint to replan`() {
        val rail = PetRoamingRail(
            key = "composer:0",
            perchKey = "composer",
            bounds = PetSafeBounds(20f, 700f, 180f, 700f),
        )

        assertEquals(
            null,
            planPetTerrainLookahead(
                current = PetPoint(100f, 620f),
                activeRailKey = rail.key,
                rails = listOf(rail),
                bounds = PetSafeBounds(0f, 0f, 400f, 800f),
                uiObstacles = emptyList(),
                footprint = PetFootprint(width = 20f, height = 20f),
                maximumStepLength = 240f,
                maxExtraStops = 3,
                mode = PetRailExplorationMode.Ascending,
            ),
        )
    }

    @Test
    fun `temperament bounds extra response bubble exploration`() {
        assertEquals(1, petBubbleExplorationStops(PetTemperament.Calm))
        assertEquals(2, petBubbleExplorationStops(PetTemperament.Balanced))
        assertEquals(3, petBubbleExplorationStops(PetTemperament.Playful))
    }

    @Test
    fun `terrain diagnostics name the authoritative movement gate`() {
        val base = PetTerrainGateArgs()
        assertEquals("roaming", base.label())
        assertEquals("no measured rails", base.copy(hasRails = false).label())
        assertEquals("scrolling", base.copy(surfaceScrolling = true).label())
        assertEquals(
            "response visit",
            base.copy(responseVisitPending = true, canPatrol = false).label(),
        )
        assertEquals("roaming off", base.copy(roamingEnabled = false).label())
    }

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
    fun `only wide side pockets and bubble tops permit settled pacing`() {
        assertTrue(shouldPaceSettledHabitat(com.hermesandroid.relay.ui.components.pet.PetSettledChatMode.SidePocketPace))
        assertTrue(shouldPaceSettledHabitat(com.hermesandroid.relay.ui.components.pet.PetSettledChatMode.BubbleTop))
        assertFalse(shouldPaceSettledHabitat(com.hermesandroid.relay.ui.components.pet.PetSettledChatMode.SidePocketIdle))
        assertFalse(shouldPaceSettledHabitat(com.hermesandroid.relay.ui.components.pet.PetSettledChatMode.ComposerCorner))
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
    fun `pending drop releases after animation and placement acknowledgement while roaming`() {
        val oldPlacement = PetPlacement(PetLogicalEdge.Start, 0.2f)
        val droppedPlacement = PetPlacement(PetLogicalEdge.End, 0.7f)

        assertFalse(
            shouldReleasePendingPetDrop(
                expectedPlacement = droppedPlacement,
                positionSettled = false,
                animationFinished = false,
                observedPlacement = droppedPlacement,
            ),
        )
        assertFalse(
            shouldReleasePendingPetDrop(
                expectedPlacement = null,
                positionSettled = true,
                animationFinished = true,
                observedPlacement = droppedPlacement,
            ),
        )
        assertFalse(
            shouldReleasePendingPetDrop(
                expectedPlacement = droppedPlacement,
                positionSettled = true,
                animationFinished = true,
                observedPlacement = oldPlacement,
            ),
        )
        assertFalse(
            shouldReleasePendingPetDrop(
                expectedPlacement = droppedPlacement,
                positionSettled = true,
                animationFinished = false,
                observedPlacement = droppedPlacement,
            ),
        )
        assertTrue(
            shouldReleasePendingPetDrop(
                expectedPlacement = droppedPlacement,
                positionSettled = true,
                animationFinished = true,
                observedPlacement = droppedPlacement,
            ),
        )

        assertFalse(
            shouldAnimatePendingPetDrop(
                expectedPlacement = droppedPlacement,
                positionSettled = true,
                animationFinished = false,
                observedPlacement = oldPlacement,
            ),
        )
        assertTrue(
            shouldAnimatePendingPetDrop(
                expectedPlacement = droppedPlacement,
                positionSettled = true,
                animationFinished = false,
                observedPlacement = droppedPlacement,
            ),
        )
    }

    @Test
    fun `scroll support follows the same measured rail`() {
        val upper = PetRoamingRail("upper:0", "upper", PetSafeBounds(20f, 100f, 180f, 100f))
        val lower = PetRoamingRail("lower:0", "lower", PetSafeBounds(20f, 260f, 180f, 260f))

        assertEquals(upper, petRailSupportingPoint(listOf(upper, lower), PetPoint(80f, 101f)))
        assertEquals(null, petRailSupportingPoint(listOf(upper, lower), PetPoint(80f, 140f)))
    }

    @Test
    fun `lost scroll support falls to the nearest lower rail before jumping upward`() {
        val upper = PetRoamingRail("upper:0", "upper", PetSafeBounds(20f, 80f, 180f, 80f))
        val nearBelow = PetRoamingRail("near:0", "near", PetSafeBounds(20f, 230f, 180f, 230f))
        val farBelow = PetRoamingRail("far:0", "far", PetSafeBounds(20f, 360f, 180f, 360f))
        val point = PetPoint(80f, 180f)

        assertEquals(
            nearBelow,
            choosePetScrollLandingRail(listOf(upper, farBelow, nearBelow), point),
        )
        assertEquals(upper, choosePetScrollLandingRail(listOf(upper), point))
    }

    @Test
    fun `scroll tracks settled chat habitat but never settles on incidental message rail`() {
        val composer = PetRoamingRail(
            "composer:0",
            CHAT_PET_WALK_REGION,
            PetSafeBounds(20f, 700f, 380f, 700f),
        )
        val message = PetRoamingRail(
            "message:0",
            "${CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX}reply-1",
            PetSafeBounds(70f, 300f, 300f, 300f),
        )
        val settled = PetSettledChatHabitat(
            mode = PetSettledChatMode.SidePocketPace,
            rail = PetRoamingRail(
                "chat-settled:reply-1:side",
                "chat-settled:reply-1",
                PetSafeBounds(330f, 340f, 380f, 340f),
            ),
        )

        val tracking = petScrollTrackingRails(listOf(composer, message), settled)
        val landing = petScrollLandingRails(listOf(composer, message), settled)

        assertEquals(settled.rail, petRailSupportingPoint(tracking, PetPoint(350f, 340f)))
        assertTrue(settled.rail in landing)
        assertTrue(composer in landing)
        assertFalse(message in landing)
    }

    @Test
    fun `scroll reacquires moved settled rail by stable key`() {
        val before = PetRoamingRail(
            "chat-settled:reply-1:side",
            "chat-settled:reply-1",
            PetSafeBounds(330f, 340f, 380f, 340f),
        )
        val after = before.copy(bounds = PetSafeBounds(330f, 220f, 380f, 220f))
        val habitat = PetSettledChatHabitat(PetSettledChatMode.SidePocketPace, after)

        assertEquals(
            after,
            petScrollTrackingRails(emptyList(), habitat).firstOrNull { it.key == before.key },
        )
    }

    @Test
    fun `pet overlapping a message bubble requires an animated escape`() {
        val bounds = PetSafeBounds(30f, 30f, 600f, 1_260f)
        val bubble = PetObstacle(80f, 975f, 450f, 1_065f)
        val footprint = PetFootprint(width = 60f, height = 60f, clearance = 4f)
        val blockedPoint = PetPoint(370f, 1_015f)

        assertTrue(
            petPositionNeedsEscape(
                point = blockedPoint,
                bounds = bounds,
                uiObstacles = listOf(bubble),
                footprint = footprint,
            ),
        )
        assertFalse(
            petPositionNeedsEscape(
                point = PetPoint(520f, 1_015f),
                bounds = bounds,
                uiObstacles = listOf(bubble),
                footprint = footprint,
            ),
        )
        val recoveryRoute = requireNotNull(
            findOverlayRoute(
                start = blockedPoint,
                requestedDestination = PetPoint(520f, 1_015f),
                bounds = bounds,
                uiObstacles = listOf(bubble),
                footprint = footprint,
            ),
        )
        assertTrue(recoveryRoute.start.distanceSquaredTo(blockedPoint) > 1f)
        assertFalse(
            petPositionNeedsEscape(
                point = recoveryRoute.start,
                bounds = bounds,
                uiObstacles = listOf(bubble),
                footprint = footprint,
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
    fun `airborne timing grows with distance and stays bounded`() {
        assertEquals(340, petAirborneDurationMs(24f))
        assertTrue(petAirborneDurationMs(160f) > petAirborneDurationMs(60f))
        assertEquals(1_400, petAirborneDurationMs(10_000f))
        assertEquals(340, petAirborneDurationMs(Float.NaN))
    }

    @Test
    fun `airborne shadow shrinks and softens`() {
        assertEquals(1f, petShadowScale(0f), 0f)
        assertEquals(0.58f, petShadowScale(1f), 0.0001f)
        assertEquals(0.20f, petShadowAlpha(0f), 0.0001f)
        assertEquals(0.08f, petShadowAlpha(1f), 0.0001f)
    }

    @Test
    fun `keyboard and short screens use compact 50dp pet`() {
        assertTrue(shouldCompactFloatingPet(imeVisible = true, screenHeightDp = 844))
        assertTrue(shouldCompactFloatingPet(imeVisible = false, screenHeightDp = 640))
        assertFalse(shouldCompactFloatingPet(imeVisible = false, screenHeightDp = 844))
        assertEquals(50, floatingPetVisualSizeDp(compact = true))
        assertEquals(60, floatingPetVisualSizeDp(compact = false))
    }

    @Test
    fun `pet scale updates art hit target and collision footprint together`() {
        assertEquals(FloatingPetDimensions(60f, 70f), floatingPetDimensions(false, 1f))
        assertEquals(FloatingPetDimensions(36f, 48f), floatingPetDimensions(false, 0.6f))
        assertEquals(FloatingPetDimensions(72f, 84f), floatingPetDimensions(false, 1.2f))
        val compactMinimum = floatingPetDimensions(true, 0.6f)
        assertEquals(30f, compactMinimum.visualSizeDp, 0.001f)
        assertEquals(48f, compactMinimum.targetSizeDp, 0.001f)
        val compactMaximum = floatingPetDimensions(true, 1.2f)
        assertEquals(60f, compactMaximum.visualSizeDp, 0.001f)
        assertEquals(72f, compactMaximum.targetSizeDp, 0.001f)
        assertEquals(FloatingPetDimensions(60f, 70f), floatingPetDimensions(false, Float.NaN))
    }

    @Test
    fun `reduced motion pauses pet animation`() {
        assertTrue(
            shouldPauseFloatingPet(
                alreadyPaused = false,
                animationEnabled = false,
            ),
        )
        assertTrue(
            shouldPauseFloatingPet(
                alreadyPaused = true,
                animationEnabled = true,
            ),
        )
        assertFalse(
            shouldPauseFloatingPet(
                alreadyPaused = false,
                animationEnabled = true,
            ),
        )
    }

    @Test
    fun `keyboard compacts pet without changing its playback state`() {
        assertTrue(shouldCompactFloatingPet(imeVisible = true, screenHeightDp = 844))
        assertEquals(50, floatingPetVisualSizeDp(compact = true))
        assertFalse(
            shouldPauseFloatingPet(
                alreadyPaused = false,
                animationEnabled = true,
            ),
        )
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

private data class PetTerrainGateArgs(
    val roamingEnabled: Boolean = true,
    val roamingAllowed: Boolean = true,
    val hasRails: Boolean = true,
    val surfaceScrolling: Boolean = false,
    val responseVisitPending: Boolean = false,
    val canPatrol: Boolean = true,
) {
    fun label(): String = petTerrainGateLabel(
        roamingEnabled = roamingEnabled,
        roamingAllowed = roamingAllowed,
        hasRails = hasRails,
        surfaceScrolling = surfaceScrolling,
        dragging = false,
        dropping = false,
        menuExpanded = false,
        animationEnabled = true,
        appForeground = true,
        osAnimations = true,
        touchExploration = false,
        paused = false,
        agentState = SphereState.Idle,
        responseVisitPending = responseVisitPending,
        canPatrol = canPatrol,
    )
}

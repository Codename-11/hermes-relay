package com.hermesandroid.relay.ui.components.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetRoamingGeometryTest {
    private val bounds = PetSafeBounds(left = 10f, top = 20f, right = 110f, bottom = 220f)

    @Test
    fun `placement clamps fraction and resolves logical edges in LTR and RTL`() {
        assertEquals(
            PetPoint(10f, 220f),
            PetPlacement(PetLogicalEdge.Start, 2f).resolve(bounds, PetLayoutDirection.Ltr),
        )
        assertEquals(
            PetPoint(110f, 20f),
            PetPlacement(PetLogicalEdge.Start, Float.NaN).resolve(bounds, PetLayoutDirection.Rtl),
        )
        assertEquals(
            PetPoint(10f, 120f),
            PetPlacement(PetLogicalEdge.End, 0.5f).resolve(bounds, PetLayoutDirection.Rtl),
        )
    }

    @Test
    fun `edge snap round trips normalized placement across layout direction`() {
        val ltr = bounds.snapToEdge(PetPoint(103f, 170f), PetLayoutDirection.Ltr)
        assertEquals(PetPlacement(PetLogicalEdge.End, 0.75f), ltr)
        assertEquals(PetPoint(110f, 170f), ltr.resolve(bounds, PetLayoutDirection.Ltr))

        val rtl = bounds.snapToEdge(PetPoint(12f, 70f), PetLayoutDirection.Rtl)
        assertEquals(PetPlacement(PetLogicalEdge.End, 0.25f), rtl)
        assertEquals(PetPoint(10f, 70f), rtl.resolve(bounds, PetLayoutDirection.Rtl))
    }

    @Test
    fun `clamp and projection keep the point in bounds and outside obstacles`() {
        assertEquals(PetPoint(10f, 220f), bounds.clamp(PetPoint(-50f, 900f)))

        val obstacle = PetObstacle(left = 40f, top = 70f, right = 80f, bottom = 150f)
        val projected = projectIntoSafeBounds(PetPoint(60f, 100f), bounds, listOf(obstacle))
        requireNotNull(projected)
        assertTrue(bounds.contains(projected))
        assertFalse(obstacle.contains(projected))
        assertTrue(projected.x < obstacle.left || projected.x > obstacle.right)
    }

    @Test
    fun `projection handles overlapping obstacles and reports fully blocked bounds`() {
        val overlapping = listOf(
            PetObstacle(20f, 40f, 70f, 180f),
            PetObstacle(60f, 60f, 100f, 200f),
        )
        val projected = projectIntoSafeBounds(PetPoint(65f, 100f), bounds, overlapping)
        requireNotNull(projected)
        assertTrue(overlapping.none { it.contains(projected) })

        assertNull(
            projectIntoSafeBounds(
                PetPoint(50f, 50f),
                PetSafeBounds(0f, 0f, 100f, 100f),
                listOf(PetObstacle(0f, 0f, 100f, 100f)),
            ),
        )
    }

    @Test
    fun `obstacles expand intersect and block crossing segments`() {
        val obstacle = PetObstacle(20f, 30f, 40f, 50f)
        assertEquals(PetObstacle(15f, 20f, 45f, 60f), obstacle.expanded(5f, 10f))
        assertTrue(obstacle.intersects(PetObstacle(40f, 45f, 60f, 70f)))
        assertFalse(obstacle.intersects(PetObstacle(41f, 51f, 60f, 70f)))
        assertTrue(obstacle.intersectsSegment(PetPoint(0f, 40f), PetPoint(100f, 40f)))
        assertTrue(obstacle.intersectsSegment(PetPoint(30f, 0f), PetPoint(30f, 100f)))
        assertFalse(obstacle.intersectsSegment(PetPoint(0f, 0f), PetPoint(10f, 10f)))
    }

    @Test
    fun `interactive card body remains blocked without removing its top edge rail`() {
        val cardBounds = PetObstacle(left = 20f, top = 100f, right = 180f, bottom = 220f)
        val segments = petPerchSegments(
            perch = PetMeasuredPerch("settings-card", cardBounds),
            obstacles = listOf(PetMeasuredObstacle("settings-controls", cardBounds)),
            footprint = PetFootprint(width = 40f, height = 40f),
            outer = PetSafeBounds(left = 0f, top = 0f, right = 200f, bottom = 300f),
            minimumWidth = 20f,
            verticalClearance = 6f,
        )

        assertEquals(listOf(PetSafeBounds(40f, 74f, 160f, 74f)), segments)
    }

    @Test
    fun `deterministic waypoint ignores blocked and unreachable candidates`() {
        val safe = PetSafeBounds(0f, 0f, 100f, 100f)
        val current = PetPoint(0f, 20f)
        val barrier = PetObstacle(40f, 0f, 60f, 100f)
        val candidates = listOf(
            PetPoint(100f, 20f), // unreachable across barrier
            PetPoint(0f, 80f),
            PetPoint(0f, 60f),
            PetPoint(40f, 50f), // blocked
            PetPoint(200f, 20f), // outside bounds
        )

        val first = chooseDeterministicWaypoint(current, candidates, safe, listOf(barrier), seed = 42L)
        val reordered = chooseDeterministicWaypoint(
            current,
            candidates.reversed(),
            safe,
            listOf(barrier),
            seed = 42L,
        )
        assertEquals(first, reordered)
        assertTrue(first == PetPoint(0f, 60f) || first == PetPoint(0f, 80f))
        assertFalse(pathIntersectsObstacle(current, requireNotNull(first), listOf(barrier)))
    }

    @Test
    fun `waypoint selection returns null without a distinct clear destination`() {
        assertNull(
            chooseDeterministicWaypoint(
                current = PetPoint(0f, 0f),
                candidates = listOf(PetPoint(0f, 0f), PetPoint(200f, 200f)),
                bounds = PetSafeBounds(0f, 0f, 100f, 100f),
                obstacles = emptyList(),
                seed = 1L,
            ),
        )
    }

    @Test
    fun `overlay safe bounds account for logical insets and pet footprint without layout reservation`() {
        val footprint = PetFootprint(width = 40f, height = 60f, clearance = 5f)
        assertEquals(
            PetSafeBounds(left = 35f, top = 50f, right = 165f, bottom = 155f),
            overlaySafeBounds(
                viewportWidth = 200f,
                viewportHeight = 220f,
                insets = PetInsets(start = 10f, top = 15f, end = 10f, bottom = 30f),
                footprint = footprint,
                layoutDirection = PetLayoutDirection.Ltr,
            ),
        )
        assertNull(
            overlaySafeBounds(
                viewportWidth = 40f,
                viewportHeight = 40f,
                insets = PetInsets(),
                footprint = PetFootprint(width = 60f, height = 60f),
                layoutDirection = PetLayoutDirection.Ltr,
            ),
        )
    }

    @Test
    fun `registered UI bounds expand by pet hit target and clearance`() {
        assertEquals(
            listOf(PetObstacle(left = 65f, top = 65f, right = 135f, bottom = 135f)),
            expandObstaclesForPet(
                obstacles = listOf(PetObstacle(90f, 90f, 110f, 110f)),
                footprint = PetFootprint(width = 40f, height = 40f, clearance = 5f),
            ),
        )
    }

    @Test
    fun `perch obstacles trim only the occupied rail segment`() {
        val outer = PetSafeBounds(0f, 0f, 300f, 300f)
        val footprint = PetFootprint(width = 40f, height = 40f)
        val perch = PetMeasuredPerch(
            key = "composer",
            bounds = PetObstacle(0f, 200f, 300f, 260f),
        )
        val obstacle = PetMeasuredObstacle(
            key = "jump-latest",
            bounds = PetObstacle(230f, 140f, 280f, 195f),
        )

        assertEquals(
            1,
            petPerchSegments(perch, listOf(obstacle), footprint, outer).size,
        )
        val segment = petPerchSegments(perch, listOf(obstacle), footprint, outer).single()
        assertEquals(20f, segment.left, 0f)
        assertTrue(segment.right < 210f)
        assertEquals(180f, segment.top, 0f)
    }

    @Test
    fun `perch keeps the full pet footprint above element content`() {
        val footprint = PetFootprint(width = 40f, height = 40f)
        val surfaceTop = 200f
        val rail = requireNotNull(
            petPerchRail(
                perch = PetMeasuredPerch(
                    key = "message",
                    bounds = PetObstacle(20f, surfaceTop, 280f, 260f),
                ),
                footprint = footprint,
                outer = PetSafeBounds(0f, 0f, 300f, 300f),
            ),
        )

        assertEquals(surfaceTop, rail.top + footprint.verticalRadius, 0f)
    }

    @Test
    fun `offscreen perches do not create detached rails at viewport edges`() {
        val footprint = PetFootprint(width = 40f, height = 40f)
        val outer = PetSafeBounds(20f, 20f, 280f, 280f)
        val offscreen = PetMeasuredPerch(
            key = "offscreen-message",
            bounds = PetObstacle(40f, 12f, 240f, 72f),
        )

        assertNull(petPerchRail(offscreen, footprint, outer))
        assertNull(
            petPerchTouchdownRail(
                perch = offscreen,
                footprint = footprint,
                outer = outer,
                minimumSurfaceWidth = 20f,
            ),
        )
        assertNull(
            petPerchEdgeRail(
                perch = offscreen,
                footprint = footprint,
                outer = outer,
                useLeftEdge = true,
            ),
        )
        assertNull(
            petPerchRail(
                perch = PetMeasuredPerch(
                    key = "offscreen-left",
                    bounds = PetObstacle(-180f, 120f, -40f, 180f),
                ),
                footprint = footprint,
                outer = outer,
            ),
        )
        assertNull(
            petPerchRail(
                perch = PetMeasuredPerch(
                    key = "offscreen-right",
                    bounds = PetObstacle(340f, 120f, 480f, 180f),
                ),
                footprint = footprint,
                outer = outer,
            ),
        )
    }

    @Test
    fun `message landing uses one outer edge with visual clearance`() {
        val footprint = PetFootprint(width = 40f, height = 40f)
        val landing = requireNotNull(
            petPerchEdgeRail(
                perch = PetMeasuredPerch(
                    key = "message",
                    bounds = PetObstacle(40f, 200f, 220f, 260f),
                ),
                footprint = footprint,
                outer = PetSafeBounds(20f, 20f, 280f, 280f),
                useLeftEdge = false,
                verticalClearance = 6f,
            ),
        )

        assertEquals(246f, landing.left, 0f)
        assertEquals(landing.left, landing.right, 0f)
        assertEquals(174f, landing.top, 0f)
        assertEquals(landing.top, landing.bottom, 0f)
    }

    @Test
    fun `message landing is omitted when neither screen gutter fits`() {
        assertNull(
            petPerchEdgeRail(
                perch = PetMeasuredPerch(
                    key = "wide-message",
                    bounds = PetObstacle(20f, 200f, 280f, 260f),
                ),
                footprint = PetFootprint(width = 40f, height = 40f),
                outer = PetSafeBounds(20f, 20f, 280f, 280f),
                useLeftEdge = false,
                verticalClearance = 6f,
            ),
        )
    }

    @Test
    fun `bubble excursion enters through gutter and traverses raised top rail`() {
        val bubble = PetMeasuredPerch(
            key = "chat-message-perch:assistant-1",
            bounds = PetObstacle(40f, 120f, 200f, 190f),
        )
        val composer = PetRoamingRail(
            key = "chat-composer-perch:0",
            perchKey = "chat-composer-perch",
            bounds = PetSafeBounds(20f, 250f, 280f, 250f),
        )
        val footprint = PetFootprint(width = 40f, height = 40f)

        val plan = requireNotNull(
            planPetBubbleExcursion(
                bubble = bubble,
                composerRail = composer,
                footprint = footprint,
                outer = PetSafeBounds(20f, 20f, 280f, 280f),
                uiObstacles = emptyList(),
                useLeftGutter = false,
                verticalClearance = 6f,
                minimumWalkWidth = 40f,
            ),
        )

        assertEquals(PetPoint(226f, 250f), plan.composerApproach)
        assertEquals(PetPoint(226f, 94f), plan.gutter)
        assertEquals(PetPoint(180f, 94f), plan.entry)
        assertEquals(PetPoint(60f, 94f), plan.opposite)
        assertEquals(PetBubbleEntryMode.ClearGutter, plan.entryMode)
        assertEquals(bubble.bounds.right, plan.entry.x + footprint.horizontalRadius, 0f)
        assertTrue(plan.gutter.x > bubble.bounds.right + footprint.horizontalRadius)
        assertTrue(plan.entry.y + footprint.height / 2f < bubble.bounds.top)
        assertEquals(
            listOf(plan.composerApproach, plan.gutter, plan.entry),
            petBubbleEntryRoute(plan).points,
        )
        assertEquals(
            petBubbleEntryRoute(plan).points.asReversed(),
            petBubbleExitRoute(plan).points,
        )
    }

    @Test
    fun `narrow perch becomes a centered touchdown but not a walking rail`() {
        val footprint = PetFootprint(width = 80f, height = 80f)
        val outer = PetSafeBounds(40f, 40f, 360f, 760f)
        val narrow = PetMeasuredPerch(
            key = "chat-message-perch:user:step:narrow",
            bounds = PetObstacle(250f, 400f, 310f, 500f),
        )

        assertNull(petPerchRail(narrow, footprint, outer, verticalClearance = 6f))
        assertEquals(
            PetSafeBounds(280f, 354f, 280f, 354f),
            petPerchTouchdownRail(
                perch = narrow,
                footprint = footprint,
                outer = outer,
                minimumSurfaceWidth = 28f,
                verticalClearance = 6f,
            ),
        )
        assertNull(
            petPerchTouchdownRail(
                perch = narrow,
                footprint = footprint,
                outer = outer,
                minimumSurfaceWidth = 61f,
                verticalClearance = 6f,
            ),
        )
    }

    @Test
    fun `tall bubble derives deterministic preferred edge footholds within the step cap`() {
        val bubble = PetMeasuredPerch(
            key = "assistant-tall",
            bounds = PetObstacle(100f, 100f, 300f, 700f),
        )
        val outer = PetSafeBounds(20f, 20f, 380f, 780f)
        val visualFootprint = PetFootprint(width = 40f, height = 40f)

        val plan = requireNotNull(
            planPetBubbleEdgeFootholds(
                bubble = bubble,
                bounds = outer,
                uiObstacles = emptyList(),
                traversalFootprint = visualFootprint,
                maximumStepLength = 210f,
                preferredSide = PetBubbleEdgeSide.Left,
            ),
        )

        assertEquals(PetBubbleEdgeSide.Left, plan.side)
        assertEquals(5, plan.footholds.size)
        assertEquals(720f, plan.footholds.first().y, 0f)
        assertEquals(80f, plan.footholds.last().y, 0f)
        assertTrue(plan.footholds.all { it.x < 80f })
        assertTrue(plan.legs.all { it.length <= 210f })
        assertEquals(
            plan,
            planPetBubbleEdgeFootholds(
                bubble = bubble,
                bounds = outer,
                uiObstacles = emptyList(),
                traversalFootprint = visualFootprint,
                maximumStepLength = 210f,
                preferredSide = PetBubbleEdgeSide.Left,
            ),
        )
    }

    @Test
    fun `bubble edge footholds fall back by side and reject fully blocked ladders`() {
        val bubble = PetMeasuredPerch(
            key = "assistant-tall",
            bounds = PetObstacle(100f, 100f, 300f, 700f),
        )
        val outer = PetSafeBounds(20f, 20f, 380f, 780f)
        val visualFootprint = PetFootprint(width = 40f, height = 40f)
        val leftBlocker = PetObstacle(45f, 260f, 85f, 540f)
        val rightBlocker = PetObstacle(315f, 260f, 355f, 540f)

        val fallback = requireNotNull(
            planPetBubbleEdgeFootholds(
                bubble = bubble,
                bounds = outer,
                uiObstacles = listOf(leftBlocker),
                traversalFootprint = visualFootprint,
                maximumStepLength = 210f,
                preferredSide = PetBubbleEdgeSide.Left,
            ),
        )
        assertEquals(PetBubbleEdgeSide.Right, fallback.side)
        assertTrue(fallback.footholds.all { it.x > 320f })

        assertNull(
            planPetBubbleEdgeFootholds(
                bubble = bubble,
                bounds = outer,
                uiObstacles = listOf(leftBlocker, rightBlocker),
                traversalFootprint = visualFootprint,
                maximumStepLength = 210f,
                preferredSide = PetBubbleEdgeSide.Left,
            ),
        )
    }

    @Test
    fun `bubble edge routing uses caller supplied visual footprint instead of touch target`() {
        val bubble = PetMeasuredPerch(
            key = "assistant-nearly-full-width",
            bounds = PetObstacle(70f, 100f, 330f, 700f),
        )
        val outer = PetSafeBounds(20f, 20f, 380f, 780f)

        assertTrue(
            planPetBubbleEdgeFootholds(
                bubble = bubble,
                bounds = outer,
                uiObstacles = emptyList(),
                traversalFootprint = PetFootprint(width = 40f, height = 40f),
                maximumStepLength = 210f,
                preferredSide = PetBubbleEdgeSide.Left,
            ) != null,
        )
        assertNull(
            planPetBubbleEdgeFootholds(
                bubble = bubble,
                bounds = outer,
                uiObstacles = emptyList(),
                traversalFootprint = PetFootprint(width = 100f, height = 100f),
                maximumStepLength = 210f,
                preferredSide = PetBubbleEdgeSide.Left,
            ),
        )
    }

    @Test
    fun `viewport filling bubble uses a traversal-only inset edge lane`() {
        val bubble = PetMeasuredPerch(
            key = "assistant-full-width",
            bounds = PetObstacle(20f, 100f, 380f, 700f),
        )
        val outer = PetSafeBounds(55f, 20f, 345f, 780f)
        val visualFootprint = PetFootprint(width = 60f, height = 60f, clearance = 6f)
        val contentObstacle = petTopSupportedObstacle(
            petBubbleEdgeTraversalObstacle(
                perch = bubble,
                traversalFootprint = visualFootprint,
                useLeftEdge = true,
            ),
        )

        val plan = requireNotNull(
            planPetBubbleEdgeFootholds(
                bubble = bubble,
                bounds = outer,
                uiObstacles = listOf(contentObstacle),
                traversalFootprint = visualFootprint,
                maximumStepLength = 210f,
                preferredSide = PetBubbleEdgeSide.Left,
                allowInsetEdge = true,
            ),
        )

        assertEquals(PetBubbleEdgeSide.Left, plan.side)
        assertTrue(plan.overlapsBubbleEdge)
        assertTrue(plan.footholds.all { point -> point.x in outer.left..outer.right })
        assertTrue(plan.legs.all { it.length <= 210f })
        val expandedContent = contentObstacle.expanded(
            visualFootprint.horizontalRadius,
            visualFootprint.verticalRadius,
        )
        assertTrue(plan.footholds.all { !expandedContent.contains(it) })
    }

    @Test
    fun `message content obstacle can expose fallback lanes on both edges`() {
        val bubble = PetMeasuredPerch(
            key = "wide",
            bounds = PetObstacle(100f, 200f, 500f, 700f),
        )
        val footprint = PetFootprint(width = 60f, height = 60f, clearance = 6f)

        val obstacle = petBubbleEdgeTraversalObstacle(
            perch = bubble,
            traversalFootprint = footprint,
            useLeftEdge = true,
            openBothEdges = true,
        )

        assertEquals(172f, obstacle.left, 0f)
        assertEquals(428f, obstacle.right, 0f)
        val expanded = obstacle.expanded(
            footprint.horizontalRadius,
            footprint.verticalRadius,
        )
        assertFalse(expanded.contains(PetPoint(136f - 0.001f, 400f)))
        assertFalse(expanded.contains(PetPoint(464f + 0.001f, 400f)))
        assertTrue(expanded.contains(PetPoint(300f, 400f)))
    }

    @Test
    fun `clear message gap can bridge with extended ballistic budget`() {
        val startRail = PetRoamingRail(
            key = "upper",
            perchKey = "upper",
            bounds = PetSafeBounds(120f, 291f, 700f, 291f),
        )
        val intermediate = PetRoamingRail(
            key = "middle",
            perchKey = "middle",
            bounds = PetSafeBounds(240f, 697f, 940f, 697f),
        )
        val targetRail = PetRoamingRail(
            key = "lower",
            perchKey = "lower",
            bounds = PetSafeBounds(240f, 1_630f, 920f, 1_630f),
        )
        val bounds = PetSafeBounds(120f, 290f, 956f, 1_975f)
        val footprint = PetFootprint(width = 72f, height = 72f, clearance = 6f)

        assertNull(
            planPetRailJourney(
                startRail = startRail,
                start = PetPoint(300f, 291f),
                targetRail = targetRail,
                rails = listOf(intermediate),
                bounds = bounds,
                uiObstacles = emptyList(),
                footprint = footprint,
                maximumStepLength = 630f,
            ),
        )
        val journey = requireNotNull(
            planPetRailJourney(
                startRail = startRail,
                start = PetPoint(300f, 291f),
                targetRail = targetRail,
                rails = listOf(intermediate),
                bounds = bounds,
                uiObstacles = emptyList(),
                footprint = footprint,
                maximumStepLength = 1_080f,
            ),
        )
        assertEquals(listOf("middle", "lower"), journey.map { it.rail.key })
        assertTrue(journey.all { it.route.length <= 1_080f })
    }

    @Test
    fun `bubble excursion rejects an edge hop that would cross bubble content`() {
        val bubble = PetMeasuredPerch(
            key = "chat-message-perch:assistant-1",
            bounds = PetObstacle(40f, 120f, 240f, 190f),
        )
        val narrowComposer = PetRoamingRail(
            key = "chat-composer-perch:0",
            perchKey = "chat-composer-perch",
            bounds = PetSafeBounds(20f, 250f, 200f, 250f),
        )

        assertNull(
            planPetBubbleExcursion(
                bubble = bubble,
                composerRail = narrowComposer,
                footprint = PetFootprint(width = 40f, height = 40f),
                outer = PetSafeBounds(20f, 20f, 300f, 280f),
                uiObstacles = emptyList(),
                useLeftGutter = false,
                verticalClearance = 6f,
                minimumWalkWidth = 40f,
            ),
        )

        assertNull(
            planPetBubbleExcursion(
                bubble = PetMeasuredPerch(
                    key = "chat-message-perch:phone-width",
                    bounds = PetObstacle(52f, 140f, 352f, 260f),
                ),
                composerRail = PetRoamingRail(
                    key = "chat-composer-perch:0",
                    perchKey = "chat-composer-perch",
                    bounds = PetSafeBounds(40f, 700f, 371f, 700f),
                ),
                footprint = PetFootprint(width = 56f, height = 56f, clearance = 3f),
                outer = PetSafeBounds(40f, 40f, 371f, 760f),
                uiObstacles = emptyList(),
                useLeftGutter = false,
                verticalClearance = 6f,
                minimumWalkWidth = 28f,
            ),
        )

        assertNull(
            planPetBubbleExcursion(
                bubble = PetMeasuredPerch(
                    key = "chat-message-perch:assistant-2",
                    bounds = PetObstacle(40f, 120f, 200f, 190f),
                ),
                composerRail = PetRoamingRail(
                    key = "chat-composer-perch:0",
                    perchKey = "chat-composer-perch",
                    bounds = PetSafeBounds(20f, 250f, 280f, 250f),
                ),
                footprint = PetFootprint(width = 40f, height = 40f),
                outer = PetSafeBounds(20f, 20f, 280f, 280f),
                uiObstacles = listOf(PetObstacle(210f, 80f, 250f, 270f)),
                useLeftGutter = false,
                verticalClearance = 6f,
                minimumWalkWidth = 40f,
            ),
        )
    }

    @Test
    fun `perch obstacle can split one ledge into two reachable rails`() {
        val outer = PetSafeBounds(0f, 0f, 300f, 300f)
        val footprint = PetFootprint(width = 40f, height = 40f)
        val perch = PetMeasuredPerch(
            key = "toolbar",
            bounds = PetObstacle(0f, 200f, 300f, 260f),
        )
        val obstacle = PetMeasuredObstacle(
            key = "center-pill",
            bounds = PetObstacle(125f, 150f, 175f, 195f),
        )

        val segments = petPerchSegments(perch, listOf(obstacle), footprint, outer)
        assertEquals(2, segments.size)
        assertTrue(segments[0].right < 105f)
        assertTrue(segments[1].left > 195f)
        assertEquals(180f, segments[0].top, 0f)
        assertEquals(180f, segments[1].top, 0f)
    }

    @Test
    fun `scroll control envelope is excluded from every composer rail segment`() {
        val outer = PetSafeBounds(0f, 0f, 400f, 400f)
        val footprint = PetFootprint(width = 70f, height = 70f, clearance = 6f)
        val composer = PetMeasuredPerch(
            key = "chat-composer",
            bounds = PetObstacle(0f, 260f, 400f, 340f),
        )
        val controlEnvelope = PetMeasuredObstacle(
            key = "chat-scroll-to-bottom-obstacle",
            bounds = PetObstacle(292f, 200f, 364f, 248f),
        )

        val segments = petPerchSegments(
            perch = composer,
            obstacles = listOf(controlEnvelope),
            footprint = footprint,
            outer = outer,
        )
        val expandedControl = controlEnvelope.bounds.expanded(
            footprint.horizontalRadius,
            footprint.verticalRadius,
        )

        assertTrue(segments.isNotEmpty())
        segments.forEach { segment ->
            assertFalse(segment.left < expandedControl.right && segment.right > expandedControl.left)
        }
    }

    @Test
    fun `sibling perch segments choose an above-control hop`() {
        val safe = PetSafeBounds(20f, 20f, 280f, 220f)
        val footprint = PetFootprint(width = 40f, height = 40f)
        val control = PetObstacle(125f, 150f, 175f, 195f)
        val rails = listOf(
            PetRoamingRail("toolbar:0", "toolbar", PetSafeBounds(20f, 180f, 104.999f, 180f)),
            PetRoamingRail("toolbar:1", "toolbar", PetSafeBounds(195.001f, 180f, 280f, 180f)),
        )

        val transfer = choosePetRailTransfer(
            currentRail = rails[0],
            current = PetPoint(104.999f, 180f),
            rails = rails,
            bounds = safe,
            uiObstacles = listOf(control),
            footprint = footprint,
        )

        requireNotNull(transfer)
        assertTrue(transfer.siblingSegment)
        assertEquals(rails[1], transfer.rail)
        assertTrue(transfer.route.points.all { it.y <= 180f })
        val expanded = expandObstaclesForPet(listOf(control), footprint)
        transfer.route.points.zipWithNext().forEach { (start, end) ->
            assertFalse(pathIntersectsObstacle(start, end, expanded))
        }
    }

    @Test
    fun `different perches still require horizontal overlap`() {
        val current = PetRoamingRail("composer:0", "composer", PetSafeBounds(20f, 180f, 100f, 180f))
        val disjoint = PetRoamingRail("header:0", "header", PetSafeBounds(160f, 80f, 280f, 80f))

        assertNull(
            choosePetRailTransfer(
                currentRail = current,
                current = PetPoint(100f, 180f),
                rails = listOf(current, disjoint),
                bounds = PetSafeBounds(20f, 20f, 280f, 220f),
                uiObstacles = emptyList(),
                footprint = PetFootprint(width = 40f, height = 40f),
            ),
        )
    }

    @Test
    fun `cross level transfer respects maximum hop length`() {
        val current = PetRoamingRail(
            "status:0",
            "status",
            PetSafeBounds(40f, 700f, 360f, 700f),
        )
        val upper = PetRoamingRail(
            "settings-level:0",
            "settings-level",
            PetSafeBounds(40f, 300f, 360f, 300f),
        )
        val outer = PetSafeBounds(20f, 20f, 380f, 760f)
        val footprint = PetFootprint(width = 56f, height = 56f)

        assertNull(
            choosePetRailTransfer(
                currentRail = current,
                current = PetPoint(200f, 700f),
                rails = listOf(upper),
                bounds = outer,
                uiObstacles = emptyList(),
                footprint = footprint,
                maximumRouteLength = 180f,
            ),
        )
        assertEquals(
            upper,
            requireNotNull(
                choosePetRailTransfer(
                    currentRail = current,
                    current = PetPoint(200f, 700f),
                    rails = listOf(upper),
                    bounds = outer,
                    uiObstacles = emptyList(),
                    footprint = footprint,
                    maximumRouteLength = 420f,
                ),
            ).rail,
        )
    }

    @Test
    fun `overlay route detours around registered control instead of crossing it`() {
        val safe = PetSafeBounds(20f, 20f, 180f, 180f)
        val rawControl = PetObstacle(80f, 60f, 120f, 140f)
        val footprint = PetFootprint(width = 20f, height = 20f, clearance = 4f)
        val expanded = expandObstaclesForPet(listOf(rawControl), footprint)

        val route = findOverlayRoute(
            start = PetPoint(30f, 100f),
            requestedDestination = PetPoint(170f, 100f),
            bounds = safe,
            uiObstacles = listOf(rawControl),
            footprint = footprint,
        )
        requireNotNull(route)
        assertEquals(PetPoint(30f, 100f), route.start)
        assertEquals(PetPoint(170f, 100f), route.destination)
        assertTrue(route.points.size >= 3)
        route.points.zipWithNext().forEach { (start, end) ->
            assertFalse(pathIntersectsObstacle(start, end, expanded))
        }
    }

    @Test
    fun `overlay route returns null when registered controls form a full barrier`() {
        assertNull(
            findOverlayRoute(
                start = PetPoint(20f, 50f),
                requestedDestination = PetPoint(180f, 50f),
                bounds = PetSafeBounds(10f, 10f, 190f, 90f),
                uiObstacles = listOf(PetObstacle(90f, 0f, 110f, 100f)),
                footprint = PetFootprint(width = 20f, height = 20f),
            ),
        )
    }

    @Test
    fun `bubble visit prefers nearest reachable above corner and stays exterior`() {
        val safe = PetSafeBounds(0f, 0f, 300f, 300f)
        val footprint = PetFootprint(width = 20f, height = 20f)
        val bubble = PetObstacle(100f, 120f, 200f, 200f)

        val route = findBubbleVisitRoute(
            targetBounds = bubble,
            footprint = footprint,
            bounds = safe,
            uiObstacles = emptyList(),
            current = PetPoint(240f, 260f),
        )

        requireNotNull(route)
        assertEquals(200f, route.destination.x, 0f)
        assertTrue(route.destination.y < 110f)
        val expandedBubble = bubble.expanded(10f, 10f)
        assertFalse(expandedBubble.contains(route.destination))
        route.points.zipWithNext().forEach { (start, end) ->
            assertFalse(pathIntersectsObstacle(start, end, listOf(expandedBubble)))
        }
    }

    @Test
    fun `bubble visit falls back to nearest side when above anchors are blocked`() {
        val route = findBubbleVisitRoute(
            targetBounds = PetObstacle(100f, 120f, 200f, 200f),
            footprint = PetFootprint(width = 20f, height = 20f),
            bounds = PetSafeBounds(0f, 0f, 300f, 300f),
            uiObstacles = listOf(PetObstacle(80f, 80f, 220f, 110f)),
            current = PetPoint(20f, 160f),
        )

        requireNotNull(route)
        assertTrue(route.destination.x < 90f)
        assertEquals(160f, route.destination.y, 0f)
    }

    @Test
    fun `bubble visit returns null when expanded bubble fills safe bounds`() {
        assertNull(
            findBubbleVisitRoute(
                targetBounds = PetObstacle(10f, 10f, 90f, 90f),
                footprint = PetFootprint(width = 20f, height = 20f),
                bounds = PetSafeBounds(0f, 0f, 100f, 100f),
                uiObstacles = emptyList(),
                current = PetPoint(0f, 0f),
            ),
        )
    }

    @Test
    fun `deterministic overlay route is candidate order independent and collision free`() {
        val safe = PetSafeBounds(10f, 10f, 190f, 190f)
        val obstacle = PetObstacle(80f, 70f, 120f, 130f)
        val candidates = listOf(PetPoint(170f, 100f), PetPoint(20f, 170f), PetPoint(170f, 170f))
        val footprint = PetFootprint(width = 16f, height = 16f, clearance = 2f)

        val route = chooseDeterministicOverlayRoute(
            current = PetPoint(20f, 100f),
            candidates = candidates,
            bounds = safe,
            uiObstacles = listOf(obstacle),
            footprint = footprint,
            seed = 88L,
        )
        val reordered = chooseDeterministicOverlayRoute(
            current = PetPoint(20f, 100f),
            candidates = candidates.reversed(),
            bounds = safe,
            uiObstacles = listOf(obstacle),
            footprint = footprint,
            seed = 88L,
        )
        assertEquals(route, reordered)
        requireNotNull(route)
        val expanded = expandObstaclesForPet(listOf(obstacle), footprint)
        route.points.zipWithNext().forEach { (start, end) ->
            assertFalse(pathIntersectsObstacle(start, end, expanded))
        }
    }

    @Test
    fun `route templates are query safe and support path arguments`() {
        assertTrue(petRouteMatches("chat", "chat?openAgentSheet=true"))
        assertTrue(petRouteMatches("profile/{name}", "profile/default?section=soul"))
        assertFalse(petRouteMatches("profile/{name}", "profile/default/soul"))
        assertFalse(petRouteMatches("chat", "settings"))
    }

    @Test
    fun `perch rail fits footprint and support follows top edge`() {
        val perch = PetMeasuredPerch(
            key = "toolbar",
            bounds = PetObstacle(10f, 160f, 190f, 200f),
        )
        val footprint = PetFootprint(width = 40f, height = 56f, clearance = 4f)
        val outer = PetSafeBounds(20f, 20f, 180f, 200f)

        assertEquals(
            PetSafeBounds(34f, 132f, 166f, 132f),
            petPerchRail(perch, footprint, outer),
        )
        assertTrue(isPetSupportedByPerch(PetPoint(100f, 132f), perch, footprint))
        assertFalse(isPetSupportedByPerch(PetPoint(100f, 129f), perch, footprint))
        assertFalse(isPetSupportedByPerch(PetPoint(15f, 132f), perch, footprint))
    }

    @Test
    fun `rail journey uses stepping stones and declines a sparse screen height jump`() {
        val outer = PetSafeBounds(20f, 20f, 380f, 760f)
        val footprint = PetFootprint(width = 56f, height = 56f, clearance = 3f)
        val composer = PetRoamingRail(
            key = "composer",
            perchKey = "composer",
            bounds = PetSafeBounds(40f, 700f, 360f, 700f),
        )
        val lower = PetRoamingRail(
            key = "lower",
            perchKey = "lower",
            bounds = PetSafeBounds(60f, 540f, 340f, 540f),
        )
        val middle = PetRoamingRail(
            key = "middle",
            perchKey = "middle",
            bounds = PetSafeBounds(60f, 380f, 340f, 380f),
        )
        val target = PetRoamingRail(
            key = "target",
            perchKey = "target",
            bounds = PetSafeBounds(60f, 220f, 340f, 220f),
        )

        assertNull(
            planPetRailJourney(
                startRail = composer,
                start = PetPoint(200f, 700f),
                targetRail = target,
                rails = emptyList(),
                bounds = outer,
                uiObstacles = emptyList(),
                footprint = footprint,
                maximumStepLength = 180f,
            ),
        )

        val journey = requireNotNull(
            planPetRailJourney(
                startRail = composer,
                start = PetPoint(200f, 700f),
                targetRail = target,
                rails = listOf(lower, middle),
                bounds = outer,
                uiObstacles = emptyList(),
                footprint = footprint,
                maximumStepLength = 180f,
            ),
        )
        assertEquals(listOf(lower, middle, target), journey.map { it.rail })
        assertTrue(journey.all { it.route.length <= 180f })
    }

    @Test
    fun `rail journey may touch a zero width bubble point without settling there`() {
        val footprint = PetFootprint(width = 80f, height = 80f)
        val outer = PetSafeBounds(40f, 40f, 360f, 760f)
        val composer = PetRoamingRail(
            key = "composer",
            perchKey = "composer",
            bounds = PetSafeBounds(80f, 700f, 320f, 700f),
        )
        val touchdown = PetRoamingRail(
            key = "user:touchdown",
            perchKey = "user",
            bounds = PetSafeBounds(280f, 500f, 280f, 500f),
        )
        val target = PetRoamingRail(
            key = "assistant",
            perchKey = "assistant",
            bounds = PetSafeBounds(180f, 300f, 320f, 300f),
        )

        val journey = planPetRailJourney(
            startRail = composer,
            start = PetPoint(280f, 700f),
            targetRail = target,
            rails = listOf(touchdown),
            bounds = outer,
            uiObstacles = emptyList(),
            footprint = footprint,
            maximumStepLength = 250f,
        )

        requireNotNull(journey)
        assertEquals(listOf(touchdown, target), journey.map { it.rail })
        assertEquals(PetPoint(280f, 500f), journey.first().route.destination)
    }

    @Test
    fun `rail journey walks to the launch point shown by the possible route graph`() {
        val footprint = PetFootprint(width = 80f, height = 80f)
        val outer = PetSafeBounds(40f, 40f, 360f, 760f)
        val composer = PetRoamingRail(
            key = "composer",
            perchKey = "composer",
            bounds = PetSafeBounds(40f, 700f, 360f, 700f),
        )
        val message = PetRoamingRail(
            key = "message",
            perchKey = "message",
            bounds = PetSafeBounds(40f, 520f, 120f, 520f),
        )

        val possible = planPetDebugRouteGraph(
            rails = listOf(composer, message),
            bounds = outer,
            uiObstacles = emptyList(),
            footprint = footprint,
            maximumRouteLength = 210f,
        )
        val journey = requireNotNull(
            planPetRailJourney(
                startRail = composer,
                start = PetPoint(360f, 700f),
                targetRail = message,
                rails = emptyList(),
                bounds = outer,
                uiObstacles = emptyList(),
                footprint = footprint,
                maximumStepLength = 210f,
            ),
        )

        assertEquals(1, possible.size)
        assertEquals(PetPoint(80f, 520f), possible.single().start)
        assertEquals(PetPoint(80f, 700f), possible.single().destination)
        assertEquals(PetPoint(360f, 700f), requireNotNull(journey.single().approach).start)
        assertEquals(PetPoint(80f, 700f), journey.single().approach?.destination)
        assertEquals(possible.single().points.asReversed(), journey.single().route.points)
    }

    @Test
    fun `rail journey backtracks when the nearest upward foothold is a dead end`() {
        val footprint = PetFootprint(width = 20f, height = 20f)
        val outer = PetSafeBounds(20f, 20f, 380f, 760f)
        val composer = PetRoamingRail(
            key = "composer",
            perchKey = "composer",
            bounds = PetSafeBounds(40f, 700f, 360f, 700f),
        )
        val deadEnd = PetRoamingRail(
            key = "dead-end",
            perchKey = "dead-end",
            bounds = PetSafeBounds(40f, 600f, 40f, 600f),
        )
        val viable = PetRoamingRail(
            key = "viable",
            perchKey = "viable",
            bounds = PetSafeBounds(340f, 500f, 340f, 500f),
        )
        val target = PetRoamingRail(
            key = "target",
            perchKey = "target",
            bounds = PetSafeBounds(340f, 300f, 340f, 300f),
        )

        val journey = requireNotNull(
            planPetRailJourney(
                startRail = composer,
                start = PetPoint(200f, 700f),
                targetRail = target,
                rails = listOf(deadEnd, viable),
                bounds = outer,
                uiObstacles = emptyList(),
                footprint = footprint,
                maximumStepLength = 210f,
            ),
        )

        assertEquals(listOf(viable, target), journey.map(PetRailJourneyStep::rail))
        assertTrue(journey.all { it.route.length <= 210f })
    }

    @Test
    fun `debug route graph distinguishes possible bounded transfers from selection`() {
        val footprint = PetFootprint(width = 80f, height = 80f)
        val outer = PetSafeBounds(40f, 40f, 360f, 760f)
        val assistant = PetRoamingRail(
            key = "assistant",
            perchKey = "assistant",
            bounds = PetSafeBounds(60f, 300f, 260f, 300f),
        )
        val userTouchdown = PetRoamingRail(
            key = "user:touchdown",
            perchKey = "user",
            bounds = PetSafeBounds(300f, 500f, 300f, 500f),
        )
        val composer = PetRoamingRail(
            key = "composer",
            perchKey = "composer",
            bounds = PetSafeBounds(40f, 700f, 360f, 700f),
        )

        val graph = planPetDebugRouteGraph(
            rails = listOf(composer, userTouchdown, assistant),
            bounds = outer,
            uiObstacles = emptyList(),
            footprint = footprint,
            maximumRouteLength = 250f,
        )

        assertEquals(2, graph.size)
        assertEquals(
            graph,
            planPetDebugRouteGraph(
                rails = listOf(userTouchdown, assistant, composer),
                bounds = outer,
                uiObstacles = emptyList(),
                footprint = footprint,
                maximumRouteLength = 250f,
            ),
        )
        assertTrue(graph.any { route -> route.destination == PetPoint(300f, 500f) })
        assertTrue(graph.any { route -> route.destination == PetPoint(300f, 700f) })
    }

    @Test
    fun `step limit caps total detour length even when endpoints share a height`() {
        val horizontal = PetRoute(listOf(PetPoint(0f, 100f), PetPoint(400f, 100f)))
        val verticalDetour = PetRoute(
            listOf(PetPoint(0f, 100f), PetPoint(0f, 0f), PetPoint(400f, 100f)),
        )

        assertTrue(petRouteFitsStepLimit(horizontal, maximumStepLength = 210f))
        assertFalse(petRouteFitsStepLimit(verticalDetour, maximumStepLength = 210f))
    }

    @Test
    fun `rail journey returns through the same bounded levels`() {
        val outer = PetSafeBounds(20f, 20f, 380f, 760f)
        val footprint = PetFootprint(width = 56f, height = 56f)
        val target = PetRoamingRail("target", "target", PetSafeBounds(60f, 220f, 340f, 220f))
        val middle = PetRoamingRail("middle", "middle", PetSafeBounds(60f, 380f, 340f, 380f))
        val lower = PetRoamingRail("lower", "lower", PetSafeBounds(60f, 540f, 340f, 540f))
        val composer = PetRoamingRail("composer", "composer", PetSafeBounds(40f, 700f, 360f, 700f))

        val journey = requireNotNull(
            planPetRailJourney(
                startRail = target,
                start = PetPoint(200f, 220f),
                targetRail = composer,
                rails = listOf(middle, lower),
                bounds = outer,
                uiObstacles = emptyList(),
                footprint = footprint,
                maximumStepLength = 180f,
            ),
        )
        assertEquals(listOf(middle, lower, composer), journey.map { it.rail })
    }

    @Test
    fun `bubble exploration keeps newest first then visits older rails in screen order`() {
        val outer = PetSafeBounds(20f, 20f, 380f, 760f)
        val footprint = PetFootprint(width = 56f, height = 56f)
        val newest = PetRoamingRail("newest", "newest", PetSafeBounds(60f, 650f, 340f, 650f))
        val nearestOlder = PetRoamingRail("older-1", "older-1", PetSafeBounds(60f, 500f, 340f, 500f))
        val nextOlder = PetRoamingRail("older-2", "older-2", PetSafeBounds(60f, 350f, 340f, 350f))
        val newerThanTarget = PetRoamingRail("newer", "newer", PetSafeBounds(60f, 710f, 340f, 710f))

        val plan = planPetBubbleExploration(
            newestRail = newest,
            start = PetPoint(200f, 650f),
            visibleMessageRails = listOf(nextOlder, newerThanTarget, nearestOlder),
            bounds = outer,
            uiObstacles = emptyList(),
            footprint = footprint,
            maximumStepLength = 180f,
            maxExtraStops = 3,
        )

        assertEquals(listOf(newest, nearestOlder, nextOlder), plan.orderedRails)
        assertEquals(listOf(nearestOlder, nextOlder), plan.continuation.map { it.rail })
        assertTrue(plan.continuation.all { it.route.length <= 180f })
    }

    @Test
    fun `bubble exploration caps additional older stops at three`() {
        val outer = PetSafeBounds(20f, 20f, 380f, 760f)
        val footprint = PetFootprint(width = 56f, height = 56f)
        val newest = PetRoamingRail("newest", "newest", PetSafeBounds(60f, 700f, 340f, 700f))
        val older = listOf(580f, 460f, 340f, 220f).mapIndexed { index, y ->
            PetRoamingRail("older-$index", "older-$index", PetSafeBounds(60f, y, 340f, y))
        }

        val plan = planPetBubbleExploration(
            newestRail = newest,
            start = PetPoint(200f, 700f),
            visibleMessageRails = older,
            bounds = outer,
            uiObstacles = emptyList(),
            footprint = footprint,
            maximumStepLength = 180f,
            maxExtraStops = 3,
        )

        assertEquals(listOf(newest) + older.take(3), plan.orderedRails)
        assertEquals(3, plan.continuation.size)
    }

    @Test
    fun `settings surface tour follows several measured levels from the status rail`() {
        val outer = PetSafeBounds(20f, 20f, 380f, 760f)
        val footprint = PetFootprint(width = 56f, height = 56f)
        val footer = PetRoamingRail("footer", "footer", PetSafeBounds(60f, 700f, 340f, 700f))
        val lowerCard = PetRoamingRail("card-3", "card-3", PetSafeBounds(60f, 560f, 340f, 560f))
        val middleCard = PetRoamingRail("card-2", "card-2", PetSafeBounds(60f, 420f, 340f, 420f))
        val upperCard = PetRoamingRail("card-1", "card-1", PetSafeBounds(60f, 280f, 340f, 280f))

        val plan = planPetRailExploration(
            originRail = footer,
            start = PetPoint(200f, 700f),
            candidateRails = listOf(upperCard, lowerCard, middleCard),
            bounds = outer,
            uiObstacles = emptyList(),
            footprint = footprint,
            maximumStepLength = 180f,
            maxExtraStops = 3,
            mode = PetRailExplorationMode.AnyDirection,
        )

        assertEquals(listOf(footer, lowerCard, middleCard, upperCard), plan.orderedRails)
        assertEquals(listOf(lowerCard, middleCard, upperCard), plan.continuation.map { it.rail })
        assertEquals(
            listOf(PetPoint(200f, 420f), PetPoint(200f, 560f), PetPoint(200f, 700f)),
            plan.continuation.asReversed().map { it.route.start },
        )
    }

    @Test
    fun `settings surface tour can descend after scroll lands on an upper card`() {
        val outer = PetSafeBounds(20f, 20f, 380f, 760f)
        val footprint = PetFootprint(width = 56f, height = 56f)
        val upperCard = PetRoamingRail("card-1", "card-1", PetSafeBounds(60f, 280f, 340f, 280f))
        val middleCard = PetRoamingRail("card-2", "card-2", PetSafeBounds(60f, 420f, 340f, 420f))
        val lowerCard = PetRoamingRail("card-3", "card-3", PetSafeBounds(60f, 560f, 340f, 560f))

        val plan = planPetRailExploration(
            originRail = upperCard,
            start = PetPoint(200f, 280f),
            candidateRails = listOf(lowerCard, middleCard),
            bounds = outer,
            uiObstacles = emptyList(),
            footprint = footprint,
            maximumStepLength = 180f,
            maxExtraStops = 2,
            mode = PetRailExplorationMode.AnyDirection,
        )

        assertEquals(listOf(upperCard, middleCard, lowerCard), plan.orderedRails)
        assertEquals(listOf(middleCard, lowerCard), plan.continuation.map { it.rail })
    }

    @Test
    fun `bubble exploration stops when older terrain is unreachable`() {
        val outer = PetSafeBounds(20f, 20f, 380f, 760f)
        val footprint = PetFootprint(width = 56f, height = 56f)
        val newest = PetRoamingRail("newest", "newest", PetSafeBounds(60f, 700f, 340f, 700f))
        val blockedOlder = PetRoamingRail("blocked", "blocked", PetSafeBounds(60f, 500f, 340f, 500f))
        val fullBarrier = PetObstacle(0f, 570f, 400f, 610f)

        val plan = planPetBubbleExploration(
            newestRail = newest,
            start = PetPoint(200f, 700f),
            visibleMessageRails = listOf(blockedOlder),
            bounds = outer,
            uiObstacles = listOf(fullBarrier),
            footprint = footprint,
            maximumStepLength = 210f,
            maxExtraStops = 3,
        )

        assertEquals(listOf(newest), plan.orderedRails)
        assertTrue(plan.continuation.isEmpty())
    }

    @Test
    fun `bubble exploration skips a blocked segment when an older bounded rail is reachable`() {
        val outer = PetSafeBounds(20f, 20f, 380f, 760f)
        val footprint = PetFootprint(width = 56f, height = 56f)
        val newest = PetRoamingRail("newest", "newest", PetSafeBounds(60f, 700f, 340f, 700f))
        val blockedNearest = PetRoamingRail("blocked", "blocked", PetSafeBounds(190f, 620f, 210f, 620f))
        val reachableOlder = PetRoamingRail("reachable", "reachable", PetSafeBounds(60f, 540f, 100f, 540f))
        val obstacleAtBlockedLanding = PetObstacle(195f, 615f, 205f, 625f)

        val plan = planPetBubbleExploration(
            newestRail = newest,
            start = PetPoint(200f, 700f),
            visibleMessageRails = listOf(blockedNearest, reachableOlder),
            bounds = outer,
            uiObstacles = listOf(obstacleAtBlockedLanding),
            footprint = footprint,
            maximumStepLength = 210f,
            maxExtraStops = 3,
        )

        assertEquals(listOf(newest, reachableOlder), plan.orderedRails)
        assertEquals(reachableOlder, plan.continuation.single().rail)
    }

    @Test
    fun `bubble exploration with no extra allowance stays on newest`() {
        val outer = PetSafeBounds(20f, 20f, 380f, 760f)
        val footprint = PetFootprint(width = 56f, height = 56f)
        val newest = PetRoamingRail("newest", "newest", PetSafeBounds(60f, 650f, 340f, 650f))
        val older = PetRoamingRail("older", "older", PetSafeBounds(60f, 500f, 340f, 500f))

        val plan = planPetBubbleExploration(
            newestRail = newest,
            start = PetPoint(200f, 650f),
            visibleMessageRails = listOf(older),
            bounds = outer,
            uiObstacles = emptyList(),
            footprint = footprint,
            maximumStepLength = 210f,
            maxExtraStops = 0,
        )

        assertEquals(listOf(newest), plan.orderedRails)
        assertTrue(plan.continuation.isEmpty())
    }

    @Test
    fun `bubble exploration accepts exact top contact without opening message bodies`() {
        val outer = PetSafeBounds(20f, 20f, 380f, 760f)
        val footprint = PetFootprint(width = 56f, height = 56f, clearance = 6f)
        val newestPerch = PetMeasuredPerch(
            key = "newest",
            bounds = PetObstacle(60f, 650f, 340f, 720f),
        )
        val olderPerch = PetMeasuredPerch(
            key = "older",
            bounds = PetObstacle(60f, 500f, 340f, 570f),
        )
        val newestRail = PetRoamingRail(
            key = "newest:0",
            perchKey = newestPerch.key,
            bounds = requireNotNull(
                petPerchRail(newestPerch, footprint, outer, verticalClearance = 6f),
            ),
        )
        val olderRail = PetRoamingRail(
            key = "older:0",
            perchKey = olderPerch.key,
            bounds = requireNotNull(
                petPerchRail(olderPerch, footprint, outer, verticalClearance = 6f),
            ),
        )
        val start = PetPoint(newestRail.bounds.right, newestRail.bounds.top)

        val inclusivePlan = planPetBubbleExploration(
            newestRail = newestRail,
            start = start,
            visibleMessageRails = listOf(olderRail),
            bounds = outer,
            uiObstacles = listOf(newestPerch.bounds, olderPerch.bounds),
            footprint = footprint,
            maximumStepLength = 300f,
            maxExtraStops = 1,
        )
        val supportedPlan = planPetBubbleExploration(
            newestRail = newestRail,
            start = start,
            visibleMessageRails = listOf(olderRail),
            bounds = outer,
            uiObstacles = listOf(
                petTopSupportedObstacle(newestPerch),
                petTopSupportedObstacle(olderPerch),
            ),
            footprint = footprint,
            maximumStepLength = 300f,
            maxExtraStops = 1,
        )

        assertTrue(inclusivePlan.continuation.isEmpty())
        assertEquals(listOf(newestRail, olderRail), supportedPlan.orderedRails)
        val supportedStep = supportedPlan.continuation.single()
        assertEquals(start, supportedStep.approach?.start ?: start)
        assertEquals(
            supportedStep.route.start,
            supportedStep.approach?.destination ?: start,
        )
        assertEquals(olderRail.bounds.top, supportedStep.route.destination.y)
        assertTrue(
            petTopSupportedObstacle(olderPerch).contains(
                PetPoint(200f, olderPerch.bounds.top + 1f),
            ),
        )
    }

    @Test
    fun `exact autonomous route rejects projected live point`() {
        val safe = PetSafeBounds(20f, 20f, 280f, 280f)
        val footprint = PetFootprint(width = 20f, height = 20f)
        val bubble = PetObstacle(100f, 100f, 200f, 200f)
        val blockedStart = PetPoint(150f, 150f)
        val destination = PetPoint(250f, 250f)

        val recoveryRoute = findOverlayRoute(
            start = blockedStart,
            requestedDestination = destination,
            bounds = safe,
            uiObstacles = listOf(bubble),
            footprint = footprint,
        )
        requireNotNull(recoveryRoute)
        assertTrue(recoveryRoute.start.distanceSquaredTo(blockedStart) > 1f)
        assertNull(
            findExactOverlayRoute(
                start = blockedStart,
                requestedDestination = destination,
                bounds = safe,
                uiObstacles = listOf(bubble),
                footprint = footprint,
            ),
        )
    }

    @Test
    fun `local escape uses nearest clear edge without authorizing a long route`() {
        val start = PetPoint(110f, 170f)
        val bubble = PetObstacle(100f, 120f, 200f, 220f)
        val escape = requireNotNull(
            findLocalPetEscapeRoute(
                start = start,
                bounds = PetSafeBounds(20f, 20f, 280f, 280f),
                uiObstacles = listOf(bubble),
                footprint = PetFootprint(width = 20f, height = 20f),
                maximumLength = 40f,
            ),
        )

        assertEquals(start, escape.start)
        assertTrue(escape.destination.x < 90f)
        assertEquals(start.y, escape.destination.y, 0f)
        assertNull(
            findLocalPetEscapeRoute(
                start = start,
                bounds = PetSafeBounds(20f, 20f, 280f, 280f),
                uiObstacles = listOf(bubble),
                footprint = PetFootprint(width = 20f, height = 20f),
                maximumLength = 10f,
            ),
        )
    }

    @Test
    fun `settled chat paces in a wide text-free side pocket`() {
        val footprint = PetFootprint(width = 56f, height = 56f)
        val habitat = planSettledChatHabitat(
            bubble = PetMeasuredPerch("message", PetObstacle(70f, 100f, 240f, 220f)),
            composerRails = listOf(composerRail()),
            obstacles = emptyList(),
            footprint = footprint,
            outer = PetSafeBounds(28f, 28f, 372f, 772f),
            useLeftPocket = false,
            verticalClearance = 6f,
        )

        requireNotNull(habitat)
        assertEquals(PetSettledChatMode.SidePocketPace, habitat.mode)
        assertTrue(habitat.rail.bounds.left >= 274f)
        val center = habitat.rail.bounds.clamp(PetPoint(300f, 192f))
        val petBounds = PetObstacle(
            center.x - footprint.width / 2f,
            center.y - footprint.height / 2f,
            center.x + footprint.width / 2f,
            center.y + footprint.height / 2f,
        )
        assertFalse(petBounds.intersects(PetObstacle(70f, 100f, 240f, 220f)))
    }

    @Test
    fun `settled chat idles in a narrow side pocket then falls back to composer when top entry is unsafe`() {
        val footprint = PetFootprint(width = 56f, height = 56f)
        val outer = PetSafeBounds(28f, 28f, 372f, 772f)
        val narrow = planSettledChatHabitat(
            bubble = PetMeasuredPerch("narrow", PetObstacle(70f, 100f, 330f, 220f)),
            composerRails = listOf(composerRail()),
            obstacles = emptyList(),
            footprint = footprint,
            outer = outer,
            useLeftPocket = false,
            verticalClearance = 6f,
        )
        val fullWidth = planSettledChatHabitat(
            bubble = PetMeasuredPerch("full", PetObstacle(70f, 100f, 360f, 220f)),
            composerRails = listOf(composerRail()),
            obstacles = emptyList(),
            footprint = footprint,
            outer = outer,
            useLeftPocket = false,
            verticalClearance = 6f,
        )

        requireNotNull(narrow)
        requireNotNull(fullWidth)
        assertEquals(PetSettledChatMode.SidePocketIdle, narrow.mode)
        assertTrue(narrow.rail.bounds.width < footprint.width * 0.75f)
        assertEquals(PetSettledChatMode.ComposerCorner, fullWidth.mode)
        assertEquals(360f, fullWidth.rail.bounds.left, 0f)
        assertEquals(fullWidth.rail.bounds.left, fullWidth.rail.bounds.right, 0f)
    }

    @Test
    fun `settled chat excludes a transient control from the side pocket`() {
        val footprint = PetFootprint(width = 56f, height = 56f)
        val fab = PetMeasuredObstacle(
            key = "occupied:chat-scroll-to-bottom",
            bounds = PetObstacle(286f, 155f, 370f, 239f),
        )

        val habitat = planSettledChatHabitat(
            bubble = PetMeasuredPerch("message", PetObstacle(70f, 100f, 240f, 220f)),
            composerRails = listOf(composerRail()),
            obstacles = listOf(fab),
            footprint = footprint,
            outer = PetSafeBounds(28f, 28f, 372f, 772f),
            useLeftPocket = false,
            verticalClearance = 6f,
        )

        requireNotNull(habitat)
        habitat.rail.bounds.let { rail ->
            val center = rail.clamp(PetPoint(rail.right, rail.top))
            val petBounds = PetObstacle(
                center.x - footprint.horizontalRadius,
                center.y - footprint.verticalRadius,
                center.x + footprint.horizontalRadius,
                center.y + footprint.verticalRadius,
            )
            assertFalse(petBounds.intersects(fab.bounds))
        }
    }

    @Test
    fun `scroll control trims crossing rails but keeps its raised perch`() {
        val footprint = PetFootprint(width = 70f, height = 70f)
        val outer = PetSafeBounds(left = 0f, top = 0f, right = 411f, bottom = 800f)
        val button = PetMeasuredObstacle(
            key = "chat-scroll-to-bottom-obstacle",
            bounds = PetObstacle(left = 347f, top = 620f, right = 395f, bottom = 668f),
        )
        val composer = PetMeasuredPerch(
            key = "chat-composer-perch",
            bounds = PetObstacle(left = 0f, top = 668f, right = 411f, bottom = 740f),
        )
        val buttonLedge = PetMeasuredPerch(
            key = "chat-scroll-to-bottom-perch",
            bounds = PetObstacle(left = 335f, top = 620f, right = 407f, bottom = 668f),
        )

        val composerRails = petPerchSegments(
            perch = composer,
            obstacles = listOf(button),
            footprint = footprint,
            outer = outer,
            verticalClearance = 6f,
        )
        val buttonRails = petPerchSegments(
            perch = buttonLedge,
            obstacles = listOf(button),
            footprint = footprint,
            outer = outer,
            verticalClearance = 6f,
        )

        assertTrue(
            composerRails.all { rail ->
                rail.right + footprint.horizontalRadius <= button.bounds.left
            },
        )
        assertEquals(1, buttonRails.size)
        assertEquals(579f, buttonRails.single().top, 0f)
    }

    @Test
    fun `blocked side and top fall back to the outer composer corner`() {
        val habitat = planSettledChatHabitat(
            bubble = PetMeasuredPerch("blocked", PetObstacle(70f, 100f, 360f, 220f)),
            composerRails = listOf(composerRail()),
            obstacles = listOf(
                PetMeasuredObstacle("top-control", PetObstacle(40f, 55f, 365f, 78f)),
            ),
            footprint = PetFootprint(width = 56f, height = 56f),
            outer = PetSafeBounds(28f, 28f, 372f, 772f),
            useLeftPocket = false,
            verticalClearance = 6f,
        )

        requireNotNull(habitat)
        assertEquals(PetSettledChatMode.ComposerCorner, habitat.mode)
        assertEquals(360f, habitat.rail.bounds.left, 0f)
        assertEquals(habitat.rail.bounds.left, habitat.rail.bounds.right, 0f)
    }

    @Test
    fun `settled side pocket mirrors between left and right bubble alignment`() {
        val footprint = PetFootprint(width = 56f, height = 56f)
        val outer = PetSafeBounds(28f, 28f, 372f, 772f)
        val right = planSettledChatHabitat(
            bubble = PetMeasuredPerch("assistant", PetObstacle(70f, 100f, 240f, 220f)),
            composerRails = listOf(composerRail()),
            obstacles = emptyList(),
            footprint = footprint,
            outer = outer,
            useLeftPocket = false,
            verticalClearance = 6f,
        )
        val left = planSettledChatHabitat(
            bubble = PetMeasuredPerch("user", PetObstacle(160f, 100f, 330f, 220f)),
            composerRails = listOf(composerRail()),
            obstacles = emptyList(),
            footprint = footprint,
            outer = outer,
            useLeftPocket = true,
            verticalClearance = 6f,
        )

        requireNotNull(right)
        requireNotNull(left)
        assertEquals(PetSettledChatMode.SidePocketPace, right.mode)
        assertEquals(PetSettledChatMode.SidePocketPace, left.mode)
        assertTrue(right.rail.bounds.left > 240f)
        assertTrue(left.rail.bounds.right < 160f)
    }

    private fun composerRail() = PetRoamingRail(
        key = "composer:0",
        perchKey = "chat-composer-perch",
        bounds = PetSafeBounds(40f, 700f, 360f, 700f),
    )

}

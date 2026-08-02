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

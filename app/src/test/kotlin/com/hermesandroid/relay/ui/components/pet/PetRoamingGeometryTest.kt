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
}

package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.ui.components.pet.PetFootprint
import com.hermesandroid.relay.ui.components.pet.PetMeasuredPerch
import com.hermesandroid.relay.ui.components.pet.PetObstacle
import com.hermesandroid.relay.ui.components.pet.PetPoint
import com.hermesandroid.relay.ui.components.pet.PetRoamingRail
import com.hermesandroid.relay.ui.components.pet.PetRoute
import com.hermesandroid.relay.ui.components.pet.PetRouteScope
import com.hermesandroid.relay.ui.components.pet.PetSafeBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PetTerrainDebugOverlayTest {

    @Test
    fun `model resolves active rail and preserves planned route order`() {
        val firstRail = rail("composer:0", "composer", 20f, 180f, 200f)
        val activeRail = rail("bubble:0", "bubble", 80f, 90f, 220f)
        val route = PetRoute(
            listOf(
                PetPoint(20f, 180f),
                PetPoint(80f, 140f),
                PetPoint(90f, 90f),
            ),
        )
        val model = model(
            rails = listOf(firstRail, activeRail),
            activeRailKey = activeRail.key,
            possibleRoutes = listOf(route),
            activeRoute = PetDebugActiveRoute(route, PetDebugRouteKind.Autonomous),
        )

        assertEquals(activeRail, model.activeRail)
        assertEquals(route.points, model.activePoints)
    }

    @Test
    fun `legend reports route gate movement and terrain counts`() {
        val model = model(
            routeLabel = "chat",
            perches = listOf(
                PetMeasuredPerch(
                    key = "composer",
                    bounds = PetObstacle(10f, 210f, 250f, 240f),
                    routeScope = PetRouteScope(setOf("chat")),
                ),
            ),
            rails = listOf(rail("composer:0", "composer", 20f, 180f, 200f)),
            activeRailKey = "composer:0",
            expandedObstacles = listOf(PetObstacle(200f, 130f, 260f, 190f)),
            locomotionLabel = "WalkRight",
            gateLabel = "patrolling",
        )

        assertEquals(
            listOf(
                "route chat",
                "routes possible 0  active none",
                "plan none",
                "blue dashed=possible  yellow=plan  orange=auto  pink=recovery  teal=drag",
                "rail composer:0  move WalkRight",
                "gate patrolling",
                "perches 1  rails 1  hops 0  obstacles 1",
            ),
            petTerrainLegendLines(model),
        )
    }

    @Test
    fun `debug views progressively disclose planner terrain and raw geometry`() {
        val plan = petTerrainDebugLayerVisibility(PetTerrainDebugViewMode.Plan)
        val terrain = petTerrainDebugLayerVisibility(PetTerrainDebugViewMode.Terrain)
        val full = petTerrainDebugLayerVisibility(PetTerrainDebugViewMode.Full)

        assertEquals(false, plan.rails)
        assertEquals(false, plan.candidates)
        assertEquals(false, plan.rawLabels)

        assertEquals(true, terrain.perches)
        assertEquals(true, terrain.rails)
        assertEquals(true, terrain.candidates)
        assertEquals(true, terrain.compactRailLabels)
        assertEquals(false, terrain.safeBounds)
        assertEquals(false, terrain.rawLabels)
        assertEquals(false, terrain.footprint)

        assertEquals(true, full.safeBounds)
        assertEquals(true, full.rawLabels)
        assertEquals(true, full.footprint)
        assertEquals(false, full.compactRailLabels)
    }

    @Test
    fun `inspector summarizes the selected route in product language`() {
        val composer = rail(
            key = "composer:0",
            perchKey = CHAT_PET_WALK_REGION,
            left = 20f,
            top = 180f,
            right = 200f,
        )
        val planned = requireNotNull(
            petDebugPlannedRoute(
                targetLabel = "A:latest",
                routes = listOf(
                    PetRoute(listOf(PetPoint(20f, 180f), PetPoint(90f, 180f))),
                    PetRoute(listOf(PetPoint(90f, 180f), PetPoint(90f, 90f))),
                ),
            ),
        )
        val model = model(
            rails = listOf(composer),
            activeRailKey = composer.key,
            plannedRoute = planned,
        )

        assertEquals("Composer → Assistant · 3 stops", petTerrainInspectorSummary(model))
    }

    @Test
    fun `inspector explicitly reports when no route is selected`() {
        val composer = rail("composer:0", CHAT_PET_WALK_REGION, 20f, 180f, 200f)
        val model = model(rails = listOf(composer), activeRailKey = composer.key)

        assertEquals("Composer · no selected route", petTerrainInspectorSummary(model))
    }

    @Test
    fun `inspector placement starts below the header and stays inside system insets`() {
        val placement = petTerrainInspectorPlacement(
            viewportWidth = 1080f,
            viewportHeight = 2340f,
            panelWidth = 900f,
            panelHeight = 150f,
            safeTop = 264f,
            bottomInset = 96f,
            margin = 8f,
            horizontalFraction = 0.5f,
            verticalFraction = 0f,
        )

        assertEquals(90f, placement.x, 0.001f)
        assertEquals(264f, placement.y, 0.001f)
        assertEquals(164f, placement.horizontalRange, 0.001f)
        assertEquals(1822f, placement.verticalRange, 0.001f)

        val bottomDocked = petTerrainInspectorPlacement(
            viewportWidth = 1080f,
            viewportHeight = 2340f,
            panelWidth = 900f,
            panelHeight = 500f,
            safeTop = 264f,
            bottomInset = 96f,
            margin = 8f,
            horizontalFraction = 1f,
            verticalFraction = 1f,
        )
        assertEquals(2340f, bottomDocked.y + 500f + 96f + 8f, 0.001f)
    }

    @Test
    fun `inspector drag fractions clamp and horizontal release snaps to an edge`() {
        assertEquals(
            1f,
            petTerrainInspectorDraggedFraction(
                currentPosition = 90f,
                delta = 200f,
                rangeStart = 8f,
                range = 164f,
            ),
            0.001f,
        )
        assertEquals(
            0f,
            petTerrainInspectorDraggedFraction(
                currentPosition = 90f,
                delta = -200f,
                rangeStart = 8f,
                range = 164f,
            ),
            0.001f,
        )
        assertEquals(
            0.5f,
            petTerrainInspectorDraggedFraction(90f, 20f, 8f, 0f),
            0.001f,
        )
        assertEquals(0f, petTerrainInspectorSnappedHorizontalFraction(0.49f), 0.001f)
        assertEquals(1f, petTerrainInspectorSnappedHorizontalFraction(0.5f), 0.001f)
    }

    @Test
    fun `empty optional diagnostics remain safe and explicit`() {
        val model = model(routeLabel = null, activeRailKey = "missing")

        assertNull(model.activeRail)
        assertEquals(emptyList<PetPoint>(), model.activePoints)
        assertEquals("route none", petTerrainLegendLines(model).first())
        assertEquals("routes possible 0  active none", petTerrainLegendLines(model)[1])
        assertEquals("plan none", petTerrainLegendLines(model)[2])
        assertEquals(
            "blue dashed=possible  yellow=plan  orange=auto  pink=recovery  teal=drag",
            petTerrainLegendLines(model)[3],
        )
        assertEquals("rail missing  move None", petTerrainLegendLines(model)[4])
    }

    @Test
    fun `possible route remains distinct until the behavior director activates it`() {
        val route = PetRoute(listOf(PetPoint(80f, 500f), PetPoint(80f, 300f)))
        val model = model(possibleRoutes = listOf(route), activeRoute = null)

        assertEquals("routes possible 1  active none", petTerrainLegendLines(model)[1])
        assertEquals(emptyList<PetPoint>(), model.activePoints)
    }

    @Test
    fun `selected routes expose numbered out and back stops`() {
        val origin = PetPoint(60f, 500f)
        val composer = PetPoint(90f, 500f)
        val bubble = PetPoint(90f, 320f)
        val planned = requireNotNull(
            petDebugPlannedRoute(
                targetLabel = "A:latest",
                routes = listOf(
                    PetRoute(listOf(origin)),
                    PetRoute(listOf(origin, composer)),
                    PetRoute(listOf(composer, bubble)),
                ),
            ),
        )
        val model = model(plannedRoute = planned)

        assertEquals(listOf(origin, composer, bubble), planned.stops)
        assertEquals(listOf(0, 1, 2, 1, 0), planned.loopStopIndices)
        assertEquals("0→1→2→1→0", planned.loopLabel)
        assertEquals("plan 0→1→2→1→0  target A:latest", petTerrainLegendLines(model)[2])
    }

    @Test
    fun `nearby stop markers retain every number in one badge`() {
        val badges = petDebugStopBadges(
            stops = listOf(
                PetPoint(20f, 500f),
                PetPoint(100f, 400f),
                PetPoint(106f, 405f),
            ),
            clusterDistance = 10f,
        )

        assertEquals(listOf("0", "1/2"), badges.map(PetDebugStopBadge::label))
        assertEquals(PetPoint(100f, 400f), badges[1].point)
    }

    @Test
    fun `terrain labels identify roles missing rails and active mapping`() {
        val assistant = PetMeasuredPerch(
            key = "${CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX}${CHAT_PET_STEP_MESSAGE_MARKER}assistant-key",
            bounds = PetObstacle(20f, 150f, 240f, 210f),
        )
        val narrowUser = PetMeasuredPerch(
            key = "${CHAT_PET_USER_MESSAGE_PERCH_PREFIX}${CHAT_PET_STEP_MESSAGE_MARKER}user-key",
            bounds = PetObstacle(230f, 220f, 270f, 260f),
        )
        val tinyUser = PetMeasuredPerch(
            key = "${CHAT_PET_USER_MESSAGE_PERCH_PREFIX}${CHAT_PET_STEP_MESSAGE_MARKER}tiny-key",
            bounds = PetObstacle(250f, 280f, 270f, 320f),
        )
        val assistantRail = rail("assistant:0", assistant.key, 60f, 120f, 200f)
        val userTouchdown = rail("user:touchdown", narrowUser.key, 250f, 190f, 250f)
        val model = model(
            perches = listOf(assistant, narrowUser, tinyUser),
            rails = listOf(assistantRail),
            touchdownRails = listOf(userTouchdown),
            activeRailKey = assistantRail.key,
        )

        assertEquals("P0 A*:assistant-key", petTerrainPerchLabels(model)[0].text)
        assertEquals("P1 U*:user-key HOP", petTerrainPerchLabels(model)[1].text)
        assertEquals("P2 U*:tiny-key NO-RAIL", petTerrainPerchLabels(model)[2].text)
        assertEquals("R0→P0 ACT", petTerrainRailLabels(model).single().text)
        assertEquals("H0→P1", petTerrainTouchdownLabels(model).single().text)
    }

    private fun model(
        routeLabel: String? = "settings",
        perches: List<PetMeasuredPerch> = emptyList(),
        rails: List<PetRoamingRail> = emptyList(),
        touchdownRails: List<PetRoamingRail> = emptyList(),
        activeRailKey: String? = null,
        expandedObstacles: List<PetObstacle> = emptyList(),
        possibleRoutes: List<PetRoute> = emptyList(),
        plannedRoute: PetDebugPlannedRoute? = null,
        activeRoute: PetDebugActiveRoute? = null,
        locomotionLabel: String = "None",
        gateLabel: String = "paused",
    ) = PetTerrainDebugModel(
        routeLabel = routeLabel,
        safeBounds = PetSafeBounds(10f, 20f, 280f, 600f),
        perches = perches,
        rails = rails,
        touchdownRails = touchdownRails,
        activeRailKey = activeRailKey,
        expandedObstacles = expandedObstacles,
        footprint = PetFootprint(width = 56f, height = 56f, clearance = 4f),
        petCenter = PetPoint(140f, 180f),
        possibleRoutes = possibleRoutes,
        plannedRoute = plannedRoute,
        activeRoute = activeRoute,
        locomotionLabel = locomotionLabel,
        gateLabel = gateLabel,
    )

    private fun rail(
        key: String,
        perchKey: String,
        left: Float,
        top: Float,
        right: Float,
    ) = PetRoamingRail(
        key = key,
        perchKey = perchKey,
        bounds = PetSafeBounds(left, top, right, top),
    )
}

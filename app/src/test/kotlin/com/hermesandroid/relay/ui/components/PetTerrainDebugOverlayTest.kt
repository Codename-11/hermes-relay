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
                "blue dashed=possible  orange=auto  pink=recovery  teal=drag",
                "rail composer:0  move WalkRight",
                "gate patrolling",
                "perches 1  rails 1  hops 0  obstacles 1",
            ),
            petTerrainLegendLines(model),
        )
    }

    @Test
    fun `empty optional diagnostics remain safe and explicit`() {
        val model = model(routeLabel = null, activeRailKey = "missing")

        assertNull(model.activeRail)
        assertEquals(emptyList<PetPoint>(), model.activePoints)
        assertEquals("route none", petTerrainLegendLines(model).first())
        assertEquals("routes possible 0  active none", petTerrainLegendLines(model)[1])
        assertEquals(
            "blue dashed=possible  orange=auto  pink=recovery  teal=drag",
            petTerrainLegendLines(model)[2],
        )
        assertEquals("rail missing  move None", petTerrainLegendLines(model)[3])
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

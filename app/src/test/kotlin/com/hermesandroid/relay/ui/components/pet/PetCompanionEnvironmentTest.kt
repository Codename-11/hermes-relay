package com.hermesandroid.relay.ui.components.pet

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetCompanionEnvironmentTest {

    @Test
    fun `surface state is isolated by navigation owner`() {
        val coordinator = PetCompanionCoordinator()
        coordinator.publishSurface(owner = "chat", scrolling = true, hidden = false)
        coordinator.publishSurface(owner = "terminal", scrolling = false, hidden = true)

        assertTrue(coordinator.activityFor("chat").scrolling)
        assertFalse(coordinator.activityFor("chat").hidden)
        assertFalse(coordinator.activityFor("terminal").scrolling)
        assertTrue(coordinator.activityFor("terminal").hidden)

        coordinator.clearSurface("chat")
        assertTrue(coordinator.activityFor("terminal").hidden)
        assertFalse(coordinator.activityFor("chat").scrolling)
    }

    @Test
    fun `snapshot includes global and matching route surfaces only`() {
        val registry = PetSafeAreaRegistry()
        registry.updatePerch("global", Rect(0f, 80f, 100f, 100f), PetRouteScope())
        registry.updatePerch(
            "chat",
            Rect(0f, 180f, 100f, 220f),
            PetRouteScope(setOf("chat")),
        )
        registry.updatePerch(
            "profile",
            Rect(10f, 120f, 90f, 150f),
            PetRouteScope(setOf("profile/{name}")),
        )
        registry.updateObstacle(
            "chat-fab",
            Rect(70f, 130f, 100f, 175f),
            PetRouteScope(setOf("chat")),
        )
        registry.updateVisitTarget(
            "latest-reply",
            Rect(8f, 240f, 92f, 300f),
            PetRouteScope(setOf("chat")),
        )

        val chat = registry.snapshot("chat?openAgentSheet=true")
        assertEquals(listOf("chat", "global"), chat.perches.map { it.key })
        assertEquals(listOf("chat-fab"), chat.obstacles.map { it.key })
        assertEquals(listOf("latest-reply"), chat.visitTargets.map { it.key })

        val profile = registry.snapshot("profile/default?section=soul")
        assertEquals(listOf("global", "profile"), profile.perches.map { it.key })
        assertTrue(profile.obstacles.isEmpty())
        assertTrue(profile.visitTargets.isEmpty())

        val routeUnknown = registry.snapshot(null)
        assertEquals(listOf("global"), routeUnknown.perches.map { it.key })
        assertTrue(routeUnknown.obstacles.isEmpty())
    }

    @Test
    fun `updates replace measurements and unregister removes live surfaces`() {
        val registry = PetSafeAreaRegistry()
        val first = Rect(0f, 100f, 80f, 140f)
        val second = Rect(5f, 120f, 95f, 160f)

        registry.updatePerch("toolbar", first, PetRouteScope(setOf("terminal")))
        registry.updatePerch("toolbar", second, PetRouteScope(setOf("terminal")))
        registry.updateObstacle("pill", first, PetRouteScope(setOf("terminal")))
        registry.updateVisitTarget("reply", first, PetRouteScope(setOf("terminal")))
        registry.updateVisitTarget("reply", second, PetRouteScope(setOf("terminal")))

        val updated = registry.snapshot("terminal")
        assertEquals(PetObstacle(5f, 120f, 95f, 160f), updated.perches.single().bounds)
        assertEquals(second, registry.walkRegions.getValue("toolbar"))
        assertEquals(PetObstacle(5f, 120f, 95f, 160f), updated.visitTargets.single().bounds)

        registry.removePerch("toolbar")
        registry.removeObstacle("pill")
        registry.removeVisitTarget("reply")
        assertTrue(registry.snapshot("terminal").perches.isEmpty())
        assertTrue(registry.snapshot("terminal").obstacles.isEmpty())
        assertTrue(registry.snapshot("terminal").visitTargets.isEmpty())
        assertFalse(registry.walkRegions.containsKey("toolbar"))
    }

    @Test
    fun `legacy walk region API remains a global perch`() {
        val registry = PetSafeAreaRegistry()
        registry.updateWalkRegion("composer", Rect(0f, 200f, 100f, 240f))

        assertEquals(listOf("composer"), registry.snapshot("chat").perches.map { it.key })
        assertEquals(listOf("composer"), registry.snapshot("settings").perches.map { it.key })

        registry.removeWalkRegion("composer")
        assertTrue(registry.snapshot("chat").perches.isEmpty())
    }
}

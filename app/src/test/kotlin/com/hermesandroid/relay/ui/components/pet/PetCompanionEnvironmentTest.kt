package com.hermesandroid.relay.ui.components.pet

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetCompanionEnvironmentTest {

    @Test
    fun `settled history without a streaming edge creates no visit`() {
        val state = reducePetVisitRequestState(
            state = PetVisitRequestState(),
            isStreaming = false,
            assistantUiKey = "history-answer",
            nowElapsedMs = 1_000L,
        )

        assertFalse(state.streamArmed)
        assertNull(state.pending)
    }

    @Test
    fun `stream falling edge creates one delayed stable-key request`() {
        val armed = reducePetVisitRequestState(
            state = PetVisitRequestState(),
            isStreaming = true,
            assistantUiKey = null,
            nowElapsedMs = 1_000L,
        )
        val afterDelta = reducePetVisitRequestState(
            state = armed,
            isStreaming = true,
            assistantUiKey = "answer-7",
            nowElapsedMs = 1_100L,
        )
        val settled = reducePetVisitRequestState(
            state = afterDelta,
            isStreaming = false,
            assistantUiKey = "answer-7",
            nowElapsedMs = 2_000L,
        )
        val duplicateIdleObservation = reducePetVisitRequestState(
            state = settled,
            isStreaming = false,
            assistantUiKey = "answer-7",
            nowElapsedMs = 2_100L,
        )

        val request = requireNotNull(settled.pending)
        assertEquals("answer-7", request.assistantUiKey)
        assertEquals("chat-message:answer-7", request.targetKey)
        assertEquals(
            2_000L + deterministicPetVisitCooldownMs("answer-7"),
            request.notBeforeElapsedMs,
        )
        assertEquals(request, duplicateIdleObservation.pending)
        assertFalse(duplicateIdleObservation.streamArmed)
    }

    @Test
    fun `cooldown is deterministic bounded and exposes readiness window`() {
        val cooldown = deterministicPetVisitCooldownMs("stable-answer")
        assertEquals(cooldown, deterministicPetVisitCooldownMs("stable-answer"))
        assertTrue(cooldown in 12_000L..20_000L)

        val request = PetVisitRequest(
            assistantUiKey = "stable-answer",
            targetKey = "chat-message:stable-answer",
            notBeforeElapsedMs = 50_000L,
            expiresAtElapsedMs = 70_000L,
        )
        assertEquals(PetVisitReadiness.CoolingDown, request.readinessAt(49_999L))
        assertEquals(PetVisitReadiness.Ready, request.readinessAt(50_000L))
        assertEquals(PetVisitReadiness.Ready, request.readinessAt(70_000L))
        assertEquals(PetVisitReadiness.Expired, request.readinessAt(70_001L))
    }

    @Test
    fun `completion race keeps stream armed until assistant row settles`() {
        val armed = reducePetVisitRequestState(
            state = PetVisitRequestState(),
            isStreaming = true,
            assistantUiKey = null,
            nowElapsedMs = 1_000L,
        )
        val transportSettledFirst = reducePetVisitRequestState(
            state = armed,
            isStreaming = false,
            assistantUiKey = null,
            completionSettled = false,
            nowElapsedMs = 1_100L,
        )
        val messageSettled = reducePetVisitRequestState(
            state = transportSettledFirst,
            isStreaming = false,
            assistantUiKey = "answer-after-race",
            completionSettled = true,
            nowElapsedMs = 1_200L,
        )

        assertTrue(transportSettledFirst.streamArmed)
        assertEquals("answer-after-race", requireNotNull(messageSettled.pending).assistantUiKey)
    }

    @Test
    fun `temperament response delay overrides legacy deterministic cooldown`() {
        val armed = reducePetVisitRequestState(
            state = PetVisitRequestState(),
            isStreaming = true,
            assistantUiKey = null,
            nowElapsedMs = 1_000L,
            responseVisitDelayMs = 750L,
        )
        val settled = reducePetVisitRequestState(
            state = armed,
            isStreaming = false,
            assistantUiKey = "answer-playful",
            nowElapsedMs = 2_000L,
            responseVisitDelayMs = 750L,
        )

        assertEquals(2_750L, requireNotNull(settled.pending).notBeforeElapsedMs)
    }

    @Test
    fun `new completion replaces pending visit and expired request is pruned`() {
        fun complete(state: PetVisitRequestState, key: String, now: Long): PetVisitRequestState {
            val armed = reducePetVisitRequestState(state, true, null, now - 1L)
            return reducePetVisitRequestState(armed, false, key, now)
        }

        val first = complete(PetVisitRequestState(), "first", 1_000L)
        val latest = complete(first, "latest", 2_000L)
        assertEquals("latest", latest.pending?.assistantUiKey)

        val expiredAt = requireNotNull(latest.pending).expiresAtElapsedMs + 1L
        val pruned = reducePetVisitRequestState(
            state = latest,
            isStreaming = false,
            assistantUiKey = "latest",
            nowElapsedMs = expiredAt,
        )
        assertNull(pruned.pending)
    }

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

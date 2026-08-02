package com.hermesandroid.relay.ui.components.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PetRoamingStateTest {
    private val home = PetPlacement(PetLogicalEdge.Start, 0.8f)

    @Test
    fun `docked pet can roam drag and persist a sanitized drop`() {
        val docked = PetRoamingState.Docked(home)
        val roaming = reducePetRoamingState(
            docked,
            PetRoamingEvent.StartRoaming(PetPoint(10f, 40f)),
        )
        assertEquals(PetRoamingState.Roaming(home, PetPoint(10f, 40f)), roaming)

        val dragging = reducePetRoamingState(
            roaming,
            PetRoamingEvent.BeginDrag(PetPoint(20f, 50f)),
        )
        assertEquals(PetRoamingState.Dragging(home, PetPoint(20f, 50f)), dragging)

        val moved = reducePetRoamingState(dragging, PetRoamingEvent.DragTo(PetPoint(30f, 60f)))
        assertEquals(PetRoamingState.Dragging(home, PetPoint(30f, 60f)), moved)

        val dropped = reducePetRoamingState(
            moved,
            PetRoamingEvent.Drop(PetPlacement(PetLogicalEdge.End, 4f)),
        )
        assertEquals(PetRoamingState.Docked(PetPlacement(PetLogicalEdge.End, 1f)), dropped)
    }

    @Test
    fun `suspension discards transient movement and resume returns home`() {
        val dragging = PetRoamingState.Dragging(home, PetPoint(90f, 20f))
        val suspended = reducePetRoamingState(
            dragging,
            PetRoamingEvent.Suspend(PetSuspensionReason.Modal),
        )
        assertEquals(PetRoamingState.Suspended(home, PetSuspensionReason.Modal), suspended)
        assertEquals(PetRoamingState.Docked(home), reducePetRoamingState(suspended, PetRoamingEvent.Resume))
    }

    @Test
    fun `dock finishes roaming without changing persisted home`() {
        val roaming = PetRoamingState.Roaming(home, PetPoint(10f, 20f))
        assertEquals(PetRoamingState.Docked(home), reducePetRoamingState(roaming, PetRoamingEvent.Dock))
    }

    @Test
    fun `unsafe transitions are stable no ops`() {
        val suspended = PetRoamingState.Suspended(home, PetSuspensionReason.Voice)
        assertSame(
            suspended,
            reducePetRoamingState(
                suspended,
                PetRoamingEvent.BeginDrag(PetPoint(10f, 10f)),
            ),
        )

        val docked = PetRoamingState.Docked(home)
        assertSame(docked, reducePetRoamingState(docked, PetRoamingEvent.DragTo(PetPoint(1f, 2f))))
        assertSame(docked, reducePetRoamingState(docked, PetRoamingEvent.Drop(home)))
        assertSame(docked, reducePetRoamingState(docked, PetRoamingEvent.Resume))
    }
}

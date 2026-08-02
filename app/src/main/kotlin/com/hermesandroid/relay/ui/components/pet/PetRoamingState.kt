package com.hermesandroid.relay.ui.components.pet

enum class PetSuspensionReason {
    Keyboard,
    Modal,
    Voice,
    Startup,
    Accessibility,
    NoSafePosition,
}

sealed interface PetRoamingState {
    val home: PetPlacement

    data class Docked(override val home: PetPlacement) : PetRoamingState

    data class Roaming(
        override val home: PetPlacement,
        val waypoint: PetPoint,
    ) : PetRoamingState

    data class Dragging(
        override val home: PetPlacement,
        val pointer: PetPoint,
    ) : PetRoamingState

    data class Suspended(
        override val home: PetPlacement,
        val reason: PetSuspensionReason,
    ) : PetRoamingState
}

sealed interface PetRoamingEvent {
    data class StartRoaming(val waypoint: PetPoint) : PetRoamingEvent
    data class BeginDrag(val pointer: PetPoint) : PetRoamingEvent
    data class DragTo(val pointer: PetPoint) : PetRoamingEvent
    data class Drop(val placement: PetPlacement) : PetRoamingEvent
    data object Dock : PetRoamingEvent
    data class Suspend(val reason: PetSuspensionReason) : PetRoamingEvent
    data object Resume : PetRoamingEvent
}

/** Pure state reducer. Unrelated or unsafe transitions are stable no-ops. */
fun reducePetRoamingState(
    state: PetRoamingState,
    event: PetRoamingEvent,
): PetRoamingState = when (event) {
    is PetRoamingEvent.StartRoaming -> when (state) {
        is PetRoamingState.Docked,
        is PetRoamingState.Roaming -> PetRoamingState.Roaming(state.home, event.waypoint)
        is PetRoamingState.Dragging,
        is PetRoamingState.Suspended -> state
    }

    is PetRoamingEvent.BeginDrag -> when (state) {
        is PetRoamingState.Docked,
        is PetRoamingState.Roaming -> PetRoamingState.Dragging(state.home, event.pointer)
        is PetRoamingState.Dragging,
        is PetRoamingState.Suspended -> state
    }

    is PetRoamingEvent.DragTo -> when (state) {
        is PetRoamingState.Dragging -> state.copy(pointer = event.pointer)
        else -> state
    }

    is PetRoamingEvent.Drop -> when (state) {
        is PetRoamingState.Dragging -> PetRoamingState.Docked(event.placement.sanitized())
        else -> state
    }

    PetRoamingEvent.Dock -> when (state) {
        is PetRoamingState.Roaming -> PetRoamingState.Docked(state.home)
        else -> state
    }

    is PetRoamingEvent.Suspend -> PetRoamingState.Suspended(state.home, event.reason)

    PetRoamingEvent.Resume -> when (state) {
        is PetRoamingState.Suspended -> PetRoamingState.Docked(state.home)
        else -> state
    }
}

package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionActivityRegistryTest {
    private val owner = owner("connection-a", "Default", "session-a")
    private val scope = SessionActivityScope.of("connection-a", "default")

    @Test
    fun `directory owner is checking until status is unavailable or confirms idle`() {
        val checking = SessionActivityRegistry().reduce(
            SessionActivityUpdate.ObserveOwner(owner, generation = 1, observedAtMillis = 1),
        )
        assertEquals(SessionActivityPhase.Idle, checking.record(owner)?.phase())
        assertEquals(SessionActivityState.Checking, checking.record(owner)?.presentationState())

        val unavailable = checking.reduce(
            SessionActivityUpdate.StatusUnavailable(scope, generation = 1, observedAtMillis = 2),
        )
        assertEquals(SessionActivityState.Unavailable, unavailable.record(owner)?.presentationState())

        val confirmedIdle = checking.reduce(activeList(scope, generation = 1))
        assertEquals(SessionActivityPhase.Idle, confirmedIdle.record(owner)?.phase())
        assertEquals(SessionActivityFreshness.Confirmed, confirmedIdle.record(owner)?.freshness)
        assertNull(confirmedIdle.record(owner)?.presentationState())
    }

    @Test
    fun `directory observation cannot downgrade confirmed live evidence`() {
        val state = SessionActivityRegistry()
            .reduce(live(owner, "runtime-a", SessionLiveStatus.Working, generation = 1))
            .reduce(SessionActivityUpdate.ObserveOwner(owner, generation = 1, observedAtMillis = 20))

        assertEquals(SessionActivityPhase.Working, state.record(owner)?.phase())
        assertEquals(SessionActivityFreshness.Confirmed, state.record(owner)?.freshness)
        assertEquals(SessionActivityEvidenceSource.SessionEvent, state.record(owner)?.evidence?.source)
    }

    @Test
    fun `owner normalizes profile without collapsing connection ownership`() {
        assertEquals(owner, owner("connection-a", " DEFAULT ", "session-a"))
        val otherConnection = owner("connection-b", "default", "session-a")
        assertEquals(2, setOf(owner, otherConnection).size)
    }

    @Test
    fun `exact pending input outranks live working and answer restores it`() {
        var state = SessionActivityRegistry()
            .reduce(live(owner, "runtime-a", SessionLiveStatus.Working, generation = 1))
            .reduce(openInput(owner, "request-a", generation = 1))

        assertEquals(SessionActivityPhase.NeedsInput, state.record(owner)?.phase(nowMillis = 10))

        state = state.reduce(closeInput(owner, "request-a", generation = 1))

        assertEquals(SessionActivityPhase.Working, state.record(owner)?.phase(nowMillis = 10))
    }

    @Test
    fun `expired pending input no longer overrides live state`() {
        val state = SessionActivityRegistry()
            .reduce(live(owner, "runtime-a", SessionLiveStatus.Working, generation = 1))
            .reduce(openInput(owner, "request-a", generation = 1, expiresAt = 50))
            .reduce(SessionActivityUpdate.Tick(nowMillis = 50))

        assertEquals(SessionActivityPhase.Working, state.record(owner)?.phase(nowMillis = 50))
    }

    @Test
    fun `checkpoint is revalidating until successful snapshot settles it`() {
        var state = SessionActivityRegistry().reduce(
            SessionActivityUpdate.RestoreCheckpoint(
                owner = owner,
                runtimeId = "runtime-a",
                phase = SessionActivityPhase.Working,
                generation = 1,
                observedAtMillis = 1,
            ),
        )
        assertEquals(SessionActivityFreshness.Revalidating, state.record(owner)?.freshness)

        state = state.reduce(activeList(scope, generation = 1))

        assertEquals(SessionActivityPhase.Idle, state.record(owner)?.phase())
        assertEquals(SessionActivityFreshness.Confirmed, state.record(owner)?.freshness)
    }

    @Test
    fun `successful snapshot absence settles only the same profile`() {
        val otherProfile = owner("connection-a", "work", "session-a")
        var state = SessionActivityRegistry()
            .reduce(live(owner, "runtime-a", SessionLiveStatus.Working, generation = 1))
            .reduce(live(otherProfile, "runtime-a", SessionLiveStatus.Working, generation = 1))

        state = state.reduce(activeList(scope, generation = 1))

        assertEquals(SessionActivityPhase.Idle, state.record(owner)?.phase())
        assertEquals(SessionActivityPhase.Working, state.record(otherProfile)?.phase())
    }

    @Test
    fun `same ids cannot alias across profiles or connections`() {
        val profileB = owner("connection-a", "work", "session-a")
        val connectionB = owner("connection-b", "default", "session-a")
        var state = SessionActivityRegistry()
        listOf(owner, profileB, connectionB).forEach {
            state = state.reduce(live(it, "runtime-shared", SessionLiveStatus.Working, generation = 2))
        }

        assertEquals(owner, state.ownerForRuntime(scope, "runtime-shared"))
        assertEquals(
            profileB,
            state.ownerForRuntime(SessionActivityScope.of("connection-a", "work"), "runtime-shared"),
        )
        assertEquals(
            connectionB,
            state.ownerForRuntime(SessionActivityScope.of("connection-b", "default"), "runtime-shared"),
        )
    }

    @Test
    fun `unscoped active row cannot mark duplicate stored ids as working`() {
        val profileB = owner("connection-a", "work", "session-a")
        var state = SessionActivityRegistry()
            .reduce(live(owner, "old-a", SessionLiveStatus.Working, generation = 1))
            .reduce(live(profileB, "old-b", SessionLiveStatus.Working, generation = 1))

        state = state.reduce(
            activeList(
                scope,
                1,
                false,
                SessionLiveRuntime(
                    owner = null,
                    runtimeId = "ambiguous-runtime",
                    status = SessionLiveStatus.Working,
                ),
            ),
        )

        assertEquals(SessionActivityPhase.Working, state.record(owner)?.phase())
        assertEquals(SessionActivityPhase.Working, state.record(profileB)?.phase())
        assertNull(state.ownerForRuntime(scope, "ambiguous-runtime"))
    }

    @Test
    fun `partial snapshot applies resolved row without settling absent owner`() {
        val absentOwner = owner("connection-a", "default", "session-b")
        var state = SessionActivityRegistry()
            .reduce(live(absentOwner, "runtime-b", SessionLiveStatus.Working, generation = 1))

        state = state.reduce(
            activeList(
                scope,
                1,
                false,
                SessionLiveRuntime(owner, "runtime-a", SessionLiveStatus.Working),
                SessionLiveRuntime(null, "ambiguous-runtime", SessionLiveStatus.Working),
            ),
        )

        assertEquals(SessionActivityPhase.Working, state.record(owner)?.phase())
        assertEquals(SessionActivityPhase.Working, state.record(absentOwner)?.phase())
        assertEquals(owner, state.ownerForRuntime(scope, "runtime-a"))
        assertNull(state.ownerForRuntime(scope, "ambiguous-runtime"))
    }

    @Test
    fun `new generation rejects late terminal event and invalidates old alias`() {
        var state = SessionActivityRegistry()
            .reduce(live(owner, "runtime-old", SessionLiveStatus.Working, generation = 3))
            .reduce(SessionActivityUpdate.BeginGeneration(scope, generation = 4, observedAtMillis = 20))

        assertEquals(SessionActivityFreshness.Revalidating, state.record(owner)?.freshness)
        assertNull(state.ownerForRuntime(scope, "runtime-old"))

        state = state.reduce(live(owner, "runtime-old", SessionLiveStatus.Idle, generation = 3))

        assertEquals(SessionActivityPhase.Working, state.record(owner)?.phase())
        assertEquals(SessionActivityFreshness.Revalidating, state.record(owner)?.freshness)
    }

    @Test
    fun `failed or unsupported status refresh is unavailable rather than idle`() {
        val state = SessionActivityRegistry()
            .reduce(
                SessionActivityUpdate.LiveState(
                    owner = owner,
                    runtimeId = "runtime-a",
                    status = SessionLiveStatus.Working,
                    source = SessionActivityEvidenceSource.ActiveList,
                    generation = 1,
                    observedAtMillis = 10,
                ),
            )
            .reduce(
                SessionActivityUpdate.StatusUnavailable(
                    scope = scope,
                    generation = 1,
                    observedAtMillis = 20,
                ),
            )

        assertEquals(SessionActivityPhase.Working, state.record(owner)?.phase())
        assertEquals(SessionActivityFreshness.Unavailable, state.record(owner)?.freshness)
        assertEquals(SessionActivityState.Unavailable, state.record(owner)?.presentationState())
    }

    @Test
    fun `active-list failure does not override exact live session event`() {
        val state = SessionActivityRegistry()
            .reduce(live(owner, "runtime-a", SessionLiveStatus.Working, generation = 1))
            .reduce(
                SessionActivityUpdate.StatusUnavailable(
                    scope = scope,
                    generation = 1,
                    observedAtMillis = 20,
                ),
            )

        assertEquals(SessionActivityFreshness.Confirmed, state.record(owner)?.freshness)
        assertEquals(SessionActivityState.Working, state.record(owner)?.presentationState())
    }

    @Test
    fun `presentation keeps starting background and revalidation distinct from working`() {
        val starting = SessionActivityRegistry().reduce(
            SessionActivityUpdate.LocalSend(owner, generation = 1, observedAtMillis = 1),
        )
        assertEquals(SessionActivityState.Starting, starting.record(owner)?.presentationState())

        val background = starting
            .reduce(process(owner, "process-a", running = true, generation = 1))
            .reduce(live(owner, "runtime-a", SessionLiveStatus.Idle, generation = 1))
        assertEquals(SessionActivityState.BackgroundWork, background.record(owner)?.presentationState())

        val checking = starting.reduce(
            SessionActivityUpdate.BeginGeneration(scope, generation = 2, observedAtMillis = 2),
        )
        assertEquals(SessionActivityState.Checking, checking.record(owner)?.presentationState())
    }

    @Test
    fun `terminal turn with running process projects background work separately`() {
        var state = SessionActivityRegistry()
            .reduce(live(owner, "runtime-a", SessionLiveStatus.Working, generation = 1))
            .reduce(process(owner, "process-a", running = true, generation = 1))
            .reduce(terminal(owner, "runtime-a", generation = 1))

        assertEquals(SessionActivityPhase.BackgroundWork, state.record(owner)?.phase())

        state = state.reduce(process(owner, "process-a", running = false, generation = 1))
        assertEquals(SessionActivityPhase.Idle, state.record(owner)?.phase())
    }

    @Test
    fun `terminal settles pending input and removes its runtime alias`() {
        var state = SessionActivityRegistry()
            .reduce(live(owner, "runtime-a", SessionLiveStatus.Working, generation = 1))
            .reduce(openInput(owner, "request-a", generation = 1))

        state = state.reduce(terminal(owner, "runtime-a", generation = 1))

        assertEquals(SessionActivityPhase.Idle, state.record(owner)?.phase())
        assertNull(state.ownerForRuntime(scope, "runtime-a"))
    }

    @Test
    fun `authoritative live state is not overwritten by restored checkpoint`() {
        val state = SessionActivityRegistry()
            .reduce(live(owner, "runtime-a", SessionLiveStatus.Idle, generation = 1))
            .reduce(
                SessionActivityUpdate.RestoreCheckpoint(
                    owner = owner,
                    runtimeId = "runtime-a",
                    phase = SessionActivityPhase.Working,
                    generation = 1,
                    observedAtMillis = 30,
                ),
            )

        assertEquals(SessionActivityPhase.Idle, state.record(owner)?.phase())
        assertEquals(SessionActivityEvidenceSource.SessionEvent, state.record(owner)?.evidence?.source)
    }

    @Test
    fun `restored needs-input checkpoint stays checking until live confirmation`() {
        val state = SessionActivityRegistry().reduce(
            SessionActivityUpdate.RestoreCheckpoint(
                owner = owner,
                runtimeId = "runtime-a",
                phase = SessionActivityPhase.NeedsInput,
                generation = 1,
                observedAtMillis = 10,
            ),
        )

        assertEquals(SessionActivityState.Checking, state.record(owner)?.presentationState())
    }

    @Test
    fun `synthetic checkpoint input does not confirm restored working state`() {
        var state = SessionActivityRegistry()
            .reduce(
                SessionActivityUpdate.RestoreCheckpoint(
                    owner = owner,
                    runtimeId = "runtime-a",
                    phase = SessionActivityPhase.Working,
                    generation = 1,
                    observedAtMillis = 10,
                ),
            )
            .reduce(
                SessionActivityUpdate.PendingInputOpened(
                    owner = owner,
                    requestId = "checkpoint:session-a",
                    confirmed = false,
                    generation = 1,
                    observedAtMillis = 11,
                ),
            )

        assertEquals(SessionActivityState.Checking, state.record(owner)?.presentationState())

        state = state.reduce(
            SessionActivityUpdate.PendingInputOpened(
                owner = owner,
                requestId = "checkpoint:session-a",
                confirmed = true,
                generation = 1,
                observedAtMillis = 12,
            ),
        )
        assertEquals(SessionActivityState.NeedsInput, state.record(owner)?.presentationState())
    }

    @Test
    fun `authoritative idle active-list row clears stale pending input`() {
        val state = SessionActivityRegistry()
            .reduce(live(owner, "runtime-a", SessionLiveStatus.Working, generation = 1))
            .reduce(openInput(owner, "request-a", generation = 1))
            .reduce(
                activeList(
                    scope,
                    generation = 1,
                    runtimes = arrayOf(SessionLiveRuntime(owner, "runtime-a", SessionLiveStatus.Idle)),
                ),
            )

        assertEquals(SessionActivityPhase.Idle, state.record(owner)?.phase())
        assertTrue(state.record(owner)?.pendingInputs.orEmpty().isEmpty())
        assertNull(state.record(owner)?.presentationState())
    }

    @Test
    fun `runtime-only update requires alias in current scoped generation`() {
        var state = SessionActivityRegistry()
            .reduce(live(owner, "runtime-a", SessionLiveStatus.Working, generation = 1))
        state = state.reduce(
            SessionActivityUpdate.RuntimeState(
                scope = scope,
                runtimeId = "runtime-a",
                status = SessionLiveStatus.Waiting,
                generation = 1,
                observedAtMillis = 20,
            ),
        )
        assertEquals(SessionActivityPhase.NeedsInput, state.record(owner)?.phase())

        state = state.reduce(SessionActivityUpdate.BeginGeneration(scope, 2, 30))
            .reduce(
                SessionActivityUpdate.RuntimeState(
                    scope = scope,
                    runtimeId = "runtime-a",
                    status = SessionLiveStatus.Idle,
                    generation = 2,
                    observedAtMillis = 40,
                ),
            )
        assertEquals(SessionActivityPhase.NeedsInput, state.record(owner)?.phase())
        assertEquals(SessionActivityFreshness.Revalidating, state.record(owner)?.freshness)
    }

    private fun owner(connection: String, profile: String, session: String) =
        SessionActivityOwner.of(connection, profile, session)

    private fun live(
        owner: SessionActivityOwner,
        runtime: String,
        status: SessionLiveStatus,
        generation: Long,
    ) = SessionActivityUpdate.LiveState(
        owner = owner,
        runtimeId = runtime,
        status = status,
        generation = generation,
        observedAtMillis = 10,
    )

    private fun activeList(
        scope: SessionActivityScope,
        generation: Long,
        complete: Boolean = true,
        vararg runtimes: SessionLiveRuntime,
    ) = SessionActivityUpdate.ActiveList(
        scope = scope,
        runtimes = runtimes.toList(),
        isCompleteForScope = complete,
        generation = generation,
        observedAtMillis = 20,
    )

    private fun openInput(
        owner: SessionActivityOwner,
        request: String,
        generation: Long,
        expiresAt: Long? = null,
    ) = SessionActivityUpdate.PendingInputOpened(
        owner = owner,
        requestId = request,
        expiresAtMillis = expiresAt,
        generation = generation,
        observedAtMillis = 10,
    )

    private fun closeInput(owner: SessionActivityOwner, request: String, generation: Long) =
        SessionActivityUpdate.PendingInputClosed(
            owner = owner,
            requestId = request,
            generation = generation,
            observedAtMillis = 20,
        )

    private fun process(owner: SessionActivityOwner, id: String, running: Boolean, generation: Long) =
        SessionActivityUpdate.ProcessState(
            owner = owner,
            processId = id,
            running = running,
            generation = generation,
            observedAtMillis = 10,
        )

    private fun terminal(owner: SessionActivityOwner, runtime: String, generation: Long) =
        SessionActivityUpdate.Terminal(
            owner = owner,
            runtimeId = runtime,
            generation = generation,
            observedAtMillis = 20,
        )
}

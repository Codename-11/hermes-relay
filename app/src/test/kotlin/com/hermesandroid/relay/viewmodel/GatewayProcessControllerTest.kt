package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.network.upstream.GatewayProcess
import com.hermesandroid.relay.network.upstream.GatewayProcessCapability
import com.hermesandroid.relay.network.upstream.GatewayProcessEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayProcessControllerTest {

    @Test
    fun pollsEveryFiveSecondsOnlyWhileAProcessIsRunning() = runTest {
        val source = FakeProcessSource()
        source.snapshot = listOf(process(id = "p1", status = "running"))
        val controller = GatewayProcessController(this)

        controller.bind(source, "chat-a")
        controller.sessionReady("chat-a")
        runCurrent()

        assertEquals(1, source.listCalls)
        assertEquals(listOf("p1"), controller.processes.value.map(GatewayProcess::id))

        advanceTimeBy(4_999L)
        runCurrent()
        assertEquals(1, source.listCalls)

        source.snapshot = listOf(process(id = "p1", status = "exited", exitCode = 0))
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, source.listCalls)
        assertFalse(controller.processes.value.single().isRunning)

        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(2, source.listCalls)
        controller.close()
    }

    @Test
    fun staleListResultCannotOverwriteNewSession() = runTest {
        val oldResult = CompletableDeferred<Result<List<GatewayProcess>>>()
        val source = FakeProcessSource().apply {
            listHandler = { call ->
                if (call == 1) {
                    // Model a transport that finishes an RPC even after the
                    // controller cancels the old refresh job.
                    withContext(NonCancellable) { oldResult.await() }
                } else {
                    Result.success(listOf(process(id = "new", command = "new session")))
                }
            }
        }
        val controller = GatewayProcessController(this)

        controller.bind(source, "chat-old")
        controller.sessionReady("chat-old")
        runCurrent()
        assertEquals(1, source.listCalls)

        controller.selectSession("chat-new")
        controller.sessionReady("chat-new")
        runCurrent()
        assertEquals(listOf("new"), controller.processes.value.map(GatewayProcess::id))

        oldResult.complete(Result.success(listOf(process(id = "old", command = "old session"))))
        runCurrent()

        assertEquals(listOf("new"), controller.processes.value.map(GatewayProcess::id))
        controller.close()
    }

    @Test
    fun messageCompleteFallbackRefreshesAndLiveOutputExtendsTheRecoverableTail() = runTest {
        val source = FakeProcessSource().apply {
            snapshot = listOf(
                process(id = "p1", status = "running", outputTail = "before\n"),
            )
        }
        val controller = GatewayProcessController(this, outputTailLimit = 12)
        controller.bind(source, "chat-a")
        controller.sessionReady("chat-a")
        runCurrent()

        source.emit(GatewayProcessEvent.Output("p1", "after-output"))
        runCurrent()
        assertEquals("after-output", controller.processes.value.single().outputTail)

        source.snapshot = listOf(
            process(id = "p1", status = "exited", exitCode = 7, outputTail = "final"),
        )
        source.emit(
            GatewayProcessEvent.Invalidated(GatewayProcessEvent.Trigger.MESSAGE_COMPLETE),
        )
        runCurrent()

        assertEquals(2, source.listCalls)
        assertEquals(7, controller.processes.value.single().exitCode)
        assertEquals("final", controller.processes.value.single().outputTail)
        controller.close()
    }

    @Test
    fun messageCompleteDiscoversRunningProcessAndStartsPolling() = runTest {
        val source = FakeProcessSource()
        val controller = GatewayProcessController(this)
        controller.bind(source, "chat-a")
        controller.sessionReady("chat-a")
        runCurrent()

        assertEquals(1, source.listCalls)
        assertTrue(controller.processes.value.isEmpty())

        source.snapshot = listOf(process(id = "p1", status = "running"))
        source.emit(
            GatewayProcessEvent.Invalidated(GatewayProcessEvent.Trigger.MESSAGE_COMPLETE),
        )
        runCurrent()

        assertEquals(2, source.listCalls)
        assertEquals(listOf("p1"), controller.processes.value.map(GatewayProcess::id))

        source.snapshot = listOf(process(id = "p1", status = "exited", exitCode = 0))
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(3, source.listCalls)
        assertFalse(controller.processes.value.single().isRunning)

        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(3, source.listCalls)
        controller.close()
    }

    @Test
    fun finishedRowsStayUntilDismissedAndReappearForANewIdentity() = runTest {
        val source = FakeProcessSource().apply {
            snapshot = listOf(
                process(
                    id = "p1",
                    status = "exited",
                    exitCode = 0,
                    startedAt = "first",
                ),
            )
        }
        val controller = GatewayProcessController(this)
        controller.bind(source, "chat-a")
        controller.sessionReady("chat-a")
        runCurrent()

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, controller.processes.value.size)

        controller.dismiss("p1")
        assertTrue(controller.processes.value.isEmpty())

        source.snapshot = listOf(
            process(
                id = "p1",
                command = "replacement",
                status = "running",
                startedAt = "second",
            ),
        )
        controller.refresh(showLoading = false)
        runCurrent()

        assertEquals("replacement", controller.processes.value.single().command)
        controller.close()
    }

    @Test
    fun stopExposesInFlightIdentityThenRefreshesAuthoritativeState() = runTest {
        val killResult = CompletableDeferred<Result<Unit>>()
        val source = FakeProcessSource().apply {
            snapshot = listOf(process(id = "p1", status = "running"))
            killHandler = { killResult.await() }
        }
        val controller = GatewayProcessController(this)
        controller.bind(source, "chat-a")
        controller.sessionReady("chat-a")
        runCurrent()

        controller.stop("p1")
        runCurrent()
        assertEquals(setOf("p1"), controller.stoppingProcessIds.value)
        assertEquals(listOf("p1"), source.killCalls)

        source.snapshot = listOf(process(id = "p1", status = "exited", exitCode = 143))
        killResult.complete(Result.success(Unit))
        runCurrent()

        assertTrue(controller.stoppingProcessIds.value.isEmpty())
        assertEquals(2, source.listCalls)
        assertEquals(143, controller.processes.value.single().exitCode)
        controller.close()
    }

    @Test
    fun unsupportedCapabilityClearsSnapshotAndStopsPolling() = runTest {
        val source = FakeProcessSource().apply {
            snapshot = listOf(process(id = "p1", status = "running"))
        }
        val controller = GatewayProcessController(this)
        controller.bind(source, "chat-a")
        controller.sessionReady("chat-a")
        runCurrent()
        assertTrue(controller.processes.value.isNotEmpty())

        source.capabilityState.value = GatewayProcessCapability.Unsupported
        runCurrent()
        assertTrue(controller.processes.value.isEmpty())
        assertEquals(GatewayProcessCapability.Unsupported, controller.capability.value)

        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1, source.listCalls)
        controller.close()
    }

    @Test
    fun sameSessionReadyAfterReconnectRelistsEvenWithoutAnActivePoller() = runTest {
        val source = FakeProcessSource()
        val controller = GatewayProcessController(this)
        controller.bind(source, "chat-a")
        controller.sessionReady("chat-a")
        runCurrent()

        assertEquals(1, source.listCalls)
        assertTrue(controller.processes.value.isEmpty())

        source.snapshot = listOf(
            process(id = "completed-offline", status = "exited", exitCode = 0),
        )
        controller.sessionReady("chat-a")
        runCurrent()

        assertEquals(2, source.listCalls)
        assertEquals("completed-offline", controller.processes.value.single().id)
        controller.close()
    }

    @Test
    fun backgroundDisablesPollingUntilForegroundSessionReadyRefresh() = runTest {
        val source = FakeProcessSource().apply {
            snapshot = listOf(process(id = "p1", status = "running"))
        }
        val controller = GatewayProcessController(this)
        controller.bind(source, "chat-a")
        controller.sessionReady("chat-a")
        runCurrent()
        assertEquals(1, source.listCalls)

        source.pollingAllowed = false
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, source.listCalls)

        source.pollingAllowed = true
        controller.sessionReady("chat-a")
        runCurrent()
        assertEquals(2, source.listCalls)
        controller.close()
    }

    @Test
    fun profileScopeChangeRejectsSameSessionIdStaleResult() = runTest {
        val oldResult = CompletableDeferred<Result<List<GatewayProcess>>>()
        val source = FakeProcessSource().apply {
            listHandler = { call ->
                if (call == 1) {
                    withContext(NonCancellable) { oldResult.await() }
                } else {
                    Result.success(listOf(process(id = "new-profile")))
                }
            }
        }
        val controller = GatewayProcessController(this)
        controller.bind(source, "same-id", scopeKey = "profile-a")
        controller.sessionReady("same-id")
        runCurrent()

        controller.selectSession("same-id", scopeKey = "profile-b")
        controller.sessionReady("same-id")
        runCurrent()
        oldResult.complete(Result.success(listOf(process(id = "old-profile"))))
        runCurrent()

        assertEquals(listOf("new-profile"), controller.processes.value.map(GatewayProcess::id))
        controller.close()
    }

    private class FakeProcessSource : GatewayProcessSource {
        val capabilityState = MutableStateFlow(GatewayProcessCapability.Unknown)
        override val capability = capabilityState

        var snapshot: List<GatewayProcess> = emptyList()
        var listCalls = 0
        var listHandler: suspend (Int) -> Result<List<GatewayProcess>> = { Result.success(snapshot) }
        var killHandler: suspend (String) -> Result<Unit> = { Result.success(Unit) }
        val killCalls = mutableListOf<String>()
        var pollingAllowed = true
        private var listener: ((GatewayProcessEvent) -> Unit)? = null

        override suspend fun listProcesses(): Result<List<GatewayProcess>> {
            listCalls += 1
            return listHandler(listCalls)
        }

        override suspend fun killProcess(processId: String): Result<Unit> {
            killCalls += processId
            return killHandler(processId)
        }

        override fun setEventListener(listener: ((GatewayProcessEvent) -> Unit)?) {
            this.listener = listener
        }

        override fun isPollingAllowed(): Boolean = pollingAllowed

        fun emit(event: GatewayProcessEvent) {
            listener?.invoke(event)
        }
    }

    private fun process(
        id: String,
        command: String = "sleep 60",
        status: String = "exited",
        exitCode: Int? = null,
        outputTail: String? = null,
        startedAt: String? = "start",
    ) = GatewayProcess(
        id = id,
        command = command,
        status = status,
        exitCode = exitCode,
        outputTail = outputTail,
        startedAt = startedAt,
    )
}

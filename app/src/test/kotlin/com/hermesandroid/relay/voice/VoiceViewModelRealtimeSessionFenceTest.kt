package com.hermesandroid.relay.voice

import android.app.Application
import android.util.Log
import com.hermesandroid.relay.network.relay.RealtimeAgentSessionControl
import com.hermesandroid.relay.network.relay.VoiceHandoffEvent
import com.hermesandroid.relay.viewmodel.BackgroundRunPhase
import com.hermesandroid.relay.viewmodel.BackgroundRunState
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelRealtimeSessionFenceTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private fun awaitBlocked(thread: AtomicReference<Thread?>): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadlineNanos) {
            if (thread.get()?.state == Thread.State.BLOCKED) return true
            Thread.sleep(5L)
        }
        return false
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @Test
    fun staleRealtimeCallbackCannotRepopulateNewVoiceSession() = runTest {
        val viewModel = VoiceViewModel(mockk<Application>(relaxed = true))
        viewModel.enterVoiceMode()
        val staleGeneration = viewModel.realtimeSessionGenerationForTest()
        viewModel.exitVoiceMode()
        viewModel.enterVoiceMode()

        viewModel.recordVoiceHandoffForTest(
            staleGeneration,
            VoiceHandoffEvent(label = "Waiting for route"),
        )

        assertTrue(viewModel.uiState.value.voiceMode)
        assertNull(viewModel.uiState.value.handoffStatus)
        assertNull(viewModel.uiState.value.backgroundRun)
    }

    @Test
    fun olderRouteTransitionCannotOverwriteConfirmedReconnect() = runTest {
        val viewModel = VoiceViewModel(mockk<Application>(relaxed = true))
        viewModel.enterVoiceMode()
        val generation = viewModel.realtimeSessionGenerationForTest()

        viewModel.recordVoiceHandoffForTest(
            generation,
            VoiceHandoffEvent(
                label = "Voice reconnected",
                active = false,
                success = true,
                transitionRevision = 4L,
            ),
        )
        viewModel.recordVoiceHandoffForTest(
            generation,
            VoiceHandoffEvent(
                label = "Waiting for route",
                transitionRevision = 3L,
            ),
        )

        assertEquals("Voice reconnected", viewModel.uiState.value.handoffStatus?.title)
        assertTrue(viewModel.uiState.value.handoffStatus?.success == true)
    }

    @Test
    fun terminalHandoffCannotLeaveBackgroundRunReconnecting() = runTest {
        val socket = mockk<WebSocket>(relaxed = true)
        val viewModel = VoiceViewModel(mockk<Application>(relaxed = true))
        viewModel.enterVoiceMode()
        val generation = viewModel.realtimeSessionGenerationForTest()
        viewModel.seedBackgroundRunForTest(
            run = BackgroundRunState(
                runId = "run-terminal-handoff",
                phase = BackgroundRunPhase.RECONNECTING,
            ),
            control = RealtimeAgentSessionControl(socket),
        )

        viewModel.recordVoiceHandoffForTest(
            generation,
            VoiceHandoffEvent(
                label = "Voice handoff failed",
                active = false,
                transitionRevision = 5L,
            ),
        )

        assertNull(viewModel.uiState.value.backgroundRun)
        assertEquals("Voice handoff failed", viewModel.uiState.value.handoffStatus?.title)
    }

    @Test
    fun exitSerializesWithCallbackThatAlreadyPassedGenerationCheck() = runTest {
        val viewModel = VoiceViewModel(mockk<Application>(relaxed = true))
        viewModel.enterVoiceMode()
        val generation = viewModel.realtimeSessionGenerationForTest()
        val reporterEntered = CountDownLatch(1)
        val releaseReporter = CountDownLatch(1)
        val exitStarted = CountDownLatch(1)
        val exitThread = AtomicReference<Thread?>()
        viewModel.setVoiceHandoffReporterForTest {
            reporterEntered.countDown()
            releaseReporter.await(2, TimeUnit.SECONDS)
        }

        val callback = async(Dispatchers.Default) {
            viewModel.recordVoiceHandoffForTest(
                generation,
                VoiceHandoffEvent(label = "Waiting for route"),
            )
        }
        assertTrue(reporterEntered.await(2, TimeUnit.SECONDS))
        val exit = async(Dispatchers.Default) {
            exitThread.set(Thread.currentThread())
            exitStarted.countDown()
            viewModel.exitVoiceMode()
        }
        assertTrue(exitStarted.await(2, TimeUnit.SECONDS))
        assertTrue("Exit must be waiting on the session lock", awaitBlocked(exitThread))
        releaseReporter.countDown()
        callback.await()
        exit.await()

        assertNull(viewModel.uiState.value.handoffStatus)
        assertNull(viewModel.uiState.value.backgroundRun)
    }

    @Test
    fun exitDetachesRunPromotedByCallbackAlreadyHoldingSessionLock() = runTest {
        val socket = mockk<WebSocket>()
        every { socket.send(any<String>()) } returns true
        val viewModel = VoiceViewModel(mockk<Application>(relaxed = true))
        viewModel.enterVoiceMode()
        val generation = viewModel.realtimeSessionGenerationForTest()
        val reporterEntered = CountDownLatch(1)
        val releaseReporter = CountDownLatch(1)
        val exitStarted = CountDownLatch(1)
        val exitThread = AtomicReference<Thread?>()
        viewModel.setVoiceHandoffReporterForTest {
            reporterEntered.countDown()
            releaseReporter.await(2, TimeUnit.SECONDS)
            viewModel.seedBackgroundRunForTest(
                run = BackgroundRunState(
                    runId = "run-promoted-during-exit",
                    phase = BackgroundRunPhase.RUNNING,
                ),
                control = RealtimeAgentSessionControl(socket),
            )
        }

        val callback = async(Dispatchers.Default) {
            viewModel.recordVoiceHandoffForTest(
                generation,
                VoiceHandoffEvent(label = "Waiting for route"),
            )
        }
        assertTrue(reporterEntered.await(2, TimeUnit.SECONDS))
        val exit = async(Dispatchers.Default) {
            exitThread.set(Thread.currentThread())
            exitStarted.countDown()
            viewModel.exitVoiceMode()
        }
        assertTrue(exitStarted.await(2, TimeUnit.SECONDS))
        assertTrue("Exit must be waiting on the session lock", awaitBlocked(exitThread))
        releaseReporter.countDown()
        callback.await()
        exit.await()

        verify(exactly = 0) { socket.send(match<String> { it.contains("response.cancel") }) }
        assertNull(viewModel.uiState.value.backgroundRun)
        assertNull(viewModel.uiState.value.handoffStatus)
    }

    @Test
    fun queuedCancelDismissesWhenAcknowledgementNeverArrives() = runTest {
        val socket = mockk<WebSocket>()
        every { socket.send(any<String>()) } returns true
        val viewModel = VoiceViewModel(mockk<Application>(relaxed = true))
        viewModel.seedBackgroundRunForTest(
            run = BackgroundRunState(
                runId = "run-cancel-timeout",
                phase = BackgroundRunPhase.RECONNECTING,
            ),
            control = RealtimeAgentSessionControl(socket),
        )

        viewModel.cancelBackgroundRun()
        assertEquals("Cancelling…", viewModel.uiState.value.backgroundRun?.message)
        advanceTimeBy(5_001L)
        runCurrent()

        assertNull(viewModel.uiState.value.backgroundRun)
        assertNull(viewModel.uiState.value.handoffStatus)
    }
}

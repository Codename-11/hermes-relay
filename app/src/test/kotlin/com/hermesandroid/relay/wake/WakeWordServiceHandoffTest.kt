package com.hermesandroid.relay.wake

import android.Manifest
import android.app.Application
import android.content.Intent
import com.hermesandroid.relay.assistant.HermesVoiceInteractionService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real service lifecycle/flow wiring with a deterministic native-reader boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WakeWordServiceHandoffTest {
    @Before fun setup() {
        MicrophoneOwnershipCoordinator.resetForTest()
        WakeWordActivationCoordinator.resetForTest()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        mockkConstructor(WakeWordPreferencesRepository::class, WakeWordModelInstaller::class, WakeWordAudioRecord::class)
        val file = File("unused-model")
        every { anyConstructed<WakeWordModelInstaller>().installedFiles() } returns
            WakeWordModelFiles(file, file, file, file, file, file)
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
        unmockkAll()
        MicrophoneOwnershipCoordinator.resetForTest()
        WakeWordActivationCoordinator.resetForTest()
    }

    @Test fun `assistant service yields to manual voice and resumes after exit`() = exerciseHandoff(true)
    @Test fun `foreground service yields to manual voice and resumes after exit`() = exerciseHandoff(false)

    @Test fun `assistant disabled during voice stays stopped on exit`() = exerciseHandoff(true, ResumeBlock.Disabled)
    @Test fun `foreground disabled during voice stays stopped on exit`() = exerciseHandoff(false, ResumeBlock.Disabled)
    @Test fun `assistant cannot resume with microphone permission revoked`() = exerciseHandoff(true, ResumeBlock.Permission)
    @Test fun `foreground cannot resume with microphone permission revoked`() = exerciseHandoff(false, ResumeBlock.Permission)

    private enum class ResumeBlock { Disabled, Permission }

    private fun exerciseHandoff(assistant: Boolean, resumeBlock: ResumeBlock? = null) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = MutableStateFlow(WakeWordPreferences(enabled = !assistant, assistantEnabled = assistant))
        every { anyConstructed<WakeWordPreferencesRepository>().flow } returns prefs
        val readers = Channel<CompletableDeferred<Boolean>>(Channel.UNLIMITED)
        val closed = Channel<Unit>(Channel.UNLIMITED)
        coEvery { anyConstructed<WakeWordAudioRecord>().detect(any(), any()) } coAnswers {
            firstArg<() -> Unit>().invoke()
            val release = CompletableDeferred<Boolean>()
            readers.send(release)
            withContext(NonCancellable) { release.await() }
        }
        every { anyConstructed<WakeWordAudioRecord>().stop() } answers { }
        every { anyConstructed<WakeWordAudioRecord>().close() } answers { closed.trySend(Unit); Unit }

        val existingVoice = MicrophoneOwnershipCoordinator.beginVoiceSession()
        val destroy: () -> Unit
        if (assistant) {
            val service = Robolectric.buildService(HermesVoiceInteractionService::class.java).create()
            service.get().onReady()
            destroy = { service.destroy() }
        } else {
            val service = Robolectric.buildService(WakeWordForegroundService::class.java).create()
            service.get().onStartCommand(Intent().setAction(WakeWordForegroundService.ACTION_START), 0, 1)
            destroy = { service.destroy() }
        }
        runCurrent()
        assertTrue(readers.tryReceive().isFailure)
        MicrophoneOwnershipCoordinator.endVoiceSession(existingVoice)
        val first = readers.receive()
        assertEquals(MicrophoneOwner.WakeWord, MicrophoneOwnershipCoordinator.owner.value)
        val voice = MicrophoneOwnershipCoordinator.beginVoiceSession()
        runCurrent()
        var handoffReady = false
        val waiter = launch { MicrophoneOwnershipCoordinator.awaitWakeRelease(); handoffReady = true }
        runCurrent()
        assertFalse(handoffReady)
        first.complete(true) // Late native detection must not launch an assistant or activation.
        closed.receive()
        waiter.join()
        runCurrent()
        assertTrue(handoffReady)
        assertNull(WakeWordActivationCoordinator.pending.value)
        assertNull(MicrophoneOwnershipCoordinator.tryAcquire(MicrophoneOwner.WakeWord))
        val capture = requireNotNull(MicrophoneOwnershipCoordinator.tryAcquire(MicrophoneOwner.VoiceCapture))
        MicrophoneOwnershipCoordinator.release(capture)
        runCurrent()
        assertTrue(readers.tryReceive().isFailure) // No reacquisition between utterances.
        when (resumeBlock) {
            ResumeBlock.Disabled -> prefs.value = WakeWordPreferences()
            ResumeBlock.Permission -> shadowOf(RuntimeEnvironment.getApplication())
                .denyPermissions(Manifest.permission.RECORD_AUDIO)
            null -> Unit
        }
        MicrophoneOwnershipCoordinator.endVoiceSession(voice)
        if (resumeBlock != null) {
            runCurrent()
            assertTrue(readers.tryReceive().isFailure)
            assertNull(MicrophoneOwnershipCoordinator.owner.value)
            val expected = if (resumeBlock == ResumeBlock.Disabled) "Stopped" else "Error"
            val actual = if (assistant) HermesVoiceInteractionService.runtimeState.value.name
                else WakeWordForegroundService.runtimeState.value.name
            assertEquals(expected, actual)
            destroy()
            return@runTest
        }
        val resumed = readers.receive()
        assertEquals(MicrophoneOwner.WakeWord, MicrophoneOwnershipCoordinator.owner.value)
        destroy()
        resumed.complete(true)
        closed.receive()
        MicrophoneOwnershipCoordinator.awaitWakeRelease()
        runCurrent()
        assertNull(MicrophoneOwnershipCoordinator.owner.value)
        assertNull(WakeWordActivationCoordinator.pending.value)
    }
}

package com.hermesandroid.relay.wake

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakeWordRecognitionTest {
    @Before fun reset() = MicrophoneOwnershipCoordinator.resetForTest()
    @After fun cleanup() = MicrophoneOwnershipCoordinator.resetForTest()

    private class Audio : WakeWordAudio {
        val releaseRead = CompletableDeferred<Boolean>()
        var stopped = false
        var closed = false
        var listening: (() -> Unit)? = null
        override suspend fun detect(onListening: () -> Unit, onSamples: (ShortArray, Int) -> Unit): Boolean {
            listening = onListening
            onListening()
            // Model native read/accept returning after cancellation was requested.
            return withContext(NonCancellable) { releaseRead.await() }
        }
        override fun stop() { stopped = true }
        override fun close() { closed = true }
    }

    @Test fun `handoff waits for native teardown and rejects stale detection`() = runTest {
        val recognition = WakeWordRecognition(backgroundScope, StandardTestDispatcher(testScheduler))
        val audio = Audio()
        var detections = 0
        var listening = 0
        recognition.start(
            canListen = { !MicrophoneOwnershipCoordinator.voiceSessionActive.value },
            createAudio = { audio }, onListening = { listening++ },
            onDetected = { detections++ }, onBusy = { fail("Unexpected busy") },
            onError = { throw it },
        )
        runCurrent()
        assertEquals(1, listening)
        val session = MicrophoneOwnershipCoordinator.beginVoiceSession()
        recognition.stop()
        var ready = false
        val waiter = launch { MicrophoneOwnershipCoordinator.awaitWakeRelease(); ready = true }
        runCurrent()
        assertTrue(audio.stopped)
        assertFalse(audio.closed)
        assertFalse(ready)
        audio.listening!!.invoke()
        audio.releaseRead.complete(true)
        runCurrent()
        assertTrue(audio.closed)
        assertTrue(ready)
        assertEquals(0, detections)
        assertEquals(1, listening)
        waiter.join()
        assertNull(MicrophoneOwnershipCoordinator.tryAcquire(MicrophoneOwner.WakeWord))
        val capture = requireNotNull(MicrophoneOwnershipCoordinator.tryAcquire(MicrophoneOwner.VoiceCapture))
        MicrophoneOwnershipCoordinator.release(capture)
        assertNull(MicrophoneOwnershipCoordinator.tryAcquire(MicrophoneOwner.WakeWord))
        MicrophoneOwnershipCoordinator.endVoiceSession(session)
        assertNotNull(MicrophoneOwnershipCoordinator.tryAcquire(MicrophoneOwner.WakeWord))
    }

    @Test fun `restart joins previous reader and cancellation cannot release replacement`() = runTest {
        val recognition = WakeWordRecognition(backgroundScope, StandardTestDispatcher(testScheduler))
        val first = Audio()
        val second = Audio()
        var created = 0
        fun start(audio: Audio) = recognition.start(
            canListen = { true }, createAudio = { created++; audio }, onListening = {},
            onDetected = {}, onBusy = { fail("Unexpected busy") }, onError = { throw it },
        )
        start(first)
        runCurrent()
        start(Audio()) // Superseded before its reader starts.
        runCurrent()
        start(second)
        runCurrent()
        assertEquals(1, created)
        first.releaseRead.complete(true)
        runCurrent()
        assertEquals(2, created)
        assertTrue(first.closed)
        assertFalse(second.closed)
        assertEquals(MicrophoneOwner.WakeWord, MicrophoneOwnershipCoordinator.owner.value)
        recognition.stop()
        second.releaseRead.complete(false)
        runCurrent()
        assertTrue(second.closed)
        assertNull(MicrophoneOwnershipCoordinator.owner.value)
    }

    @Test fun `destroy while starting closes resources and never publishes callbacks`() = runTest {
        val serviceJob = Job()
        val scope = kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(testScheduler) + serviceJob)
        val recognition = WakeWordRecognition(scope, StandardTestDispatcher(testScheduler))
        val audio = Audio()
        var detections = 0
        recognition.start(
            canListen = { true }, createAudio = { audio }, onListening = {},
            onDetected = { detections++ }, onBusy = {}, onError = { throw it },
        )
        runCurrent()
        recognition.stop()
        scope.cancel()
        audio.releaseRead.complete(true)
        runCurrent()
        assertTrue(audio.closed)
        assertEquals(0, detections)
        assertNull(MicrophoneOwnershipCoordinator.owner.value)
    }

    @Test fun `permission or model initialization failure releases lease`() = runTest {
        val recognition = WakeWordRecognition(backgroundScope, StandardTestDispatcher(testScheduler))
        var failure: Throwable? = null
        recognition.start(
            canListen = { true }, createAudio = { throw SecurityException("Permission revoked") },
            onListening = {}, onDetected = {}, onBusy = {}, onError = { failure = it },
        )
        runCurrent()
        assertTrue(failure is SecurityException)
        assertNull(MicrophoneOwnershipCoordinator.owner.value)
    }

    @Test fun `new service cannot acquire during voice and old exit cannot resume new session`() = runTest {
        val oldSession = MicrophoneOwnershipCoordinator.beginVoiceSession()
        val release = Job()
        MicrophoneOwnershipCoordinator.endVoiceSession(oldSession, release)
        val newSession = MicrophoneOwnershipCoordinator.beginVoiceSession()
        release.complete()
        assertTrue(MicrophoneOwnershipCoordinator.voiceSessionActive.value)
        val recognition = WakeWordRecognition(backgroundScope, StandardTestDispatcher(testScheduler))
        var busy = false
        recognition.start(
            canListen = { true }, createAudio = { error("Must not construct recorder") },
            onListening = {}, onDetected = {}, onBusy = { busy = true }, onError = { throw it },
        )
        runCurrent()
        assertTrue(busy)
        MicrophoneOwnershipCoordinator.endVoiceSession(newSession)
        assertFalse(MicrophoneOwnershipCoordinator.voiceSessionActive.value)
        assertNotNull(MicrophoneOwnershipCoordinator.tryAcquire(MicrophoneOwner.WakeWord))
    }
}

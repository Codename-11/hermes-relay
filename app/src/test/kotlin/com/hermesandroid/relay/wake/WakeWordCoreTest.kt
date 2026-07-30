package com.hermesandroid.relay.wake

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WakeWordCoreTest {
    @Before
    fun setUp() {
        MicrophoneOwnershipCoordinator.resetForTest()
        WakeWordActivationCoordinator.resetForTest()
    }

    @After
    fun tearDown() {
        MicrophoneOwnershipCoordinator.resetForTest()
        WakeWordActivationCoordinator.resetForTest()
    }

    @Test
    fun microphoneLease_isExclusiveAndRejectsStaleRelease() {
        val wakeLease = MicrophoneOwnershipCoordinator.tryAcquire(MicrophoneOwner.WakeWord)
        requireNotNull(wakeLease)
        assertEquals(MicrophoneOwner.WakeWord, MicrophoneOwnershipCoordinator.owner.value)
        assertNull(MicrophoneOwnershipCoordinator.tryAcquire(MicrophoneOwner.VoiceCapture))

        val stale = MicrophoneLease(MicrophoneOwner.WakeWord, "stale")
        assertFalse(MicrophoneOwnershipCoordinator.release(stale))
        assertEquals(MicrophoneOwner.WakeWord, MicrophoneOwnershipCoordinator.owner.value)

        assertTrue(MicrophoneOwnershipCoordinator.release(wakeLease))
        assertNull(MicrophoneOwnershipCoordinator.owner.value)
        assertEquals(
            MicrophoneOwner.VoiceCapture,
            MicrophoneOwnershipCoordinator.tryAcquire(MicrophoneOwner.VoiceCapture)?.owner,
        )
    }

    @Test
    fun activation_remainsPendingUntilMatchingConsumer() {
        val activation = WakeWordActivation(
            id = "activation-1",
            startNewSession = true,
            profileRouting = WakeWordProfileRouting(),
        )
        WakeWordActivationCoordinator.request(activation)

        assertEquals(activation, WakeWordActivationCoordinator.pending.value)
        assertFalse(WakeWordActivationCoordinator.consume("other"))
        assertEquals(activation, WakeWordActivationCoordinator.pending.value)
        assertTrue(WakeWordActivationCoordinator.consume("activation-1"))
        assertNull(WakeWordActivationCoordinator.pending.value)
    }

    @Test
    fun confirmationGate_firesOnceAfterRequiredConsecutiveFrames() {
        val gate = WakeWordConfirmationGate(requiredFrames = 3)
        assertFalse(gate.update(true))
        assertFalse(gate.update(false))
        assertFalse(gate.update(true))
        assertFalse(gate.update(true))
        assertTrue(gate.update(true))
        assertFalse(gate.update(true))
    }

    @Test
    fun sensitivityThreshold_higherMeansStricterAndClamps() {
        assertTrue(WakeWordTuning.threshold(0.8f) > WakeWordTuning.threshold(0.3f))
        assertEquals(0.2f, WakeWordTuning.threshold(-1f))
        assertEquals(0.9f, WakeWordTuning.threshold(2f))
    }
}

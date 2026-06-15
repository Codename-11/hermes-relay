package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureGuardTest {

    @Test
    fun discardsQuickReleaseBeforeSendingAudio() {
        assertTrue(shouldDiscardVoiceCapture(durationMs = 120L, pcmBytes = 8_000))
    }

    @Test
    fun discardsEmptyRecorderOutput() {
        assertTrue(shouldDiscardVoiceCapture(durationMs = 600L, pcmBytes = 0))
    }

    @Test
    fun acceptsSettledShortUtterance() {
        assertFalse(shouldDiscardVoiceCapture(durationMs = 420L, pcmBytes = 12_000))
    }
}

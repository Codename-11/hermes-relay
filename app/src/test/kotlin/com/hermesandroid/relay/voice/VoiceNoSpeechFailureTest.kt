package com.hermesandroid.relay.voice

import com.hermesandroid.relay.viewmodel.isNoSpeechTranscriptionFailure
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceNoSpeechFailureTest {
    @Test
    fun recognizesRelayEmptyTranscriptAsNoSpeech() {
        assertTrue(
            isNoSpeechTranscriptionFailure(
                IOException(
                    "Relay error (HTTP 500) " +
                        "({\"error\":\"xAI STT returned empty transcript\"})",
                ),
            ),
        )
    }

    @Test
    fun recognizesWrappedNoSpeechFailure() {
        assertTrue(
            isNoSpeechTranscriptionFailure(
                IOException("Voice transcribe failed", IllegalStateException("No speech detected")),
            ),
        )
    }

    @Test
    fun preservesRealTranscriptionFailures() {
        assertFalse(isNoSpeechTranscriptionFailure(IOException("HTTP 500 provider unavailable")))
        assertFalse(isNoSpeechTranscriptionFailure(null))
    }
}

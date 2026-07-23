package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.network.shared.VoiceSpeechStreamOutcome
import com.hermesandroid.relay.network.shared.VoiceSpeechStreamStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class StandardSpeechFallbackTest {
    @Test
    fun preAudioFallbackAndFailureUseLegacySpeech() {
        assertTrue(
            shouldFallbackStandardSpeech(
                VoiceSpeechStreamOutcome(VoiceSpeechStreamStatus.Fallback, audioStarted = false),
            ),
        )
        assertTrue(
            shouldFallbackStandardSpeech(
                VoiceSpeechStreamOutcome(
                    VoiceSpeechStreamStatus.Failed,
                    audioStarted = false,
                    error = IOException("auth expired"),
                ),
            ),
        )
    }

    @Test
    fun postAudioDisconnectNeverReplaysWholeReply() {
        assertFalse(
            shouldFallbackStandardSpeech(
                VoiceSpeechStreamOutcome(
                    VoiceSpeechStreamStatus.Failed,
                    audioStarted = true,
                    error = IOException("route changed"),
                ),
            ),
        )
        assertFalse(
            shouldFallbackStandardSpeech(
                VoiceSpeechStreamOutcome(VoiceSpeechStreamStatus.Completed, audioStarted = true),
            ),
        )
    }

    @Test
    fun completedStreamWithoutAudioUsesLegacySpeech() {
        assertTrue(
            shouldFallbackStandardSpeech(
                VoiceSpeechStreamOutcome(VoiceSpeechStreamStatus.Completed, audioStarted = false),
            ),
        )
    }
}

package com.hermesandroid.relay.assistant

import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSessionProtocolTest {
    @Test
    fun assistAction_routesIntoSystemSessionActivation() {
        assertTrue(AssistantSessionProtocol.isAssistAction("android.intent.action.ASSIST"))
        assertFalse(AssistantSessionProtocol.isAssistAction("android.intent.action.MAIN"))
        assertFalse(AssistantSessionProtocol.isAssistAction(null))
    }

    @Test
    fun voiceState_mapsToSystemSessionPresentation() {
        val listening = AssistantSessionProtocol.snapshotFromVoiceState(
            VoiceUiState(
                voiceMode = true,
                state = VoiceState.Listening,
                transcribedText = "Hey Hermes",
            )
        )
        assertEquals(AssistantSessionPhase.Listening, listening.phase)
        assertEquals("Hey Hermes", listening.transcript)

        val speaking = AssistantSessionProtocol.snapshotFromVoiceState(
            VoiceUiState(
                voiceMode = true,
                state = VoiceState.Speaking,
                responseText = "Hello.",
            )
        )
        assertEquals(AssistantSessionPhase.Speaking, speaking.phase)
        assertEquals("Hello.", speaking.response)
    }

    @Test
    fun exitAndError_haveTerminalPresentationStates() {
        val closed = AssistantSessionProtocol.snapshotFromVoiceState(VoiceUiState())
        assertEquals(AssistantSessionPhase.Closed, closed.phase)
        assertNull(closed.error)

        val error = AssistantSessionProtocol.snapshotFromVoiceState(
            VoiceUiState(
                voiceMode = true,
                state = VoiceState.Error,
                error = "Microphone unavailable",
            )
        )
        assertEquals(AssistantSessionPhase.Error, error.phase)
        assertEquals("Microphone unavailable", error.error)
    }

    @Test
    fun persistedSessionMarker_expiresAfterBoundedRecoveryWindow() {
        val now = 2_000_000L
        assertTrue(AssistantSessionPersistence.isFresh(now - 1_000L, now))
        assertFalse(AssistantSessionPersistence.isFresh(0L, now))
        assertFalse(
            AssistantSessionPersistence.isFresh(
                sinceMs = now - (31 * 60 * 1_000L),
                nowMs = now,
            )
        )
    }
}

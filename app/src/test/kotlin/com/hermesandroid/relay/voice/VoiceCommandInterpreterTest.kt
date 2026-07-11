package com.hermesandroid.relay.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandInterpreterTest {
    @Test
    fun `normalizes casing whitespace and terminal punctuation`() {
        val action = VoiceCommandInterpreter.interpretFinalTranscript(
            rawTranscript = "  STOP TALKING!!!  ",
            context = VoiceCommandContext(responseActive = true),
        )

        assertEquals(VoiceCommandAction.StopResponse, action)
    }

    @Test
    fun `normalizes hands-free punctuation without fuzzy matching`() {
        val action = VoiceCommandInterpreter.interpretFinalTranscript(
            rawTranscript = "Pause hands-free listening.",
            context = VoiceCommandContext(
                continuousModeSelected = true,
                continuousListeningActive = true,
            ),
        )

        assertEquals(VoiceCommandAction.PauseContinuousListening, action)
    }

    @Test
    fun `rejects partial transcripts and phrases embedded in ordinary prompts`() {
        val active = VoiceCommandContext(responseActive = true)

        assertNull(VoiceCommandInterpreter.interpretFinalTranscript("stop talk", active))
        assertNull(
            VoiceCommandInterpreter.interpretFinalTranscript(
                "Can you stop talking about the old design?",
                active,
            ),
        )
        assertNull(
            VoiceCommandInterpreter.interpretFinalTranscript(
                "Explain how to stop the response from timing out",
                active,
            ),
        )
    }

    @Test
    fun `stop is response-only and never aliases background cancellation`() {
        val bothActive = VoiceCommandContext(
            responseActive = true,
            backgroundTaskActive = true,
        )

        assertEquals(
            VoiceCommandAction.StopResponse,
            VoiceCommandInterpreter.interpretFinalTranscript("stop talking", bothActive),
        )
        assertEquals(
            VoiceCommandAction.CancelBackgroundTask,
            VoiceCommandInterpreter.interpretFinalTranscript(
                "cancel the background task",
                bothActive,
            ),
        )
        assertNull(VoiceCommandInterpreter.interpretFinalTranscript("cancel", bothActive))
        assertNull(VoiceCommandInterpreter.interpretFinalTranscript("stop", bothActive))
    }

    @Test
    fun `state gates stop and background cancellation`() {
        assertNull(
            VoiceCommandInterpreter.interpretFinalTranscript(
                "stop talking",
                VoiceCommandContext(responseActive = false),
            ),
        )
        assertNull(
            VoiceCommandInterpreter.interpretFinalTranscript(
                "cancel my background task",
                VoiceCommandContext(backgroundTaskActive = false),
            ),
        )
    }

    @Test
    fun `pause and resume require continuous mode and the matching loop state`() {
        assertEquals(
            VoiceCommandAction.PauseContinuousListening,
            VoiceCommandInterpreter.interpretFinalTranscript(
                "pause",
                VoiceCommandContext(
                    continuousModeSelected = true,
                    continuousListeningActive = true,
                ),
            ),
        )
        assertNull(
            VoiceCommandInterpreter.interpretFinalTranscript(
                "pause continuous listening",
                VoiceCommandContext(
                    continuousModeSelected = true,
                    continuousListeningActive = false,
                ),
            ),
        )
        assertEquals(
            VoiceCommandAction.ResumeContinuousListening,
            VoiceCommandInterpreter.interpretFinalTranscript(
                "resume",
                VoiceCommandContext(
                    continuousModeSelected = true,
                    continuousListeningPaused = true,
                ),
            ),
        )
        assertNull(
            VoiceCommandInterpreter.interpretFinalTranscript(
                "resume continuous listening",
                VoiceCommandContext(
                    continuousModeSelected = false,
                    continuousListeningPaused = true,
                ),
            ),
        )
    }

    @Test
    fun `repeat requires a delivered background answer`() {
        assertEquals(
            VoiceCommandAction.RepeatBackgroundAnswer,
            VoiceCommandInterpreter.interpretFinalTranscript(
                "repeat that",
                VoiceCommandContext(backgroundAnswerAvailable = true),
            ),
        )
        assertNull(
            VoiceCommandInterpreter.interpretFinalTranscript(
                "repeat the last background answer",
                VoiceCommandContext(backgroundAnswerAvailable = false),
            ),
        )
    }

    @Test
    fun `new chat is typed only at an allowed boundary`() {
        assertEquals(
            VoiceCommandAction.StartNewChat,
            VoiceCommandInterpreter.interpretFinalTranscript(
                "New chat!",
                VoiceCommandContext(canStartNewChat = true),
            ),
        )
        assertNull(
            VoiceCommandInterpreter.interpretFinalTranscript(
                "start a new chat",
                VoiceCommandContext(canStartNewChat = false),
            ),
        )
        assertNull(
            VoiceCommandInterpreter.interpretFinalTranscript(
                "Should I start a new chat for this?",
                VoiceCommandContext(canStartNewChat = true),
            ),
        )
    }
}

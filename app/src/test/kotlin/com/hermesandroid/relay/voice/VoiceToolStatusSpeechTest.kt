package com.hermesandroid.relay.voice

import com.hermesandroid.relay.viewmodel.brokeredToolStartStatusForTts
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceToolStatusSpeechTest {

    @Test
    fun `maps common Hermes tool starts to short spoken status`() {
        assertEquals("I'll search that now.", brokeredToolStartStatusForTts("web_search", 0))
        assertEquals("I'll run a quick check.", brokeredToolStartStatusForTts("terminal", 0))
        assertEquals("I'll check the phone.", brokeredToolStartStatusForTts("android_tap", 0))
        assertEquals("I'll check the desktop.", brokeredToolStartStatusForTts("desktop_computer_action", 0))
        assertEquals("I'll check the relevant files.", brokeredToolStartStatusForTts("read_file", 0))
    }

    @Test
    fun `keeps repeated tool status brief`() {
        assertEquals("I'm checking one more thing.", brokeredToolStartStatusForTts("web_search", 1))
        assertEquals("Let me check that.", brokeredToolStartStatusForTts("unknown_tool", 0))
    }
}

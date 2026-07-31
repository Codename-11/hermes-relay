package com.hermesandroid.relay.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenInterruptionLatchTest {
    @Test
    fun `note is one shot and matches upstream wording`() {
        var now = 1_000L
        val latch = SpokenInterruptionLatch(nowMs = { now })

        latch.mark()

        assertTrue(latch.take())
        assertFalse(latch.take())
        assertEquals(
            STABLE_VOICE_INTERFACE_CONTEXT_TEST_VALUE +
                "\n" + SPEECH_INTERRUPTED_NOTE,
            voiceInterfaceContextPrompt(
                STABLE_VOICE_INTERFACE_CONTEXT_TEST_VALUE,
                spokenReplyInterrupted = true,
            ),
        )
    }

    @Test
    fun `stale interruption expires and clear drops pending note`() {
        var now = 1_000L
        val latch = SpokenInterruptionLatch(nowMs = { now })

        latch.mark()
        now += 120_001L
        assertFalse(latch.take())

        latch.mark()
        latch.clear()
        assertFalse(latch.take())
        assertNull(latch.takeNote())
    }

    private companion object {
        const val STABLE_VOICE_INTERFACE_CONTEXT_TEST_VALUE = "Voice context"
    }
}

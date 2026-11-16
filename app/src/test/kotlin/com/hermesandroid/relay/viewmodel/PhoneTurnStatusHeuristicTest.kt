package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: bare substring matches like `working\\b` treated final replies
 * ("Not working — idle…") as mid-turn status and left Stop/streaming stuck.
 */
class PhoneTurnStatusHeuristicTest {

    @Test
    fun midTurnStatusLines_keepChrome() {
        assertTrue(looksLikePhoneTurnStatus("Working…"))
        assertTrue(looksLikePhoneTurnStatus("Still working…"))
        assertTrue(looksLikePhoneTurnStatus("Compacting context…"))
        assertTrue(looksLikePhoneTurnStatus("Compressing conversation…"))
        assertTrue(looksLikePhoneTurnStatus("Summarizing earlier turns…"))
        assertTrue(looksLikePhoneTurnStatus("Thinking…"))
        assertTrue(looksLikePhoneTurnStatus("Recalling memories…"))
        assertTrue(looksLikePhoneTurnStatus("Hindsight recall…"))
        assertTrue(looksLikePhoneTurnStatus("⏳ Reading files…"))
        assertTrue(looksLikePhoneTurnStatus("Progress: loading models…"))
        assertTrue(looksLikePhoneTurnStatus("Running tool search…"))
    }

    @Test
    fun finalRepliesThatMentionStatusWords_doNotKeepChrome() {
        assertFalse(
            looksLikePhoneTurnStatus(
                "Not working — idle. If the phone still shows streaming/Stop, that chrome is stuck.",
            ),
        )
        assertFalse(looksLikePhoneTurnStatus("Not working — idle."))
        assertFalse(looksLikePhoneTurnStatus("Not working — idle…"))
        assertFalse(
            looksLikePhoneTurnStatus(
                "Yes — already collapsed by default. No change needed.",
            ),
        )
        assertFalse(
            looksLikePhoneTurnStatus(
                "Shipped collapsible Context compaction tool card; reopen the chat to dogfood.",
            ),
        )
        assertFalse(
            looksLikePhoneTurnStatus(
                "The tool finished. Streaming should clear now.",
            ),
        )
        assertFalse(
            looksLikePhoneTurnStatus(
                "I am not still working; the turn ended a few minutes ago.",
            ),
        )
        assertFalse(looksLikePhoneTurnStatus("Done."))
        assertFalse(looksLikePhoneTurnStatus(""))
    }
}

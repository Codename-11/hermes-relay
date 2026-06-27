package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the [DemoMode] enter/exit state machine — the seam
 * [com.hermesandroid.relay.viewmodel.ConnectionViewModel] delegates `isDemoMode`
 * to. Runs without Android/Robolectric because [DemoMode] is plain Kotlin with
 * no framework or network collaborators (it takes only a transcript factory).
 *
 * "Demo never triggers a network call" is enforced structurally: [DemoMode] has
 * no client/socket reference it *could* call — it only flips a flag and holds
 * canned data. The ViewModel's network entry points (`reconnectIfStale`,
 * `revalidate`, `connectRelay`, `probeApiHealth`, `probeRelayHealth`)
 * early-return while [DemoMode.active] is true.
 */
class DemoModeTest {

    @Test
    fun startsInactiveWithEmptyTranscript() {
        val demo = DemoMode()
        assertFalse(demo.active.value)
        assertTrue(demo.transcript.value.isEmpty())
    }

    @Test
    fun enterActivatesAndLoadsTheCannedTranscript() {
        val demo = DemoMode()
        demo.enter()
        assertTrue("entering demo should set active", demo.active.value)
        assertEquals(
            "entering demo should load the canned transcript",
            DemoContent.transcript(),
            demo.transcript.value,
        )
        assertTrue(demo.transcript.value.isNotEmpty())
    }

    @Test
    fun exitDeactivatesAndClearsTheTranscript() {
        val demo = DemoMode()
        demo.enter()
        demo.exit()
        assertFalse("exiting demo should clear active", demo.active.value)
        assertTrue("exiting demo should clear the transcript", demo.transcript.value.isEmpty())
    }

    @Test
    fun enterIsIdempotent() {
        val demo = DemoMode()
        demo.enter()
        val first = demo.transcript.value
        demo.enter()
        assertTrue(demo.active.value)
        assertEquals(first, demo.transcript.value)
    }

    @Test
    fun roundTripReturnsToCleanInitialState() {
        val demo = DemoMode()
        demo.enter()
        demo.exit()
        demo.enter()
        demo.exit()
        assertFalse(demo.active.value)
        assertTrue(demo.transcript.value.isEmpty())
    }

    @Test
    fun usesInjectedTranscriptFactory() {
        val canned = listOf(
            ChatMessage(
                id = "x",
                role = MessageRole.USER,
                content = "hi",
                timestamp = 0L,
            ),
        )
        val demo = DemoMode(transcriptFactory = { canned })
        demo.enter()
        assertEquals(canned, demo.transcript.value)
    }
}

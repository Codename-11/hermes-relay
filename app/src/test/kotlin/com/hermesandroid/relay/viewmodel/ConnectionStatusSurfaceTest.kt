package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards [presentationSurface] — the pure classifier that tiers a
 * [ConnectionStatusSnapshot] onto a chrome surface by **persistence, not
 * severity**. The whole "routine reconnect no longer shoves the UI down, and
 * nothing non-critical covers the profile" behavior rides on this mapping:
 *
 * - in-flight (`active`) → [ConnectionStatusSurface.None]  (bottom strip cue)
 * - sustained Warning/Error → [ConnectionStatusSurface.Banner]  (take-space)
 * - everything else (connected / reconnected / route swap / info) →
 *   [ConnectionStatusSurface.Passive]  (slim strip above the top bar, no float)
 *
 * Also checks the real handoff producers map through
 * [asConnectionStatusSnapshot] to the surface each call site intends.
 */
class ConnectionStatusSurfaceTest {

    private fun snapshot(
        tone: ConnectionStatusTone = ConnectionStatusTone.Info,
        active: Boolean = false,
        success: Boolean = false,
    ) = ConnectionStatusSnapshot(
        title = "test",
        tone = tone,
        active = active,
        success = success,
    )

    @Test
    fun `in-flight reconnect stays off the top chrome`() {
        // active wins regardless of tone — a routine reconnect must never
        // reshape or float; the bottom strip carries it.
        assertEquals(
            ConnectionStatusSurface.None,
            snapshot(tone = ConnectionStatusTone.Info, active = true).presentationSurface(),
        )
        assertEquals(
            ConnectionStatusSurface.None,
            snapshot(tone = ConnectionStatusTone.Warning, active = true).presentationSurface(),
        )
    }

    @Test
    fun `positive delta gets the slim strip, never a float`() {
        // connected / reconnected — any success → the slim strip above the top
        // bar, so it doesn't cover the profile icon / nav.
        assertEquals(
            ConnectionStatusSurface.Passive,
            snapshot(tone = ConnectionStatusTone.Success, success = true).presentationSurface(),
        )
    }

    @Test
    fun `sustained problems take space`() {
        assertEquals(
            ConnectionStatusSurface.Banner,
            snapshot(tone = ConnectionStatusTone.Warning).presentationSurface(),
        )
        assertEquals(
            ConnectionStatusSurface.Banner,
            snapshot(tone = ConnectionStatusTone.Error).presentationSurface(),
        )
    }

    @Test
    fun `bare non-active info gets the slim strip`() {
        assertEquals(
            ConnectionStatusSurface.Passive,
            snapshot(tone = ConnectionStatusTone.Info).presentationSurface(),
        )
    }

    @Test
    fun `handoff producers map to the intended surfaces`() {
        // "Reconnecting" / "Connection interrupted": active → bottom strip.
        val reconnecting = ConnectionHandoffStatus(
            title = "Reconnecting",
            active = true,
            success = false,
        ).asConnectionStatusSnapshot()
        assertEquals(ConnectionStatusSurface.None, reconnecting.presentationSurface())

        // "Connection restored" / "Connected" / "route changed": settled +
        // success → slim strip (no float over the profile).
        val restored = ConnectionHandoffStatus(
            title = "Connection restored",
            active = false,
            success = true,
        ).asConnectionStatusSnapshot()
        assertEquals(ConnectionStatusSurface.Passive, restored.presentationSurface())
    }
}

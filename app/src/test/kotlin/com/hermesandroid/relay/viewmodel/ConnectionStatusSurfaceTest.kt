package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards [presentationSurface] — the pure classifier that tiers a
 * [ConnectionStatusSnapshot] onto a chrome surface by **persistence, not
 * severity**. The whole "routine reconnect no longer shoves the UI down"
 * behavior rides on this mapping, so pin it:
 *
 * - in-flight (`active`) → [ConnectionStatusSurface.None]  (bottom strip cue)
 * - resolved positive (`success`) → [ConnectionStatusSurface.Float]
 * - sustained Warning/Error → [ConnectionStatusSurface.Banner]
 * - bare non-active Info → [ConnectionStatusSurface.Float]
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
    fun `resolved positive delta floats`() {
        assertEquals(
            ConnectionStatusSurface.Float,
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
    fun `bare non-active info floats rather than reshaping`() {
        assertEquals(
            ConnectionStatusSurface.Float,
            snapshot(tone = ConnectionStatusTone.Info).presentationSurface(),
        )
    }

    @Test
    fun `handoff producers map to the intended surfaces`() {
        // "Reconnecting" / "Connection interrupted": active, not success.
        val reconnecting = ConnectionHandoffStatus(
            title = "Reconnecting",
            active = true,
            success = false,
        ).asConnectionStatusSnapshot()
        assertEquals(ConnectionStatusSurface.None, reconnecting.presentationSurface())

        // "Connection restored" / "Connected" / "route changed": settled + success.
        val restored = ConnectionHandoffStatus(
            title = "Connection restored",
            active = false,
            success = true,
        ).asConnectionStatusSnapshot()
        assertEquals(ConnectionStatusSurface.Float, restored.presentationSurface())
    }
}

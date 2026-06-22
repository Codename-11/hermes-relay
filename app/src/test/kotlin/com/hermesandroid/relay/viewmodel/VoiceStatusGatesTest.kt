package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the two pure decision helpers extracted from
 * [VoiceViewModel]: [shouldSpeakStatusNow] (the W3 spoken-status throttle) and
 * [shouldMarkRealtimeOutputActive] (the playback-synced "output is live" gate).
 *
 * Both are top-level `internal` and side-effect free, so they need no
 * ViewModel / Android scaffolding.
 */
class VoiceStatusGatesTest {

    // --- shouldSpeakStatusNow ----------------------------------------------
    //
    // Contract (from the helper + its KDoc):
    //   if (count >= maxCount) return false                       // cap wins first
    //   if (lastSpokenAtMs > 0 && now - lastSpokenAtMs < gapMs) return false
    //   else return true
    //
    // i.e. lastSpokenAtMs == 0 ("none yet this turn") skips the gap check, but
    // the over-count cap is still enforced ahead of it.

    @Test
    fun firstOfTurn_isAllowed() {
        // lastSpokenAtMs == 0 → no prior status this turn → always allowed
        // (count under cap).
        assertTrue(
            shouldSpeakStatusNow(
                now = 0L,
                lastSpokenAtMs = 0L,
                count = 0,
                gapMs = 4_000L,
                maxCount = 6,
            ),
        )
    }

    @Test
    fun firstOfTurn_allowed_evenWhenNowIsLargeAndGapWide() {
        // The gap check is skipped entirely when lastSpokenAtMs == 0, so a huge
        // `now` against a wide gap is irrelevant.
        assertTrue(
            shouldSpeakStatusNow(
                now = 1_000_000L,
                lastSpokenAtMs = 0L,
                count = 2,
                gapMs = 10_000L,
                maxCount = 6,
            ),
        )
    }

    @Test
    fun withinGap_isSuppressed() {
        // now - lastSpokenAtMs = 5_000 - 2_000 = 3_000 < 4_000 → suppress.
        assertFalse(
            shouldSpeakStatusNow(
                now = 5_000L,
                lastSpokenAtMs = 2_000L,
                count = 1,
                gapMs = 4_000L,
                maxCount = 6,
            ),
        )
    }

    @Test
    fun overCount_isSuppressed_evenFirstOfTurn() {
        // count >= maxCount short-circuits to false BEFORE the gap/first checks,
        // so even lastSpokenAtMs == 0 cannot rescue an over-cap status.
        assertFalse(
            shouldSpeakStatusNow(
                now = 0L,
                lastSpokenAtMs = 0L,
                count = 6,
                gapMs = 4_000L,
                maxCount = 6,
            ),
        )
    }

    @Test
    fun overCount_isSuppressed_pastGap() {
        // Well past the gap, but at the cap → still suppressed.
        assertFalse(
            shouldSpeakStatusNow(
                now = 100_000L,
                lastSpokenAtMs = 1_000L,
                count = 7,
                gapMs = 4_000L,
                maxCount = 6,
            ),
        )
    }

    @Test
    fun pastGap_underCount_isAllowed() {
        // now - lastSpokenAtMs = 10_000 - 2_000 = 8_000 >= 4_000 gap, count < cap.
        assertTrue(
            shouldSpeakStatusNow(
                now = 10_000L,
                lastSpokenAtMs = 2_000L,
                count = 2,
                gapMs = 4_000L,
                maxCount = 6,
            ),
        )
    }

    @Test
    fun exactlyAtGap_isAllowed() {
        // now - lastSpokenAtMs == gapMs (4_000 == 4_000). The suppression
        // predicate is strict `<`, so being exactly at the gap is NOT suppressed.
        assertTrue(
            shouldSpeakStatusNow(
                now = 6_000L,
                lastSpokenAtMs = 2_000L,
                count = 1,
                gapMs = 4_000L,
                maxCount = 6,
            ),
        )
    }

    @Test
    fun justBelowCount_isAllowed_pastGap() {
        // count == maxCount - 1 is the last allowed slot (cap check is `>=`).
        assertTrue(
            shouldSpeakStatusNow(
                now = 10_000L,
                lastSpokenAtMs = 1_000L,
                count = 5,
                gapMs = 4_000L,
                maxCount = 6,
            ),
        )
    }

    // --- shouldMarkRealtimeOutputActive ------------------------------------
    //
    // Contract: true once headFrames > 0 OR playbackAmplitude > 0f; false only
    // at the cold (0, 0f) origin.

    @Test
    fun coldOrigin_isInactive() {
        assertFalse(shouldMarkRealtimeOutputActive(headFrames = 0, playbackAmplitude = 0f))
    }

    @Test
    fun headFramesMoved_isActive() {
        assertTrue(shouldMarkRealtimeOutputActive(headFrames = 1, playbackAmplitude = 0f))
    }

    @Test
    fun amplitudePresent_isActive() {
        assertTrue(shouldMarkRealtimeOutputActive(headFrames = 0, playbackAmplitude = 0.01f))
    }

    @Test
    fun bothPresent_isActive() {
        assertTrue(shouldMarkRealtimeOutputActive(headFrames = 1, playbackAmplitude = 0.5f))
    }
}

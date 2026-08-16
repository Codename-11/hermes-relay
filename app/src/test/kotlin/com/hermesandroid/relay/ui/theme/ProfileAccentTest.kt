package com.hermesandroid.relay.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileAccentTest {
    @Test
    fun namedProfilesReceiveStableDistinctColors() {
        assertEquals(deterministicProfileAccent("alpha"), deterministicProfileAccent("alpha"))
        assertNotEquals(deterministicProfileAccent("alpha"), deterministicProfileAccent("beta"))
    }

    @Test
    fun overrideWinsAndDefaultRemainsNeutral() {
        assertEquals(accentColor("#356CFF"), resolveProfileAccent("alpha", mapOf("alpha" to "#356CFF")))
        assertNull(resolveProfileAccent("default", mapOf("default" to "#356CFF")))
    }

    @Test
    fun pickerOffersDesktopSizedPalette() {
        assertEquals(12, ProfileAccentSwatches.distinct().size)
    }
}

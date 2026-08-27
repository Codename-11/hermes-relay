package com.hermesandroid.relay.ui.screens

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ChangelogScreenTest {
    @Test
    fun formatsReleaseDateForTheCurrentLocale() {
        assertEquals("Aug 25, 2026", formatReleaseDate("2026-08-25", Locale.US))
    }

    @Test
    fun preservesAnUnparseableReleaseDate() {
        assertEquals("coming soon", formatReleaseDate("coming soon", Locale.US))
    }

    @Test
    fun matchesInstalledReleaseAcrossFlavorSuffixes() {
        assertEquals(true, isInstalledRelease("1.13.2", "1.13.2-sideload", "sideload"))
        assertEquals(true, isInstalledRelease("1.13.2-rc.1", "1.13.2-rc.1-googlePlay", "googlePlay"))
        assertEquals(false, isInstalledRelease("1.13.1", "1.13.2-sideload", "sideload"))
    }
}

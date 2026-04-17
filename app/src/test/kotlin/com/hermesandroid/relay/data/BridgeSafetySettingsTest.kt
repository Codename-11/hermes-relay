package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Unit tests for [BridgeSafetySettings] defaults.
 *
 * The DataStore-backed [BridgeSafetyPreferencesRepository] requires
 * Android Context and isn't covered here; we test the data class's
 * default values to lock in the v0.4.1 unattended-access wire shape.
 *
 * The two new fields are load-bearing for the unattended-access
 * feature gate, so the defaults need to stay false-by-default so a
 * fresh install doesn't accidentally opt in to wake-the-screen-on-
 * commands behaviour.
 */
class BridgeSafetySettingsTest {

    @Test
    fun unattendedAccessEnabled_defaultsToFalse() {
        val settings = BridgeSafetySettings()
        assertFalse(
            "unattendedAccessEnabled must default to false " +
                "— enabling on first install would opt users into " +
                "screen-wake behaviour without consent",
            settings.unattendedAccessEnabled,
        )
        assertFalse(
            "DEFAULT_UNATTENDED_ACCESS_ENABLED constant must match the data class default",
            DEFAULT_UNATTENDED_ACCESS_ENABLED,
        )
    }

    @Test
    fun unattendedWarningSeen_defaultsToFalse() {
        val settings = BridgeSafetySettings()
        assertFalse(
            "unattendedWarningSeen must default to false so the scary " +
                "explainer dialog fires the first time the user enables " +
                "unattended access",
            settings.unattendedWarningSeen,
        )
        assertFalse(
            "DEFAULT_UNATTENDED_WARNING_SEEN constant must match the data class default",
            DEFAULT_UNATTENDED_WARNING_SEEN,
        )
    }

    @Test
    fun copy_preservesUnattendedFields() {
        // copy() with only one field set should preserve the others —
        // catches a refactor that drops one of the unattended fields
        // from the data class signature.
        val before = BridgeSafetySettings(
            unattendedAccessEnabled = true,
            unattendedWarningSeen = true,
        )
        val after = before.copy(autoDisableMinutes = 60)
        assertEquals(true, after.unattendedAccessEnabled)
        assertEquals(true, after.unattendedWarningSeen)
        assertEquals(60, after.autoDisableMinutes)
    }
}

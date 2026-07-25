package com.hermesandroid.relay.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsTest {

    @Test
    fun debugBuildAlwaysEnablesRelayTools() {
        assertTrue(
            FeatureFlags.resolveRelayEnabled(
                isDevBuild = true,
                isSideload = false,
                storedOverride = false,
            )
        )
    }

    @Test
    fun sideloadDefaultsRelayToolsOnWithoutStoredChoice() {
        assertTrue(
            FeatureFlags.resolveRelayEnabled(
                isDevBuild = false,
                isSideload = true,
                storedOverride = null,
            )
        )
    }

    @Test
    fun googlePlayDefaultsRelayToolsOffWithoutStoredChoice() {
        assertFalse(
            FeatureFlags.resolveRelayEnabled(
                isDevBuild = false,
                isSideload = false,
                storedOverride = null,
            )
        )
    }

    @Test
    fun explicitOptInEnablesGooglePlayRelayTools() {
        assertTrue(
            FeatureFlags.resolveRelayEnabled(
                isDevBuild = false,
                isSideload = false,
                storedOverride = true,
            )
        )
    }

    @Test
    fun explicitOptOutDisablesSideloadRelayTools() {
        assertFalse(
            FeatureFlags.resolveRelayEnabled(
                isDevBuild = false,
                isSideload = true,
                storedOverride = false,
            )
        )
    }
}

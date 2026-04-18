package com.hermesandroid.relay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [buildPromptBlock] / [buildBridgeLine].
 *
 * v0.4.1 added three unattended-access fields to [PhoneSnapshot]
 * ([PhoneSnapshot.unattendedEnabled], [PhoneSnapshot.credentialLockDetected],
 * [PhoneSnapshot.screenOn]). The agent relies on the resulting system-prompt
 * copy to decide whether to defer bridge commands or warn the user up front —
 * so the exact string fragments are load-bearing and these tests lock them in.
 *
 * The tests also preserve existing behaviour:
 *   - `master = false` returns `null` (omits the system message entirely).
 *   - `bridgeBound = false` short-circuits to the "not connected" line,
 *     regardless of the new unattended fields (the agent shouldn't be
 *     advised about unattended access if there's no bridge at all).
 *   - `bridgeBound = true && masterEnabled = false` short-circuits to the
 *     "disabled by user" line before any unattended copy is appended.
 */
class PhoneStatusPromptBuilderTest {

    private val defaultSettings = AppContextSettings()

    // --- Case 1: bridge not bound, all defaults off ---

    @Test
    fun defaultSnapshot_bridgeNotBound_saysNotConnected() {
        val output = buildPromptBlock(defaultSettings, PhoneSnapshot())
        assertNotNull(
            "master defaults to true so the block should render",
            output,
        )
        assertTrue(
            "expected the 'not connected' bridge line when bridgeBound=false; got: $output",
            output!!.contains("Phone bridge: not connected"),
        )
        assertTrue(
            "unattended copy must NOT leak into the not-connected path",
            !output.contains("Unattended access"),
        )
        assertTrue(
            "screen copy must NOT leak into the not-connected path",
            !output.contains("Screen: "),
        )
    }

    // --- Case 2: bridgeBound but masterEnabled=false → disabled-by-user ---

    @Test
    fun bridgeBound_masterDisabled_returnsDisabledLine_withoutUnattendedCopy() {
        val snapshot = PhoneSnapshot(
            bridgeBound = true,
            masterEnabled = false,
            // Even if these were somehow set, masterEnabled=false must win.
            unattendedEnabled = true,
            credentialLockDetected = true,
            screenOn = true,
        )
        val output = buildPromptBlock(defaultSettings, snapshot)
        assertNotNull(output)
        assertTrue(
            "expected the disabled-by-user line; got: $output",
            output!!.contains("Phone bridge: connected but disabled by user"),
        )
        assertFalse(
            "unattended copy must NOT appear when master is disabled",
            output.contains("Unattended access"),
        )
        assertFalse(
            "screen on/off copy must NOT appear when master is disabled",
            output.contains("Screen: "),
        )
    }

    // --- Case 3: fully enabled, unattended off, screen on ---

    @Test
    fun fullyEnabled_unattendedOff_screenOn_rendersOffAdviceAndScreenOn() {
        val snapshot = PhoneSnapshot(
            bridgeBound = true,
            masterEnabled = true,
            accessibilityGranted = true,
            unattendedEnabled = false,
            credentialLockDetected = false,
            screenOn = true,
        )
        val output = buildPromptBlock(defaultSettings, snapshot)
        assertNotNull(output)
        assertTrue(
            "expected 'Unattended access: off' advisory; got: $output",
            output!!.contains("Unattended access: off"),
        )
        assertTrue(
            "off-advisory should tell the agent to ask the user to wake the phone",
            output.contains("ask the user to wake the phone"),
        )
        assertTrue(
            "expected 'Screen: on.'; got: $output",
            output.contains("Screen: on."),
        )
        assertFalse(
            "must not emit the screen-off copy when screenOn=true",
            output.contains("Screen: off."),
        )
    }

    // --- Case 4: unattended on, no credential lock, screen off ---

    @Test
    fun unattendedOn_noLock_screenOff_rendersSafeAdviceAndScreenOff() {
        val snapshot = PhoneSnapshot(
            bridgeBound = true,
            masterEnabled = true,
            accessibilityGranted = true,
            unattendedEnabled = true,
            credentialLockDetected = false,
            screenOn = false,
        )
        val output = buildPromptBlock(defaultSettings, snapshot)
        assertNotNull(output)
        assertTrue(
            "expected the 'Unattended access: on — the screen will wake' advisory; got: $output",
            output!!.contains("Unattended access: on \u2014 the screen will wake"),
        )
        assertTrue(
            "safe-unattended advisory should tell the agent it's safe to send commands",
            output.contains("Safe to send commands while the user is away"),
        )
        assertTrue(
            "expected 'Screen: off.'; got: $output",
            output.contains("Screen: off."),
        )
        assertFalse(
            "must not emit the credential-lock warning when credentialLockDetected=false",
            output.contains("credential lock"),
        )
    }

    // --- Case 5: unattended on WITH credential lock → keyguard_blocked warning ---

    @Test
    fun unattendedOn_withCredentialLock_rendersKeyguardBlockedWarning() {
        val snapshot = PhoneSnapshot(
            bridgeBound = true,
            masterEnabled = true,
            accessibilityGranted = true,
            unattendedEnabled = true,
            credentialLockDetected = true,
            screenOn = true,
        )
        val output = buildPromptBlock(defaultSettings, snapshot)
        assertNotNull(output)
        assertTrue(
            "agent MUST see the credential-lock warning string; got: $output",
            output!!.contains("Unattended access: on, but the device has a credential lock"),
        )
        assertTrue(
            "warning should enumerate PIN / pattern / biometric",
            output.contains("PIN / pattern / biometric"),
        )
        assertTrue(
            "warning should mention the keyguard_blocked error code so the agent " +
                "recognises the failure mode at tool-call time",
            output.contains("keyguard_blocked"),
        )
        assertFalse(
            "must not emit the safe-unattended advisory when credential lock is present",
            output.contains("Safe to send commands while the user is away"),
        )
    }

    // --- Case 6: master=false → null ---

    @Test
    fun masterDisabled_returnsNull() {
        val output = buildPromptBlock(
            AppContextSettings(master = false),
            PhoneSnapshot(
                bridgeBound = true,
                masterEnabled = true,
                unattendedEnabled = true,
                screenOn = true,
            ),
        )
        assertNull(
            "master=false must omit the system message entirely " +
                "— no empty string, no preamble",
            output,
        )
    }

    // --- Regression: fully-enabled permissions list still survives the new copy ---

    @Test
    fun fullyEnabled_stillRendersPermissionsList() {
        // Locks in that the unattended + screen additions didn't displace
        // the existing permissions summary.
        val snapshot = PhoneSnapshot(
            bridgeBound = true,
            masterEnabled = true,
            accessibilityGranted = true,
            screenCaptureGranted = true,
            overlayGranted = true,
            notificationsGranted = true,
            unattendedEnabled = true,
            credentialLockDetected = false,
            screenOn = true,
        )
        val output = buildPromptBlock(defaultSettings, snapshot)
        assertNotNull(output)
        assertTrue(
            "expected permissions list; got: $output",
            output!!.contains("Permissions: accessibility, screen capture, overlay, notifications"),
        )
    }
}

package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented tests for the v0.4.1 [BridgeMasterToggle] polish: tapping
 * the Switch to ON while accessibility hasn't been granted must route
 * through the new [BridgeMasterToggle.onAccessibilityNeeded] callback
 * instead of silently flipping the toggle on.
 *
 * The Switch is intentionally *not* `enabled = false` when accessibility
 * is missing — a disabled Switch swallows taps silently on Android, which
 * reads as a broken control to users. Instead the onCheckedChange handler
 * short-circuits through onAccessibilityNeeded so BridgeScreen can surface
 * an "Open Settings" snackbar.
 */
class BridgeMasterToggleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun switch_whenAccessibilityMissing_togglingOn_callsOnAccessibilityNeeded() {
        var toggleCalls = 0
        var toggleLastValue: Boolean? = null
        var needsCalls = 0

        composeTestRule.setContent {
            MaterialTheme {
                BridgeMasterToggle(
                    enabled = false,
                    status = null,
                    accessibilityGranted = false,
                    onToggle = { wantsOn ->
                        toggleCalls++
                        toggleLastValue = wantsOn
                    },
                    onAccessibilityNeeded = { needsCalls++ },
                )
            }
        }

        // The Switch is the only toggleable node in this composable — find it
        // without relying on an accessibility label (the design spec doesn't
        // currently give the Switch one) to avoid flakiness if the label
        // changes.
        composeTestRule.onNode(isToggleable()).assertIsOff()
        composeTestRule.onNode(isToggleable()).performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            "onAccessibilityNeeded should fire exactly once when user taps " +
                "to enable without accessibility permission",
            1,
            needsCalls,
        )
        assertEquals(
            "onToggle must NOT fire on the blocked-enable path — otherwise " +
                "callers would see a phantom enable event even though a11y " +
                "isn't granted",
            0,
            toggleCalls,
        )
        assertEquals(
            "sanity: onToggle lastValue should remain untouched",
            null,
            toggleLastValue,
        )
    }

    @Test
    fun switch_whenAccessibilityGrantedAndOn_togglingOff_callsOnToggleFalse() {
        // Complementary path: when accessibility IS granted and the Switch
        // is currently checked, flipping it off must go through onToggle
        // (not onAccessibilityNeeded). Locks in that the new conditional
        // didn't accidentally hijack the normal toggle-off path.
        var toggleCalls = 0
        var toggleLastValue: Boolean? = null
        var needsCalls = 0

        composeTestRule.setContent {
            MaterialTheme {
                BridgeMasterToggle(
                    enabled = true,
                    status = null,
                    accessibilityGranted = true,
                    onToggle = { wantsOn ->
                        toggleCalls++
                        toggleLastValue = wantsOn
                    },
                    onAccessibilityNeeded = { needsCalls++ },
                )
            }
        }

        composeTestRule.onNode(isToggleable()).performClick()
        composeTestRule.waitForIdle()

        assertEquals("onToggle must fire exactly once", 1, toggleCalls)
        assertFalse(
            "toggling a checked switch should pass wantsOn=false",
            toggleLastValue!!,
        )
        assertEquals(
            "onAccessibilityNeeded must NOT fire on the normal toggle-off path",
            0,
            needsCalls,
        )
    }

    @Test
    fun switch_whenAccessibilityGrantedAndOff_togglingOn_callsOnToggleTrue() {
        var toggleCalls = 0
        var toggleLastValue: Boolean? = null
        var needsCalls = 0

        composeTestRule.setContent {
            MaterialTheme {
                BridgeMasterToggle(
                    enabled = false,
                    status = null,
                    accessibilityGranted = true,
                    onToggle = { wantsOn ->
                        toggleCalls++
                        toggleLastValue = wantsOn
                    },
                    onAccessibilityNeeded = { needsCalls++ },
                )
            }
        }

        composeTestRule.onNode(isToggleable()).performClick()
        composeTestRule.waitForIdle()

        assertEquals("onToggle must fire exactly once", 1, toggleCalls)
        assertTrue(
            "toggling an unchecked switch with a11y granted should pass wantsOn=true",
            toggleLastValue!!,
        )
        assertEquals(
            "onAccessibilityNeeded must NOT fire when a11y is already granted",
            0,
            needsCalls,
        )
    }
}

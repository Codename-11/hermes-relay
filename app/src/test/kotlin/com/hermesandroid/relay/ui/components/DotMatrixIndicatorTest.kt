package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DotMatrixIndicatorTest {

    @Test
    fun animationRunsOnlyWhenEveryMotionGateAllowsIt() {
        assertTrue(
            shouldAnimateDotMatrix(
                appAnimationsEnabled = true,
                osAnimationsEnabled = true,
                touchExplorationEnabled = false,
            ),
        )
    }

    @Test
    fun appAnimationPreferenceCanParkTheIndicator() {
        assertFalse(
            shouldAnimateDotMatrix(
                appAnimationsEnabled = false,
                osAnimationsEnabled = true,
                touchExplorationEnabled = false,
            ),
        )
    }

    @Test
    fun systemReduceMotionCanParkTheIndicator() {
        assertFalse(
            shouldAnimateDotMatrix(
                appAnimationsEnabled = true,
                osAnimationsEnabled = false,
                touchExplorationEnabled = false,
            ),
        )
    }

    @Test
    fun talkBackTouchExplorationCanParkTheIndicator() {
        assertFalse(
            shouldAnimateDotMatrix(
                appAnimationsEnabled = true,
                osAnimationsEnabled = true,
                touchExplorationEnabled = true,
            ),
        )
    }
}

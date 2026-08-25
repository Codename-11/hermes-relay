package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MorphingSphereMotionPolicyTest {
    @Test
    fun `visible idle sphere uses lightweight ambient motion`() {
        assertEquals(
            SphereMotionMode.AmbientLayer,
            sphereMotionMode(
                state = SphereState.Idle,
                voiceMode = false,
                motionVisible = true,
                fixedTime = null,
                fixedColorPhase = null,
            ),
        )
    }

    @Test
    fun `hidden or paused idle sphere is still`() {
        assertEquals(
            SphereMotionMode.Still,
            sphereMotionMode(SphereState.Idle, false, false, null, null),
        )
        assertEquals(
            SphereMotionMode.Still,
            sphereMotionMode(SphereState.Idle, false, true, 0f, 0f),
        )
    }

    @Test
    fun `visible active and voice states keep procedural motion`() {
        assertEquals(
            SphereMotionMode.Procedural,
            sphereMotionMode(SphereState.Thinking, false, true, null, null),
        )
        assertEquals(
            SphereMotionMode.Procedural,
            sphereMotionMode(SphereState.Idle, true, true, null, null),
        )
    }
}

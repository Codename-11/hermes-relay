package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MorphingSphereMotionPolicyTest {
    @Test
    fun `every visible sphere uses smooth procedural motion`() {
        assertEquals(
            SphereMotionMode.Procedural,
            sphereMotionMode(
                motionVisible = true,
                fixedTime = null,
                fixedColorPhase = null,
            ),
        )
    }

    @Test
    fun `hidden or paused sphere is still`() {
        assertEquals(
            SphereMotionMode.Still,
            sphereMotionMode(false, null, null),
        )
        assertEquals(
            SphereMotionMode.Still,
            sphereMotionMode(true, 0f, 0f),
        )
    }
}

package com.hermesandroid.relay.update

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SemverCompareTest {
    @Test fun `equal versions compare equal`() {
        assertEquals(0, compareVersions("0.5.0", "0.5.0"))
        assertEquals(0, compareVersions("v0.5.0", "0.5.0"))
        assertEquals(0, compareVersions("0.5", "0.5.0"))
    }

    @Test fun `older current returns negative`() {
        assertTrue(compareVersions("0.5.0", "0.5.1") < 0)
        assertTrue(compareVersions("0.5.9", "0.6.0") < 0)
        assertTrue(compareVersions("0.9.0", "1.0.0") < 0)
        assertTrue(compareVersions("v0.4.1", "v0.5.0") < 0)
    }

    @Test fun `newer current returns positive`() {
        assertTrue(compareVersions("0.6.0", "0.5.9") > 0)
        assertTrue(compareVersions("1.0.0", "0.9.9") > 0)
    }

    @Test fun `prerelease suffix is stripped — treats rc and release as equal`() {
        assertEquals(0, compareVersions("0.6.0-rc.1", "0.6.0"))
        assertEquals(0, compareVersions("0.6.0", "0.6.0-rc.1"))
    }

    @Test fun `build metadata is stripped`() {
        assertEquals(0, compareVersions("0.5.0+build.42", "0.5.0"))
    }

    @Test fun `malformed segments default to zero`() {
        assertEquals(0, compareVersions("0.5.abc", "0.5.0"))
        assertTrue(compareVersions("0.5.0", "0.6.xyz") < 0)
    }

    @Test fun `leading whitespace is tolerated`() {
        assertEquals(0, compareVersions("  0.5.0  ", "0.5.0"))
    }
}

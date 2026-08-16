package com.hermesandroid.relay.network.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronManagementTest {
    @Test
    fun `finite repeat accepts bounded whole numbers and optional blank`() {
        assertNull(parseFiniteRepeat(" ").getOrThrow())
        assertEquals(2, parseFiniteRepeat("2").getOrThrow())
        assertEquals(999, parseFiniteRepeat("999").getOrThrow())
    }

    @Test
    fun `finite repeat never coerces unsafe values to unlimited`() {
        assertTrue(parseFiniteRepeat("0").isFailure)
        assertTrue(parseFiniteRepeat("-2").isFailure)
        assertTrue(parseFiniteRepeat("1000").isFailure)
        assertTrue(parseFiniteRepeat("two").isFailure)
    }

    @Test
    fun `draft validation trims fields and preserves explicit profile`() {
        val result = CronCreationDraft(
            name = "  Morning brief ",
            schedule = " every 1d ",
            prompt = " Summarize updates ",
            repeat = 3,
            profile = " work ",
        ).validated().getOrThrow()

        assertEquals("Morning brief", result.name)
        assertEquals("every 1d", result.schedule)
        assertEquals("Summarize updates", result.prompt)
        assertEquals("work", result.profile)
    }
}

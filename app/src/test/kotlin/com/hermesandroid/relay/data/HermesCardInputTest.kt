package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HermesCardInputTest {

    @Test
    fun `multi select answer is an exact ordered deduplicated json array`() {
        assertEquals(
            "[\"prod\",\"dev\",\"custom, value\"]",
            encodeClarifyMultiSelectAnswer(
                listOf(" prod ", "dev", "prod", "", "custom, value"),
            ),
        )
    }

    @Test
    fun `zero multi select answers encode as an empty array`() {
        assertEquals("[]", encodeClarifyMultiSelectAnswer(emptyList()))
    }
}

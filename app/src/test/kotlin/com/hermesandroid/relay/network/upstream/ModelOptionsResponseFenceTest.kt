package com.hermesandroid.relay.network.upstream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOptionsResponseFenceTest {
    @Test
    fun acceptsOnlySameGenerationAndProfile() {
        assertTrue(isCurrentModelOptionsResponse(4, 4, "connection::alpha", "connection::alpha"))
        assertFalse(isCurrentModelOptionsResponse(3, 4, "connection::alpha", "connection::alpha"))
        assertFalse(isCurrentModelOptionsResponse(4, 4, "connection::alpha", "connection::beta"))
    }
}

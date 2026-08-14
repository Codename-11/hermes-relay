package com.hermesandroid.relay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class NavRouteRequestTest {
    @Test
    fun requestBeforeCollector_isRetainedAndConsumedOnce() = runBlocking {
        assertTrue(NavRouteRequest.tryRequest("chat?proactiveChatId=phone"))

        assertEquals(
            "chat?proactiveChatId=phone",
            withTimeout(1_000) { NavRouteRequest.requests.first() },
        )
        assertNull(withTimeoutOrNull(50) { NavRouteRequest.requests.first() })
    }
}

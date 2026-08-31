package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.Connection
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionRemovalPresentationTest {
    @Test
    fun `confirmed removal disappears before cleanup completes`() {
        val first = connection("first")
        val second = connection("second")

        assertEquals(
            listOf("second"),
            visibleConnectionsDuringRemoval(
                connections = listOf(first, second),
                removingIds = setOf("first"),
            ).map { it.id },
        )
        assertEquals(
            listOf("first", "second"),
            visibleConnectionsDuringRemoval(
                connections = listOf(first, second),
                removingIds = emptySet(),
            ).map { it.id },
        )
    }

    private fun connection(id: String) = Connection(
        id = id,
        label = id,
        apiServerUrl = "",
        relayUrl = "",
        tokenStoreKey = Connection.buildTokenStoreKey(id),
    )
}

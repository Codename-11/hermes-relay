package com.hermesandroid.relay.network.relay

import com.hermesandroid.relay.network.relay.models.Envelope
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelMultiplexerSupervisedUpdateTest {
    @Test fun `supervised update acknowledgement reaches system auth handler`() {
        val multiplexer = ChannelMultiplexer()
        val received = mutableListOf<Envelope>()
        multiplexer.registerHandler("system") { received += it }

        val acknowledgement = Envelope(
            channel = "system",
            type = "supervised.updated",
            id = "update-1",
        )
        multiplexer.route(acknowledgement)

        assertEquals(listOf(acknowledgement), received)
    }

    @Test fun `correlated system error reaches system auth handler`() {
        val multiplexer = ChannelMultiplexer()
        val received = mutableListOf<Envelope>()
        multiplexer.registerHandler("system") { received += it }

        val error = Envelope(channel = "system", type = "error", id = "update-2")
        multiplexer.route(error)

        assertEquals(listOf(error), received)
    }
}

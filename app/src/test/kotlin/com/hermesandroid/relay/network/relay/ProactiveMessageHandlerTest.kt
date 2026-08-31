package com.hermesandroid.relay.network.relay

import android.content.Context
import com.hermesandroid.relay.network.relay.models.Envelope
import com.hermesandroid.relay.notifications.ProactiveMessageNotifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProactiveMessageHandlerTest {
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        mockkObject(ProactiveMessageNotifier)
        every {
            ProactiveMessageNotifier.notify(any(), any(), any(), any(), any())
        } returns 42
    }

    @After
    fun tearDown() {
        unmockkObject(ProactiveMessageNotifier)
    }

    @Test
    fun `notification surfacing notifies even when matching Thread is open`() {
        val persisted = mutableListOf<ProactiveMessage>()
        val handler = ProactiveMessageHandler(context, toInbox = persisted::add).apply {
            injectIntoThread = { true }
        }

        handler.onMessage(messageEnvelope(surfacing = "notification"))

        assertEquals(1, persisted.size)
        assertEquals(42, persisted.single().notificationId)
        verify(exactly = 1) {
            ProactiveMessageNotifier.notify(context, "Hermes", "ready", "m-1", "phone")
        }
    }

    @Test
    fun `inbox surfacing persists silently`() {
        val persisted = mutableListOf<ProactiveMessage>()
        val handler = ProactiveMessageHandler(context, toInbox = persisted::add).apply {
            injectIntoThread = { true }
        }

        handler.onMessage(messageEnvelope(surfacing = "inbox"))

        verify(exactly = 0) {
            ProactiveMessageNotifier.notify(any(), any(), any(), any(), any())
        }
        assertEquals(null, persisted.single().notificationId)
    }

    @Test
    fun `session surfacing suppresses notification only when a destination accepts`() {
        val accepted = ProactiveMessageHandler(context, toInbox = {}).apply {
            injectIntoThread = { false }
            toSession = { true }
        }
        accepted.onMessage(messageEnvelope(surfacing = "session"))
        verify(exactly = 0) {
            ProactiveMessageNotifier.notify(any(), any(), any(), any(), any())
        }

        val unavailable = ProactiveMessageHandler(context, toInbox = {}).apply {
            injectIntoThread = { false }
            toSession = { false }
        }
        unavailable.onMessage(messageEnvelope(surfacing = "session"))
        verify(exactly = 1) {
            ProactiveMessageNotifier.notify(context, "Hermes", "ready", "m-1", "phone")
        }
    }

    @Test
    fun `queued delivery is persisted with provenance and summarized once`() {
        val persisted = mutableListOf<ProactiveMessage>()
        val summaries = mutableListOf<Int>()
        val handler = ProactiveMessageHandler(context, toInbox = persisted::add).apply {
            onBacklogDelivered = summaries::add
        }

        handler.onMessage(messageEnvelope(surfacing = "inbox", queuedDelivery = true))
        handler.onMessage(
            Envelope(
                channel = "proactive",
                type = "proactive.backlog.complete",
                payload = buildJsonObject { put("count", JsonPrimitive(1)) },
            ),
        )

        assertTrue(persisted.single().arrivedWhileAway)
        assertEquals(listOf(1), summaries)
    }

    private fun messageEnvelope(
        surfacing: String,
        queuedDelivery: Boolean = false,
    ) = Envelope(
        channel = "proactive",
        type = "phone.message",
        payload = buildJsonObject {
            put("message_id", JsonPrimitive("m-1"))
            put("chat_id", JsonPrimitive("phone"))
            put("text", JsonPrimitive("ready"))
            put("title", JsonPrimitive("Hermes"))
            put("surfacing", JsonPrimitive(surfacing))
            if (queuedDelivery) put("queued_delivery", JsonPrimitive(true))
        },
    )
}

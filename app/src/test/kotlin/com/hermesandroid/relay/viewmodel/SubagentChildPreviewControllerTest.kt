package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.GatewayChildWatch
import com.hermesandroid.relay.network.upstream.models.MessageItem
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubagentChildPreviewControllerTest {
    @Test
    fun `pre-ack live callbacks append after hydrated history`() = runTest {
        val client = mockk<GatewayChatClient>()
        val controller = SubagentChildPreviewController(
            scope = this,
            openWatch = { _, _, _, callbacks ->
                callbacks.onStart()
                callbacks.onTextDelta("live")
                callbacks.onComplete()
                Result.success(watch(messages = listOf(message("history")), running = true))
            },
        )

        controller.open(activity(), client, "parent", "scope", true) { true }
        advanceUntilIdle()

        val state = controller.state.value!!
        assertTrue(state.messages.any { it.content == "history" })
        assertTrue(state.messages.any { it.content == "live" })
        assertFalse(state.running)
        assertFalse(state.status == "completed")
    }

    @Test
    fun `dismissed in-flight open closes the late exact watch without publishing`() = runTest {
        val client = mockk<GatewayChatClient>()
        val acknowledgement = CompletableDeferred<GatewayChildWatch>()
        var closed: GatewayChildWatch? = null
        val controller = SubagentChildPreviewController(
            scope = this,
            openWatch = { _, _, _, _ -> Result.success(acknowledgement.await()) },
            closeWatch = { _, watch ->
                closed = watch
                Result.success(Unit)
            },
        )

        controller.open(activity(), client, "parent", "scope", true) { true }
        runCurrent()
        controller.close()
        val late = watch()
        acknowledgement.complete(late)
        advanceUntilIdle()

        assertEquals(late, closed)
        assertNull(controller.state.value)
    }

    private fun activity() = SubagentActivity(
        laneId = 0,
        turnId = "turn",
        taskIndex = 0,
        taskCount = 1,
        goal = "Inspect",
        phase = SubagentActivityPhase.PROGRESS,
        childSessionId = "child-stored",
        profile = "default",
    )

    private fun message(text: String) = MessageItem(
        role = "assistant",
        content = JsonPrimitive(text),
    )

    private fun watch(
        messages: List<MessageItem> = emptyList(),
        running: Boolean = false,
    ) = GatewayChildWatch(
        storedSessionId = "child-stored",
        liveSessionId = "child-live",
        profile = "default",
        generation = 1,
        messages = messages,
        historyTruncated = false,
        running = running,
        status = if (running) "streaming" else "idle",
    )
}

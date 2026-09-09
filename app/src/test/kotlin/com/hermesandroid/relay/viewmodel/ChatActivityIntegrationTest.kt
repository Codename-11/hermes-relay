package com.hermesandroid.relay.viewmodel

import android.os.Handler
import android.os.Looper
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.ChatActivityPhase
import com.hermesandroid.relay.data.InMemoryChatActivityStore
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.projectChatActivityReceipts
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.GatewayClientHarness
import com.hermesandroid.relay.network.upstream.HermesApiClient
import com.hermesandroid.relay.network.upstream.models.MessageItem
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatActivityIntegrationTest {
    private lateinit var harness: GatewayClientHarness
    private lateinit var gatewayScope: CoroutineScope
    private lateinit var client: GatewayChatClient
    private lateinit var socket: WebSocket
    private lateinit var handler: ChatHandler
    private lateinit var viewModel: ChatViewModel
    private val owner = AgentDisplay.profileContextKey("connection-a", "research")
    @Volatile private var history: List<MessageItem> = emptyList()

    @Before
    fun setUp() {
        harness = GatewayClientHarness()
        // This harness has a fixed process list for process-controller tests.
        // Keep this delegation-only lane independent of those unrelated rows.
        harness.methodNotFound.add("process.list")
        harness.resumeLiveSessionIds["child-session"] = "child-live"
        gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        client = GatewayChatClient(
            initialDashboardClient = DashboardApiClient(
                harness.server.url("/").toString().trimEnd('/'), OkHttpClient(),
            ),
            okHttpClient = OkHttpClient(),
            callbackDispatcher = { block -> Handler(Looper.getMainLooper()).post(block) },
            scope = gatewayScope,
        )
        handler = ChatHandler().also { it.setSessionId(SESSION) }
        viewModel = ChatViewModel().also {
            it.initialize(HermesApiClient(harness.server.url("/").toString(), "test-key"), handler)
            it.streamingEndpoint = "gateway"
            it.setSelectedProfileProvider { Profile(name = "research", model = "model") }
            it.setSessionProfileNameProvider { "research" }
            it.setProfileMessageLoader { Result.success(history) }
            it.setChatActivityStore(InMemoryChatActivityStore())
            it.switchProfileContext(owner, SESSION)
            it.updateGatewayClient(client)
        }
        assertTrue(runBlocking { client.prewarmAwait(SESSION) })
        socket = harness.awaitServerSocket()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        viewModel.updateGatewayClient(null)
        client.shutdown()
        gatewayScope.cancel()
        harness.shutdown()
    }

    @Test
    fun detachedCompletionKeepsHistoryEntryThroughWakeAndClosesOnProfileSwitch() {
        viewModel.sendMessage("Inspect the project")
        harness.awaitRpc("prompt.submit")
        emit("message.start")
        emit("subagent.start", childPayload())
        awaitCondition { viewModel.activityRecords.value.singleOrNull()?.phase == ChatActivityPhase.RUNNING }

        history = listOf(row("prompt", "user", "Inspect the project"), row("parent", "assistant", "Children launched."))
        emit("message.complete", buildJsonObject {
            put("text", "Children launched.")
            put("status", "complete")
        })
        awaitCondition { !handler.isStreaming.value }
        assertEquals(ChatActivityPhase.RUNNING, viewModel.activityRecords.value.single().phase)

        emit("subagent.complete", childPayload(completed = true))
        awaitCondition { viewModel.activityRecords.value.singleOrNull()?.phase == ChatActivityPhase.COMPLETE }
        val completed = viewModel.activityRecords.value.single()
        assertEquals("delegation-a", completed.sourceId)
        assertEquals("child-session", completed.children.single().childSessionId)
        assertTrue(viewModel.openRetainedActivity(completed))
        assertEquals(completed.id, viewModel.retainedActivityPreview.value?.record?.id)
        val promptCount = harness.rpcLog.count { it.first == "prompt.submit" }
        viewModel.openSubagentChildPreview(completed.previewActivities().single().stableKey)
        awaitCondition { viewModel.subagentChildPreview.value?.childWatchAvailable == true }
        val childResume = harness.rpcLog.single {
            it.first == "session.resume" && it.second["session_id"] == JsonPrimitive("child-session")
        }.second
        assertEquals(JsonPrimitive(true), childResume["lazy"])
        assertEquals(JsonPrimitive("research"), childResume["profile"])
        assertEquals("live-resumed", client.currentLiveSessionId(SESSION))
        assertEquals(promptCount, harness.rpcLog.count { it.first == "prompt.submit" })

        val receipt = row("receipt", "user", "[ASYNC DELEGATION COMPLETE — delegation-a]").copy(
            rowId = 73,
            displayKind = "async_delegation_complete",
            displayMetadata = buildJsonObject {
                put("delegation_id", "delegation-a")
                put("task_count", 1)
                put("completed_count", 1)
                put("failed_count", 0)
            },
        )
        history = history + receipt + row("wake", "assistant", "Inspection complete.")
        emit("message.start")
        emit("message.complete", buildJsonObject {
            put("text", "Inspection complete.")
            put("status", "complete")
        })
        awaitCondition { handler.messages.value.any { it.id == "receipt" } }
        assertEquals(completed.id, viewModel.activityRecords.value.single().id)

        val raw = handler.messages.value
        val canonical = raw.single { it.id == "receipt" }
        val projected = projectChatActivityReceipts(raw, viewModel.activityRecords.value, owner, SESSION)
        val displayed = projected.single { it.activityRecord != null }
        assertEquals(canonical.id, displayed.id)
        assertEquals(canonical.uiKey, displayed.uiKey)
        assertEquals(73L, displayed.rowId)
        assertEquals(canonical.content, displayed.content)
        assertEquals(completed.id, displayed.activityRecord?.id)
        assertEquals(raw, handler.messages.value)
        assertTrue(handler.messages.value.none { it.activityRecord != null || it.id.startsWith("activity:") })

        assertFalse(viewModel.openRetainedActivity(completed.copy(scopeKey = "foreign-owner")))
        assertFalse(viewModel.openRetainedActivity(completed.copy(sessionId = "foreign-session")))
        assertNotNull(viewModel.retainedActivityPreview.value)
        viewModel.switchProfileContext(AgentDisplay.profileContextKey("connection-a", "other"), SESSION)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.activityRecords.value.isEmpty())
        assertNull(viewModel.retainedActivityPreview.value)
        assertNull(viewModel.subagentChildPreview.value)
        assertFalse(viewModel.openRetainedActivity(completed))
    }

    private fun row(id: String, role: String, content: String) = MessageItem(
        id = id, sessionId = SESSION, role = role, content = JsonPrimitive(content),
    )

    private fun childPayload(completed: Boolean = false) = buildJsonObject {
        put("delegation_id", "delegation-a")
        put("subagent_id", "child-a")
        put("child_session_id", "child-session")
        put("task_index", 0)
        put("task_count", 1)
        put("goal", "Inspect the project")
        if (completed) {
            put("status", "completed")
            put("summary", "Inspected the project")
        }
    }

    private fun emit(type: String, payload: JsonObject? = null) {
        assertTrue(socket.send(harness.eventFrame(type, payload, "live-resumed")))
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("Activity=${viewModel.activityRecords.value}; messages=${handler.messages.value}", condition())
    }

    companion object {
        private const val SESSION = "stored-session"
    }
}

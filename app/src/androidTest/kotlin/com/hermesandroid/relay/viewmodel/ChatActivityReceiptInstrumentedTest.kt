package com.hermesandroid.relay.viewmodel

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.ChatActivityPhase
import com.hermesandroid.relay.data.InMemoryChatActivityStore
import com.hermesandroid.relay.data.projectChatActivityReceipts
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.HermesApiClient
import com.hermesandroid.relay.ui.components.ChatActivityReceipt
import com.hermesandroid.relay.ui.components.GatewayBackgroundProcessSheet
import com.hermesandroid.relay.ui.components.GatewayBackgroundProcessStrip
import com.hermesandroid.relay.ui.components.SubagentPreviewVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Gateway callbacks drive production activity surfaces through Android lifecycle changes. */
class ChatActivityReceiptInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fixture: AndroidGatewayContractFixture
    private lateinit var gatewayScope: CoroutineScope
    private lateinit var gateway: GatewayChatClient
    private lateinit var handler: ChatHandler
    private lateinit var viewModel: ChatViewModel
    private lateinit var socket: WebSocket
    private val owner = AgentDisplay.profileContextKey("fixture-connection", "research")

    @Before
    fun setUp() {
        fixture = AndroidGatewayContractFixture().also { it.profileName = "research" }
        gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpClient()
        gateway = GatewayChatClient(
            initialDashboardClient = DashboardApiClient(fixture.server.url("/").toString().trimEnd('/'), okHttpClient = http),
            okHttpClient = http,
            callbackDispatcher = { block -> Handler(Looper.getMainLooper()).post(block) },
            scope = gatewayScope,
            reconnectJitterUnit = { 0.0 },
        )
        handler = ChatHandler().also { it.setSessionId(STORED_SESSION_ID) }
        viewModel = ChatViewModel().also {
            it.initialize(HermesApiClient(fixture.server.url("/").toString(), "fixture-key"), handler)
            it.streamingEndpoint = "gateway"
            it.setSessionProfileNameProvider { "research" }
            it.setProfileMessageLoader { Result.success(emptyList()) }
            it.setChatActivityStore(InMemoryChatActivityStore())
            it.switchProfileContext(owner, STORED_SESSION_ID)
            it.updateGatewayClient(gateway)
            it.setChatVisible(true)
        }
        compose.setContent {
            val messages by viewModel.messages.collectAsStateWithLifecycle()
            val records by viewModel.activityRecords.collectAsStateWithLifecycle()
            val children by viewModel.subagentActivities.collectAsStateWithLifecycle()
            val retained by viewModel.retainedActivityPreview.collectAsStateWithLifecycle()
            val childPreview by viewModel.subagentChildPreview.collectAsStateWithLifecycle()
            val session by viewModel.currentSessionId.collectAsStateWithLifecycle()
            var sheetOpen by remember { mutableStateOf(false) }
            MaterialTheme {
                Column {
                    GatewayBackgroundProcessStrip(
                        processes = emptyList(), subagentActivities = children,
                        subagentPreviewVisibility = SubagentPreviewVisibility(), loading = false,
                        onClick = { viewModel.openCurrentActivityPreview(); sheetOpen = true },
                        modifier = Modifier.testTag("active-activity"),
                    )
                    projectChatActivityReceipts(messages, records, owner, session).forEach { message ->
                        message.activityRecord?.let { record ->
                            ChatActivityReceipt(
                                record = record,
                                onClick = { sheetOpen = viewModel.openRetainedActivity(record) },
                                modifier = Modifier.testTag("activity-receipt"),
                            )
                        }
                    }
                }
                if (sheetOpen) {
                    GatewayBackgroundProcessSheet(
                        processes = retained?.processes.orEmpty(),
                        subagentActivities = retained?.record?.previewActivities() ?: children,
                        subagentChildPreview = childPreview,
                        subagentPreviewVisibility = SubagentPreviewVisibility(),
                        loading = false, stoppingProcessIds = emptySet(),
                        onRefresh = viewModel::refreshBackgroundProcesses,
                        onStop = viewModel::stopBackgroundProcess,
                        onDismissProcess = viewModel::dismissBackgroundProcess,
                        onOpenSubagentChild = viewModel::openSubagentChildPreview,
                        onDismiss = { viewModel.closeActivityPreview(); sheetOpen = false },
                        readOnlyHistory = retained != null,
                        historyNotice = "Recorded activity. Available child history is read-only.",
                    )
                }
            }
        }
        assertTrue(runBlocking { gateway.prewarmAwait(STORED_SESSION_ID) })
        socket = fixture.awaitServerSocket()
        fixture.awaitRpc("session.resume")
    }

    @After
    fun tearDown() {
        viewModel.updateGatewayClient(null)
        gateway.shutdown()
        gatewayScope.cancel()
        fixture.shutdown()
    }

    @Test
    fun detachedCompletionLeavesReopenableReceiptAcrossActivityResume() {
        viewModel.sendMessage("Delegate a background task")
        fixture.awaitRpc("prompt.submit")
        socket.send(fixture.event("message.start", null, LIVE_SESSION_ID))
        socket.send(fixture.event("subagent.start", buildJsonObject {
            put("subagent_id", "receipt-child")
            put("delegation_id", "receipt-delegation")
            put("task_count", 1)
            put("goal", "Inspect activity lifecycle")
        }, LIVE_SESSION_ID))
        compose.waitUntil(5_000) { viewModel.subagentActivities.value.size == 1 }
        compose.onNodeWithTag("active-activity").assertIsDisplayed()
        socket.send(fixture.event("message.complete", buildJsonObject { put("text", "Launched") }, LIVE_SESSION_ID))
        compose.waitUntil(5_000) { !handler.isStreaming.value }
        compose.onNodeWithTag("active-activity").assertIsDisplayed()

        socket.send(fixture.event("subagent.complete", buildJsonObject {
            put("subagent_id", "receipt-child")
            put("delegation_id", "receipt-delegation")
            put("status", "completed")
        }, LIVE_SESSION_ID))
        compose.waitUntil(5_000) { viewModel.activityRecords.value.singleOrNull()?.phase == ChatActivityPhase.COMPLETE }
        compose.onNodeWithTag("active-activity").assertDoesNotExist()
        compose.onNodeWithTag("activity-receipt").assertIsDisplayed().performClick()
        compose.onNodeWithText("Chat activity").assertIsDisplayed()
        compose.onNodeWithText("Recorded activity. Available child history is read-only.").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close activity preview").performClick()
        compose.onNodeWithTag("activity-receipt").assertIsDisplayed()

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("active-activity").assertDoesNotExist()
        compose.onNodeWithTag("activity-receipt").assertIsDisplayed().performClick()
        compose.onNodeWithText("Chat activity").assertIsDisplayed()
    }

    private companion object {
        const val STORED_SESSION_ID = "20260821_120000_fixture"
        const val LIVE_SESSION_ID = "fixture-live-1"
    }
}

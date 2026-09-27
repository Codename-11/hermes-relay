package com.hermesandroid.relay.viewmodel

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.HermesApiClient
import com.hermesandroid.relay.ui.components.MessageBubble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Production socket, ViewModel, transcript, Compose and IME actions on a virtual device. */
class ClarifyBatchInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var fixture: AndroidGatewayContractFixture
    private lateinit var scope: CoroutineScope
    private lateinit var gateway: GatewayChatClient
    private lateinit var viewModel: ChatViewModel
    private lateinit var handler: ChatHandler
    private lateinit var socket: WebSocket

    @Before fun setUp() {
        fixture = AndroidGatewayContractFixture()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpClient()
        gateway = GatewayChatClient(
            initialDashboardClient = DashboardApiClient(fixture.server.url("/").toString().trimEnd('/'), http),
            okHttpClient = http, scope = scope,
            callbackDispatcher = { Handler(Looper.getMainLooper()).post(it) },
        )
        handler = ChatHandler().also { it.setSessionId("20260821_120000_fixture") }
        viewModel = ChatViewModel().also {
            it.initialize(HermesApiClient(fixture.server.url("/").toString(), "fixture-key"), handler)
            it.streamingEndpoint = "gateway"
            it.setProfileMessageLoader { Result.success(emptyList()) }
            it.updateGatewayClient(gateway)
        }
        compose.setContent {
            val messages by handler.messages.collectAsStateWithLifecycle()
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(messages.size, key = { messages[it].id }) { index ->
                        MessageBubble(messages[index], showTimestamps = false,
                            onCardInput = viewModel::answerAsk, animationEnabled = false)
                    }
                }
            }
        }
        assertTrue(runBlocking { gateway.prewarmAwait("20260821_120000_fixture") })
        socket = fixture.awaitServerSocket()
    }

    @After fun tearDown() {
        viewModel.updateGatewayClient(null)
        gateway.shutdown()
        scope.cancel()
        fixture.shutdown()
    }

    @Test fun confirmedProgressSurvivesLifecycleAndCustomAnswerUsesIme() = exerciseBatch(false)

    @Test fun nativeProgressSurvivesLifecycleAndCustomAnswerUsesIme() = exerciseBatch(true)

    @Test fun nativeReplayRestoresLockedQuestionAfterSocketReplacement() {
        compose.runOnIdle { viewModel.sendMessage("Ask two questions") }
        fixture.awaitRpc("prompt.submit")
        val frame = Json.parseToJsonElement("""{
            "id":"srq-replay", "method":"clarify", "params":{"session_id":"fixture-live-1","questions":[
              {"qid":"route/a","question":"Which route?","choices":["Canary","Immediate"]},
              {"qid":"notes:b","question":"Anything else?"}]}}
        """) as JsonObject
        socket.send(frame.toString())
        compose.waitUntil(10_000) { viewModel.pendingAsk.value != null }
        compose.onNodeWithText("Canary").performClick()
        compose.waitUntil(10_000) { viewModel.pendingAsk.value?.ask?.answers?.get("route/a") == "Canary" }
        fixture.recoveryRunning = true
        fixture.openRequests = kotlinx.serialization.json.JsonArray(listOf(JsonObject(frame + ("params" to
            JsonObject((frame["params"] as JsonObject) + ("answers" to JsonObject(mapOf("route/a" to JsonPrimitive("Canary")))))))))
        socket.close(1001, "fixture replay")
        fixture.awaitServerSocket()
        fixture.awaitRpcCount("client.capabilities", 2)
        fixture.awaitRpc("session.activate")
        compose.waitUntil(10_000) { gateway.connectionState.value == com.hermesandroid.relay.network.upstream.GatewayConnectionState.Ready }
        compose.onNodeWithText("Question 2 of 2").assertIsDisplayed()
        compose.onNodeWithText("Skip").performClick()
        compose.waitUntil(10_000) { viewModel.pendingAsk.value == null }
        assertEquals(2, fixture.rpcCount("clarify.lock"))
        assertEquals(0, fixture.rpcCount("clarify.respond"))
    }

    @Test fun nativeCancellationAndUnsupportedMethodsDoNotLeaveInputsWaiting() {
        compose.runOnIdle { viewModel.sendMessage("Ask a question") }
        fixture.awaitRpc("prompt.submit")
        socket.send("""{"id":7,"method":"clarify","params":{"session_id":"fixture-live-1","question":"Continue?"}}""")
        compose.waitUntil(10_000) { viewModel.pendingAsk.value != null }
        socket.send(fixture.event("request.cancel", Json.parseToJsonElement("""{"id":7,"method":"clarify","reason":"timeout"}""") as JsonObject, "fixture-live-1"))
        compose.waitUntil(10_000) { viewModel.pendingAsk.value == null }
        socket.send("""{"id":"srq-tour","method":"tour","params":{"session_id":"fixture-live-1","action":"start"}}""")
        val response = fixture.serverResponses.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: error("No unsupported response")
        assertEquals(JsonPrimitive(-32601), (response["error"] as JsonObject)["code"])
        compose.onNodeWithContentDescription("Type an answer…").assertDoesNotExist()
    }

    @Test fun nativeAnswerCannotCrossSessionSwitch() = verifySwitchFence(false)

    @Test fun nativeAnswerCannotCrossProfileSwitch() = verifySwitchFence(true)

    private fun verifySwitchFence(profile: Boolean) {
        compose.runOnIdle { viewModel.sendMessage("Ask a question") }
        fixture.awaitRpc("prompt.submit")
        socket.send("""{"id":"srq-owner","method":"clarify","params":{"session_id":"fixture-live-1","question":"Continue?"}}""")
        compose.waitUntil(10_000) { viewModel.pendingAsk.value != null }
        val pending = requireNotNull(viewModel.pendingAsk.value)
        compose.runOnIdle {
            if (profile) viewModel.switchProfileContext("fixture-other-profile", null)
            else viewModel.switchSession("fixture-other-session")
        }
        compose.waitUntil(10_000) { viewModel.pendingAsk.value == null }
        compose.runOnIdle { viewModel.answerAsk(pending.messageId, pending.cardKey, "stale answer") }
        compose.waitForIdle()
        assertTrue(fixture.serverResponses.isEmpty())
        assertEquals(0, fixture.rpcCount("clarify.respond"))
        assertEquals(0, fixture.rpcCount("clarify.lock"))
    }

    private fun exerciseBatch(native: Boolean) {
        compose.runOnIdle { viewModel.sendMessage("Ask two questions") }
        fixture.awaitRpc("prompt.submit")
        val payload = Json.parseToJsonElement("""
            {"request_id":"batch-device","questions":[
              {"qid":"route/a","question":"Which route?","choices":["Canary","Immediate"]},
              {"qid":"notes:b","question":"Anything else?","choices":null}
            ]}
        """) as JsonObject
        val frame = if (native) JsonObject(mapOf(
            "jsonrpc" to JsonPrimitive("2.0"), "id" to JsonPrimitive("srq-device"),
            "method" to JsonPrimitive("clarify"), "params" to JsonObject(payload - "request_id" +
                ("session_id" to JsonPrimitive("fixture-live-1"))),
        )).toString() else fixture.event("clarify.request", payload, "fixture-live-1")
        socket.send(frame)
        if (native) socket.send(frame) // Duplicate delivery must keep one card and one answer per qid.
        val responseMethod = if (native) "clarify.lock" else "clarify.respond"
        compose.waitUntil(10_000) { viewModel.pendingAsk.value != null }
        compose.onNodeWithText("Canary").performClick()
        compose.waitUntil(10_000) { viewModel.pendingAsk.value?.ask?.answers?.get("route/a") == "Canary" }
        assertEquals(JsonPrimitive("route/a"), fixture.awaitRpc(responseMethod)["question_id"])
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Question 2 of 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Type an answer…").apply {
            performClick()
            performTextInput("  Keep rollback ready  ")
            performImeAction()
        }
        compose.waitUntil(10_000) { viewModel.pendingAsk.value == null }
        compose.onNodeWithText("All questions answered").assertIsDisplayed()
        assertEquals(2, fixture.rpcCount(responseMethod))
        assertEquals(1, fixture.rpcCount("prompt.submit"))
    }
}

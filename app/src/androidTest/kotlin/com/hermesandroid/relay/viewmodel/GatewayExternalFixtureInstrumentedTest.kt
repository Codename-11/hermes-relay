package com.hermesandroid.relay.viewmodel

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Opt-in physical-device/emulator adapter for the shared Python fixture.
 *
 * Pass `-e gatewayFixtureBaseUrl http://127.0.0.1:8765` after exposing the
 * host fixture with `adb reverse`. With no argument this test alone is skipped;
 * the embedded regression remains fully standalone.
 */
class GatewayExternalFixtureInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var gatewayScope: CoroutineScope? = null
    private var gatewayClient: GatewayChatClient? = null
    private var viewModel: ChatViewModel? = null

    @After
    fun tearDown() {
        viewModel?.updateGatewayClient(null)
        gatewayClient?.shutdown()
        gatewayScope?.cancel()
    }

    @Test
    fun terminalGapActivate_externalFixtureRecoversFromAuthoritativeHttpHistory() {
        val fixtureBaseUrl = InstrumentationRegistry.getArguments()
            .getString(ARG_FIXTURE_BASE_URL)
            ?.trim()
            ?.trimEnd('/')
        assumeTrue(
            "Pass -e $ARG_FIXTURE_BASE_URL <url> to run the external fixture lane",
            !fixtureBaseUrl.isNullOrBlank(),
        )
        requireNotNull(fixtureBaseUrl)

        val okHttp = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        val initialState = readFixtureJson(okHttp, "$fixtureBaseUrl/__fixture__/state")
        assertEquals("terminal_gap_activate", initialState["scenario"]?.jsonString())
        assertEquals("1", initialState["remaining_turns"].toString())
        val dashboard = DashboardApiClient(fixtureBaseUrl, okHttp)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { gatewayScope = it }
        val gateway = GatewayChatClient(
            initialDashboardClient = dashboard,
            okHttpClient = okHttp,
            callbackDispatcher = { block -> Handler(Looper.getMainLooper()).post(block) },
            scope = scope,
            reconnectJitterUnit = { 0.0 },
        ).also { gatewayClient = it }
        val handler = ChatHandler().also { it.setSessionId(STORED_SESSION_ID) }
        val vm = ChatViewModel().also {
            // Deliberately omit HermesApiClient: this lane has no API-server
            // fallback surface, so a passing turn proves Gateway ownership.
            it.initialize(null, handler)
            it.streamingEndpoint = "gateway"
            it.setProfileMessageLoaderWithMode { profile, sessionId, mode ->
                dashboard.getSessionMessages(sessionId, profile, mode)
            }
            it.updateGatewayClient(gateway)
            it.setChatVisible(true)
        }.also { viewModel = it }

        compose.setContent {
            val messages by vm.messages.collectAsStateWithLifecycle()
            val streaming by vm.isStreaming.collectAsStateWithLifecycle()
            MaterialTheme {
                Column(Modifier.testTag("external-contract-transcript")) {
                    Text(
                        text = if (streaming) "STREAMING" else "IDLE",
                        modifier = Modifier.testTag("external-stream-state"),
                    )
                    messages.forEach { message ->
                        Text(
                            text = "${message.role.name}:${message.content}",
                            modifier = Modifier.testTag("external-message-${message.id}"),
                        )
                    }
                }
            }
        }

        assertTrue(runBlocking { gateway.prewarmAwait(STORED_SESSION_ID) })
        vm.sendMessage("Exercise terminal gap.")

        compose.waitUntil(10_000) {
            !handler.isStreaming.value &&
                !gateway.hasActiveTurn() &&
                handler.messages.value.any {
                    it.role == MessageRole.ASSISTANT && it.content == AUTHORITATIVE_ANSWER
                }
        }

        compose.onNodeWithTag("external-contract-transcript").assertIsDisplayed()
        compose.onNodeWithTag("external-stream-state").assertTextEquals("IDLE")
        compose.onAllNodesWithText("${MessageRole.ASSISTANT.name}:$AUTHORITATIVE_ANSWER")
            .assertCountEquals(1)

        val messages = handler.messages.value
        assertEquals(
            1,
            messages.count {
                it.role == MessageRole.ASSISTANT && it.content == AUTHORITATIVE_ANSWER
            },
        )
        assertEquals(1, messages.count { it.role == MessageRole.USER })
        assertFalse(messages.any { it.isStreaming || it.isThinkingStreaming })
        assertEquals("gateway", vm.streamingEndpoint)

        val evidence = readFixtureJson(okHttp, "$fixtureBaseUrl/__fixture__/evidence")
        assertEquals("terminal_gap_activate", evidence["scenario"]?.jsonString())
        val entries = evidence["entries"] as? JsonArray ?: JsonArray(emptyList())
        assertEquals(1, entries.rpcCount("prompt.submit"))
        assertEquals(1, entries.rpcCount("session.activate"))

        val state = readFixtureJson(okHttp, "$fixtureBaseUrl/__fixture__/state")
        assertEquals("terminal_gap_activate", state["scenario"]?.jsonString())
        assertEquals("2", state["history_rows"].toString())
    }

    private fun readFixtureJson(client: OkHttpClient, url: String): JsonObject {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "fixture HTTP ${response.code}" }
            Json.parseToJsonElement(response.body.string()).jsonObject
        }
    }

    @Test
    fun queuedStopResume_preservesWorkAcrossLifecycleAndUsesExplicitResume() {
        val base = InstrumentationRegistry.getArguments().getString(ARG_FIXTURE_BASE_URL)?.trimEnd('/')
        assumeTrue("Pass a queued_stop_resume fixture URL", !base.isNullOrBlank())
        requireNotNull(base)
        val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        assertEquals("queued_stop_resume", readFixtureJson(http, "$base/__fixture__/state")["scenario"]?.jsonString())
        val dashboard = DashboardApiClient(base, http)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { gatewayScope = it }
        val gateway = GatewayChatClient(
            initialDashboardClient = dashboard, okHttpClient = http, scope = scope,
            callbackDispatcher = { block -> Handler(Looper.getMainLooper()).post(block) },
            reconnectJitterUnit = { 0.0 },
        ).also { gatewayClient = it }
        val handler = ChatHandler().also { it.setSessionId(STORED_SESSION_ID) }
        val vm = ChatViewModel().also {
            it.initialize(null, handler)
            it.streamingEndpoint = "gateway"
            it.setProfileMessageLoaderWithMode { profile, session, mode -> dashboard.getSessionMessages(session, profile, mode) }
            it.updateGatewayClient(gateway)
            it.switchProfileContext(com.hermesandroid.relay.data.AgentDisplay.profileContextKey("fixture-queue", null), STORED_SESSION_ID)
        }.also { viewModel = it }
        compose.setContent {
            val queue by vm.queuedMessages.collectAsStateWithLifecycle()
            val paused by vm.queuePaused.collectAsStateWithLifecycle()
            com.hermesandroid.relay.ui.theme.HermesRelayTheme(themePreference = "dark") {
                androidx.compose.material3.Surface {
                    Column {
                        com.hermesandroid.relay.ui.components.ChatBusyActionSelector(
                            com.hermesandroid.relay.data.BusyMessageAction.QueueNext, {}, onStop = vm::cancelStream,
                        )
                        com.hermesandroid.relay.ui.components.ChatMessageQueue(
                            queue, paused, vm::resumeQueue, vm::clearQueue, {}, vm::removeQueuedAt, canEdit = true,
                        )
                    }
                }
            }
        }
        assertTrue(runBlocking { gateway.prewarmAwait(STORED_SESSION_ID) })
        compose.runOnIdle { vm.sendMessage("Original work") }
        compose.waitUntil(10_000) { handler.isStreaming.value && vm.steerableTurn.value }
        compose.runOnIdle {
            vm.sendMessage("Remove this follow-up", com.hermesandroid.relay.data.BusyMessageAction.QueueNext)
            vm.sendMessage("Keep this follow-up", com.hermesandroid.relay.data.BusyMessageAction.QueueNext)
        }
        compose.onAllNodesWithContentDescription("Remove queued message")[0].performClick()
        compose.onNodeWithContentDescription("Stop streaming").performClick()
        compose.onNodeWithText("Queue paused").assertIsDisplayed()
        assertEquals(listOf("Keep this follow-up"), vm.queuedMessages.value)

        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithText("Keep this follow-up").assertIsDisplayed()
        compose.onNodeWithText("Resume").performClick()
        try {
            compose.waitUntil(15_000) {
                vm.queuedMessages.value.isEmpty() && !handler.isStreaming.value &&
                    handler.messages.value.any { it.content == "Resumed follow-up." }
            }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(
                "Synthetic queue fixture did not settle: queued=${vm.queuedMessages.value.size}, " +
                    "paused=${vm.queuePaused.value}, streaming=${handler.isStreaming.value}, " +
                    "messages=${handler.messages.value.map { it.role to it.content }}, " +
                    "error=${handler.error.value}",
                error,
            )
        }
        val evidence = readFixtureJson(http, "$base/__fixture__/evidence")["entries"] as JsonArray
        assertEquals(2, evidence.rpcCount("prompt.submit"))
        assertEquals(1, evidence.rpcCount("session.interrupt"))
        assertEquals(0, evidence.rpcCount("session.redirect"))
        assertEquals("gateway", vm.streamingEndpoint)
    }

    private fun JsonArray.rpcCount(method: String): Int = count { element ->
        val entry = element as? JsonObject ?: return@count false
        entry["kind"]?.jsonString() == "rpc" && entry["method"]?.jsonString() == method
    }

    private fun kotlinx.serialization.json.JsonElement.jsonString(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val ARG_FIXTURE_BASE_URL = "gatewayFixtureBaseUrl"
        const val STORED_SESSION_ID = "20260821_120000_fixture"
        const val AUTHORITATIVE_ANSWER = "Persisted after the socket gap."
    }
}

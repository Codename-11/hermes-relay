package com.hermesandroid.relay.ui.screens

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hermesandroid.relay.data.BotGatewayRoute
import com.hermesandroid.relay.data.BotGatewayRouteKey
import com.hermesandroid.relay.data.BotRosterEntry
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.viewmodel.AndroidGatewayContractFixture
import com.hermesandroid.relay.viewmodel.ChatViewModel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** On-device proof for the route-owned first-composition collection boundary. */
class BotChatScreenBindingInstrumentedTest {
    private lateinit var fixture: AndroidGatewayContractFixture
    private lateinit var gatewayScope: CoroutineScope
    private lateinit var dashboardClient: DashboardApiClient
    private lateinit var gatewayClient: GatewayChatClient
    private lateinit var viewModel: ChatViewModel
    private lateinit var handler: ChatHandler
    private var activityScenario: ActivityScenario<BotChatBindingTestActivity>? = null

    @Before
    fun setUp() {
        fixture = AndroidGatewayContractFixture()
        gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dashboardClient = DashboardApiClient(
            baseUrl = fixture.server.url("/").toString().trimEnd('/'),
            okHttpClient = OkHttpClient(),
        )
        gatewayClient = GatewayChatClient(
            initialDashboardClient = dashboardClient,
            okHttpClient = OkHttpClient(),
            callbackDispatcher = { block -> Handler(Looper.getMainLooper()).post(block) },
            scope = gatewayScope,
            reconnectJitterUnit = { 0.0 },
        )
        viewModel = ChatViewModel()
        handler = ChatHandler()
    }

    @After
    fun tearDown() {
        activityScenario?.close()
        viewModel.updateGatewayClient(null)
        gatewayClient.shutdown()
        gatewayScope.cancel()
        dashboardClient.shutdown()
        fixture.shutdown()
    }

    @Test
    fun fastInitialHistoryRendersBeforeNavigationAndSurvivesLifecycleResume() {
        val route = BotGatewayRoute(
            key = BotGatewayRouteKey("fixture-gateway", PROFILE_NAME),
            connectionLabel = "Fixture gateway",
        )
        val bot = BotRosterEntry(
            profile = Profile(
                name = PROFILE_NAME,
                model = "fixture-model",
                description = "Fixture profile",
            ),
            displayName = "Research",
            route = route,
        )
        val scenario = ActivityScenario.launch(BotChatBindingTestActivity::class.java)
            .also { activityScenario = it }

        scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    BotChatScreen(
                        route = route,
                        bot = bot,
                        sessionId = STORED_SESSION_ID,
                        gatewayClient = gatewayClient,
                        dashboardClient = dashboardClient,
                        chatViewModel = viewModel,
                        onBack = {},
                        handlerFactory = { handler },
                        historyLoader = { _, _, _ ->
                            Result.success(
                                listOf(
                                    MessageItem(
                                        id = HISTORY_ID,
                                        sessionId = STORED_SESSION_ID,
                                        role = "assistant",
                                        content = JsonPrimitive(HISTORY_TEXT),
                                        timestamp = 1.0,
                                        finishReason = "stop",
                                    ),
                                ),
                            )
                        },
                        profileIconFlow = { _, _ -> MutableStateFlow(null) },
                    )
                }
            }
        }

        waitUntil { handler.messages.value.singleOrNull()?.content == HISTORY_TEXT }
        waitUntil { renderedTextExists(HISTORY_TEXT) }

        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitUntil { renderedTextExists(HISTORY_TEXT) }
        assertEquals(0, fixture.rpcCount("prompt.submit"))
    }

    private fun renderedTextExists(expected: String): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return root.containsText(expected)
    }

    private fun AccessibilityNodeInfo.containsText(expected: String): Boolean {
        if (text?.toString() == expected || contentDescription?.toString() == expected) return true
        return (0 until childCount).any { index -> getChild(index)?.containsText(expected) == true }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue("Condition was not satisfied within 5 seconds", condition())
    }

    private companion object {
        const val PROFILE_NAME = "research"
        const val STORED_SESSION_ID = "20260829_120000_bot_chat"
        const val HISTORY_ID = "persisted-bot-history"
        const val HISTORY_TEXT = "Durable Bot Chat history is ready."
    }
}

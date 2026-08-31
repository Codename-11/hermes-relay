package com.hermesandroid.relay.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.BotGatewayRoute
import com.hermesandroid.relay.data.BotGatewayRouteKey
import com.hermesandroid.relay.data.BotRosterEntry
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.viewmodel.ChatViewModel
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-432dpi")
class BotChatScreenBindingTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = CopyOnWriteArrayList<ScreenResources>()
    @After
    fun tearDown() {
        resources.forEach(ScreenResources::close)
    }

    @Test
    fun fastHistoryPublishedDuringInitialBindRendersWithoutNavigation() {
        val screen = resources("research")
        val handler = ChatHandler()

        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                screen.content(
                    handler = handler,
                    historyLoader = { _, _, _ ->
                        Result.success(history(screen.sessionId, FAST_HISTORY))
                    },
                )
            }
        }

        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil(5_000) { handler.messages.value.singleOrNull()?.content == FAST_HISTORY }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(FAST_HISTORY).assertIsDisplayed()

        compose.runOnIdle { handler.onTextDelta("live-tail", LIVE_TAIL) }
        compose.onNodeWithText(LIVE_TAIL).assertIsDisplayed()

        compose.runOnIdle { handler.onStreamError(HANDLER_ERROR) }
        compose.onNodeWithText(HANDLER_ERROR).assertIsDisplayed()
    }

    @Test
    fun delayedHistoryAfterCompositionRendersFromTheSameHandler() {
        val screen = resources("builder")
        val handler = ChatHandler()
        val releaseHistory = CompletableDeferred<Unit>()

        compose.setContent {
            MaterialTheme {
                screen.content(
                    handler = handler,
                    historyLoader = { _, _, _ ->
                        releaseHistory.await()
                        Result.success(history(screen.sessionId, DELAYED_HISTORY))
                    },
                )
            }
        }

        compose.waitUntil(5_000) { screen.viewModel.isLoadingHistory.value }
        compose.onNodeWithText(DELAYED_HISTORY).assertDoesNotExist()
        releaseHistory.complete(Unit)
        compose.waitUntil(5_000) { handler.messages.value.isNotEmpty() }
        compose.onNodeWithText(DELAYED_HISTORY).assertIsDisplayed()
    }

    @Test
    fun replacementHandlerRejectsLateHistoryAndOldHandlerPublications() {
        val screen = resources("operator")
        val firstHandler = ChatHandler()
        val secondHandler = ChatHandler()
        val releaseFirstHistory = CompletableDeferred<Unit>()
        val target = mutableStateOf(
            Target(
                revision = 0,
                handler = firstHandler,
                loader = { _, _, _ ->
                    releaseFirstHistory.await()
                    Result.success(history(screen.sessionId, OLD_HISTORY))
                },
            ),
        )

        compose.setContent {
            val current = target.value
            key(current.revision) {
                MaterialTheme {
                    screen.content(current.handler, current.loader)
                }
            }
        }

        compose.waitUntil(5_000) { screen.viewModel.isLoadingHistory.value }
        compose.runOnIdle {
            target.value = Target(
                revision = 1,
                handler = secondHandler,
                loader = { _, _, _ -> Result.success(history(screen.sessionId, NEW_HISTORY)) },
            )
        }
        compose.waitForIdle()
        compose.runOnIdle { assertSame(secondHandler, screen.viewModel.boundHandler) }
        compose.waitUntil(5_000) { secondHandler.messages.value.singleOrNull()?.content == NEW_HISTORY }
        compose.onNodeWithText(NEW_HISTORY).assertIsDisplayed()

        releaseFirstHistory.complete(Unit)
        compose.waitForIdle()
        assertEquals(emptyList<String>(), firstHandler.messages.value.map { it.content })
        compose.runOnIdle { firstHandler.onTextDelta("old-tail", OLD_TAIL) }
        compose.waitForIdle()
        compose.onNodeWithText(OLD_HISTORY).assertDoesNotExist()
        compose.onNodeWithText(OLD_TAIL).assertDoesNotExist()
        assertEquals(listOf(OLD_TAIL), firstHandler.messages.value.map { it.content })
        assertEquals(listOf(NEW_HISTORY), secondHandler.messages.value.map { it.content })
    }

    private fun resources(profileName: String): ScreenResources = ScreenResources(profileName).also {
        resources += it
    }

    private fun history(sessionId: String, text: String) = listOf(
        MessageItem(
            id = "history-$sessionId",
            sessionId = sessionId,
            role = "assistant",
            content = JsonPrimitive(text),
            timestamp = 1.0,
            finishReason = "stop",
        ),
    )

    private inner class ScreenResources(profileName: String) {
        val route = BotGatewayRoute(
            key = BotGatewayRouteKey("gateway-$profileName", profileName),
            connectionLabel = "Fixture gateway",
        )
        val bot = BotRosterEntry(
            profile = Profile(
                name = profileName,
                model = "fixture-model",
                description = "Fixture profile",
            ),
            displayName = profileName.replaceFirstChar(Char::uppercase),
            route = route,
        )
        val sessionId = "fixture-$profileName-session"
        val viewModel = ChatViewModel()
        private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val dashboardClient = DashboardApiClient("http://127.0.0.1:1")
        private val gatewayClient = GatewayChatClient(
            initialDashboardClient = dashboardClient,
            fixedSessionProfile = profileName,
            scope = gatewayScope,
            reconnectJitterUnit = { 0.0 },
        )

        @androidx.compose.runtime.Composable
        fun content(handler: ChatHandler, historyLoader: BotChatHistoryLoader) {
            BotChatScreen(
                route = route,
                bot = bot,
                sessionId = sessionId,
                gatewayClient = gatewayClient,
                dashboardClient = dashboardClient,
                chatViewModel = viewModel,
                onBack = {},
                handlerFactory = { handler },
                historyLoader = historyLoader,
                profileIconFlow = { _, _ -> MutableStateFlow(null) },
            )
        }

        fun close() {
            viewModel.updateGatewayClient(null)
            gatewayClient.shutdown()
            gatewayScope.cancel()
            dashboardClient.shutdown()
        }
    }

    private data class Target(
        val revision: Int,
        val handler: ChatHandler,
        val loader: BotChatHistoryLoader,
    )

    private companion object {
        const val FAST_HISTORY = "History loaded before the next frame."
        const val DELAYED_HISTORY = "History loaded after composition."
        const val LIVE_TAIL = "Live tail from the owned handler."
        const val HANDLER_ERROR = "Owned handler error"
        const val OLD_HISTORY = "Late history from the old route."
        const val OLD_TAIL = "Old handler live tail."
        const val NEW_HISTORY = "History from the replacement route."
    }
}

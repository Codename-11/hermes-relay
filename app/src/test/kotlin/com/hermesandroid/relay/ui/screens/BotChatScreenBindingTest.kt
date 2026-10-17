package com.hermesandroid.relay.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.ChatComposerDraft
import com.hermesandroid.relay.data.ChatComposerDraftKey
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
import kotlinx.coroutines.runBlocking
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
    fun reopeningKeepsTranscriptAndReadingPositionWithoutLoadingHistoryAgain() {
        val screen = resources("retained")
        val visible = mutableStateOf(true)
        val scrollState = BotChatScrollState()
        var loads = 0
        compose.setContent {
            if (visible.value) MaterialTheme {
                screen.content(
                    handler = screen.viewModel.boundHandler ?: ChatHandler(),
                    historyLoader = { _, _, _ ->
                        loads++
                        Result.success((0..40).map { index ->
                            history(screen.sessionId, "Stored message $index").single().copy(id = "row-$index")
                        })
                    },
                    scrollState = scrollState,
                )
            }
        }
        compose.waitUntil(5_000) { scrollState.positioned }
        compose.runOnIdle { scrollState.followingLatest = false }
        compose.onNodeWithTag("bot-chat-messages").performScrollToIndex(5)
        compose.waitForIdle()
        val index = scrollState.listState.firstVisibleItemIndex
        val offset = scrollState.listState.firstVisibleItemScrollOffset
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        assertEquals(1, loads)
        assertEquals(index, scrollState.listState.firstVisibleItemIndex)
        assertEquals(offset, scrollState.listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun newMessagesDoNotPullReaderAwayFromHistory() {
        val screen = resources("reader")
        val handler = ChatHandler()
        val scrollState = BotChatScrollState()
        compose.setContent {
            MaterialTheme {
                screen.content(handler, { _, _, _ ->
                    Result.success((0..40).map { index ->
                        history(screen.sessionId, "Stored message $index").single().copy(id = "row-$index")
                    })
                }, scrollState = scrollState)
            }
        }
        compose.waitUntil(5_000) { scrollState.positioned }
        compose.onNodeWithTag("bot-chat-messages").performScrollToIndex(5)
        compose.waitForIdle()
        compose.onNodeWithTag("bot-chat-messages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertFalse(scrollState.followingLatest)
        val index = scrollState.listState.firstVisibleItemIndex
        val offset = scrollState.listState.firstVisibleItemScrollOffset
        compose.runOnIdle { handler.onTextDelta("new-answer", "New message while reading history") }
        compose.waitForIdle()
        assertEquals(index, scrollState.listState.firstVisibleItemIndex)
        assertEquals(offset, scrollState.listState.firstVisibleItemScrollOffset)
        compose.onNodeWithContentDescription("Scroll to bottom").performClick()
        compose.onNodeWithText("New message while reading history").assertIsDisplayed()
        compose.runOnIdle { handler.onTextDelta("new-answer", " continuing") }
        compose.onNodeWithText("New message while reading history continuing").assertIsDisplayed()
        compose.runOnIdle { handler.onTextDelta("new-answer", "\n" + "A long streaming answer.\n".repeat(80)) }
        compose.waitUntil(5_000) {
            scrollState.listState.layoutInfo.visibleItemsInfo.any { it.key == "new-answer" && it.size > 1_000 }
        }
        compose.waitUntil(5_000) { !scrollState.listState.canScrollForward }
        compose.waitForIdle()
        assertFalse(scrollState.listState.canScrollForward)
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

        // Grok-style chrome: bot pill, named placeholder, + opens the attach sheet.
        compose.onNodeWithText("Research").assertIsDisplayed()
        compose.onNodeWithText("Message Research").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/ui-regression/bot-chat.png")
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithText("Photos").assertIsDisplayed()
        compose.onNodeWithText("Files").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/ui-regression/bot-chat-attach-sheet.png")
        compose.onNodeWithText("Files").performClick()

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

    @Test
    fun composerVoiceWaitsForLoadedBotSessionAndUsesOnlyItsCallback() {
        val screen = resources("voice-bot")
        val handler = ChatHandler()
        val releaseHistory = CompletableDeferred<Unit>()
        var starts = 0
        compose.setContent {
            MaterialTheme {
                screen.content(handler, { _, _, _ ->
                    releaseHistory.await()
                    Result.success(history(screen.sessionId, DELAYED_HISTORY))
                }, onVoice = { starts++ })
            }
        }
        compose.waitUntil(5_000) { screen.viewModel.isLoadingHistory.value }
        compose.onNodeWithTag(BOT_CHAT_VOICE_TAG).assertIsNotEnabled()
        releaseHistory.complete(Unit)
        compose.waitUntil(5_000) { !screen.viewModel.isLoadingHistory.value }
        compose.onNodeWithTag(BOT_CHAT_VOICE_TAG).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(screen.sessionId, screen.viewModel.currentSessionId.value)
            handler.onStreamError(HANDLER_ERROR)
        }
        compose.onNodeWithTag(BOT_CHAT_VOICE_TAG).assertIsNotEnabled()
    }

    @Test
    fun newAndBotIdentityOpenTheBotPicker() {
        val screen = resources("research")
        var pickerRequests = 0
        compose.setContent {
            MaterialTheme {
                screen.content(handler = ChatHandler(), historyLoader = { _, _, _ -> Result.success(emptyList()) },
                    onNewChat = { pickerRequests++ })
            }
        }
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.onNodeWithText("Research").performClick()
        compose.runOnIdle { assertEquals(2, pickerRequests) }
    }

    @Test
    fun newSavesDraftUnderTheExactBotOwner() {
        val screen = resources("research")
        val owner = ChatComposerDraftKey(screen.route.connectionId, "research", screen.sessionId)
        runBlocking { screen.viewModel.composerDraftStore.save(owner, ChatComposerDraft(text = "Unsent plan")) }
        var opened = false
        compose.setContent {
            MaterialTheme {
                screen.content(ChatHandler(), { _, _, _ -> Result.success(emptyList()) }, onNewChat = { opened = true })
            }
        }
        compose.onNodeWithText("Unsent plan").performTextInput(" update")
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil { opened }
        runBlocking {
            assertEquals("Unsent plan update", screen.viewModel.composerDraftStore.snapshot(owner).text)
            assertEquals("", screen.viewModel.composerDraftStore.snapshot(owner.copy(profileId = "other")).text)
        }
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
        fun content(handler: ChatHandler, historyLoader: BotChatHistoryLoader, onVoice: (() -> Unit)? = null, onNewChat: (() -> Unit)? = null, scrollState: BotChatScrollState = androidx.compose.runtime.remember { BotChatScrollState() }) {
            BotChatScreen(
                route = route,
                bot = bot,
                sessionId = sessionId,
                gatewayClient = gatewayClient,
                dashboardClient = dashboardClient,
                chatViewModel = viewModel,
                onBack = {},
                scrollState = scrollState,
                onVoice = onVoice,
                handlerFactory = { handler },
                historyLoader = historyLoader,
                onNewChat = onNewChat,
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

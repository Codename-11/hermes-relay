package com.hermesandroid.relay.screenshots

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.ui.screens.ChatScreen
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class ChatComposerProgressScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear() }

    @Test fun progressStaysAboveComposerWhileTranscriptScrollsAndDisappearsAtCompletion() = capture("phone", 1f)

    @Test @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun compactPhoneWithLargerText() = capture("compact", 1.3f)

    @Test @Config(qualifiers = "w720dp-h360dp-xhdpi")
    fun landscape() = capture("landscape", 1f)

    @Test fun unreadHeaderKeepsOffscreenCompletionVisible() = capture("unread-header", 1f, withUnread = true)

    private fun capture(name: String, fontScale: Float, withUnread: Boolean = false) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val connection = ConnectionViewModel(app).also { store.put("connection", it) }
        val voice = VoiceViewModel(app).also { store.put("voice", it) }
        val handler = ChatHandler().also { it.setSessionId("fixture-session") }
        repeat(24) { handler.onTextDelta("history-$it", "Earlier response $it. ".repeat(8)); handler.onStreamComplete("history-$it") }
        handler.onTextDelta("current", "The current reply is still arriving. ".repeat(8))
        val chat = ChatViewModel().also { it.initialize(null, handler); store.put("chat", it) }
        if (withUnread) {
            kotlinx.coroutines.runBlocking {
                val unread = com.hermesandroid.relay.data.ChatUnreadStore(app)
                unread.clear()
                unread.completed(com.hermesandroid.relay.data.AgentDisplay.profileContextKey("fixture-connection", "research"),
                    "other-conversation", "fixture-turn", 1, false)
            }
            chat.initializeGatewayOnly(app)
            chat.streamingEndpoint = "gateway"
            chat.switchProfileContext(com.hermesandroid.relay.data.AgentDisplay.profileContextKey("fixture-connection", null), "fixture-session")
        }

        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale),
                com.hermesandroid.relay.ui.LocalSnackbarHost provides androidx.compose.material3.SnackbarHostState(),
            ) {
                HermesRelayTheme(themePreference = "dark") {
                    ChatScreen(chatViewModel = chat, connectionViewModel = connection, voiceViewModel = voice)
                }
            }
        }
        compose.waitForIdle()
        if (withUnread) {
            compose.waitUntil(5_000) { chat.completionReceipts.value.any { it.unread } }
            compose.onNodeWithContentDescription("Unread conversations: 1", useUnmergedTree = true).assertIsDisplayed()
        }
        val progress = compose.onNodeWithTag("chat-composer-progress", useUnmergedTree = true)
        progress.assertIsDisplayed()
        val before = progress.fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("chat-input-field", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Progress must sit above the composer", before.bottom <= composer.top)
        val transcript = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        transcript.performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(before, progress.fetchSemanticsNode().boundsInRoot)
        val scrollBeforeDelta = transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle { handler.onTextDelta("current", "More output while reading history. ".repeat(8)) }
        compose.waitForIdle()
        assertEquals(scrollBeforeDelta, transcript.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        val output = File("build/ui-evidence/chat-composer-progress-$name.png").apply { parentFile?.mkdirs() }
        compose.onRoot().captureRoboImage(output.absolutePath)
        compose.runOnIdle { handler.onStreamComplete("current") }
        compose.onNodeWithTag("chat-composer-progress", useUnmergedTree = true).assertDoesNotExist()
    }
}

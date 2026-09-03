package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.BusyMessageAction
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class ChatBusyControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectorChangesIntentWithoutSendingAndKeepsStopSeparate() {
        var selected by mutableStateOf(BusyMessageAction.CorrectNow)
        var stopped = 0
        compose.setContent {
            HermesRelayTheme(themePreference = "dark") {
                ChatBusyActionSelector(selected, { selected = it }, onStop = { stopped++ })
            }
        }
        compose.onNodeWithText("Queue next").assertDoesNotExist()
        compose.onNodeWithTag("chatBusyActionTrigger").performClick()
        compose.onNodeWithTag("chatBusyAction-queue_next").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chatBusyActionDrawer").assertDoesNotExist()
        compose.onNodeWithText("Queue next").assertIsDisplayed()
        assertEquals(BusyMessageAction.QueueNext, selected)
        assertEquals(0, stopped)
        compose.onNodeWithContentDescription("Stop streaming").performClick()
        assertEquals(1, stopped)
    }

    @Test fun unavailableCorrectionKeepsQueueSelectable() {
        compose.setContent {
            HermesRelayTheme {
                ChatBusyActionSelector(BusyMessageAction.QueueNext, {}, correctionAvailable = false)
            }
        }
        compose.onNodeWithTag("chatBusyActionTrigger").performClick()
        compose.onNodeWithTag("chatBusyAction-correct_now").assertIsNotEnabled()
        compose.onNodeWithTag("chatBusyAction-queue_next").assertIsSelected()
        compose.onNodeWithTag("chatBusyActionDrawer")
            .captureRoboImage("build/ui-evidence/chat-busy-drawer.png")
    }

    @Test fun pausedQueueActionsAndComposerRenderTogether() {
        var resumed = 0
        var cleared = 0
        var draft by mutableStateOf("A correction")
        compose.setContent {
            HermesRelayTheme(themePreference = "dark") {
                Surface(Modifier.fillMaxSize()) {
                    Column(verticalArrangement = Arrangement.Bottom) {
                        ChatMessageQueue(listOf("A saved follow-up", "Another follow-up"), true,
                            { resumed++ }, { cleared++ }, {}, {}, canEdit = true)
                        ChatInputBar(
                            value = draft, onValueChange = { draft = it }, placeholder = "Message",
                            trailing = ChatInputTrailing.STEER, onSend = {}, onVoice = {}, onStop = {},
                            onAttachPhotos = {}, onAttachFiles = {}, onAttachCamera = {}, onPasteImage = {},
                            onLongPressAttach = {}, charLimit = 20_000, caption = "Changes the current response",
                            voiceReady = false, showVoiceHint = false, onVoiceHintShown = {}, isDarkTheme = true,
                            busyAction = BusyMessageAction.CorrectNow,
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Resume").assertIsDisplayed().performClick()
        compose.onNodeWithText("Clear").performClick()
        assertEquals(1, resumed)
        assertEquals(1, cleared)
        compose.onRoot().captureRoboImage("build/ui-evidence/chat-busy-controls-dark.png")

        val input = compose.onNode(hasSetTextAction())
        input.performClick()
        val inputBottom = input.fetchSemanticsNode().boundsInRoot.bottom
        val composerHeight = compose.onNodeWithTag("chatComposerForeground").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("chatBusyActionTrigger").performClick()
        compose.onNodeWithTag("chatBusyActionDrawer").assertIsDisplayed()
        compose.onNode(isDialog()).assertDoesNotExist()
        input.assertIsDisplayed().assertIsFocused()
        assertEquals(inputBottom, input.fetchSemanticsNode().boundsInRoot.bottom, 1f)
        val foreground = compose.onNodeWithTag("chatComposerForeground").fetchSemanticsNode().boundsInRoot
        val rearTray = compose.onNodeWithTag("chatBusyActionTray").fetchSemanticsNode().boundsInRoot
        assertEquals(composerHeight, foreground.height, 1f)
        assertTrue("Rear tray must tuck behind the foreground composer", rearTray.bottom > foreground.top)
        assertTrue(compose.onNodeWithTag("chatBusyActionDrawer").fetchSemanticsNode().boundsInRoot.bottom <= foreground.top)
        input.performTextInput(" updated")
        assertTrue(draft.contains("updated"))
        compose.onRoot().captureRoboImage("build/ui-evidence/chat-busy-composer-expanded.png")
    }
}

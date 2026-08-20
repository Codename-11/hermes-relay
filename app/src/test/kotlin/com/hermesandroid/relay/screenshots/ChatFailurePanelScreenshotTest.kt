package com.hermesandroid.relay.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.components.ChatFailureDetailsDialog
import com.hermesandroid.relay.ui.components.ChatFailurePanel
import com.hermesandroid.relay.ui.components.ChatInputBar
import com.hermesandroid.relay.ui.components.ChatInputTrailing
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.ChatFailureNotice
import com.hermesandroid.relay.viewmodel.ChatFailureRoute
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-432dpi")
class ChatFailurePanelScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val failure = ChatFailureNotice(
        sessionId = "session-1",
        turnId = "turn-1",
        rawError = "API call failed after 3 retries: HTTP 404: 404 page not found",
        route = ChatFailureRoute.GATEWAY,
        model = "agnes-2",
        provider = "nous",
        recoverable = true,
    )

    @Test
    fun failurePanelRendersAtComposerEdgeAndOpensDetails() {
        var detailsOpen by mutableStateOf(false)
        var copied = false
        var retried = false
        var dismissed = false
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    ChatFailurePanel(
                        failure = failure,
                        routeLabel = "Gateway",
                        onDetails = { detailsOpen = true },
                        onRetry = { retried = true },
                        onDismiss = { dismissed = true },
                    )
                    ChatInputBar(
                        value = "",
                        onValueChange = {},
                        placeholder = "Message Hermes",
                        trailing = ChatInputTrailing.SEND,
                        onSend = {},
                        onVoice = {},
                        onStop = {},
                        onAttachPhotos = {},
                        onAttachFiles = {},
                        onAttachCamera = {},
                        onPasteImage = {},
                        onLongPressAttach = {},
                        charLimit = 20_000,
                        caption = null,
                        voiceReady = true,
                        showVoiceHint = false,
                        onVoiceHintShown = {},
                        isDarkTheme = true,
                    )
                    if (detailsOpen) {
                        ChatFailureDetailsDialog(
                            failure = failure,
                            routeLabel = "Gateway",
                            onCopy = { copied = true },
                            onDismiss = { detailsOpen = false },
                        )
                    }
                }
            }
        }

        compose.onRoot().captureRoboImage("build/ui-evidence/chat-failure-panel.png")
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText(failure.rawError).assertExists()
        compose.onRoot().captureRoboImage("build/ui-evidence/chat-failure-details.png")
        compose.onNodeWithText("Copy details").performClick()
        assertTrue(copied)
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Dismiss").performClick()
        assertTrue(retried)
        assertTrue(dismissed)
    }
}

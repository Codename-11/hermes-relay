package com.hermesandroid.relay.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.network.upstream.GatewayConnectionState
import com.hermesandroid.relay.ui.components.ChatDebugDrawer
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class ChatDebugDrawerScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun initializationFailureShowsConfirmedConnectionAndActionableError() {
        var closed = false
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                ChatDebugDrawer(
                    profile = "Server Default", model = "gpt-5.6-sol", sessionId = "session-example",
                    gateway = true, signedIn = true, signInRequired = false,
                    socketState = GatewayConnectionState.Ready, preparing = false,
                    streaming = false, loadingHistory = false, directoryUnavailable = false,
                    failure = "agent init failed: incompatible runtime helper",
                    onClose = { closed = true }, onConnections = {},
                )
            }
        }
        compose.onNodeWithText("Authenticated for this connection.").assertExists()
        compose.onNodeWithText("The request failed. Details are below.").assertExists()
        compose.onRoot().captureRoboImage("build/ui-regression/chat-debug-drawer.png")
        compose.onNodeWithContentDescription("Close session diagnostics").performClick()
        assertTrue(closed)
    }
}

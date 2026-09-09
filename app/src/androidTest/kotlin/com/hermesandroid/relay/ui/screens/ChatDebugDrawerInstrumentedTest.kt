package com.hermesandroid.relay.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.network.upstream.GatewayConnectionState
import com.hermesandroid.relay.ui.components.ChatDebugDrawer
import com.hermesandroid.relay.ui.components.ChatDebugOverlay
import com.hermesandroid.relay.ui.components.chatDebugHeaderGesture
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatDebugDrawerInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longPressOpensDiagnosticsBelowHeaderAndCloseRestoresChat() {
        compose.setContent {
            var open by remember { mutableStateOf(false) }
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                Box(Modifier.fillMaxSize()) {
                    Text("Hermes", Modifier.fillMaxWidth().height(64.dp).testTag("header")
                        .chatDebugHeaderGesture(true, onClick = {}, onHold = { open = true }))
                    ChatDebugOverlay(open, 64.dp, onClose = { open = false }) {
                        ChatDebugDrawer(
                            profile = "Server Default", model = "Example", sessionId = "session",
                            gateway = true, signedIn = true, signInRequired = false,
                            socketState = GatewayConnectionState.Ready, preparing = false,
                            streaming = false, loadingHistory = false, directoryUnavailable = false,
                            failure = null, onClose = { open = false }, onConnections = {},
                        )
                    }
                }
            }
        }
        val header = compose.onNodeWithTag("header")
        val before = header.fetchSemanticsNode().boundsInRoot
        header.performTouchInput { longClick() }
        compose.onNodeWithText("Session diagnostics").assertIsDisplayed()
        assertEquals(before, header.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("Close session diagnostics").performClick()
        compose.onNodeWithText("Session diagnostics").assertDoesNotExist()
        header.assertIsDisplayed()
    }
}

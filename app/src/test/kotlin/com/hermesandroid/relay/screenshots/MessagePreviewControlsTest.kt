package com.hermesandroid.relay.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.UiMessageBus
import com.hermesandroid.relay.ui.components.LocalMessageActionHost
import com.hermesandroid.relay.ui.components.MessageBannerHost
import com.hermesandroid.relay.ui.components.ThemedMessageHost
import com.hermesandroid.relay.ui.screens.MessagePreviewControls
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1200dp-xhdpi")
class MessagePreviewControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun previewRetryIsLocalAndClearPreservesRealMessages() {
        val host = SnackbarHostState()
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                CompositionLocalProvider(LocalMessageActionHost provides host) {
                    Surface { Column {
                        MessageBannerHost(includeStatusBarPadding = false)
                        MessagePreviewControls()
                        ThemedMessageHost(host)
                    } }
                }
            }
        }
        compose.onNodeWithContentDescription("Actionable error preview").performClick()
        compose.onNodeWithText("Retry").assertExists()
        compose.onRoot().captureRoboImage("build/ui-regression/message-previews.png")
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Preview retry selected — no request sent.").assertExists()
        compose.runOnIdle { UiMessageBus.post("Real app message", ttlMillis = 0L, key = "real-test-message") }
        compose.onNodeWithContentDescription("Clear previews").performClick()
        compose.onNodeWithText("Real app message").assertExists()
        compose.runOnIdle { UiMessageBus.clear("real-test-message") }
    }
}

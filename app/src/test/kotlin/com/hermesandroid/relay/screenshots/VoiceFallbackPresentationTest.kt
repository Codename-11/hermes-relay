package com.hermesandroid.relay.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.UiMessageBus
import com.hermesandroid.relay.ui.components.MessageBannerHost
import com.hermesandroid.relay.ui.components.StatsForNerds
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.VoiceStats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h720dp-xhdpi", sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceFallbackPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun warningAndTimingRowsAreReadable() {
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        MessageBannerHost(includeStatusBarPadding = false)
                        StatsForNerds(voiceStats = VoiceStats(
                            currentResponseTtsChunks = 3, recentTtsLatenciesMs = listOf(500),
                            lastTtsQueueWaitMs = 350, lastTtsPlayerStartMs = 50,
                        ))
                    }
                }
            }
        }
        val message = RuntimeEnvironment.getApplication().getString(R.string.voice_streaming_failed_basic)
        compose.runOnIdle { UiMessageBus.warning(message, ttlMillis = 0) }
        compose.onNodeWithText(message).assertIsDisplayed().performClick()
        capture("warning")
        compose.onNodeWithText("Expand").performClick()
        compose.onNodeWithText("Audio queue wait").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Player start delay").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthesis request gap").assertExists()
        capture("timing")
        compose.runOnIdle { UiMessageBus.clearAll() }
    }

    private fun capture(name: String) {
        val file = File("build/ui-evidence/voice-fallback-$name.png")
        file.parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(file.absolutePath)
    }
}

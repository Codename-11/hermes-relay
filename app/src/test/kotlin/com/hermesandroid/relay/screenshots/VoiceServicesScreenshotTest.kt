package com.hermesandroid.relay.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.VoiceEngineMode
import com.hermesandroid.relay.network.shared.GptLiveStatus
import com.hermesandroid.relay.ui.screens.GptLiveVoiceCard
import com.hermesandroid.relay.ui.screens.VoiceServicePicker
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.ui.theme.LayoutStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class VoiceServicesScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun standardVoiceIsSelectedAndRealtimeRequiresRelay() {
        var changes = 0
        compose.setContent {
            HermesRelayTheme(appThemeId = "clean", themePreference = "light", layoutStyleId = LayoutStyle.CLEAN.id) {
                Surface {
                    VoiceServicePicker(VoiceEngineMode.HermesVoiceOutput, false) { changes++ }
                }
            }
        }
        compose.onNodeWithText("Chat voice").assertIsSelected()
        compose.onNodeWithText("Live conversation").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, changes) }
        compose.onRoot().captureRoboImage("build/ui-regression/voice-services-standard.png")
    }

    @Test fun gptLivePageShowsHostModelAndVoiceOnly() {
        compose.setContent {
            HermesRelayTheme(appThemeId = "clean", themePreference = "dark", layoutStyleId = LayoutStyle.CLEAN.id) {
                Surface {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        VoiceServicePicker(VoiceEngineMode.HermesVoiceOutput, true) {}
                        GptLiveVoiceCard(GptLiveStatus(mode = "gpt-live", available = true, model = "gpt-live-1", voice = "marin"))
                    }
                }
            }
        }
        compose.onNodeWithText("GPT-Live").assertIsDisplayed()
        compose.onNodeWithText("gpt-live-1").assertIsDisplayed()
        compose.onNodeWithText("Marin").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/ui-regression/voice-settings-gpt-live.png")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun servicesRemainReachableAtLargeFont() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent {
            HermesRelayTheme(appThemeId = "clean", themePreference = "dark", layoutStyleId = LayoutStyle.CLEAN.id) {
                Surface {
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            var engine by remember { mutableStateOf(VoiceEngineMode.HermesVoiceOutput) }
                            VoiceServicePicker(engine, true) { engine = it }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Live conversation").performScrollTo().performClick().assertIsSelected()
        compose.onRoot().captureRoboImage("build/ui-regression/voice-services-large-text.png")
    }
}

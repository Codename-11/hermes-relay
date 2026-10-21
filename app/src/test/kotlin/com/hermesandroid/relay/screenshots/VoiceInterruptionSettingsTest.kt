package com.hermesandroid.relay.screenshots

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.assistant.AssistantWakeRuntimeState
import com.hermesandroid.relay.data.BargeInPreferences
import com.hermesandroid.relay.data.VoicePreferencesRepository
import com.hermesandroid.relay.data.VoiceEngineMode
import com.hermesandroid.relay.wake.WakeWordPreferences
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.ui.screens.VoiceSettingsScreen
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.*
import com.hermesandroid.relay.wake.WakeWordRuntimeState
import com.hermesandroid.relay.wake.WakeWordTestState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-xhdpi", sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceInterruptionSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun standardSettingsKeepSpeechAndMicrophoneControlsIndependent() {
        val phone = MutableStateFlow(BargeInPreferences(enabled = false))
        val settings = mockk<VoiceSettingsViewModel>(relaxed = true) {
            every { bargeInPrefs } returns phone
            every { aecAvailable } returns true
            every { wakeWordPrefs } returns MutableStateFlow(WakeWordPreferences())
            every { wakeWordRuntimeState } returns MutableStateFlow(WakeWordRuntimeState.Stopped)
            every { wakeWordTestState } returns MutableStateFlow(WakeWordTestState())
            every { wakeWordInstallState } returns MutableStateFlow(WakeWordInstallUiState())
            every { assistantWakeRuntimeState } returns MutableStateFlow(AssistantWakeRuntimeState.Stopped)
            every { configState } returns MutableStateFlow(VoiceConfigUiState())
            every { configErrorEvents } returns MutableSharedFlow()
        }
        val voice = mockk<VoiceViewModel>(relaxed = true) {
            every { errorEvents } returns MutableSharedFlow()
        }
        val dashboard = mockk<DashboardApiClient>(relaxed = true) {
            coEvery { getConfig() } returns Result.success(Json.parseToJsonElement(
                """{"voice":{"barge_in":false}}""",
            ).jsonObject)
            coEvery { getConfigSchema() } returns Result.success(Json.parseToJsonElement(
                """{"fields":{"voice.barge_in":{"type":"bool","description":"Barge in","category":"voice"}}}""",
            ).jsonObject)
            coEvery { getElevenLabsVoices(any()) } returns Result.failure(IllegalStateException("not configured"))
            coEvery { getTtsToolsetConfig() } returns Result.success(Json.parseToJsonElement("{}").jsonObject)
        }
        val prefs = VoicePreferencesRepository(RuntimeEnvironment.getApplication())
        compose.setContent {
            CompositionLocalProvider(LocalSnackbarHost provides remember { SnackbarHostState() }) {
                HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                    VoiceSettingsScreen(
                        voiceViewModel = voice, voiceClient = null,
                        standardVoiceAvailability = StandardVoiceAvailability.Ready,
                        dashboardUrl = "https://hermes.example.test", dashboardClientProvider = { dashboard },
                        onBack = {}, settingsViewModel = settings,
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Speak tool updates").performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5_000) { !runBlocking { prefs.settings.first() }.speakToolUpdates }
        compose.onNodeWithContentDescription("Speak tool updates").assertIsOff()
        compose.onNodeWithText("Built-in tool updates are English.", substring = true).assertIsDisplayed()
        capture("tool-updates-off")
        compose.onNodeWithContentDescription("Final answer only").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { prefs.settings.first() }.finalAnswerOnly }
        compose.onNodeWithContentDescription("Speak tool updates").assertIsNotEnabled().assertIsOff()
        capture("tool-updates-final-only")
        compose.onNodeWithContentDescription("Final answer only").performClick()
        compose.waitUntil(5_000) { !runBlocking { prefs.settings.first() }.finalAnswerOnly }
        compose.onNodeWithContentDescription("Speak tool updates").assertIsEnabled().assertIsOff()
        runBlocking { prefs.setEngineMode(VoiceEngineMode.RealtimeAgent) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Speak tool updates").assertDoesNotExist()
        runBlocking { prefs.setEngineMode(VoiceEngineMode.HermesVoiceOutput) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Speak tool updates").assertIsOff()
        compose.onNodeWithText("Listening").performClick()
        for ((mode, label) in listOf("hold" to "Hold to talk", "tap" to "Tap to talk", "continuous" to "Continuous")) {
            for (enabled in listOf(false, true)) {
                runBlocking { prefs.setInteractionMode(mode) }
                compose.runOnIdle { phone.value = BargeInPreferences(enabled = enabled) }
                compose.onNodeWithText(label).performScrollTo().assertIsSelected()
                compose.onNodeWithText("Hold records while pressed.", substring = true).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Phone interruption must be enabled", substring = true).assertExists()
                capture("mode-$mode-$enabled")
                compose.onNodeWithText("Phone interruption").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Uses this phone", substring = true).performScrollTo().assertIsDisplayed()
                capture("phone-$mode-$enabled")
                assertEquals(enabled, phone.value.enabled)
            }
        }
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Host barge-in").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Controls interruption in Hermes host voice mode.", substring = true).performScrollTo().assertIsDisplayed()
        capture("host")
        assertEquals(true, phone.value.enabled)
        verify(exactly = 0) { settings.setBargeInEnabled(any()) }
    }

    private fun capture(name: String) {
        val file = File("build/ui-evidence/voice-interruption-$name.png")
        file.parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(file.absolutePath)
    }
}

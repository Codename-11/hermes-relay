package com.hermesandroid.relay.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xhdpi")
class VoiceSettingsScrollStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var connectionId by mutableStateOf("connection-a")
    private var profileName by mutableStateOf("default")
    private lateinit var scrollState: ScrollState

    @Composable
    private fun Content() {
        var section by rememberSaveable { mutableStateOf(VoiceSettingsSection.Output) }
        scrollState = rememberVoiceSettingsScrollState(section, connectionId, profileName)
        MaterialTheme {
            Column(Modifier.height(360.dp)) {
                VoiceSettingsTabs(section) { section = it }
                key(section) {
                    Column(Modifier.weight(1f).verticalScroll(scrollState)) {
                        repeat(20) { Text("$section $it", Modifier.height(48.dp)) }
                    }
                }
            }
        }
    }

    private fun scrollToEnd(section: VoiceSettingsSection): Int {
        compose.onNodeWithText("$section 19").performScrollTo()
        var value = 0
        compose.runOnIdle { value = scrollState.value }
        assertTrue("The fixture must actually scroll", value > 0)
        return value
    }

    @Test fun tabsKeepIndependentScrollPositions() {
        compose.setContent { Content() }
        val outputPosition = scrollToEnd(VoiceSettingsSection.Output)
        compose.onNodeWithText("Listening").performClick()
        compose.runOnIdle { assertEquals(0, scrollState.value) }
        val listeningPosition = scrollToEnd(VoiceSettingsSection.Listening)
        compose.onNodeWithText("Voice").performClick()
        compose.runOnIdle { assertEquals(outputPosition, scrollState.value) }
        compose.onNodeWithText("Listening").performClick()
        compose.runOnIdle { assertEquals(listeningPosition, scrollState.value) }
    }

    @Test fun inactiveAndActiveTabPositionsSurviveStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Content() }
        val outputPosition = scrollToEnd(VoiceSettingsSection.Output)
        compose.onNodeWithText("Listening").performClick()
        val listeningPosition = scrollToEnd(VoiceSettingsSection.Listening)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Listening").assertIsSelected()
        compose.runOnIdle { assertEquals(listeningPosition, scrollState.value) }
        compose.onNodeWithText("Voice").performClick()
        compose.runOnIdle { assertEquals(outputPosition, scrollState.value) }
    }

    @Test fun changingConnectionOrProfileResetsScrollPositions() {
        compose.setContent { Content() }
        scrollToEnd(VoiceSettingsSection.Output)
        compose.runOnIdle { connectionId = "connection-b" }
        compose.runOnIdle { assertEquals(0, scrollState.value) }
        scrollToEnd(VoiceSettingsSection.Output)
        compose.runOnIdle { profileName = "other-profile" }
        compose.runOnIdle { assertEquals(0, scrollState.value) }
    }
}

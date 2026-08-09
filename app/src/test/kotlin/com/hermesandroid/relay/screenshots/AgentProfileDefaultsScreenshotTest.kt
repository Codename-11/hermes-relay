package com.hermesandroid.relay.screenshots

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.ui.screens.AgentProfileDefaultsPreviewScreen
import com.hermesandroid.relay.ui.screens.AgentProfileDefaultsPreviewState
import com.hermesandroid.relay.ui.screens.AgentProfileDefaultsTarget
import com.hermesandroid.relay.ui.screens.AgentProfileModelChoice
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h935dp-xhdpi")
class AgentProfileDefaultsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loadedProfileDefaults_matchesSettingsDetailDesign() {
        val model = AgentProfileModelChoice(
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            reasoningSupported = true,
            reasoningEfforts = listOf("low", "medium", "high", "xhigh"),
            fastSupported = true,
        )
        val state = AgentProfileDefaultsPreviewState(
            target = AgentProfileDefaultsTarget(
                connectionId = "hermes-home",
                connectionLabel = "Hermes Home",
                dashboardUrl = "https://hermes.example.test",
                profileName = "Default",
            ),
            config = Json.parseToJsonElement(
                """
                {
                  "agent": {
                    "reasoning_effort": "high",
                    "service_tier": "priority",
                    "personalities": {"Hermes": {}}
                  },
                  "display": {"personality": "Hermes"},
                  "approvals": {"mode": "smart"},
                  "tts": {},
                  "memory": {"provider": "built-in"}
                }
                """.trimIndent(),
            ).jsonObject,
            model = model,
            modelChoices = listOf(model),
            personalityChoices = listOf("", "Hermes"),
            profile = Profile(
                name = "Default",
                model = "claude-sonnet-4-5",
                description = "Default agent",
                gatewayRunning = true,
                hasSoul = true,
                skillCount = 18,
            ),
            pendingConfig = mapOf(
                "agent.reasoning_effort" to Json.parseToJsonElement("\"medium\""),
                "agent.service_tier" to Json.parseToJsonElement("\"normal\""),
            ),
        )
        val viewModel = ConnectionViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
        )

        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                AgentProfileDefaultsPreviewScreen(
                    connectionViewModel = viewModel,
                    state = state,
                )
            }
        }

        compose.onNodeWithText("Agent profile defaults").fetchSemanticsNode()
        compose.onNodeWithText("Default").fetchSemanticsNode()
        compose.onNodeWithText("Default model").fetchSemanticsNode()
        compose.onNodeWithText("Active sessions keep their current overrides.").fetchSemanticsNode()
        compose.onNodeWithText("2 unsaved changes").fetchSemanticsNode()
        compose.onRoot().captureRoboImage(
            "build/ui-regression/agent-profile-defaults-dark.png",
        )
    }
}

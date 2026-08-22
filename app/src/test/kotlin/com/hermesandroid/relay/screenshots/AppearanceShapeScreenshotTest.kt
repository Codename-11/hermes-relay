package com.hermesandroid.relay.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.ui.components.ChatFailurePanel
import com.hermesandroid.relay.ui.components.ChatFailureDetailsDialog
import com.hermesandroid.relay.ui.components.ChatInputBar
import com.hermesandroid.relay.ui.components.ChatInputPickerOption
import com.hermesandroid.relay.ui.components.ChatInputTrailing
import com.hermesandroid.relay.ui.components.ExtraKeysToolbar
import com.hermesandroid.relay.ui.components.MessageBubble
import com.hermesandroid.relay.ui.components.ModelPickerSheet
import com.hermesandroid.relay.ui.components.ProfileInspectorCard
import com.hermesandroid.relay.ui.components.RelayStatusStrip
import com.hermesandroid.relay.ui.components.SettingsExpandableCard
import com.hermesandroid.relay.ui.components.TerminalTabBar
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.ChatFailureNotice
import com.hermesandroid.relay.viewmodel.ChatFailureRoute
import com.hermesandroid.relay.viewmodel.TerminalViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h1000dp-432dpi")
class AppearanceShapeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun softDarkRepresentativeSurfaces() = captureMode("soft", "hermes-relay", "dark", 1f)
    @Test fun balancedDarkRepresentativeSurfaces() = captureMode("balanced", "hermes-relay", "dark", 1f)
    @Test fun sharpDarkRepresentativeSurfaces() = captureMode("sharp", "hermes-relay", "dark", 1f)
    @Test fun sharpLightLargeFontRepresentativeSurfaces() = captureMode("sharp", "nous-blue", "light", 1.25f)

    @Test fun softDarkMaximumCombinedFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1.3f),
            ) {
                HermesRelayTheme(themePreference = "dark", fontScale = 1.3f, shapeId = "soft") {
                    MaximumCombinedFontSurfaces()
                }
            }
        }
        compose.onRoot().captureRoboImage("build/ui-evidence/appearance-shape-soft-combined-font-1.69.png")
    }

    @Test fun sharpDarkDialogSurface() {
        compose.setContent {
            HermesRelayTheme(themePreference = "dark", shapeId = "sharp") {
                ChatFailureDetailsDialog(failure(), "Gateway", onCopy = {}, onDismiss = {})
            }
        }
        compose.onRoot().captureRoboImage("build/ui-evidence/appearance-shape-sharp-dialog-dark.png")
    }

    @Test fun balancedDarkConnectionSheetSurface() {
        compose.setContent {
            HermesRelayTheme(themePreference = "dark", shapeId = "balanced") {
                ModelPickerSheet(
                    options = listOf(
                        ChatInputPickerOption("Server default", null),
                        ChatInputPickerOption("Hermes 3", "hermes-3", group = "Nous"),
                        ChatInputPickerOption("GPT-5.6", "gpt-5.6", group = "OpenAI"),
                    ),
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("build/ui-evidence/appearance-shape-balanced-sheet-dark.png")
    }

    private fun captureMode(shapeId: String, themeId: String, themePreference: String, fontScale: Float) {
        compose.setContent {
            HermesRelayTheme(
                appThemeId = themeId,
                themePreference = themePreference,
                fontScale = fontScale,
                shapeId = shapeId,
            ) {
                RepresentativeShapeSurfaces(shapeId)
            }
        }
        compose.onRoot().captureRoboImage("build/ui-evidence/appearance-shape-$shapeId-$themePreference-font-$fontScale.png")
    }
}

@androidx.compose.runtime.Composable
private fun RepresentativeShapeSurfaces(shapeId: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Shape: $shapeId",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        MessageBubble(
            message = ChatMessage("user", MessageRole.USER, "The real chat bubble follows Appearance.", 0L),
        )
        MessageBubble(
            message = ChatMessage("assistant", MessageRole.ASSISTANT, "Cards and controls keep their visual hierarchy.", 0L),
        )
        SettingsExpandableCard(
            title = "Connection and settings card",
            expanded = true,
            onToggle = {},
            isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f,
        ) {
            Text("Applied immediately and restored after restart.")
        }
        TerminalTabBar(
            tabs = listOf(
                TerminalViewModel.TabState(1, "main", displayName = "main"),
                TerminalViewModel.TabState(2, "logs", displayName = "logs", unreadOutput = true),
            ),
            activeTabId = 1,
            onSelectTab = {},
            onCloseTab = {},
            onNewTab = {},
        )
        ExtraKeysToolbar(
            ctrlActive = true,
            altActive = false,
            onEsc = {},
            onTab = {},
            onCtrlToggle = {},
            onAltToggle = {},
            onArrow = {},
        )
        ChatFailurePanel(
            failure = failure(),
            routeLabel = "Gateway",
            onDetails = {},
            onRetry = {},
            onDismiss = {},
        )
        ProfileInspectorCard(
            activeProfile = Profile(name = "Victor", model = "gpt-5.6-sol"),
            onClick = {},
            isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f,
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
            isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f,
        )
        RelayStatusStrip(
            leadingBadge = { Text("Gateway", color = MaterialTheme.colorScheme.primary) },
            routeLabel = "LAN",
            trailing = "profile: Victor",
        )
    }
}

@androidx.compose.runtime.Composable
private fun MaximumCombinedFontSurfaces() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Maximum combined font scale",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        ProfileInspectorCard(
            activeProfile = Profile(name = "Victor", model = "gpt-5.6-sol"),
            onClick = {},
            isDarkTheme = true,
        )
        SettingsExpandableCard(
            title = "Readable settings card",
            expanded = true,
            onToggle = {},
            isDarkTheme = true,
        ) {
            Text("System and app font scaling stack without clipping controls.")
        }
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
        RelayStatusStrip(
            leadingBadge = { Text("Gateway", color = MaterialTheme.colorScheme.primary) },
            routeLabel = "LAN",
            trailing = "profile: Victor",
        )
    }
}

private fun failure() = ChatFailureNotice(
    sessionId = "session",
    turnId = "turn",
    rawError = "Representative dialog-adjacent status surface",
    route = ChatFailureRoute.GATEWAY,
    recoverable = true,
)

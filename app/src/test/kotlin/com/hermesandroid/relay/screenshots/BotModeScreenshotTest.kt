package com.hermesandroid.relay.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.BotGroupMessage
import com.hermesandroid.relay.data.BotGroupRoom
import com.hermesandroid.relay.data.BotModeRoster
import com.hermesandroid.relay.data.BotModeState
import com.hermesandroid.relay.data.BotRosterEntry
import com.hermesandroid.relay.data.BotSessionSummary
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.ui.components.LocalSphereSkin
import com.hermesandroid.relay.ui.components.SphereRegistry
import com.hermesandroid.relay.ui.screens.BotModeContent
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-432dpi")
class BotModeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun approvedBotModeHome() {
        val output = File("build/ui-evidence/bot-mode-home.png")
        output.parentFile?.mkdirs()
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                CompositionLocalProvider(LocalSphereSkin provides SphereRegistry.Adaptive) {
                    BotModeContent(
                        state = fixtureState(),
                        connections = listOf(
                            fixtureConnection("hermes", "Hermes"),
                            fixtureConnection("lab", "Lab server"),
                        ),
                        activeConnection = fixtureConnection("hermes", "Hermes"),
                        onOpenSettings = {},
                        onOpenChat = {},
                        onRefresh = {},
                        onSelectGateway = {},
                        onOpenBot = {},
                        onOpenGroup = {},
                        onNewBot = {},
                        nowMs = NOW,
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage(output.absolutePath)
    }

    @Test
    @Config(qualifiers = "w360dp-h720dp-432dpi")
    fun compactLargeTypePicker() {
        capture("bot-mode-compact-picker", "dark", 1.3f, picker = true)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-432dpi")
    fun compactLandscapeLargeType() {
        capture("bot-mode-landscape", "dark", 1.3f)
        compose.onAllNodesWithText("Researcher").assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp-240dpi")
    fun tabletLightLargeType() {
        capture("bot-mode-tablet-light", "light", 1.3f)
    }

    private fun capture(name: String, theme: String, scale: Float, picker: Boolean = false) {
        val output = File("build/ui-evidence/$name.png")
        output.parentFile?.mkdirs()
        val base = fixtureState()
        val expanded = base.copy(roster = base.roster.copy(bots = base.roster.bots.mapIndexed { index, bot ->
            if (index == 0) bot.copy(displayName = "Research and release planning assistant with a long name", stale = true)
            else bot
        }))
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = theme, fontScale = scale) {
                CompositionLocalProvider(LocalSphereSkin provides SphereRegistry.Adaptive) {
                    BotModeContent(
                        openPicker = picker,
                        state = expanded,
                        connections = listOf(fixtureConnection("hermes", "Hermes"), fixtureConnection("lab", "Lab server")),
                        activeConnection = fixtureConnection("hermes", "Hermes"),
                        onOpenSettings = {}, onOpenChat = {}, onRefresh = {}, onSelectGateway = {},
                        onOpenBot = {}, onOpenGroup = {}, onNewBot = {}, nowMs = NOW,
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage(output.absolutePath)
    }

    private fun fixtureState() = BotModeState(
        roster = BotModeRoster(
            bots = listOf(
                bot("hermes", "Hermes", "default", "Lucy", "Drafted a rollout plan for the new flow.", NOW - 60_000L),
                bot("lab", "Lab server", "researcher", "Researcher", "Here are the latest findings.", NOW - 18 * 60_000L),
                bot("hermes", "Hermes", "builder", "Builder", "Build complete. 3 tests added.", NOW - 43 * 60_000L),
            ),
            groups = listOf(
                room("id:launch", "Launch Council", "Maya", "Please review the deck when you can.", NOW - 86_400_000L),
                room("id:home", "Home Lab", "Alex", "Benchmarked the new model.", NOW - 3 * 86_400_000L),
            ),
            botModeProtocolSupported = true,
        ),
    )

    private fun bot(
        connectionId: String,
        connectionLabel: String,
        name: String,
        title: String,
        preview: String,
        activeAt: Long,
    ) = BotRosterEntry(
        profile = Profile(name = name, model = "gpt-5.6", description = title),
        displayName = title,
        route = com.hermesandroid.relay.data.BotGatewayRoute(
            key = com.hermesandroid.relay.data.BotGatewayRouteKey(connectionId, name),
            connectionLabel = connectionLabel,
        ),
        canonicalSession = BotSessionSummary(
            id = "$name-bot-chat",
            preview = preview,
            lastActiveAtMs = activeAt,
            messageCount = 8,
        ),
        workerSession = BotSessionSummary(
            id = "$name-worker",
            lastActiveAtMs = NOW - 30_000L,
        ),
    )

    private fun room(key: String, name: String, sender: String, text: String, at: Long) = BotGroupRoom(
        key = key,
        roomId = key.substringAfter(':'),
        name = name,
        messages = listOf(
            BotGroupMessage(
                id = "$key-message",
                senderName = sender,
                senderKind = "member",
                text = text,
                atMs = at,
            ),
        ),
    )

    private fun fixtureConnection(id: String, label: String) = Connection(
        id = id,
        label = label,
        apiServerUrl = "",
        relayUrl = "",
        dashboardUrl = "https://example.invalid",
        tokenStoreKey = "test-$id",
    )

    private companion object {
        const val NOW = 1_777_000_000_000L
    }
}

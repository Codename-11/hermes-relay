package com.hermesandroid.relay.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import com.hermesandroid.relay.data.BotGroupMessage
import com.hermesandroid.relay.data.BotGatewayRoute
import com.hermesandroid.relay.data.BotGatewayRouteKey
import com.hermesandroid.relay.data.BotGroupRoom
import com.hermesandroid.relay.data.BotModeRoster
import com.hermesandroid.relay.data.BotModeState
import com.hermesandroid.relay.data.BotRosterEntry
import com.hermesandroid.relay.data.BotSessionSummary
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-432dpi")
class BotModeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `settings avatar and main chat stay reachable with no gateways`() {
        var settingsOpened = 0
        var chatOpened = 0
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                BotModeContent(
                    state = BotModeState(),
                    connections = emptyList(),
                    activeConnection = null,
                    onOpenSettings = { settingsOpened++ },
                    onOpenChat = { chatOpened++ },
                    onRefresh = {},
                    onSelectGateway = {},
                    onOpenBot = {},
                    onOpenGroup = {},
                    onNewBot = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Main chat").assertDoesNotExist()
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.onNodeWithText("Create Bot").assertIsNotEnabled()
        compose.onNodeWithText("Main chat").performClick()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithContentDescription("All gateways").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, settingsOpened)
            assertEquals(1, chatOpened)
        }
    }

    @Test
    fun `home lists pinned bots, conversations and read only groups together`() {
        render()

        // Pinned shortcut plus conversation row.
        compose.onAllNodesWithText("Lucy").assertCountEquals(2)
        compose.onNodeWithText("Launch Council").assertExists()
        compose.onNodeWithText("Read only").assertExists()
    }

    @Test
    fun `plus opens the To picker which searches and opens bots`() {
        val opened = mutableListOf<String>()
        var created = 0
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                BotModeContent(
                    state = state(),
                    connections = listOf(connection(), labConnection()),
                    activeConnection = connection(),
                    onOpenSettings = {},
                    onOpenChat = {},
                    onRefresh = {},
                    onSelectGateway = {},
                    onOpenBot = { opened += it.route!!.connectionId },
                    onOpenGroup = {},
                    onNewBot = { created++ },
                    nowMs = NOW + 200_000L,
                )
            }
        }

        compose.onNodeWithContentDescription("New chat").performClick()
        compose.onNodeWithText("Create Bot").assertExists()
        compose.onAllNodesWithText("Researcher").onLast().performClick()
        assertEquals(listOf("lab"), opened)

        compose.onNodeWithContentDescription("New chat").performClick()
        compose.onNodeWithText("Create Bot").performClick()
        assertEquals(1, created)
    }

    @Test
    fun `bot and group rows dispatch their exact owners`() {
        var botOwner: String? = null
        var groupOwner: String? = null
        render(
            onOpenBot = { botOwner = it.profile.name },
            onOpenGroup = { groupOwner = it.key },
        )

        compose.onAllNodesWithText("Lucy").onLast().performClick()
        compose.onNodeWithText("Launch Council").performClick()

        assertEquals("default", botOwner)
        assertEquals("id:launch", groupOwner)
    }

    @Test
    fun `gateway scope filters union without switching active connection`() {
        render(selectedGatewayId = "lab")

        compose.onAllNodesWithText("Researcher").onFirst().assertExists()
        compose.onNodeWithText("Lucy").assertDoesNotExist()
    }

    @Test
    fun `same named bots on different gateways render and open their own conversation`() {
        val opened = mutableListOf<String>()
        render(onOpenBot = { opened += it.route!!.connectionId })

        compose.onAllNodesWithText("Lucy").onLast().performClick()
        compose.onAllNodesWithText("Researcher").onLast().performClick()

        assertEquals(listOf("home", "lab"), opened)
    }

    @Test
    fun `same named active bots on different gateways render and open their own owner`() {
        val opened = mutableListOf<String>()
        render(nowMs = NOW, onOpenBot = { opened += it.route!!.connectionId })

        compose.onAllNodesWithText("Lucy")[0].performClick()
        compose.onAllNodesWithText("Researcher")[0].performClick()

        assertEquals(listOf("home", "lab"), opened)
    }

    @Test
    fun `opening progress only replaces the selected owners preview`() {
        render(openingRoute = BotGatewayRouteKey("home", "default"))

        compose.onNodeWithText("Drafted a rollout plan").assertDoesNotExist()
        compose.onNodeWithText("Findings ready").assertExists()
    }

    @Test
    fun `item identity survives presentation refresh and separates delimiter containing owners`() {
        val bot = state().roster.bots.first()
        val refreshed = bot.copy(
            displayName = "Renamed",
            handle = "new-handle",
            stale = true,
            route = BotGatewayRoute(bot.route!!.key, "New label", "new-install-metadata"),
            canonicalSession = BotSessionSummary(id = "compressed-tip"),
        )
        assertEquals(bot.lazyItemKey, refreshed.lazyItemKey)
        assertNotEquals(
            bot.copy(route = BotGatewayRoute(BotGatewayRouteKey("a:b", "c"), "Same label")).lazyItemKey,
            bot.copy(route = BotGatewayRoute(BotGatewayRouteKey("a", "b:c"), "Same label")).lazyItemKey,
        )
        assertNotEquals(
            bot.lazyItemKey,
            bot.copy(route = BotGatewayRoute(BotGatewayRouteKey("home", "other"), "Hermes")).lazyItemKey,
        )
        assertNotEquals(bot.lazyItemKey, bot.copy(route = null).lazyItemKey)
    }


    @Test
    fun `picker request is consumed and searching a gateway keeps exact bot ownership`() {
        val opened = mutableListOf<String>()
        var consumed = 0
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                BotModeContent(
                    openPicker = true,
                    onPickerHandled = { consumed++ },
                    state = state(),
                    connections = listOf(connection(), labConnection()),
                    activeConnection = connection(),
                    onOpenSettings = {}, onOpenChat = {}, onRefresh = {}, onSelectGateway = {},
                    onOpenBot = { opened += it.route!!.connectionId }, onOpenGroup = {}, onNewBot = {},
                    nowMs = NOW,
                )
            }
        }
        compose.onNodeWithText("Search Bots").performTextInput("Lab server")
        compose.onAllNodesWithText("Researcher").onLast().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, consumed)
            assertEquals(listOf("lab"), opened)
        }
    }

    @Test
    fun `search with no matches offers creation and a clear empty result`() {
        render()
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.onNodeWithText("Search Bots").performTextInput("not-a-bot")
        compose.onNodeWithText("No results").assertIsDisplayed()
        compose.onNodeWithText("Create Bot").assertIsEnabled()
    }

    @Test
    fun `opening one bot disables other bot rows until routing settles`() {
        render(openingRoute = BotGatewayRouteKey("home", "default"))
        compose.onAllNodesWithText("Researcher").onLast().assertIsNotEnabled()
        compose.onNodeWithText("Opening Bot Chat…").assertExists()
    }

    private fun render(
        onOpenBot: (BotRosterEntry) -> Unit = {},
        onOpenGroup: (BotGroupRoom) -> Unit = {},
        selectedGatewayId: String? = null,
        nowMs: Long = NOW + 200_000L,
        openingRoute: BotGatewayRouteKey? = null,
    ) {
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                BotModeContent(
                    state = state(),
                    connections = listOf(connection(), labConnection()),
                    activeConnection = connection(),
                    selectedGatewayId = selectedGatewayId,
                    openingRoute = openingRoute,
                    onOpenSettings = {},
                    onOpenChat = {},
                    onRefresh = {},
                    onSelectGateway = {},
                    onOpenBot = onOpenBot,
                    onOpenGroup = onOpenGroup,
                    onNewBot = {},
                    nowMs = nowMs,
                )
            }
        }
    }

    private fun state() = BotModeState(
        roster = BotModeRoster(
            bots = listOf(
                BotRosterEntry(
                    profile = Profile(name = "default", model = "gpt-5.6", description = "Operator"),
                    displayName = "Lucy",
                    route = com.hermesandroid.relay.data.BotGatewayRoute(
                        key = com.hermesandroid.relay.data.BotGatewayRouteKey("home", "default"),
                        connectionLabel = "Hermes",
                    ),
                    canonicalSession = BotSessionSummary(
                        id = "bot-root",
                        preview = "Drafted a rollout plan",
                        lastActiveAtMs = NOW - 10_000L,
                    ),
                ),
                BotRosterEntry(
                    profile = Profile(name = "default", model = "gpt-5.6", description = "Research"),
                    displayName = "Researcher",
                    route = com.hermesandroid.relay.data.BotGatewayRoute(
                        key = com.hermesandroid.relay.data.BotGatewayRouteKey("lab", "default"),
                        connectionLabel = "Lab server",
                    ),
                    canonicalSession = BotSessionSummary(
                        id = "researcher-root",
                        preview = "Findings ready",
                        lastActiveAtMs = NOW - 30_000L,
                    ),
                ),
            ),
            groups = listOf(
                BotGroupRoom(
                    key = "id:launch",
                    roomId = "launch",
                    name = "Launch Council",
                    messages = listOf(
                        BotGroupMessage(
                            id = "message-1",
                            senderName = "Lucy",
                            senderKind = "member",
                            text = "Rollout is clear",
                            atMs = NOW - 20_000L,
                        ),
                    ),
                    sourceConnectionIds = setOf("home", "lab"),
                ),
            ),
        ),
    )

    private fun connection() = Connection(
        id = "home",
        label = "Hermes",
        apiServerUrl = "",
        relayUrl = "",
        dashboardUrl = "https://example.invalid",
        tokenStoreKey = "test",
    )

    private fun labConnection() = Connection(
        id = "lab",
        label = "Lab server",
        apiServerUrl = "",
        relayUrl = "",
        dashboardUrl = "https://lab.invalid",
        tokenStoreKey = "test-lab",
    )

    private companion object {
        const val NOW = 1_777_000_000_000L
    }
}

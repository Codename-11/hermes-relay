package com.hermesandroid.relay.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
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
    fun `tabs filter bots and read only groups without changing workspace`() {
        render()

        compose.onNodeWithText("Lucy").assertExists()
        compose.onNodeWithText("Launch Council").assertExists()

        compose.onNodeWithText("Groups").performClick()

        compose.onNodeWithText("Lucy").assertDoesNotExist()
        compose.onNodeWithText("Launch Council").assertExists()
        compose.onNodeWithText("Read only").assertExists()
    }

    @Test
    fun `bot and group rows dispatch their exact owners`() {
        var botOwner: String? = null
        var groupOwner: String? = null
        render(
            onOpenBot = { botOwner = it.profile.name },
            onOpenGroup = { groupOwner = it.key },
        )

        compose.onNodeWithText("Lucy").performClick()
        compose.onNodeWithText("Launch Council").performClick()

        assertEquals("default", botOwner)
        assertEquals("id:launch", groupOwner)
    }

    @Test
    fun `gateway scope filters union without switching active connection`() {
        render(selectedGatewayId = "lab")

        compose.onNodeWithText("Researcher").assertExists()
        compose.onNodeWithText("Lucy").assertDoesNotExist()
    }

    @Test
    fun `same named bots on different gateways render and open their own conversation`() {
        val opened = mutableListOf<String>()
        render(onOpenBot = { opened += it.route!!.connectionId })

        compose.onNodeWithText("Lucy").performClick()
        compose.onNodeWithText("Researcher").performClick()

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
                    onBack = {},
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

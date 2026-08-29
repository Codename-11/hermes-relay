package com.hermesandroid.relay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class RelayNavigationHydrationUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `connection policy hydration keeps navigation owner and destination mounted`() {
        var navigationHydrated by mutableStateOf(true)
        val observedControllers = mutableListOf<NavHostController>()

        compose.setContent {
            val navController = rememberNavController()
            val entry by navController.currentBackStackEntryAsState()
            SideEffect { observedControllers += navController }
            Box {
                NavHost(navController = navController, startDestination = "pair-test") {
                    composable("pair-test") { Text("Pair screen") }
                    composable("chat-test") { Text("Chat screen") }
                }
                val policyRoute = when (entry?.destination?.route) {
                    "pair-test" -> Screen.Pair.route("draft")
                    "chat-test" -> Screen.Chat.route
                    else -> null
                }
                if (shouldCoverRelayNavigation(navigationHydrated, true, policyRoute)) {
                    Text("Protected loading cover")
                }
            }
        }

        compose.onNodeWithText("Pair screen").assertExists()
        compose.runOnIdle { navigationHydrated = false }
        compose.onNodeWithText("Pair screen").assertExists()
        compose.onNodeWithText("Protected loading cover").assertDoesNotExist()

        compose.runOnIdle { observedControllers.last().navigate("chat-test") }
        compose.onNodeWithText("Chat screen").assertExists()
        compose.onNodeWithText("Protected loading cover").assertExists()

        compose.runOnIdle { navigationHydrated = true }
        compose.onNodeWithText("Protected loading cover").assertDoesNotExist()
        compose.onNodeWithText("Chat screen").assertExists()
        compose.runOnIdle {
            val first = observedControllers.first()
            observedControllers.forEach { assertSame(first, it) }
        }
    }
}

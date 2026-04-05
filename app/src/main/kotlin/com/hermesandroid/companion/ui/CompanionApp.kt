package com.hermesandroid.companion.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hermesandroid.companion.ui.screens.BridgeScreen
import com.hermesandroid.companion.ui.screens.ChatScreen
import com.hermesandroid.companion.ui.screens.SettingsScreen
import com.hermesandroid.companion.ui.screens.TerminalScreen
import com.hermesandroid.companion.ui.theme.HermesCompanionTheme
import com.hermesandroid.companion.viewmodel.ChatViewModel
import com.hermesandroid.companion.viewmodel.ConnectionViewModel

sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Chat : Screen("chat", "Chat", Icons.Filled.Chat)
    data object Terminal : Screen("terminal", "Terminal", Icons.Filled.Code)
    data object Bridge : Screen("bridge", "Bridge", Icons.Filled.PhoneAndroid)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

private val bottomNavScreens = listOf(
    Screen.Chat,
    Screen.Terminal,
    Screen.Bridge,
    Screen.Settings
)

@Composable
fun CompanionApp() {
    val connectionViewModel: ConnectionViewModel = viewModel()
    val chatViewModel: ChatViewModel = viewModel()

    // Initialize ChatViewModel with networking dependencies
    remember {
        chatViewModel.initialize(
            connectionViewModel.multiplexer,
            connectionViewModel.chatHandler
        )
        true
    }

    // Observe theme preference
    val themePreference by connectionViewModel.theme.collectAsState()

    HermesCompanionTheme(themePreference = themePreference) {
        val navController = rememberNavController()

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination

                    bottomNavScreens.forEach { screen ->
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.label
                                )
                            },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any {
                                it.route == screen.route
                            } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Chat.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(Screen.Chat.route) {
                    ChatScreen(
                        chatViewModel = chatViewModel,
                        connectionViewModel = connectionViewModel
                    )
                }
                composable(Screen.Terminal.route) {
                    TerminalScreen()
                }
                composable(Screen.Bridge.route) {
                    BridgeScreen()
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(connectionViewModel = connectionViewModel)
                }
            }
        }
    }
}

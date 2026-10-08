package com.hermesandroid.relay.ui

import android.Manifest
import android.app.NotificationManager
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.notifications.ChatNotificationTarget
import com.hermesandroid.relay.notifications.TurnCompleteNotifier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatNotificationNavigationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun replyIntentNavigatesRealChatRouteWithDecodedExactOwner() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        lateinit var nav: NavHostController
        compose.setContent {
            val controller = rememberNavController()
            SideEffect { nav = controller }
            NavHost(controller, startDestination = "test-home") {
                composable("test-home") { Text("Home") }
                composable(Screen.Chat.route, arguments = listOf(
                    navArgument(Screen.Chat.ARG_OPEN_AGENT_SHEET) { type = NavType.BoolType; defaultValue = false },
                    navArgument(Screen.Chat.ARG_CONNECTION_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument(Screen.Chat.ARG_SESSION_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument(Screen.Chat.ARG_PROFILE) { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument(Screen.Chat.ARG_PROACTIVE_CHAT_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
                )) { entry ->
                    Text(listOf(Screen.Chat.ARG_CONNECTION_ID, Screen.Chat.ARG_PROFILE, Screen.Chat.ARG_SESSION_ID)
                        .joinToString(" | ") { entry.arguments?.getString(it).orEmpty() })
                }
            }
        }
        val first = ChatNotificationTarget("connection / one", "profile & one", "same / session")
        val second = first.copy(connectionId = "connection / two", profile = "default")
        for (target in listOf(first, second)) {
            TurnCompleteNotifier.notifyTurnComplete(app, target, "turn", null, "Synthetic reply")
            val alert = manager.activeNotifications.single { it.tag == TurnCompleteNotifier.notificationTag(target) }
            val route = shadowOf(alert.notification.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_NAV_ROUTE)!!
            compose.runOnIdle { nav.navigate(route) { launchSingleTop = true } }
            compose.onNodeWithText("${target.connectionId} | ${target.profile} | ${target.sessionId}").assertExists()
        }
        manager.cancelAll()
    }
}

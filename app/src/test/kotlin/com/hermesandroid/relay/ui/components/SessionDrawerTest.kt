package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.ChatSession
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class SessionDrawerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `reordered leading session remains visible after drawer refresh`() {
        var sessions by mutableStateOf(
            List(15) { index ->
                ChatSession(
                    sessionId = "session-$index",
                    title = "Session $index",
                    model = null,
                    lastActivityAt = 100L - index,
                )
            },
        )

        compose.setContent {
            MaterialTheme {
                SessionDrawerContent(
                    sessions = sessions,
                    currentSessionId = null,
                    isOpen = true,
                    onNewChat = {},
                    onSelectSession = {},
                    onDeleteSession = {},
                    onRenameSession = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag(SESSION_DRAWER_LIST_TAG)
            .performScrollToNode(hasText("Session 14"))
        compose.onNodeWithText("Session 14").assertIsDisplayed()

        compose.runOnIdle {
            sessions = sessions.map { session ->
                if (session.sessionId == "session-10") {
                    session.copy(title = "Now latest", lastActivityAt = 1_000L)
                } else {
                    session
                }
            }
        }

        compose.onNodeWithText("Now latest").assertIsDisplayed()
    }
}

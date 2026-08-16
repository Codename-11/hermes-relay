package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class ToolProgressCardInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `completed card expands during an active session and survives updates`() {
        var call by mutableStateOf(
            ToolCall(
                id = "completed-terminal",
                name = "terminal",
                args = "pwd",
                result = "workspace",
                success = true,
                isComplete = true,
            ),
        )
        val expansionChanges = mutableListOf<Boolean>()

        compose.setContent {
            MaterialTheme {
                ToolProgressCard(
                    toolCall = call,
                    onExpandedChange = { expansionChanges += it },
                )
            }
        }

        compose.onNodeWithText("workspace").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand").performTouchInput { click() }
        compose.onNodeWithText("workspace").assertExists()

        compose.runOnIdle { call = call.copy(outputRisk = "high") }

        compose.onNodeWithText("workspace").assertExists()
        compose.runOnIdle { assertEquals(listOf(true), expansionChanges) }
    }

    @Test
    fun `running card remains expanded when the live tool completes`() {
        var call by mutableStateOf(
            ToolCall(
                id = "generating:running-skill",
                name = "skill",
                args = "inspect active session",
                result = null,
                success = null,
                isComplete = false,
            ),
        )

        compose.setContent {
            MaterialTheme { ToolProgressCard(toolCall = call) }
        }

        compose.onNodeWithText("inspect active session").assertExists()
        compose.onNodeWithContentDescription("Collapse").performTouchInput { click() }
        compose.onNodeWithText("inspect active session").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand").performTouchInput { click() }
        compose.onNodeWithText("inspect active session").assertExists()

        // A live placeholder can adopt the real Gateway ID during
        // reconciliation. Expansion belongs to the logical call, not the ID.
        compose.runOnIdle { call = call.copy(id = "reconciled-running-skill") }
        compose.onNodeWithText("inspect active session").assertExists()

        compose.runOnIdle {
            call = call.copy(
                result = "skill completed",
                success = true,
                isComplete = true,
                completedAt = System.currentTimeMillis(),
            )
        }

        compose.onNodeWithText("inspect active session").assertExists()
        compose.onNodeWithText("skill completed").assertExists()
    }
}

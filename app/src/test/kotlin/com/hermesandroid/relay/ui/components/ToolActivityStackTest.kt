package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class ToolActivityStackTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `live summary shows phase counts and elapsed while details stay compact`() {
        compose.setContent {
            MaterialTheme {
                ToolActivityStack(
                    state = ToolActivityState(
                        phase = ToolActivityPhase.USING_TOOLS,
                        toolCalls = listOf(runningTool(), completedTool()),
                        thinkingContent = "Inspecting the workspace",
                        elapsedMillis = 8_900,
                    ),
                )
            }
        }

        compose.onNodeWithText("Running tools").assertExists()
        compose.onNodeWithText("1 active · 1 completed · 8s").assertExists()
        compose.onNodeWithText("Inspecting the workspace").assertDoesNotExist()
        compose.onNodeWithText("workspace result").assertDoesNotExist()
    }

    @Test
    fun `expansion renders thinking and existing tool cards`() {
        val expansionChanges = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                ToolActivityStack(
                    state = ToolActivityState(
                        phase = ToolActivityPhase.USING_TOOLS,
                        toolCalls = listOf(runningTool()),
                        thinkingContent = "Inspecting the workspace",
                    ),
                    onExpandedChange = { expansionChanges += it },
                )
            }
        }

        compose.onNodeWithContentDescription("Expand activity").performClick()

        compose.onNodeWithText("Thought process").assertExists()
        compose.onNodeWithText("Inspecting the workspace").assertExists()
        compose.onNodeWithText("Terminal").assertExists()
        compose.onNodeWithText("pwd").assertExists()
        compose.runOnIdle { assertEquals(listOf(true), expansionChanges) }
    }

    @Test
    fun `terminal phase transition collapses live details to used tool summary`() {
        var state by mutableStateOf(
            ToolActivityState(
                phase = ToolActivityPhase.USING_TOOLS,
                toolCalls = listOf(runningTool()),
                thinkingContent = "Inspecting the workspace",
            ),
        )
        val expansionChanges = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                ToolActivityStack(
                    state = state,
                    activityKey = "assistant-turn-1",
                    onExpandedChange = { expansionChanges += it },
                )
            }
        }

        compose.onNodeWithContentDescription("Expand activity").performClick()
        compose.onNodeWithText("Inspecting the workspace").assertExists()

        compose.runOnIdle {
            state = state.copy(
                phase = ToolActivityPhase.COMPLETED,
                toolCalls = listOf(completedTool()),
            )
        }

        compose.onNodeWithText("Activity complete").assertExists()
        compose.onNodeWithText("Used 1 tool").assertExists()
        compose.onNodeWithText("Inspecting the workspace").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(true, false), expansionChanges) }
    }

    @Test
    fun `accessibility state excludes timer and streamed thinking tokens`() {
        var state by mutableStateOf(
            ToolActivityState(
                phase = ToolActivityPhase.USING_TOOLS,
                toolCalls = listOf(runningTool(), completedTool()),
                thinkingContent = "first token",
                elapsedMillis = 1_000,
            ),
        )
        compose.setContent {
            MaterialTheme { ToolActivityStack(state = state) }
        }

        val stableState = SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            "Running tools, 1 active, 1 completed",
        )
        compose.onNodeWithContentDescription("Expand activity").assert(stableState)

        compose.runOnIdle {
            state = state.copy(
                thinkingContent = "first token followed by many more tokens",
                elapsedMillis = 45_000,
            )
        }

        compose.onNodeWithContentDescription("Expand activity").assert(stableState)
        compose.onNodeWithText("1 active · 1 completed · 45s").assertExists()
    }

    @Test
    fun `elapsed formatter remains concise`() {
        assertEquals("0s", formatToolActivityElapsed(-1))
        assertEquals("59s", formatToolActivityElapsed(59_999))
        assertEquals("1m 01s", formatToolActivityElapsed(61_000))
        assertEquals("2h 03m", formatToolActivityElapsed(7_380_000))
    }

    private fun runningTool() = ToolCall(
        id = "running-terminal",
        name = "terminal",
        args = "pwd",
        result = null,
        success = null,
        isComplete = false,
    )

    private fun completedTool() = ToolCall(
        id = "completed-terminal",
        name = "terminal",
        args = "pwd",
        result = "workspace result",
        success = true,
        isComplete = true,
    )
}

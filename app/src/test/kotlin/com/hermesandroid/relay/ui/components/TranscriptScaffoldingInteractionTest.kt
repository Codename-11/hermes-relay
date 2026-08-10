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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class TranscriptScaffoldingInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `thinking opens live and collapses when it settles`() {
        var streaming by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                ThinkingBlock("Reasoning detail", isStreaming = streaming)
            }
        }

        compose.onNodeWithText("Reasoning detail").assertExists()
        compose.runOnIdle { streaming = false }
        compose.onNodeWithText("Thought").assertExists()
        compose.onNodeWithText("Reasoning detail").assertDoesNotExist()
    }

    @Test
    fun `explicit thinking disclosure survives settlement`() {
        var streaming by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                ThinkingBlock("Reasoning detail", isStreaming = streaming)
            }
        }

        compose.onNodeWithContentDescription("Collapse").performTouchInput { click() }
        compose.onNodeWithContentDescription("Expand").performTouchInput { click() }
        compose.runOnIdle { streaming = false }
        compose.onNodeWithText("Reasoning detail").assertExists()
    }

    @Test
    fun `settled tool run is one collapsed summary with expandable rows`() {
        val calls = listOf(
            completedCall("read-1", "read_file"),
            completedCall("read-2", "open_file"),
        )
        compose.setContent {
            MaterialTheme {
                ToolActivityRun(calls = calls, live = false, detailed = false)
            }
        }

        compose.onNodeWithText("Read 2 items").assertExists()
        compose.onNodeWithText("read_file").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand").performTouchInput { click() }
        compose.onNodeWithText("read_file").assertExists()
        compose.onNodeWithText("open_file").assertExists()
    }

    private fun completedCall(id: String, name: String) = ToolCall(
        id = id,
        name = name,
        args = null,
        result = "ok",
        success = true,
        isComplete = true,
        uiKey = id,
    )
}

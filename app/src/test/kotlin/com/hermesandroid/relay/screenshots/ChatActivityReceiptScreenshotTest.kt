package com.hermesandroid.relay.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.ChatActivityChild
import com.hermesandroid.relay.data.ChatActivityKind
import com.hermesandroid.relay.data.ChatActivityPhase
import com.hermesandroid.relay.data.ChatActivityRecord
import com.hermesandroid.relay.data.ToolCall
import com.hermesandroid.relay.network.upstream.GatewayProcess
import com.hermesandroid.relay.ui.components.ChatActivityReceipt
import com.hermesandroid.relay.ui.components.GatewayBackgroundProcessStrip
import com.hermesandroid.relay.ui.components.GatewayBackgroundProcessSheet
import com.hermesandroid.relay.ui.components.SubagentPreviewVisibility
import com.hermesandroid.relay.ui.components.ToolProgressCard
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class ChatActivityReceiptScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun completedActivityStaysCompactAndOpensOnlyOnTap() {
        var opened = 0
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                Surface {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolProgressCard(ToolCall(
                            name = "delegate_task", args = null, result = "{\"status\":\"dispatched\"}",
                            success = true, isComplete = true,
                        ))
                        ChatActivityReceipt(
                            record(ChatActivityKind.SUBAGENTS, ChatActivityPhase.COMPLETE).copy(
                                children = listOf(
                                    ChatActivityChild("child-one", phase = ChatActivityPhase.COMPLETE, summary = "Private result"),
                                    ChatActivityChild("child-two", phase = ChatActivityPhase.FAILED),
                                ),
                            ),
                            onClick = { opened++ },
                        )
                        ChatActivityReceipt(record(ChatActivityKind.PROCESS, ChatActivityPhase.UNKNOWN), {})
                    }
                }
            }
        }
        assertEquals(0, opened)
        compose.onNodeWithText("Dispatched").assertExists()
        compose.onNodeWithText("Subagents · 1 Completed · 1 Failed").assertExists()
        compose.onNodeWithText("Private result").assertDoesNotExist()
        compose.onNodeWithText("Background command · Final state unavailable").assertExists()
        compose.onRoot().captureRoboImage("build/ui-regression/chat-activity-receipts.png")
        compose.onNodeWithText("View activity").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun completionRemovesActiveStripButLeavesTranscriptEntry() {
        val running = mutableStateOf(true)
        compose.setContent {
            HermesRelayTheme {
                Column {
                    GatewayBackgroundProcessStrip(
                        processes = listOf(GatewayProcess("process", "command", status = if (running.value) "running" else "completed")),
                        subagentActivities = emptyList(),
                        subagentPreviewVisibility = SubagentPreviewVisibility(),
                        loading = true,
                        onClick = {},
                    )
                    ChatActivityReceipt(
                        record(ChatActivityKind.PROCESS, if (running.value) ChatActivityPhase.RUNNING else ChatActivityPhase.COMPLETE),
                        {},
                    )
                }
            }
        }
        compose.onNodeWithText("Current chat activity").assertExists()
        compose.runOnIdle { running.value = false }
        compose.onNodeWithText("Current chat activity").assertDoesNotExist()
        compose.onNodeWithText("Background command · Completed").assertExists()
    }

    @Test
    fun recordedProcessPreviewOffersNoMutationControls() {
        compose.setContent {
            HermesRelayTheme {
                GatewayBackgroundProcessSheet(
                    processes = listOf(GatewayProcess("process", "Saved command", status = "completed", exitCode = 0)),
                    subagentActivities = emptyList(),
                    subagentChildPreview = null,
                    subagentPreviewVisibility = SubagentPreviewVisibility(),
                    loading = false,
                    stoppingProcessIds = emptySet(),
                    onRefresh = {},
                    onStop = {},
                    onDismissProcess = {},
                    onOpenSubagentChild = {},
                    onDismiss = {},
                    readOnlyHistory = true,
                    historyNotice = "Recorded activity. Output is unavailable.",
                )
            }
        }
        compose.onNodeWithText("Chat activity").assertExists()
        compose.onNodeWithText("Recorded activity. Output is unavailable.").assertExists()
        compose.onNodeWithText("Saved command").assertExists()
        compose.onNodeWithText("Stop").assertDoesNotExist()
        compose.onNodeWithText("Dismiss").assertDoesNotExist()
    }

    private fun record(kind: ChatActivityKind, phase: ChatActivityPhase) = ChatActivityRecord(
        id = "receipt-${kind.name}",
        scopeKey = "scope",
        sessionId = "session",
        kind = kind,
        sourceId = "source",
        title = "Activity",
        phase = phase,
        createdAt = 1L,
        updatedAt = 2L,
    )
}

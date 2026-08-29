package com.hermesandroid.relay.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.ui.components.GatewayBackgroundProcessSheet
import com.hermesandroid.relay.ui.components.SubagentPreviewVisibility
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.SubagentActivity
import com.hermesandroid.relay.viewmodel.SubagentActivityEvent
import com.hermesandroid.relay.viewmodel.SubagentActivityEventKind
import com.hermesandroid.relay.viewmodel.SubagentActivityPhase
import com.hermesandroid.relay.viewmodel.SubagentChildPreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class SubagentActivitySheetScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun concurrentLiveAgentsRenderAsReadOnlyActivity() {
        val activities = listOf(
            activity(0, 0, "Inspect Android event handling", SubagentActivityPhase.PROGRESS),
            activity(1, 1, "Review privacy boundaries", SubagentActivityPhase.INTERRUPTED),
        )
        val preview = SubagentChildPreview(
            activityKey = activities.first().stableKey,
            parentSessionId = "parent",
            parentScopeKey = "scope",
            childWatchAvailable = true,
            messages = listOf(
                ChatMessage("task", MessageRole.USER, "Trace the upstream child watch contract.", 1L),
                ChatMessage("answer", MessageRole.ASSISTANT, "The child-only history is available read-only.", 2L),
            ),
            running = true,
            status = "streaming",
        )
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                GatewayBackgroundProcessSheet(
                    processes = emptyList(),
                    subagentActivities = activities,
                    subagentChildPreview = preview,
                    subagentPreviewVisibility = SubagentPreviewVisibility(),
                    loading = false,
                    stoppingProcessIds = emptySet(),
                    onRefresh = {},
                    onStop = {},
                    onDismissProcess = {},
                    onOpenSubagentChild = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithContentDescription(
            "Inspect Android event handling, Working, agent 1 of 2",
        ).performClick()
        compose.onNodeWithText("Child history · live updates").assertExists()
        compose.onNodeWithText("The child-only history is available read-only.").assertExists()
        compose.onNodeWithText("Stop").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/ui-regression/subagent-activity-sheet.png")
    }

    private fun activity(
        laneId: Long,
        taskIndex: Int,
        goal: String,
        phase: SubagentActivityPhase,
    ) = SubagentActivity(
        laneId = laneId,
        turnId = "turn",
        taskIndex = taskIndex,
        taskCount = 2,
        goal = goal,
        phase = phase,
        childSessionId = "child-$taskIndex",
        profile = "default",
        events = listOf(
            SubagentActivityEvent(
                sequence = laneId,
                kind = SubagentActivityEventKind.UPDATE,
                text = if (phase == SubagentActivityPhase.INTERRUPTED) {
                    "Stopped safely"
                } else {
                    "Mapping Gateway events"
                },
                phase = phase,
                observedAtMillis = 1,
            ),
        ),
    )
}

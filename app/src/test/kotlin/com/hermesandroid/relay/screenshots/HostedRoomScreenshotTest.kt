package com.hermesandroid.relay.screenshots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.*
import com.hermesandroid.relay.ui.screens.HostedRoomContent
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.connection.HostedRoomViewState
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-432dpi")
class HostedRoomScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val capabilities = HostedRoomCapabilities(
        methods = setOf("groups.state", "groups.log", "groups.send", "groups.stop", "groups.retry", "groups.approve", "groups.attachment.put", "groups.attachment.read"),
        features = setOf("idempotent_send", "actor_identity", "monotonic_log"), driver = true)
    private val room = BotGroupRoom(key = "hosted:fixture:room", roomId = "room", name = "Shared review", hosted = true,
        route = BotGatewayRoute(BotGatewayRouteKey("fixture", "default"), "Local gateway"),
        members = listOf(BotGroupMember("Writer", memberId = "writer-1", handle = "writer"), BotGroupMember("Retired", memberId = "old", handle = "retired", retired = true)),
        messages = listOf(
            BotGroupMessage(id = "event-1", seq = 1, senderId = "desktop", senderKind = "user", senderName = "desktop", text = "@writer Review the launch plan.", atMs = 1000, threadId = "thread-a"),
            BotGroupMessage(id = "event-2", seq = 2, senderId = "writer-1", senderKind = "member", senderName = "Writer", text = "The draft is ready for review.", atMs = 2000, threadId = "thread-a"),
            BotGroupMessage(id = "event-3", seq = 3, senderId = "desktop", senderKind = "user", senderName = "desktop", text = "Separate budget discussion", atMs = 3000, threadId = "thread-b")))

    @Test fun writableThreadPreservesIdentityAndDispatchesExactControls() {
        var sends = 0
        var stopped = false
        var mention = ""
        compose.setContent { HermesRelayTheme {
            HostedRoomContent(HostedRoomViewState(room = room, capabilities = capabilities, ready = true, selectedThread = "thread-a", draft = "Review the attached notes"),
                onSend = { sends++ }, onDraft = { mention = it }, onAction = { action, _ -> stopped = action == null })
        } }
        compose.onNodeWithText("Send").assertIsEnabled().performClick()
        compose.onNodeWithText("Stop whole room").performClick()
        compose.onNodeWithText("@writer").performClick()
        compose.onNodeWithText("@retired").assertDoesNotExist()
        assertEquals(1, sends); assertTrue(stopped); assertTrue(mention.endsWith(" @writer "))
        compose.onNodeWithText("Reply in selected thread").assertExists()
        compose.onAllNodesWithText("Separate budget discussion").assertCountEquals(1) // Only its thread selector, no transcript row.
        capture("hosted-room-thread")
    }

    @Test fun olderGatewayExplainsDisabledComposer() {
        compose.setContent { HermesRelayTheme { HostedRoomContent(HostedRoomViewState(room = room)) } }
        compose.onNodeWithText("Send").assertDoesNotExist()
        compose.onNodeWithText("Stop whole room").assertIsNotEnabled()
        compose.onNodeWithText("This gateway provides a read-only room snapshot. Hosted history is unavailable.").assertExists()
        capture("hosted-room-unsupported")
    }

    @Test fun uncertainSendAndApprovalAreVisibleWithoutFakeCompletion() {
        val status = Json.parseToJsonElement("""{"blocked":true,"needs_attention":true,"counts":{"indeterminate":1},"pending_actions":[{"kind":"approval","member_id":"writer-1","task_id":"task-1","request_id":"request-1","execution_generation":2,"approval":{"description":"Inspect the working tree","command":"git status --short"}}]}""") as JsonObject
        compose.setContent { HermesRelayTheme { HostedRoomContent(HostedRoomViewState(room = room.copy(messages = emptyList()), capabilities = capabilities, ready = true,
            draft = "Keep this exact send", pendingId = "pending-send", status = status)) } }
        compose.onNodeWithText("Retry same send").assertIsEnabled()
        compose.onNodeWithText("Allow once").assertExists()
        compose.onNodeWithText("git status --short").assertExists()
        compose.onNodeWithText("Room status: Blocked").assertExists()
        capture("hosted-room-attention")
    }

    private fun capture(name: String) {
        val output = File("build/ui-evidence/$name.png"); output.parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(output.absolutePath)
    }
}

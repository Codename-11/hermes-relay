package com.hermesandroid.relay.screenshots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.*
import com.hermesandroid.relay.ui.screens.*
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

    @Test fun unavailableMemberAndCanonicalFailureHaveThreadScopedDetails() {
        val status = Json.parseToJsonElement("""{"peer_routes":[{"member_id":"writer-1","status":"unavailable"}]}""") as JsonObject
        val failure = Json.parseToJsonElement("""{"event_id":"failed-event","kind":"turn.failed","payload":{"member_id":"writer-1","thread_id":"thread-a","task_id":"task-failed","error":"Native worker is unavailable"}}""") as JsonObject
        var opened: String? = null
        compose.setContent { HermesRelayTheme(themePreference = "dark") {
            HostedRoomContent(HostedRoomViewState(room = room.copy(messages = emptyList()), capabilities = capabilities, ready = true,
                status = status, activity = listOf(failure)), onThread = { opened = it })
        } }
        compose.onNodeWithText("Writer: unavailable").assertExists()
        compose.onNodeWithText("@writer").assertIsNotEnabled()
        compose.onNodeWithText("Native worker is unavailable").assertExists()
        compose.onNodeWithText("Open activity thread").performClick()
        assertEquals("thread-a", opened)
        capture("hosted-room-failed-member-dark")
    }

    @Test fun exportClearlyIdentifiesImmutableSourceRetention() {
        compose.setContent { HermesRelayTheme { HostedRoomContent(HostedRoomViewState(room = room, ready = true)) } }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Export immutable source log").assertExists()
        compose.onNodeWithText("Original edited/deleted content is retained.").assertExists()
        capture("hosted-room-source-export")
    }

    @Test fun legacyReadTrackingIsExplicitlyLocalOnly() {
        compose.setContent { HermesRelayTheme { HostedRoomContent(HostedRoomViewState(room = room, capabilities = capabilities, ready = true)) } }
        compose.onNodeWithText("Local gateway | 3 unread (local-only)").assertExists()
        compose.onNodeWithText("Mark read (local-only)").assertExists()
    }

    @Test fun negotiatedReadTruthNeverPresentsFallbackAsSharedSuccess() {
        val shared = capabilities.copy(methods = capabilities.methods + setOf("groups.read.get", "groups.read.mark"), features = capabilities.features + "room_read_cursors_v1")
        val current = androidx.compose.runtime.mutableStateOf(HostedRoomViewState(room = room, capabilities = shared, ready = true, serverUnread = 7))
        compose.setContent { HermesRelayTheme { HostedRoomContent(current.value) } }
        compose.onNodeWithText("Local gateway | 7 unread (shared)").assertExists()
        compose.onNodeWithText("Mark read").assertIsEnabled()
        capture("mobile-ui-final-shared-read")
        compose.runOnIdle { current.value = current.value.copy(serverUnread = null, readError = "Reader service unavailable") }
        compose.onNodeWithText("Local gateway | Shared read state unavailable").assertExists()
        compose.onNodeWithText("Reader service unavailable").assertExists()
        compose.onNodeWithText("Local gateway | 3 unread (shared)").assertDoesNotExist()
        capture("mobile-ui-final-read-unavailable")
    }

    @Test fun authorAffordancesUseServerIdentityNotDisplayNamesAndFailClosed() {
        val own = room.messages.first().copy(revision = 4)
        val other = own.copy(id = "other", seq = 4, senderId = "different", senderName = own.senderName)
        val caps = HostedRoomCapabilities(methods = setOf("groups.state", "groups.history", "groups.message.edit", "groups.message.delete", "groups.message.react"), features = setOf("message_history_projection_v1", "message_mutations_v1"), driver = true)
        val current = androidx.compose.runtime.mutableStateOf(HostedRoomViewState(room = room.copy(messages = listOf(own, other)), capabilities = caps, ready = true, reader = Json.parseToJsonElement("""{"kind":"user","id":"desktop"}""") as JsonObject))
        compose.setContent { HermesRelayTheme { HostedRoomContent(current.value) } }
        compose.onNodeWithTag("edit:event-1").assertExists()
        compose.onNodeWithTag("edit:other").assertDoesNotExist()
        compose.onNodeWithTag("delete:other").assertDoesNotExist()
        compose.onNodeWithText("Edited · revision 4").assertExists()
        compose.runOnIdle { current.value = current.value.copy(reader = JsonObject(emptyMap())) }
        compose.onNodeWithTag("edit:event-1").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(ready = false) }
        compose.onNodeWithTag("react:event-1").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val output = File("build/ui-evidence/$name.png"); output.parentFile?.mkdirs()
        if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) compose.onNode(isDialog()).captureRoboImage(output.absolutePath)
        else compose.onRoot().captureRoboImage(output.absolutePath)
    }
}

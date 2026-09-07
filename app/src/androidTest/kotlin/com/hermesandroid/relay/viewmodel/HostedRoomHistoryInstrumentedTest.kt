package com.hermesandroid.relay.viewmodel

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hermesandroid.relay.data.*
import com.hermesandroid.relay.network.upstream.*
import com.hermesandroid.relay.ui.screens.*
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.connection.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real Compose -> controller -> WebSocket, synthetic authority, no live core/device claim. */
class HostedRoomHistoryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun canonicalSearchAndExactMessageActionsUseProductionRoute() {
        val fixture = AndroidGatewayContractFixture()
        val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requests = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val rawRoom = obj("""{"room_id":"history-room","name":"History fixture","revision":1,"members":[]}""")
        var message = obj("""{"event_id":"message-own","seq":1,"revision":4,"actor":{"kind":"user","id":"desktop"},"text":"Current edited text","thread_id":"thread-a","deleted":false,"reactions":[]}""")
        val other = obj("""{"event_id":"message-other","seq":2,"revision":2,"actor":{"kind":"member","id":"worker","display_name":"desktop"},"text":"Other author text","thread_id":"thread-a","deleted":false,"reactions":[]}""")
        fixture.hostedRoomHandler = { method, params ->
            requests.add(method to params)
            when (method) {
                "groups.capabilities" -> obj("""{"driver":true,"methods":["groups.state","groups.history","groups.history.search","groups.message.edit","groups.message.delete","groups.message.react","groups.read.get","groups.read.mark"],"features":["message_history_projection_v1","message_history_search_v1","message_mutations_v1","room_read_cursors_v1"]}""")
                "groups.state" -> buildJsonObject { put("room", rawRoom) }
                "groups.history" -> buildJsonObject { put("messages", JsonArray(listOf(message, other))); put("cursor", 10); put("snapshot_seq", 10); put("has_more", false) }
                "groups.read.get", "groups.read.mark" -> buildJsonObject { put("room_id", "history-room"); params["thread_id"]?.let { put("thread_id", it) }; put("reader", obj("""{"kind":"user","id":"desktop"}""")); put("through_seq", 10); put("unread_count", 2) }
                "groups.history.search" -> buildJsonObject { val first = params.roomLong("after_seq") == 0L; put("messages", JsonArray(listOf(if (first) message else other))); put("cursor", if (first) 1 else 10); put("snapshot_seq", 10); put("has_more", first) }
                "groups.message.edit", "groups.message.delete", "groups.message.react" -> {
                    message = when (method) {
                        "groups.message.edit" -> JsonObject(message + mapOf("text" to params.getValue("text"), "revision" to JsonPrimitive(6)))
                        "groups.message.delete" -> JsonObject(message + mapOf("text" to JsonPrimitive(""), "deleted" to JsonPrimitive(true), "revision" to JsonPrimitive(8)))
                        else -> JsonObject(message + ("reactions" to if (params.roomBool("present")) JsonArray(listOf(buildJsonObject { put("reaction", params.getValue("reaction")); put("actors", JsonArray(listOf(obj("""{"kind":"user","id":"desktop"}""")))) })) else JsonArray(emptyList())))
                    }
                    buildJsonObject { put("event", buildJsonObject { put("room_id", "history-room") }); put("message", message) }
                }
                else -> JsonObject(emptyMap())
            }
        }
        val client = GatewayChatClient(initialDashboardClient = DashboardApiClient(fixture.server.url("/").toString().trimEnd('/'), OkHttpClient()), fixedSessionProfile = "default", okHttpClient = OkHttpClient(), callbackDispatcher = { Handler(Looper.getMainLooper()).post(it) }, scope = networkScope)
        val controller = HostedRoomController(acquire = { Result.success(UpstreamTransportController.RouteGatewayLease(client) {}) })
        val room = hostedRoom(rawRoom, BotGatewayRoute(BotGatewayRouteKey("fixture", "default"), "Fixture"))
        try {
            compose.setContent { HermesRelayTheme(themePreference = "dark") { HostedRoomRoute(room, controller, {}) } }
            compose.waitUntil(10_000) { controller.state.value.serverUnread == 2 }
            compose.onNodeWithText("Search canonical history").performTextInput("text")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Search messages").performClick()
            compose.waitUntil(10_000) { controller.state.value.searchHasMore }
            compose.onNodeWithText("Search results · snapshot 10").assertExists()
            compose.onNodeWithText("Load more results").performScrollTo().performClick()
            compose.waitUntil(10_000) { controller.state.value.searchResults.size == 2 }
            val search = requests.filter { it.first == "groups.history.search" }.map { it.second }
            assertEquals(2, search.size); assertEquals(1L, search[1].roomLong("after_seq")); assertEquals(10L, search[1].roomLong("snapshot_seq"))
            capture("mobile-ui-final-search-dark-api36")
            compose.onNodeWithText("Current history").performScrollTo().performClick()
            compose.onNodeWithTag("edit:message-own").performScrollTo().performClick()
            compose.onNodeWithText("Replacement text").performTextReplacement("Edited from Android")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            capture("mobile-ui-final-edit-dark-api36")
            compose.onNodeWithText("Save edit").performClick()
            compose.waitUntil(10_000) { controller.state.value.room?.messages?.first()?.revision == 6L }
            val edit = requests.single { it.first == "groups.message.edit" }.second
            assertEquals("message-own", edit.roomString("target_event_id")); assertEquals(4L, edit.roomLong("expected_revision")); assertEquals("Edited from Android", edit.roomString("text")); assertEquals("history-room", edit.roomString("room_id"))
            compose.onNodeWithTag("react:message-own").performScrollTo().performClick()
            compose.onNodeWithText("Reaction").performTextInput("like")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            capture("mobile-ui-final-reaction-dialog-dark-api36")
            compose.onNodeWithText("Add reaction").performClick()
            compose.waitUntil(10_000) { controller.state.value.room?.messages?.first()?.reactions?.isNotEmpty() == true }
            compose.onNodeWithText("Remove like (1)").performScrollTo().assertIsDisplayed()
            capture("mobile-ui-final-reaction-present-dark-api36")
            compose.onNodeWithText("Remove like (1)").performClick()
            compose.waitUntil(10_000) { requests.count { it.first == "groups.message.react" } == 2 && controller.state.value.room?.messages?.first()?.reactions?.isEmpty() == true }
            assertEquals(listOf(true, false), requests.filter { it.first == "groups.message.react" }.map { it.second.roomBool("present") })
            compose.onNodeWithTag("delete:message-own").performScrollTo().performClick()
            capture("mobile-ui-final-delete-dark-api36")
            compose.onNodeWithText("Delete message").performClick()
            compose.waitUntil(10_000) { controller.state.value.room?.messages?.first()?.deleted == true }
            compose.onNode(hasText("Message deleted") and !hasClickAction()).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Edited from Android").assertDoesNotExist()
            compose.onNodeWithTag("edit:message-other").assertDoesNotExist()
            val delete = requests.single { it.first == "groups.message.delete" }.second
            assertEquals("message-own", delete.roomString("target_event_id")); assertEquals(6L, delete.roomLong("expected_revision"))
            capture("mobile-ui-final-tombstone-dark-api36")
            for (method in listOf("session.resume", "session.activate", "session.create", "prompt.submit")) assertEquals(0, fixture.rpcCount(method))
        } finally { controller.close(); client.shutdown(); networkScope.cancel(); fixture.shutdown() }
    }

    @Test fun settingsCannotRetargetAnotherRoomWithTheSameRevision() {
        val capabilities = HostedRoomCapabilities(methods = setOf("groups.state", "groups.log", "groups.rename"), features = setOf("rename_revision"))
        val first = BotGroupRoom(key = "room-a", roomId = "a", name = "First room", revision = 1, hosted = true)
        val state = androidx.compose.runtime.mutableStateOf(HostedRoomViewState(room = first, capabilities = capabilities, ready = true))
        val controller = HostedRoomController(acquire = { Result.failure(IllegalStateException("No fixture RPC expected")) })
        compose.setContent { HermesRelayTheme { HostedRoomManagementDialog(state.value, emptyList(), controller, {}, {}) } }
        compose.onNodeWithText("Save name").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(room = first.copy(key = "room-b", roomId = "b")) }
        compose.onNodeWithText("Save name").assertIsNotEnabled()
        capture("mobile-ui-final-room-binding-api36")
    }

    @Test fun exactEditorRejectsRevisionDriftAndRoomSwitchDismissesIt() {
        val own = BotGroupMessage(id = "own", seq = 1, revision = 2, senderId = "viewer", senderKind = "user", senderName = "Viewer", text = "Original", atMs = 0)
        val first = BotGroupRoom(key = "room-a", roomId = "a", name = "First room", hosted = true, messages = listOf(own))
        val capabilities = HostedRoomCapabilities(methods = setOf("groups.state", "groups.history", "groups.message.edit"), features = setOf("message_history_projection_v1", "message_mutations_v1"), driver = true)
        val state = androidx.compose.runtime.mutableStateOf(HostedRoomViewState(room = first, capabilities = capabilities, ready = true, reader = obj("""{"kind":"user","id":"viewer"}""")))
        compose.setContent { HermesRelayTheme { HostedRoomContent(state.value) } }
        compose.onNodeWithTag("edit:own").performScrollTo().performClick()
        compose.onNodeWithText("Save edit").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(room = first.copy(messages = listOf(own.copy(revision = 3)))) }
        compose.onNodeWithText("Save edit").assertIsNotEnabled()
        compose.onNodeWithText("Message or room changed. Close and reopen this action.").assertExists()
        capture("mobile-ui-final-revision-conflict-api36")
        compose.runOnIdle { state.value = state.value.copy(room = first.copy(key = "room-b", roomId = "b")) }
        compose.onNodeWithText("Save edit").assertDoesNotExist()
    }

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        // Native dialog window animations are outside the Compose idle clock.
        android.os.SystemClock.sleep(350)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val directory = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val output = File(directory, "$name.png"); output.parentFile?.mkdirs()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}

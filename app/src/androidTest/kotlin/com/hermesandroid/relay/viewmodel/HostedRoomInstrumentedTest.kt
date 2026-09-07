package com.hermesandroid.relay.viewmodel

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.hermesandroid.relay.data.*
import com.hermesandroid.relay.network.upstream.*
import com.hermesandroid.relay.ui.screens.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.connection.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Production route/controller/socket and real Activity lifecycle; synthetic room only. */
class HostedRoomInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun acceptedSendSurvivesLostResponseAndForegroundReturnWithoutSessionTakeover() {
        val fixture = AndroidGatewayContractFixture()
        val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = CopyOnWriteArrayList<JsonObject>()
        val rawRoom = Json.parseToJsonElement("""{"room_id":"instrumented-room","name":"Fixture shared room","revision":1,"members":[]}""") as JsonObject
        fixture.hostedRoomHandler = { method, params -> when (method) {
            "groups.capabilities" -> Json.parseToJsonElement("""{"driver":true,"methods":["groups.state","groups.log","groups.send"],"features":["idempotent_send","actor_identity","monotonic_log"]}""") as JsonObject
            "groups.state" -> buildJsonObject { put("room", rawRoom) }
            "groups.log" -> buildJsonObject { put("events", JsonArray(events.filter { it.roomLong("seq") > params.roomLong("since_seq") })); put("cursor", events.size); put("has_more", false) }
            "groups.send" -> {
                val id = hostedUserEventId(params.roomString("event_id"))
                if (events.none { it.roomString("event_id") == id }) events.add(buildJsonObject {
                    put("room_id", "instrumented-room"); put("seq", 1); put("event_id", id); put("kind", "message.user")
                    put("actor", buildJsonObject { put("kind", "user"); put("id", "desktop") })
                    put("payload", JsonObject((params["payload"] as JsonObject) + ("thread_id" to JsonPrimitive(id))))
                })
                null
            }
            else -> JsonObject(emptyMap())
        } }
        val client = GatewayChatClient(initialDashboardClient = DashboardApiClient(fixture.server.url("/").toString().trimEnd('/'), OkHttpClient()),
            fixedSessionProfile = "default", okHttpClient = OkHttpClient(), callbackDispatcher = { Handler(Looper.getMainLooper()).post(it) },
            scope = networkScope, rpcTimeoutMs = 300)
        val saved = mutableMapOf<String, String>()
        val controller = HostedRoomController(acquire = { Result.success(UpstreamTransportController.RouteGatewayLease(client) {}) },
            readLocal = { saved[it] }, writeLocal = { key, value -> saved[key] = value })
        val room = hostedRoom(rawRoom, BotGatewayRoute(BotGatewayRouteKey("fixture", "default"), "Fixture"))
        try {
            compose.setContent { HermesRelayTheme { HostedRoomRoute(room, controller, {}) } }
            compose.waitUntil(10_000) { controller.state.value.canSend }
            compose.onNodeWithText("New thread message").performTextInput("Rendered canonical room message")
            compose.waitUntil(5_000) { controller.state.value.draft.isNotBlank() }
            compose.onNodeWithText("Send").performClick()
            compose.waitUntil(10_000) { controller.state.value.room?.messages?.size == 1 && controller.state.value.pendingId == null }
            compose.onNodeWithText("Rendered canonical room message").assertIsDisplayed()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText("Rendered canonical room message").assertIsDisplayed()
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithText("Room settings").performClick()
            compose.onNodeWithText("Room name").assertIsDisplayed()
            compose.onNodeWithText("Save name").assertIsNotEnabled()
            capture("hosted-room-settings-api36")
            compose.onNodeWithText("Done").performClick()
            assertEquals(1, fixture.rpcCount("groups.send"))
            for (method in listOf("session.resume", "session.activate", "session.create", "prompt.submit", "session.interrupt")) assertEquals(0, fixture.rpcCount(method))
        } finally { controller.close(); client.shutdown(); networkScope.cancel(); fixture.shutdown() }
    }
    private val capabilities = HostedRoomCapabilities(methods = setOf("groups.state", "groups.log"), driver = true)
    private val room = BotGroupRoom(key = "hosted:fixture:room", roomId = "room", name = "Shared review", hosted = true,
        route = BotGatewayRoute(BotGatewayRouteKey("fixture", "default"), "Local gateway"),
        members = listOf(BotGroupMember("Writer", memberId = "writer-1", handle = "writer"), BotGroupMember("Reviewer", memberId = "reviewer-1", handle = "reviewer")))

    @Test fun roomCreationOffersAvailableMembersAndStableSelection() {
        val bots = listOf("writer", "reviewer").map { BotRosterEntry(Profile(name = it, model = "fixture"), it, room.route) }
        var created = false
        compose.setContent { HermesRelayTheme(themePreference = "dark") {
            Box(Modifier.fillMaxSize()) { CreateHostedRoomDialog(bots, true, {}, { id, title, selected -> created = id.isNotBlank() && title == "Research" && selected.size == 2; Result.success(Unit) }) }
        } }
        compose.onNodeWithText("Room name").performTextInput("Research")
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Create room").assertIsEnabled().performClick()
        compose.waitForIdle(); assertTrue(created)
        capture("hosted-room-create-dark")
    }

    @Test fun settingsRejectRevisionDriftInRenderedDialog() {
        val controller = HostedRoomController(acquire = { Result.failure(IllegalStateException("Unused fixture gateway")) })
        val mutable = mutableStateOf(HostedRoomViewState(room = room.copy(revision = 1), ready = true,
            capabilities = capabilities.copy(methods = capabilities.methods + setOf("groups.rename", "groups.members.update"), features = capabilities.features + setOf("rename_revision", "local_membership_revision"))))
        compose.setContent { HermesRelayTheme(themePreference = "dark") { Box(Modifier.fillMaxSize()) { HostedRoomManagementDialog(mutable.value, emptyList(), controller, {}, {}) } } }
        compose.onNodeWithText("Save name").assertIsEnabled()
        compose.runOnIdle { mutable.value = mutable.value.copy(room = room.copy(revision = 2)) }
        compose.onNodeWithText("Save name").assertIsNotEnabled()
        compose.onNodeWithText("Room changed while editing. Close and reopen settings before saving.").assertExists()
        capture("hosted-room-settings-conflict-dark")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) {
            "Run through the Gradle managed-device lane to collect rendered evidence"
        }
        val output = File(directory, "$name.png"); output.parentFile?.mkdirs()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}

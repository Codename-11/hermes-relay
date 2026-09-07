package com.hermesandroid.relay.viewmodel.connection

import com.hermesandroid.relay.data.*
import com.hermesandroid.relay.network.upstream.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class HostedRoomControllerTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject
    private val roomJson = obj("""{"room_id":"room","name":"Shared review","revision":1,"members":[{"member_id":"writer-id","profile":"writer","handle":"writer","display_name":"Writer"}]}""")

    @Test fun responseLossReconcilesCanonicalIdentityWithoutInventingReply() = runBlocking {
        withController { controller, harness, events ->
            controller.open(hostedRoom(roomJson, route))
            controller.editDraft("@writer Review this")
            controller.send()
            assertEquals(1, events.size)
            assertEquals(1, controller.state.value.room!!.messages.size)
            assertEquals("desktop", controller.state.value.room!!.messages.single().senderId)
            assertEquals("", controller.state.value.draft)
            assertNull(controller.state.value.pendingId)
            assertTrue(harness.rpcLog.none { it.first.startsWith("session.") || it.first == "prompt.submit" })
        }
    }

    @Test fun draftsSurviveReloadAndStayBoundToRoomAndThread() = runBlocking {
        withController { controller, _, _ ->
            val room = hostedRoom(roomJson, route)
            controller.open(room)
            controller.editDraft("fresh draft")
            controller.selectThread("thread-a")
            controller.editDraft("reply draft")
            controller.selectThread(null)
            assertEquals("fresh draft", controller.state.value.draft)
            controller.close()
            controller.open(room)
            controller.selectThread("thread-a")
            assertEquals("reply draft", controller.state.value.draft)
            controller.open(hostedRoom(JsonObject(roomJson + ("room_id" to JsonPrimitive("other"))), route))
            assertEquals("", controller.state.value.draft)
        }
    }

    @Test fun oldGatewayDoesNotEnableComposerOrSend() = runBlocking {
        withController { controller, harness, _ ->
            harness.hostedRoomHandler = { _, _ -> obj("{}") }
            controller.open(hostedRoom(roomJson, route))
            controller.editDraft("test")
            controller.send()
            assertFalse(controller.state.value.canSend)
            assertTrue(harness.rpcLog.none { it.first == "groups.send" })
            assertNotNull(controller.state.value.explanation)
        }
    }

    @Test fun roomSwitchDuringDurableSendCannotRetargetIngress() = runBlocking {
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withController(persistHook = { value ->
            if (value.contains("event_id")) { saving.complete(Unit); release.await() }
        }) { controller, harness, _ ->
            controller.open(hostedRoom(roomJson, route))
            controller.editDraft("belongs to first room")
            val sending = launch { controller.send() }
            saving.await()
            val opening = launch { controller.open(hostedRoom(JsonObject(roomJson + ("room_id" to JsonPrimitive("other"))), route)) }
            yield()
            release.complete(Unit)
            sending.join(); opening.join()
            assertTrue("Old draft must never be sent to another room", harness.rpcLog.none { it.first == "groups.send" })
        }
    }

    @Test fun retryAfterUnacceptedResponseLossUsesSamePayloadAndEventId() = runBlocking {
        withController { controller, harness, _ ->
            val original = harness.hostedRoomHandler!!
            var lose = true
            harness.hostedRoomHandler = { method, params ->
                if (method == "groups.send" && lose) { lose = false; null } else original(method, params)
            }
            controller.open(hostedRoom(roomJson, route))
            controller.editDraft("immutable")
            controller.send()
            assertNotNull(controller.state.value.pendingId)
            controller.editDraft("must not replace uncertain payload")
            controller.send()
            val sends = harness.rpcLog.filter { it.first == "groups.send" }.map { it.second }
            assertEquals(2, sends.size)
            assertEquals(sends[0], sends[1])
            assertNull(controller.state.value.pendingId)
        }
    }

    @Test fun attachmentKeepsExactBytesAndSendsOnlyCanonicalDescriptor() = runBlocking {
        withController { controller, harness, _ ->
            val original = harness.hostedRoomHandler!!
            val bytes = byteArrayOf(0, 1, 2, 13, 10, -1, 127)
            val receipt = obj("""{"attachment_id":"file-1","kind":"file","name":"sample.bin","mime":"application/octet-stream","size":7,"sha256":"fixture","state":"stored","created_at":1000,"idempotent":false}""")
            harness.hostedRoomHandler = { method, params -> when (method) {
                "groups.capabilities" -> obj("""{"driver":true,"methods":["groups.state","groups.log","groups.send","groups.attachment.put","groups.attachment.read"],"features":["idempotent_send","actor_identity","monotonic_log"]}""")
                "groups.attachment.put" -> { assertArrayEquals(bytes, java.util.Base64.getDecoder().decode(params.roomString("content_base64"))); buildJsonObject { put("attachment", receipt) } }
                "groups.attachment.read" -> buildJsonObject { put("attachment", receipt); put("content_base64", java.util.Base64.getEncoder().encodeToString(bytes)) }
                else -> original(method, params)
            } }
            val room = hostedRoom(roomJson, route)
            controller.open(room)
            controller.upload(room.key, null, "sample.bin", "application/octet-stream", bytes)
            controller.send()
            val payload = harness.rpcLog.single { it.first == "groups.send" }.second["payload"] as JsonObject
            assertEquals(setOf("attachment_id", "kind", "name", "mime", "size"), payload.roomObjects("attachments").single().keys)
            val message = controller.state.value.room!!.messages.single()
            assertArrayEquals(bytes, controller.readAttachment(message.id!!, message.attachments.single()).getOrThrow())
            val read = harness.rpcLog.single { it.first == "groups.attachment.read" }.second
            assertEquals("room", read.roomString("room_id")); assertEquals(message.id, read.roomString("event_id"))
            assertEquals("viewer", read.roomString("purpose"))
        }
    }

    @Test fun exactActionsNeverUseSessionControlsOrForeignTask() = runBlocking {
        withController { controller, harness, _ ->
            val original = harness.hostedRoomHandler!!
            val retry = obj("""{"kind":"retry","task_id":"task-a"}""")
            val approval = obj("""{"kind":"approval","task_id":"task-b","member_id":"writer-id","request_id":"request-a","execution_generation":3}""")
            harness.hostedRoomHandler = { method, params -> when (method) {
                "groups.capabilities" -> obj("""{"driver":true,"methods":["groups.state","groups.log","groups.send","groups.stop","groups.retry","groups.approve"],"features":["idempotent_send","actor_identity","monotonic_log"]}""")
                "groups.state" -> buildJsonObject { put("room", roomJson); put("driver_status", buildJsonObject { put("pending_actions", JsonArray(listOf(retry, approval))) }) }
                else -> original(method, params)
            } }
            controller.open(hostedRoom(roomJson, route))
            controller.act(obj("""{"kind":"retry","task_id":"foreign"}"""))
            controller.act(retry); controller.act(approval, "once"); controller.act()
            val calls = harness.rpcLog.filter { it.first in setOf("groups.stop", "groups.retry", "groups.approve") }
            assertEquals(3, calls.size)
            assertTrue(calls.all { it.second.roomString("room_id") == "room" })
            assertEquals("task-a", calls.single { it.first == "groups.retry" }.second.roomString("task_id"))
            val approved = calls.single { it.first == "groups.approve" }.second
            assertEquals("request-a", approved.roomString("request_id")); assertEquals(3, approved.roomLong("execution_generation").toInt())
            assertFalse(calls.single { it.first == "groups.stop" }.second.containsKey("thread_id"))
            assertTrue(harness.rpcLog.none { it.first.startsWith("session.") })
        }
    }

    private val route = BotGatewayRoute(BotGatewayRouteKey("connection", "default"), "Fixture")
    private suspend fun withController(persistHook: suspend (String) -> Unit = {}, block: suspend (HostedRoomController, GatewayClientHarness, MutableList<JsonObject>) -> Unit) {
        val harness = GatewayClientHarness()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = GatewayChatClient(initialDashboardClient = DashboardApiClient(harness.server.url("/").toString().trimEnd('/'), OkHttpClient()),
            fixedSessionProfile = "default", okHttpClient = OkHttpClient(), callbackDispatcher = { it() }, scope = scope, rpcTimeoutMs = 200)
        val events = mutableListOf<JsonObject>()
        val saved = mutableMapOf<String, String>()
        harness.hostedRoomHandler = { method, params -> when (method) {
            "groups.capabilities" -> obj("""{"driver":true,"methods":["groups.state","groups.log","groups.send"],"features":["idempotent_send","actor_identity","monotonic_log"]}""")
            "groups.state" -> buildJsonObject { put("room", roomJson) }
            "groups.log" -> buildJsonObject { put("events", JsonArray(events.filter { it.roomLong("seq") > params.roomLong("since_seq") })); put("cursor", events.size); put("has_more", false) }
            "groups.send" -> {
                val id = hostedUserEventId(params.roomString("event_id"))
                val payload = params["payload"] as JsonObject
                if (events.none { it.roomString("event_id") == id }) events.add(buildJsonObject {
                    put("room_id", params.roomString("room_id")); put("event_id", id); put("seq", events.size + 1)
                    put("kind", "message.user"); put("actor", obj("""{"kind":"user","id":"desktop"}"""))
                    put("payload", JsonObject(payload + ("thread_id" to (payload["thread_id"] ?: JsonPrimitive(id)))))
                })
                null // Accepted and persisted, but the response is lost on the real WebSocket.
            }
            else -> obj("{}")
        } }
        val controller = HostedRoomController(acquire = { Result.success(UpstreamTransportController.RouteGatewayLease(client) {}) },
            readLocal = { saved[it] }, writeLocal = { key, value -> saved[key] = value; persistHook(value) })
        try { block(controller, harness, events) } finally { client.shutdown(); scope.cancel(); harness.shutdown() }
    }
}

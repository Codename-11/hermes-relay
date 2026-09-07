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
            assertNull(controller.state.value.operationError)
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
            var uploadAttempts = 0
            val bytes = byteArrayOf(0, 1, 2, 13, 10, -1, 127)
            val receipt = obj("""{"attachment_id":"file-1","kind":"file","name":"sample.bin","mime":"application/octet-stream","size":7,"sha256":"fixture","state":"stored","created_at":1000,"idempotent":false}""")
            harness.hostedRoomHandler = { method, params -> when (method) {
                "groups.capabilities" -> obj("""{"driver":true,"methods":["groups.state","groups.log","groups.send","groups.attachment.put","groups.attachment.read"],"features":["idempotent_send","actor_identity","monotonic_log"]}""")
                "groups.attachment.put" -> { assertArrayEquals(bytes, java.util.Base64.getDecoder().decode(params.roomString("content_base64"))); uploadAttempts++; if (uploadAttempts == 1) null else buildJsonObject { put("attachment", receipt) } }
                "groups.attachment.read" -> buildJsonObject { put("attachment", receipt); put("content_base64", java.util.Base64.getEncoder().encodeToString(bytes)) }
                else -> original(method, params)
            } }
            val room = hostedRoom(roomJson, route)
            controller.open(room)
            controller.upload(room.key, null, "sample.bin", "application/octet-stream", bytes)
            controller.upload(room.key, null, "sample.bin", "application/octet-stream", bytes)
            val uploads = harness.rpcLog.filter { it.first == "groups.attachment.put" }.map { it.second }
            assertEquals(2, uploads.size); assertEquals(uploads[0], uploads[1])
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

    @Test fun managementUsesRevisionAndRetainsImmutableMemberTargets() = runBlocking {
        withController { controller, harness, _ ->
            val original = harness.hostedRoomHandler!!
            val managed = obj("""{"room_id":"room","name":"Shared review","revision":7,"members":[{"member_id":"a","profile":"a","handle":"a","target":{"kind":"local","profile":"a"}},{"member_id":"b","profile":"b","handle":"b","target":{"kind":"local","profile":"b"}},{"member_id":"c","profile":"c","handle":"c"}]}""")
            harness.hostedRoomHandler = { method, params -> when (method) {
                "groups.capabilities" -> obj("""{"driver":true,"methods":["groups.state","groups.log","groups.rename","groups.members.update","groups.disband"],"features":["rename_revision","local_membership_revision"]}""")
                "groups.state" -> buildJsonObject { put("room", managed) }
                else -> original(method, params)
            } }
            controller.open(hostedRoom(managed, route))
            controller.rename("Revised title").getOrThrow()
            controller.changeMembers(setOf("a", "b"), emptyList()).getOrThrow()
            val renamed = harness.rpcLog.single { it.first == "groups.rename" }.second
            assertEquals(7L, renamed.roomLong("expected_revision")); assertEquals("room", renamed.roomString("room_id"))
            val changed = harness.rpcLog.single { it.first == "groups.members.update" }.second
            assertEquals(7L, changed.roomLong("expected_revision"))
            assertEquals(managed.roomObjects("members").take(2), changed.roomObjects("members"))
            val exported = obj(controller.exportHistory().toString(Charsets.UTF_8))
            assertEquals(managed, exported["room"])
            assertEquals("connection", exported.roomString("connection_id"))
        }
    }

    @Test fun readWatermarksAgreeAcrossRoomAndThreadAfterReplay() = runBlocking {
        withController { controller, _, events ->
            for (index in 1..2) events.add(buildJsonObject {
                put("room_id", "room"); put("event_id", "event-$index"); put("seq", index); put("kind", "message.member")
                put("actor", obj("""{"kind":"member","id":"writer-id"}"""))
                put("payload", buildJsonObject { put("thread_id", "thread-$index"); put("text", "Reply $index") })
            })
            controller.open(hostedRoom(roomJson, route))
            assertEquals(2, controller.state.value.unread)
            controller.selectThread("thread-1"); controller.markRead(); controller.selectThread(null)
            assertEquals(1, controller.state.value.unread)
            controller.markRead(); controller.selectThread("thread-2")
            assertEquals(0, controller.state.value.unread)
            controller.refresh()
            assertEquals(0, controller.state.value.unread)
        }
    }

    @Test fun localDraftEditingDoesNotWaitForPassiveHistoryFetch() = runBlocking {
        withController { controller, harness, _ ->
            controller.open(hostedRoom(roomJson, route))
            val original = harness.hostedRoomHandler!!
            val entered = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            harness.hostedRoomHandler = { method, params ->
                if (method == "groups.log") { entered.complete(Unit); release.await(3, java.util.concurrent.TimeUnit.SECONDS) }
                original(method, params)
            }
            val fetching = launch { controller.refresh() }
            entered.await()
            try { withTimeout(100) { controller.editDraft("Typing remains responsive") } }
            finally { release.countDown() }
            fetching.join()
            assertEquals("Typing remains responsive", controller.state.value.draft)
        }
    }

    @Test fun oversizedTextRemainsEditableWithoutCreatingPendingIngress() = runBlocking {
        withController { controller, harness, _ ->
            controller.open(hostedRoom(roomJson, route))
            controller.editDraft("x".repeat(65_537)); controller.send()
            assertNull(controller.state.value.pendingId)
            assertTrue(harness.rpcLog.none { it.first == "groups.send" })
            controller.editDraft("Corrected")
            assertEquals("Corrected", controller.state.value.draft)
        }
    }

    @Test fun staleRoomCallbacksAndDisplayedRevisionCannotMutateCurrentOwner() = runBlocking {
        withController { controller, harness, _ ->
            val original = harness.hostedRoomHandler!!
            harness.hostedRoomHandler = { method, params -> when (method) {
                "groups.capabilities" -> obj("""{"driver":true,"methods":["groups.state","groups.log","groups.send","groups.rename"],"features":["idempotent_send","actor_identity","monotonic_log","rename_revision"]}""")
                else -> original(method, params)
            } }
            val room = hostedRoom(roomJson, route)
            controller.open(room)
            controller.editDraft("wrong room", "hosted:foreign:room")
            controller.send("hosted:foreign:room")
            assertEquals("", controller.state.value.draft)
            assertTrue(controller.rename("stale name", room.key, expectedRevision = 0).isFailure)
            assertTrue(harness.rpcLog.none { it.first == "groups.send" || it.first == "groups.rename" })
        }
    }

    @Test fun fileCatalogUsesViewerQueryAndOpaqueContinuation() = runBlocking {
        withController { controller, harness, _ ->
            val original = harness.hostedRoomHandler!!
            harness.hostedRoomHandler = { method, params -> when (method) {
                "groups.capabilities" -> obj("""{"driver":true,"methods":["groups.state","groups.log","groups.attachment.list"],"features":[]}""")
                "groups.attachment.list" -> if (!params.containsKey("cursor")) obj("""{"items":[],"next_cursor":"opaque-page-2"}""") else obj("""{"items":[{"attachment_id":"file-a","event_id":"event-a","name":"report.pdf","mime":"application/pdf","size":4}],"next_cursor":null}""")
                else -> original(method, params)
            } }
            controller.open(hostedRoom(roomJson, route))
            controller.searchFiles("report").getOrThrow()
            controller.searchFiles("report", more = true).getOrThrow()
            val calls = harness.rpcLog.filter { it.first == "groups.attachment.list" }.map { it.second }
            assertEquals("opaque-page-2", calls[1].roomString("cursor"))
            assertTrue(calls.all { it.roomString("purpose") == "viewer" && it.roomString("query") == "report" && it.roomString("room_id") == "room" })
            assertEquals("file-a", controller.state.value.files.single().roomString("attachment_id"))
        }
    }

    @Test fun canonicalTaskFailuresRemainActivityAndPreserveRealOriginFields() = runBlocking {
        withController { controller, _, events ->
            events.add(obj("""{"room_id":"room","seq":1,"event_id":"reply","kind":"message.member","actor":{"kind":"member","id":"writer-id","profile":"writer","connection_id":"peer-a"},"payload":{"text":"Reply","thread_id":"thread-a"}}"""))
            events.add(obj("""{"room_id":"room","seq":2,"event_id":"failure","kind":"turn.failed","actor":{"kind":"gateway","id":"gateway"},"payload":{"member_id":"writer-id","thread_id":"thread-a","error":"Unavailable"}}"""))
            controller.open(hostedRoom(roomJson, route))
            assertEquals(1, controller.state.value.room!!.messages.size)
            assertEquals("writer / peer-a", controller.state.value.room!!.messages.single().senderSource)
            assertEquals("failure", controller.state.value.activity.single().roomString("event_id"))
        }
    }

    @Test fun pickedMentionCannotSilentlyBecomeBroadcastAfterMemberRemoval() = runBlocking {
        withController { controller, harness, _ ->
            controller.open(hostedRoom(roomJson, route))
            val member = controller.state.value.room!!.members.first()
            controller.mention(member.memberId!!)
            val original = harness.hostedRoomHandler!!
            harness.hostedRoomHandler = { method, params ->
                if (method == "groups.state") buildJsonObject {
                    put("room", JsonObject(roomJson + ("members" to JsonArray(emptyList()))))
                } else original(method, params)
            }
            controller.send()
            assertTrue(harness.rpcLog.none { it.first == "groups.send" })
            assertNull(controller.state.value.pendingId)
            assertTrue(controller.state.value.operationError!!.contains("mention", ignoreCase = true))
            controller.editDraft("Intentional message to the current room")
            controller.send()
            assertEquals(1, harness.rpcLog.count { it.first == "groups.send" })
        }
    }

    @Test fun sendClickCannotRetargetAnotherThreadDuringCanonicalRefresh() = runBlocking {
        withController { controller, harness, _ ->
            controller.open(hostedRoom(roomJson, route))
            controller.selectThread("thread-b"); controller.editDraft("Draft B")
            controller.selectThread("thread-a"); controller.editDraft("Draft A")
            val original = harness.hostedRoomHandler!!
            val entered = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            harness.hostedRoomHandler = { method, params ->
                if (method == "groups.log") { entered.complete(Unit); release.await(3, java.util.concurrent.TimeUnit.SECONDS) }
                original(method, params)
            }
            val sending = launch { controller.send() }
            entered.await()
            controller.selectThread("thread-b")
            release.countDown(); sending.join()
            assertTrue("Send from A must never submit B", harness.rpcLog.none { it.first == "groups.send" })
            assertEquals("Draft B", controller.state.value.draft)
            controller.selectThread("thread-a")
            assertEquals("Draft A", controller.state.value.draft)
            controller.send()
            val payload = harness.rpcLog.single { it.first == "groups.send" }.second["payload"] as JsonObject
            assertEquals("thread-a", payload.roomString("thread_id")); assertEquals("Draft A", payload.roomString("text"))
        }
    }

    @Test fun sendClickCannotSubmitEditsMadeDuringCanonicalRefresh() = runBlocking {
        withController { controller, harness, _ ->
            controller.open(hostedRoom(roomJson, route)); controller.editDraft("Clicked draft")
            val original = harness.hostedRoomHandler!!
            val entered = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            harness.hostedRoomHandler = { method, params ->
                if (method == "groups.log") { entered.complete(Unit); release.await(3, java.util.concurrent.TimeUnit.SECONDS) }
                original(method, params)
            }
            val sending = launch { controller.send() }
            entered.await(); controller.editDraft("Still editing, not submitted")
            release.countDown(); sending.join()
            assertTrue("Only the clicked draft is authorized", harness.rpcLog.none { it.first == "groups.send" })
            assertEquals("Still editing, not submitted", controller.state.value.draft)
            controller.send()
            assertEquals(1, harness.rpcLog.count { it.first == "groups.send" })
            assertEquals("", controller.state.value.draft)
        }
    }

    @Test fun androidUploadBoundRejectsBeforeEncodingOrDispatch() = runBlocking {
        withController { controller, harness, _ ->
            val original = harness.hostedRoomHandler!!
            harness.hostedRoomHandler = { method, params ->
                if (method == "groups.capabilities") obj("""{"driver":true,"methods":["groups.state","groups.log","groups.send","groups.attachment.put"],"features":["idempotent_send","actor_identity","monotonic_log"]}""")
                else original(method, params)
            }
            controller.open(hostedRoom(roomJson, route))
            controller.upload(controller.state.value.room!!.key, null, "large.bin", "application/octet-stream", ByteArray(12_000_001))
            assertTrue(harness.rpcLog.none { it.first == "groups.attachment.put" })
            assertTrue(controller.state.value.operationError!!.contains("12 MB"))
            controller.refresh(); assertTrue(controller.state.value.ready)
        }
    }

    @Test fun desktopMutationUsesCurrentProjectionNotUnsupportedRawReader() = runBlocking {
        withController { controller, harness, _ ->
            val original = harness.hostedRoomHandler!!
            var edited = "Edited on Desktop"
            harness.hostedRoomHandler = { method, params -> when(method) {
                "groups.capabilities" -> obj("""{"driver":true,"methods":["groups.state","groups.log","groups.history"],"features":["message_history_projection_v1","message_mutations_v1"]}""")
                "groups.history" -> buildJsonObject {
                    put("messages", JsonArray(listOf(obj("""{"event_id":"root","seq":1,"thread_id":"root","actor":{"kind":"user","id":"desktop"},"original_text":"Original","text":"$edited","revision":3,"deleted":false}"""))))
                    put("cursor",3); put("snapshot_seq",3); put("latest_seq",3); put("has_more",false)
                }
                "groups.log" -> null // New core rejects an unnegotiated mutation reader.
                else -> original(method, params)
            } }
            controller.open(hostedRoom(roomJson, route))
            assertTrue("Desktop-edited room must remain readable", controller.state.value.ready)
            assertEquals(edited, controller.state.value.room!!.messages.single().text)
            edited = "Edited again"
            controller.refresh()
            assertEquals(edited, controller.state.value.room!!.messages.single().text)
            assertTrue(harness.rpcLog.none { it.first == "groups.log" })
            assertTrue(harness.rpcLog.filter { it.first == "groups.history" }.all { it.second.roomLong("after_seq") == 0L })
        }
    }

    @Test fun deletedProjectionDoesNotExposeOriginalTextOrAttachments() {
        val projected = obj("""{"event_id":"root","seq":1,"thread_id":"root","actor":{"kind":"user","id":"desktop"},"original_text":"Sensitive original","text":null,"revision":3,"deleted":true,"attachments":[{"attachment_id":"file"}],"reactions":[{"reaction":"👍","actors":[{"kind":"user","id":"desktop"}]}]}""")
        val message = hostedProjectedMessage(projected, hostedRoom(roomJson, route))!!
        assertEquals("Message deleted", message.text)
        assertTrue(message.attachments.isEmpty())
        assertNull(hostedMessage(obj("""{"kind":"message.edited","actor":{"kind":"user","id":"desktop"},"payload":{"target_event_id":"root","text":"not a new message"}}"""), hostedRoom(roomJson, route)))
    }

    @Test fun sharedReadCursorUsesServerIdentityAndExactVisibleThread() = runBlocking {
        withController { controller, harness, _ ->
            val original = harness.hostedRoomHandler!!
            var through = 1L
            harness.hostedRoomHandler = { method, params -> when(method) {
                "groups.capabilities" -> obj("""{"methods":["groups.state","groups.log","groups.history","groups.read.get","groups.read.mark"],"features":["message_history_projection_v1","room_read_cursors_v1"]}""")
                "groups.history" -> obj("""{"messages":[{"event_id":"root","seq":1,"thread_id":"root","actor":{"kind":"member","id":"writer-id"},"text":"Read on desktop","revision":1}],"cursor":3,"snapshot_seq":3,"has_more":false}""")
                "groups.read.get", "groups.read.mark" -> {
                    if(method.endsWith("mark")) through = params.roomLong("through_seq")
                    obj("""{"room_id":"room","thread_id":${params["thread_id"] ?: JsonNull},"reader":{"kind":"user","id":"desktop"},"through_seq":$through,"unread_count":0,"latest_seq":3}""")
                }
                else -> original(method, params)
            } }
            controller.open(hostedRoom(roomJson, route))
            assertEquals("Desktop read state must replace local unread", 0, controller.state.value.unread)
            controller.selectThread("root"); controller.markRead()
            val read = harness.rpcLog.single { it.first == "groups.read.mark" }.second
            assertEquals("root", read.roomString("thread_id")); assertEquals(3L, read.roomLong("through_seq"))
            assertFalse(read.containsKey("reader")); assertFalse(read.containsKey("actor"))
            controller.editDraft("Keep shared cursor while editing")
            assertEquals(0, controller.state.value.unread)
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

package com.hermesandroid.relay.viewmodel.connection

import com.hermesandroid.relay.data.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

private fun Long?.orZero(): Long = this ?: 0L

data class HostedRoomViewState(
    val room: BotGroupRoom? = null,
    val capabilities: HostedRoomCapabilities = HostedRoomCapabilities(),
    val selectedThread: String? = null,
    val draft: String = "",
    val attachments: List<JsonObject> = emptyList(),
    val pendingId: String? = null,
    val busy: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null,
    val operationError: String? = null,
    val status: JsonObject = JsonObject(emptyMap()),
    val readSeq: Long = 0,
    val threadRead: Map<String, Long> = emptyMap(),
) {
    val canSend: Boolean get() = ready && !busy && capabilities.writable && room?.stale != true
    val visibleMessages: List<BotGroupMessage> get() = room?.messages.orEmpty().filter {
        selectedThread == null || it.threadId == selectedThread
    }
    val unread: Int get() = visibleMessages.count { it.seq > maxOf(readSeq, threadRead[it.threadId].orZero()) }
    val explanation: String? get() = when {
        !capabilities.readable -> "This gateway provides a read-only room snapshot. Hosted history is unavailable."
        !ready -> "Room is offline. Reconnect to verify canonical history before sending."
        !capabilities.writable -> "Sending is unavailable: this gateway needs an active hosted-room driver and idempotent send support."
        else -> null
    }
}

/** One explicit gateway/room owner; never resumes, activates or claims native sessions. */
class HostedRoomController(
    private val acquire: (BotGatewayRoute) -> Result<UpstreamTransportController.RouteGatewayLease>,
    private val readLocal: suspend (String) -> String? = { null },
    private val writeLocal: suspend (String, String) -> Unit = { _, _ -> },
) {
    private val mutable = MutableStateFlow(HostedRoomViewState())
    val state: StateFlow<HostedRoomViewState> = mutable
    private val operations = Mutex()
    private val refreshLock = Mutex()
    private var history = emptyList<JsonObject>()
    private var historyCursor = 0L
    private var historyEpoch = 0L
    private var generation = 0L
    private var local = JsonObject(emptyMap())
    private var localKey = ""
    private val drafts get() = local["drafts"] as? JsonObject ?: JsonObject(emptyMap())
    private fun threadKey() = state.value.selectedThread.orEmpty()
    private fun draftRecord() = drafts[threadKey()] as? JsonObject ?: JsonObject(emptyMap())

    fun close() { generation++; mutable.value = HostedRoomViewState() }

    suspend fun open(room: BotGroupRoom) {
        val token = ++generation
        mutable.value = HostedRoomViewState(room = room)
        operations.withLock {
            if (token != generation) return
            history = emptyList()
            historyCursor = 0L
            historyEpoch = 0L
            localKey = JsonArray(listOf(room.route?.connectionId.orEmpty(), room.route?.profileName.orEmpty(), room.roomId.orEmpty()).map(::JsonPrimitive)).toString()
            local = runCatching { readLocal(localKey)?.let { Json.parseToJsonElement(it) as JsonObject } }.getOrNull() ?: JsonObject(emptyMap())
            if (token != generation) return
            showDraft()
        }
        refresh()
    }

    private fun showDraft() {
        val record = draftRecord()
        mutable.value = state.value.copy(draft = record.roomString("text"),
            attachments = record.roomObjects("attachments"), pendingId = record.roomString("event_id").ifBlank { null },
            readSeq = (drafts[""] as? JsonObject)?.roomLong("read_seq") ?: 0,
            threadRead = drafts.mapValues { (_, value) -> (value as? JsonObject)?.roomLong("read_seq") ?: 0 })
    }

    private suspend fun saveRecord(record: JsonObject) {
        local = JsonObject(local + ("drafts" to JsonObject(drafts + (threadKey() to record))))
        val token = generation
        writeLocal(localKey, local.toString())
        if (token == generation) showDraft()
    }

    suspend fun selectThread(threadId: String?) = operations.withLock {
        mutable.value = state.value.copy(selectedThread = threadId)
        showDraft()
    }

    suspend fun editDraft(text: String) = operations.withLock {
        if (state.value.pendingId != null) return@withLock
        saveRecord(JsonObject(draftRecord() + ("text" to JsonPrimitive(text))))
    }

    suspend fun markRead() = operations.withLock {
        val last = state.value.visibleMessages.maxOfOrNull { it.seq } ?: return@withLock
        saveRecord(JsonObject(draftRecord() + ("read_seq" to JsonPrimitive(last))))
    }

    private suspend fun <T> withOwner(block: suspend (BotGroupRoom, com.hermesandroid.relay.network.upstream.GatewayChatClient, Long) -> T): T? {
        val room = state.value.room ?: return null
        val route = room.route ?: return null
        val token = generation
        return acquire(route).getOrThrow().use { lease -> block(room, lease.client, token) }
    }

    suspend fun refresh() = refreshLock.withLock {
        val token = generation
        val owner = state.value.room ?: return@withLock
        val route = owner.route ?: return@withLock
        try {
            acquire(route).getOrThrow().use { lease ->
                val client = lease.client
                val capabilities = HostedRoomCapabilities.parse(client.hostedRoomRpc("groups.capabilities").getOrThrow())
                if (token != generation) return@use
                if (!capabilities.readable) {
                    operations.withLock { if (token == generation) mutable.value = state.value.copy(capabilities = capabilities, ready = false) }
                    return@use
                }
                val response = client.hostedRoomRpc("groups.state", buildJsonObject { put("room_id", owner.roomId) }).getOrThrow()
                val raw = response["room"] as? JsonObject ?: error("Missing canonical room")
                check(raw.roomString("room_id") == owner.roomId) { "Room identity mismatch" }
                val canonical = hostedRoom(raw, route)
                val epoch = raw.roomLong("authority_epoch")
                val reuse = epoch == historyEpoch && (!raw.containsKey("latest_seq") || raw.roomLong("latest_seq") >= historyCursor)
                var cursor = if (reuse) historyCursor else 0L
                val events = (if (reuse) history else emptyList()).toMutableList()
                do {
                    val page = client.hostedRoomRpc("groups.log", buildJsonObject {
                        put("room_id", owner.roomId); put("since_seq", cursor); put("limit", 100)
                    }).getOrThrow()
                    val batch = page.roomObjects("events")
                    var previous = cursor
                    for (event in batch) {
                        check(event.roomString("room_id") == owner.roomId && event.roomLong("seq") > previous) { "Foreign or nonmonotonic room history" }
                        previous = event.roomLong("seq")
                    }
                    events.addAll(batch)
                    val next = page.roomLong("cursor")
                    check(next >= previous && (!page.roomBool("has_more") || next > cursor)) { "Room history cursor did not advance" }
                    cursor = next
                    if (token != generation) return@use
                } while (page.roomBool("has_more"))
                operations.withLock {
                    if (token != generation) return@withLock
                    history = events; historyCursor = cursor; historyEpoch = epoch
                    mutable.value = state.value.copy(capabilities = capabilities,
                        room = canonical.copy(messages = events.mapNotNull { hostedMessage(it, canonical) }.distinctBy { it.id }),
                        status = response["driver_status"] as? JsonObject ?: JsonObject(emptyMap()), ready = true, error = null)
                    val reconciled = drafts.mapValues { (_, value) ->
                        val record = value as? JsonObject ?: return@mapValues value
                        val id = record.roomString("event_id")
                        if (id.isNotBlank() && events.any { it.roomString("event_id") == hostedUserEventId(id) })
                            buildJsonObject { put("read_seq", record.roomLong("read_seq")) }
                        else record
                    }
                    local = JsonObject(local + ("drafts" to JsonObject(reconciled)))
                    writeLocal(localKey, local.toString())
                    if (token == generation) showDraft()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (token == generation) mutable.value = state.value.copy(ready = false, error = e.message ?: "Room unavailable")
        }
    }

    suspend fun send() {
        operations.withLock {
            if (!state.value.canSend) return@withLock
            if (state.value.draft.isBlank() && state.value.attachments.isEmpty()) return@withLock
            val token = generation
            try {
                require(state.value.draft.toByteArray(Charsets.UTF_8).size <= 65_536) { "Message exceeds the 64 KiB text limit" }
                require(state.value.attachments.size <= 8) { "A message supports at most eight files" }
                val record = draftRecord()
                val id = state.value.pendingId ?: UUID.randomUUID().toString()
                val payload = record["payload"] as? JsonObject ?: buildJsonObject {
                    put("text", state.value.draft.trim())
                    state.value.selectedThread?.let { put("thread_id", it) }
                    if (state.value.attachments.isNotEmpty()) put("attachments", JsonArray(state.value.attachments.map { attachment -> JsonObject(attachment.filterKeys { it in setOf("attachment_id", "kind", "name", "mime", "size") }) }))
                }
                // Persist before ingress; subsequent attempts reuse both ID and exact payload.
                saveRecord(JsonObject(record + mapOf("event_id" to JsonPrimitive(id), "payload" to payload)))
                if (token != generation) return@withLock
                mutable.value = state.value.copy(busy = true)
                withOwner { room, client, _ ->
                    client.hostedRoomRpc("groups.send", buildJsonObject {
                        put("room_id", room.roomId); put("event_id", id); put("payload", payload)
                    }).getOrThrow()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (token == generation) mutable.value = state.value.copy(operationError = if (state.value.pendingId == null) e.message else
                    "Send outcome uncertain: ${e.message}. Refresh history or retry this same send.")
            } finally {
                if (token == generation) mutable.value = state.value.copy(busy = false)
            }
        }
        refresh()
    }
    suspend fun act(action: JsonObject? = null, choice: String? = null) {
        operations.withLock {
            val current = state.value
            if (!current.ready || current.busy) return@withLock
            val kind = action?.roomString("kind") ?: "stop"
            val method = when (kind) { "retry" -> "groups.retry"; "approval" -> "groups.approve"; "stop" -> "groups.stop"; else -> return@withLock }
            if (method !in current.capabilities.methods || !current.capabilities.driver) return@withLock
            if (action != null && action !in current.status.roomObjects("pending_actions")) return@withLock
            val token = generation
            try {
                val commands = local["commands"] as? JsonObject ?: JsonObject(emptyMap())
                val commandKey = action?.toString() ?: "stop:${UUID.randomUUID()}"
                val commandId = commands.roomString(commandKey).ifBlank { UUID.randomUUID().toString() }
                local = JsonObject(local + ("commands" to JsonObject(commands + (commandKey to JsonPrimitive(commandId)))))
                writeLocal(localKey, local.toString())
                if (token != generation) return@withLock
                withOwner { room, client, _ ->
                    client.hostedRoomRpc(method, buildJsonObject {
                        put("room_id", room.roomId)
                        when (kind) {
                            "stop" -> put("cancel_id", commandId)
                            "retry" -> { require(!action!!.roomString("task_id").isBlank()); put("task_id", action.roomString("task_id")); put("command_id", commandId) }
                            "approval" -> {
                                require(choice in setOf("once", "deny"))
                                for (key in listOf("member_id", "task_id", "request_id")) {
                                    require(action!!.roomString(key).isNotBlank()); put(key, action.roomString(key))
                                }
                                require(action!!.roomLong("execution_generation") > 0)
                                put("execution_generation", action.roomLong("execution_generation")); put("choice", choice)
                            }
                        }
                    }).getOrThrow()
                }
                if (token == generation) {
                    local = JsonObject(local + ("commands" to JsonObject(commands - commandKey)))
                    writeLocal(localKey, local.toString())
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (token == generation) mutable.value = state.value.copy(operationError = e.message ?: "Room action failed")
            }
        }
        refresh()
    }

    suspend fun upload(expectedRoomKey: String, expectedThread: String?, name: String, mime: String, bytes: ByteArray) = operations.withLock {
        val current = state.value
        if (current.room?.key != expectedRoomKey || current.selectedThread != expectedThread) return@withLock
        if (!current.canSend || current.pendingId != null || "groups.attachment.put" !in current.capabilities.methods) return@withLock
        val token = generation
        try {
            require(current.attachments.size < 8) { "A message supports at most eight files" }
            require(bytes.isNotEmpty() && bytes.size <= 15_000_000) { "Files must be between 1 byte and 15 MB" }
            require(current.attachments.sumOf { it.roomLong("size") } + bytes.size <= 25_000_000) { "Message files exceed 25 MB" }
            val kind = when { mime.startsWith("image/") -> "image"; mime == "application/pdf" -> "pdf"; else -> "file" }
            withOwner { room, client, _ ->
                val result = client.hostedRoomRpc("groups.attachment.put", buildJsonObject {
                    put("room_id", room.roomId); put("upload_id", UUID.randomUUID().toString())
                    put("kind", kind); put("name", name); put("mime", mime)
                    put("content_base64", java.util.Base64.getEncoder().encodeToString(bytes))
                }).getOrThrow()
                if (token != generation) return@withOwner
                val attachment = result["attachment"] as? JsonObject ?: error("Missing attachment receipt")
                require(attachment.roomString("attachment_id").isNotBlank() && attachment.roomLong("size") == bytes.size.toLong()) { "Invalid attachment receipt" }
                saveRecord(JsonObject(draftRecord() + ("attachments" to JsonArray(current.attachments + attachment))))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (token == generation) mutable.value = state.value.copy(operationError = e.message ?: "Upload failed")
        }
    }

    suspend fun discardDraft() = operations.withLock {
        saveRecord(buildJsonObject { put("read_seq", draftRecord().roomLong("read_seq")) })
    }

    suspend fun removeAttachment(id: String) = operations.withLock {
        if (state.value.pendingId == null) saveRecord(JsonObject(draftRecord() +
            ("attachments" to JsonArray(state.value.attachments.filterNot { it.roomString("attachment_id") == id }))))
    }

    suspend fun readAttachment(eventId: String, attachment: JsonObject): Result<ByteArray> = operations.withLock {
        runCatching {
            val current = state.value
            require(current.ready && "groups.attachment.read" in current.capabilities.methods) { "File reads are unavailable on this gateway" }
            require(current.room?.messages.orEmpty().any { it.id == eventId && attachment in it.attachments }) { "Attachment is not part of this room event" }
            val token = generation
            withOwner { room, client, _ ->
                val response = client.hostedRoomRpc("groups.attachment.read", buildJsonObject {
                    put("room_id", room.roomId); put("event_id", eventId)
                    put("attachment_id", attachment.roomString("attachment_id")); put("purpose", "viewer")
                }).getOrThrow()
                check(token == generation) { "Room changed while reading file" }
                val receipt = response["attachment"] as? JsonObject ?: error("Missing attachment metadata")
                check(receipt.roomString("attachment_id") == attachment.roomString("attachment_id")) { "File identity mismatch" }
                val encoded = response.roomString("content_base64")
                require(encoded.length <= 20_000_000) { "File exceeds 15 MB" }
                java.util.Base64.getDecoder().decode(encoded).also {
                    check(it.size.toLong() == attachment.roomLong("size")) { "File size mismatch" }
                }
            } ?: error("Room unavailable")
        }
    }

}

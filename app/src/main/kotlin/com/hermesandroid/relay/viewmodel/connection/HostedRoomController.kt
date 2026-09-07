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
    val serverUnread: Int? = null,
    val reader: JsonObject = JsonObject(emptyMap()),
    val readError: String? = null,
    val threadRead: Map<String, Long> = emptyMap(),
    val roomRecord: JsonObject = JsonObject(emptyMap()),
    val activity: List<JsonObject> = emptyList(),
    val files: List<JsonObject> = emptyList(),
    val fileCursor: String? = null,
    val fileQuery: String = "",
) {
    val canSend: Boolean get() = ready && !busy && capabilities.writable && room?.stale != true
    val visibleMessages: List<BotGroupMessage> get() = room?.messages.orEmpty().filter {
        selectedThread == null || it.threadId == selectedThread
    }
    val unread: Int get() = serverUnread ?: visibleMessages.count { it.seq > maxOf(readSeq, threadRead[it.threadId].orZero()) }
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
            readSeq = if (state.value.capabilities.sharedRead) state.value.readSeq else (drafts[""] as? JsonObject)?.roomLong("read_seq") ?: 0,
            threadRead = drafts.mapValues { (_, value) -> (value as? JsonObject)?.roomLong("read_seq") ?: 0 })
    }

    private suspend fun saveRecord(record: JsonObject) {
        local = JsonObject(local + ("drafts" to JsonObject(drafts + (threadKey() to record))))
        val token = generation
        writeLocal(localKey, local.toString())
        if (token == generation) showDraft()
    }

    suspend fun selectThread(threadId: String?, expectedRoomKey: String? = state.value.room?.key) = operations.withLock {
        if (state.value.room?.key != expectedRoomKey) return@withLock
        mutable.value = state.value.copy(selectedThread = threadId, serverUnread = null)
                showDraft()
                if (state.value.capabilities.sharedRead) updateReadCursor()
    }

    suspend fun editDraft(text: String, expectedRoomKey: String? = state.value.room?.key) = operations.withLock {
        if (state.value.room?.key != expectedRoomKey) return@withLock
        if (state.value.pendingId != null) return@withLock
        val mentions = draftRecord()["mentions"] as? JsonObject ?: JsonObject(emptyMap())
        val handles = Regex("@([A-Za-z0-9][A-Za-z0-9._:-]*)").findAll(text).map { it.groupValues[1].lowercase() }.toSet()
        saveRecord(JsonObject(draftRecord() + mapOf("text" to JsonPrimitive(text),
            "mentions" to JsonObject(mentions.filterValues { (it as? JsonPrimitive)?.contentOrNull?.lowercase() in handles }))))
        mutable.value = state.value.copy(operationError = null)
    }

    suspend fun mention(memberId: String, expectedRoomKey: String? = state.value.room?.key) = operations.withLock {
        if (state.value.room?.key != expectedRoomKey || state.value.pendingId != null) return@withLock
        val member = state.value.room?.members?.firstOrNull { it.memberId == memberId && !it.retired } ?: return@withLock
        val handle = member.handle ?: return@withLock
        val mentions = draftRecord()["mentions"] as? JsonObject ?: JsonObject(emptyMap())
        saveRecord(JsonObject(draftRecord() + mapOf("text" to JsonPrimitive(state.value.draft + " @$handle "),
            "mentions" to JsonObject(mentions + (memberId to JsonPrimitive(handle))))))
    }

    suspend fun markRead(expectedRoomKey: String? = state.value.room?.key) = operations.withLock {
        if (state.value.room?.key != expectedRoomKey) return@withLock
        if (!state.value.ready) return@withLock
        if (state.value.capabilities.sharedRead) updateReadCursor(mark = true)
        else {
            val last = state.value.visibleMessages.maxOfOrNull { it.seq } ?: return@withLock
            saveRecord(JsonObject(draftRecord() + ("read_seq" to JsonPrimitive(last))))
        }
    }

    private suspend fun updateReadCursor(mark: Boolean = false) {
        val token = generation
        val thread = state.value.selectedThread
        try {
            withOwner { room, client, _ ->
                val result = client.hostedRoomRpc(if (mark) "groups.read.mark" else "groups.read.get", buildJsonObject {
                    put("room_id", room.roomId)
                    thread?.let { put("thread_id", it) }
                    if (mark) put("through_seq", historyCursor)
                }).getOrThrow()
                check(result.roomString("room_id") == room.roomId && result.roomString("thread_id") == thread.orEmpty()) { "Read cursor scope mismatch" }
                val reader = result["reader"] as? JsonObject ?: error("Missing server reader identity")
                check(reader.roomString("id").isNotBlank() && result.containsKey("unread_count")) { "Invalid server read cursor" }
                if (token == generation && thread == state.value.selectedThread) mutable.value = state.value.copy(
                    readSeq = result.roomLong("through_seq"), serverUnread = result.roomLong("unread_count").toInt(), reader = reader, readError = null)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (token == generation) mutable.value = state.value.copy(serverUnread = null, reader = JsonObject(emptyMap()), readError = e.message ?: "Shared read state unavailable")
        }
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
                val reuse = !capabilities.projection && epoch == historyEpoch && (!raw.containsKey("latest_seq") || raw.roomLong("latest_seq") >= historyCursor)
                var cursor = if (reuse) historyCursor else 0L
                val events = (if (reuse) history else emptyList()).toMutableList()
                var snapshot: Long? = null
                do {
                    val page = client.hostedRoomRpc(if (capabilities.projection) "groups.history" else "groups.log", buildJsonObject {
                                            put("room_id", owner.roomId); put(if (capabilities.projection) "after_seq" else "since_seq", cursor); put("limit", 100)
                                            if (capabilities.projection && snapshot != null) put("snapshot_seq", snapshot)
                                        }).getOrThrow()
                                        if (capabilities.projection) {
                                            check(page.containsKey("snapshot_seq")) { "Missing history snapshot" }
                                            if (snapshot != null) check(snapshot == page.roomLong("snapshot_seq")) { "History snapshot changed during paging" }
                                            snapshot = page.roomLong("snapshot_seq")
                                        }
                                        val batch = page.roomObjects(if (capabilities.projection) "messages" else "events")
                    var previous = cursor
                    for (event in batch) {
                        check((capabilities.projection || event.roomString("room_id") == owner.roomId) && event.roomLong("seq") > previous) { "Foreign or nonmonotonic room history" }
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
                    mutable.value = state.value.copy(capabilities = capabilities, roomRecord = raw,
                        activity = events.filter { it.roomString("kind").startsWith("turn.") || it.roomString("kind") in setOf("room.activity", "member.unavailable") }.takeLast(30),
                        room = canonical.copy(messages = events.mapNotNull { if (capabilities.projection) hostedProjectedMessage(it, canonical) else hostedMessage(it, canonical) }.distinctBy { it.id }),
                        status = response["driver_status"] as? JsonObject ?: JsonObject(emptyMap()), ready = true, error = null)
                    if (state.value.pendingId?.let { id -> events.any { it.roomString("event_id") == hostedUserEventId(id) } } == true) {
                        mutable.value = state.value.copy(operationError = null)
                    }
                    val reconciled = drafts.mapValues { (_, value) ->
                        val record = value as? JsonObject ?: return@mapValues value
                        val id = record.roomString("event_id")
                        if (id.isNotBlank() && events.any { it.roomString("event_id") == hostedUserEventId(id) })
                            buildJsonObject { put("read_seq", record.roomLong("read_seq")) }
                        else record
                    }
                    local = JsonObject(local + ("drafts" to JsonObject(reconciled)))
                    writeLocal(localKey, local.toString())
                    if (token == generation) {
                        showDraft()
                        if (capabilities.sharedRead) updateReadCursor()
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (token == generation) mutable.value = state.value.copy(ready = false, error = e.message ?: "Room unavailable")
        }
    }

    suspend fun send(
        expectedRoomKey: String? = state.value.room?.key,
        expectedThread: String? = state.value.selectedThread,
        expectedDraft: String = state.value.draft,
    ) {
        if (state.value.room?.key != expectedRoomKey || state.value.selectedThread != expectedThread || state.value.draft != expectedDraft) return
        val token = generation
        val clickedRecord = draftRecord()
        refresh()
        operations.withLock {
            if (token != generation || state.value.room?.key != expectedRoomKey) return@withLock
            val acceptedId = clickedRecord.roomString("event_id")
            if (acceptedId.isNotBlank() && history.any { it.roomString("event_id") == hostedUserEventId(acceptedId) }) return@withLock
            if (state.value.selectedThread != expectedThread || draftRecord() != clickedRecord) {
                mutable.value = state.value.copy(operationError = "Thread or draft changed before sending. Review it and press Send again.")
                return@withLock
            }
            if (!state.value.canSend) return@withLock
            if (state.value.draft.isBlank() && state.value.attachments.isEmpty()) return@withLock
            try {
                require(state.value.draft.toByteArray(Charsets.UTF_8).size <= 65_536) { "Message exceeds the 64 KiB text limit" }
                require(state.value.attachments.size <= 8) { "A message supports at most eight files" }
                val record = draftRecord()
                if (state.value.pendingId == null) {
                    val mentions = record["mentions"] as? JsonObject ?: JsonObject(emptyMap())
                    require(mentions.all { (id, handle) -> state.value.room?.members.orEmpty().any { !it.retired && it.memberId == id && it.handle == (handle as? JsonPrimitive)?.contentOrNull } }) {
                        "A selected mention changed or left the room. Remove it and choose an active member."
                    }
                }
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
    suspend fun act(action: JsonObject? = null, choice: String? = null, expectedRoomKey: String? = state.value.room?.key) {
        operations.withLock {
            if (state.value.room?.key != expectedRoomKey) return@withLock
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
                    mutable.value = state.value.copy(operationError = null)
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
            require(bytes.isNotEmpty() && bytes.size <= HOSTED_ROOM_ANDROID_UPLOAD_MAX_BYTES) { "Files must be between 1 byte and 12 MB on Android" }
            require(current.attachments.sumOf { it.roomLong("size") } + bytes.size <= 25_000_000) { "Message files exceed 25 MB" }
            val kind = when { mime.startsWith("image/") -> "image"; mime == "application/pdf" -> "pdf"; else -> "file" }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val uploadKey = JsonArray(listOf(name, mime, digest).map(::JsonPrimitive)).toString()
            val uploads = draftRecord()["uploads"] as? JsonObject ?: JsonObject(emptyMap())
            val uploadId = uploads.roomString(uploadKey).ifBlank { UUID.randomUUID().toString() }
            saveRecord(JsonObject(draftRecord() + ("uploads" to JsonObject(uploads + (uploadKey to JsonPrimitive(uploadId))))))
            if (token != generation) return@withLock
            withOwner { room, client, _ ->
                val result = client.hostedRoomRpc("groups.attachment.put", buildJsonObject {
                    put("room_id", room.roomId); put("upload_id", uploadId)
                    put("kind", kind); put("name", name); put("mime", mime)
                    put("content_base64", java.util.Base64.getEncoder().encodeToString(bytes))
                }).getOrThrow()
                if (token != generation) return@withOwner
                val attachment = result["attachment"] as? JsonObject ?: error("Missing attachment receipt")
                require(attachment.roomString("attachment_id").isNotBlank() && attachment.roomLong("size") == bytes.size.toLong()) { "Invalid attachment receipt" }
                saveRecord(JsonObject(draftRecord() + mapOf("attachments" to JsonArray(current.attachments + attachment), "uploads" to JsonObject(uploads - uploadKey))))
                if (token == generation) mutable.value = state.value.copy(operationError = null)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (token == generation) mutable.value = state.value.copy(operationError = e.message ?: "Upload failed")
        }
    }

    suspend fun rename(name: String, expectedRoomKey: String? = state.value.room?.key, expectedRevision: Long? = state.value.room?.revision): Result<Unit> = manage("groups.rename", buildJsonObject { put("name", name.trim()) }, "rename_revision", expectedRoomKey, expectedRevision)

    suspend fun changeMembers(keep: Set<String>, added: List<BotRosterEntry>, expectedRoomKey: String? = state.value.room?.key, expectedRevision: Long? = state.value.room?.revision): Result<Unit> {
        if (state.value.room?.key != expectedRoomKey) return Result.failure(IllegalStateException("Room changed before membership edit"))
        val current = state.value
        if (current.room?.revision != expectedRevision) return Result.failure(IllegalStateException("Room changed while editing. Reopen room settings."))
        if (added.any { it.route?.connectionId != current.room?.route?.connectionId || it.stale }) return Result.failure(IllegalArgumentException("Choose available members from this gateway"))
        val members = current.roomRecord.roomObjects("members").filter { it.roomString("member_id") in keep } + added.map { bot ->
            require(bot.route?.connectionId == current.room?.route?.connectionId && !bot.stale) { "Member belongs to another gateway" }
            buildJsonObject {
                put("member_id", UUID.nameUUIDFromBytes("${current.room?.roomId}:${bot.profile.name}".toByteArray(Charsets.UTF_8)).toString())
                put("profile", bot.profile.name); put("handle", bot.profile.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'))
                put("display_name", bot.displayName)
            }
        }
        if (members.size !in 2..6) return Result.failure(IllegalArgumentException("A room needs two to six members"))
        return manage("groups.members.update", buildJsonObject { put("members", JsonArray(members)) }, "local_membership_revision", expectedRoomKey, expectedRevision)
    }

    suspend fun disband(expectedRoomKey: String? = state.value.room?.key): Result<Unit> = manage("groups.disband", JsonObject(emptyMap()), expectedRoomKey = expectedRoomKey)

    private suspend fun manage(method: String, values: JsonObject, feature: String? = null, expectedRoomKey: String? = state.value.room?.key, expectedRevision: Long? = state.value.room?.revision): Result<Unit> {
        val token = generation
        val result = operations.withLock {
            runCatching {
                val current = state.value
                require(current.room?.key == expectedRoomKey && token == generation) { "Room changed before action" }
                require(current.ready && method in current.capabilities.methods && (feature == null || feature in current.capabilities.features)) { "This gateway does not support this room action" }
                val room = current.room ?: error("Room unavailable")
                if (method != "groups.disband") require(room.revision == expectedRevision) { "Room changed while editing. Reopen room settings." }
                val route = room.route ?: error("Gateway unavailable")
                val base = buildJsonObject {
                    put("room_id", room.roomId)
                    if (method != "groups.disband") put("expected_revision", expectedRevision)
                    for ((key, value) in values) put(key, value)
                }
                val commands = local["commands"] as? JsonObject ?: JsonObject(emptyMap())
                val key = "$method:$base"
                val id = commands.roomString(key).ifBlank { UUID.randomUUID().toString() }
                local = JsonObject(local + ("commands" to JsonObject(commands + (key to JsonPrimitive(id)))))
                writeLocal(localKey, local.toString())
                check(token == generation) { "Room changed before action" }
                acquire(route).getOrThrow().use { lease ->
                    lease.client.hostedRoomRpc(method, JsonObject(base + ((if (method == "groups.disband") "cancel_id" else "event_id") to JsonPrimitive(id)))).getOrThrow()
                }
                if (token == generation) {
                    local = JsonObject(local + ("commands" to JsonObject(commands - key)))
                    writeLocal(localKey, local.toString())
                }
            }
        }
        if (result.isSuccess && token == generation && method != "groups.disband") refresh()
        return result
    }

    suspend fun searchFiles(query: String, more: Boolean = false, expectedRoomKey: String? = state.value.room?.key): Result<Unit> = operations.withLock {
        runCatching {
            val token = generation
            val current = state.value
            require(current.room?.key == expectedRoomKey) { "Room changed before file search" }
            require(current.ready && "groups.attachment.list" in current.capabilities.methods) { "File search is unavailable on this gateway" }
            if (more && (current.fileCursor == null || current.fileQuery != query)) return@runCatching
            withOwner { room, client, _ ->
                val page = client.hostedRoomRpc("groups.attachment.list", buildJsonObject {
                    put("room_id", room.roomId); put("purpose", "viewer"); put("limit", 50); put("query", query)
                    if (more) put("cursor", current.fileCursor)
                }).getOrThrow()
                if (token == generation) mutable.value = state.value.copy(files =
                    ((if (more) current.files else emptyList()) + page.roomObjects("items")).distinctBy { "${it.roomString("event_id")}:${it.roomString("attachment_id")}" },
                    fileCursor = page.roomString("next_cursor").ifBlank { null }, fileQuery = query)
            }
            Unit
        }
    }

    suspend fun exportHistory(expectedRoomKey: String? = state.value.room?.key): ByteArray = operations.withLock {
        check(state.value.room?.key == expectedRoomKey) { "Room changed before export" }
        check(state.value.ready) { "Refresh canonical history before exporting" }
        buildJsonObject {
            put("format", "hermes-hosted-room-history-v1")
            put("connection_id", state.value.room?.route?.connectionId)
            put("profile", state.value.room?.route?.profileName)
            put("room", state.value.roomRecord); put("through_seq", historyCursor)
            put("events", JsonArray(history))
        }.toString().toByteArray(Charsets.UTF_8)
    }

    suspend fun discardDraft(expectedRoomKey: String? = state.value.room?.key) = operations.withLock {
        if (state.value.room?.key != expectedRoomKey) return@withLock
        saveRecord(buildJsonObject { put("read_seq", draftRecord().roomLong("read_seq")) })
    }

    suspend fun removeAttachment(id: String, expectedRoomKey: String? = state.value.room?.key) = operations.withLock {
        if (state.value.room?.key != expectedRoomKey) return@withLock
        if (state.value.pendingId == null) saveRecord(JsonObject(draftRecord() +
            ("attachments" to JsonArray(state.value.attachments.filterNot { it.roomString("attachment_id") == id }))))
    }

    suspend fun readAttachment(eventId: String, attachment: JsonObject, expectedRoomKey: String? = state.value.room?.key): Result<ByteArray> = operations.withLock {
        runCatching {
            val current = state.value
            require(current.room?.key == expectedRoomKey) { "Room changed before file read" }
            require(current.ready && "groups.attachment.read" in current.capabilities.methods) { "File reads are unavailable on this gateway" }
            require(current.room?.messages.orEmpty().any { it.id == eventId && it.attachments.any { descriptor -> descriptor.roomString("attachment_id") == attachment.roomString("attachment_id") && descriptor.roomLong("size") == attachment.roomLong("size") } }) { "Attachment is not part of this room event" }
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

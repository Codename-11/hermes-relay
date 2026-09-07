package com.hermesandroid.relay.data

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Leaves room for base64 and JSON inside the WebSocket outbound queue. */
const val HOSTED_ROOM_ANDROID_UPLOAD_MAX_BYTES = 12_000_000

internal fun JsonObject.roomString(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.roomLong(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
internal fun JsonObject.roomBool(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.roomObjects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

data class HostedRoomCapabilities(
    val methods: Set<String> = emptySet(),
    val features: Set<String> = emptySet(),
    val driver: Boolean = false,
) {
    val projection: Boolean get() = "groups.history" in methods && "message_history_projection_v1" in features
    val sharedRead: Boolean get() = "room_read_cursors_v1" in features && methods.containsAll(setOf("groups.read.get", "groups.read.mark"))
    val searchable: Boolean get() = projection && "groups.history.search" in methods && "message_history_search_v1" in features
    val mutations: Boolean get() = projection && driver && "message_mutations_v1" in features
    val rawExport: Boolean get() = "groups.log" in methods
    val readable: Boolean get() = "groups.state" in methods && (projection || "groups.log" in methods)
    val writable: Boolean get() = readable && driver && "groups.send" in methods &&
        features.containsAll(setOf("idempotent_send", "actor_identity", "monotonic_log"))
    companion object {
        fun parse(value: JsonObject) = HostedRoomCapabilities(
            methods = (value["methods"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
            features = (value["features"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
            driver = value.roomBool("driver"),
        )
    }
}

internal fun hostedRoom(value: JsonObject, route: BotGatewayRoute? = null) = BotGroupRoom(
    key = "hosted:${route?.connectionId.orEmpty()}:${value.roomString("room_id")}",
    roomId = value.roomString("room_id"), route = route, hosted = true,
    name = value.roomString("name"), revision = value.roomLong("revision"),
    members = (value.roomObjects("members") + value.roomObjects("retired_members")).map {
        BotGroupMember(name = it.roomString("display_name").ifBlank { it.roomString("profile") },
            retired = it !in value.roomObjects("members"),
            memberId = it.roomString("member_id"), handle = it.roomString("handle"))
    },
)

internal fun hostedMessage(event: JsonObject, room: BotGroupRoom): BotGroupMessage? {
    if (event.roomString("kind") !in setOf("message.user", "message.member", "message.participant")) return null
    val actor = event["actor"] as? JsonObject ?: return null
    val payload = event["payload"] as? JsonObject ?: return null
    val id = actor.roomString("id")
    return BotGroupMessage(
        id = event.roomString("event_id"), seq = event.roomLong("seq"),
        senderId = id, senderKind = actor.roomString("kind"),
        senderName = room.members.firstOrNull { it.memberId == id }?.name ?: actor.roomString("display_name").ifBlank { id.ifBlank { "Unknown author" } },
        senderSource = listOf(actor.roomString("profile"), actor.roomString("connection_id")).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { null },
        threadId = payload.roomString("thread_id"), text = payload.roomString("text"),
        parentEventId = payload.roomString("parent_event_id").ifBlank { null },
        attachments = payload.roomObjects("attachments"),
        atMs = ((event["created_at"] as? JsonPrimitive)?.doubleOrNull?.times(1000))?.toLong() ?: 0L,
    )
}

internal fun hostedProjectedMessage(message: JsonObject, room: BotGroupRoom): BotGroupMessage? =
    hostedMessage(buildJsonObject {
        put("kind", "message.user"); put("event_id", message.roomString("event_id")); put("seq", message.roomLong("seq"))
        put("actor", message["actor"] ?: JsonNull); put("payload", message)
        put("created_at", message["updated_at"] ?: JsonNull)
    }, room)?.copy(
        text = if (message.roomBool("deleted")) "Message deleted" else message.roomString("text"),
        attachments = if (message.roomBool("deleted")) emptyList() else message.roomObjects("attachments"),
        deleted = message.roomBool("deleted"), revision = message.roomLong("revision"),
        reactions = message.roomObjects("reactions"),
    )

internal fun hostedUserEventId(clientId: String): String = "user:" +
    MessageDigest.getInstance("SHA-256").digest(clientId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

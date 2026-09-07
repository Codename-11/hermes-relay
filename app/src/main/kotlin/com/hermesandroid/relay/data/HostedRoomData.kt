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
    val readable: Boolean get() = methods.containsAll(setOf("groups.state", "groups.log"))
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
    if (!event.roomString("kind").startsWith("message.")) return null
    val actor = event["actor"] as? JsonObject ?: return null
    val payload = event["payload"] as? JsonObject ?: return null
    val id = actor.roomString("id")
    return BotGroupMessage(
        id = event.roomString("event_id"), seq = event.roomLong("seq"),
        senderId = id, senderKind = actor.roomString("kind"),
        senderName = room.members.firstOrNull { it.memberId == id }?.name ?: actor.roomString("display_name").ifBlank { id.ifBlank { "Unknown author" } },
        senderSource = listOf(actor.roomString("profile"), actor.roomString("connection_id")).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { null },
        threadId = payload.roomString("thread_id"), text = payload.roomString("text"),
        attachments = payload.roomObjects("attachments"),
        atMs = ((event["created_at"] as? JsonPrimitive)?.doubleOrNull?.times(1000))?.toLong() ?: 0L,
    )
}

internal fun hostedUserEventId(clientId: String): String = "user:" +
    MessageDigest.getInstance("SHA-256").digest(clientId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

package com.hermesandroid.relay.network.upstream

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** JSON-RPC ids retain their wire type; numeric 1 and string "1" are different requests. */
internal data class GatewayServerRequest(
    val id: JsonPrimitive,
    val method: String,
    val params: JsonObject,
) {
    val key: String get() = "jsonrpc:$id"
    val sessionId: String? get() = (params["session_id"] as? JsonPrimitive)?.contentOrNull
    val eventType: String get() = "$method.request"
    val payload: JsonObject get() = JsonObject(params + mapOf(
        "request_id" to JsonPrimitive(key),
        "_server_request" to JsonPrimitive(true),
    ))

    companion object {
        val supportedMethods = setOf("clarify", "approval", "sudo", "secret")

        fun id(frame: JsonObject): JsonPrimitive? = (frame["id"] as? JsonPrimitive)
            ?.takeIf { it != JsonNull && (it.isString || it.doubleOrNull?.isFinite() == true) }

        fun parse(frame: JsonObject): GatewayServerRequest? {
            val id = id(frame) ?: return null
            val method = (frame["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val params = frame["params"] as? JsonObject ?: return null
            return GatewayServerRequest(id, method, params)
        }
    }
}

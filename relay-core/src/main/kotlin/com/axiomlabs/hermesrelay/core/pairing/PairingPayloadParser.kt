package com.axiomlabs.hermesrelay.core.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class PairingRequest(
    val relayUrl: String,
    val pairingCode: String,
)

object PairingPayloadParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): Result<PairingRequest> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Pairing payload is empty"))
        }

        if (!trimmed.startsWith("{")) {
            return Result.failure(IllegalArgumentException("Paste a full QR payload, or enter URL and code manually"))
        }

        return runCatching {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            val relayUrl = endpointRelayUrl(obj)
                ?: obj["relay"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                ?: error("QR payload does not include relay.url")
            val code = obj["relay"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
                ?: error("QR payload does not include relay.code")
            PairingRequest(relayUrl = relayUrl, pairingCode = code)
        }
    }

    private fun endpointRelayUrl(obj: JsonObject): String? {
        val endpoints = obj["endpoints"]?.jsonArray ?: return null
        val sorted = endpoints.mapNotNull { element ->
            val endpoint = element as? JsonObject ?: return@mapNotNull null
            val priority = endpoint["priority"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val relayUrl = endpoint["relay"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            priority to relayUrl
        }.sortedBy { it.first }
        return sorted.firstOrNull()?.second
    }
}

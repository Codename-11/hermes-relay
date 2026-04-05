package com.hermesandroid.companion.network.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

@Serializable
data class Envelope(
    val channel: String,
    val type: String,
    val id: String = UUID.randomUUID().toString(),
    val payload: JsonObject = buildJsonObject {}
)

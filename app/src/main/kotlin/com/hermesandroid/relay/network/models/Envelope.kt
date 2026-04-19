package com.hermesandroid.relay.network.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/**
 * Standard relay envelope. Most channel traffic nests data in [payload]
 * so a single decoder handles every message shape.
 *
 * **`profiles` escape hatch.** The `pairing`-channel `profiles.updated`
 * push envelope (see `AuthManager.handleProfilesUpdated`) hoists its
 * profiles array to the top level of the JSON rather than nesting it
 * inside payload. Rather than duplicating the kotlinx.serialization
 * pipeline for one message type, we widen [Envelope] with an optional
 * top-level [profiles] array. Every other envelope type leaves it null
 * — kotlinx.serialization tolerates an absent field because of the
 * default.
 */
@Serializable
data class Envelope(
    val channel: String,
    val type: String,
    val id: String = UUID.randomUUID().toString(),
    val payload: JsonObject = buildJsonObject {},
    val profiles: JsonArray? = null,
)

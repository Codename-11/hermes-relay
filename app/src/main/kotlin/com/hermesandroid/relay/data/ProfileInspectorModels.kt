package com.hermesandroid.relay.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire contracts for the v0.7.0 Profile Inspector endpoints:
 *
 *  - `GET /api/profiles/{name}/config`
 *  - `GET /api/profiles/{name}/skills`
 *  - `GET /api/profiles/{name}/soul`
 *  - `GET /api/profiles/{name}/memory`
 *
 * All four endpoints are read-only introspection views served by the relay
 * directly off disk (no gateway round-trip). Field names mirror the Python
 * worker's contracts exactly — any rename here is a protocol break.
 *
 * Optional fields on the wire (`truncated`, `readonly`) default to safe
 * values so older relays that omit them deserialize without failing the
 * whole payload.
 */

/**
 * Response for `GET /api/profiles/{name}/config`.
 *
 * `config` is a raw [JsonObject] so the UI can render arbitrary nested YAML
 * loaded from the profile's `config.yaml`. We don't model every possible
 * config shape — that's upstream Hermes territory and churns frequently.
 *
 * @property readonly The relay always serves this view read-only; the flag
 *                    is advisory. Optional on the wire, defaults to false.
 */
@Serializable
data class ProfileConfigResponse(
    val profile: String,
    val path: String,
    val config: JsonObject,
    val readonly: Boolean = false,
)

/**
 * One entry in the [ProfileSkillsResponse.skills] list.
 *
 * `enabled` is optional on the wire; defaults to true so a pre-v0.7 relay
 * that doesn't emit the field treats every skill as enabled.
 */
@Serializable
data class ProfileSkillEntry(
    val name: String,
    val category: String,
    val description: String,
    val path: String,
    val enabled: Boolean = true,
)

/** Response for `GET /api/profiles/{name}/skills`. */
@Serializable
data class ProfileSkillsResponse(
    val profile: String,
    val skills: List<ProfileSkillEntry>,
    val total: Int,
)

/**
 * Response for `GET /api/profiles/{name}/soul`.
 *
 * When `exists=false`, [content] is typically an empty string and the UI
 * should render an empty-state pointing at the expected [path].
 *
 * [truncated] is optional on the wire — Python may omit when false.
 */
@Serializable
data class ProfileSoulResponse(
    val profile: String,
    val path: String,
    val content: String,
    val exists: Boolean,
    @SerialName("size_bytes")
    val sizeBytes: Long,
    val truncated: Boolean = false,
)

/**
 * One entry in the [ProfileMemoryResponse.entries] list — a single memory
 * file found under the profile's memories directory.
 */
@Serializable
data class ProfileMemoryEntry(
    val name: String,
    val filename: String,
    val path: String,
    val content: String,
    @SerialName("size_bytes")
    val sizeBytes: Long,
    val truncated: Boolean = false,
)

/** Response for `GET /api/profiles/{name}/memory`. */
@Serializable
data class ProfileMemoryResponse(
    val profile: String,
    @SerialName("memories_dir")
    val memoriesDir: String,
    val entries: List<ProfileMemoryEntry>,
    val total: Int,
)

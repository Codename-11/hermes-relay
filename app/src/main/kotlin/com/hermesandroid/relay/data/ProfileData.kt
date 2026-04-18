package com.hermesandroid.relay.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An agent profile advertised by a Hermes server in its `auth.ok` payload.
 *
 * Historically (pre-R1) the server scanned a non-existent top-level
 * `profiles:` key in `~/.hermes/config.yaml` and effectively shipped an
 * empty list. Worker R1 rewrote the relay-side loader to scan the REAL
 * upstream layout (`~/.hermes/profiles/*/` directories) and added
 * [systemMessage], sourced from each profile's `SOUL.md`.
 *
 * A Profile is a NAMED AGENT CONFIG within a Connection. Switching profile
 * changes:
 *  - which model the phone asks the server to use on the next chat
 *    request (via [model]);
 *  - which system message the phone sends for that request (via
 *    [systemMessage], when non-blank).
 *
 * It does not change the server, sessions, or memory.
 *
 * Wire shape uses snake_case (`system_message`), this class uses camelCase
 * (`systemMessage`) — translated via [SerialName].
 */
@Serializable
data class Profile(
    val name: String,
    val model: String,
    val description: String = "",
    @SerialName("system_message")
    val systemMessage: String? = null,
)

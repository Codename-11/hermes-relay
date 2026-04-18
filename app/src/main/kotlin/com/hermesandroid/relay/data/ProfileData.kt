package com.hermesandroid.relay.data

import kotlinx.serialization.Serializable

/**
 * An agent profile advertised by a Hermes server in its `auth.ok` payload.
 * Corresponds to an entry under `profiles:` (or `agents:`) in the server's
 * `~/.hermes/config.yaml`. See `plugin/relay/config.py:_load_profiles`.
 *
 * A Profile is a NAMED AGENT CONFIG within a Connection. Switching profile
 * changes which model the phone asks the server to use on the next chat
 * request; it does not change the server, sessions, memory, or personality.
 */
@Serializable
data class Profile(
    val name: String,
    val model: String,
    val description: String = "",
)

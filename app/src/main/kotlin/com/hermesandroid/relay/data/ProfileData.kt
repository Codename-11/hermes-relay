package com.hermesandroid.relay.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An agent profile advertised by a Hermes server in its `auth.ok` payload.
 *
 * Historically (pre-R1) the server scanned a non-existent top-level
 * `profiles:` key in `~/.hermes/config.yaml` and effectively shipped an
 * empty list. Worker R1 rewrote the relay-side loader to scan the REAL
 * upstream layout (one directory per profile under `~/.hermes/profiles/`)
 * and added [systemMessage], sourced from each profile's `SOUL.md`.
 *
 * A Profile is a NAMED AGENT CONFIG within a Connection. Switching profile
 * changes the active agent identity for the Android chat surface:
 *  - which profile API server the phone routes chat/session calls to when
 *    the relay advertises [apiServerUrl];
 *  - which profile name the phone sends to the server for new sessions and
 *    chat turns when isolated routing is not available;
 *  - which model and system message the phone can send as compatibility
 *    fallback (via [model] and [systemMessage]);
 *  - which profile-scoped session id Android resumes for local chat context.
 *
 * It does not mutate the server's configured default profile.
 *
 * Wire shape uses snake_case (`system_message`), this class uses camelCase
 * (`systemMessage`) — translated via [SerialName].
 *
 * **v0.7.0 runtime metadata.** Three optional fields — [gatewayRunning],
 * [hasSoul], [skillCount] — describe what the relay observes about each
 * profile directory at discovery time:
 *  - [gatewayRunning] is a read-only probe (best-effort; can be stale or
 *    wrong if the relay's last probe missed a restart). Drives the green/
 *    grey status dot in the agent sheet.
 *  - [hasSoul] is true when the profile directory has a non-empty
 *    `SOUL.md` on disk. Decoupled from `systemMessage != null` so a SOUL
 *    that fails to load (permissions, I/O) still reports its presence.
 *  - [skillCount] is the count of skills visible under the profile
 *    directory — drives the "N skills" chip.
 *
 * All three default to safe zero-values and are optional on the wire, so
 * older relays without the fields deserialize cleanly as
 * `gatewayRunning = false, hasSoul = false, skillCount = 0`.
 *
 * **Hermes profile API metadata.** A relay can advertise an isolated
 * profile API server without exposing its secret. When [apiServerUrl] is
 * present, Android routes chat/session traffic to that URL and reuses the
 * active connection's stored API key. Operators that use distinct API keys
 * per profile should pair those profile API servers as separate connections.
 */
@Serializable
data class Profile(
    val name: String,
    val model: String,
    val description: String = "",
    @SerialName("system_message")
    val systemMessage: String? = null,
    @SerialName("gateway_running")
    val gatewayRunning: Boolean = false,
    @SerialName("has_soul")
    val hasSoul: Boolean = false,
    @SerialName("skill_count")
    val skillCount: Int = 0,
    @SerialName("api_server_enabled")
    val apiServerEnabled: Boolean = false,
    @SerialName("api_server_url")
    val apiServerUrl: String? = null,
    @SerialName("api_server_host")
    val apiServerHost: String? = null,
    @SerialName("api_server_port")
    val apiServerPort: Int? = null,
    @SerialName("api_server_key_present")
    val apiServerKeyPresent: Boolean = false,
) {
    val hasIsolatedApi: Boolean
        get() = !apiServerUrl.isNullOrBlank()
}

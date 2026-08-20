package com.hermesandroid.relay.bridge

import kotlinx.serialization.Serializable

/** Stable, auditable authority groups for every phone-side Bridge command. */
@Serializable
enum class BridgeCapability(val wireId: String, val timed: Boolean) {
    DEVICE_INFO("device_info", false),
    CONTACTS_READ("contacts_read", false),
    LOCATION_READ("location_read", false),
    CLIPBOARD_READ("clipboard_read", false),
    CLIPBOARD_WRITE("clipboard_write", false),
    MEDIA_CONTROL("media_control", false),
    COMMUNICATIONS("communications", false),
    OUTBOUND_SHARING("outbound_sharing", false),
    SCREEN_INSPECTION("screen_inspection", true),
    SCREEN_CONTROL("screen_control", true),
}

enum class BridgeCapabilityGrant { EXEMPT, PERMANENT, TIMED }

data class BridgeCommandAuthority(
    val capability: BridgeCapability? = null,
    val grant: BridgeCapabilityGrant,
)

/**
 * Closed command registry. Authorization is resolved from both path and HTTP
 * method so method-split commands such as clipboard read/write cannot share a
 * grant accidentally. Unknown paths and method combinations return null and
 * must be denied by the command boundary.
 *
 * Composite Python tools (android_navigate/android_macro) do not get a broad
 * grant: every primitive route they dispatch is checked here independently.
 */
object BridgeCommandRegistry {
    private data class Key(val method: String, val path: String)

    private fun permanent(capability: BridgeCapability) =
        BridgeCommandAuthority(capability, BridgeCapabilityGrant.PERMANENT)

    private fun timed(capability: BridgeCapability) =
        BridgeCommandAuthority(capability, BridgeCapabilityGrant.TIMED)

    private val exempt = BridgeCommandAuthority(grant = BridgeCapabilityGrant.EXEMPT)

    private val routes: Map<Key, BridgeCommandAuthority> = buildMap {
        fun route(method: String, path: String, authority: BridgeCommandAuthority) {
            put(Key(method, path), authority)
        }

        route("GET", "/ping", exempt)
        route("POST", "/setup", exempt)
        route("POST", "/wait", exempt)

        route("GET", "/current_app", permanent(BridgeCapability.DEVICE_INFO))
        route("GET", "/get_apps", permanent(BridgeCapability.DEVICE_INFO))
        route("GET", "/apps", permanent(BridgeCapability.DEVICE_INFO))
        route("POST", "/search_contacts", permanent(BridgeCapability.CONTACTS_READ))
        route("GET", "/location", permanent(BridgeCapability.LOCATION_READ))
        route("GET", "/clipboard", permanent(BridgeCapability.CLIPBOARD_READ))
        route("POST", "/clipboard", permanent(BridgeCapability.CLIPBOARD_WRITE))
        route("POST", "/media", permanent(BridgeCapability.MEDIA_CONTROL))
        route("POST", "/call", permanent(BridgeCapability.COMMUNICATIONS))
        route("POST", "/send_sms", permanent(BridgeCapability.COMMUNICATIONS))
        route("POST", "/share_media", permanent(BridgeCapability.OUTBOUND_SHARING))
        route("POST", "/send_mms", permanent(BridgeCapability.OUTBOUND_SHARING))

        listOf("/screen", "/screenshot", "/screen_hash", "/events").forEach {
            route("GET", it, timed(BridgeCapability.SCREEN_INSPECTION))
        }
        listOf("/find_nodes", "/describe_node", "/diff_screen", "/events/stream").forEach {
            route("POST", it, timed(BridgeCapability.SCREEN_INSPECTION))
        }

        listOf(
            "/tap", "/tap_text", "/long_press", "/type", "/swipe", "/drag",
            "/scroll", "/press_key", "/open_app", "/return_to_hermes",
            "/send_intent", "/broadcast",
        ).forEach { route("POST", it, timed(BridgeCapability.SCREEN_CONTROL)) }
    }

    fun resolve(path: String, method: String): BridgeCommandAuthority? =
        routes[Key(method.trim().uppercase(), path.trim())]

    fun registeredRoutes(): Set<Pair<String, String>> =
        routes.keys.mapTo(linkedSetOf()) { it.method to it.path }
}

@Serializable
data class BridgeCapabilityPolicy(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val permanentGrants: Set<BridgeCapability> = emptySet(),
    val timedExpiriesMs: Map<BridgeCapability, Long> = emptyMap(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        /** Explicit sentinel for a user-selected "Until turned off" lease. */
        const val NEVER_EXPIRES_AT_MS: Long = Long.MAX_VALUE
    }

    fun allows(capability: BridgeCapability, nowMs: Long): Boolean =
        if (capability.timed) {
            (timedExpiriesMs[capability] ?: 0L) > nowMs
        } else {
            capability in permanentGrants
        }

    fun expiryFor(capability: BridgeCapability): Long? = timedExpiriesMs[capability]

    fun isUnlimited(capability: BridgeCapability): Boolean =
        timedExpiriesMs[capability] == NEVER_EXPIRES_AT_MS
}

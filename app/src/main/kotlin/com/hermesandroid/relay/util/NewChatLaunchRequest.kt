package com.hermesandroid.relay.util

import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.Profile
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Public contract for the explicit "start a new chat" launch intent.
 *
 * A launcher widget, shortcut, or automation sends an explicit intent to
 * `MainActivity` with [ACTION_NEW_CHAT] and optionally names the gateway and
 * agent profile that should own the fresh chat. Connection ids are random and
 * private to the install, so the gateway is identified by URL and matched
 * against the saved connections. The request only opens an empty chat with the
 * composer focused; it never sends anything.
 */
object NewChatLaunch {
    const val ACTION_NEW_CHAT = "com.hermesandroid.relay.action.NEW_CHAT"

    /** Optional gateway URL (for example `https://hermes.example.com`). Absent = active gateway. */
    const val EXTRA_GATEWAY_URL = "com.hermesandroid.relay.extra.GATEWAY_URL"

    /** Optional profile name; `default` selects the default profile. Absent = current profile. */
    const val EXTRA_PROFILE = "com.hermesandroid.relay.extra.PROFILE"

    /** A request older than this is dropped instead of being applied to a later app state. */
    const val REQUEST_TTL_MS = 30_000L

    /** How long to wait for a gateway's profile roster to load before rejecting an unknown profile. */
    const val PROFILE_WAIT_MS = 15_000L

    /** How long to wait for the chat runtime to bind after a connection switch. */
    const val RUNTIME_WAIT_MS = 3_000L

    /** Composer focus is only meaningful right after the chat opens. */
    const val FOCUS_TTL_MS = 10_000L

    internal const val MAX_EXTRA_LENGTH = 2048
}

/**
 * [malformed] is set when an extra was present but unusable (wrong type, blank,
 * or oversized). Such a request is rejected rather than silently falling back to
 * the active gateway/profile, so a typo can never open a chat on another agent.
 */
data class NewChatLaunchRequest(
    val id: Long,
    val gatewayUrl: String? = null,
    val profile: String? = null,
    val malformed: Boolean = false,
    val requestedAtMs: Long = 0L,
)

data class ComposerFocusRequest(val id: Long, val requestedAtMs: Long)

/**
 * Process-local handoff from `MainActivity` to the app-owned chat runtime.
 * Like [SharedContentRequest], the request stays pending until RelayApp has
 * resolved the connection/profile and either opened the chat or rejected it;
 * a newer request replaces an older one.
 */
object NewChatLaunchRequests {
    private val nextId = AtomicLong(0L)
    private val _pending = MutableStateFlow<NewChatLaunchRequest?>(null)
    private val _composerFocus = MutableStateFlow<ComposerFocusRequest?>(null)

    val pending: StateFlow<NewChatLaunchRequest?> = _pending.asStateFlow()
    val composerFocus: StateFlow<ComposerFocusRequest?> = _composerFocus.asStateFlow()

    fun tryRequest(parsed: ParsedNewChatLaunch?, nowMs: Long = System.currentTimeMillis()): Boolean {
        parsed ?: return false
        _pending.value = NewChatLaunchRequest(
            id = nextId.incrementAndGet(),
            gatewayUrl = parsed.gatewayUrl,
            profile = parsed.profile,
            malformed = parsed.malformed,
            requestedAtMs = nowMs,
        )
        return true
    }

    fun consume(id: Long) {
        _pending.update { request -> request?.takeUnless { it.id == id } }
    }

    fun requestComposerFocus(id: Long, nowMs: Long = System.currentTimeMillis()) {
        _composerFocus.value = ComposerFocusRequest(id, nowMs)
    }

    fun consumeComposerFocus(id: Long) {
        _composerFocus.update { request -> request?.takeUnless { it.id == id } }
    }
}

data class ParsedNewChatLaunch(
    val gatewayUrl: String?,
    val profile: String?,
    val malformed: Boolean,
)

/**
 * Interpret the raw intent. Extras are taken as [Any]? so a non-String value is
 * detected instead of being read as "absent" (which would mean "current agent").
 * Returns null when [action] is not [NewChatLaunch.ACTION_NEW_CHAT].
 */
fun parseNewChatLaunch(action: String?, gatewayExtra: Any?, profileExtra: Any?): ParsedNewChatLaunch? {
    if (action != NewChatLaunch.ACTION_NEW_CHAT) return null
    var malformed = false
    fun read(raw: Any?): String? {
        raw ?: return null
        val value = (raw as? CharSequence)?.toString()?.trim()
        if (value.isNullOrEmpty() || value.length > NewChatLaunch.MAX_EXTRA_LENGTH) {
            malformed = true
            return null
        }
        return value
    }
    val gateway = read(gatewayExtra)
    val profile = read(profileExtra)
    return ParsedNewChatLaunch(gateway, profile, malformed)
}

fun isNewChatLaunchFresh(requestedAtMs: Long, nowMs: Long, ttlMs: Long): Boolean =
    nowMs - requestedAtMs in 0..ttlMs

// --- Gateway resolution ------------------------------------------------------------

internal data class NewChatConnectionCandidate(val id: String, val urls: List<String>)

/** Every URL a saved connection is reachable by; any of them may identify the gateway. */
internal fun Connection.newChatCandidate(): NewChatConnectionCandidate = NewChatConnectionCandidate(
    id = id,
    urls = buildList {
        add(apiServerUrl)
        add(relayUrl)
        dashboardUrl?.let(::add)
        authenticatedDashboardOrigin?.let(::add)
        routeCandidates.forEach { route ->
            route.api?.url?.let(::add)
            route.relay?.url?.let(::add)
            route.dashboard?.url?.let(::add)
            route.proxy?.url?.let(::add)
        }
    }.filter(String::isNotBlank),
)

internal data class GatewayOrigin(val scheme: String?, val host: String, val port: Int?)

/**
 * Parse `https://host[:port][/path]`, `wss://…`, or a bare `host[:port]`. `ws`/`wss`
 * fold to `http`/`https`; any other scheme is unsupported. Path/query are ignored.
 */
internal fun parseGatewayOrigin(raw: String): GatewayOrigin? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val uri = runCatching {
        URI(if ("://" in trimmed) trimmed else "//$trimmed")
    }.getOrNull() ?: return null
    val host = uri.host?.lowercase()?.takeIf(String::isNotBlank) ?: return null
    val scheme = when (uri.scheme?.lowercase()) {
        null -> null
        "http", "ws" -> "http"
        "https", "wss" -> "https"
        else -> return null
    }
    val explicitPort = uri.port.takeIf { it >= 0 }
    val port = explicitPort ?: when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> null
    }
    return GatewayOrigin(scheme, host, port)
}

/**
 * A requested origin matches a configured one on host, plus scheme and port when
 * the request specifies them. A bare host therefore matches any port/scheme.
 */
internal fun gatewayOriginMatches(requested: GatewayOrigin, candidate: GatewayOrigin): Boolean {
    if (requested.host != candidate.host) return false
    if (requested.scheme != null && candidate.scheme != null && requested.scheme != candidate.scheme) return false
    if (requested.port != null && candidate.port != null && requested.port != candidate.port) return false
    return true
}

internal sealed interface NewChatConnectionResolution {
    /** No gateway was requested; use whatever connection is active. */
    data object UseActive : NewChatConnectionResolution
    data class Use(val connectionId: String) : NewChatConnectionResolution
    data object NotFound : NewChatConnectionResolution
    data object Ambiguous : NewChatConnectionResolution
}

internal fun resolveNewChatConnection(
    gatewayUrl: String?,
    connections: List<NewChatConnectionCandidate>,
): NewChatConnectionResolution {
    gatewayUrl ?: return NewChatConnectionResolution.UseActive
    val requested = parseGatewayOrigin(gatewayUrl) ?: return NewChatConnectionResolution.NotFound
    val matches = connections.filter { connection ->
        connection.urls.any { url ->
            parseGatewayOrigin(url)?.let { gatewayOriginMatches(requested, it) } == true
        }
    }.map { it.id }.distinct()
    return when (matches.size) {
        0 -> NewChatConnectionResolution.NotFound
        1 -> NewChatConnectionResolution.Use(matches.single())
        else -> NewChatConnectionResolution.Ambiguous
    }
}

// --- Profile resolution --------------------------------------------------------------

internal const val NEW_CHAT_DEFAULT_PROFILE = "default"

internal sealed interface NewChatProfileResolution {
    /** No profile requested: the chat opens on the currently selected profile. */
    data object Unspecified : NewChatProfileResolution
    data class Found(val profile: Profile) : NewChatProfileResolution

    /** `default` requested on a gateway that advertises no profile roster. */
    data object ServerDefault : NewChatProfileResolution
    data object Missing : NewChatProfileResolution
}

/**
 * Match the requested name against the connection's roster (case-insensitive,
 * unambiguous). `default` means the profile literally named `default`, or the one
 * the gateway flags as its default. It never means "whatever the picker holds".
 */
internal fun resolveNewChatProfile(requested: String?, roster: List<Profile>): NewChatProfileResolution {
    requested ?: return NewChatProfileResolution.Unspecified
    val named = roster.filter { it.name.equals(requested, ignoreCase = true) }
    if (named.size == 1) return NewChatProfileResolution.Found(named.single())
    if (named.size > 1) return NewChatProfileResolution.Missing
    if (requested.equals(NEW_CHAT_DEFAULT_PROFILE, ignoreCase = true)) {
        val flagged = roster.filter { it.isDefault }
        return when {
            flagged.size == 1 -> NewChatProfileResolution.Found(flagged.single())
            roster.isEmpty() -> NewChatProfileResolution.ServerDefault
            else -> NewChatProfileResolution.Missing
        }
    }
    return NewChatProfileResolution.Missing
}

// --- Decision ---------------------------------------------------------------------------

internal enum class NewChatRejection {
    Supervised,
    Malformed,
    GatewayNotFound,
    GatewayAmbiguous,
    ProfileNotFound,
    ProfileLocked,
}

internal sealed interface NewChatLaunchStep {
    data object Wait : NewChatLaunchStep
    data class Reject(val reason: NewChatRejection) : NewChatLaunchStep
    data class SwitchConnection(val connectionId: String) : NewChatLaunchStep

    /** The target connection is active and the profile resolved; create the chat. */
    data class Open(val profile: NewChatProfileResolution) : NewChatLaunchStep
}

/**
 * One step of the launch state machine; RelayApp re-evaluates after every
 * connection/profile state change. It never resolves against a different
 * gateway or profile than the one requested: unknown targets reject, and an
 * as-yet-unloaded roster waits until [profileWaitExpired].
 */
internal fun newChatLaunchStep(
    request: NewChatLaunchRequest,
    hydrated: Boolean,
    supervisedEnabled: Boolean,
    connections: List<NewChatConnectionCandidate>,
    activeConnectionId: String?,
    profileSelectionSettled: Boolean,
    roster: List<Profile>,
    profileWaitExpired: Boolean,
    isProfileSelectionAllowed: (String?) -> Boolean,
): NewChatLaunchStep {
    if (!hydrated) return NewChatLaunchStep.Wait
    if (supervisedEnabled) return NewChatLaunchStep.Reject(NewChatRejection.Supervised)
    if (request.malformed) return NewChatLaunchStep.Reject(NewChatRejection.Malformed)
    val targetConnectionId = when (val resolved = resolveNewChatConnection(request.gatewayUrl, connections)) {
        NewChatConnectionResolution.UseActive -> activeConnectionId
        is NewChatConnectionResolution.Use -> resolved.connectionId
        NewChatConnectionResolution.NotFound ->
            return NewChatLaunchStep.Reject(NewChatRejection.GatewayNotFound)
        NewChatConnectionResolution.Ambiguous ->
            return NewChatLaunchStep.Reject(NewChatRejection.GatewayAmbiguous)
    } ?: return NewChatLaunchStep.Reject(NewChatRejection.GatewayNotFound)
    if (activeConnectionId != targetConnectionId) {
        return NewChatLaunchStep.SwitchConnection(targetConnectionId)
    }
    if (!profileSelectionSettled) return NewChatLaunchStep.Wait
    return when (val profile = resolveNewChatProfile(request.profile, roster)) {
        NewChatProfileResolution.Unspecified -> NewChatLaunchStep.Open(profile)
        is NewChatProfileResolution.Found ->
            if (isProfileSelectionAllowed(profile.profile.name)) {
                NewChatLaunchStep.Open(profile)
            } else {
                NewChatLaunchStep.Reject(NewChatRejection.ProfileLocked)
            }
        NewChatProfileResolution.ServerDefault ->
            if (profileWaitExpired) {
                if (isProfileSelectionAllowed(null)) {
                    NewChatLaunchStep.Open(profile)
                } else {
                    NewChatLaunchStep.Reject(NewChatRejection.ProfileLocked)
                }
            } else {
                NewChatLaunchStep.Wait
            }
        NewChatProfileResolution.Missing ->
            if (profileWaitExpired) {
                NewChatLaunchStep.Reject(NewChatRejection.ProfileNotFound)
            } else {
                NewChatLaunchStep.Wait
            }
    }
}

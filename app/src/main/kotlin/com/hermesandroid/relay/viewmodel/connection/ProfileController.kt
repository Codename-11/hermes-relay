package com.hermesandroid.relay.viewmodel.connection

import android.content.Context
import android.net.Uri
import com.hermesandroid.relay.auth.AuthManager
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.ProfileDisplayAliasStore
import com.hermesandroid.relay.data.ProfileIconStore
import com.hermesandroid.relay.data.ProfileLockStore
import com.hermesandroid.relay.data.ProfileSelectionStore
import com.hermesandroid.relay.data.ProfileSessionStore
import com.hermesandroid.relay.data.SessionTransport
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.network.upstream.models.SessionItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the **agent-profiles cluster** of
 * [com.hermesandroid.relay.viewmodel.ConnectionViewModel]: the merged
 * [agentProfiles] list (relay `auth.ok` ∪ dashboard `/api/profiles`), the
 * per-connection selected-profile state machine + its persistence stores
 * ([ProfileSelectionStore] / [ProfileSessionStore] / [ProfileDisplayAliasStore]),
 * the [profileDisplayAlias], and the per-profile last-session restore logic.
 *
 * Extracted as part of the ConnectionViewModel decomposition (ADR 34
 * follow-up). Pure mechanical lift — every method body is identical to the
 * original. The ViewModel keeps its public getters/functions and delegates;
 * because the profile state machine is co-driven by ViewModel-level lifecycle
 * observers (connection switch, active-connection change, agent-profile
 * arrival, gateway-availability settle), this controller exposes the granular
 * lifecycle hooks ([resetForConnectionSwitch], [clearSelectedProfile],
 * [setPendingConnectionId]/[setPendingName], [resolvePendingProfileFrom],
 * [refreshLastSessionForProfile]) those observers call **in their original
 * order** — the orchestration stays in the ViewModel; only the state + logic
 * moved here, so the profile state machine is now unit-testable in isolation.
 *
 * The three persistence stores are exposed as public vals so the ViewModel's
 * connection-lifecycle orchestrators (`removeConnection`, the duplicate-merge,
 * `resetAppData`, `saveLastSessionId`) keep their clear/persist call sites
 * byte-identical.
 *
 * Dependencies are injected as flows/providers/callbacks (mirroring the
 * [com.hermesandroid.relay.viewmodel.ConnectionSwitchCoordinator] precedent).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Swappable AuthManager flow — supplies the relay-advertised profile list. */
    authManagerFlow: StateFlow<AuthManager>,
    /** Active connection id flow (drives [profileDisplayAlias] + reads). */
    private val activeConnectionId: StateFlow<String?>,
    /** Resolved dashboard URL for the active connection, or null off-surface. */
    private val activeDashboardUrlProvider: () -> String?,
    /** Builds a dashboard client for a connection — the upstream factory. */
    private val dashboardClientFactory: (connectionId: String, dashboardUrl: String) -> DashboardApiClient,
    /** Current `streamingEndpoint` preference (for [activeSessionTransport]). */
    private val streamingEndpointProvider: () -> String,
    /** Current gateway availability tier (for [activeSessionTransport]). */
    private val gatewayAvailabilityProvider: () -> GatewayAvailability,
    /** Writes `ConnectionViewModel._lastSessionId`. */
    private val setLastSessionId: (String?) -> Unit,
    /** Legacy default (untransported) session id for the server-default profile. */
    private val legacyDefaultSessionId: suspend () -> String?,
    /** Rebuilds the per-profile chat API client. */
    private val rebuildChatApiClient: suspend () -> Unit,
) {

    // Server-advertised named agent configs, flattened to a StateFlow the
    // profile picker reads. Must flatMapLatest over the AuthManager flow for
    // the same reason as authState/pairingCode — after a connection switch the
    // underlying AuthManager instance is replaced and the public flow needs to
    // repoint at the new manager's backing state.
    private val _dashboardProfiles = MutableStateFlow<List<Profile>>(emptyList())

    val agentProfiles: StateFlow<List<Profile>> = combine(
        authManagerFlow.flatMapLatest { it.agentProfiles },
        _dashboardProfiles,
    ) { relay, dashboard ->
        // Prefer the relay's list when it has entries (richer runtime metadata);
        // fall back to the dashboard list so a dashboard-only connection still
        // sees its server profiles in the chat picker.
        relay.ifEmpty { dashboard }
    }.stateIn(scope, SharingStarted.Eagerly, authManagerFlow.value.agentProfiles.value)

    private val _selectedProfile = MutableStateFlow<Profile?>(null)
    val selectedProfile: StateFlow<Profile?> = _selectedProfile.asStateFlow()
    private val _pendingSelectedProfileConnectionId = MutableStateFlow<String?>(null)
    private val _pendingSelectedProfileName = MutableStateFlow<String?>(null)

    /**
     * DataStore-backed persistence for the selected profile keyed by
     * connection id. Public so the ViewModel's connection-lifecycle
     * orchestrators can clear them; otherwise driven from here.
     */
    val profileSelectionStore: ProfileSelectionStore = ProfileSelectionStore(context)
    val profileSessionStore: ProfileSessionStore = ProfileSessionStore(context)
    val profileDisplayAliasStore: ProfileDisplayAliasStore = ProfileDisplayAliasStore(context)

    /**
     * Per-connection "profile lock" persistence (twin of [profileSelectionStore],
     * sharing the same DataStore). Public so the ViewModel's connection-lifecycle
     * orchestrators can clear it alongside the selection store.
     */
    val profileLockStore: ProfileLockStore = ProfileLockStore(context)

    val profileDisplayAlias: StateFlow<String?> = combine(
        activeConnectionId,
        selectedProfile,
    ) { connectionId, profile ->
        connectionId to AgentDisplay.profileRequestName(profile?.name)
    }.flatMapLatest { (connectionId, profileName) ->
        if (connectionId == null) {
            flowOf(null)
        } else {
            profileDisplayAliasStore.aliasFlow(connectionId, profileName)
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The active connection's stored profile-lock target, or `null` when the
     * connection is unlocked. The value is the raw stored token: the sentinel
     * [AgentDisplay.SERVER_DEFAULT_PROFILE_KEY] means "locked to Server default",
     * any other string is a profile name. Built by flatMapLatest on the active
     * connection id exactly like [profileDisplayAlias] so it repoints cleanly
     * across connection switches.
     */
    val lockedProfileName: StateFlow<String?> = activeConnectionId
        .flatMapLatest { connectionId ->
            if (connectionId == null) {
                flowOf(null)
            } else {
                profileLockStore.lockedProfileFlow(connectionId)
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** True when the active connection is pinned to a single profile. */
    val isProfileLocked: StateFlow<Boolean> = lockedProfileName
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val profileIconStore: ProfileIconStore = ProfileIconStore(context)

    /** The active profile's local agent-icon path (twin of [profileDisplayAlias]). */
    val profileIcon: StateFlow<String?> = combine(
        activeConnectionId,
        selectedProfile,
    ) { connectionId, profile ->
        connectionId to AgentDisplay.profileRequestName(profile?.name)
    }.flatMapLatest { (connectionId, profileName) ->
        if (connectionId == null) {
            flowOf(null)
        } else {
            profileIconStore.iconFlow(connectionId, profileName)
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Load the host's agent profiles from the dashboard `/api/profiles` into
     * [agentProfiles] (merged in the combine above). Lets the chat agent sheet
     * offer server profiles on a dashboard-only connection. Best-effort: leaves
     * the current list untouched on failure (e.g. dashboard not signed in).
     */
    fun refreshDashboardProfiles() {
        val connectionId = activeConnectionId.value ?: return
        val dashboardUrl = activeDashboardUrlProvider() ?: return
        scope.launch {
            dashboardClientFactory(connectionId, dashboardUrl)
                .listProfiles().onSuccess { profiles ->
                    _dashboardProfiles.value = profiles
                }
        }
    }

    /**
     * The ACTIVE profile's chat sessions, scoped server-side via the dashboard
     * `GET /api/sessions?profile=` surface. Returns `null` when there's no
     * dashboard URL, so the caller falls back to the shared api_server list.
     */
    suspend fun listProfileScopedSessions(limit: Int = 200): Result<List<SessionItem>>? {
        val connectionId = activeConnectionId.value ?: return null
        val dashboardUrl = activeDashboardUrlProvider() ?: return null
        val profileName = AgentDisplay.profileRequestName(_selectedProfile.value?.name)
        return dashboardClientFactory(connectionId, dashboardUrl)
            .listSessions(profile = profileName, limit = limit)
    }

    /**
     * A session's transcript, scoped to the active profile via the dashboard
     * `/api/sessions/{id}/messages?profile=`. Returns `null` off the dashboard
     * surface so the caller falls back to the api_server transcript.
     */
    suspend fun loadProfileScopedMessages(sessionId: String): Result<List<MessageItem>>? {
        val connectionId = activeConnectionId.value ?: return null
        val dashboardUrl = activeDashboardUrlProvider() ?: return null
        val profileName = AgentDisplay.profileRequestName(_selectedProfile.value?.name)
        return dashboardClientFactory(connectionId, dashboardUrl)
            .getSessionMessages(sessionId, profileName)
    }

    fun setProfileDisplayAlias(alias: String?) {
        val connectionId = activeConnectionId.value ?: return
        val profileName = AgentDisplay.profileRequestName(_selectedProfile.value?.name)
        val normalizedAlias = AgentDisplay.localDisplayAlias(alias)
        scope.launch {
            profileDisplayAliasStore.setAlias(connectionId, profileName, normalizedAlias)
        }
    }

    fun setProfileIcon(uri: Uri) {
        val connectionId = activeConnectionId.value ?: return
        val profileName = AgentDisplay.profileRequestName(_selectedProfile.value?.name)
        scope.launch {
            val path = copyIcon(connectionId, profileName, uri) ?: return@launch
            profileIconStore.setIcon(connectionId, profileName, path)
        }
    }

    fun clearProfileIcon() {
        val connectionId = activeConnectionId.value ?: return
        val profileName = AgentDisplay.profileRequestName(_selectedProfile.value?.name)
        scope.launch {
            profileIconStore.iconFlow(connectionId, profileName).first()?.let {
                runCatching { File(it).delete() }
            }
            profileIconStore.setIcon(connectionId, profileName, null)
        }
    }

    /** Copy [uri]'s image bytes into app-internal storage; returns the path or null. */
    private suspend fun copyIcon(connectionId: String, profileName: String?, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "profile-icons").apply { mkdirs() }
                val key = AgentDisplay.profileSessionKey(profileName)
                val safe = "${connectionId}_$key"
                    .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
                    .joinToString("")
                val out = File(dir, "$safe.png")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                } ?: return@withContext null
                out.absolutePath
            } catch (t: Throwable) {
                null
            }
        }

    /**
     * The stored lock-token for a (possibly null) profile. Server default —
     * including the synthetic "default" alias — maps to
     * [AgentDisplay.SERVER_DEFAULT_PROFILE_KEY]; everything else to its name.
     * Mirrors [AgentDisplay.profileSessionKey] so the lock token and the
     * session/selection key for the same profile always agree.
     */
    private fun lockTokenFor(profile: Profile?): String =
        AgentDisplay.profileSessionKey(profile?.name)

    /**
     * Set (or clear, with `null`) the active profile pick. Writes through
     * to [profileSelectionStore] for the currently-active connection so
     * the selection survives process death and connection switches.
     *
     * When the connection is **locked**, a request for a profile other than
     * the locked target is ignored (the pickers are gated, but this guards the
     * programmatic paths too — e.g. voice/card dispatch). Re-selecting the
     * locked target is allowed (it's a no-op against current state anyway).
     */
    fun selectProfile(profile: Profile?) {
        val normalizedProfile = AgentDisplay.normalizeSelection(profile)
        val locked = lockedProfileName.value
        if (locked != null && lockTokenFor(normalizedProfile) != locked) {
            // Pinned to a different profile — refuse the switch. Never silently
            // coerce to the locked target here; the resolution path already
            // holds the selection on the locked target (or null if it's gone).
            return
        }
        applyProfileSelection(normalizedProfile)
    }

    /**
     * The actual selection write — runs the full profile-switch machinery
     * (fresh draft via [setLastSessionId], pending-state stamp, persist,
     * chat-API rebuild, last-session restore). Bypasses the lock gate so
     * [lockProfile] can force-select the new locked target even mid-relock;
     * [selectProfile] is the gated public entry point.
     */
    private fun applyProfileSelection(normalizedProfile: Profile?) {
        _selectedProfile.value = normalizedProfile
        setLastSessionId(null)
        val connectionId = activeConnectionId.value ?: return
        _pendingSelectedProfileConnectionId.value = connectionId
        _pendingSelectedProfileName.value = normalizedProfile?.name
        scope.launch {
            profileSelectionStore.setSelectedProfile(connectionId, normalizedProfile?.name)
            rebuildChatApiClient()
        }
        refreshLastSessionForProfile(connectionId, normalizedProfile?.name)
    }

    fun resolvePendingProfileFrom(list: List<Profile>): Boolean {
        val connectionId = activeConnectionId.value ?: return false
        if (_pendingSelectedProfileConnectionId.value != connectionId) {
            return false
        }
        // When the connection is locked, the lock target — NOT the pending or
        // persisted selection — decides the active profile. The sentinel means
        // Server default (selection null); any other token resolves against the
        // current list. If the locked profile isn't (yet/anymore) advertised we
        // HOLD on null so the Settings banner can explain it — never fall back.
        val locked = lockedProfileName.value
        if (locked != null) {
            return resolveLockedProfileFrom(locked, list)
        }
        val current = _selectedProfile.value
        if (current != null) {
            if (AgentDisplay.isServerDefaultAlias(current.name)) {
                _selectedProfile.value = null
                _pendingSelectedProfileName.value = null
                return true
            }
            val refreshed = list.firstOrNull { it.name == current.name }
            if (refreshed != null) {
                if (refreshed != current) {
                    _selectedProfile.value = refreshed
                    return true
                }
                return false
            }
            _selectedProfile.value = null
            _pendingSelectedProfileName.value = current.name
            return true
        }
        val pendingName = _pendingSelectedProfileName.value ?: return false
        if (AgentDisplay.isServerDefaultAlias(pendingName)) {
            _pendingSelectedProfileName.value = null
            _selectedProfile.value = null
            return true
        }
        val resolved = list.firstOrNull { it.name == pendingName }
        if (resolved != null) {
            _selectedProfile.value = resolved
            return true
        }
        return false
    }

    /**
     * Resolve the active profile against the lock [token] (already known to be
     * non-null by the caller). Returns true when the selection changed.
     *
     *  - sentinel → Server default → selection null.
     *  - a name present in [list] → select that profile.
     *  - a name absent from [list] → HOLD on null (the locked profile is gone
     *    or hasn't been advertised yet); the pending name is kept so a banner
     *    can name it and so a later list arrival can recover it.
     */
    private fun resolveLockedProfileFrom(token: String, list: List<Profile>): Boolean {
        if (AgentDisplay.isServerDefaultAlias(token) ||
            token == AgentDisplay.SERVER_DEFAULT_PROFILE_KEY
        ) {
            _pendingSelectedProfileName.value = null
            val changed = _selectedProfile.value != null
            _selectedProfile.value = null
            return changed
        }
        val resolved = list.firstOrNull { it.name == token }
        if (resolved != null) {
            val changed = _selectedProfile.value != resolved
            _selectedProfile.value = resolved
            _pendingSelectedProfileName.value = resolved.name
            return changed
        }
        // Locked profile not present — hold on null, keep the pending name so the
        // banner can name it and a later arrival can recover the lock.
        _pendingSelectedProfileName.value = token
        val changed = _selectedProfile.value != null
        _selectedProfile.value = null
        return changed
    }

    /**
     * Lock the active connection to [profile]. A `null` argument locks to
     * **Server default** (stored as the [AgentDisplay.SERVER_DEFAULT_PROFILE_KEY]
     * sentinel so it's distinct from "unlocked"). Persists the lock, then forces
     * the selection to the locked target via the normal [selectProfile] path so
     * the existing profile-switch machinery (fresh draft, gateway hot-swap, chat
     * API rebuild) runs. Locking to the already-selected profile is effectively
     * a no-op for the selection but still records the lock.
     */
    suspend fun lockProfile(profile: Profile?) {
        val connectionId = activeConnectionId.value ?: return
        val normalizedProfile = AgentDisplay.normalizeSelection(profile)
        val token = lockTokenFor(normalizedProfile)
        // Persist the lock first, then force-select via the un-gated body so the
        // switch lands even when re-locking from a different target (the
        // lockedProfileName StateFlow may still hold the previous token until the
        // DataStore emission propagates).
        profileLockStore.setLockedProfile(connectionId, token)
        applyProfileSelection(normalizedProfile)
    }

    /** Remove the lock for the active connection (back to free profile choice). */
    suspend fun unlockProfile() {
        val connectionId = activeConnectionId.value ?: return
        profileLockStore.setLockedProfile(connectionId, null)
    }

    /**
     * Which transport's session slot to restore right now — or `null` when the
     * decision is still pending (the gateway probe hasn't landed). A manual
     * streaming-endpoint override resolves immediately; under `"auto"` the slot
     * follows the gateway probe, and we deliberately DEFER while it's [Unknown]
     * rather than guess SSE.
     */
    fun activeSessionTransport(): SessionTransport? {
        val preference = streamingEndpointProvider()
        if (preference != "auto") return SessionTransport.forEndpoint(preference)
        return when (gatewayAvailabilityProvider()) {
            GatewayAvailability.Ready -> SessionTransport.GATEWAY
            GatewayAvailability.Unknown -> null
            else -> SessionTransport.SSE
        }
    }

    fun refreshLastSessionForProfile(
        connectionId: String?,
        profileName: String?,
    ) {
        setLastSessionId(null)
        if (connectionId == null) return
        // Defer until the active transport is known — restoring an id the
        // current transport can't resume is exactly what forks a session
        // mid-conversation on a non-default profile.
        val transport = activeSessionTransport() ?: return
        scope.launch {
            val profileScoped = profileSessionStore
                .sessionIdFlow(connectionId, profileName, transport)
                .first()
            // Default profile shares the launch DB across both transports, so a
            // pre-transport (untransported) pointer is still resumable — surface
            // it as the fallback only for the server-default context.
            val legacyDefault = if (profileName == null) {
                legacyDefaultSessionId()
            } else {
                null
            }
            if (
                activeConnectionId.value == connectionId &&
                _selectedProfile.value?.name == profileName &&
                activeSessionTransport() == transport
            ) {
                setLastSessionId(profileScoped ?: legacyDefault)
            }
        }
    }

    // --- Lifecycle hooks (driven by the ViewModel's init observers) --------

    /** Drop the current Profile object + pending state + dashboard list as a switch begins. */
    fun resetForConnectionSwitch() {
        _selectedProfile.value = null
        _pendingSelectedProfileConnectionId.value = null
        _pendingSelectedProfileName.value = null
        // Dashboard profile lists are per-connection — drop the old one so the
        // pending persisted name can't resolve against the previous connection's
        // profiles before the new connection's list arrives.
        _dashboardProfiles.value = emptyList()
    }

    /** Clear just the in-memory selection + pending state (resetAppData). */
    fun clearSelectionState() {
        _selectedProfile.value = null
        _pendingSelectedProfileConnectionId.value = null
        _pendingSelectedProfileName.value = null
    }

    fun clearSelectedProfile() {
        _selectedProfile.value = null
    }

    fun setPendingConnectionId(connectionId: String?) {
        _pendingSelectedProfileConnectionId.value = connectionId
    }

    fun setPendingName(profileName: String?) {
        _pendingSelectedProfileName.value = profileName
    }
}

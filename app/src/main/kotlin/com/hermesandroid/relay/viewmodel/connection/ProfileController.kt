package com.hermesandroid.relay.viewmodel.connection

import android.content.Context
import android.net.Uri
import com.hermesandroid.relay.auth.AuthManager
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.GatewayProfileManagementUnsupportedException
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.ProfileDisplayAliasStore
import com.hermesandroid.relay.data.ProfileIconStore
import com.hermesandroid.relay.data.prepareProfileAvatar
import com.hermesandroid.relay.data.profileAvatarMime
import com.hermesandroid.relay.data.preferredProfileIcon
import com.hermesandroid.relay.data.ProfileLockStore
import com.hermesandroid.relay.data.ProfilePresentation
import com.hermesandroid.relay.data.ProfilePresentationPolicy
import com.hermesandroid.relay.data.ProfilePresentationStore
import com.hermesandroid.relay.data.ProfileSelectionStore
import com.hermesandroid.relay.data.ProfileSessionStore
import com.hermesandroid.relay.data.SessionTransport
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.DashboardProfileScope
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.GatewayPetGalleryItem
import com.hermesandroid.relay.network.upstream.GatewayPetInfo
import com.hermesandroid.relay.network.upstream.GatewayRpcException
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.network.upstream.SessionMessageLoadMode
import com.hermesandroid.relay.network.upstream.models.SessionItem
import com.hermesandroid.relay.network.relay.RelayHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal fun isCurrentProfileAvatarRefresh(
    requestConnectionId: String,
    activeConnectionId: String?,
    requestGeneration: Long,
    currentGeneration: Long,
): Boolean = requestConnectionId == activeConnectionId && requestGeneration == currentGeneration

internal fun mergeGatewayProfileRoster(
    gateway: List<Profile>,
    fallback: List<Profile>,
): List<Profile> = gateway.map { authoritative ->
    val extension = fallback.firstOrNull { it.name == authoritative.name }
        ?: return@map authoritative
    authoritative.copy(
        systemMessage = extension.systemMessage,
        gatewayRunning = extension.gatewayRunning,
        hasSoul = extension.hasSoul,
        apiServerEnabled = extension.apiServerEnabled,
        apiServerUrl = extension.apiServerUrl,
        apiServerHost = extension.apiServerHost,
        apiServerPort = extension.apiServerPort,
        apiServerKeyPresent = extension.apiServerKeyPresent,
    )
}

internal fun selectProfileRoster(
    gatewayAuthoritative: Boolean,
    gateway: List<Profile>,
    fallback: List<Profile>,
): List<Profile> = if (gatewayAuthoritative) {
    mergeGatewayProfileRoster(gateway, fallback)
} else {
    fallback
}

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
    /** Optional Relay client used only for importing an icon from the host. */
    private val relayHttpClient: RelayHttpClient? = null,
    /** Current per-connection Gateway client; profile management stays upstream-owned. */
    private val gatewayClientProvider: () -> GatewayChatClient? = { null },
) {

    // Server-advertised named agent configs, flattened to a StateFlow the
    // profile picker reads. Must flatMapLatest over the AuthManager flow for
    // the same reason as authState/pairingCode — after a connection switch the
    // underlying AuthManager instance is replaced and the public flow needs to
    // repoint at the new manager's backing state.
    private val _dashboardProfiles = MutableStateFlow<List<Profile>>(emptyList())
    private val _gatewayProfiles = MutableStateFlow<List<Profile>>(emptyList())
    private val _gatewayRosterAuthoritative = MutableStateFlow(false)
    private val avatarRefreshGeneration = AtomicLong(0L)
    private val petRefreshGeneration = AtomicLong(0L)
    private val petGalleryGeneration = AtomicLong(0L)
    private val sessionRestoreGeneration = AtomicLong(0L)
    private val freshDraftScopes = ConcurrentHashMap.newKeySet<SessionScopeKey>()

    private data class SessionScopeKey(
        val connectionId: String,
        val profileName: String?,
        val transport: SessionTransport,
    )
    private val petThumbnailRequests = ConcurrentHashMap.newKeySet<String>()

    val agentProfiles: StateFlow<List<Profile>> = combine(
        authManagerFlow.flatMapLatest { it.agentProfiles },
        _dashboardProfiles,
        _gatewayProfiles,
        _gatewayRosterAuthoritative,
    ) { relay, dashboard, gateway, gatewayAuthoritative ->
        // Current Gateway rows are authoritative for shared profile metadata and
        // avatar presence. Relay-only runtime/API routing fields are joined by
        // exact name so adopting the roster never drops an isolated profile route.
        val fallback = relay.ifEmpty { dashboard }
        selectProfileRoster(gatewayAuthoritative, gateway, fallback)
    }.stateIn(scope, SharingStarted.Eagerly, authManagerFlow.value.agentProfiles.value)

    private val _selectedProfile = MutableStateFlow<Profile?>(null)
    val selectedProfile: StateFlow<Profile?> = _selectedProfile.asStateFlow()
    private val _pendingSelectedProfileConnectionId = MutableStateFlow<String?>(null)
    private val _pendingSelectedProfileName = MutableStateFlow<String?>(null)

    // `selectedProfile == null` is the UI's Server-default sentinel, not
    // necessarily the running dashboard's launch profile. Upstream exposes the
    // sticky default (`active`) separately from that process scope (`current`).
    // Keep both values so the distinction stays explicit, and derive the one
    // session namespace used by Gateway + dashboard CRUD below.
    private val _serverDefaultProfileScope = MutableStateFlow<DashboardProfileScope?>(null)
    val serverDefaultProfileScope: StateFlow<DashboardProfileScope?> =
        _serverDefaultProfileScope.asStateFlow()
    private val _serverDefaultProfileSettled = MutableStateFlow(false)

    val effectiveSessionProfileName: StateFlow<String?> = combine(
        selectedProfile,
        serverDefaultProfileScope,
    ) { selected, serverDefault ->
        AgentDisplay.effectiveSessionProfileName(selected?.name, serverDefault?.active)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Display identity resolved through the same sticky server default used by session routing. */
    val effectiveDisplayProfile: StateFlow<Profile?> = combine(
        selectedProfile,
        agentProfiles,
        serverDefaultProfileScope,
    ) { selected, profiles, serverDefault ->
        AgentDisplay.effectiveDisplayProfile(
            selectedProfile = selected,
            profiles = profiles,
            serverDefaultProfileName = serverDefault?.active,
        )
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * True once the active connection's persisted profile selection has SETTLED
     * — i.e. profile-scoped reads (session drawer, transcript restore, voice
     * prefs) can run without racing the cold-start restore and wrongly loading
     * the SERVER-DEFAULT profile. Settled when any of these hold:
     *  - there's no active connection yet (nothing profile-scoped to gate), or
     *  - the selection has resolved into [selectedProfile], or
     *  - Server default is selected and `/api/profiles/active` has resolved
     *    (or cleanly degraded to the launch-profile fallback), or
     *  - the agent-profile list has arrived, so resolution has been ATTEMPTED —
     *    a genuinely-missing profile then falls back to server default rather
     *    than gating forever.
     *
     * False only in the cold-start window where a non-default profile name is
     * persisted but the profile list hasn't landed yet to resolve it — exactly
     * when an unscoped read would load the server-default profile by mistake.
     */
    private val profileResolutionInputs = combine(
        agentProfiles,
        _serverDefaultProfileSettled,
    ) { profiles, serverDefaultSettled -> profiles to serverDefaultSettled }

    val selectionSettled: StateFlow<Boolean> = combine(
        activeConnectionId,
        selectedProfile,
        _pendingSelectedProfileConnectionId,
        _pendingSelectedProfileName,
        profileResolutionInputs,
    ) { connId, selected, pendingConnId, pendingName, resolution ->
        val (profiles, serverDefaultSettled) = resolution
        when {
            connId == null -> true
            selected != null -> true
            // Pending state still points at a previous connection mid-switch —
            // hold until this connection's restore re-stamps the pending name.
            pendingConnId != connId -> false
            pendingName == null || pendingName == AgentDisplay.SERVER_DEFAULT_PROFILE_KEY ->
                serverDefaultSettled
            // Non-default name pending: settled once the profile list is present
            // (resolution attempted), even if the name turns out to be gone.
            else -> profiles.isNotEmpty()
        }
    }.stateIn(scope, SharingStarted.Eagerly, false)

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

    /** Local ordering/visibility preferences for the active connection's picker. */
    val profilePresentationStore: ProfilePresentationStore = ProfilePresentationStore(context)
    private val profilePresentationWriteMutex = Mutex()

    val profilePresentation: StateFlow<ProfilePresentation> = activeConnectionId
        .flatMapLatest { connectionId ->
            if (connectionId == null) {
                flowOf(ProfilePresentation())
            } else {
                profilePresentationStore.presentationFlow(connectionId)
            }
        }.stateIn(scope, SharingStarted.Eagerly, ProfilePresentation())

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

    /** The active profile's device-local fallback icon. */
    val localProfileIcon: StateFlow<String?> = combine(
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

    private val activeServerAvatarIdentity: Flow<Pair<String?, String?>> = combine(
        activeConnectionId,
        selectedProfile,
        serverDefaultProfileScope,
        _gatewayProfiles,
    ) { connectionId, selected, serverDefault, gatewayProfiles ->
        connectionId to (
            selected?.name ?: serverDefault?.active
                ?: gatewayProfiles.firstOrNull(Profile::isDefault)?.name
        )
    }

    /** Cached bytes fetched from Hermes; always preferred over the local fallback. */
    val serverProfileAvatar: StateFlow<String?> = activeServerAvatarIdentity
        .flatMapLatest { (connectionId, profileName) ->
            if (connectionId == null || profileName.isNullOrBlank()) flowOf(null)
            else profileIconStore.serverAvatarFlow(connectionId, profileName)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Whether this phone should prefer its local image over Hermes' shared avatar. */
    val useLocalProfileIconOverride: StateFlow<Boolean> = combine(
        activeConnectionId,
        selectedProfile,
    ) { connectionId, profile ->
        connectionId to AgentDisplay.profileRequestName(profile?.name)
    }.flatMapLatest { (connectionId, profileName) ->
        if (connectionId == null) flowOf(false)
        else profileIconStore.localOverrideFlow(connectionId, profileName)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val profileIcon: StateFlow<String?> = combine(
        serverProfileAvatar,
        localProfileIcon,
        useLocalProfileIconOverride,
    ) { server, local, localOverride -> preferredProfileIcon(server, local, localOverride) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Local icon for any profile identity on the active connection. */
    fun profileIconFlow(profileName: String?): Flow<String?> = combine(
        activeConnectionId,
        serverDefaultProfileScope,
        _gatewayProfiles,
    ) { connectionId, serverDefault, gatewayProfiles ->
        connectionId to (
            profileName ?: serverDefault?.active
                ?: gatewayProfiles.firstOrNull(Profile::isDefault)?.name
        )
    }
        .flatMapLatest { (connectionId, serverProfileName) ->
            if (connectionId == null) return@flatMapLatest flowOf(null)
            val local = profileIconStore.iconFlow(connectionId, profileName)
            val localOverride = profileIconStore.localOverrideFlow(connectionId, profileName)
            if (serverProfileName.isNullOrBlank()) local else combine(
                profileIconStore.serverAvatarFlow(connectionId, serverProfileName),
                local,
                localOverride,
            ) { server, fallback, override -> preferredProfileIcon(server, fallback, override) }
        }

    /** Exact profile identity on any saved connection; never consults active state. */
    fun profileIconFlow(connectionId: String, profileName: String): Flow<String?> = combine(
        profileIconStore.serverAvatarFlow(connectionId, profileName),
        profileIconStore.iconFlow(connectionId, profileName),
        profileIconStore.localOverrideFlow(connectionId, profileName),
    ) { server, local, localOverride -> preferredProfileIcon(server, local, localOverride) }

    data class HostIconImportState(
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _hostIconImportState = MutableStateFlow(HostIconImportState())
    val hostIconImportState: StateFlow<HostIconImportState> =
        _hostIconImportState.asStateFlow()

    data class SharedAvatarState(
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _sharedAvatarState = MutableStateFlow(SharedAvatarState())
    val sharedAvatarState: StateFlow<SharedAvatarState> = _sharedAvatarState.asStateFlow()

    data class HermesPetPresentation(
        val connectionId: String,
        val profileName: String?,
        val slug: String,
        val displayName: String,
        val spritesheetPath: String,
        val spritesheetRevision: String,
        val frameWidth: Int,
        val frameHeight: Int,
        val framesPerState: Int,
        val framesByState: Map<String, Int>,
        val framesByRow: Map<String, Int>,
        val loopMs: Int,
        val scale: Float,
        val stateRows: List<String>,
    )

    data class HermesPetState(
        val supported: Boolean? = null,
        val loading: Boolean = false,
        val galleryLoading: Boolean = false,
        val active: HermesPetPresentation? = null,
        val gallery: List<GatewayPetGalleryItem> = emptyList(),
        val thumbnails: Map<String, String> = emptyMap(),
        val error: String? = null,
    )

    private val _hermesPetState = MutableStateFlow(HermesPetState())
    val hermesPetState: StateFlow<HermesPetState> = _hermesPetState.asStateFlow()

    /**
     * Load the host's agent profiles from the dashboard `/api/profiles` into
     * [agentProfiles] (merged in the combine above). Lets the chat agent sheet
     * offer server profiles on a dashboard-only connection. Best-effort: leaves
     * the current list untouched on failure (e.g. dashboard not signed in).
     */
    fun refreshDashboardProfiles() {
        val connectionId = activeConnectionId.value ?: return
        val dashboardUrl = activeDashboardUrlProvider()
        if (dashboardUrl == null) {
            _serverDefaultProfileScope.value = null
            _serverDefaultProfileSettled.value = true
            return
        }
        scope.launch {
            refreshGatewayProfiles(connectionId)
            refreshHermesPet(connectionId)
            val client = dashboardClientFactory(connectionId, dashboardUrl)
            val defaultScope = client.getActiveProfileScope().getOrNull()
            if (activeConnectionId.value != connectionId) return@launch

            // Older dashboards may not expose this endpoint. Settle to null so
            // they retain the historical launch-profile behavior rather than
            // blocking chat indefinitely.
            _serverDefaultProfileScope.value = defaultScope
            _serverDefaultProfileSettled.value = true
            if (_selectedProfile.value == null) {
                refreshLastSessionForProfile(connectionId, null)
                rebuildChatApiClient()
            }

            client.listProfiles().onSuccess { profiles ->
                if (activeConnectionId.value == connectionId) {
                    _dashboardProfiles.value = profiles
                }
            }
        }
    }

    fun refreshGatewayProfiles() {
        val connectionId = activeConnectionId.value ?: return
        scope.launch {
            refreshGatewayProfiles(connectionId)
            refreshHermesPet(connectionId)
        }
    }

    private suspend fun refreshGatewayProfiles(connectionId: String) {
        val gateway = gatewayClientProvider() ?: return
        val generation = avatarRefreshGeneration.incrementAndGet()
        gateway.listProfiles().onSuccess { profiles ->
            if (!isCurrentProfileAvatarRefresh(connectionId, activeConnectionId.value, generation, avatarRefreshGeneration.get())) return@onSuccess
            _gatewayProfiles.value = profiles
            _gatewayRosterAuthoritative.value = true
            profiles.forEach { profile ->
                if (!profile.hasAvatar) {
                    clearServerAvatarCache(connectionId, profile.name, generation)
                } else {
                    gateway.getProfileAvatar(profile.name).onSuccess { asset ->
                        if (!isCurrentProfileAvatarRefresh(connectionId, activeConnectionId.value, generation, avatarRefreshGeneration.get())) {
                            return@onSuccess
                        }
                        if (asset == null) {
                            clearServerAvatarCache(connectionId, profile.name, generation)
                        } else {
                            val path = copyServerAvatarBytes(connectionId, profile.name, asset.data, asset.mime)
                            if (path != null && avatarRefreshGeneration.get() == generation) {
                                profileIconStore.setServerAvatar(connectionId, profile.name, path)
                            }
                        }
                    }
                }
            }
        }.onFailure { failure ->
            if (
                failure is GatewayProfileManagementUnsupportedException &&
                isCurrentProfileAvatarRefresh(
                    connectionId,
                    activeConnectionId.value,
                    generation,
                    avatarRefreshGeneration.get(),
                )
            ) {
                _gatewayProfiles.value = emptyList()
                _gatewayRosterAuthoritative.value = false
            }
        }
    }

    private suspend fun clearServerAvatarCache(connectionId: String, profileName: String, generation: Long) {
        if (avatarRefreshGeneration.get() != generation) return
        profileIconStore.serverAvatarFlow(connectionId, profileName).first()?.let { runCatching { File(it).delete() } }
        profileIconStore.setServerAvatar(connectionId, profileName, null)
    }

    /** Refresh the active profile's upstream sprite contract without re-sending an unchanged sheet. */
    fun refreshHermesPet() {
        val connectionId = activeConnectionId.value ?: return
        scope.launch { refreshHermesPet(connectionId) }
    }

    private suspend fun refreshHermesPet(connectionId: String) {
        val gateway = gatewayClientProvider() ?: run {
            _hermesPetState.value = HermesPetState(supported = null)
            return
        }
        val profileName = resolveSessionProfileName()
        val generation = petRefreshGeneration.incrementAndGet()
        val previous = _hermesPetState.value.active?.takeIf {
            it.connectionId == connectionId && it.profileName == profileName
        }
        _hermesPetState.value = _hermesPetState.value.copy(loading = true, error = null)
        gateway.petInfo(profile = profileName, knownRevision = previous?.spritesheetRevision).fold(
            onSuccess = { info ->
                if (!isCurrentPetRefresh(connectionId, generation)) return@fold
                if (!info.enabled) {
                    _hermesPetState.value = HermesPetState(supported = true)
                    return@fold
                }
                val presentation = cacheHermesPet(connectionId, profileName, info, previous)
                _hermesPetState.value = if (presentation == null) {
                    HermesPetState(
                        supported = true,
                        error = "Hermes returned an animated pet that this phone could not cache",
                    )
                } else {
                    _hermesPetState.value.copy(
                        supported = true,
                        loading = false,
                        active = presentation,
                        error = null,
                    )
                }
            },
            onFailure = { failure ->
                if (!isCurrentPetRefresh(connectionId, generation)) return@fold
                _hermesPetState.value = if ((failure as? GatewayRpcException)?.code == -32601) {
                    HermesPetState(supported = false)
                } else {
                    _hermesPetState.value.copy(
                        loading = false,
                        error = failure.message ?: "Could not load the Hermes animated pet",
                    )
                }
            },
        )
    }

    /** Load the profile-scoped upstream gallery only when the user opens the picker. */
    fun loadHermesPetGallery() {
        val connectionId = activeConnectionId.value ?: return
        val gateway = gatewayClientProvider() ?: return
        val profileName = resolveSessionProfileName()
        val generation = petGalleryGeneration.incrementAndGet()
        scope.launch {
            _hermesPetState.value = _hermesPetState.value.copy(galleryLoading = true, error = null)
            gateway.petGallery(profile = profileName).fold(
                onSuccess = { gallery ->
                    if (!isCurrentPetGallery(connectionId, profileName, generation)) return@fold
                    _hermesPetState.value = _hermesPetState.value.copy(
                        supported = true,
                        galleryLoading = false,
                        gallery = gallery.pets,
                        error = null,
                    )
                },
                onFailure = { failure ->
                    if (!isCurrentPetGallery(connectionId, profileName, generation)) return@fold
                    _hermesPetState.value = _hermesPetState.value.copy(
                        galleryLoading = false,
                        error = failure.message ?: "Could not load the Hermes pet gallery",
                    )
                },
            )
        }
    }

    fun selectHermesPet(slug: String) = mutateHermesPet { gateway, profile ->
        gateway.selectPet(slug, profile)
    }

    /** Lazily fetch one upstream-cropped idle frame for a visible gallery row. */
    fun loadHermesPetThumbnail(pet: GatewayPetGalleryItem) {
        if (_hermesPetState.value.thumbnails.containsKey(pet.slug)) return
        val connectionId = activeConnectionId.value ?: return
        val gateway = gatewayClientProvider() ?: return
        val profileName = resolveSessionProfileName()
        val requestKey = "$connectionId\u0000${AgentDisplay.profileSessionKey(profileName)}\u0000${pet.slug}"
        if (!petThumbnailRequests.add(requestKey)) return
        scope.launch {
            try {
                gateway.petThumbnail(
                    slug = pet.slug,
                    spritesheetUrl = pet.spritesheetUrl,
                    profile = profileName,
                ).onSuccess { dataUri ->
                    if (
                        dataUri != null && activeConnectionId.value == connectionId &&
                        resolveSessionProfileName() == profileName
                    ) {
                        _hermesPetState.value = _hermesPetState.value.copy(
                            thumbnails = _hermesPetState.value.thumbnails + (pet.slug to dataUri),
                        )
                    }
                }
            } finally {
                petThumbnailRequests.remove(requestKey)
            }
        }
    }

    fun disableHermesPet() = mutateHermesPet { gateway, profile ->
        gateway.disablePet(profile)
    }

    private fun mutateHermesPet(
        mutation: suspend (GatewayChatClient, String?) -> Result<Unit>,
    ) {
        val connectionId = activeConnectionId.value ?: return
        val gateway = gatewayClientProvider() ?: return
        val profileName = resolveSessionProfileName()
        scope.launch {
            _hermesPetState.value = _hermesPetState.value.copy(loading = true, error = null)
            mutation(gateway, profileName).fold(
                onSuccess = { refreshHermesPet(connectionId) },
                onFailure = { failure ->
                    _hermesPetState.value = _hermesPetState.value.copy(
                        loading = false,
                        error = failure.message ?: "Could not update the Hermes animated pet",
                    )
                },
            )
        }
    }

    private fun isCurrentPetRefresh(connectionId: String, generation: Long): Boolean =
        activeConnectionId.value == connectionId && petRefreshGeneration.get() == generation

    private fun isCurrentPetGallery(connectionId: String, profileName: String?, generation: Long): Boolean =
        activeConnectionId.value == connectionId &&
            resolveSessionProfileName() == profileName &&
            petGalleryGeneration.get() == generation

    private suspend fun cacheHermesPet(
        connectionId: String,
        profileName: String?,
        info: GatewayPetInfo,
        previous: HermesPetPresentation?,
    ): HermesPetPresentation? = withContext(Dispatchers.IO) {
        val slug = info.slug ?: return@withContext null
        val revision = info.spritesheetRevision ?: return@withContext null
        val path = if (info.spritesheetUnchanged && previous?.spritesheetRevision == revision) {
            previous.spritesheetPath.takeIf { File(it).isFile }
        } else {
            val bytes = info.spritesheet ?: return@withContext null
            val dir = File(context.filesDir, "hermes-profile-pets").apply { mkdirs() }
            val key = MessageDigest.getInstance("SHA-256")
                .digest("$connectionId\u0000${AgentDisplay.profileSessionKey(profileName)}".toByteArray())
                .take(12)
                .joinToString("") { "%02x".format(it) }
            val extension = if (info.mime == "image/webp") "webp" else "png"
            val target = File(dir, "$key.$extension")
            val pending = File(dir, "$key.$extension.tmp")
            runCatching {
                pending.writeBytes(bytes)
                if (!pending.renameTo(target)) {
                    target.writeBytes(bytes)
                    pending.delete()
                }
                target.absolutePath
            }.getOrNull()
        } ?: return@withContext null

        HermesPetPresentation(
            connectionId = connectionId,
            profileName = profileName,
            slug = slug,
            displayName = info.displayName ?: slug,
            spritesheetPath = path,
            spritesheetRevision = revision,
            frameWidth = info.frameWidth,
            frameHeight = info.frameHeight,
            framesPerState = info.framesPerState,
            framesByState = info.framesByState,
            framesByRow = info.framesByRow,
            loopMs = info.loopMs,
            scale = info.scale,
            stateRows = info.stateRows,
        )
    }

    /** Effective profile namespace for Gateway and profile-scoped session I/O. */
    fun resolveSessionProfileName(selectedProfileName: String? = _selectedProfile.value?.name): String? =
        AgentDisplay.effectiveSessionProfileName(
            selectedProfileName = selectedProfileName,
            serverDefaultProfileName = _serverDefaultProfileScope.value?.active,
        )

    /**
     * The ACTIVE profile's chat sessions, scoped server-side via the dashboard
     * `GET /api/sessions?profile=` surface. Returns `null` when there's no
     * dashboard URL, so the caller falls back to the shared api_server list.
     */
    suspend fun listProfileScopedSessions(limit: Int = 200): Result<List<SessionItem>>? {
        return listProfileScopedSessions(resolveSessionProfileName(), limit)
    }

    /** List the exact profile namespace owned by the visible chat binding. */
    suspend fun listProfileScopedSessions(
        profileName: String?,
        limit: Int = 200,
    ): Result<List<SessionItem>>? {
        val connectionId = activeConnectionId.value ?: return null
        val dashboardUrl = activeDashboardUrlProvider() ?: return null
        return dashboardClientFactory(connectionId, dashboardUrl)
            .listSessions(profile = profileName, limit = limit, archived = "include")
    }

    suspend fun listAllProfileSessions(limit: Int = 200): Result<List<SessionItem>>? {
        val connectionId = activeConnectionId.value ?: return null
        val dashboardUrl = activeDashboardUrlProvider() ?: return null
        return dashboardClientFactory(connectionId, dashboardUrl).listAllProfileSessions(limit)
    }

    suspend fun deleteSession(
        profileName: String?,
        sessionId: String,
        expectedContextKey: String? = null,
    ): Boolean {
        val connectionId = activeConnectionId.value ?: return false
        val dashboardUrl = activeDashboardUrlProvider() ?: return false
        if (
            expectedContextKey != null &&
            AgentDisplay.profileContextKey(connectionId, profileName) != expectedContextKey
        ) return false
        return dashboardClientFactory(connectionId, dashboardUrl)
            .deleteSession(sessionId, profileName)
            .isSuccess
    }

    suspend fun renameSession(
        profileName: String?,
        sessionId: String,
        title: String,
        expectedContextKey: String? = null,
    ): Boolean {
        val connectionId = activeConnectionId.value ?: return false
        val dashboardUrl = activeDashboardUrlProvider() ?: return false
        if (
            expectedContextKey != null &&
            AgentDisplay.profileContextKey(connectionId, profileName) != expectedContextKey
        ) return false
        return dashboardClientFactory(connectionId, dashboardUrl)
            .renameSession(sessionId, title, profileName)
            .isSuccess
    }

    suspend fun setSessionPinned(
        profileName: String?,
        sessionId: String,
        pinned: Boolean,
        expectedContextKey: String? = null,
    ): Boolean {
        val connectionId = activeConnectionId.value ?: return false
        val dashboardUrl = activeDashboardUrlProvider() ?: return false
        if (
            expectedContextKey != null &&
            AgentDisplay.profileContextKey(connectionId, profileName) != expectedContextKey
        ) return false
        return dashboardClientFactory(connectionId, dashboardUrl)
            .setSessionPinned(sessionId, pinned, profileName)
            .isSuccess
    }

    suspend fun setSessionArchived(
        profileName: String?,
        sessionId: String,
        archived: Boolean,
        expectedContextKey: String? = null,
    ): Boolean {
        val connectionId = activeConnectionId.value ?: return false
        val dashboardUrl = activeDashboardUrlProvider() ?: return false
        if (
            expectedContextKey != null &&
            AgentDisplay.profileContextKey(connectionId, profileName) != expectedContextKey
        ) return false
        return dashboardClientFactory(connectionId, dashboardUrl)
            .setSessionArchived(sessionId, archived, profileName)
            .isSuccess
    }

    /**
     * A session's transcript, scoped to the active profile via the dashboard
     * `/api/sessions/{id}/messages?profile=`. Returns `null` off the dashboard
     * surface so the caller falls back to the api_server transcript.
     */
    suspend fun loadProfileScopedMessages(
        sessionId: String,
        mode: SessionMessageLoadMode = SessionMessageLoadMode.COMPLETE,
    ): Result<List<MessageItem>>? = loadProfileScopedMessages(
        profileName = resolveSessionProfileName(),
        sessionId = sessionId,
        mode = mode,
    )

    suspend fun loadProfileScopedMessages(
        profileName: String?,
        sessionId: String,
        mode: SessionMessageLoadMode = SessionMessageLoadMode.COMPLETE,
    ): Result<List<MessageItem>>? {
        val connectionId = activeConnectionId.value ?: return null
        val dashboardUrl = activeDashboardUrlProvider() ?: return null
        return dashboardClientFactory(connectionId, dashboardUrl)
            .getSessionMessages(sessionId, profileName, mode)
    }

    suspend fun deleteProfileScopedSession(sessionId: String): Boolean {
        val connectionId = activeConnectionId.value ?: return false
        val dashboardUrl = activeDashboardUrlProvider() ?: return false
        return dashboardClientFactory(connectionId, dashboardUrl)
            .deleteSession(sessionId, resolveSessionProfileName())
            .isSuccess
    }

    suspend fun renameProfileScopedSession(sessionId: String, title: String): Boolean {
        val connectionId = activeConnectionId.value ?: return false
        val dashboardUrl = activeDashboardUrlProvider() ?: return false
        return dashboardClientFactory(connectionId, dashboardUrl)
            .renameSession(sessionId, title, resolveSessionProfileName())
            .isSuccess
    }

    suspend fun setProfileScopedSessionPinned(
        sessionId: String,
        pinned: Boolean,
        expectedContextKey: String? = null,
    ): Boolean {
        val connectionId = activeConnectionId.value ?: return false
        val dashboardUrl = activeDashboardUrlProvider() ?: return false
        val profileName = resolveSessionProfileName()
        if (
            expectedContextKey != null &&
            AgentDisplay.profileContextKey(connectionId, profileName) != expectedContextKey
        ) return false
        return dashboardClientFactory(connectionId, dashboardUrl)
            .setSessionPinned(sessionId, pinned, profileName)
            .isSuccess
    }

    suspend fun setProfileScopedSessionArchived(
        sessionId: String,
        archived: Boolean,
        expectedContextKey: String? = null,
    ): Boolean {
        val connectionId = activeConnectionId.value ?: return false
        val dashboardUrl = activeDashboardUrlProvider() ?: return false
        val profileName = resolveSessionProfileName()
        if (
            expectedContextKey != null &&
            AgentDisplay.profileContextKey(connectionId, profileName) != expectedContextKey
        ) return false
        return dashboardClientFactory(connectionId, dashboardUrl)
            .setSessionArchived(sessionId, archived, profileName)
            .isSuccess
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
            profileIconStore.setLocalOverride(connectionId, profileName, true)
        }
    }

    /** Import the active profile's conventional avatar file from its host. */
    fun importProfileIconFromHost() {
        val connectionId = activeConnectionId.value ?: return
        val profileName = AgentDisplay.profileRequestName(_selectedProfile.value?.name)
        val client = relayHttpClient
        if (client == null) {
            _hostIconImportState.value = HostIconImportState(
                error = "Relay is not available for host image import"
            )
            return
        }
        scope.launch {
            _hostIconImportState.value = HostIconImportState(loading = true)
            client.fetchProfileAvatar(profileName).fold(
                onSuccess = { media ->
                    val path = copyIconBytes(connectionId, profileName, media.bytes)
                    if (path == null) {
                        _hostIconImportState.value = HostIconImportState(
                            error = "Could not save the imported profile image"
                        )
                    } else {
                        profileIconStore.setIcon(connectionId, profileName, path)
                        profileIconStore.setLocalOverride(connectionId, profileName, true)
                        _hostIconImportState.value = HostIconImportState()
                    }
                },
                onFailure = { failure ->
                    _hostIconImportState.value = HostIconImportState(
                        error = failure.message ?: "Host profile image import failed"
                    )
                },
            )
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

    fun setUseLocalProfileIconOverride(enabled: Boolean) {
        val connectionId = activeConnectionId.value ?: return
        val profileName = AgentDisplay.profileRequestName(_selectedProfile.value?.name)
        scope.launch { profileIconStore.setLocalOverride(connectionId, profileName, enabled) }
    }

    /** Upload a selected static image directly to Hermes without changing this phone's override. */
    fun setSharedProfileAvatar(uri: Uri) {
        val connectionId = activeConnectionId.value ?: return
        val profileName = resolveSharedAssetProfileName()
        val gateway = gatewayClientProvider()
        if (profileName.isNullOrBlank() || gateway == null) {
            _sharedAvatarState.value = SharedAvatarState(error = "Shared avatars require a current Hermes Gateway")
            return
        }
        scope.launch {
            _sharedAvatarState.value = SharedAvatarState(loading = true)
            val bytes = withContext(Dispatchers.IO) {
                prepareProfileAvatar(context, uri, GatewayChatClient.PROFILE_AVATAR_MAX_BYTES)
            }
            if (bytes == null) {
                _sharedAvatarState.value = SharedAvatarState(error = "That image could not be prepared for Hermes")
                return@launch
            }
            gateway.setProfileAvatar(profileName, bytes).fold(
                onSuccess = {
                    cacheAcknowledgedSharedAvatar(connectionId, profileName, bytes)
                    _sharedAvatarState.value = SharedAvatarState()
                },
                onFailure = { failure ->
                    _sharedAvatarState.value = SharedAvatarState(
                        error = failure.message ?: "Shared avatar upload failed",
                    )
                },
            )
        }
    }

    /** Explicit migration: upload the current device-local fallback to Hermes. */
    fun uploadLocalProfileIconToHermes() {
        val connectionId = activeConnectionId.value ?: return
        val profileName = resolveSharedAssetProfileName()
        if (profileName.isNullOrBlank()) {
            _sharedAvatarState.value = SharedAvatarState(error = "Hermes profile identity is not available")
            return
        }
        val gateway = gatewayClientProvider()
        if (gateway == null) {
            _sharedAvatarState.value = SharedAvatarState(error = "Shared avatars require a current Hermes Gateway")
            return
        }
        scope.launch {
            _sharedAvatarState.value = SharedAvatarState(loading = true)
            val path = profileIconStore.iconFlow(
                connectionId,
                AgentDisplay.profileRequestName(_selectedProfile.value?.name),
            ).first()
            val file = path?.let(::File)
            val bytes = when {
                file == null || !file.isFile -> null
                file.length() > GatewayChatClient.PROFILE_AVATAR_MAX_BYTES -> null
                else -> withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
            }
            if (bytes == null) {
                _sharedAvatarState.value = SharedAvatarState(error = "Choose a local PNG, JPEG, or WebP under 2 MB first")
                return@launch
            }
            gateway.setProfileAvatar(profileName, bytes).fold(
                onSuccess = {
                    cacheAcknowledgedSharedAvatar(connectionId, profileName, bytes)
                    _sharedAvatarState.value = SharedAvatarState()
                },
                onFailure = { failure ->
                    _sharedAvatarState.value = SharedAvatarState(
                        error = failure.message ?: "Shared avatar upload failed",
                    )
                },
            )
        }
    }

    /** Clear only Hermes' shared asset; the device-local fallback remains untouched. */
    fun clearSharedProfileAvatar() {
        val connectionId = activeConnectionId.value ?: return
        val profileName = resolveSharedAssetProfileName()
        val gateway = gatewayClientProvider()
        if (profileName.isNullOrBlank() || gateway == null) {
            _sharedAvatarState.value = SharedAvatarState(error = "Shared avatars require a current Hermes Gateway")
            return
        }
        scope.launch {
            _sharedAvatarState.value = SharedAvatarState(loading = true)
            gateway.clearProfileAvatar(profileName).fold(
                onSuccess = {
                    val generation = avatarRefreshGeneration.incrementAndGet()
                    clearServerAvatarCache(connectionId, profileName, generation)
                    _gatewayProfiles.value = _gatewayProfiles.value.map { profile ->
                        if (profile.name == profileName) profile.copy(hasAvatar = false) else profile
                    }
                    _sharedAvatarState.value = SharedAvatarState()
                },
                onFailure = { failure ->
                    _sharedAvatarState.value = SharedAvatarState(
                        error = failure.message ?: "Shared avatar clear failed",
                    )
                },
            )
        }
    }

    /** Copy [uri]'s image bytes into app-internal storage; returns the path or null. */
    private suspend fun copyIcon(connectionId: String, profileName: String?, uri: Uri): String? =
        readBoundedUri(uri, LOCAL_PROFILE_ICON_MAX_BYTES)?.let { bytes ->
            copyIconBytes(connectionId, profileName, bytes)
        }

    private suspend fun copyIconBytes(
        connectionId: String,
        profileName: String?,
        bytes: ByteArray,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val extension = localImageExtension(bytes) ?: return@withContext null
            val dir = File(context.filesDir, "profile-icons").apply { mkdirs() }
            val key = AgentDisplay.profileSessionKey(profileName)
            val safe = "${connectionId}_$key"
                .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
                .joinToString("")
            val target = File(dir, "$safe.$extension")
            target.writeBytes(bytes)
            LOCAL_PROFILE_ICON_EXTENSIONS
                .filterNot(extension::equals)
                .forEach { stale -> runCatching { File(dir, "$safe.$stale").delete() } }
            target.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun readBoundedUri(uri: Uri, maxBytes: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) return@use null
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            }.getOrNull()
        }

    private fun localImageExtension(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
        ) -> "png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[2] == 0xff.toByte() -> "jpg"
        bytes.size >= 6 && (
            bytes.copyOfRange(0, 6).contentEquals("GIF87a".toByteArray()) ||
                bytes.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray())
            ) -> "gif"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "webp"
        else -> null
    }

    private suspend fun copyServerAvatarBytes(
        connectionId: String,
        profileName: String,
        bytes: ByteArray,
        mime: String,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val extension = when (mime) {
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                else -> return@withContext null
            }
            val dir = File(context.filesDir, "profile-avatars-server").apply { mkdirs() }
            val safe = MessageDigest.getInstance("SHA-256")
                .digest("$connectionId\u0000${AgentDisplay.profileSessionKey(profileName)}".toByteArray())
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            val target = File(dir, "$safe.$extension")
            val temp = File(dir, "$safe.$extension.tmp")
            temp.writeBytes(bytes)
            try {
                java.nio.file.Files.move(
                    temp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    temp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
            listOf("png", "jpg", "webp")
                .filterNot(extension::equals)
                .forEach { stale -> runCatching { File(dir, "$safe.$stale").delete() } }
            target.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun cacheAcknowledgedSharedAvatar(
        connectionId: String,
        profileName: String,
        bytes: ByteArray,
    ) {
        if (activeConnectionId.value != connectionId) return
        val mime = profileAvatarMime(bytes) ?: return
        val generation = avatarRefreshGeneration.incrementAndGet()
        val path = copyServerAvatarBytes(connectionId, profileName, bytes, mime) ?: return
        if (!isCurrentProfileAvatarRefresh(
                connectionId,
                activeConnectionId.value,
                generation,
                avatarRefreshGeneration.get(),
            )
        ) return
        profileIconStore.setServerAvatar(connectionId, profileName, path)
        _gatewayProfiles.value = _gatewayProfiles.value.map { profile ->
            if (profile.name == profileName) profile.copy(hasAvatar = true) else profile
        }
    }

    private fun resolveSharedAssetProfileName(): String? =
        resolveSessionProfileName()
            ?: _gatewayProfiles.value.firstOrNull(Profile::isDefault)?.name

    private companion object {
        const val LOCAL_PROFILE_ICON_MAX_BYTES = 8_000_000
        val LOCAL_PROFILE_ICON_EXTENSIONS = listOf("png", "jpg", "gif", "webp")
    }

    /**
     * The stored lock-token for a (possibly null) profile. Server default —
     * A null selection maps to [AgentDisplay.SERVER_DEFAULT_PROFILE_KEY]; a
     * profile literally named `default`, like every named profile, maps to its name.
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

    fun isProfileSelectionAllowed(profileName: String?): Boolean {
        val locked = lockedProfileName.value ?: return true
        return AgentDisplay.profileSessionKey(profileName) == locked
    }

    fun moveProfile(profileName: String?, delta: Int) {
        val connectionId = activeConnectionId.value ?: return
        val key = AgentDisplay.profileSessionKey(profileName)
        scope.launch {
            profilePresentationWriteMutex.withLock {
                val current = profilePresentationStore.presentationFlow(connectionId).first()
                val order = ProfilePresentationPolicy
                    .orderedKeys(agentProfiles.value, current)
                    .toMutableList()
                val from = order.indexOf(key)
                if (from < 0) return@withLock
                val to = (from + delta).coerceIn(0, order.lastIndex)
                if (from == to) return@withLock
                order.removeAt(from)
                order.add(to, key)
                profilePresentationStore.setOrder(connectionId, order)
            }
        }
    }

    fun setProfileHidden(profileName: String?, hidden: Boolean) {
        val connectionId = activeConnectionId.value ?: return
        val key = AgentDisplay.profileSessionKey(profileName)
        scope.launch {
            profilePresentationWriteMutex.withLock {
                val updated = profilePresentationStore
                    .presentationFlow(connectionId)
                    .first()
                    .hidden
                    .toMutableSet()
                    .apply { if (hidden) add(key) else remove(key) }
                profilePresentationStore.setHidden(connectionId, updated)
            }
        }
    }

    /** Persist or clear a local cosmetic accent for a named profile. */
    fun setProfileColor(profileName: String, colorHex: String?) {
        val connectionId = activeConnectionId.value ?: return
        val key = profileName.trim()
        if (key.isBlank() || key.equals("default", ignoreCase = true)) return
        scope.launch {
            profilePresentationWriteMutex.withLock {
                val updated = profilePresentationStore
                    .presentationFlow(connectionId)
                    .first()
                    .colors
                    .toMutableMap()
                    .apply { if (colorHex == null) remove(key) else put(key, colorHex) }
                profilePresentationStore.setColors(connectionId, updated)
            }
        }
    }

    fun resetProfilePresentation() {
        val connectionId = activeConnectionId.value ?: return
        scope.launch {
            profilePresentationWriteMutex.withLock {
                profilePresentationStore.clear(connectionId)
            }
        }
    }

    /**
     * The actual selection write — runs the full profile-switch machinery
     * (fresh draft via [setLastSessionId], pending-state stamp, persist,
     * chat-API rebuild, last-session restore). Bypasses the lock gate so
     * [lockProfile] can force-select the new locked target even mid-relock;
     * [selectProfile] is the gated public entry point.
     */
    private fun applyProfileSelection(normalizedProfile: Profile?) {
        _sharedAvatarState.value = SharedAvatarState()
        _hostIconImportState.value = HostIconImportState()
        petRefreshGeneration.incrementAndGet()
        petGalleryGeneration.incrementAndGet()
        _hermesPetState.value = HermesPetState()
        _selectedProfile.value = normalizedProfile
        setLastSessionId(null)
        val connectionId = activeConnectionId.value ?: return
        _pendingSelectedProfileConnectionId.value = connectionId
        _pendingSelectedProfileName.value = normalizedProfile?.name
        scope.launch {
            profileSelectionStore.setSelectedProfile(connectionId, normalizedProfile?.name)
            rebuildChatApiClient()
            refreshHermesPet(connectionId)
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
        if (pendingName == AgentDisplay.SERVER_DEFAULT_PROFILE_KEY) {
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
        if (token == AgentDisplay.SERVER_DEFAULT_PROFILE_KEY) {
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
     * sentinel so it's distinct from "unlocked"). Forces the selection at the
     * ViewModel boundary, then persists the lock so
     * the existing profile-switch machinery (fresh draft, gateway hot-swap, chat
     * API rebuild) runs. Locking to the already-selected profile is effectively
     * a no-op for the selection but still records the lock.
     */
    fun lockProfile(profile: Profile?) {
        val connectionId = activeConnectionId.value ?: return
        val normalizedProfile = AgentDisplay.normalizeSelection(profile)
        val token = lockTokenFor(normalizedProfile)
        // Force-select synchronously so callers can immediately detach/clear the
        // old Gateway session against the new profile identity. Persistence is
        // serialized by DataStore and the lock StateFlow catches up afterward.
        applyProfileSelection(normalizedProfile)
        scope.launch { profileLockStore.setLockedProfile(connectionId, token) }
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

    /**
     * Persist a user-requested empty draft for one exact conversation scope.
     *
     * The in-memory marker fences any stored-session read that was already in
     * flight, while clearing the exact transport slot makes the draft survive a
     * process restart. Other profiles, connections, transports, and the server's
     * actual session/history rows are untouched.
     */
    fun markFreshDraft(
        connectionId: String,
        profileName: String?,
        transport: SessionTransport,
    ) {
        val scopeKey = SessionScopeKey(connectionId, profileName, transport)
        freshDraftScopes += scopeKey
        sessionRestoreGeneration.incrementAndGet()
        if (
            activeConnectionId.value == connectionId &&
            _selectedProfile.value?.name == profileName
        ) {
            setLastSessionId(null)
        }
        scope.launch {
            profileSessionStore.setSessionId(connectionId, profileName, transport, null)
        }
    }

    /** A real session supersedes the fresh-draft marker for its exact scope. */
    fun markSessionPersisted(
        connectionId: String,
        profileName: String?,
        transport: SessionTransport,
    ) {
        freshDraftScopes -= SessionScopeKey(connectionId, profileName, transport)
        sessionRestoreGeneration.incrementAndGet()
    }

    fun refreshLastSessionForProfile(
        connectionId: String?,
        profileName: String?,
    ) {
        val generation = sessionRestoreGeneration.incrementAndGet()
        setLastSessionId(null)
        if (connectionId == null) return
        // Defer until the active transport is known — restoring an id the
        // current transport can't resume is exactly what forks a session
        // mid-conversation on a non-default profile.
        val transport = activeSessionTransport() ?: return
        // Persist the UI selection identity, not the resolved server route.
        // Server default may currently route to a sticky profile named
        // `default` (or any other name), but its last-session slot must remain
        // distinct from explicitly selecting that named profile.
        val sessionProfileName = profileName
        val scopeKey = SessionScopeKey(connectionId, sessionProfileName, transport)
        if (scopeKey in freshDraftScopes) return
        scope.launch {
            val profileScoped = profileSessionStore
                .sessionIdFlow(connectionId, sessionProfileName, transport)
                .first()
            // Default profile shares the launch DB across both transports, so a
            // pre-transport (untransported) pointer is still resumable — surface
            // it as the fallback only for the server-default context.
            val legacyDefault = if (
                sessionProfileName == null
            ) {
                legacyDefaultSessionId()
            } else {
                null
            }
            if (
                sessionRestoreGeneration.get() == generation &&
                scopeKey !in freshDraftScopes &&
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
        _serverDefaultProfileScope.value = null
        _serverDefaultProfileSettled.value = false
        // Dashboard profile lists are per-connection — drop the old one so the
        // pending persisted name can't resolve against the previous connection's
        // profiles before the new connection's list arrives.
        _dashboardProfiles.value = emptyList()
        _gatewayProfiles.value = emptyList()
        _gatewayRosterAuthoritative.value = false
        avatarRefreshGeneration.incrementAndGet()
        petRefreshGeneration.incrementAndGet()
        petGalleryGeneration.incrementAndGet()
        _hermesPetState.value = HermesPetState()
    }

    /** Clear just the in-memory selection + pending state (resetAppData). */
    fun clearSelectionState() {
        _selectedProfile.value = null
        _pendingSelectedProfileConnectionId.value = null
        _pendingSelectedProfileName.value = null
        _serverDefaultProfileScope.value = null
        _serverDefaultProfileSettled.value = false
        petRefreshGeneration.incrementAndGet()
        petGalleryGeneration.incrementAndGet()
        _hermesPetState.value = HermesPetState()
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

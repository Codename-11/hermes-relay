package com.hermesandroid.relay.runtime

import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.HermesRelayApp
import com.hermesandroid.relay.assistant.AssistantAppSessionState
import com.hermesandroid.relay.assistant.AssistantSessionPhase
import com.hermesandroid.relay.assistant.AssistantSessionProtocol
import com.hermesandroid.relay.assistant.AssistantSessionSnapshot
import com.hermesandroid.relay.assistant.AssistantVoiceCommandCoordinator
import com.hermesandroid.relay.audio.BargeInListener
import com.hermesandroid.relay.audio.RealtimePcmPlayer
import com.hermesandroid.relay.audio.VadEngine
import com.hermesandroid.relay.audio.VoicePlayer
import com.hermesandroid.relay.audio.VoiceRecorder
import com.hermesandroid.relay.audio.VoiceSfxPlayer
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.BargeInPreferencesRepository
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.data.EnhancedVoiceOverrides
import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.data.VoiceEngineMode
import com.hermesandroid.relay.data.VoicePreferencesRepository
import com.hermesandroid.relay.data.VoiceSettings
import com.hermesandroid.relay.network.relay.RelayVoiceAudioClientAdapter
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.network.shared.AutoVoiceAudioClient
import com.hermesandroid.relay.network.shared.pluginProxyRoutesOrNull
import com.hermesandroid.relay.network.upstream.StandardHermesVoiceClient
import com.hermesandroid.relay.viewmodel.SESSION_DIRECTORY_PAGE_SIZE
import com.hermesandroid.relay.viewmodel.StandardVoiceAvailability
import com.hermesandroid.relay.viewmodel.VoiceState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

/**
 * Process-lifetime composition root for the chat and voice state machines.
 *
 * This owns only runtime behavior. Navigation, Activity lifecycle revalidation,
 * and visual presentation remain UI responsibilities.
 */
internal class HermesRuntimeBinder(
    private val application: HermesRelayApp,
    private val runtime: HermesProcessRuntime,
) {
    private val jobs = mutableListOf<Job>()
    private var bound = false

    val voicePreferencesRepository = VoicePreferencesRepository(application)
    private val _voiceSettings = MutableStateFlow(VoiceSettings())
    val voiceSettings: StateFlow<VoiceSettings> = _voiceSettings.asStateFlow()
    private lateinit var relayVoiceClient: RelayVoiceClient

    private val _voiceActivationReadiness =
        MutableStateFlow<HermesVoiceActivationReadiness>(HermesVoiceActivationReadiness.Initializing)
    val voiceActivationReadiness: StateFlow<HermesVoiceActivationReadiness> =
        _voiceActivationReadiness.asStateFlow()
    private val voiceSettingsHydrated = MutableStateFlow(false)
    private val profileContextReady = MutableStateFlow(false)

    private val _assistantSnapshot = MutableStateFlow(AssistantSessionSnapshot())
    val assistantSnapshot: StateFlow<AssistantSessionSnapshot> = _assistantSnapshot.asStateFlow()

    fun bind() {
        if (bound) return

        val connection = runtime.connectionViewModel
        val chat = runtime.chatViewModel
        val voice = runtime.voiceViewModel

        relayVoiceClient = RelayVoiceClient(
            context = application,
            okHttpClient = OkHttpClient.Builder()
                .readTimeout(2, TimeUnit.MINUTES)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build(),
            relayUrlProvider = { connection.effectiveRelayUrl.value },
            relayRouteChangesProvider = {
                connection.activeRelayEndpoint.mapNotNull { endpoint ->
                    endpoint?.pluginProxyRoutesOrNull()?.relayWebSocketUrl
                        ?: endpoint?.relay?.url
                }
            },
            routeProbeRequester = connection::probeNow,
            profileNameProvider = {
                AgentDisplay.profileRequestName(connection.selectedProfile.value?.name)
            },
            sessionTokenProvider = {
                (connection.authState.value as? AuthState.Paired)?.token
            },
            apiBearerTokenProvider = connection::getApiKey,
            dashboardHttpClientProvider = connection::dashboardHttpClientForRelayIngress,
            dashboardIngressWebSocketRequestProvider = connection::dashboardRelayRequestForIngress,
        )
        val standardVoiceClient = StandardHermesVoiceClient(
            context = application,
            dashboardHttpClientProvider = connection::dashboardHttpClientForActive,
            dashboardUrlProvider = connection::activeDashboardUrl,
            profileProvider = {
                AgentDisplay.profileRequestName(connection.selectedProfile.value?.name)
            },
        )
        val voiceAudioClient = AutoVoiceAudioClient(
            standardClient = standardVoiceClient,
            relayClient = RelayVoiceAudioClientAdapter(
                relayVoiceClient,
                enhancedOverridesProvider = {
                    EnhancedVoiceOverrides.fromSettings(voiceSettings.value)
                },
            ),
            routeProvider = {
                VoiceAudioRoute.fromStorage(voiceSettings.value.audioRoute)
            },
            standardReadyProvider = { connection.standardVoiceReady.value },
            relayReadyProvider = { connection.relayVoiceReady.value },
        )

        voice.initialize(
            voiceClient = relayVoiceClient,
            voiceAudioClient = voiceAudioClient,
            chatViewModel = chat,
            recorder = VoiceRecorder(application, voice.viewModelScope),
            player = VoicePlayer(application),
            realtimePcmPlayer = RealtimePcmPlayer(application),
            sfxPlayer = VoiceSfxPlayer(application),
            bridgeMultiplexer = connection.multiplexer,
            localBridgeDispatcher = if (BuildFlavor.isSideload) {
                connection.bridgeCommandHandler::handleLocalCommand
            } else {
                null
            },
            voicePreferences = voicePreferencesRepository,
            voiceRelayPreflight = connection::verifyRelayForVoice,
            voiceHandoffReporter = connection::recordVoiceHandoff,
            bargeInPreferences = BargeInPreferencesRepository(application),
            vadEngineFactory = { VadEngine(application) },
            bargeInListenerFactory = { vad ->
                BargeInListener.create(application, vad)
            },
        )

        bindChatDependencies()
        bindProcessCollectors()
        bound = true
    }

    private fun bindChatDependencies() {
        val connection = runtime.connectionViewModel
        val chat = runtime.chatViewModel
        val voice = runtime.voiceViewModel

        chat.initialize(connection.chatApiClient.value, connection.chatHandler)
        chat.initializeMedia(
            context = application,
            relayHttpClient = connection.relayHttpClient,
            mediaSettingsRepo = connection.mediaSettingsRepo,
            mediaCacheWriter = connection.mediaCacheWriter,
        )
        chat.setSelectedProfileProvider { connection.selectedProfile.value }
        chat.setIsolatedProfileApiProvider { connection.selectedProfileUsesIsolatedApiRoute() }
        chat.setSessionProfileNameProvider { connection.effectiveSessionProfileName.value }
        chat.setEffectiveProfileProvider {
            AgentDisplay.effectiveProfile(
                selectedProfile = connection.selectedProfile.value,
                profiles = connection.agentProfiles.value,
            )
        }
        chat.setDisplayProfileProvider { connection.effectiveDisplayProfile.value }
        chat.setDisplayAliasProvider { connection.profileDisplayAlias.value }
        chat.setLockedProfileNameProvider { connection.lockedProfileName.value }
        chat.setProfileSelectionHandler { profile ->
            if (!connection.isProfileSelectionAllowed(profile?.name)) {
                false
            } else {
                connection.selectProfile(profile)
                true
            }
        }
        chat.setProfileSessionLister { profileName ->
            connection.listProfileScopedSessions(
                profileName = profileName,
                limit = SESSION_DIRECTORY_PAGE_SIZE,
                excludeSources = connection.hiddenSources.value,
            )
        }
        chat.setProfileSessionPageLister { profileName, offset, limit ->
            connection.listProfileScopedSessions(
                profileName = profileName,
                limit = limit,
                offset = offset,
                excludeSources = connection.hiddenSources.value,
            )
        }
        chat.setProfileMessageLoaderWithMode { profileName, sessionId, mode ->
            connection.loadProfileScopedMessages(profileName, sessionId, mode)
        }
        chat.setDashboardSignInRequiredHandler(connection::probeNow)
        chat.setDashboardConfigLoader { connection.loadActiveDashboardConfig() }
        chat.profileSessionDeleter = connection::deleteSession
        chat.profileSessionRenamer = connection::renameSession
        chat.profileSessionPinner = connection::setSessionPinned
        chat.profileSessionArchiver = connection::setSessionArchived
        chat.onSessionChanged = connection::saveLastSessionId
        chat.onFreshDraftSelected = connection::saveFreshDraft
        chat.setDemoModeWiring(
            isDemo = { connection.isDemoMode.value },
            handler = { connection.chatHandler },
        )
        voice.chatNoticeSink = connection.chatHandler::addSystemNotice

        connection.registerStreamCancelCallback(chat::cancelStream)
        if (BuildFlavor.isSideload) {
            connection.registerVoiceStopCallback(voice::exitVoiceMode)
        }
        chat.observeConnectionSwitches(connection.connectionSwitchEvents)
    }

    private fun bindProcessCollectors() {
        val connection = runtime.connectionViewModel
        val chat = runtime.chatViewModel
        val voice = runtime.voiceViewModel
        var boundCatalogConnectionId: String? = null
        var lastAcquiredDashboardUrl: String? = null

        jobs += runtime.coroutineScope.launch {
            connection.authState.collect { auth ->
                if (auth is AuthState.Paired) connection.reconnectIfStale()
            }
        }
        jobs += runtime.coroutineScope.launch {
            combine(connection.chatApiClient, connection.activeConnectionId) { client, id ->
                client to id
            }.collect { (client, connectionId) ->
                if (chat.boundHandler === connection.chatHandler) {
                    if (boundCatalogConnectionId != connectionId) {
                        chat.resetConnectionCatalogs()
                        chat.updateApiClient(null)
                    } else {
                        chat.updateApiClient(client)
                    }
                } else {
                    chat.initialize(client, connection.chatHandler)
                }
                boundCatalogConnectionId = connectionId
            }
        }
        jobs += runtime.coroutineScope.launch {
            chat.isStreaming.collect(connection::setChatStreaming)
        }
        jobs += runtime.coroutineScope.launch {
            combine(
                connection.activeConnectionId,
                connection.selectedProfile,
            ) { connectionId, profile ->
                connectionId to AgentDisplay.profileRequestName(profile?.name)
            }.distinctUntilChanged().collectLatest { (connectionId, profileName) ->
                voiceSettingsHydrated.value = false
                voice.setVoicePrefsConnection(connectionId)
                voicePreferencesRepository.setActiveScope(connectionId, profileName)
                voice.onProfileChanged(profileName)
                // Collect the repository flow directly. Its first value is read
                // from DataStore for the selected scope; unlike a seeded
                // StateFlow, it cannot falsely mark default settings hydrated.
                voicePreferencesRepository.settings.collect { settings ->
                    _voiceSettings.value = settings
                    voiceSettingsHydrated.value = true
                }
            }
        }
        jobs += runtime.coroutineScope.launch {
            combine(
                connection.streamingEndpoint,
                connection.serverCapabilities,
                connection.gatewayAvailability,
                connection.effectiveDashboardUrl,
            ) { preference, _, gateway, dashboardUrl ->
                Triple(preference, gateway, dashboardUrl)
            }.collectLatest { (preference, _, dashboardUrl) ->
                if (
                    lastAcquiredDashboardUrl != null &&
                    lastAcquiredDashboardUrl != dashboardUrl
                ) {
                    delay(GATEWAY_ROUTE_SETTLE_MS)
                }
                val resolved = connection.resolveStreamingEndpoint(preference)
                chat.streamingEndpoint = resolved
                chat.sseFallbackEndpoint = connection.resolveSseStreamingEndpoint()
                chat.updateGatewayClient(
                    if (resolved == "gateway") connection.activeGatewayChatClient() else null,
                )
                lastAcquiredDashboardUrl = dashboardUrl
            }
        }
        jobs += runtime.coroutineScope.launch {
            val contextInputs = combine(
                connection.chatReady,
                connection.activeConnectionId,
                connection.effectiveSessionProfileName,
                connection.lastSessionId,
                connection.activeEndpoint,
            ) { ready, connectionId, profileName, sessionId, activeEndpoint ->
                ProfileContextInputs(
                    ready,
                    connectionId,
                    profileName,
                    sessionId,
                    dashboardRouteResolved = activeEndpoint != null,
                )
            }
            combine(
                contextInputs,
                connection.profileSelectionSettled,
                connection.lockedProfileName,
                connection.hiddenSources,
            ) { inputs, settled, lockedProfileName, hiddenSources ->
                inputs.copy(
                    profileSelectionSettled = settled,
                    profileLocked = lockedProfileName != null,
                    hiddenSources = hiddenSources,
                )
            }.collectLatest { inputs ->
                profileContextReady.value = false
                if (!shouldRefreshSessionDirectory(inputs.chatReady, inputs.dashboardRouteResolved)) {
                    return@collectLatest
                }
                if (!inputs.profileSelectionSettled) {
                    delay(PROFILE_SETTLE_BACKSTOP_MS)
                    // The backstop is diagnostic patience, not permission to
                    // issue an unscoped read. Server-default ownership remains
                    // unknown until the lightweight active-profile scope lands.
                    if (!connection.profileSelectionSettled.value) return@collectLatest
                } else {
                    delay(PROFILE_CONTEXT_COALESCE_MS)
                }
                val contextKey = AgentDisplay.profileContextKey(
                    connectionId = inputs.connectionId,
                    profileName = inputs.profileName,
                )
                if (inputs.profileLocked) {
                    chat.switchProfileContext(contextKey, inputs.sessionId)
                } else {
                    chat.reconcileProfileContext(contextKey, inputs.sessionId)
                }
                chat.refreshSessions()
                profileContextReady.value = true
            }
        }
        jobs += runtime.coroutineScope.launch {
            var metadataHydratedRoute: Pair<String, String>? = null
            chat.sessionDirectoryReadyEvents.collect { event ->
                if (!chat.ownsSessionDirectoryReadyEvent(event)) return@collect
                val connectionId = connection.activeConnectionId.value ?: return@collect
                val expectedContextKey = AgentDisplay.profileContextKey(
                    connectionId = connectionId,
                    profileName = connection.effectiveSessionProfileName.value,
                )
                if (event.contextKey != expectedContextKey) return@collect
                val dashboardUrl = connection.effectiveDashboardUrl.value
                    .takeIf(String::isNotBlank)
                    ?: return@collect
                val routeKey = connectionId to dashboardUrl.trim().trimEnd('/').lowercase()
                if (metadataHydratedRoute == routeKey) return@collect
                metadataHydratedRoute = routeKey
                // `/api/profiles`, Gateway avatars, pets, skills, and model
                // metadata are not session-directory prerequisites. Hydrate
                // them only after exact-owner rows have already published.
                connection.refreshDeferredProfileMetadata()
            }
        }
        jobs += runtime.coroutineScope.launch {
            connection.parseToolAnnotations.collect { enabled ->
                connection.chatHandler.parseToolAnnotations = enabled
            }
        }
        jobs += runtime.coroutineScope.launch {
            connection.showSystemMessages.collect { enabled ->
                connection.chatHandler.showSystemMarkers = enabled
            }
        }
        jobs += runtime.coroutineScope.launch {
            val routeReadiness = combine(
                voiceSettings,
                connection.chatReady,
                connection.standardVoiceAvailability,
                connection.relayVoiceReady,
                connection.profileSelectionSettled,
            ) { settings, chatReady, standard, relayReady, profileSettled ->
                resolveVoiceActivationReadiness(
                    settings,
                    chatReady,
                    standard,
                    relayReady,
                    profileSettled,
                )
            }
            combine(
                routeReadiness,
                voiceSettingsHydrated,
                profileContextReady,
            ) { readiness, settingsReady, contextReady ->
                when {
                    !settingsReady ->
                        HermesVoiceActivationReadiness.Waiting("Loading voice settings")
                    !contextReady &&
                        (readiness as? HermesVoiceActivationReadiness.Ready)?.route !=
                        HermesVoiceActivationRoute.Realtime ->
                        HermesVoiceActivationReadiness.Waiting("Restoring the Hermes chat context")
                    else -> readiness
                }
            }.collect { readiness ->
                _voiceActivationReadiness.value = readiness
            }
        }
        jobs += runtime.coroutineScope.launch {
            voice.uiState.collect { state ->
                val snapshot = AssistantSessionProtocol.snapshotFromVoiceState(state).copy(
                    screenContextSupported = assistantCanTransmitScreenContext(
                        VoiceEngineMode.fromStorage(voiceSettings.value.engineMode),
                    ),
                )
                _assistantSnapshot.value = snapshot
                if (!AssistantAppSessionState.active.value) return@collect
                if (state.voiceMode) AssistantAppSessionState.markVoiceStarted()
                if (state.voiceMode || AssistantAppSessionState.hasVoiceStarted()) {
                    state.assistantActivationId?.let { activationId ->
                        AssistantSessionProtocol.publish(application, activationId, snapshot)
                    }
                }
            }
        }
        jobs += runtime.coroutineScope.launch {
            AssistantVoiceCommandCoordinator.cancelRequest.collect { request ->
                request ?: return@collect
                cancelVoice()
                AssistantVoiceCommandCoordinator.consume(request)
            }
        }
    }

    suspend fun activateVoice(
        activationId: String,
        startNewSession: Boolean,
        manualMic: Boolean,
        expectScreenContext: Boolean,
        timeoutMs: Long,
        isCurrent: () -> Boolean,
    ) {
        check(bound) { "Hermes runtime is not initialized" }
        val connection = runtime.connectionViewModel
        val chat = runtime.chatViewModel
        val voice = runtime.voiceViewModel

        connection.reconnectIfStale()
        connection.revalidate()
        val readiness = withTimeout(timeoutMs) {
            voiceActivationReadiness.first {
                it is HermesVoiceActivationReadiness.Ready ||
                    it is HermesVoiceActivationReadiness.Blocked
            }
        }
        if (readiness is HermesVoiceActivationReadiness.Blocked) {
            error(readiness.reason)
        }
        readiness as HermesVoiceActivationReadiness.Ready

        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "Assistant activation was superseded" }
        check(!voice.uiState.value.voiceMode) { "Hermes voice is already active" }
        // Re-apply scope before entry. The readiness collector already observed
        // the scope's resolved settings, so Realtime prewarm cannot use defaults.
        voice.setVoicePrefsConnection(connection.activeConnectionId.value)
        voice.onProfileChanged(
            AgentDisplay.profileRequestName(connection.selectedProfile.value?.name)
        )
        if (startNewSession) {
            currentCoroutineContext().ensureActive()
            check(isCurrent()) { "Assistant activation was superseded" }
            chat.createNewChat()
        }
        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "Assistant activation was superseded" }
        voice.enterVoiceMode(
            activationId = activationId,
            expectScreenContext = expectScreenContext &&
                readiness.route != HermesVoiceActivationRoute.Realtime,
        )
        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "Assistant activation was superseded" }
        if (!manualMic) {
            voice.startListening()
            check(voice.uiState.value.state == VoiceState.Listening) {
                voice.uiState.value.error ?: "Voice recorder did not enter Listening"
            }
        }
        _voiceActivationReadiness.value = readiness
    }

    fun startAssistantListening() {
        val voice = runtime.voiceViewModel
        if (voice.uiState.value.voiceMode && voice.uiState.value.state == VoiceState.Idle) {
            voice.startListening()
        }
    }

    fun stopAssistantListening() {
        val voice = runtime.voiceViewModel
        if (voice.uiState.value.voiceMode && voice.uiState.value.state == VoiceState.Listening) {
            voice.stopListening()
        }
    }

    fun retryAssistantVoiceAfterFailure() {
        runtime.voiceViewModel.retryAssistantVoiceAfterFailure()
    }

    fun cancelVoice() {
        runtime.voiceViewModel.exitVoiceMode()
    }

    fun requireRelayVoiceClient(): RelayVoiceClient {
        check(bound && ::relayVoiceClient.isInitialized) {
            "Call HermesProcessRuntime.ensureInitialized() before using voice clients"
        }
        return relayVoiceClient
    }

    fun clear() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        bound = false
        _voiceActivationReadiness.value = HermesVoiceActivationReadiness.Initializing
        _assistantSnapshot.value = AssistantSessionSnapshot(phase = AssistantSessionPhase.Closed)
    }

    private data class ProfileContextInputs(
        val chatReady: Boolean,
        val connectionId: String?,
        val profileName: String?,
        val sessionId: String?,
        val dashboardRouteResolved: Boolean,
        val profileSelectionSettled: Boolean = false,
        val profileLocked: Boolean = false,
        val hiddenSources: Set<String> = emptySet(),
    )

    private companion object {
        const val PROFILE_SETTLE_BACKSTOP_MS = 2_500L
        const val PROFILE_CONTEXT_COALESCE_MS = 160L
        const val GATEWAY_ROUTE_SETTLE_MS = 750L
    }
}

/**
 * Session browsing is Dashboard HTTP state, not Gateway-socket state. API-only
 * connections still use chat readiness; Dashboard connections can refresh once
 * the resolver has selected a live route, after the profile-settle fence.
 */
internal fun shouldRefreshSessionDirectory(
    chatReady: Boolean,
    dashboardRouteResolved: Boolean,
): Boolean = chatReady || dashboardRouteResolved

internal fun assistantCanTransmitScreenContext(engineMode: VoiceEngineMode): Boolean =
    engineMode == VoiceEngineMode.HermesVoiceOutput

sealed interface HermesVoiceActivationReadiness {
    data object Initializing : HermesVoiceActivationReadiness
    data class Waiting(val reason: String) : HermesVoiceActivationReadiness
    data class Ready(val route: HermesVoiceActivationRoute) : HermesVoiceActivationReadiness
    data class Blocked(val reason: String) : HermesVoiceActivationReadiness
}

enum class HermesVoiceActivationRoute {
    Standard,
    RelayAudio,
    Realtime,
}

internal fun resolveVoiceActivationReadiness(
    settings: VoiceSettings,
    chatReady: Boolean,
    standardAvailability: StandardVoiceAvailability,
    relayReady: Boolean,
    profileSettled: Boolean,
): HermesVoiceActivationReadiness {
    if (!profileSettled) {
        return HermesVoiceActivationReadiness.Waiting("Loading the selected Hermes profile")
    }
    return when (VoiceEngineMode.fromStorage(settings.engineMode)) {
        VoiceEngineMode.RealtimeAgent -> {
            if (relayReady) {
                HermesVoiceActivationReadiness.Ready(HermesVoiceActivationRoute.Realtime)
            } else {
                HermesVoiceActivationReadiness.Waiting("Waiting for the Relay realtime route")
            }
        }
        VoiceEngineMode.HermesVoiceOutput -> {
            if (!chatReady) {
                return HermesVoiceActivationReadiness.Waiting("Waiting for Hermes chat")
            }
            when (VoiceAudioRoute.fromStorage(settings.audioRoute)) {
                VoiceAudioRoute.Relay -> if (relayReady) {
                    HermesVoiceActivationReadiness.Ready(
                        HermesVoiceActivationRoute.RelayAudio
                    )
                } else {
                    HermesVoiceActivationReadiness.Waiting("Waiting for Relay voice")
                }
                VoiceAudioRoute.Standard -> standardVoiceReadiness(standardAvailability)
                VoiceAudioRoute.Auto -> when {
                    relayReady -> HermesVoiceActivationReadiness.Ready(
                        HermesVoiceActivationRoute.RelayAudio
                    )
                    standardAvailability == StandardVoiceAvailability.Ready ->
                        HermesVoiceActivationReadiness.Ready(
                            HermesVoiceActivationRoute.Standard
                        )
                    else -> standardVoiceReadiness(standardAvailability)
                }
            }
        }
    }
}

private fun standardVoiceReadiness(
    availability: StandardVoiceAvailability,
): HermesVoiceActivationReadiness = when (availability) {
    StandardVoiceAvailability.Ready ->
        HermesVoiceActivationReadiness.Ready(HermesVoiceActivationRoute.Standard)
    StandardVoiceAvailability.SignInRequired ->
        HermesVoiceActivationReadiness.Blocked(
            "Vanilla Hermes voice needs dashboard sign-in in Manage"
        )
    StandardVoiceAvailability.Unsupported ->
        HermesVoiceActivationReadiness.Blocked(
            "This Hermes dashboard does not expose the upstream audio routes"
        )
    StandardVoiceAvailability.Unreachable ->
        HermesVoiceActivationReadiness.Waiting("Waiting for the Hermes dashboard")
    StandardVoiceAvailability.Unknown ->
        HermesVoiceActivationReadiness.Waiting("Checking Vanilla Hermes voice")
}

package com.hermesandroid.relay.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermesandroid.relay.R
import com.hermesandroid.relay.HermesRelayApp
import com.hermesandroid.relay.ui.components.CrashReportGate
import com.hermesandroid.relay.ui.components.CandidateBuildBanner
import com.hermesandroid.relay.ui.components.DemoModeBanner
import com.hermesandroid.relay.ui.components.DemoUnavailableContent
import com.hermesandroid.relay.ui.components.MessageBannerHost
import com.hermesandroid.relay.ui.components.newestPetAssistantIsSettled
import com.hermesandroid.relay.ui.components.newestPetVisitTargetUiKey
import com.hermesandroid.relay.ui.components.LocalAgentIconPath
import com.hermesandroid.relay.ui.components.LocalAvailableSphereSkins
import com.hermesandroid.relay.ui.components.LocalSphereSkin
import com.hermesandroid.relay.ui.components.SphereRegistry
import com.hermesandroid.relay.ui.components.SphereSkinLoader
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.avatar.AgentAvatar
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.LocalAgentAvatar
import com.hermesandroid.relay.ui.components.avatar.LocalAvailableAvatars
import com.hermesandroid.relay.ui.components.avatar.LocalAvailablePets
import com.hermesandroid.relay.ui.components.avatar.LocalBackgroundVisualizationEnabled
import com.hermesandroid.relay.ui.components.avatar.LocalFloatingPet
import com.hermesandroid.relay.ui.components.avatar.LocalPetPlaybackSpeed
import com.hermesandroid.relay.ui.components.avatar.LocalPetStabilize
import com.hermesandroid.relay.ui.components.avatar.PetLoader
import com.hermesandroid.relay.ui.components.avatar.toAvatar
import com.hermesandroid.relay.ui.components.avatar.SphereAvatar
import com.hermesandroid.relay.ui.components.avatar.resolveBackgroundAvatar
import com.hermesandroid.relay.ui.components.FloatingPetCompanion
import com.hermesandroid.relay.ui.components.shouldCompactFloatingPet
import com.hermesandroid.relay.ui.components.pet.LocalPetCompanionCoordinator
import com.hermesandroid.relay.ui.components.pet.LocalPetSafeAreaRegistry
import com.hermesandroid.relay.ui.components.pet.PetCompanionCoordinator
import com.hermesandroid.relay.ui.components.pet.PetInteractionLayer
import com.hermesandroid.relay.ui.components.pet.PetSafeAreaRegistry
import com.hermesandroid.relay.ui.components.pet.petPerchSurface
import com.hermesandroid.relay.ui.components.pet.platformModalOwnsPetLayer
import com.hermesandroid.relay.ui.components.ConnectionSwitcherSheet
import com.hermesandroid.relay.ui.components.ChatTransportStatusBadge
import com.hermesandroid.relay.ui.components.ChatTransportTier
import com.hermesandroid.relay.ui.components.ConnectionSecurityGlyph
import com.hermesandroid.relay.ui.components.PowerFeatureGateScreen
import com.hermesandroid.relay.ui.components.PowerFeatureGateStatus
import com.hermesandroid.relay.ui.components.RelayStatusStrip
import com.hermesandroid.relay.ui.components.UnattendedGlobalBanner
import com.hermesandroid.relay.ui.components.UpdateAvailableBanner
import com.hermesandroid.relay.ui.components.HostResourcePressureBanner
import com.hermesandroid.relay.ui.components.rememberUpdateAvailability
import com.hermesandroid.relay.ui.components.resolveChatTransportStatus
import com.hermesandroid.relay.ui.components.WhatsNewDialog
import com.hermesandroid.relay.ui.components.WhatsNewToast
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.BridgePreferencesRepository
import com.hermesandroid.relay.data.BridgeSafetyPreferencesRepository
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.data.CandidateBuild
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.data.SupervisedModePolicy
import com.hermesandroid.relay.data.SupervisedModeStore
import com.hermesandroid.relay.data.VoicePresentationMode
import com.hermesandroid.relay.data.capabilities
import com.hermesandroid.relay.data.displayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hermesandroid.relay.util.HumanError
import kotlinx.coroutines.delay
import com.hermesandroid.relay.ui.onboarding.OnboardingScreen
import com.hermesandroid.relay.ui.screens.AboutScreen
import com.hermesandroid.relay.ui.screens.AdvancedSettingsScreen
import com.hermesandroid.relay.ui.screens.AnalyticsScreen
import com.hermesandroid.relay.ui.screens.AppearanceSettingsScreen
import com.hermesandroid.relay.ui.screens.CustomThemeScreen
import com.hermesandroid.relay.ui.screens.CustomPetGuideScreen
import com.hermesandroid.relay.ui.screens.PetdexBrowseScreen
import com.hermesandroid.relay.ui.screens.BridgeCoreScreen
import com.hermesandroid.relay.ui.screens.DiagnosticsScreen
import com.hermesandroid.relay.ui.screens.BridgeScreen
// === PHASE3-safety-rails: bridge safety route ===
import com.hermesandroid.relay.ui.screens.BridgeSafetySettingsScreen
import com.hermesandroid.relay.ui.screens.BotGroupDetailScreen
import com.hermesandroid.relay.ui.screens.BotChatScreen
import com.hermesandroid.relay.ui.screens.BotModeScreen
// === END PHASE3-safety-rails ===
import com.hermesandroid.relay.ui.screens.ChatScreen
import com.hermesandroid.relay.ui.screens.ChatSettingsScreen
import com.hermesandroid.relay.ui.screens.ChangelogScreen
import com.hermesandroid.relay.ui.screens.DashboardManagementScreen
import com.hermesandroid.relay.ui.screens.DashboardSignInScreen
import com.hermesandroid.relay.ui.screens.DeveloperSettingsScreen
import com.hermesandroid.relay.ui.screens.MediaSettingsScreen
import com.hermesandroid.relay.ui.screens.PairedDevicesScreen
import com.hermesandroid.relay.ui.screens.ConnectionsSettingsScreen
import com.hermesandroid.relay.ui.screens.PermissionsStatusScreen
import com.hermesandroid.relay.ui.screens.ProfileInspectorScreen
import com.hermesandroid.relay.ui.screens.RealtimeVoiceTestScreen
import com.hermesandroid.relay.ui.screens.SettingsScreen
import com.hermesandroid.relay.ui.screens.SupervisedControlsScreen
import com.hermesandroid.relay.ui.screens.SupervisedAppearanceSettingsScreen
import com.hermesandroid.relay.ui.screens.UsageLimitsScreen
import com.hermesandroid.relay.ui.screens.PluginsScreen
import com.hermesandroid.relay.ui.screens.PluginPageScreen
import com.hermesandroid.relay.ui.screens.GitStateScreen
import com.hermesandroid.relay.viewmodel.GitStateViewModel
import com.hermesandroid.relay.viewmodel.GitStateUiState
import com.hermesandroid.relay.ui.screens.TerminalScreen
import com.hermesandroid.relay.ui.screens.NotificationCompanionSettingsScreen
import com.hermesandroid.relay.ui.screens.ProactiveSettingsScreen
import com.hermesandroid.relay.ui.screens.VoiceSettingsScreen
import com.hermesandroid.relay.ui.screens.prewarmDashboardManage
import com.hermesandroid.relay.ui.theme.AppThemes
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayGridTexture
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.network.relay.RelayProfileInspectorClient
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.viewmodel.ChatRuntimeStatus
import com.hermesandroid.relay.viewmodel.ChatTransportPath
import com.hermesandroid.relay.viewmodel.ChatTransportReadiness
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.plugins.runtime.PLUGIN_API_WRITE_CAPABILITY
import com.hermesandroid.relay.viewmodel.PluginsViewModel
import com.hermesandroid.relay.viewmodel.PluginsHubState
import com.hermesandroid.relay.viewmodel.ProfileInspectorViewModel
import com.hermesandroid.relay.viewmodel.TerminalViewModel
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import com.hermesandroid.relay.viewmodel.resolveChatRuntimeStatus
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.runtime.HermesRuntimeInitializationState

// Global snackbar host so any screen can surface a HumanError without
// plumbing a host through every ViewModel. Provided by RelayApp below.
val LocalSnackbarHost = staticCompositionLocalOf<SnackbarHostState> {
    error("LocalSnackbarHost not provided — wrap your UI in RelayApp's CompositionLocalProvider")
}

// Short-lived snackbar by default; retryable errors get Long so users have
// time to tap the action before it auto-dismisses.
suspend fun SnackbarHostState.showHumanError(err: HumanError): SnackbarResult {
    return showSnackbar(
        message = err.body,
        actionLabel = err.actionLabel,
        duration = if (err.retryable) SnackbarDuration.Long else SnackbarDuration.Short,
    )
}

/** Startup chrome should wait for either standard chat surface, not Relay. */
internal fun hasConfiguredStartupChat(connection: Connection?): Boolean =
    connection?.capabilities?.chatConfigured == true

/**
 * A Pair route is allowed to start once its target connection is active and
 * persisted. Duplicate Renew may authorize one explicit existing-connection
 * handoff; arbitrary active-id mismatches remain blocked so restored state
 * cannot bypass connection/auth hydration.
 */
internal fun resolvePairSetupReady(
    storeHydrated: Boolean,
    connectionId: String?,
    authorizedHandoffId: String?,
    activeConnectionId: String?,
    connectionIds: Set<String>,
    draftConnectionId: String? = null,
): Boolean = connectionId == null || connectionId == draftConnectionId || storeHydrated && activeConnectionId != null &&
    activeConnectionId in connectionIds &&
    (activeConnectionId == connectionId || activeConnectionId == authorizedHandoffId)

/** A user retry replaces even a still-active preparation attempt. */
internal fun shouldStartPairPreparation(hasActiveJob: Boolean, retryRequested: Boolean): Boolean =
    retryRequested || !hasActiveJob

/** A replaced/canceled attempt must not evict the newer job from the route map. */
internal fun isCurrentPairPreparation(mappedJob: Any?, completingJob: Any): Boolean =
    mappedJob === completingJob

/**
 * App-root chat health derived only from the two transports that can carry a
 * conversation. Optional Relay state is deliberately absent.
 */
internal fun resolveAppChatRuntimeStatus(
    connection: Connection?,
    gatewayAvailability: GatewayAvailability,
    apiHealth: ConnectionViewModel.HealthStatus,
): ChatRuntimeStatus {
    val capabilities = connection?.capabilities
    val gateway = when {
        capabilities?.dashboardGatewayConfigured != true -> ChatTransportReadiness.NotConfigured
        gatewayAvailability == GatewayAvailability.Ready -> ChatTransportReadiness.Ready
        gatewayAvailability == GatewayAvailability.Unknown -> ChatTransportReadiness.Connecting
        else -> ChatTransportReadiness.Unavailable
    }
    val api = when {
        capabilities?.apiServerConfigured != true -> ChatTransportReadiness.NotConfigured
        apiHealth == ConnectionViewModel.HealthStatus.Reachable -> ChatTransportReadiness.Ready
        apiHealth == ConnectionViewModel.HealthStatus.Unknown ||
            apiHealth == ConnectionViewModel.HealthStatus.Probing -> ChatTransportReadiness.Connecting
        else -> ChatTransportReadiness.Unavailable
    }
    return resolveChatRuntimeStatus(gateway = gateway, apiSse = api)
}

internal fun shouldSettleStartupUnreachable(
    hasConfiguredChat: Boolean,
    runtimeStatus: ChatRuntimeStatus,
): Boolean =
    hasConfiguredChat &&
        runtimeStatus is ChatRuntimeStatus.Unavailable

/** Route represented by the app footer's currently usable chat transport. */
internal fun resolveFooterRouteCandidate(
    runtimeStatus: ChatRuntimeStatus,
    activeEndpoint: EndpointCandidate?,
    connection: Connection?,
    effectiveDashboardUrl: String,
): EndpointCandidate? {
    val connected = runtimeStatus as? ChatRuntimeStatus.Connected ?: return null
    return when (connected.transport) {
        ChatTransportPath.Gateway -> {
            val dashboardUrl = effectiveDashboardUrl.trim().trimEnd('/')
                .ifBlank { connection?.resolvedDashboardUrl.orEmpty() }
            if (dashboardUrl.isBlank()) {
                null
            } else {
                val activeDashboardUrl = activeEndpoint?.dashboard?.url
                    ?.trim()
                    ?.trimEnd('/')
                activeEndpoint?.takeIf { activeDashboardUrl == dashboardUrl }
                    ?: Connection.endpointCandidateFromDashboardUrl(
                        role = Connection.inferRouteRole(dashboardUrl),
                        priority = activeEndpoint?.priority ?: 0,
                        dashboardUrl = dashboardUrl,
                    )
            }
        }

        ChatTransportPath.ApiSse -> activeEndpoint?.takeIf { it.api != null }
            ?: connection?.routeCandidates?.firstOrNull { it.api != null }
    }
}

/**
 * Compact, surface-aware label for the persistent chat footer.
 *
 * Endpoint roles are operator and wire metadata, so an internal role such as
 * `authenticated_dashboard` must never leak into this constrained surface.
 * Gateway labels describe how the Dashboard is reached; API fallback keeps
 * the route's ordinary transport label.
 */
internal fun resolveFooterRouteLabel(
    runtimeStatus: ChatRuntimeStatus,
    route: EndpointCandidate?,
    fallbackLabel: String,
): String {
    val connected = runtimeStatus as? ChatRuntimeStatus.Connected ?: return ""
    if (route == null) return fallbackLabel
    if (connected.transport == ChatTransportPath.ApiSse) return route.displayLabel()

    return when (route.role.trim().lowercase()) {
        "lan" -> "LAN"
        "tailscale" -> "Tailscale"
        else -> if (
            route.dashboard?.url?.startsWith("https://", ignoreCase = true) == true
        ) {
            "HTTP"
        } else {
            route.displayLabel()
        }
    }
}

/** Keep the footer's model identity compact; context-window suffixes belong in model details. */
internal fun compactFooterModelLabel(model: String): String =
    model.substringAfterLast('/').replace(Regex("-\\d+[kKmM]$"), "")

/**
 * Conversation voice remains part of chat, so its persistent connection
 * footer stays visible. Focus voice is the only presentation that suppresses
 * the surrounding chat chrome.
 */
internal fun shouldShowConnectionFooter(
    voiceMode: Boolean,
    presentationMode: VoicePresentationMode,
): Boolean = !voiceMode || presentationMode == VoicePresentationMode.Conversation

/**
 * Full-screen setup owns the whole window. Keeping connection-dependent app
 * chrome composed behind it makes that chrome rebuild while Add connection
 * swaps to its placeholder, visibly resizing the entering setup surface.
 */
internal fun shouldSuppressGlobalChrome(
    onboardingCompleted: Boolean,
    isDemoMode: Boolean,
    currentRoute: String?,
): Boolean =
    (!onboardingCompleted && !isDemoMode) ||
        currentRoute == Screen.Onboarding.route ||
        currentRoute == Screen.Pair.route

internal const val APP_STATUS_PET_WALK_REGION = "app-status-footer"

/**
 * Routes where the persistent status chrome is deliberately exposed as a
 * pet ledge. Keep this list small: every entry must publish its active-scroll
 * and modal state so autonomous motion never fights the screen beneath it.
 */
internal val APP_STATUS_PET_ROUTES: Set<String> = setOf(
    Screen.Settings.route,
    Screen.AppearanceSettings.route,
    Screen.About.route,
)

internal fun petSurfaceOwnerForRoute(route: String?): String? = when (route) {
    Screen.Chat.route -> "chat"
    Screen.Terminal.route -> "terminal"
    in APP_STATUS_PET_ROUTES -> route
    else -> null
}

/** Petdex already supplies interactive pet previews; keep its install cards unobstructed. */
internal fun floatingPetAllowedOnRoute(route: String?): Boolean =
    route != Screen.PetdexBrowse.route

sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Onboarding : Screen("onboarding", "Onboarding", Icons.Filled.Settings)
    // `openAgentSheet` — optional flag that tells ChatScreen to auto-open
    // its consolidated AgentInfoSheet on first composition. Added so the
    // Settings → Active Agent card can bounce the user straight to Chat
    // with the sheet pre-opened (Connection / Profile / Personality
    // editing lives inside the sheet). The arg is consumed once and then
    // cleared from the back-stack entry's arguments so returning to the
    // Chat tab later via bottom nav does NOT re-open the sheet.
    //
    // `route` stays the NavHost route template — matching the pattern used
    // by Screen.Pair — while [route] (the function) builds concrete URIs.
    // The bottom-nav selected-state hierarchy check keys off [route]
    // (the template), so the template must match what's registered in the
    // NavHost, and the NavigationBarItem click must navigate via [route]()
    // so no unresolved `{openAgentSheet}` leaks into the destination.
    data object Chat : Screen(
        "chat?openAgentSheet={openAgentSheet}&sessionId={sessionId}&profile={profile}" +
            "&proactiveChatId={proactiveChatId}",
        "Chat",
        Icons.AutoMirrored.Filled.Chat,
    ) {
        const val ARG_OPEN_AGENT_SHEET: String = "openAgentSheet"
        const val ARG_SESSION_ID: String = "sessionId"
        const val ARG_PROFILE: String = "profile"
        const val ARG_PROACTIVE_CHAT_ID: String = "proactiveChatId"
        fun route(
            openAgentSheet: Boolean = false,
            sessionId: String? = null,
            profile: String? = null,
            proactiveChatId: String? = null,
        ): String {
            val params = buildList {
                if (openAgentSheet) add("$ARG_OPEN_AGENT_SHEET=true")
                sessionId?.takeIf { it.isNotBlank() }?.let {
                    add("$ARG_SESSION_ID=${android.net.Uri.encode(it)}")
                }
                profile?.takeIf { it.isNotBlank() }?.let {
                    add("$ARG_PROFILE=${android.net.Uri.encode(it)}")
                }
                proactiveChatId?.takeIf { it.isNotBlank() }?.let {
                    add("$ARG_PROACTIVE_CHAT_ID=${android.net.Uri.encode(it)}")
                }
            }
            return if (params.isEmpty()) "chat" else "chat?${params.joinToString("&")}"
        }
    }
    data object BotMode : Screen("bot_mode", "Bot Mode", Icons.Filled.Groups)
    data object BotGroup : Screen(
        "bot_mode/groups/{roomKey}",
        "Bot group",
        Icons.Filled.Groups,
    ) {
        const val ARG_ROOM_KEY: String = "roomKey"
        fun route(roomKey: String): String =
            "bot_mode/groups/${android.net.Uri.encode(roomKey)}"
    }
    data object BotChat : Screen(
        "bot_mode/chat/{connectionId}/{profileName}/{sessionId}",
        "Bot Chat",
        Icons.AutoMirrored.Filled.Chat,
    ) {
        const val ARG_CONNECTION_ID: String = "connectionId"
        const val ARG_PROFILE_NAME: String = "profileName"
        const val ARG_SESSION_ID: String = "sessionId"
        fun route(connectionId: String, profileName: String, sessionId: String): String =
            "bot_mode/chat/${android.net.Uri.encode(connectionId)}/" +
                "${android.net.Uri.encode(profileName)}/${android.net.Uri.encode(sessionId)}"
    }
    data object Terminal : Screen("terminal", "Terminal", Icons.Filled.Code)
    data object Bridge : Screen("bridge", "Bridge", Icons.Filled.PhoneAndroid)
    data object Manage : Screen("manage", "Manage", Icons.Filled.Settings)
    data object DashboardSignIn : Screen(
        "dashboard_sign_in?source={source}",
        "Dashboard sign in",
        Icons.Filled.Settings,
    ) {
        const val ARG_SOURCE: String = "source"
        const val SOURCE_GENERAL: String = "general"
        const val SOURCE_PAIR: String = "pair"
        const val SOURCE_ONBOARDING: String = "onboarding"
        fun route(source: String = SOURCE_GENERAL): String = "dashboard_sign_in?source=$source"
    }
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
    data object Plugins : Screen("plugins", "Plugins", Icons.Filled.Extension)
    data object GitState : Screen("git_state", "Git", Icons.Filled.Code)
    data object PluginPage : Screen(
        "plugins/{pluginId}/pages/{pageId}",
        "Plugin",
        Icons.Filled.Extension,
    ) {
        const val ARG_PLUGIN_ID: String = "pluginId"
        const val ARG_PAGE_ID: String = "pageId"
        fun route(pluginId: String, pageId: String): String =
            "plugins/${encode(pluginId)}/pages/${encode(pageId)}"

        private fun encode(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    // Non-bottom-nav destinations — reached by explicit navigation, not the
    // NavigationBar. "Relay sessions" is the user-facing label; the Kotlin
    // object name keeps `PairedDevices` to avoid churning navigation identifiers
    // and deep-link routes. Opened from Settings → Relay sessions and from the
    // active connection card's Security section.
    data object PairedDevices : Screen("paired_devices", "Relay sessions", Icons.Filled.Settings)
    // Full-screen pair wizard route. Replaces the old in-Settings Dialog
    // launch so the chooser + Confirm + Verify steps + the camera viewport
    // get a real fullscreen surface (the Dialog wasn't actually filling the
    // window — Settings cards were leaking through behind it).
    //
    // Multi-connection: accepts an optional `connectionId` query arg —
    // the ConnectionsSettings "Re-pair" button targets a specific
    // connection. The "Add connection" path creates a transient id-scoped
    // auth draft; it is persisted and activated only after setup succeeds.
    data object Pair : Screen(
        "pair?connectionId={connectionId}&autoStart={autoStart}",
        "Connect",
        Icons.Filled.Settings,
    ) {
        const val ARG_CONNECTION_ID: String = "connectionId"
        /**
         * Optional "skip the chooser, jump into this pair method" hint.
         * Currently only `"scan"` is recognised — the "Add connection" FAB
         * on the Connections screen passes it so the camera opens
         * immediately instead of forcing the user through the Method step.
         * Standard add/re-pair flows leave this null so the full chooser
         * remains available.
         */
        const val ARG_AUTO_START: String = "autoStart"
        fun route(connectionId: String? = null, autoStart: String? = null): String {
            val params = buildList {
                if (connectionId != null) add("connectionId=$connectionId")
                if (autoStart != null) add("autoStart=$autoStart")
            }
            return if (params.isEmpty()) "pair" else "pair?${params.joinToString("&")}"
        }
    }
    data object ConnectionsSettings : Screen("settings/connections", "Gateways", Icons.Filled.Settings)
    // Level-2 detail for a single connection (tabbed: Overview / Routes /
    // Advanced / Security). Drilled into from the Connections list. The
    // `connectionId` path segment survives process death via SavedStateHandle;
    // the route template registers a typed StringType arg and `route(id)`
    // builds the concrete URI (mirrors Screen.ProfileInspector).
    data object ConnectionDetail : Screen(
        "settings/connections/{connectionId}",
        "Connection",
        Icons.Filled.Settings,
    ) {
        const val ARG_CONNECTION_ID: String = "connectionId"
        fun route(connectionId: String): String {
            val encoded = java.net.URLEncoder.encode(connectionId, "UTF-8")
                .replace("+", "%20")
            return "settings/connections/$encoded"
        }
    }
    data object VoiceSettings : Screen("voice_settings", "Voice", Icons.Filled.Settings)
    // === PHASE3-notif-listener-followup ===
    data object NotificationCompanionSettings :
        Screen("settings/notifications", "Notification companion", Icons.Filled.Settings)
    // === END PHASE3-notif-listener-followup ===
    data object ProactiveSettings :
        Screen("settings/proactive", "Threads", Icons.Filled.Settings)
    data object PermissionsSettings : Screen("settings/permissions", "Permissions", Icons.Filled.Settings)
    // === PHASE3-safety-rails: bridge safety route ===
    data object BridgeSafetySettings :
        Screen("settings/bridge_safety", "Bridge safety", Icons.Filled.Settings)
    // === END PHASE3-safety-rails ===
    // Per-category settings sub-screens — split out of the mega SettingsScreen
    // following the VoiceSettingsScreen pattern (see DEVLOG 2026-04-11).
    // (The singular `ConnectionSettings` object was removed on 2026-04-21
    // when its underlying screen was collapsed into the active card of
    // the plural `ConnectionsSettings` subpage. See `ConnectionsSettings`
    // above for the surviving route.)
    data object ChatSettings : Screen("settings/chat", "Chat", Icons.Filled.Settings)
    data object AdvancedSettings : Screen("settings/advanced", "Advanced", Icons.Filled.Settings)
    data object SupervisedAppearanceSettings : Screen(
        "settings/supervised/appearance",
        "Appearance",
        Icons.Filled.Settings,
    )
    data object SupervisedControls : Screen(
        "settings/supervised",
        "Supervised mode",
        Icons.Filled.Settings,
    )
    data object ProviderUsage : Screen("settings/usage", "Usage & limits", Icons.Filled.Settings)
    data object MediaSettings : Screen("settings/media", "Media", Icons.Filled.Settings)
    data object AppearanceSettings : Screen("settings/appearance", "Appearance", Icons.Filled.Settings)
    data object CustomTheme : Screen("settings/appearance/custom-theme", "Custom", Icons.Filled.Settings)
    data object PetdexBrowse : Screen("settings/appearance/petdex", "Petdex", Icons.Filled.Settings)
    data object CustomPetGuide : Screen("settings/appearance/custom-pet", "Create a pet", Icons.Filled.Settings)
    data object Analytics : Screen("settings/analytics", "Analytics", Icons.Filled.Settings)
    data object Diagnostics : Screen("settings/diagnostics", "Diagnostics", Icons.Filled.Settings)
    data object DeveloperSettings : Screen("settings/developer", "Developer", Icons.Filled.Settings)
    data object RealtimeVoiceTest : Screen("settings/developer/realtime_voice", "Realtime voice", Icons.Filled.Settings)
    data object About : Screen("settings/about", "About", Icons.Filled.Settings)

    // Profile Inspector — full-screen read-only viewer with 4 tabs
    // (Config / SOUL / Memory / Skills) for a single profile. The
    // `profileName` path segment survives process death via Android's
    // SavedStateHandle arg propagation; the route template is registered
    // in the NavHost with a typed `StringType` arg, and the concrete
    // URI is built by `route(profileName)`.
    data object ProfileInspector : Screen(
        "settings/profile_inspector/{profileName}?section={section}",
        "Profile Inspector",
        Icons.Filled.Settings,
    ) {
        const val ARG_PROFILE_NAME: String = "profileName"
        const val ARG_SECTION: String = "section"

        /** Tab sections accepted by the `section` query arg. */
        const val SECTION_CONFIG: String = "config"
        const val SECTION_SOUL: String = "soul"
        const val SECTION_MEMORY: String = "memory"
        const val SECTION_SKILLS: String = "skills"

        /**
         * Build a concrete nav URI for the Profile Inspector.
         *
         * @param profileName the profile to inspect (required).
         * @param section     which tab to land on. One of
         *                    [SECTION_CONFIG] / [SECTION_SOUL] /
         *                    [SECTION_MEMORY] / [SECTION_SKILLS].
         *                    Defaults to [SECTION_CONFIG] so callers
         *                    that don't care about the tab — the
         *                    common "Inspect" card entry — land on
         *                    Config, matching the pre-deep-link
         *                    behaviour.
         *
         * Backwards compat: the route template still accepts a
         * call without `?section=` because the query arg has a
         * default value in the navArgument declaration. Old
         * deep-links without the arg resolve to Config.
         */
        fun route(profileName: String, section: String = SECTION_CONFIG): String {
            val encoded = java.net.URLEncoder.encode(profileName, "UTF-8")
                .replace("+", "%20")
            return "settings/profile_inspector/$encoded?section=$section"
        }
    }
}

@Composable
private fun SupervisedStartupLoadingScreen() {
    HermesRelayTheme(themePreference = "dark") {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Loading protected settings…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RelayApp() {
    val applicationContext = LocalContext.current.applicationContext
    val processRuntime = (applicationContext as HermesRelayApp).runtime
    val connectionViewModel: ConnectionViewModel = processRuntime.connectionViewModel
    val chatViewModel: ChatViewModel = processRuntime.chatViewModel
    val terminalViewModel: TerminalViewModel = viewModel()
    val pluginsViewModel: PluginsViewModel = viewModel()
    val gitStateViewModel: GitStateViewModel = viewModel()
    val voiceViewModel: VoiceViewModel = processRuntime.voiceViewModel
    val runtimeInitializationState by processRuntime.initializationState.collectAsState()

    LaunchedEffect(processRuntime) {
        processRuntime.ensureInitialized()
    }
    if (runtimeInitializationState != HermesRuntimeInitializationState.Ready) {
        SupervisedStartupLoadingScreen()
        return
    }

    val voiceClient: RelayVoiceClient = processRuntime.relayVoiceClient
    val voicePreferences = processRuntime.voicePreferences
    val voiceSettings by processRuntime.voiceSettings.collectAsState()
    val voicePresentationMode = VoicePresentationMode.fromStorage(voiceSettings.presentationMode)

    // Composition-scoped coroutine scope for firing connection-store suspend
    // writes off of UI click handlers (rename/revoke/remove) —
    // ConnectionStore's mutations are all suspend fns and we don't want to
    // block the main dispatcher from inside the composable body.
    val connectionSwitchScope = rememberCoroutineScope()
    // Add-connection preparation may outlive the initiating list frame now
    // that navigation happens immediately. Keep the job by placeholder id so
    // an instant Back can wait for creation and then discard it safely.
    val pendingAddConnectionJobs = remember {
        mutableMapOf<String, kotlinx.coroutines.Job>()
    }
    val prepareAddConnection: (String, Boolean) -> Unit = { id, retryRequested ->
        val existingJob = pendingAddConnectionJobs[id]
        if (shouldStartPairPreparation(existingJob?.isActive == true, retryRequested)) {
            if (retryRequested) {
                pendingAddConnectionJobs.remove(id)?.cancel()
            }
            val job = connectionSwitchScope.launch(
                start = kotlinx.coroutines.CoroutineStart.LAZY,
            ) {
                connectionViewModel.beginAddConnection(preAllocatedId = id)
            }
            job.invokeOnCompletion {
                if (isCurrentPairPreparation(pendingAddConnectionJobs[id], job)) {
                    pendingAddConnectionJobs.remove(id)
                }
            }
            pendingAddConnectionJobs[id] = job
            job.start()
        }
    }

    // One-time init: the terminal channel ViewModel registers with the shared
    // multiplexer and observes the relay connection state so it can attach/
    // reattach automatically on network changes.
    val terminalAppContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        terminalViewModel.initialize(
            multiplexer = connectionViewModel.multiplexer,
            connectionState = connectionViewModel.relayConnectionState,
            authState = connectionViewModel.authState,
            authManager = connectionViewModel.authManager,
            tabNameStore = com.hermesandroid.relay.data.TerminalTabNameStore(terminalAppContext),
        )
    }

    // Lifecycle-aware revalidation. ON_RESUME (every time the app comes
    // to the foreground) flips both health badges to Probing and fires a
    // fresh API + relay /health probe. Without this hook, badges showed
    // stale Connected/Disconnected for up to 30s after foregrounding —
    // the entire StateFlow snapshot was preserved across backgrounding
    // even when the underlying server had died or the network had flipped.
    val lifecycleOwner = LocalLifecycleOwner.current
    // Timestamp of the last ON_PAUSE, so ON_RESUME can debounce the re-probe by
    // how long we were actually away (a quick app-switch skips it).
    val lastPausedAtMs = remember { mutableStateOf(0L) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> lastPausedAtMs.value = System.currentTimeMillis()
                Lifecycle.Event.ON_RESUME -> {
                    // First resume (cold start) forces a probe; otherwise pass
                    // the away-duration so a brief, healthy switch-away skips
                    // the cache-clearing re-probe + Probing badge flash.
                    val awayMs = if (lastPausedAtMs.value == 0L) {
                        Long.MAX_VALUE
                    } else {
                        System.currentTimeMillis() - lastPausedAtMs.value
                    }
                    connectionViewModel.revalidateOnResume(awayMs)
                    voiceViewModel.onAppResumed()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val coldStartAuthState by connectionViewModel.authState.collectAsState()
    val currentPairedSession by connectionViewModel.currentPairedSession.collectAsState()
    val selectedProfile by connectionViewModel.selectedProfile.collectAsState()
    val effectiveSessionProfileName by connectionViewModel.effectiveSessionProfileName.collectAsState()
    val currentChatSessionId by chatViewModel.currentSessionId.collectAsState()
    val effectiveDisplayProfile by connectionViewModel.effectiveDisplayProfile.collectAsState()
    val profileSelectionSettled by connectionViewModel.profileSelectionSettled.collectAsState()
    val agentProfiles by connectionViewModel.agentProfiles.collectAsState()
    val activeConnectionId by connectionViewModel.activeConnectionId.collectAsState()
    val connectionStoreHydrated by
        connectionViewModel.connectionStore.isHydrated.collectAsState()
    val supervisedModeStore = remember(applicationContext) {
        SupervisedModeStore(applicationContext)
    }
    val supervisedPolicyState = produceState<Pair<String?, SupervisedModePolicy>?>(
        initialValue = null,
        key1 = activeConnectionId,
        key2 = supervisedModeStore,
    ) {
        val connectionId = activeConnectionId
        if (connectionId == null) {
            value = null to SupervisedModePolicy()
        } else {
            supervisedModeStore.policyFlow(connectionId).collect { policy ->
                value = connectionId to policy
            }
        }
    }
    val ownedSupervisedPolicyState = supervisedPolicyState.value
        ?.takeIf { (ownerConnectionId, _) -> ownerConnectionId == activeConnectionId }
    // Fail closed across process restoration. activeConnectionId starts as
    // null while ConnectionStore reads DataStore, so null alone cannot prove
    // this is a fresh install with no supervised policy to restore.
    if (!isRelayNavigationHydrated(
            connectionStoreHydrated = connectionStoreHydrated,
            activeConnectionId = activeConnectionId,
            supervisedPolicyHydrated = ownedSupervisedPolicyState != null,
        )
    ) {
        SupervisedStartupLoadingScreen()
        return
    }
    val supervisedPolicy = ownedSupervisedPolicyState?.second ?: SupervisedModePolicy()
    val supervisedPinnedProfile = supervisedPolicy.pinnedProfileName?.let { name ->
        agentProfiles.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
    val supervisedProfileConfirmed = !supervisedPolicy.enabled || (
        profileSelectionSettled &&
            supervisedPinnedProfile != null &&
            selectedProfile?.name.equals(supervisedPinnedProfile.name, ignoreCase = true)
        )
    val chatSupervisedPolicy = if (supervisedPolicy.enabled && !supervisedProfileConfirmed) {
        supervisedPolicy.copy(pinnedProfileName = null)
    } else supervisedPolicy
    var parentAccessUnlocked by remember(activeConnectionId) { mutableStateOf(false) }

    LaunchedEffect(
        activeConnectionId,
        supervisedPolicy,
        agentProfiles,
        selectedProfile,
        profileSelectionSettled,
    ) {
        chatViewModel.updateSupervisedModePolicy(chatSupervisedPolicy)
        connectionViewModel.authManager.updateSupervisedMode(chatSupervisedPolicy)
        if (!supervisedPolicy.enabled) {
            parentAccessUnlocked = false
            return@LaunchedEffect
        }
        val pinned = supervisedPinnedProfile ?: return@LaunchedEffect
        if (!selectedProfile?.name.equals(pinned.name, ignoreCase = true)) {
            connectionViewModel.selectProfile(pinned)
            chatViewModel.activateGatewayProfile(pinned)
        }
    }
    val connections by connectionViewModel.connections.collectAsState()

    val standardVoiceAvailability by connectionViewModel.standardVoiceAvailability.collectAsState()
    val relayVoiceReady by connectionViewModel.relayVoiceReady.collectAsState()

    val profileInspectorHttpClient = remember {
        okhttp3.OkHttpClient.Builder()
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    // === PHASE3-status: sync granular phone-status settings to chat ===
    val appContextEnabled by connectionViewModel.appContextEnabled.collectAsState()
    val appContextBridgeState by connectionViewModel.appContextBridgeState.collectAsState()
    val appContextCurrentApp by connectionViewModel.appContextCurrentApp.collectAsState()
    val appContextBattery by connectionViewModel.appContextBattery.collectAsState()
    val appContextSafetyStatus by connectionViewModel.appContextSafetyStatus.collectAsState()
    LaunchedEffect(
        appContextEnabled,
        appContextBridgeState,
        appContextCurrentApp,
        appContextBattery,
        appContextSafetyStatus,
    ) {
        chatViewModel.appContextSettings = com.hermesandroid.relay.util.AppContextSettings(
            master = appContextEnabled,
            bridgeState = appContextBridgeState,
            currentApp = appContextCurrentApp,
            battery = appContextBattery,
            safetyStatus = appContextSafetyStatus,
        )
    }
    // === END PHASE3-status ===

    // Mirror the "Notify when Hermes finishes" setting into ChatViewModel —
    // same pattern as appContextSettings; the VM reads the plain field at
    // turn-complete time instead of holding a ConnectionViewModel reference.
    val notifyTurnComplete by connectionViewModel.notifyTurnComplete.collectAsState()
    LaunchedEffect(notifyTurnComplete) {
        chatViewModel.notifyOnTurnComplete = notifyTurnComplete
    }

    val streamingEndpoint by connectionViewModel.streamingEndpoint.collectAsState()
    val serverCapabilities by connectionViewModel.serverCapabilities.collectAsState()
    val gatewayAvailability by connectionViewModel.gatewayAvailability.collectAsState()
    val effectiveDashboardUrl by connectionViewModel.effectiveDashboardUrl.collectAsState()
    val gitOwnerKey = activeConnectionId?.takeIf { it.isNotBlank() }?.let { connectionId ->
        effectiveDashboardUrl.takeIf { it.isNotBlank() }?.let { dashboardUrl ->
            "$connectionId\u0000${effectiveSessionProfileName.orEmpty()}\u0000$dashboardUrl"
        }
    }
    LaunchedEffect(
        activeConnectionId,
        effectiveDashboardUrl,
        effectiveSessionProfileName,
        currentChatSessionId,
    ) {
        pluginsViewModel.configure(
            connectionId = activeConnectionId,
            dashboardUrl = effectiveDashboardUrl.takeIf { it.isNotBlank() },
            profileName = effectiveSessionProfileName,
            dashboardFactory = connectionViewModel::dashboardClientForActive,
            sessionId = currentChatSessionId,
        )
    }
    LaunchedEffect(gitOwnerKey) {
        val dashboard = effectiveDashboardUrl
            .takeIf { it.isNotBlank() }
            ?.let { connectionViewModel.dashboardClientForActive(it) }
        gitStateViewModel.configure(dashboard, gitOwnerKey)
    }

    // Mirror the plugin.api.write grant into the Git view model so write
    // mutations are refused client-side until the user grants write access
    // (matches the plug-in's grant gating in PluginsViewModel).
    val pluginsHubState by pluginsViewModel.hubState.collectAsState()
    LaunchedEffect(pluginsHubState, gitOwnerKey) {
        val ready = pluginsHubState as? PluginsHubState.Ready
        val granted = ready
            ?.takeIf { it.ownerKey == gitOwnerKey }
            ?.plugins
            ?.firstOrNull { it.catalog.id == "hermes-relay" }
            ?.preferences
            ?.grants
            ?.contains(PLUGIN_API_WRITE_CAPABILITY) == true
        gitStateViewModel.setWriteGrant(gitOwnerKey, granted)
    }

    val gitReposState by gitStateViewModel.repos.collectAsState()
    val gitDetailState by gitStateViewModel.detail.collectAsState()
    val selectedGitRepoId by gitStateViewModel.selectedRepoId.collectAsState()
    val chatSessions by chatViewModel.sessions.collectAsState()
    val activeChatSession = remember(chatSessions, currentChatSessionId) {
        chatSessions.firstOrNull { it.sessionId == currentChatSessionId }
    }

    // Bind Git to the active coding session when upstream supplies its exact
    // workspace metadata. CWD fallback only matches a path-segment descendant;
    // an ambiguous multi-repo catalog stays unselected until the user chooses.
    LaunchedEffect(gitReposState, activeChatSession, selectedGitRepoId) {
        val repos = (gitReposState as? GitStateUiState.Ready)?.repos.orEmpty()
        val target = selectGitRepoForWorkspace(
            repos = repos,
            selectedRepoId = selectedGitRepoId,
            sessionRepoRoot = activeChatSession?.gitRepoRoot,
            sessionWorkingDirectory = activeChatSession?.workingDirectory,
        )
        if (target != null && target.id != selectedGitRepoId) {
            gitStateViewModel.selectRepo(target.id)
        }
    }

    val gitWorkspaceAvailable = gitReposState is GitStateUiState.Ready
    val gitWorkspaceSummary = remember(
        gitReposState,
        gitDetailState,
        selectedGitRepoId,
    ) {
        val repo = (gitReposState as? GitStateUiState.Ready)
            ?.repos
            ?.firstOrNull { it.id == selectedGitRepoId }
        buildChatGitWorkspaceSummary(repo, gitDetailState)
    }

    // Post-update What's New starts as a non-blocking toast and expands only
    // when requested; manual Settings/About entry points retain the full view.
    val showWhatsNew by connectionViewModel.showWhatsNew.collectAsState()
    var showWhatsNewExpanded by rememberSaveable { mutableStateOf(false) }
    var showWhatsNewHistory by rememberSaveable { mutableStateOf(false) }

    if (showWhatsNew && !showWhatsNewExpanded) {
        WhatsNewToast(
            onDismiss = { connectionViewModel.dismissWhatsNew() },
            onExpand = {
                connectionViewModel.dismissWhatsNew()
                showWhatsNewExpanded = true
            },
        )
    }
    if (showWhatsNewExpanded) {
        WhatsNewDialog(
            onDismiss = { showWhatsNewExpanded = false },
            onViewHistory = {
                showWhatsNewExpanded = false
                showWhatsNewHistory = true
            },
        )
    }
    if (showWhatsNewHistory) {
        Dialog(
            onDismissRequest = { showWhatsNewHistory = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                ChangelogScreen(onClose = { showWhatsNewHistory = false })
            }
        }
    }

    // Mark version as seen on first launch (when there's no previous version)
    val onboardingCompleted by connectionViewModel.onboardingCompleted.collectAsState()
    LaunchedEffect(onboardingCompleted) {
        if (onboardingCompleted) {
            connectionViewModel.markVersionSeen()
        }
    }

    // Observe theme preference
    val themePreference by connectionViewModel.theme.collectAsState()
    val appThemeId by connectionViewModel.appTheme.collectAsState()
    val fontScale by connectionViewModel.fontScale.collectAsState()
    val appFontId by connectionViewModel.appFont.collectAsState()
    val appearanceAccent by connectionViewModel.appearanceAccent.collectAsState()
    val appearanceShape by connectionViewModel.appearanceShape.collectAsState()
    val activeCustomTheme by connectionViewModel.activeCustomTheme.collectAsState()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val parentAccessForCurrentRoute = parentAccessUnlocked &&
        !shouldRelockParentAccess(
            supervisedEnabled = supervisedPolicy.enabled,
            parentAccessUnlocked = parentAccessUnlocked,
            route = currentRoute,
        )
    val resolvedTheme = resolveSupervisedTheme(
        policy = supervisedPolicy,
        parentAccessUnlocked = parentAccessForCurrentRoute,
        globalAppThemeId = appThemeId,
        globalThemePreference = themePreference,
    )
    val supervisedAppearanceLocked = supervisedPolicy.enabled && !parentAccessForCurrentRoute

    // Resolve the active sphere skin (built-in / adaptive / user-loaded) and
    // publish it + the full available set so every MorphingSphere picks it up
    // via LocalSphereSkin without per-call-site threading. Adaptive skins read
    // the brand lazily inside MorphingSphere, so this can sit outside the theme.
    val sphereSkinId by connectionViewModel.sphereSkin.collectAsState()
    val appearanceAssetsRefreshTick by connectionViewModel.avatarsRefreshTick.collectAsState()
    val sphereContext = androidx.compose.ui.platform.LocalContext.current
    val availableSphereSkins by produceState(
        initialValue = SphereRegistry.builtIns,
        key1 = sphereContext,
        key2 = appearanceAssetsRefreshTick,
    ) {
        value = SphereRegistry.builtIns +
            withContext(Dispatchers.IO) { SphereSkinLoader.loadUserSkins(sphereContext) }
    }
    val activeSphereSkin = remember(sphereSkinId, resolvedTheme.appThemeId, availableSphereSkins) {
        SphereRegistry.resolve(
            selectedId = sphereSkinId,
            themeDefaultSkinId = AppThemes.byId(resolvedTheme.appThemeId).defaultSphereSkinId,
            available = availableSphereSkins,
        )
    }

    // Central/background visualization and pet companionship are independent.
    // LocalAgentAvatar owns the central surfaces; LocalFloatingPet owns roaming.
    val floatingPetId by connectionViewModel.floatingPet.collectAsState()
    val backgroundAvatarId by connectionViewModel.backgroundAvatar.collectAsState()
    // Re-scans the pets/ dir whenever the tick bumps (in-app import/delete, or the
    // Appearance screen opening), so newly added/removed pets appear everywhere
    // without an app restart.
    val avatarsRefreshTick = appearanceAssetsRefreshTick
    val availablePets by produceState(
        initialValue = emptyList<AgentAvatar>(),
        key1 = sphereContext,
        key2 = avatarsRefreshTick,
    ) {
        value = withContext(Dispatchers.IO) { PetLoader.loadPets(sphereContext) }
    }
    val hermesPetState by connectionViewModel.hermesPetState.collectAsState()
    val upstreamProfilePet = remember(hermesPetState.active) {
        hermesPetState.active?.toAvatar()
    }
    val activeFloatingPet = remember(floatingPetId, availablePets, upstreamProfilePet) {
        availablePets.firstOrNull { it.id == floatingPetId } ?: upstreamProfilePet
    }
    var floatingPetMenuExpanded by remember(activeFloatingPet?.id) { mutableStateOf(false) }
    val activeBackgroundAvatar = remember(backgroundAvatarId, availablePets) {
        resolveBackgroundAvatar(backgroundAvatarId, availablePets)
    }
    val petSpeed by connectionViewModel.petSpeed.collectAsState()
    val petStabilize by connectionViewModel.petStabilize.collectAsState()
    val petRoamingEnabled by connectionViewModel.petRoamingEnabled.collectAsState()
    val petBehaviorPreferences by connectionViewModel.petBehaviorPreferences.collectAsState()
    val petTerrainOverlayEnabled by FeatureFlags.petTerrainOverlayEnabled(sphereContext)
        .collectAsState(false)
    val petTerrainOverlayScope = rememberCoroutineScope()
    val petPlacement by connectionViewModel.petPlacement.collectAsState()
    val animationEnabled by connectionViewModel.animationEnabled.collectAsState()
    val petCompanionCoordinator = remember { PetCompanionCoordinator() }
    val petSafeAreaRegistry = remember { PetSafeAreaRegistry() }
    // Turn activity belongs to the app, not the Chat destination. Keeping the
    // authoritative flows here lets the companion remain thinking/working when
    // the user opens Settings or Manage during an in-flight response.
    val petMessages by chatViewModel.messages.collectAsState()
    val petIsStreaming by chatViewModel.isStreaming.collectAsState()
    val petError by chatViewModel.error.collectAsState()
    val rawPetState = when {
        petError != null -> SphereState.Error
        petIsStreaming && petMessages.lastOrNull()?.isThinkingStreaming == true -> SphereState.Thinking
        petIsStreaming -> SphereState.Streaming
        else -> SphereState.Idle
    }
    var appPetState by remember(chatViewModel) { mutableStateOf(SphereState.Idle) }
    LaunchedEffect(rawPetState) {
        if (appPetState == SphereState.Thinking && rawPetState == SphereState.Streaming) {
            delay(1_500L)
        }
        appPetState = rawPetState
    }
    val appPetIntensity by animateFloatAsState(
        targetValue = if (petIsStreaming) 0.7f else 0f,
        animationSpec = tween(if (petIsStreaming) 1_000 else 2_000),
        label = "app-pet-intensity",
    )
    val appPetHasActiveTools = petMessages.lastOrNull()?.toolCalls?.any { !it.isComplete } == true
    val appPetToolBurst by animateFloatAsState(
        targetValue = if (appPetHasActiveTools) 1f else 0f,
        animationSpec = tween(if (appPetHasActiveTools) 200 else 1_200),
        label = "app-pet-tool-burst",
    )
    LaunchedEffect(appPetState, appPetIntensity, appPetToolBurst) {
        petCompanionCoordinator.publishRenderState(
            AvatarRenderState(
                state = appPetState,
                intensity = appPetIntensity,
                toolCallBurst = appPetToolBurst,
            ),
        )
    }
    val completedPetVisitUiKey = remember(petMessages) {
        newestPetVisitTargetUiKey(petMessages)
    }
    val petVisitCompletionSettled = remember(petMessages) {
        newestPetAssistantIsSettled(petMessages)
    }
    // Arm on stream start and emit exactly once on its falling edge. Message
    // deltas never create requests; the stable uiKey survives ID reconciliation.
    LaunchedEffect(
        petIsStreaming,
        completedPetVisitUiKey,
        petVisitCompletionSettled,
        petBehaviorPreferences,
        petCompanionCoordinator,
    ) {
        petCompanionCoordinator.observeChatStream(
            isStreaming = petIsStreaming,
            assistantUiKey = completedPetVisitUiKey,
            completionSettled = petVisitCompletionSettled,
            nowElapsedMs = SystemClock.elapsedRealtime(),
            responseVisitDelayMs = petBehaviorPreferences.temperament.pacing.responseVisitDelayMs,
        )
    }
    val backgroundVisualizationEnabled by
        connectionViewModel.backgroundVisualizationEnabled.collectAsState()
    val agentIconPath by connectionViewModel.profileIcon.collectAsState()

    CompositionLocalProvider(
        LocalSphereSkin provides activeSphereSkin,
        LocalAvailableSphereSkins provides availableSphereSkins,
        LocalAgentAvatar provides activeBackgroundAvatar,
        // Compatibility list for the existing Appearance picker during the
        // transition; new companion UI consumes LocalAvailablePets.
        LocalAvailableAvatars provides listOf(SphereAvatar) + availablePets,
        LocalAvailablePets provides availablePets,
        LocalFloatingPet provides activeFloatingPet,
        LocalBackgroundVisualizationEnabled provides backgroundVisualizationEnabled,
        LocalPetPlaybackSpeed provides petSpeed,
        LocalPetStabilize provides petStabilize,
        LocalPetCompanionCoordinator provides petCompanionCoordinator,
        LocalPetSafeAreaRegistry provides petSafeAreaRegistry,
        LocalAgentIconPath provides agentIconPath,
    ) {
        // Dialogs and modal sheets use their own focused window. Treat that
        // focus handoff as an app-wide interaction layer so a dock-only pet on
        // any route cannot remain visible beneath modal chrome.
        PetInteractionLayer(
            owner = "platform-modal-window",
            active = platformModalOwnsPetLayer(
                windowFocused = LocalWindowInfo.current.isWindowFocused,
                petMenuExpanded = floatingPetMenuExpanded,
            ),
        )
    HermesRelayTheme(
        appThemeId = resolvedTheme.appThemeId,
        themePreference = resolvedTheme.themePreference,
        fontScale = fontScale,
        appFontId = appFontId,
        accentHex = appearanceAccent.takeIf { resolvedTheme.useGlobalCustomTheme },
        shapeId = appearanceShape,
        customTheme = activeCustomTheme.takeIf { resolvedTheme.useGlobalCustomTheme },
    ) {
        // Surface a crash report from a previous session, if any. Renders a
        // platform Dialog (own window) so tree position is z-order-agnostic;
        // it just needs to be inside the theme for Material colors.
        CrashReportGate()

        // Wire the proactive "session" surfacing once: a message with
        // surfacing="session" is injected into the active chat conversation.
        // ChatViewModel isn't available where ConnectionViewModel builds the
        // handler, so the session sink is set here at the app root where both
        // ViewModels are in scope.
        val proactiveSummaryResources = LocalContext.current.resources
        LaunchedEffect(connectionViewModel, chatViewModel) {
            connectionViewModel.proactiveMessageHandler.toSession = { msg ->
                val text = buildString {
                    msg.title?.takeIf { it.isNotBlank() }?.let { append(it); append(": ") }
                    append(msg.text)
                }
                chatViewModel.injectProactiveMessage(text)
            }
            connectionViewModel.proactiveMessageHandler.onBacklogDelivered = { count ->
                UiMessageBus.info(
                    proactiveSummaryResources.getQuantityString(
                        R.plurals.proactive_messages_arrived_while_away,
                        count,
                        count,
                    ),
                )
            }
            // Agent Thread reply path: a send from the chat composer while a
            // source=phone Thread is open routes over the relay proactive
            // channel (continues the gateway phone session) instead of a normal
            // chat send; the relay's per-reply ack settles the bubble's status.
            chatViewModel.onProactiveReply = { text, chatId, replyTo, messageId ->
                connectionViewModel.sendProactiveReply(text, chatId, replyTo, messageId)
            }
            connectionViewModel.proactiveMessageHandler.onReplyAck = { clientMsgId, status ->
                chatViewModel.onProactiveReplyAck(clientMsgId, status)
            }
            // Unified Threads: render an inbound agent message inline in the open
            // Thread when it belongs there. Surfacing semantics still decide
            // independently whether a system notification is also required.
            connectionViewModel.proactiveMessageHandler.injectIntoThread = { msg ->
                chatViewModel.injectThreadMessage(msg)
            }
            // Persist + re-apply user-chosen Thread names so a named Thread keeps
            // its name across restart/reconnect (overrides the gateway auto-title).
            chatViewModel.onSaveThreadName = { sessionId, name ->
                connectionViewModel.saveThreadName(sessionId, name)
            }
            launch {
                connectionViewModel.threadNames.collect { names ->
                    chatViewModel.applyPersistedThreadNames(names)
                }
            }
            // Seed reply routing from the relay's /phone/threads (the session→
            // chat_id map the API omits), so any Thread routes replies correctly.
            launch {
                connectionViewModel.phoneThreadChatIds.collect { map ->
                    chatViewModel.seedThreadChatIds(map)
                }
            }
        }

        // startDestination uses the route TEMPLATE so it matches the
        // composable registered below; optional args default to null/false.
        val startDestination = if (onboardingCompleted) Screen.Chat.route else Screen.Onboarding.route

        // Offline Demo / Explore mode. Treated like "onboarding complete" for
        // CHROME purposes (so the demo Chat shows the normal scaffold + status
        // strip and the user can move around) WITHOUT actually completing
        // onboarding — exiting demo returns to the real Connect flow. The demo
        // is entered by navigating to Chat on top of Onboarding, so a process
        // restart cleanly lands back in setup.
        val isDemoMode by connectionViewModel.isDemoMode.collectAsState()

        // The unlock remains useful while moving between parent-only settings,
        // but never follows an enrolled device user back into supervised chat.
        // Cross-layer requests (notifications, services, deep links) use the
        // route-scoped unlock. As soon as Chat is current, the parent grant is
        // ineffective even before the state-clearing effect runs.
        LaunchedEffect(
            navController,
            supervisedPolicy.enabled,
            parentAccessForCurrentRoute,
        ) {
            com.hermesandroid.relay.util.NavRouteRequest.requests.collect { route ->
                if (
                    supervisedPolicy.enabled &&
                    !isSupervisedRouteAllowed(route, parentAccessForCurrentRoute)
                ) return@collect
                navController.navigate(route) {
                    launchSingleTop = true
                }
            }
        }
        LaunchedEffect(
            supervisedPolicy.enabled,
            parentAccessForCurrentRoute,
            currentRoute,
        ) {
            val redirect = shouldRedirectSupervisedRoute(
                supervisedEnabled = supervisedPolicy.enabled,
                parentAccessUnlocked = parentAccessForCurrentRoute,
                currentRoute = currentRoute,
            )
            if (redirect) {
                navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                    popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
        LaunchedEffect(supervisedPolicy.enabled, parentAccessUnlocked, currentRoute) {
            if (shouldRelockParentAccess(supervisedPolicy.enabled, parentAccessUnlocked, currentRoute)) {
                // Route-scoped authority is already false on Chat. Let Navigation
                // finish committing the new destination before clearing the raw
                // parent grant, otherwise the same-frame root recomposition can
                // leave a themed but contentless surface.
                withFrameNanos { }
                withFrameNanos { }
                parentAccessUnlocked = false
            }
        }
        LaunchedEffect(parentAccessUnlocked, supervisedPolicy.parentAccess.timeoutMinutes) {
            if (parentAccessUnlocked) {
                delay(supervisedPolicy.parentAccess.timeoutMinutes * 60_000L)
                parentAccessUnlocked = false
            }
        }
        DisposableEffect(lifecycleOwner, supervisedPolicy.enabled, parentAccessUnlocked) {
            val relockObserver = LifecycleEventObserver { _, event ->
                if (
                    event == Lifecycle.Event.ON_PAUSE &&
                    supervisedPolicy.enabled &&
                    parentAccessUnlocked &&
                    supervisedPolicy.parentAccess.relockOnBackground
                ) {
                    parentAccessUnlocked = false
                }
            }
            lifecycleOwner.lifecycle.addObserver(relockObserver)
            onDispose { lifecycleOwner.lifecycle.removeObserver(relockObserver) }
        }
        val suppressGlobalChrome = shouldSuppressGlobalChrome(
            onboardingCompleted = onboardingCompleted,
            isDemoMode = isDemoMode,
            currentRoute = currentRoute,
        )

        // Safety net: landing on a real connect surface (onboarding or the
        // Connect/Pair wizard) while demo is still active — via the banner's
        // Connect action OR a system-back out of the demo Chat — drops demo so
        // the offline network guards don't block the real connection the user
        // is now setting up.
        LaunchedEffect(currentRoute, isDemoMode) {
            if (isDemoMode &&
                (currentRoute == Screen.Onboarding.route || currentRoute == Screen.Pair.route)
            ) {
                connectionViewModel.exitDemoMode()
            }
        }
        var bridgePrimaryReturnRoute by remember { mutableStateOf<String?>(null) }
        var bridgePrimaryReturnLabel by remember { mutableStateOf<String?>(null) }

        fun rememberBridgeReturn(route: String, label: String) {
            bridgePrimaryReturnRoute = route
            bridgePrimaryReturnLabel = label
        }

        fun clearBridgeReturn() {
            bridgePrimaryReturnRoute = null
            bridgePrimaryReturnLabel = null
        }

        val bridgeReturnLabelResId = when (bridgePrimaryReturnLabel) {
            "Chat" -> R.string.bridge_return_chat_label
            "Manage" -> R.string.bridge_return_manage_label
            else -> R.string.bridge_return_default_label
        }
        val bridgeReturnDisplayLabel = stringResource(bridgeReturnLabelResId)
        val bridgeReturnTitle = bridgePrimaryReturnLabel?.let {
            stringResource(R.string.bridge_return_title_format, bridgeReturnDisplayLabel)
        }
        val bridgeReturnSubtitle = when (bridgePrimaryReturnLabel) {
            "Chat" -> stringResource(R.string.bridge_return_chat_subtitle)
            "Manage" -> stringResource(R.string.bridge_return_manage_subtitle)
            else -> stringResource(R.string.bridge_return_default_subtitle)
        }
        val bridgeReturnAction: (() -> Unit)? = bridgePrimaryReturnRoute?.let { route ->
            {
                clearBridgeReturn()
                // Reliable navigate to the remembered route. saveState +
                // restoreState no-op'd when the route was Chat (the start
                // destination), leaving the user stuck on Bridge.
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        inclusive = false
                    }
                    launchSingleTop = true
                }
            }
        }

        LaunchedEffect(currentRoute) {
            if (
                currentRoute == Screen.Chat.route ||
                currentRoute == Screen.Manage.route ||
                currentRoute == Screen.Settings.route ||
                currentRoute == Screen.Onboarding.route
            ) {
                clearBridgeReturn()
            }
        }

        val density = LocalDensity.current
        val imeBottom = WindowInsets.ime.getBottom(density)
        val isKeyboardVisible = imeBottom > 0

        // Voice mode is a full-screen modality — while it's active we hide the
        // bottom navigation bar so the voice overlay can own the entire screen
        // without the Chat/Terminal/Bridge/Settings tabs peeking through below.
        val voiceUiState by voiceViewModel.uiState.collectAsState()
        val wakeActivation by
            com.hermesandroid.relay.wake.WakeWordActivationCoordinator.pending.collectAsState()
        val appIsForeground by
            com.hermesandroid.relay.util.AppForegroundTracker.isForeground.collectAsState()

        // The process runtime owns Android-assistant activation. RelayApp only
        // brings the already-running turn into its full Voice presentation.
        // Foreground-service wake detections retain their existing visible-app
        // gate and UI-owned activation flow.
        LaunchedEffect(wakeActivation?.id, appIsForeground) {
            val activation = wakeActivation ?: return@LaunchedEffect
            if (activation.source ==
                com.hermesandroid.relay.wake.WakeWordActivationSource.SystemAssistant
            ) {
                navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                    launchSingleTop = true
                }
                com.hermesandroid.relay.wake.WakeWordActivationCoordinator.consume(activation.id)
                return@LaunchedEffect
            }
            if (!appIsForeground) return@LaunchedEffect

            com.hermesandroid.relay.wake.WakeWordForegroundService.prepareForVoice()
            if (activation.startNewSession) {
                chatViewModel.createNewChat()
            }
            navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                launchSingleTop = true
            }
            voiceViewModel.enterVoiceMode()
            // Let the existing RelayApp initialization and Chat destination
            // settle before opening VoiceRecorder on a cold task recreation.
            delay(120L)
            voiceViewModel.startListening()
            com.hermesandroid.relay.wake.WakeWordActivationCoordinator.consume(activation.id)
        }

        LaunchedEffect(voiceUiState.voiceMode) {
            com.hermesandroid.relay.wake.WakeWordForegroundService.setVoiceSessionActive(
                voiceUiState.voiceMode
            )
        }
        val postResumeQuiet by connectionViewModel.postResumeQuiet.collectAsState()
        val apiHealth by connectionViewModel.apiServerHealth.collectAsState()
        val activeConnection by connectionViewModel.activeConnection.collectAsState()
        val activeEndpoint by connectionViewModel.activeEndpoint.collectAsState()
        val connectionSecurity by connectionViewModel.connectionSecurity.collectAsState()
        val serverModelName by chatViewModel.serverModelName.collectAsState()
        val gatewayCurrentModel by chatViewModel.gatewayCurrentModel.collectAsState()
        val appReady by connectionViewModel.isReady.collectAsState()
        val initialChatSettled by chatViewModel.initialChatSettled.collectAsState()
        val shareConnectionId by rememberUpdatedState(
            activeConnection?.id?.takeIf(String::isNotBlank) ?: "offline"
        )
        val shareProfileId by rememberUpdatedState(
            selectedProfile?.name?.takeIf(String::isNotBlank)
                ?: com.hermesandroid.relay.data.ChatComposerDraftKey.DEFAULT_PROFILE_ID
        )
        // Android sharesheet handoff: wait until the configured chat context is
        // settled, then create a fresh draft. The request remains identity-fenced
        // until ChatScreen restores that exact draft and ingests its text/files.
        LaunchedEffect(navController, onboardingCompleted, initialChatSettled) {
            if (!onboardingCompleted || !initialChatSettled) return@LaunchedEffect
            com.hermesandroid.relay.util.SharedContentRequest.pending.collect { request ->
                request ?: return@collect
                val targetConnectionId = shareConnectionId
                val targetProfileId = shareProfileId
                if (!request.ready && !request.preparing && !request.failed) {
                    com.hermesandroid.relay.util.SharedContentRequest.markPreparing(request.id)
                    val opened = chatViewModel.openSharedContentDraft(
                        onReady = { sessionId ->
                            com.hermesandroid.relay.util.SharedContentRequest.markReady(
                                id = request.id,
                                targetConnectionId = targetConnectionId,
                                targetProfileId = targetProfileId,
                                targetSessionId = sessionId,
                            )
                        },
                        onFailure = {
                            com.hermesandroid.relay.util.SharedContentRequest.markFailed(request.id)
                        },
                    )
                    if (opened) {
                        navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                            launchSingleTop = true
                        }
                    } else {
                        com.hermesandroid.relay.util.SharedContentRequest.markFailed(request.id)
                    }
                }
            }
        }
        // The SAME readiness signal ChatScreen renders its "Connect Standard
        // Hermes" CTA from (chat client exists + reachable verdict). The gate
        // must release on this — releasing on the resolver's earlier
        // evidence alone left a window where the reveal showed the CTA for
        // the few hundred ms until the client-based health verdict landed.
        val chatReady by connectionViewModel.chatReady.collectAsState()
        var startupGateMinElapsed by remember { mutableStateOf(false) }
        var startupGateTimedOut by remember { mutableStateOf(false) }
        var startupGateReleased by remember { mutableStateOf(false) }
        var startupUnreachableSettled by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            delay(650L)
            startupGateMinElapsed = true
        }
        // Backstop only. The old 5.5s value force-hid the sphere
        // (showStartupSphere checked !timedOut) while startup was genuinely
        // still working, dumping users into half-hydrated UI — a "connect"
        // CTA they were never meant to see, then the connected state, then
        // the conversation, one reveal at a time. Now the timeout RELEASES
        // the gate like any other condition and the sphere narrates
        // progress, so a longer ceiling is watchable instead of broken.
        LaunchedEffect(Unit) {
            delay(12_000L)
            startupGateTimedOut = true
        }

        val hasStartupConnection = hasConfiguredStartupChat(activeConnection)
        val appChatRuntimeStatus = resolveAppChatRuntimeStatus(
            connection = activeConnection,
            gatewayAvailability = gatewayAvailability,
            apiHealth = apiHealth,
        )
        // A Dashboard/Gateway-only connection is a complete standard Hermes
        // connection. Startup readiness follows the same transport-neutral
        // priority as the footer instead of waiting for an optional API probe.
        val startupChatUp = appChatRuntimeStatus is ChatRuntimeStatus.Connected

        // An Unreachable verdict only counts after it SURVIVES a settle
        // window: the first health probe often runs against the persisted
        // (e.g. LAN) URL moments before the route resolver lands on
        // Tailscale and the client is rebuilt. Releasing the gate on that
        // first verdict was what flashed the disconnected chat UI at users
        // who were connected-just-waiting. The keyed effect restarts on
        // every health flip, cancelling a pending settle.
        LaunchedEffect(appChatRuntimeStatus, activeEndpoint, startupGateReleased) {
            if (startupGateReleased) return@LaunchedEffect
            if (shouldSettleStartupUnreachable(
                    hasConfiguredChat = hasStartupConnection,
                    runtimeStatus = appChatRuntimeStatus,
                )
            ) {
                delay(3_000L)
                startupUnreachableSettled = true
            } else {
                startupUnreachableSettled = false
            }
        }
        val startupUnreachableConfirmed = startupUnreachableSettled &&
            shouldSettleStartupUnreachable(
                hasConfiguredChat = hasStartupConnection,
                runtimeStatus = appChatRuntimeStatus,
            )

        // ---- Startup narration: real states the checklist verifies ----
        val startupEndpoint = activeEndpoint
        val startupCheckTargets = if (!hasStartupConnection) {
            emptyList()
        } else {
            listOf(
                if (appReady) {
                    StartupCheck(StartupCheckState.Done, "state restored")
                } else {
                    StartupCheck(StartupCheckState.Active, "restoring state")
                },
                when {
                    startupEndpoint != null -> StartupCheck(
                        StartupCheckState.Done,
                        "route · ${startupEndpoint.displayLabel()}",
                    )
                    startupChatUp ->
                        StartupCheck(StartupCheckState.Done, "route · direct")
                    appReady ->
                        StartupCheck(StartupCheckState.Active, "resolving route")
                    else -> StartupCheck(StartupCheckState.Pending, "route")
                },
                when {
                    startupChatUp ->
                        StartupCheck(StartupCheckState.Done, "hermes online")
                    startupUnreachableConfirmed ->
                        StartupCheck(StartupCheckState.Failed, "hermes unreachable")
                    appChatRuntimeStatus is ChatRuntimeStatus.Unavailable && startupEndpoint != null ->
                        StartupCheck(StartupCheckState.Active, "gateway retrying")
                    appReady ->
                        StartupCheck(StartupCheckState.Active, "contacting hermes")
                    else -> StartupCheck(StartupCheckState.Pending, "hermes")
                },
                // Done is keyed on chatReady — the signal ChatScreen itself
                // renders from — so this row can never tick while the chat
                // surface would still show its connect CTA.
                when {
                    chatReady && initialChatSettled ->
                        StartupCheck(StartupCheckState.Done, "conversation ready")
                    startupChatUp ->
                        StartupCheck(StartupCheckState.Active, "loading conversation")
                    else -> StartupCheck(StartupCheckState.Pending, "conversation")
                },
            )
        }

        // ---- Narration choreography ----
        // With the key-less fast path every readiness signal can be
        // satisfied before the sphere finishes fading in — an all-✓-at-once
        // reveal reads as "nothing was actually checked". So rows resolve
        // strictly top-to-bottom and each one holds a spinner beat before
        // its verdict lands, even when the underlying state was already
        // true. The gate's happy path waits for the narration to finish;
        // error and timeout releases don't.
        var startupNarrationStage by remember { mutableStateOf(0) }
        LaunchedEffect(startupCheckTargets, startupNarrationStage, startupGateReleased) {
            if (startupGateReleased) return@LaunchedEffect
            if (startupNarrationStage >= startupCheckTargets.size) return@LaunchedEffect
            val target = startupCheckTargets[startupNarrationStage].state
            if (target == StartupCheckState.Done || target == StartupCheckState.Failed) {
                delay(350L)
                startupNarrationStage += 1
            }
        }
        val startupNarrationComplete =
            startupNarrationStage >= startupCheckTargets.size

        val startupConnectionResolved = appReady && (
            !hasStartupConnection ||
                // Happy path: the chat surface's OWN readiness signal is
                // true (client built + reachable verdict — what its connect
                // CTA renders from), the last conversation has been restored
                // (or there was none), and the checklist has visibly
                // finished ticking. Anything weaker (e.g. the resolver's
                // earlier health evidence) reveals a chat screen that still
                // shows "Connect Vanilla Hermes" for the few hundred ms
                // until the client-based verdict catches up.
                (chatReady && initialChatSettled && startupNarrationComplete) ||
                // Error path: a settled unreachable reveals the normal UI,
                // which owns offline presentation (status pill, retry).
                startupUnreachableConfirmed ||
                startupGateTimedOut
            )
        LaunchedEffect(
            onboardingCompleted,
            startupGateMinElapsed,
            startupConnectionResolved,
        ) {
            if (
                onboardingCompleted &&
                !startupGateReleased &&
                startupGateMinElapsed &&
                startupConnectionResolved
            ) {
                // When the 12s backstop (not readiness, not a settled error)
                // is what opened the gate, leave a diagnostic naming the
                // conditions still unmet — the demo-video session measured
                // 6–28s launch variance against the same LAN server and had
                // no way to see why from the device.
                val happyPathReady =
                    chatReady && initialChatSettled && startupNarrationComplete
                if (
                    hasStartupConnection &&
                    !happyPathReady &&
                    !startupUnreachableConfirmed &&
                    startupGateTimedOut
                ) {
                    DiagnosticsLog.record(
                        category = DiagnosticCategory.Api,
                        severity = DiagnosticSeverity.Warning,
                        title = "Startup gate released by timeout",
                        detail = "chatReady=$chatReady " +
                            "historySettled=$initialChatSettled " +
                            "narration=$startupNarrationStage/${startupCheckTargets.size} " +
                            "health=$apiHealth " +
                            "route=${activeEndpoint?.role ?: "unresolved"}",
                    )
                }
                startupGateReleased = true
            }
        }
        val showStartupSphere =
            !suppressGlobalChrome &&
                !startupGateReleased &&
                !voiceUiState.voiceMode &&
                // Demo mode skips the startup connect-narration sphere entirely
                // — there's no server to contact, so the canned chat shows
                // immediately.
                !isDemoMode

        // Hydrate the Manage payload cache from its plain-JSON disk mirror
        // as early as possible — independent of connectivity or auth, so a
        // cold process renders last-seen dashboard data instantly. Entries
        // keep their original fetch timestamps, making them stale by
        // definition: the pre-warm below and the screen's
        // stale-while-revalidate path refresh them quietly.
        val hydrateContext =
            androidx.compose.ui.platform.LocalContext.current.applicationContext
        LaunchedEffect(Unit) {
            com.hermesandroid.relay.ui.screens.hydrateDashboardManageCache(
                hydrateContext.cacheDir,
            )
        }

        // Pre-warm the Manage tab's payload cache when the persisted
        // dashboard snapshot says this connection was reachable and signed
        // in (or auth-free) — a cold app start then lands on populated
        // Manage data instead of skeletons. Keyed on the effective URL so a
        // LAN↔Tailscale handoff re-warms the new host's cache; the delay
        // debounces resolver flaps during startup (each key change cancels
        // the previous run). The pre-warm fills cold keys and refreshes
        // stale (disk-hydrated) ones, then mirrors results back to disk.
        val effectiveDashboardUrl by connectionViewModel.effectiveDashboardUrl.collectAsState()
        val effectiveManageProfile by connectionViewModel.effectiveSessionProfileName.collectAsState()
        LaunchedEffect(activeConnection?.id, effectiveDashboardUrl, effectiveManageProfile) {
            val connection = activeConnection ?: return@LaunchedEffect
            if (effectiveDashboardUrl.isBlank()) return@LaunchedEffect
            val snapshot = connection.dashboardLastStatus ?: return@LaunchedEffect
            val dashboardUsable = snapshot.reachable &&
                (snapshot.authRequired == false || snapshot.authenticated == true)
            if (!dashboardUsable) return@LaunchedEffect
            delay(1_500L)
            // The VM's cached per-connection store — the prewarm must NOT
            // construct its own (each instance lazily pays a multi-second
            // Keystore keyset build under a process-global Tink lock).
            prewarmDashboardManage(
                clientFactory = {
                    connectionViewModel.dashboardClientForActive(effectiveDashboardUrl)
                },
                connectionId = connection.id,
                dashboardUrl = effectiveDashboardUrl,
                effectiveProfileName = effectiveManageProfile,
                cacheDir = hydrateContext.cacheDir,
                context = hydrateContext,
            )
        }

        // Single snackbar host for the whole app — exposed via LocalSnackbarHost
        // so voice/chat/settings screens can call showHumanError from their
        // error-collector LaunchedEffects without threading state downwards.
        val snackbarHostState = remember { SnackbarHostState() }
        val profilesUpdatedLabel = stringResource(R.string.relay_app_profiles_updated)
        val reconnectingRelayLabel = stringResource(R.string.relay_app_reconnecting)
        val renameFailedLabel = stringResource(R.string.relay_app_rename_failed)
        val revokeOnlyActiveLabel = stringResource(R.string.relay_app_revoke_only_active)

        // Relay-pushed `profiles.updated` announcements. AuthManager
        // filters out idempotent pushes (same names + same count), so
        // this only fires when the profile list actually changed.
        LaunchedEffect(connectionViewModel) {
            connectionViewModel.profilesUpdatedEvents.collect {
                UiMessageBus.success(profilesUpdatedLabel)
            }
        }

        // === v0.4.1 polish: global unattended-access banner ===
        // Rendered at the top of the scaffold on every tab when BOTH the
        // master toggle is ON and unattended access is ON. The per-screen
        // UnattendedAccessRow inside Bridge already surfaces this, but the
        // user's typical workflow after enabling it is to leave the Bridge
        // tab — we don't want them to forget about a screen-waking opt-in
        // just because they're reading Chat history. Kept as a thin 28dp
        // amber strip so its footprint doesn't eat scroll space.
        //
        // State is read directly from the two DataStore repos instead of
        // going through BridgeViewModel. The VM is Bridge-tab-scoped; the
        // banner lives at app-root scope. Reading the repos here avoids
        // instantiating the entire BridgeViewModel (with its many side-
        // effectful init blocks) just to peek at two StateFlows.
        // Key the remembers off applicationContext (process-stable) instead
        // of LocalContext.current (changes on rotation, dark-mode swap,
        // locale change). Keying off a transient context would re-construct
        // the repos — and their DataStore handles — on every config change.
        val bridgeAppCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
        val bridgePrefsRepo = remember(bridgeAppCtx) { BridgePreferencesRepository(bridgeAppCtx) }
        val safetyPrefsRepo = remember(bridgeAppCtx) { BridgeSafetyPreferencesRepository(bridgeAppCtx) }
        // Stabilize the mapped flows in `remember` — invoking `.map` directly
        // inside composition trips the `FlowOperatorInvokedInComposition` lint
        // rule because a fresh Flow instance would be created on every
        // recomposition, defeating collectAsState's state-preservation.
        val masterEnabledFlow = remember(bridgePrefsRepo) {
            bridgePrefsRepo.settings.map { it.masterEnabled }
        }
        val unattendedEnabledFlow = remember(safetyPrefsRepo) {
            safetyPrefsRepo.settings.map { it.unattendedAccessEnabled }
        }
        val masterEnabled by masterEnabledFlow.collectAsState(initial = false)
        val unattendedEnabled by unattendedEnabledFlow.collectAsState(initial = false)
        val activeBridgePolicy by (connectionViewModel.bridgeSafety?.activeCapabilityPolicy
            ?: remember { kotlinx.coroutines.flow.MutableStateFlow(
                com.hermesandroid.relay.bridge.BridgeCapabilityPolicy(),
            ) })
            .collectAsState()
        val timedScreenControlActive = activeBridgePolicy.allows(
            com.hermesandroid.relay.bridge.BridgeCapability.SCREEN_CONTROL,
            System.currentTimeMillis(),
        )
        // Sideload-only: googlePlay has no wake lock and the unattended
        // flag never gets written there — gating here is defence in depth
        // and makes the check cheap via R8 in release builds.
        val showUnattendedBanner = BuildFlavor.isSideload &&
            masterEnabled &&
            unattendedEnabled &&
            timedScreenControlActive &&
            !suppressGlobalChrome &&
            !showStartupSphere &&
            !voiceUiState.voiceMode
        val showCandidateBanner = CandidateBuild.isCandidate &&
            !voiceUiState.voiceMode &&
            !showStartupSphere
        // Persistent Demo-mode strip — visible on every demo surface so the
        // user always knows the chat is sample data with no live server, and
        // can exit into the real Connect flow with one tap.
        val showDemoBanner = isDemoMode && !voiceUiState.voiceMode
        val hostResourcePressure by connectionViewModel.hostResourcePressure.collectAsState()
        val showHostResourcePressure = hostResourcePressure.needsAttention &&
            !isDemoMode && !voiceUiState.voiceMode && !showStartupSphere
        val hostResourcePressureText = buildList {
            if (hostResourcePressure.lastBootSuspectedOom) {
                add(stringResource(R.string.host_resource_recent_oom))
            }
            when (hostResourcePressure.memoryPressure) {
                "critical" -> add(stringResource(R.string.host_resource_memory_critical))
                "elevated" -> add(stringResource(R.string.host_resource_memory_elevated))
            }
            when (hostResourcePressure.diskPressure) {
                "critical" -> add(stringResource(R.string.host_resource_disk_critical))
                "elevated" -> add(stringResource(R.string.host_resource_disk_elevated))
            }
        }.distinct().joinToString(" ")
        // Transient info/status banner (UiMessageBus) — thin, takes its own
        // space, auto-dismisses. Folded into the inset accounting below so a
        // child TopAppBar doesn't double-pad when this banner owns the top edge.
        val activeMessageCount by UiMessageBus.activeCount.collectAsState()
        val showMessageBanner = activeMessageCount > 0
        // Update availability (unified): googlePlay = Play In-App Update FLEXIBLE,
        // sideload = GitHub releases. The handle filters dismissed versions +
        // throttles checks internally, exposing a surfaceable status for the
        // floating overlay (mirrors the connection toast treatment).
        val updateHandle = rememberUpdateAvailability()
        val availableUpdateStatus by updateHandle.visibleStatus

        // Connection status has no top-of-screen surface. The two connections are
        // surfaced where they matter, never covering or shifting the top:
        //   • Chat/agent (gateway/API) → the chat header SUBTITLE swaps the model
        //     line for "Connecting…"/"Disconnected" when the chat path is down
        //     (WhatsApp-style; see ChatScreen). That's the "can I talk to the
        //     agent?" signal.
        //   • Relay socket (bridge/terminal/relay-voice) → the bottom
        //     RelayStatusStrip's "Reconnecting…" cue only. It never blocks chat,
        //     so it stays ambient. (`connectionReconnecting` below.)
        // A routine in-progress reconnect surfaces only in the bottom strip.
        // Computed off the raw status (not the dismiss-gated `toast`) because the
        // strip cue isn't dismissible — it just mirrors live connection state.
        // Gated by postResumeQuiet so a benign background→foreground re-handshake
        // stays fully silent (the health "Connecting" cue used to flash here for a
        // few seconds and then clear with no "Connected" toast).
        val connectionReconnecting =
            appChatRuntimeStatus is ChatRuntimeStatus.Connecting && !postResumeQuiet &&
                !suppressGlobalChrome && !showStartupSphere && !voiceUiState.voiceMode
        // === END v0.4.1 polish ===

        // Multi-connection switcher has moved into the AgentInfoSheet's
        // Connection section (see ConnectionInfoSheet.kt) — the top-bar
        // ConnectionChip row that used to live here was duplicating that
        // surface and eating vertical space. The `connectionSheetVisible`
        // state and the ConnectionSwitcherSheet declaration further down
        // are kept for any callers that still need the modal switcher
        // (e.g. programmatic routes), but nothing in the default UI opens
        // them anymore.
        //
        // Kept as a named const so the `if (showUnattendedBanner ||
        // connectionChipVisible)` window-inset conditional below still
        // compiles without a deeper rewrite. Collapses to false now that
        // the chip is gone; when the unattended banner is absent too, the
        // Scaffold goes back to default TopAppBar status-bar padding.
        val connectionChipVisible = false

        // --- Offline Demo mode navigation ---------------------------------
        // Enter: load the canned transcript + bind it to the chat VM (no
        // network), then land on Chat WITHOUT completing onboarding. Binding
        // synchronously before navigating means ChatScreen's first composition
        // already sees the demo messages. Exit: clear demo + return to the
        // real Connect flow (onboarding for a fresh install, the Pair wizard
        // for an already-set-up app).
        val enterDemo: () -> Unit = {
            connectionViewModel.enterDemoMode()
            chatViewModel.bindDemoHandler(connectionViewModel.chatHandler)
            navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                launchSingleTop = true
            }
        }
        val exitDemoToConnect: () -> Unit = {
            connectionViewModel.exitDemoMode()
            if (onboardingCompleted) {
                navController.navigate(Screen.Pair.route()) { launchSingleTop = true }
            } else {
                navController.navigate(Screen.Onboarding.route) {
                    popUpTo(Screen.Chat.route) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        // The banner takes its own vertical space above the Scaffold so
        // no screen's content is covered by it (unlike floating overlays
        // that would need per-screen padding compensation). Use
        // AnimatedVisibility to fade in/out on state transitions.
        AnimatedVisibility(
            visible = showUnattendedBanner,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            UnattendedGlobalBanner(
                onTap = {
                    navController.navigate(Screen.Bridge.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = showDemoBanner,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            DemoModeBanner(onConnect = exitDemoToConnect)
        }

        AnimatedVisibility(
            visible = showHostResourcePressure,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            HostResourcePressureBanner(
                text = hostResourcePressureText,
                critical = hostResourcePressure.critical,
                includeStatusBarPadding = !showUnattendedBanner && !showDemoBanner,
            )
        }

        // Connection status intentionally has NO top-of-screen surface (no
        // banner, no strip, no float). Chat/agent status rides the chat header
        // subtitle; the relay socket rides the bottom RelayStatusStrip cue. See
        // the note at the top of this composable.

        // Transient info/status banner. Sits below the persistent banners and
        // owns the status-bar inset only when no banner is above it (otherwise
        // that banner already padded the top — avoid double padding).
        MessageBannerHost(
            includeStatusBarPadding =
                !showUnattendedBanner && !showDemoBanner && !showHostResourcePressure,
        )

        // The update banner AND the connection-status indicator now render as
        // floating overlay TOASTS in the Box below (see the top-overlay Column
        // after the Scaffold), so they slide down OVER the content instead of
        // taking layout space — no UI resize/cut on update / handoff / reconnect.

        // (The app-wide ConnectionChip row that used to live here has been
        // removed. Multi-connection switching is now reachable from the
        // AgentInfoSheet's Connection section — see ConnectionInfoSheet.kt's
        // "Multi-connection switcher" block. Keeps the top chrome tidy and
        // puts the control next to the related Profile + Personality
        // radios.)
        Scaffold(
            // weight(1f) instead of fillMaxSize(): Column arranges children
            // top-down, so a fillMaxSize child would try to claim the full
            // parent height and overflow past the banner. weight(1f) takes
            // exactly the remaining main-axis space after the banner (which
            // is 0 when the banner is hidden).
            //
            // consumeWindowInsets is conditional on banner visibility: the
            // banner pads itself for WindowInsets.statusBars, and without
            // this consume, child TopAppBars (e.g. ChatScreen's) also pad
            // for status bars — yielding ~24dp of double-padding between
            // the banner and the first row of screen content. When the
            // banner is hidden we want the default behavior (TopAppBar
            // self-pads below the status bar).
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    // Any banner / chip sitting above the Scaffold that
                    // pads itself for the status bar means child TopAppBars
                    // would otherwise double-pad and render too far down.
                    // Consume the inset here so the Scaffold tree treats
                    // the top edge as already handled.
                    // The connection-status toast is now a floating overlay and
                    // doesn't occupy space above the Scaffold, so it no longer
                    // participates in the top-inset accounting.
                    if (showUnattendedBanner || showDemoBanner || showHostResourcePressure ||
                        connectionChipVisible ||
                        showMessageBanner
                    ) {
                        Modifier.consumeWindowInsets(WindowInsets.statusBars)
                    } else {
                        Modifier
                    }
                ),
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (
                    !suppressGlobalChrome &&
                    !isKeyboardVisible &&
                    !showStartupSphere &&
                    (!supervisedPolicy.enabled ||
                        supervisedPolicy.visibility.resolved().showTechnicalRoute) &&
                    shouldShowConnectionFooter(voiceUiState.voiceMode, voicePresentationMode)
                ) {
                    val footerRoute = resolveFooterRouteCandidate(
                        runtimeStatus = appChatRuntimeStatus,
                        activeEndpoint = activeEndpoint,
                        connection = activeConnection,
                        effectiveDashboardUrl = effectiveDashboardUrl,
                    )
                    val routeLabel = resolveFooterRouteLabel(
                        runtimeStatus = appChatRuntimeStatus,
                        route = footerRoute,
                        fallbackLabel = activeConnection?.label
                            ?: stringResource(R.string.status_no_route),
                    )
                    val transportStatus = resolveChatTransportStatus(
                        streamingEndpoint = streamingEndpoint,
                        gatewayAvailability = gatewayAvailability,
                        serverCapabilities = serverCapabilities,
                    )
                    val transportRouteLabel = if (
                        transportStatus.tier == ChatTransportTier.Offline
                    ) "" else routeLabel
                    val profileLabel = AgentDisplay.profileDisplayName(effectiveDisplayProfile)
                        ?: stringResource(R.string.status_profile_default)
                    val displayProfile = effectiveDisplayProfile
                    val modelLabel = AgentDisplay.displayModelName(gatewayCurrentModel)
                        ?: AgentDisplay.displayModelName(displayProfile?.model)
                        ?: AgentDisplay.displayModelName(serverModelName)
                        ?: stringResource(R.string.status_model_pending)
                    val footerModelLabel = compactFooterModelLabel(modelLabel)
                    val openConnections = {
                        navController.navigate(Screen.ConnectionsSettings.route) {
                            launchSingleTop = true
                        }
                    }
                    RelayStatusStrip(
                        leadingBadge = {
                            ChatTransportStatusBadge(
                                status = transportStatus,
                                onClick = openConnections,
                            )
                        },
                        routeLabel = transportRouteLabel,
                        trailing = "$footerModelLabel / $profileLabel",
                        // Tap the persistent status/route readout to open
                        // Connections — preserves the affordance the dropped
                        // header endpoint chip used to provide.
                        onClick = openConnections,
                        securityGlyph = if (transportStatus.tier != ChatTransportTier.Offline) {
                            { ConnectionSecurityGlyph(connectionSecurity) }
                        } else {
                            null
                        },
                        // Routine in-progress reconnect surfaces here (amber cue)
                        // instead of a take-space banner or a floating toast.
                        reconnecting = connectionReconnecting,
                        modifier = Modifier.petPerchSurface(
                            key = APP_STATUS_PET_WALK_REGION,
                            routes = APP_STATUS_PET_ROUTES,
                        ),
                    )
                }
            }
        ) { innerPadding ->
            CompositionLocalProvider(LocalSnackbarHost provides snackbarHostState) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                val routeContentAllowed = isSupervisedRouteContentAllowed(
                    supervisedEnabled = supervisedPolicy.enabled,
                    parentAccessUnlocked = parentAccessForCurrentRoute,
                    currentRoute = currentRoute,
                )
                Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.fillMaxSize(),
                ) {
                composable(Screen.Onboarding.route) {
                    // The wizard inside OnboardingScreen now owns credential
                    // application via ConnectionViewModel.applyPairingPayload,
                    // so the callback collapses to "mark complete + navigate
                    // to chat". The legacy 4-arg signature was discarding the
                    // relay block entirely.
                    //
                    // CRITICAL: pass the Activity-scoped connectionViewModel
                    // explicitly instead of letting OnboardingScreen fetch
                    // its own via `viewModel()`. A bare `viewModel()` call
                    // inside a `composable(...)` block binds to the
                    // NavBackStackEntry's store, so the onboarding VM gets
                    // destroyed by `popUpTo(Onboarding) { inclusive = true }`
                    // on navigation to Chat — taking the freshly-minted
                    // session token with it. See the full writeup on the
                    // OnboardingScreen function definition.
                    OnboardingScreen(
                        connectionViewModel = connectionViewModel,
                        onComplete = {
                            connectionViewModel.completeOnboarding()
                            // Concrete bare-"chat" URI — the Screen.Chat.route
                            // field is the route TEMPLATE (contains
                            // `{openAgentSheet}`) and must not be navigated
                            // to directly; build the URI via Screen.Chat.route(...).
                            navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                            }
                        },
                        onManageSignIn = {
                            navController.navigate(
                                Screen.DashboardSignIn.route(Screen.DashboardSignIn.SOURCE_ONBOARDING),
                            )
                        },
                        onOpenPermissions = {
                            navController.navigate(Screen.PermissionsSettings.route)
                        },
                        onTryDemo = enterDemo,
                    )
                }
                composable(
                    route = Screen.Chat.route,
                    arguments = listOf(
                        navArgument(Screen.Chat.ARG_OPEN_AGENT_SHEET) {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                        navArgument(Screen.Chat.ARG_SESSION_ID) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(Screen.Chat.ARG_PROFILE) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(Screen.Chat.ARG_PROACTIVE_CHAT_ID) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { backStackEntry ->
                    // Responsive bubble width based on screen width. The "Blend"
                    // chat look favors wider bubbles: on compact phones the cap is
                    // raised so long turns fill most of the row instead of
                    // wrapping early in a narrow column. Assistant identity
                    // stays in the group header and does not reduce this cap.
                    val configuration = LocalConfiguration.current
                    val screenWidthDp = configuration.screenWidthDp.dp
                    val maxBubbleWidth = when {
                        screenWidthDp >= 840.dp -> 640.dp  // Expanded (tablet)
                        screenWidthDp >= 600.dp -> 520.dp  // Medium (landscape / small tablet)
                        else -> 340.dp                      // Compact (phone portrait)
                    }

                    // Consume-once semantics: ChatScreen only treats the
                    // flag as "open the sheet on entry" while it's still
                    // `true` in the back-stack entry's arguments. After
                    // firing, ChatScreen writes `false` back into the same
                    // arguments bundle so recompositions (tab switches,
                    // config changes, process resume) don't re-open the
                    // sheet.
                    val openAgentSheetArg = backStackEntry.arguments
                        ?.getBoolean(Screen.Chat.ARG_OPEN_AGENT_SHEET, false) == true
                    val rawRequestedSessionId = backStackEntry.arguments
                        ?.getString(Screen.Chat.ARG_SESSION_ID)
                        ?.takeIf { it.isNotBlank() }
                    val rawRequestedProfileRoute = backStackEntry.arguments
                        ?.getString(Screen.Chat.ARG_PROFILE)
                        ?.takeIf { it.isNotBlank() }
                    val rawRequestedProactiveChatId = backStackEntry.arguments
                        ?.getString(Screen.Chat.ARG_PROACTIVE_CHAT_ID)
                        ?.takeIf { it.isNotBlank() }
                    // Nav/deep-link arguments are not ownership evidence. The
                    // supervised drawer uses profile-scoped session rows
                    // directly; external args stay discarded until an
                    // owner-aware source can explicitly prove the binding.
                    val sanitizedRouteArgs = sanitizeSupervisedChatRouteArgs(
                        policy = supervisedPolicy,
                        args = SupervisedChatRouteArgs(
                            sessionId = rawRequestedSessionId,
                            profile = rawRequestedProfileRoute,
                            proactiveChatId = rawRequestedProactiveChatId,
                        ),
                        pinnedProfileOwnershipProven = false,
                    )
                    val requestedSessionId = sanitizedRouteArgs.sessionId
                    val requestedProfileRoute = sanitizedRouteArgs.profile
                    val requestedProactiveChatId = sanitizedRouteArgs.proactiveChatId
                    LaunchedEffect(
                        supervisedPolicy.enabled,
                        rawRequestedSessionId,
                        rawRequestedProfileRoute,
                        rawRequestedProactiveChatId,
                    ) {
                        if (supervisedPolicy.enabled) {
                            backStackEntry.arguments?.putString(Screen.Chat.ARG_SESSION_ID, null)
                            backStackEntry.arguments?.putString(Screen.Chat.ARG_PROFILE, null)
                            backStackEntry.arguments?.putString(Screen.Chat.ARG_PROACTIVE_CHAT_ID, null)
                        }
                    }
                    val proactiveInboxEntries by connectionViewModel.inboxMessages.collectAsState()
                    val phoneThreadChatIds by connectionViewModel.phoneThreadChatIds.collectAsState()
                    LaunchedEffect(
                        requestedProactiveChatId,
                        proactiveInboxEntries,
                        phoneThreadChatIds,
                    ) {
                        val chatId = requestedProactiveChatId ?: return@LaunchedEffect
                        val realSessionId = phoneThreadChatIds.entries
                            .firstOrNull { it.value == chatId }
                            ?.key
                        if (realSessionId != null) {
                            chatViewModel.switchSession(realSessionId)
                        } else {
                            val entries = proactiveInboxEntries.filter {
                                (it.connectionId == null || it.connectionId == activeConnectionId) &&
                                    (it.chatId ?: "phone") == chatId
                            }
                            if (entries.isEmpty()) return@LaunchedEffect
                            chatViewModel.openProactiveThread(chatId, entries)
                        }
                        backStackEntry.arguments?.putString(
                            Screen.Chat.ARG_PROACTIVE_CHAT_ID,
                            null,
                        )
                    }
                    LaunchedEffect(
                        requestedSessionId,
                        requestedProfileRoute,
                        profileSelectionSettled,
                        effectiveSessionProfileName,
                        agentProfiles,
                    ) {
                        val sessionId = requestedSessionId ?: return@LaunchedEffect
                        if (requestedProfileRoute != null) {
                            val targetProfile = requestedProfileRoute.takeUnless {
                                it == com.hermesandroid.relay.notifications
                                    .InteractionRequestNotifier.DEFAULT_PROFILE_ROUTE_VALUE
                            }
                            if (!profileSelectionSettled) return@LaunchedEffect
                            if (!connectionViewModel.isProfileSelectionAllowed(targetProfile)) {
                                backStackEntry.arguments?.putString(Screen.Chat.ARG_SESSION_ID, null)
                                backStackEntry.arguments?.putString(Screen.Chat.ARG_PROFILE, null)
                                return@LaunchedEffect
                            }
                            if (effectiveSessionProfileName != targetProfile) {
                                val selection = targetProfile?.let { name ->
                                    agentProfiles.firstOrNull { it.name == name }
                                }
                                if (targetProfile == null || selection != null) {
                                    connectionViewModel.selectProfile(selection)
                                    chatViewModel.activateGatewayProfile(selection)
                                }
                                return@LaunchedEffect
                            }
                            chatViewModel.switchProfileContext(
                                contextKey = AgentDisplay.profileContextKey(
                                    connectionId = activeConnectionId,
                                    profileName = targetProfile,
                                ),
                                sessionId = sessionId,
                            )
                        } else if (chatViewModel.currentSessionId.value != sessionId) {
                            chatViewModel.switchSession(sessionId)
                        }
                        backStackEntry.arguments?.putString(Screen.Chat.ARG_SESSION_ID, null)
                        backStackEntry.arguments?.putString(Screen.Chat.ARG_PROFILE, null)
                    }

                    val screenChatLabel = stringResource(R.string.screen_chat_label)

                    ChatScreen(
                        chatViewModel = chatViewModel,
                        connectionViewModel = connectionViewModel,
                        voiceViewModel = voiceViewModel,
                        voiceClient = voiceClient,
                        maxBubbleWidth = maxBubbleWidth,
                        voicePresentationMode = voicePresentationMode,
                        onVoicePresentationModeChange = { mode ->
                            connectionSwitchScope.launch {
                                voicePreferences.setPresentationMode(mode)
                            }
                        },
                        openAgentSheetOnEntry = openAgentSheetArg,
                        onAgentSheetArgConsumed = {
                            backStackEntry.arguments?.putBoolean(
                                Screen.Chat.ARG_OPEN_AGENT_SHEET, false,
                            )
                        },
                        // AgentInfoSheet footer jumps straight into the full
                        // Connections CRUD screen — saves a detour through
                        // Settings → Gateways.
                        onNavigateToConnections = {
                            navController.navigate(Screen.ConnectionsSettings.route)
                        },
                        onNavigateToConnect = {
                            navController.navigate(Screen.Pair.route()) {
                                launchSingleTop = true
                            }
                        },
                        onRepairConnection = {
                            navController.navigate(
                                Screen.Pair.route(
                                    connectionId = activeConnectionId,
                                    autoStart = "relay",
                                ),
                            ) {
                                launchSingleTop = true
                            }
                        },
                        // Empty-chat "needs connection" card also offers the offline
                        // demo, so a skipped / never-connected first run can explore
                        // without leaving Chat. Safe here — this state only shows when
                        // nothing is configured, so there's no placeholder in flight.
                        onTryDemo = enterDemo,
                        onNavigateToManage = {
                            navController.navigate(Screen.Manage.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToBridge = {
                            rememberBridgeReturn(
                                route = Screen.Chat.route(openAgentSheet = false),
                                label = screenChatLabel,
                            )
                            navController.navigate(Screen.Bridge.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToTerminal = {
                            navController.navigate(Screen.Terminal.route) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToSettings = {
                            navController.navigate(Screen.Settings.route) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToAppearanceSettings = {
                            navController.navigate(Screen.AppearanceSettings.route) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToVoiceSettings = {
                            navController.navigate(Screen.VoiceSettings.route) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToProfileInspector = { profileName ->
                            navController.navigate(Screen.ProfileInspector.route(profileName)) {
                                launchSingleTop = true
                            }
                        },
                        supervisedPolicy = chatSupervisedPolicy,
                        onNavigateToBotMode = {
                            navController.navigate(Screen.BotMode.route) { launchSingleTop = true }
                        },
                        gitWorkspaceAvailable = gitWorkspaceAvailable,
                        gitWorkspaceSummary = gitWorkspaceSummary,
                        onNavigateToGitWorkspace = {
                            navController.navigate(Screen.GitState.route) { launchSingleTop = true }
                        },
                    )
                }
                composable(Screen.BotMode.route) {
                    BotModeScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenBotChat = { route, sessionId ->
                            navController.navigate(
                                Screen.BotChat.route(
                                    connectionId = route.connectionId,
                                    profileName = route.profileName,
                                    sessionId = sessionId,
                                ),
                            )
                        },
                        onOpenGroup = { roomKey ->
                            navController.navigate(Screen.BotGroup.route(roomKey))
                        },
                    )
                }
                composable(
                    route = Screen.BotGroup.route,
                    arguments = listOf(
                        navArgument(Screen.BotGroup.ARG_ROOM_KEY) { type = NavType.StringType },
                    ),
                ) { entry ->
                    val roomKey = entry.arguments?.getString(Screen.BotGroup.ARG_ROOM_KEY)
                    val botModeState by connectionViewModel.botModeState.collectAsState()
                    BotGroupDetailScreen(
                        room = botModeState.roster.groups.firstOrNull { it.key == roomKey },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Screen.BotChat.route,
                    arguments = listOf(
                        navArgument(Screen.BotChat.ARG_CONNECTION_ID) { type = NavType.StringType },
                        navArgument(Screen.BotChat.ARG_PROFILE_NAME) { type = NavType.StringType },
                        navArgument(Screen.BotChat.ARG_SESSION_ID) { type = NavType.StringType },
                    ),
                ) { entry ->
                    val connectionId = entry.arguments?.getString(Screen.BotChat.ARG_CONNECTION_ID).orEmpty()
                    val profileName = entry.arguments?.getString(Screen.BotChat.ARG_PROFILE_NAME).orEmpty()
                    val sessionId = entry.arguments?.getString(Screen.BotChat.ARG_SESSION_ID).orEmpty()
                    val botModeState by connectionViewModel.botModeState.collectAsState()
                    val connection = connections.firstOrNull { it.id == connectionId }
                    val bot = botModeState.roster.bots.firstOrNull {
                        it.route?.connectionId == connectionId && it.profile.name == profileName
                    }
                    val route = bot?.route ?: connection?.let {
                        com.hermesandroid.relay.data.BotGatewayRoute(
                            key = com.hermesandroid.relay.data.BotGatewayRouteKey(connectionId, profileName),
                            connectionLabel = it.label,
                        )
                    }
                    val currentRouteUrl = connection?.let {
                        if (it.id == activeConnectionId) effectiveDashboardUrl else it.resolvedDashboardUrl
                    }.orEmpty()
                    val lease = remember(route?.key, currentRouteUrl) {
                        route?.let(connectionViewModel::acquireBotGateway)?.getOrNull()
                    }
                    val botDashboardClient = remember(route?.key, currentRouteUrl) {
                        route?.let(connectionViewModel::botDashboardClient)?.getOrNull()
                    }
                    DisposableEffect(lease, botDashboardClient) {
                        onDispose {
                            lease?.close()
                            botDashboardClient?.shutdown()
                        }
                    }
                    if (
                        route == null || bot == null || lease == null ||
                        botDashboardClient == null || sessionId.isBlank()
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.bot_mode_chat_open_failed),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        val botChatViewModel: ChatViewModel = viewModel(
                            key = "bot-chat:${route.connectionId}:${route.profileName}:$sessionId",
                        )
                        BotChatScreen(
                            route = route,
                            bot = bot,
                            sessionId = sessionId,
                            gatewayClient = lease.client,
                            dashboardClient = botDashboardClient,
                            chatViewModel = botChatViewModel,
                            connectionViewModel = connectionViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(Screen.Manage.route) {
                    if (isDemoMode) {
                        // Demo is offline — Manage talks to the live dashboard,
                        // so show a friendly demo empty state instead of
                        // attempting a sign-in / fetch.
                        DemoUnavailableContent(
                            feature = stringResource(R.string.demo_feature_manage),
                            onConnect = exitDemoToConnect,
                        )
                    } else {
                    val screenManageLabel = stringResource(R.string.screen_manage_label)
                    DashboardManagementScreen(
                        connectionViewModel = connectionViewModel,
                        onNavigateToConnections = {
                            navController.navigate(Screen.ConnectionsSettings.route)
                        },
                        onNavigateToSignIn = {
                            navController.navigate(Screen.DashboardSignIn.route()) {
                                launchSingleTop = true
                            }
                        },
                        // Standard back: return to wherever Manage was opened
                        // from (Settings → Hermes management, the agent sheet,
                        // etc.). The prior forced navigate(Chat) with
                        // saveState/restoreState was a no-op at runtime —
                        // navigating to the start destination with restoreState
                        // restored an equivalent stack and nothing moved.
                        onBack = { navController.popBackStack() },
                        onNavigateToBridge = {
                            rememberBridgeReturn(
                                route = Screen.Manage.route,
                                label = screenManageLabel,
                            )
                            navController.navigate(Screen.Bridge.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToTerminal = {
                            navController.navigate(Screen.Terminal.route) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToSettings = {
                            navController.navigate(Screen.Settings.route) {
                                launchSingleTop = true
                            }
                        },
                    )
                    }
                }
                composable(
                    route = Screen.DashboardSignIn.route,
                    arguments = listOf(
                        navArgument(Screen.DashboardSignIn.ARG_SOURCE) {
                            type = NavType.StringType
                            defaultValue = Screen.DashboardSignIn.SOURCE_GENERAL
                        },
                    ),
                ) { backStackEntry ->
                    val source = backStackEntry.arguments
                        ?.getString(Screen.DashboardSignIn.ARG_SOURCE)
                        ?: Screen.DashboardSignIn.SOURCE_GENERAL
                    DashboardSignInScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                        onAuthenticated = {
                            when (source) {
                                Screen.DashboardSignIn.SOURCE_ONBOARDING -> {
                                    connectionViewModel.completeOnboarding()
                                    navController.navigate(Screen.Chat.route()) {
                                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                                Screen.DashboardSignIn.SOURCE_PAIR -> {
                                    navController.popBackStack()
                                    navController.popBackStack()
                                }
                                else -> navController.popBackStack()
                            }
                        },
                    )
                }
                composable(Screen.Terminal.route) {
                    if (coldStartAuthState is AuthState.Paired) {
                        TerminalScreen(
                            terminalViewModel = terminalViewModel,
                            connectionViewModel = connectionViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    } else {
                        PowerFeatureGateScreen(
                            title = stringResource(R.string.power_gate_terminal_title),
                            summary = stringResource(R.string.power_gate_terminal_summary),
                            status = PowerFeatureGateStatus.fromRelayAuth(coldStartAuthState),
                            onPrimaryAction = {
                                navController.navigate(Screen.Pair.route())
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(Screen.Bridge.route) {
                    if (coldStartAuthState !is AuthState.Paired) {
                        PowerFeatureGateScreen(
                            title = stringResource(R.string.power_gate_bridge_title),
                            summary = stringResource(R.string.power_gate_bridge_summary),
                            status = PowerFeatureGateStatus.fromRelayAuth(coldStartAuthState),
                            onPrimaryAction = {
                                navController.navigate(Screen.Pair.route())
                            },
                            onBack = bridgeReturnAction,
                        )
                    } else {
                        if (BuildFlavor.isSideload) {
                            BridgeScreen(
                                connectionViewModel = connectionViewModel,
                                returnTitle = bridgeReturnTitle,
                                returnSubtitle = bridgeReturnSubtitle,
                                returnLabel = bridgePrimaryReturnLabel?.let {
                                    stringResource(bridgeReturnLabelResId)
                                } ?: stringResource(R.string.bridge_return_default_label),
                                onReturn = bridgeReturnAction,
                                onNavigateToBridgeSafety = {
                                    navController.navigate(Screen.BridgeSafetySettings.route)
                                },
                                onNavigateToChat = {
                                    // Standard back. The prior navigate(Chat) with
                                    // saveState/restoreState was a no-op — Chat is
                                    // the start destination, so restoreState just
                                    // restored the same stack and nothing moved.
                                    clearBridgeReturn()
                                    navController.popBackStack()
                                },
                                onNavigateToManage = {
                                    clearBridgeReturn()
                                    navController.navigate(Screen.Manage.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onNavigateToSettings = {
                                    navController.navigate(Screen.Settings.route) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        } else {
                            BridgeCoreScreen(
                                connectionViewModel = connectionViewModel,
                                returnTitle = bridgeReturnTitle,
                                returnSubtitle = bridgeReturnSubtitle,
                                returnLabel = bridgePrimaryReturnLabel?.let {
                                    stringResource(bridgeReturnLabelResId)
                                } ?: stringResource(R.string.bridge_return_default_label),
                                onReturn = bridgeReturnAction,
                                onNavigateToConnections = {
                                    navController.navigate(Screen.ConnectionsSettings.route)
                                },
                                onNavigateToChat = {
                                    // Standard back. The prior navigate(Chat) with
                                    // saveState/restoreState was a no-op — Chat is
                                    // the start destination, so restoreState just
                                    // restored the same stack and nothing moved.
                                    clearBridgeReturn()
                                    navController.popBackStack()
                                },
                                onNavigateToManage = {
                                    clearBridgeReturn()
                                    navController.navigate(Screen.Manage.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onNavigateToTerminal = {
                                    navController.navigate(Screen.Terminal.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onNavigateToVoiceSettings = {
                                    navController.navigate(Screen.VoiceSettings.route)
                                },
                                onNavigateToNotificationCompanion = {
                                    navController.navigate(Screen.NotificationCompanionSettings.route)
                                },
                                onNavigateToMediaSettings = {
                                    navController.navigate(Screen.MediaSettings.route)
                                },
                                onNavigateToRelaySessions = {
                                    navController.navigate(Screen.PairedDevices.route)
                                },
                                onNavigateToSettings = {
                                    navController.navigate(Screen.Settings.route) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                    }
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        connectionViewModel = connectionViewModel,
                        chatViewModel = chatViewModel,
                        supervisedPolicy = supervisedPolicy,
                        parentAccessUnlocked = parentAccessForCurrentRoute,
                        onRequestParentAccess = { parentAccessUnlocked = true },
                        onUpdateSupervisedPolicy = { policy ->
                            activeConnectionId?.let { connectionId ->
                                connectionSwitchScope.launch {
                                    supervisedModeStore.setPolicy(connectionId, policy)
                                }
                            }
                        },
                        onNavigateToAdvancedSettings = {
                            navController.navigate(Screen.AdvancedSettings.route)
                        },
                        onNavigateToSupervisedAppearance = {
                            navController.navigate(Screen.SupervisedAppearanceSettings.route)
                        },
                        onNavigateToSupervisedControls = {
                            navController.navigate(Screen.SupervisedControls.route)
                        },
                        onBack = { navController.popBackStack() },
                        // (The `onNavigateToChatWithAgentSheet` callback that
                        // used to live here was removed 2026-04-21. Tapping
                        // the Active Agent card on Settings now opens the
                        // AgentInfoSheet inline on THAT screen — closing the
                        // sheet returns the user to Settings instead of
                        // leaving them on Chat after a confusing redirect.)
                        onNavigateToConnections = {
                            navController.navigate(Screen.ConnectionsSettings.route)
                        },
                        onNavigateToManage = {
                            navController.navigate(Screen.Manage.route)
                        },
                        onNavigateToProviderUsage = {
                            navController.navigate(Screen.ProviderUsage.route)
                        },
                        onNavigateToPlugins = {
                            navController.navigate(Screen.Plugins.route)
                        },
                        onNavigateToGitWorkspace = {
                            navController.navigate(Screen.GitState.route)
                        },
                        onNavigateToChatSettings = {
                            navController.navigate(Screen.ChatSettings.route)
                        },
                        onNavigateToTerminal = {
                            navController.navigate(Screen.Terminal.route)
                        },
                        onNavigateToBridge = {
                            clearBridgeReturn()
                            navController.navigate(Screen.Bridge.route)
                        },
                        onNavigateToMediaSettings = {
                            navController.navigate(Screen.MediaSettings.route)
                        },
                        onNavigateToAppearanceSettings = {
                            navController.navigate(Screen.AppearanceSettings.route)
                        },
                        onNavigateToAnalytics = {
                            navController.navigate(Screen.Analytics.route)
                        },
                        onNavigateToDiagnostics = {
                            navController.navigate(Screen.Diagnostics.route)
                        },
                        onNavigateToVoiceSettings = {
                            navController.navigate(Screen.VoiceSettings.route)
                        },
                        onNavigateToNotificationCompanion = {
                            navController.navigate(Screen.NotificationCompanionSettings.route)
                        },
                        onNavigateToProactiveSettings = {
                            navController.navigate(Screen.ProactiveSettings.route)
                        },
                        onNavigateToPermissions = {
                            navController.navigate(Screen.PermissionsSettings.route)
                        },
                        // === PHASE3-safety-rails: bridge safety route ===
                        onNavigateToBridgeSafety = {
                            navController.navigate(Screen.BridgeSafetySettings.route)
                        },
                        // === END PHASE3-safety-rails ===
                        onNavigateToPairedDevices = {
                            navController.navigate(Screen.PairedDevices.route)
                        },
                        onNavigateToDeveloperSettings = {
                            navController.navigate(Screen.DeveloperSettings.route)
                        },
                        onNavigateToAbout = {
                            navController.navigate(Screen.About.route)
                        },
                        onNavigateToProfileInspector = { profileName ->
                            navController.navigate(
                                Screen.ProfileInspector.route(profileName),
                            )
                        },
                    )
                }
                composable(Screen.AdvancedSettings.route) {
                    if (!parentAccessForCurrentRoute && supervisedPolicy.enabled) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        AdvancedSettingsScreen(
                            supervisedPolicy = supervisedPolicy,
                            onNavigateToSupervisedControls = {
                                navController.navigate(Screen.SupervisedControls.route)
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(Screen.SupervisedAppearanceSettings.route) {
                    if (!supervisedPolicy.enabled && !parentAccessForCurrentRoute) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        SupervisedAppearanceSettingsScreen(
                            connectionViewModel = connectionViewModel,
                            policy = supervisedPolicy,
                            onPolicyChange = { policy ->
                                activeConnectionId?.let { connectionId ->
                                    connectionSwitchScope.launch {
                                        supervisedModeStore.setPolicy(connectionId, policy)
                                    }
                                }
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(Screen.SupervisedControls.route) {
                    if (!parentAccessForCurrentRoute && supervisedPolicy.enabled) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        SupervisedControlsScreen(
                            connectionViewModel = connectionViewModel,
                            policy = supervisedPolicy,
                            profiles = agentProfiles.filterNot { it.isDefault },
                            onPolicyChange = { policy ->
                                activeConnectionId?.let { connectionId ->
                                    connectionSwitchScope.launch {
                                        supervisedModeStore.setPolicy(connectionId, policy)
                                    }
                                }
                            },
                            onBack = { navController.popBackStack() },
                            onReturnToSupervisedView = {
                                navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                                    popUpTo(Screen.Chat.route) { inclusive = false }
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                }
                composable(Screen.ProviderUsage.route) {
                    UsageLimitsScreen(
                        connectionViewModel = connectionViewModel,
                        chatViewModel = chatViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.Plugins.route) {
                    PluginsScreen(
                        viewModel = pluginsViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenPage = { pluginId, pageId ->
                            if (pluginId == "hermes-relay" && pageId == "git") {
                                navController.navigate(Screen.GitState.route)
                            } else {
                                navController.navigate(Screen.PluginPage.route(pluginId, pageId))
                            }
                        },
                    )
                }
                composable(Screen.GitState.route) {
                    GitStateScreen(
                        viewModel = gitStateViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Screen.PluginPage.route,
                    arguments = listOf(
                        navArgument(Screen.PluginPage.ARG_PLUGIN_ID) { type = NavType.StringType },
                        navArgument(Screen.PluginPage.ARG_PAGE_ID) { type = NavType.StringType },
                    ),
                ) { entry ->
                    PluginPageScreen(
                        viewModel = pluginsViewModel,
                        pluginId = entry.arguments?.getString(Screen.PluginPage.ARG_PLUGIN_ID).orEmpty(),
                        pageId = entry.arguments?.getString(Screen.PluginPage.ARG_PAGE_ID).orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.VoiceSettings.route) {
                    if (isDemoMode) {
                        // Voice runs through the live server (transcribe /
                        // synthesize) — show the demo empty state offline.
                        DemoUnavailableContent(
                            feature = stringResource(R.string.screen_voice_label),
                            onConnect = exitDemoToConnect,
                        )
                    } else {
                    val standardVoiceSignInRouteHint by
                        connectionViewModel.standardVoiceSignInRouteHint.collectAsState()
                    val voiceDashboardUrl by
                        connectionViewModel.effectiveDashboardUrl.collectAsState()
                    VoiceSettingsScreen(
                        voiceViewModel = voiceViewModel,
                        voiceClient = voiceClient,
                        connectionId = activeConnectionId,
                        selectedProfile = selectedProfile,
                        displayProfile = effectiveDisplayProfile,
                        standardVoiceAvailability = standardVoiceAvailability,
                        standardVoiceSignInRouteHint = standardVoiceSignInRouteHint,
                        relayVoiceReady = relayVoiceReady,
                        dashboardUrl = voiceDashboardUrl,
                        dashboardClientProvider = { dashboardUrl ->
                            connectionViewModel.dashboardClientForActive(dashboardUrl)
                        },
                        onOpenManage = {
                            navController.navigate(Screen.Manage.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onBack = { navController.popBackStack() }
                    )
                    }
                }
                // === PHASE3-notif-listener-followup: notification companion route ===
                composable(Screen.NotificationCompanionSettings.route) {
                    NotificationCompanionSettingsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
                // === END PHASE3-notif-listener-followup ===
                composable(Screen.ProactiveSettings.route) {
                    ProactiveSettingsScreen(
                        connectionViewModel = connectionViewModel,
                        onOpenChat = {
                            navController.navigate(Screen.Chat.route()) {
                                popUpTo(Screen.Chat.route()) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.PermissionsSettings.route) {
                    PermissionsStatusScreen(
                        onBack = { navController.popBackStack() },
                        onOpenBridge = {
                            navController.navigate(Screen.Bridge.route) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
                // === PHASE3-safety-rails: bridge safety route ===
                composable(Screen.BridgeSafetySettings.route) {
                    if (BuildFlavor.isSideload) {
                        BridgeSafetySettingsScreen(
                            connectionId = activeConnectionId,
                            onBack = { navController.popBackStack() }
                        )
                    } else {
                        BridgeCoreScreen(
                            connectionViewModel = connectionViewModel,
                            onNavigateToConnections = {
                                navController.navigate(Screen.ConnectionsSettings.route)
                            },
                            onNavigateToChat = {
                                // popBackStack: navigate(Chat) with restoreState
                                // no-ops (Chat is the start destination).
                                navController.popBackStack()
                            },
                            onNavigateToManage = {
                                navController.navigate(Screen.Manage.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onNavigateToTerminal = {
                                navController.navigate(Screen.Terminal.route)
                            },
                            onNavigateToVoiceSettings = {
                                navController.navigate(Screen.VoiceSettings.route)
                            },
                            onNavigateToNotificationCompanion = {
                                navController.navigate(Screen.NotificationCompanionSettings.route)
                            },
                            onNavigateToMediaSettings = {
                                navController.navigate(Screen.MediaSettings.route)
                            },
                            onNavigateToRelaySessions = {
                                navController.navigate(Screen.PairedDevices.route)
                            },
                            onNavigateToSettings = {
                                navController.navigate(Screen.Settings.route) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                }
                // === END PHASE3-safety-rails ===
                composable(Screen.PairedDevices.route) {
                    if (
                        coldStartAuthState is AuthState.Paired ||
                        currentPairedSession != null
                    ) {
                        PairedDevicesScreen(
                            connectionViewModel = connectionViewModel,
                            onBack = { navController.popBackStack() },
                            onManageSessions = {
                                navController.navigate(Screen.Manage.route) {
                                    launchSingleTop = true
                                }
                            },
                            onRequestRepair = {
                                navController.navigate(Screen.Pair.route())
                            }
                        )
                    } else {
                        PowerFeatureGateScreen(
                            title = stringResource(R.string.screen_relay_sessions_label),
                            summary = stringResource(R.string.power_gate_relay_sessions_summary),
                            status = PowerFeatureGateStatus.fromRelayAuth(coldStartAuthState),
                            onPrimaryAction = {
                                navController.navigate(Screen.Pair.route())
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                // (The `composable(Screen.ConnectionSettings.route)` block
                // that used to live here — hosting the singular, legacy
                // 1400-line `ConnectionSettingsScreen` — was removed on
                // 2026-04-21 as part of the connection-settings
                // unification. Everything that screen did (pair, manual
                // URL config, TLS/insecure toggle, manual pairing code
                // fallback) now lives inline on the active card of the
                // plural `ConnectionsSettings` screen below, via the
                // expandable sections in `ActiveConnectionSections.kt`.
                // The corresponding `data object ConnectionSettings` was
                // also removed from the `Screen` sealed class, and the
                // `onNavigateToConnectionSettings` param on
                // `SettingsScreen` was dropped.)
                composable(Screen.ConnectionsSettings.route) {
                    val connectionsList by connectionViewModel.connections.collectAsState()
                    val activeId by connectionViewModel.activeConnectionId.collectAsState()
                    val activeRelayUiState by connectionViewModel.relayUiState.collectAsState()
                    ConnectionsSettingsScreen(
                        connections = connectionsList,
                        activeConnectionId = activeId,
                        activeRelayUiState = activeRelayUiState,
                        // Tapping a connection card drills into its tabbed
                        // detail (Overview / Routes / Advanced / Security),
                        // where rename / re-pair / revoke / remove now live.
                        onOpenConnection = { id ->
                            navController.navigate(Screen.ConnectionDetail.route(id))
                        },
                        addConnectionEnabled = mayStartAddConnection(
                            supervisedEnabled = supervisedPolicy.enabled,
                            parentAccessUnlocked = parentAccessForCurrentRoute,
                        ),
                        onAddConnection = {
                            val id = java.util.UUID.randomUUID().toString()
                            runAddConnectionAction(
                                supervisedEnabled = supervisedPolicy.enabled,
                                parentAccessUnlocked = parentAccessForCurrentRoute,
                                navigateToPair = {
                                    navController.navigate(Screen.Pair.route(connectionId = id))
                                },
                                prepareConnection = { prepareAddConnection(id, false) },
                            )
                        },
                        onBack = { navController.popBackStack() },
                        // Pass the VM so the list cards can read live status
                        // for the active connection. Null-safe — if the VM
                        // isn't wired (tests, previews), cards degrade to the
                        // flat layout.
                        connectionViewModel = connectionViewModel,
                    )
                }
                composable(
                    route = Screen.ConnectionDetail.route,
                    arguments = listOf(
                        navArgument(Screen.ConnectionDetail.ARG_CONNECTION_ID) {
                            type = NavType.StringType
                        },
                    ),
                ) { backStackEntry ->
                    val detailId = backStackEntry.arguments
                        ?.getString(Screen.ConnectionDetail.ARG_CONNECTION_ID)
                        .orEmpty()
                    com.hermesandroid.relay.ui.screens.ConnectionDetailScreen(
                        connectionId = detailId,
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                        onReconnect = {
                            connectionViewModel.reconnectIfStale()
                            UiMessageBus.status(reconnectingRelayLabel)
                        },
                        onRename = { id, newLabel ->
                            connectionSwitchScope.launch {
                                connectionViewModel.renameConnection(id, newLabel)
                                    .onFailure { err ->
                                        snackbarHostState.showSnackbar(
                                            err.message ?: renameFailedLabel,
                                        )
                                    }
                            }
                        },
                        onRepair = { id ->
                            connectionSwitchScope.launch {
                                connectionViewModel.switchConnection(id).join()
                                navController.navigate(Screen.Pair.route(id, autoStart = "relay"))
                            }
                        },
                        onRevoke = { id ->
                            connectionSwitchScope.launch {
                                val result = connectionViewModel.revokeConnection(id)
                                if (result.isFailure) {
                                    snackbarHostState.showSnackbar(revokeOnlyActiveLabel)
                                }
                            }
                        },
                        onRemove = { id ->
                            connectionSwitchScope.launch {
                                connectionViewModel.removeConnection(id)
                            }
                        },
                        onSwitchToConnection = { id ->
                            connectionSwitchScope.launch {
                                connectionViewModel.switchConnection(id)
                            }
                        },
                        onNavigateToManage = {
                            navController.navigate(Screen.Manage.route) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToPairedDevices = {
                            navController.navigate(Screen.PairedDevices.route)
                        },
                    )
                }
                composable(
                    route = Screen.Pair.route,
                    arguments = listOf(
                        navArgument(Screen.Pair.ARG_CONNECTION_ID) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(Screen.Pair.ARG_AUTO_START) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { backStackEntry ->
                    val connectionIdArg = backStackEntry.arguments
                        ?.getString(Screen.Pair.ARG_CONNECTION_ID)
                    val autoStartArg = backStackEntry.arguments
                        ?.getString(Screen.Pair.ARG_AUTO_START)
                    val pairConnections by connectionViewModel.connections.collectAsState()
                    val pairActiveId by connectionViewModel.activeConnectionId.collectAsState()
                    val pairDraftId by connectionViewModel.connectionDraftId.collectAsState()
                    val pairStoreHydrated by connectionViewModel.connectionStore.isHydrated.collectAsState()
                    // Duplicate Renew authorizes one explicit route handoff
                    // before switching away from the placeholder. Persist the
                    // identity, not a bare readiness boolean, so Activity
                    // recreation remains safe and process restore still has
                    // to hydrate a real matching connection row.
                    var authorizedPairHandoffId by rememberSaveable(connectionIdArg) {
                        mutableStateOf<String?>(null)
                    }
                    val pairSetupReady = resolvePairSetupReady(
                        storeHydrated = pairStoreHydrated,
                        connectionId = connectionIdArg,
                        authorizedHandoffId = authorizedPairHandoffId,
                        activeConnectionId = pairActiveId,
                        connectionIds = pairConnections.mapTo(mutableSetOf()) { it.id },
                        draftConnectionId = pairDraftId,
                    )
                    com.hermesandroid.relay.ui.screens.PairScreen(
                        connectionViewModel = connectionViewModel,
                        autoStart = autoStartArg,
                        setupReady = pairSetupReady,
                        onSetupTimeout = if (connectionIdArg == null) null else ({
                            DiagnosticsLog.record(
                                category = DiagnosticCategory.Auth,
                                severity = DiagnosticSeverity.Warning,
                                title = "Connection setup did not become ready",
                                detail = "targetPresent=${pairConnections.any { it.id == connectionIdArg }} " +
                                    "activeMatches=${pairActiveId == connectionIdArg} " +
                                    "activePresent=${pairActiveId != null}",
                                operation = "Prepare connection-scoped local storage",
                                suggestion = "Retry setup or cancel and add the connection again.",
                            )
                        }),
                        onSetupRetry = if (connectionIdArg == null) null else ({
                            prepareAddConnection(connectionIdArg, true)
                        }),
                        onConnectionTargetChanged = { existingId ->
                            authorizedPairHandoffId = existingId
                        },
                        // Offer demo only on the bare "Connect" entry (the
                        // "No Hermes connection" path) — not on add-connection /
                        // re-pair flows, which have a placeholder connection in
                        // flight that enterDemo would leave un-discarded.
                        onTryDemo = if (connectionIdArg == null) enterDemo else null,
                        onComplete = {
                            if (connectionIdArg != null && pairDraftId == connectionIdArg) {
                                connectionSwitchScope.launch {
                                    connectionViewModel.commitConnectionDraft(connectionIdArg)
                                    navController.popBackStack()
                                }
                            } else {
                                navController.popBackStack()
                            }
                        },
                        onManageSignIn = {
                            navController.navigate(
                                Screen.DashboardSignIn.route(Screen.DashboardSignIn.SOURCE_PAIR),
                            ) {
                                launchSingleTop = true
                            }
                        },
                        onCancel = {
                            // If the user bailed out before completing a
                            // pair, discard the placeholder we pre-created
                            // on entry. Safe no-op for real (paired)
                            // connections; only removes placeholders that
                            // never got a pairedAt stamp.
                            if (connectionIdArg != null) {
                                connectionSwitchScope.launch {
                                    // If Back wins the race with background
                                    // preparation, wait until the placeholder
                                    // exists before attempting to discard it.
                                    pendingAddConnectionJobs.remove(connectionIdArg)?.join()
                                    connectionViewModel.discardPlaceholderConnection(connectionIdArg)
                                }
                            }
                            navController.popBackStack()
                        },
                    )
                }
                composable(Screen.ChatSettings.route) {
                    ChatSettingsScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.MediaSettings.route) {
                    MediaSettingsScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.AppearanceSettings.route) {
                    AppearanceSettingsScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                        onBrowsePetdex = { navController.navigate(Screen.PetdexBrowse.route) },
                        onCreatePet = { navController.navigate(Screen.CustomPetGuide.route) },
                        onOpenCustomTheme = { navController.navigate(Screen.CustomTheme.route) },
                    )
                }
                composable(Screen.CustomTheme.route) {
                    CustomThemeScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.PetdexBrowse.route) {
                    PetdexBrowseScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.CustomPetGuide.route) {
                    CustomPetGuideScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                        onStartNewChat = { prompt ->
                            chatViewModel.createNewChat()
                            chatViewModel.stageComposerDraft(prompt)
                            navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                                popUpTo(Screen.Chat.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(Screen.Analytics.route) {
                    AnalyticsScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                        voiceViewModel = voiceViewModel,
                        chatViewModel = chatViewModel,
                    )
                }
                composable(Screen.Diagnostics.route) {
                    DiagnosticsScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.DeveloperSettings.route) {
                    DeveloperSettingsScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                        onNavigateToRealtimeVoice = {
                            navController.navigate(Screen.RealtimeVoiceTest.route)
                        },
                        onNavigateToImageGenerationLab = {
                            terminalAppContext.startActivity(
                                android.content.Intent().apply {
                                    setClassName(
                                        terminalAppContext,
                                        "com.hermesandroid.relay.ui.screens.ImageGenerationDesignQaActivity",
                                    )
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        },
                    )
                }
                composable(Screen.RealtimeVoiceTest.route) {
                    RealtimeVoiceTestScreen(
                        voiceClient = voiceClient,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.About.route) {
                    AboutScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                        allowDeveloperUnlock = !supervisedPolicy.enabled || parentAccessForCurrentRoute,
                    )
                }
                composable(
                    route = Screen.ProfileInspector.route,
                    arguments = listOf(
                        navArgument(Screen.ProfileInspector.ARG_PROFILE_NAME) {
                            type = NavType.StringType
                        },
                        // Optional `section` query arg — which tab to
                        // land on. Defaults to "config" so existing
                        // deep-links without the arg keep their
                        // pre-deep-link behaviour.
                        navArgument(Screen.ProfileInspector.ARG_SECTION) {
                            type = NavType.StringType
                            defaultValue = Screen.ProfileInspector.SECTION_CONFIG
                        },
                    ),
                ) { backStackEntry ->
                    // Build the VM with the shared inspector client and
                    // the nav-back-stack's SavedStateHandle. A small
                    // factory keeps the VM scoped to this destination —
                    // leaving the screen (popBackStack) destroys it, so
                    // entering a different profile later gets a fresh
                    // VM rather than reusing stale state.
                    //
                    // Keyed on the profile-name arg so navigating from
                    // profile A → profile B (unlikely in v1 but possible
                    // via deep link) yields a fresh VM rather than
                    // reusing the A VM with A's loaded state.
                    val profileNameArg = backStackEntry.arguments
                        ?.getString(Screen.ProfileInspector.ARG_PROFILE_NAME)
                        .orEmpty()
                    val sectionArg = backStackEntry.arguments
                        ?.getString(Screen.ProfileInspector.ARG_SECTION)
                        ?: Screen.ProfileInspector.SECTION_CONFIG
                    val inspectorGatewayClient = connectionViewModel.activeGatewayChatClient()
                    if (coldStartAuthState !is AuthState.Paired && inspectorGatewayClient == null) {
                        PowerFeatureGateScreen(
                            title = stringResource(R.string.screen_profile_inspector_label),
                            summary = stringResource(R.string.power_gate_profile_inspector_summary),
                            status = PowerFeatureGateStatus.fromRelayAuth(coldStartAuthState),
                            onPrimaryAction = {
                                navController.navigate(Screen.Pair.route())
                            },
                            onBack = { navController.popBackStack() },
                        )
                        return@composable
                    }
                    val inspectorViewModel: ProfileInspectorViewModel = viewModel(
                        viewModelStoreOwner = backStackEntry,
                        key = "profile-inspector-$profileNameArg",
                        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : androidx.lifecycle.ViewModel> create(
                                modelClass: Class<T>,
                                extras: androidx.lifecycle.viewmodel.CreationExtras,
                            ): T {
                                // createSavedStateHandle() pulls the
                                // typed nav args out of extras — the
                                // backStackEntry is the SavedStateRegistry
                                // owner here, so the resulting
                                // SavedStateHandle contains our
                                // `profileName` arg automatically.
                                val ssh = extras.createSavedStateHandle()
                                // Freeze both transports to the connection that
                                // owned this nav entry. A later connection/profile
                                // switch cannot redirect an open editor's writes.
                                val relayUrl = connectionViewModel.effectiveRelayUrl.value
                                val relayToken = (connectionViewModel.authState.value as? AuthState.Paired)?.token
                                return ProfileInspectorViewModel(
                                    legacyClient = RelayProfileInspectorClient(
                                        okHttpClient = profileInspectorHttpClient,
                                        relayUrlProvider = { relayUrl },
                                        sessionTokenProvider = { relayToken },
                                        dashboardHttpClientProvider =
                                            connectionViewModel::dashboardHttpClientForRelayIngress,
                                    ),
                                    gatewayClient = inspectorGatewayClient,
                                    savedStateHandle = ssh,
                                ) as T
                            }
                        },
                    )

                    // Pull the model label off the current activeProfile
                    // for the top-bar subtitle — read-only snapshot,
                    // falls back to null when the selected profile
                    // doesn't happen to match the one we're inspecting
                    // (shouldn't normally happen since the entry is
                    // keyed off the same Profile).
                    val inspectorDisplayProfile by connectionViewModel
                        .effectiveDisplayProfile.collectAsState()
                    val modelLabel = inspectorDisplayProfile
                        ?.takeIf { it.name == profileNameArg }
                        ?.model

                    ProfileInspectorScreen(
                        viewModel = inspectorViewModel,
                        profileModel = modelLabel,
                        initialSection = sectionArg,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
                if (!routeContentAllowed) {
                    // Keep the graph mounted so the redirect can complete, but
                    // cover restored parent-only content with an opaque fail-closed surface.
                    SupervisedStartupLoadingScreen()
                }
                }
            } // end bridge-return wrapper column
            } // end CompositionLocalProvider
        }
        } // end Column (wraps banner + Scaffold)

        // One companion host survives navigation. Screens register real-layout
        // walk strips; without one the pet remains docked at its persisted edge.
        // The host's empty full-screen area has no pointer input, so only the
        // The scaled pet target intercepts touches.
        val petSurfaceOwner = petSurfaceOwnerForRoute(currentRoute)
        val petActivity = petCompanionCoordinator.activityFor(petSurfaceOwner)
        val showFloatingPet = activeFloatingPet != null &&
            shouldShowPetInSupervisedMode(supervisedPolicy, parentAccessForCurrentRoute) &&
            floatingPetAllowedOnRoute(currentRoute) &&
            !petActivity.hidden &&
            !suppressGlobalChrome &&
            !showStartupSphere &&
            !voiceUiState.voiceMode
        if (showFloatingPet) {
            val roamingRoute = petSurfaceOwner
            FloatingPetCompanion(
                pet = requireNotNull(activeFloatingPet),
                state = petActivity.renderState,
                placement = petPlacement,
                roamingEnabled = petRoamingEnabled,
                behaviorPreferences = petBehaviorPreferences,
                debugTerrainOverlay = petTerrainOverlayEnabled,
                sizeScale = petBehaviorPreferences.sizeScale,
                // Surface scrolling suspends autonomous movement without
                // visually muting the companion. The keyboard only selects
                // the compact footprint; typing keeps the pet animated and
                // interactive on the remaining safe terrain.
                // A scroll is a temporary movement pause, not a route change.
                // Keeping route support stable prevents the overlay from
                // re-docking to its persisted edge position mid-scroll.
                roamingAllowed = roamingRoute != null,
                surfaceScrolling = petActivity.scrolling,
                compact = shouldCompactFloatingPet(
                    imeVisible = isKeyboardVisible,
                    screenHeightDp = LocalConfiguration.current.screenHeightDp,
                ),
                animationEnabled = animationEnabled,
                appForeground = appIsForeground,
                interactive = !supervisedAppearanceLocked,
                route = roamingRoute,
                visitRequest = petCompanionCoordinator.pendingVisitRequest,
                onVisitRequestConsumed = petCompanionCoordinator::clearVisitRequest,
                onPlacementChanged = connectionViewModel::setPetPlacement,
                onRoamingEnabledChanged = connectionViewModel::setPetRoamingEnabled,
                onResetPlacement = connectionViewModel::resetPetPlacement,
                onHide = { connectionViewModel.setFloatingPet(null) },
                onOpenAppearance = {
                    navController.navigate(Screen.AppearanceSettings.route) { launchSingleTop = true }
                },
                onMenuExpandedChanged = { floatingPetMenuExpanded = it },
                onExitTerrainDebug = {
                    petTerrainOverlayScope.launch {
                        FeatureFlags.setPetTerrainOverlayEnabled(sphereContext, false)
                    }
                },
            )
        }

        // Floating overlay toasts (update + connection status). Rendered in the
        // Box, stacked top-down in one status-bar-padded Column so they slide
        // down OVER the content without resizing it — no UI cut/resize on update
        // / handoff / reconnect. Both gated off during onboarding / startup
        // sphere / voice mode. The Column self-pads the status bar once; the
        // children don't (so two stacked toasts don't double-pad).
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            AnimatedVisibility(
                visible = showCandidateBanner,
                enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(160)),
            ) {
                CandidateBuildBanner()
            }
            AnimatedVisibility(
                visible = availableUpdateStatus != null && !suppressGlobalChrome &&
                    !showStartupSphere && !voiceUiState.voiceMode,
                enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(160)),
            ) {
                availableUpdateStatus?.let { status ->
                    UpdateAvailableBanner(
                        status = status,
                        onUpdate = updateHandle.onUpdateClick,
                        onDismiss = updateHandle.onDismiss,
                        includeStatusBarPadding = false,
                    )
                }
            }
            // Connection status has no surface here (or anywhere at the top).
            // Chat/agent status rides the chat header subtitle; the relay socket
            // rides the bottom RelayStatusStrip cue. Only the update banner floats.
        }

        // (The ConnectionSwitcherSheet modal that used to live here was
        // driven by the removed top-bar ConnectionChip. Switching is now
        // inline in AgentInfoSheet's Connection section — see
        // ConnectionInfoSheet.kt's "Multi-connection switcher" block.
        // ConnectionSwitcherSheet.kt itself is kept so any future programmatic
        // callers (deep links, automation) can still invoke it if needed.)

        // Startup connection gate. The sphere is the loading screen: it
        // holds until the app is actually presentable (connected + last
        // conversation restored, or a settled error, or the backstop
        // timeout) and narrates progress as terminal-style check lines so
        // a longer wait reads as work, not a hang.
        AnimatedVisibility(
            visible = showStartupSphere,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(600))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(RelayRefresh.Background)
                    .relayGridTexture(alpha = 0.14f),
                contentAlignment = Alignment.Center
            ) {
                // Avatar fills background (sphere by default; routed through the
                // seam so a future pet appears on the startup screen too).
                LocalAgentAvatar.current.Render(
                    state = AvatarRenderState(state = SphereState.Idle),
                    modifier = Modifier.fillMaxSize(),
                )

                // Branding overlaid at bottom third
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 120.dp)
                ) {
                    Text(
                        text = stringResource(R.string.app_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = RelayRefresh.Paper.copy(alpha = 0.92f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.agent_interface),
                        style = MaterialTheme.typography.bodyMedium,
                        color = RelayRefresh.Muted.copy(alpha = 0.72f),
                        letterSpacing = 2.sp
                    )
                }

                // Startup checks — all rows are always laid out (pending
                // ones dimmed) so the column never reflows and the branding
                // above never shifts. What renders is the CHOREOGRAPHED view
                // of startupCheckTargets: rows above the narration stage show
                // their real verdict, the stage row spins (unless it already
                // failed), rows below sit dimmed with their short labels.
                if (startupCheckTargets.isNotEmpty()) {
                    val pendingLabels = listOf("state", "route", "hermes", "conversation")
                    val displayedChecks = startupCheckTargets.mapIndexed { index, check ->
                        when {
                            index < startupNarrationStage -> check
                            index == startupNarrationStage ->
                                if (check.state == StartupCheckState.Failed) {
                                    check
                                } else {
                                    check.copy(state = StartupCheckState.Active)
                                }
                            else -> StartupCheck(
                                StartupCheckState.Pending,
                                pendingLabels.getOrElse(index) { check.label },
                            )
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                    ) {
                        displayedChecks.forEach { check -> StartupCheckRow(check) }
                    }
                }
            }
        }
        } // end Box
    }
    } // end CompositionLocalProvider (sphere skin)
}

/** One line of the startup sphere's progress narration. */
private data class StartupCheck(
    val state: StartupCheckState,
    val label: String,
)

private val STARTUP_SPINNER_FRAMES = listOf("|", "/", "-", "\\")

private enum class StartupCheckState { Pending, Active, Done, Failed }

/**
 * Terminal-style check line for the startup sphere: monospace glyph + label,
 * dimmed while pending and fading up as the underlying state lands. Failed
 * is visible only briefly — a settled failure releases the gate and the
 * normal UI takes over error presentation.
 */
@Composable
private fun StartupCheckRow(check: StartupCheck) {
    val targetAlpha = when (check.state) {
        StartupCheckState.Pending -> 0.28f
        StartupCheckState.Active -> 0.85f
        else -> 0.95f
    }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(400),
        label = "startup-check-alpha",
    )
    // Classic ASCII spinner for the active row — guaranteed glyphs in the
    // platform monospace font (fancier braille spinners render as tofu on
    // some devices) and on-theme for terminal-style narration.
    var spinnerFrame by remember { mutableStateOf(0) }
    if (check.state == StartupCheckState.Active) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(120L)
                spinnerFrame = (spinnerFrame + 1) % STARTUP_SPINNER_FRAMES.size
            }
        }
    }
    val glyph = when (check.state) {
        StartupCheckState.Pending -> "·"
        StartupCheckState.Active -> STARTUP_SPINNER_FRAMES[spinnerFrame]
        StartupCheckState.Done -> "✓"
        StartupCheckState.Failed -> "✕"
    }
    val glyphColor = when (check.state) {
        StartupCheckState.Done -> RelayRefresh.Green
        StartupCheckState.Failed -> RelayRefresh.Danger
        else -> RelayRefresh.Muted
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.alpha(alpha),
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = glyphColor,
        )
        Text(
            text = check.label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (check.state == StartupCheckState.Active) {
                RelayRefresh.Paper.copy(alpha = 0.9f)
            } else {
                RelayRefresh.Muted
            },
        )
    }
}

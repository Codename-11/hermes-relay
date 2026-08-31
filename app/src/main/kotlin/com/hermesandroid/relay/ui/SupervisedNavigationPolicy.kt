package com.hermesandroid.relay.ui

import com.hermesandroid.relay.data.ConnectionStore
import com.hermesandroid.relay.data.SupervisedModePolicy

/** Allowlist applied to external, deep-link, and programmatic navigation. */
internal fun isSupervisedRouteAllowed(route: String?, parentAccessUnlocked: Boolean): Boolean {
    if (parentAccessUnlocked) return true
    val normalized = route?.substringBefore('?') ?: return false
    return normalized == "chat" ||
        normalized == Screen.Settings.route ||
        normalized == Screen.SupervisedAppearanceSettings.route
}

internal fun mayStartAddConnection(
    supervisedEnabled: Boolean,
    parentAccessUnlocked: Boolean,
    activeTargetId: String? = null,
): Boolean = activeTargetId == null && (!supervisedEnabled || parentAccessUnlocked)

internal inline fun runAddConnectionAction(
    supervisedEnabled: Boolean,
    parentAccessUnlocked: Boolean,
    activeTargetId: String?,
    allocateTarget: () -> String,
    recordTarget: (String) -> Unit,
    navigateToPair: (String) -> Unit,
    prepareConnection: (String) -> Unit,
): String? {
    if (!mayStartAddConnection(supervisedEnabled, parentAccessUnlocked, activeTargetId)) return null
    val targetId = allocateTarget()
    recordTarget(targetId)
    navigateToPair(targetId)
    prepareConnection(targetId)
    return targetId
}

/** Do not inspect or mutate a NavController until its first destination exists. */
internal fun shouldRedirectSupervisedRoute(
    supervisedEnabled: Boolean,
    parentAccessUnlocked: Boolean,
    currentRoute: String?,
): Boolean = currentRoute != null &&
    supervisedEnabled &&
    !isSupervisedRouteAllowed(currentRoute, parentAccessUnlocked)

/** A null route is Navigation's pre-graph bootstrap state, not a forbidden destination. */
internal fun isSupervisedRouteContentAllowed(
    supervisedEnabled: Boolean,
    parentAccessUnlocked: Boolean,
    currentRoute: String?,
): Boolean = currentRoute == null ||
    !supervisedEnabled ||
    isSupervisedRouteAllowed(currentRoute, parentAccessUnlocked)

/**
 * Cold-start gate for the app navigation graph.
 *
 * A null active connection is also the seed value used while [ConnectionStore]
 * is reading DataStore. Callers must therefore wait for the store's explicit
 * hydration signal before treating null as "no connection" and composing the
 * unrestricted onboarding/settings graph.
 */
internal fun isRelayNavigationHydrated(
    connectionStoreHydrated: Boolean,
    activeConnectionId: String?,
    supervisedPolicyHydrated: Boolean,
): Boolean = connectionStoreHydrated &&
    (activeConnectionId == null || supervisedPolicyHydrated)

/**
 * Keep the NavHost mounted while a new connection's supervised policy loads.
 * Setup and Dashboard sign-in expose no protected conversation/settings data,
 * so they may remain visible; every other route stays covered fail-closed.
 */
internal fun shouldCoverRelayNavigation(
    navigationHydrated: Boolean,
    routeContentAllowed: Boolean,
    currentRoute: String?,
): Boolean {
    if (!routeContentAllowed) return true
    if (navigationHydrated) return false
    return currentRoute?.substringBefore('?') !in setOf(
        Screen.Onboarding.route,
        Screen.Pair.route.substringBefore('?'),
        Screen.DashboardSignIn.route.substringBefore('?'),
    )
}

/** A parent unlock never follows the user back into the supervised chat root. */
internal fun shouldRelockParentAccess(
    supervisedEnabled: Boolean,
    parentAccessUnlocked: Boolean,
    route: String?,
): Boolean = supervisedEnabled &&
    parentAccessUnlocked &&
    route?.substringBefore('?') == "chat"

/**
 * External chat route arguments are untrusted. A session may be restored only
 * after an owner-aware source has proved that it belongs to the pinned profile.
 */
internal fun mayRestoreSupervisedSessionRoute(
    policy: SupervisedModePolicy,
    requestedSessionId: String?,
    requestedProfile: String?,
    pinnedProfileOwnershipProven: Boolean,
): Boolean = policy.isActive &&
    policy.capabilities.conversationHistory &&
    pinnedProfileOwnershipProven &&
    !requestedSessionId.isNullOrBlank() &&
    !requestedProfile.isNullOrBlank() &&
    requestedProfile.equals(policy.pinnedProfileName, ignoreCase = true)

internal data class SupervisedChatRouteArgs(
    val sessionId: String? = null,
    val profile: String? = null,
    val proactiveChatId: String? = null,
)

/** Strip external chat targeting before any destination effect can dispatch it. */
internal fun sanitizeSupervisedChatRouteArgs(
    policy: SupervisedModePolicy,
    args: SupervisedChatRouteArgs,
    pinnedProfileOwnershipProven: Boolean,
): SupervisedChatRouteArgs {
    if (!policy.enabled) return args
    val allowSession = mayRestoreSupervisedSessionRoute(
        policy = policy,
        requestedSessionId = args.sessionId,
        requestedProfile = args.profile,
        pinnedProfileOwnershipProven = pinnedProfileOwnershipProven,
    )
    return if (allowSession) {
        args.copy(proactiveChatId = null)
    } else {
        SupervisedChatRouteArgs()
    }
}

/** A disabled policy may become active only after the app-specific parent credential succeeds. */
internal fun mayEnableSupervisedMode(
    policy: SupervisedModePolicy,
    parentCredentialConfirmed: Boolean,
): Boolean = !policy.enabled &&
    policy.isConfigured &&
    parentCredentialConfirmed

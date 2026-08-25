package com.hermesandroid.relay.ui

import com.hermesandroid.relay.data.SupervisedModePolicy

internal data class ResolvedSupervisedTheme(
    val appThemeId: String,
    val themePreference: String,
    val useGlobalCustomTheme: Boolean,
)

/** Keep the supervised palette isolated from the parent's ordinary app theme. */
internal fun resolveSupervisedTheme(
    policy: SupervisedModePolicy,
    parentAccessUnlocked: Boolean,
    globalAppThemeId: String,
    globalThemePreference: String,
): ResolvedSupervisedTheme = if (policy.enabled && !parentAccessUnlocked) {
    ResolvedSupervisedTheme(
        appThemeId = policy.appearance.appThemeId,
        themePreference = policy.appearance.themePreference,
        useGlobalCustomTheme = false,
    )
} else {
    ResolvedSupervisedTheme(
        appThemeId = globalAppThemeId,
        themePreference = globalThemePreference,
        useGlobalCustomTheme = true,
    )
}

internal fun shouldShowPetInSupervisedMode(
    policy: SupervisedModePolicy,
    parentAccessUnlocked: Boolean,
): Boolean = !policy.enabled || parentAccessUnlocked || policy.appearance.showPet

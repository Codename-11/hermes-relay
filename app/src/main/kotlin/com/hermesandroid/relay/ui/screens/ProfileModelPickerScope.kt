package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.network.upstream.GatewayModelProvider

/**
 * A named GPT Web bridge profile owns a distinct provider catalog. Do not leak
 * other agent defaults (Terra, Luna, etc.) into its picker when the Gateway is
 * still loading or has returned a stale/unscoped catalog.
 *
 * Other profiles keep the unmodified upstream picker behavior. We cannot make
 * up a model when its catalog is unavailable: keep only the verified profile
 * default and let the normal error/Refresh UI request the real options.
 */
internal data class ProfileModelPickerScope(
    val providers: List<GatewayModelProvider>,
    val fallbackModelIds: List<String>,
    val showDefaultRow: Boolean,
    val isGptWebProfile: Boolean,
)

internal fun scopeProfileModelPicker(
    explicitProfileName: String?,
    profileDefaultModel: String?,
    providers: List<GatewayModelProvider>,
    fallbackModelIds: List<String>,
): ProfileModelPickerScope {
    val isGptWebProfile = !explicitProfileName.isNullOrBlank() &&
        profileDefaultModel?.startsWith("chatgpt-web/") == true
    if (!isGptWebProfile) {
        return ProfileModelPickerScope(providers, fallbackModelIds, showDefaultRow = true,
            isGptWebProfile = false)
    }
    val scoped = providers.filter { profileDefaultModel in it.models }
    return ProfileModelPickerScope(
        providers = scoped,
        fallbackModelIds = listOf(profileDefaultModel!!),
        showDefaultRow = scoped.isEmpty(),
        isGptWebProfile = true,
    )
}

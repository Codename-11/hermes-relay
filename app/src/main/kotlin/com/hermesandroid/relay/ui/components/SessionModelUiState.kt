package com.hermesandroid.relay.ui.components

/**
 * Model identity shown by chat-scoped controls.
 *
 * A running/resumed session owns its runtime model. Profile and server values
 * are defaults only, and may seed a fresh draft, but must never repaint an
 * existing session merely because a picker or Agent Passport was opened.
 */
internal data class SessionModelUiState(
    val model: String?,
    val provider: String?,
    val pickerModel: String?,
    val pickerProvider: String?,
    val inheritsProfileDefault: Boolean,
)

internal fun resolveSessionModelUiState(
    hasSession: Boolean,
    pendingModel: String?,
    pendingProvider: String?,
    gatewayModel: String?,
    gatewayProvider: String?,
    persistedSessionModel: String?,
    profileDefaultModel: String?,
    serverDefaultModel: String?,
): SessionModelUiState {
    fun String?.present(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    val pending = pendingModel.present()
    val gateway = gatewayModel.present()
    val persisted = persistedSessionModel.present()
    val profileDefault = profileDefaultModel.present()
    val serverDefault = serverDefaultModel.present()
    val effectiveModel = if (hasSession) {
        pending ?: gateway ?: persisted ?: profileDefault ?: serverDefault
    } else {
        pending ?: profileDefault ?: gateway ?: serverDefault
    }
    val effectiveProvider = pendingProvider.present()
        ?: gatewayProvider.present()

    return SessionModelUiState(
        model = effectiveModel,
        provider = effectiveProvider,
        // A live session highlights what it is actually running. On a fresh
        // draft, null remains the explicit "inherit profile default" state.
        pickerModel = if (hasSession) effectiveModel else pending,
        pickerProvider = if (hasSession) effectiveProvider else pendingProvider.present(),
        inheritsProfileDefault = !hasSession && pending == null,
    )
}

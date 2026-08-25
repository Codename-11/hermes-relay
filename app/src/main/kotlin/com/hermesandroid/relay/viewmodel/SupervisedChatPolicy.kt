package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.SupervisedModePolicy

/**
 * Fail-closed dispatch policy for Android Supervised Mode.
 *
 * This intentionally runs before demo handling, route selection, slash.exec,
 * command.dispatch, steering, and queueing. Kotlin's default trim recognizes
 * Unicode whitespace, preventing an indented slash command from bypassing the
 * client restriction.
 */
internal fun supervisedMessageBlockReason(
    policy: SupervisedModePolicy,
    text: String,
): String? {
    if (!policy.enabled) return null
    if (!policy.isConfigured) {
        return "Supervised mode is unavailable until the parent selects a profile."
    }
    if (text.trimStart().startsWith('/')) {
        return "Slash commands are unavailable in supervised mode."
    }
    return null
}

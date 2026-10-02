package com.hermesandroid.relay.ui

import com.hermesandroid.relay.notifications.ChatNotificationTarget

internal enum class ChatNotificationNavigationStep { Wait, Reject, SwitchConnection, SelectProfile, Open }

/** Never resolve a notification against a different connection or a restoring profile picker. */
internal fun chatNotificationNavigationStep(
    target: ChatNotificationTarget,
    hydrated: Boolean,
    allowed: Boolean,
    connectionIds: Set<String>,
    activeConnectionId: String?,
    profileSelectionSettled: Boolean,
    selectedProfile: String?,
    profileNames: Set<String>,
): ChatNotificationNavigationStep = when {
    !hydrated -> ChatNotificationNavigationStep.Wait
    !allowed || target.connectionId !in connectionIds -> ChatNotificationNavigationStep.Reject
    activeConnectionId != target.connectionId -> ChatNotificationNavigationStep.SwitchConnection
    !profileSelectionSettled -> ChatNotificationNavigationStep.Wait
    target.profile != null && target.profile !in profileNames -> ChatNotificationNavigationStep.Wait
    selectedProfile != target.profile -> ChatNotificationNavigationStep.SelectProfile
    else -> ChatNotificationNavigationStep.Open
}

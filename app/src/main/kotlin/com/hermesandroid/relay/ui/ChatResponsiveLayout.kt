package com.hermesandroid.relay.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Width policy shared by Chat's content and persistent status surfaces. */
internal data class ChatResponsiveLayout(
    val introMaxWidth: Dp?,
    val avatarSize: Dp?,
    val chromeMaxWidth: Dp?,
    val transcriptMaxWidth: Dp?,
    val focusVoiceMaxWidth: Dp?,
)

internal fun chatResponsiveLayout(screenWidthDp: Int): ChatResponsiveLayout = when {
    screenWidthDp >= 840 -> ChatResponsiveLayout(
        introMaxWidth = 720.dp,
        avatarSize = 360.dp,
        chromeMaxWidth = 960.dp,
        transcriptMaxWidth = 960.dp,
        focusVoiceMaxWidth = 1120.dp,
    )
    screenWidthDp >= 600 -> ChatResponsiveLayout(
        introMaxWidth = 600.dp,
        avatarSize = 300.dp,
        chromeMaxWidth = 760.dp,
        transcriptMaxWidth = 760.dp,
        focusVoiceMaxWidth = 760.dp,
    )
    else -> ChatResponsiveLayout(
        introMaxWidth = null,
        avatarSize = null,
        chromeMaxWidth = null,
        transcriptMaxWidth = null,
        focusVoiceMaxWidth = null,
    )
}

internal fun useSplitVoiceLayout(screenWidthDp: Int, screenHeightDp: Int): Boolean =
    screenWidthDp >= 840 && screenWidthDp > screenHeightDp

package com.hermesandroid.relay.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Width policy shared by Chat's content and persistent status surfaces. */
internal data class ChatResponsiveLayout(
    val introMaxWidth: Dp?,
    val avatarSize: Dp?,
    val chromeMaxWidth: Dp?,
)

internal fun chatResponsiveLayout(screenWidthDp: Int): ChatResponsiveLayout = when {
    screenWidthDp >= 840 -> ChatResponsiveLayout(
        introMaxWidth = 720.dp,
        avatarSize = 360.dp,
        chromeMaxWidth = 960.dp,
    )
    screenWidthDp >= 600 -> ChatResponsiveLayout(
        introMaxWidth = 600.dp,
        avatarSize = 300.dp,
        chromeMaxWidth = 760.dp,
    )
    else -> ChatResponsiveLayout(
        introMaxWidth = null,
        avatarSize = null,
        chromeMaxWidth = null,
    )
}

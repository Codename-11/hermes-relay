package com.hermesandroid.relay.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Information-density overlay, independent of the color theme.
 *
 * [CLASSIC] is the shipped dense layout. [CLEAN] is a progressive-disclosure
 * layout modeled on mainstream messaging apps: assistant replies read as
 * full-width prose without a filled bubble, the per-turn sender label and
 * per-bubble metadata (time, tokens, "Delivered") hide until the message is
 * tapped, consecutive routine tool calls fold into one summary row, header
 * shortcuts collapse into the overflow menu, and the persistent footer status
 * strip only appears while the connection needs attention.
 */
enum class LayoutStyle(val id: String) {
    CLASSIC("classic"),
    CLEAN("clean");

    companion object {
        val DEFAULT = CLASSIC

        fun fromId(id: String?): LayoutStyle = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

val LocalLayoutStyle = staticCompositionLocalOf { LayoutStyle.DEFAULT }

/** True when the Clean progressive-disclosure layout is active. */
val isCleanLayout: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalLayoutStyle.current == LayoutStyle.CLEAN

package com.hermesandroid.relay.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Information-density overlay, independent of the color theme.
 *
 * [CLEAN] is the default progressive-disclosure layout modeled on mainstream
 * messaging apps and Material 3 Expressive: assistant replies read as
 * full-width prose without a filled bubble, the per-turn sender label and
 * per-bubble metadata (time, tokens, "Delivered") hide until the message is
 * tapped, consecutive routine tool calls fold into one summary row, header
 * shortcuts collapse into the overflow menu, the composer is a borderless
 * pill, motion uses expressive springs, and the footer status strip only
 * appears while the connection is recovering. [CLASSIC] is the dense layout,
 * kept as an opt-in.
 */
enum class LayoutStyle(val id: String) {
    CLASSIC("classic"),
    CLEAN("clean");

    companion object {
        /** Layout for installs that never picked one. */
        val DEFAULT = CLEAN

        fun fromId(id: String?): LayoutStyle = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Composition default stays [LayoutStyle.CLASSIC] so components hosted outside
 * [HermesRelayTheme] (previews, isolated UI tests) keep their historical look;
 * the app root always provides the persisted choice.
 */
val LocalLayoutStyle = staticCompositionLocalOf { LayoutStyle.CLASSIC }

/** True when the Clean progressive-disclosure layout is active. */
val isCleanLayout: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalLayoutStyle.current == LayoutStyle.CLEAN

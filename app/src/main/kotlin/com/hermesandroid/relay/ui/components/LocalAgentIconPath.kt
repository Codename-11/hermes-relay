package com.hermesandroid.relay.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Path to the active profile's local agent icon (client-side, keyed per
 * `(connection, profile)` — see `ProfileIconStore`). Provided at the app root
 * from `ConnectionViewModel.profileIcon` and read by [MessageBubble] to show a
 * small avatar beside the agent name. Null = no icon set for this profile.
 */
val LocalAgentIconPath = staticCompositionLocalOf<String?> { null }

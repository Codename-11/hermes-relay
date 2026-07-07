package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayMetadataStyle

/** A session's originating gateway/source, classified for a drawer badge. */
data class SourceBadge(val label: String, val color: Color)

/**
 * The app's own chats — no badge (they're "your" conversations from this app /
 * desktop / API, not a distinct gateway lane).
 */
private val OWN_CHAT_SOURCES = setOf("tui", "api_server", "cli", "local", "")

/**
 * Map a session `source` to a drawer badge, or null for the app's own chats and
 * the phone **Thread** (which renders its own thread-spool chip). External
 * gateways — discord / telegram / slack / cron / webhook / web / … — each get a
 * small colored chip so the drawer reads like the desktop's per-channel tags.
 * Confirmed live sources: tui, cli, api_server, web, discord, telegram, cron,
 * webhook, phone.
 */
fun sourceBadge(source: String?): SourceBadge? {
    val s = source?.trim()?.lowercase() ?: return null
    if (s.isBlank() || s in OWN_CHAT_SOURCES || s == "phone") return null
    return when (s) {
        "discord" -> SourceBadge("Discord", Color(0xFF5865F2))
        "telegram" -> SourceBadge("Telegram", Color(0xFF229ED9))
        "slack" -> SourceBadge("Slack", Color(0xFF8E3A93))
        "cron" -> SourceBadge("Cron", Color(0xFFB78A2E))
        "webhook" -> SourceBadge("Webhook", Color(0xFF2E9B8F))
        "web" -> SourceBadge("Web", Color(0xFF6E7787))
        else -> SourceBadge(s.replaceFirstChar { it.uppercase() }, RelayRefresh.Relay)
    }
}

/** Small colored source chip (e.g. "Discord", "Cron") for a drawer row. */
@Composable
fun SourceChip(badge: SourceBadge, modifier: Modifier = Modifier) {
    Text(
        text = badge.label,
        style = relayMetadataStyle(),
        color = badge.color,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(badge.color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Quiet amber "Beta" chip — Threads is feature-complete enough to use but
 *  gated below a full release (live `/api/ws` foreground transport, unread, etc). */
@Composable
fun BetaChip(modifier: Modifier = Modifier) {
    Text(
        text = "Beta",
        style = relayMetadataStyle(),
        color = RelayRefresh.Amber,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(RelayRefresh.Amber.copy(alpha = 0.18f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.network.relay.ConnectionState
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.upstream.ServerCapabilities

// ---------------------------------------------------------------------------
// Session-path summary — the glanceable "which transport/route am I on" view
// that sits at the TOP of the agent sheet's Connection section.
//
// Design intent (2026-06-18 connection-clarity pass):
//   - The user couldn't tell which transport/path a session used: the only
//     surface was a collapsed "Show routes" expander showing the RAW enum
//     ("Streaming: ENHANCED_HERMES"), which is meaningless and doesn't even
//     name the gateway (live-thinking) path.
//   - So: a friendly one-line summary always visible, an honest capability
//     chip strip beneath it, and the richer technical detail folded into the
//     SAME (single) expander — progressive disclosure, low density.
//
// Honesty principle (matches the rest of this sheet — GatewayToggleControl /
// LoadedFadeIn / pickerListsSettling): never imply a capability that isn't
// confirmed. A chip only renders when its own signal says the capability is
// live on THIS session. Standard upstream features that are simply off on this
// transport render muted with a reason in the detail list rather than vanish.
// ---------------------------------------------------------------------------

/**
 * Resolved transport identifiers as returned by
 * `ConnectionViewModel.resolveStreamingEndpoint(...)`:
 * `"gateway"`, `"sessions"`, `"completions"`, `"runs"`. Anything else is
 * treated as the generic SSE case. Translated to a friendly name here so the
 * raw token NEVER reaches the user.
 */
internal data class SessionPathTransport(
    val friendlyName: String,
    val descriptor: String,
    val icon: ImageVector?,
    /** True only for the gateway transport — gates the "Live thinking" chip. */
    val isGateway: Boolean,
)

internal fun sessionPathTransport(resolvedTransport: String): SessionPathTransport =
    when (resolvedTransport) {
        "gateway" -> SessionPathTransport(
            friendlyName = "Gateway",
            descriptor = "live thinking",
            icon = Icons.Filled.Bolt,
            isGateway = true,
        )
        "sessions" -> SessionPathTransport(
            friendlyName = "Sessions API",
            descriptor = "streaming",
            icon = null,
            isGateway = false,
        )
        "completions" -> SessionPathTransport(
            friendlyName = "Chat Completions",
            descriptor = "streaming",
            icon = null,
            isGateway = false,
        )
        "runs" -> SessionPathTransport(
            friendlyName = "Runs API",
            descriptor = "structured streaming",
            icon = null,
            isGateway = false,
        )
        else -> SessionPathTransport(
            friendlyName = "Direct chat",
            descriptor = "streaming",
            icon = null,
            isGateway = false,
        )
    }

/**
 * One capability the session may or may not expose, paired with whether it is
 * actually available right now. [available] is set strictly from a confirming
 * signal — never a guess. [reason] explains an unavailable standard feature so
 * the detail list can show it muted instead of hiding it (never-hide principle).
 */
internal data class SessionCapability(
    val label: String,
    val available: Boolean,
    val reason: String? = null,
)

/**
 * Build the honest capability list for the current session.
 *
 * Gating signals (each must positively CONFIRM availability):
 *  - Live thinking → only the gateway transport streams `reasoning.delta`
 *    live. SSE paths only get post-hoc reasoning, so this is gateway-only.
 *    While the gateway is still being probed (Unknown) we surface it as a
 *    "checking" reason rather than a confirmed chip.
 *  - Media / Terminal → require the relay to be CONNECTED (the relay brokers
 *    those channels). A configured-but-disconnected relay is not enough.
 *  - Voice → `voiceReady` (standard dashboard voice OR relay voice — whichever
 *    the connection actually has).
 */
internal fun sessionCapabilities(
    transport: SessionPathTransport,
    gatewayAvailability: GatewayAvailability,
    relayConnected: Boolean,
    relayConfigured: Boolean,
    voiceReady: Boolean,
    threadsActive: Boolean = false,
): List<SessionCapability> {
    val liveThinkingReason = when {
        transport.isGateway -> null
        gatewayAvailability == GatewayAvailability.Unknown -> "Checking gateway…"
        gatewayAvailability == GatewayAvailability.SignInRequired ->
            "Sign in under Manage for the live-thinking gateway."
        else -> "Available on the gateway transport — this session streams over the API server."
    }
    return listOf(
        SessionCapability(
            label = "Live thinking",
            available = transport.isGateway,
            reason = liveThinkingReason,
        ),
        SessionCapability(
            label = "Media",
            available = relayConnected,
            reason = when {
                relayConnected -> null
                relayConfigured -> "Relay paired but not connected."
                else -> "Pair the relay to send and receive media."
            },
        ),
        SessionCapability(
            label = "Terminal",
            available = relayConnected,
            reason = when {
                relayConnected -> null
                relayConfigured -> "Relay paired but not connected."
                else -> "Pair the relay for terminal access."
            },
        ),
        SessionCapability(
            label = "Voice",
            available = voiceReady,
            reason = if (voiceReady) null else "Voice not ready on this connection.",
        ),
        SessionCapability(
            label = "Threads",
            available = threadsActive,
            reason = if (threadsActive) {
                null
            } else {
                "Pair the relay and turn on “Let Hermes message me” so the agent can open Threads."
            },
        ),
    )
}

/**
 * The always-visible session-path summary that anchors the Connection section:
 * a friendly transport + route line, then a strip of chips for capabilities
 * that are CONFIRMED available right now (unavailable ones are omitted here and
 * shown — with a reason — only in the expandable detail).
 *
 * @param routeLabel friendly route ("LAN" / "Tailscale" / "Public" / host) —
 *        already resolved by the caller from `activeEndpoint.displayLabel()`
 *        with a host fallback.
 */
@Composable
internal fun SessionPathSummary(
    transport: SessionPathTransport,
    routeLabel: String?,
    capabilities: List<SessionCapability>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Summary line: ⚡ Gateway · LAN — live thinking
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (transport.icon != null) {
                Icon(
                    imageVector = transport.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            val routeSuffix = routeLabel?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            Text(
                text = buildString {
                    append(transport.friendlyName)
                    append(routeSuffix)
                    if (transport.descriptor.isNotBlank()) {
                        append(" — ")
                        append(transport.descriptor)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        // Honest capability chips — only the confirmed-available ones.
        val activeCaps = capabilities.filter { it.available }
        if (activeCaps.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                activeCaps.forEach { cap ->
                    CapabilityChip(label = cap.label)
                }
            }
        }
    }
}

/** Small "this capability is live" pill. Quiet, on-brand — primaryContainer. */
@Composable
private fun CapabilityChip(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * The single expandable "Session details" block that ABSORBS the old "Show
 * routes" expander. Header toggles; expanded body shows the friendly transport,
 * route, relay state, the full honest capability list (✓ / —), and the
 * technical URL rows. There is intentionally ONE expander here — callers must
 * not also render the legacy routes block.
 */
@Composable
internal fun SessionPathDetails(
    transport: SessionPathTransport,
    routeLabel: String?,
    relayConnectionState: ConnectionState,
    capabilities: List<SessionCapability>,
    apiServerUrl: String,
    relayUrl: String,
    streamingEndpoint: String,
    gatewayAvailability: GatewayAvailability,
    serverCapabilities: ServerCapabilities,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "Hide session details" else "Session details",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = if (expanded) "Hide session details" else "Show session details",
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow(label = "Transport", value = transport.friendlyName)
                DetailRow(label = "Route", value = routeLabel?.takeIf { it.isNotBlank() } ?: "—")
                DetailChipRow(label = "Relay state") {
                    SessionPathConnectionChip(relayConnectionState)
                }

                Spacer(modifier = Modifier.size(2.dp))

                // Full honest capability list — ✓ for live, — for unavailable
                // (with the reason), so a standard feature is never hidden.
                capabilities.forEach { cap ->
                    CapabilityDetailRow(cap)
                }

                Spacer(modifier = Modifier.size(4.dp))

                // Transport tier ladder (basic → best) — shows every chat path,
                // which one is active and why, and which the server doesn't expose.
                TransportTierStepper(
                    streamingEndpoint = streamingEndpoint,
                    gatewayAvailability = gatewayAvailability,
                    serverCapabilities = serverCapabilities,
                )

                Spacer(modifier = Modifier.size(2.dp))

                DetailRow(label = "API", value = apiServerUrl, monospace = true)
                DetailRow(label = "Relay", value = relayUrl, monospace = true)
            }
        }
    }
}

/**
 * Vertical "transport path" ladder, basic → best:
 * Completions → Runs → Sessions → Gateway. The active tier is filled +
 * highlighted; tiers the server doesn't expose render muted; the resolver's
 * reason ("auto → Gateway (best)" / "gateway unavailable → Sessions") is shown
 * beneath. Uses the same [resolveChatTransportStatus] the status badge does, so
 * the drawer and the badge can never disagree.
 */
@Composable
internal fun TransportTierStepper(
    streamingEndpoint: String,
    gatewayAvailability: GatewayAvailability,
    serverCapabilities: ServerCapabilities,
    modifier: Modifier = Modifier,
) {
    val status = remember(streamingEndpoint, gatewayAvailability, serverCapabilities) {
        resolveChatTransportStatus(streamingEndpoint, gatewayAvailability, serverCapabilities)
    }
    // basic → best, paired with whether THIS server exposes the tier.
    val tiers = listOf(
        Triple(ChatTransportTier.Completions, "Chat Completions", serverCapabilities.portable),
        Triple(ChatTransportTier.Runs, "Runs API", serverCapabilities.runs),
        Triple(ChatTransportTier.Sessions, "Sessions API", serverCapabilities.sessionsChatStream),
        Triple(
            ChatTransportTier.Gateway,
            "Gateway · live thinking",
            gatewayAvailability == GatewayAvailability.Ready,
        ),
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Transport path  ·  basic → best",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(6.dp))
        tiers.forEachIndexed { index, (tier, name, available) ->
            TransportTierRow(
                name = name,
                available = available,
                isActive = status.tier == tier,
                isLast = index == tiers.lastIndex,
            )
        }
        Spacer(modifier = Modifier.size(2.dp))
        Text(
            text = status.reason,
            style = MaterialTheme.typography.labelSmall,
            color = when (status.tone) {
                ChatTransportTone.Active -> MaterialTheme.colorScheme.primary
                ChatTransportTone.Fallback -> MaterialTheme.colorScheme.tertiary
                ChatTransportTone.Unavailable -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun TransportTierRow(
    name: String,
    available: Boolean,
    isActive: Boolean,
    isLast: Boolean,
) {
    val nodeFill = when {
        isActive -> MaterialTheme.colorScheme.primary
        available -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    }
    Row(verticalAlignment = Alignment.Top) {
        // Rail: tier node + connector down to the next tier.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(if (isActive) 12.dp else 9.dp)
                    .clip(RoundedCornerShape(50))
                    .background(nodeFill),
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)),
                )
            }
        }
        Spacer(modifier = Modifier.size(10.dp))
        Column(modifier = Modifier.padding(bottom = if (isLast) 0.dp else 6.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (available || isActive) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = when {
                    isActive -> "active"
                    available -> "available"
                    else -> "not exposed by this server"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

// --- Local row primitives (kept private so SessionPathCard is self-contained;
//     mirror the shared InfoRow/ChipRow look in ConnectionInfoSheet) ----------

@Composable
private fun DetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .wrapContentWidth(Alignment.End),
        )
    }
}

@Composable
private fun DetailChipRow(label: String, chip: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        chip()
    }
}

@Composable
private fun CapabilityDetailRow(cap: SessionCapability) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (cap.available) Icons.Filled.Check else Icons.Filled.Remove,
            contentDescription = if (cap.available) "Available" else "Unavailable",
            tint = if (cap.available) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cap.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (cap.available) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            // Unavailable standard features explain WHY rather than vanish.
            if (!cap.available && cap.reason != null) {
                Text(
                    text = cap.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Compact relay-connection chip (local twin of ConnectionInfoSheet's). */
@Composable
private fun SessionPathConnectionChip(state: ConnectionState) {
    val (label, bg, fg) = when (state) {
        ConnectionState.Connected -> Triple(
            "Connected",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        ConnectionState.Connecting -> Triple(
            "Connecting…",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        ConnectionState.Reconnecting -> Triple(
            "Reconnecting…",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        ConnectionState.Disconnected -> Triple(
            "Disconnected",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

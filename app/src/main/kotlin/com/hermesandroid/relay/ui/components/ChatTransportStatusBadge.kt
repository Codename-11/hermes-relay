package com.hermesandroid.relay.ui.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.upstream.ServerCapabilities
import com.hermesandroid.relay.ui.theme.RelayRefresh

enum class ChatTransportTier(val endpointId: String, val label: String) {
    Gateway("gateway", "⚡ Gateway"),
    Sessions("sessions", "📡 Sessions"),
    Completions("completions", "Completions"),
    Runs("runs", "Runs"),
    Offline("offline", "offline"),
}

enum class ChatTransportTone {
    Active,
    Fallback,
    Unavailable,
}

data class ChatTransportStatus(
    val tier: ChatTransportTier,
    val tone: ChatTransportTone,
    val reason: String,
    val detail: String,
) {
    val available: Boolean
        get() = tone != ChatTransportTone.Unavailable && tier != ChatTransportTier.Offline
}

fun resolveChatTransportStatus(
    streamingEndpoint: String,
    gatewayAvailability: GatewayAvailability,
    serverCapabilities: ServerCapabilities,
): ChatTransportStatus {
    val preference = streamingEndpoint.trim().lowercase()
    val gatewayReady = gatewayAvailability == GatewayAvailability.Ready

    fun unavailable(tier: ChatTransportTier, reason: String): ChatTransportStatus =
        ChatTransportStatus(
            tier = tier,
            tone = ChatTransportTone.Unavailable,
            reason = reason,
            detail = "${tier.label}: unavailable on the current connection.",
        )

    fun offline(reason: String = "offline"): ChatTransportStatus =
        ChatTransportStatus(
            tier = ChatTransportTier.Offline,
            tone = ChatTransportTone.Unavailable,
            reason = reason,
            detail = "No reachable Hermes chat transport is available.",
        )

    fun sseFallback(gatewayReason: String): ChatTransportStatus {
        if (!serverCapabilities.healthy) return offline(gatewayReason)
        val tier = preferredAvailableSseTier(serverCapabilities)
            ?: return offline(gatewayReason)
        return ChatTransportStatus(
            tier = tier,
            tone = ChatTransportTone.Fallback,
            reason = "$gatewayReason → ${tier.plainName()}",
            detail = "${tier.detailText()} Using this as the fallback while Gateway is unavailable.",
        )
    }

    fun manualSse(tier: ChatTransportTier, supported: Boolean): ChatTransportStatus {
        if (!serverCapabilities.healthy) return offline()
        return if (supported) {
            ChatTransportStatus(
                tier = tier,
                tone = ChatTransportTone.Active,
                reason = "${tier.plainName()} selected",
                detail = tier.detailText(),
            )
        } else {
            unavailable(tier, "${tier.plainName()} unavailable")
        }
    }

    return when (preference) {
        "auto" -> when {
            gatewayReady -> ChatTransportStatus(
                tier = ChatTransportTier.Gateway,
                tone = ChatTransportTone.Active,
                reason = "auto → Gateway (best)",
                detail = ChatTransportTier.Gateway.detailText(),
            )
            else -> sseFallback(gatewayFallbackReason(gatewayAvailability))
        }
        "gateway" -> when {
            gatewayReady -> ChatTransportStatus(
                tier = ChatTransportTier.Gateway,
                tone = ChatTransportTone.Active,
                reason = "Gateway selected",
                detail = ChatTransportTier.Gateway.detailText(),
            )
            else -> sseFallback(gatewayFallbackReason(gatewayAvailability))
        }
        "sessions" -> manualSse(ChatTransportTier.Sessions, serverCapabilities.sessionsChatStream)
        "completions" -> manualSse(ChatTransportTier.Completions, serverCapabilities.portable)
        "runs" -> manualSse(ChatTransportTier.Runs, serverCapabilities.runs)
        else -> offline()
    }
}

private fun preferredAvailableSseTier(capabilities: ServerCapabilities): ChatTransportTier? =
    when {
        capabilities.sessionsChatStream -> ChatTransportTier.Sessions
        capabilities.portable -> ChatTransportTier.Completions
        capabilities.runs -> ChatTransportTier.Runs
        else -> null
    }

private fun gatewayFallbackReason(availability: GatewayAvailability): String =
    when (availability) {
        GatewayAvailability.SignInRequired -> "gateway sign-in required"
        GatewayAvailability.Unreachable -> "gateway unavailable"
        GatewayAvailability.Unsupported -> "gateway unsupported"
        GatewayAvailability.Unknown -> "checking gateway"
        GatewayAvailability.Ready -> "gateway ready"
    }

private fun ChatTransportTier.plainName(): String =
    when (this) {
        ChatTransportTier.Gateway -> "Gateway"
        ChatTransportTier.Sessions -> "Sessions"
        ChatTransportTier.Completions -> "Completions"
        ChatTransportTier.Runs -> "Runs"
        ChatTransportTier.Offline -> "offline"
    }

private fun ChatTransportTier.detailText(): String =
    when (this) {
        ChatTransportTier.Gateway ->
            "Gateway uses the dashboard WebSocket /api/ws for live thinking and rich tool events."
        ChatTransportTier.Sessions ->
            "Sessions uses /api/sessions/{id}/chat/stream with server-side session history."
        ChatTransportTier.Completions ->
            "Completions uses OpenAI-compatible SSE at /v1/chat/completions."
        ChatTransportTier.Runs ->
            "Runs uses /v1/runs plus streamed run events."
        ChatTransportTier.Offline ->
            "No chat transport is reachable."
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatTransportStatusBadge(
    status: ChatTransportStatus,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val textColor = status.textColor()
    val background = status.backgroundColor()
    Surface(
        modifier = modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = {
                Toast.makeText(
                    context,
                    "${status.reason}: ${status.detail}",
                    Toast.LENGTH_LONG,
                ).show()
            },
        ),
        shape = RoundedCornerShape(999.dp),
        color = background,
        contentColor = textColor,
    ) {
        Text(
            text = status.tier.label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = textColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun ChatTransportStatusBadge(
    streamingEndpoint: String,
    gatewayAvailability: GatewayAvailability,
    serverCapabilities: ServerCapabilities,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val status = remember(streamingEndpoint, gatewayAvailability, serverCapabilities) {
        resolveChatTransportStatus(
            streamingEndpoint = streamingEndpoint,
            gatewayAvailability = gatewayAvailability,
            serverCapabilities = serverCapabilities,
        )
    }
    ChatTransportStatusBadge(
        status = status,
        modifier = modifier,
        onClick = onClick,
    )
}

@Composable
fun ChatTransportStatus.textColor(): Color =
    when (tone) {
        ChatTransportTone.Active -> RelayRefresh.Green
        ChatTransportTone.Fallback -> RelayRefresh.Amber
        ChatTransportTone.Unavailable -> RelayRefresh.Muted
    }

@Composable
private fun ChatTransportStatus.backgroundColor(): Color =
    when (tone) {
        ChatTransportTone.Active -> RelayRefresh.Green.copy(alpha = 0.12f)
        ChatTransportTone.Fallback -> RelayRefresh.Amber.copy(alpha = 0.14f)
        ChatTransportTone.Unavailable -> RelayRefresh.Navy3.copy(alpha = 0.72f)
    }

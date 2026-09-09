package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.network.upstream.GatewayConnectionState
import com.hermesandroid.relay.viewmodel.ConnectionStepState

@Composable
internal fun Modifier.chatDebugHeaderGesture(enabled: Boolean, onClick: () -> Unit, onHold: () -> Unit): Modifier =
    combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onHold,
        onLongClickLabel = stringResource(R.string.chat_debug_open))

@Composable
internal fun BoxScope.ChatDebugOverlay(
    visible: Boolean,
    headerHeight: Dp,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(enabled = visible, onBack = onClose)
    if (visible) {
        Box(Modifier.fillMaxSize().padding(top = headerHeight)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f))
            .clickable(onClick = onClose))
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = headerHeight).fillMaxWidth().clipToBounds(),
    ) { content() }
}

/** Read-only snapshot of the visible conversation; opening it sends no RPCs. */
@Composable
internal fun ChatDebugDrawer(
    profile: String,
    model: String,
    sessionId: String?,
    gateway: Boolean,
    signedIn: Boolean,
    signInRequired: Boolean,
    socketState: GatewayConnectionState,
    preparing: Boolean,
    streaming: Boolean,
    loadingHistory: Boolean,
    directoryUnavailable: Boolean,
    failure: String?,
    onClose: () -> Unit,
    onConnections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.chat_debug_title)
    val safeFailure = DiagnosticsLog.redactReportText(failure)?.take(600)
    val ready = socketState == GatewayConnectionState.Ready
    val steps = buildList {
        if (gateway) {
            add(ConnectionSetupTimelineStep(
                stringResource(R.string.chat_debug_sign_in),
                stringResource(when {
                    signInRequired -> R.string.chat_debug_sign_in_needed
                    signedIn -> R.string.chat_debug_authenticated
                    else -> R.string.chat_debug_auth_unknown
                }),
                when {
                    signInRequired -> ConnectionStepState.Failed
                    signedIn -> ConnectionStepState.Done
                    else -> ConnectionStepState.Pending
                },
            ))
            add(ConnectionSetupTimelineStep(
                stringResource(R.string.chat_debug_gateway),
                stringResource(when (socketState) {
                    GatewayConnectionState.Ready -> R.string.chat_debug_socket_ready
                    GatewayConnectionState.MintingTicket -> R.string.chat_debug_ticket
                    GatewayConnectionState.Connecting -> R.string.chat_debug_socket_connecting
                    GatewayConnectionState.AwaitingReady -> R.string.chat_debug_socket_waiting
                    GatewayConnectionState.Idle -> R.string.chat_debug_socket_idle
                }),
                when {
                    ready -> ConnectionStepState.Done
                    signInRequired -> ConnectionStepState.Failed
                    socketState != GatewayConnectionState.Idle -> ConnectionStepState.Active
                    else -> ConnectionStepState.Pending
                },
            ))
        }
        add(ConnectionSetupTimelineStep(
            stringResource(R.string.chat_debug_session),
            stringResource(when {
                preparing -> R.string.chat_debug_preparing_detail
                loadingHistory -> R.string.chat_debug_history_loading
                directoryUnavailable -> R.string.chat_debug_history_failed
                sessionId != null -> R.string.chat_debug_session_selected
                else -> R.string.chat_debug_session_new
            }),
            when {
                preparing || loadingHistory -> ConnectionStepState.Active
                directoryUnavailable -> ConnectionStepState.Failed
                sessionId != null -> ConnectionStepState.Done
                else -> ConnectionStepState.Pending
            },
        ))
        add(ConnectionSetupTimelineStep(
            stringResource(R.string.chat_debug_response),
            stringResource(when {
                safeFailure != null -> R.string.chat_debug_response_failed
                preparing -> R.string.chat_debug_response_waiting
                streaming -> R.string.chat_debug_response_active
                else -> R.string.chat_debug_response_idle
            }),
            when {
                safeFailure != null -> ConnectionStepState.Failed
                preparing -> ConnectionStepState.Pending
                streaming -> ConnectionStepState.Active
                else -> ConnectionStepState.Pending
            },
        ))
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { paneTitle = title },
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(listOf(if (gateway) "Gateway" else "Direct API", profile, model)
                        .filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, stringResource(R.string.chat_debug_close))
                }
            }
            ConnectionSetupTimeline(steps)
            if (safeFailure != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(safeFailure, style = MaterialTheme.typography.bodySmall)
                        if (safeFailure.contains("agent init failed", ignoreCase = true)) {
                            Text(stringResource(R.string.chat_debug_init_recovery), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.chat_debug_session_id, sessionId ?: "—"),
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onConnections) { Text(stringResource(R.string.chat_debug_connections)) }
        }
    }
}

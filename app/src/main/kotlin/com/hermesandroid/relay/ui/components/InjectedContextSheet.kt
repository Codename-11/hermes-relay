package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.viewmodel.ChatViewModel

/**
 * Bottom-sheet audit of the exact extra context the agent is injected with on
 * the next turn — opened by tapping the chat [ContextMeterBar].
 *
 * Renders the SAME [ChatViewModel.InjectedContext] the send path builds (via
 * [ChatViewModel.previewInjectedContext] → `composeInjectedContext`), so it is
 * a faithful audit, not a re-derivation that could drift. Empty blocks show a
 * labeled note instead of vanishing, and the gateway's server-side persona is
 * explicitly called out as not-sent-from-this-device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InjectedContextSheet(
    context: ChatViewModel.InjectedContext,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.injected_context_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.injected_context_subtitle, context.transport),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            val personaTitle = stringResource(R.string.injected_context_persona_title)
            val phoneStatusTitle = stringResource(R.string.injected_context_phone_status_title)
            val mediaTitle = stringResource(R.string.injected_context_media_title)
            val relayTitle = stringResource(R.string.injected_context_relay_title)
            val turnTitle = stringResource(R.string.injected_context_turn_title)
            val personaServerSide = stringResource(R.string.injected_context_persona_server_side)
            val personaNotSet = stringResource(R.string.injected_context_persona_not_set)
            val phoneStatusNotSet = stringResource(R.string.injected_context_phone_status_not_set)
            val mediaRelayActive = stringResource(R.string.injected_context_media_relay_active)
            val mediaNoRelay = stringResource(R.string.injected_context_media_no_relay)
            val relayNotSet = stringResource(R.string.injected_context_relay_not_set)
            val turnNotSet = stringResource(R.string.injected_context_turn_not_set)

            ContextSection(
                title = personaTitle,
                body = context.personaPrompt,
                emptyNote = if (context.personaOwnedServerSide) {
                    personaServerSide
                } else {
                    personaNotSet
                },
            )
            ContextSection(
                title = phoneStatusTitle,
                body = context.appContext,
                emptyNote = phoneStatusNotSet,
            )
            ContextSection(
                title = mediaTitle,
                body = context.mediaCapability,
                emptyNote = if (context.relayMediaAvailable) {
                    mediaRelayActive
                } else {
                    mediaNoRelay
                },
            )
            ContextSection(
                title = relayTitle,
                body = context.relayServerBlocks
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("\n\n") { (name, text) ->
                        "[$name]\n$text"
                    },
                emptyNote = relayNotSet,
            )
            ContextSection(
                title = turnTitle,
                body = context.interfaceContext,
                emptyNote = turnNotSet,
            )
        }
    }
}

@Composable
private fun ContextSection(
    title: String,
    body: String?,
    emptyNote: String,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
    if (body.isNullOrBlank()) {
        Text(
            text = emptyNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(10.dp),
                )
                .padding(12.dp),
        )
    }
    Spacer(Modifier.height(18.dp))
}

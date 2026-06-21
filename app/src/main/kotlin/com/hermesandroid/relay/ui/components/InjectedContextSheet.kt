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
                text = "What the agent sees",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "The exact extra context prepended to your next turn, for " +
                    "transparency. Transport: ${context.transport}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            ContextSection(
                title = "Persona / profile",
                body = context.personaPrompt,
                emptyNote = if (context.personaOwnedServerSide) {
                    "Added server-side by Hermes (profile soul + personality " +
                        "overlay) — not sent from this device."
                } else {
                    "No persona prompt is being sent — the server uses its " +
                        "configured default."
                },
            )
            ContextSection(
                title = "Phone status",
                body = context.appContext,
                emptyNote = "No phone-status block — enable it in App Context settings.",
            )
            ContextSection(
                title = "Media capability",
                body = context.mediaCapability,
                emptyNote = if (context.relayMediaAvailable) {
                    // Gateway path: media WORKS (client renders server-local images
                    // via the relay) — just no injected hint, since the gateway has
                    // no per-turn system slot. Say so, rather than "not set".
                    "Relay route active — server-local images and files render " +
                        "in-app via the relay (client-side). The gateway transport " +
                        "has no system slot, so no hint is injected here, but media " +
                        "still works."
                } else {
                    "No relay route configured — the agent can't fetch server-local " +
                        "images or files by path."
                },
            )
            ContextSection(
                title = "Relay context (server-side)",
                body = context.relayServerBlocks
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("\n\n") { (name, text) ->
                        "[$name]\n$text"
                    },
                emptyNote = "No relay server-side context blocks are active, or the relay " +
                    "context layer is disabled.",
            )
            ContextSection(
                title = "This turn",
                body = context.interfaceContext,
                emptyNote = "Nothing extra for a typed turn. Voice turns add a " +
                    "spoken-output hint here.",
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

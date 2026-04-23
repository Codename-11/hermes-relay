package com.hermesandroid.relay.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.HermesCard
import com.hermesandroid.relay.data.HermesCardAction
import com.hermesandroid.relay.data.HermesCardDispatch
import com.hermesandroid.relay.data.HermesCardField

/**
 * Inline rich-card render for a [HermesCard] extracted from an assistant
 * message via the `CARD:{json}` marker pipeline in
 * [com.hermesandroid.relay.network.handlers.ChatHandler].
 *
 * Layout (top → bottom):
 *  - Accent stripe (leading 3dp bar, colored by [HermesCard.accent])
 *  - Header row: type icon + title + optional subtitle
 *  - Body (markdown) if present
 *  - Fields table (label : value rows)
 *  - Actions row (AssistChip / Button per [HermesCardAction])
 *  - Footer (muted labelSmall) if present
 *
 * Unknown [HermesCard.type] renders via the generic path — title + body +
 * fields + actions — so a newer agent emitting a type the phone build
 * doesn't recognize still gets a coherent card, not an empty bubble.
 *
 * Action dispatch is fully delegated to [onActionTap]. The bubble is
 * stateless w.r.t. dispatch tracking — it reads [dispatches] (from the
 * owning [com.hermesandroid.relay.data.ChatMessage.cardDispatches]) and
 * renders a confirmation row instead of the action buttons once the user
 * has chosen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HermesCardBubble(
    card: HermesCard,
    cardKey: String,
    dispatches: List<HermesCardDispatch>,
    onActionTap: (cardKey: String, action: HermesCardAction) -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 280.dp,
) {
    val accentColor = accentToColor(card.accent)
    val typeIcon = iconForType(card.type)
    val alreadyChosen = dispatches.firstOrNull { it.cardKey == cardKey }

    Card(
        modifier = modifier
            .widthIn(max = maxWidth)
            .fillMaxWidth()
            .semantics {
                contentDescription = "Card: ${card.title ?: card.type}"
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            // Accent stripe — runs full card height so tall cards keep the
            // color tie. Using the SAME tertiary accent strategy as the
            // voice/phone-action bubble marker in MessageBubble.kt so the
            // visual language stays consistent.
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accentColor),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (typeIcon != null) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        if (!card.title.isNullOrBlank()) {
                            Text(
                                text = card.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (!card.subtitle.isNullOrBlank()) {
                            Text(
                                text = card.subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Body — markdown, so the agent can embed inline code /
                // emphasis / links. Uses the existing MarkdownContent
                // renderer from MessageBubble's stack.
                if (!card.body.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    MarkdownContent(
                        content = card.body,
                        textColor = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Fields table
                if (card.fields.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    card.fields.forEach { field ->
                        FieldRow(field)
                    }
                }

                // Actions OR dispatch confirmation
                if (card.actions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    if (alreadyChosen != null) {
                        val chosen = card.actions.firstOrNull {
                            it.value == alreadyChosen.actionValue
                        }
                        ChoseRow(chosen?.label ?: alreadyChosen.actionValue)
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            card.actions.forEach { action ->
                                ActionButton(
                                    action = action,
                                    onClick = { onActionTap(cardKey, action) },
                                )
                            }
                        }
                    }
                }

                // Footer
                if (!card.footer.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = card.footer,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldRow(field: HermesCardField) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = field.value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (looksMonospaceWorthy(field.value)) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Heuristic — values that look like paths, commands, or code get a mono font. */
private fun looksMonospaceWorthy(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.startsWith("/") ||
        trimmed.startsWith("$") ||
        trimmed.startsWith("`") ||
        trimmed.contains("://") ||
        trimmed.matches(Regex("""^\S+\s+--\S.*$""")) // flags-style
}

@Composable
private fun ChoseRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Chose: $label",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun ActionButton(
    action: HermesCardAction,
    onClick: () -> Unit,
) {
    when (action.style) {
        HermesCardAction.Styles.PRIMARY -> Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) { Text(action.label, style = MaterialTheme.typography.labelMedium) }
        HermesCardAction.Styles.DANGER -> OutlinedButton(
            onClick = onClick,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error,
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text(action.label, style = MaterialTheme.typography.labelMedium) }
        else -> OutlinedButton(onClick = onClick) {
            Text(action.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Semantic accent → ColorScheme token. Unknown values fall back to the
 * neutral `info` tint (primary) so the stripe still shows up.
 */
@Composable
private fun accentToColor(accent: String?): Color = when (accent) {
    HermesCard.Accents.SUCCESS -> MaterialTheme.colorScheme.primary
    HermesCard.Accents.WARNING -> MaterialTheme.colorScheme.tertiary
    HermesCard.Accents.DANGER -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary // info + unknown
}

/** Built-in type → header icon. Unknown types show a neutral "auto" spark. */
private fun iconForType(type: String): ImageVector? = when (type) {
    HermesCard.BuiltInTypes.APPROVAL_REQUEST -> Icons.Filled.Shield
    HermesCard.BuiltInTypes.LINK_PREVIEW -> Icons.Filled.Language
    HermesCard.BuiltInTypes.CALENDAR_EVENT -> Icons.Filled.CalendarToday
    HermesCard.BuiltInTypes.WEATHER -> Icons.Filled.WbSunny
    HermesCard.BuiltInTypes.SKILL_RESULT -> Icons.Filled.AutoAwesome
    else -> Icons.Filled.AutoAwesome
}

/**
 * Resolve a [HermesCardAction] tap to a side-effecting action on the
 * current Android context. Kept as a plain top-level helper so both the
 * ChatViewModel and any future preview harness can reuse it without
 * dragging in ViewModel dependencies.
 *
 *  - [HermesCardAction.Modes.OPEN_URL] → `ACTION_VIEW` intent.
 *  - Other modes return false — the caller is expected to route them
 *    (send as a new user message, or interpret as a slash command in
 *    [com.hermesandroid.relay.viewmodel.ChatViewModel]).
 */
fun handleCardActionExternally(
    context: android.content.Context,
    action: HermesCardAction,
): Boolean {
    if (action.mode != HermesCardAction.Modes.OPEN_URL) return false
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.value))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

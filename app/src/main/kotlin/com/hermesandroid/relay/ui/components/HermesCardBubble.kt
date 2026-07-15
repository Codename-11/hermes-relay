package com.hermesandroid.relay.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.HermesCard
import com.hermesandroid.relay.data.HermesCardAction
import com.hermesandroid.relay.data.HermesCardDispatch
import com.hermesandroid.relay.data.HermesCardField
import com.hermesandroid.relay.data.HermesCardInput
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayMetadataStyle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Inline rich-card render for a [HermesCard] extracted from an assistant
 * message via the `CARD:{json}` marker pipeline in
 * [com.hermesandroid.relay.network.upstream.ChatHandler].
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
 * Action dispatch is fully delegated to [onActionTap]; input-slot
 * submissions (ask cards — clarify answer, secret value, sudo confirm)
 * are delegated to [onInputSubmit] the same way. The bubble is stateless
 * w.r.t. dispatch tracking — it reads [dispatches] (from the owning
 * [com.hermesandroid.relay.data.ChatMessage.cardDispatches]) and renders
 * a confirmation row instead of the answer surfaces once the user has
 * chosen.
 *
 * Timed asks ([HermesCardInput.expiresAtMillis]) tick a countdown footer
 * (Amber under 30s) and self-collapse to a muted "Expired — not granted"
 * stamp past expiry — history reload past the deadline lands directly in
 * the collapsed state, so a dead ask never re-prompts.
 *
 * Secrets: when [HermesCardInput.masked] is set, the submitted value goes
 * only through [onInputSubmit]; the collapse stamp renders masked dots and
 * the caller must record [HermesCardInput.SECRET_PROVIDED_STAMP] as the
 * dispatch value, never the secret itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HermesCardBubble(
    card: HermesCard,
    cardKey: String,
    dispatches: List<HermesCardDispatch>,
    onActionTap: (cardKey: String, action: HermesCardAction) -> Unit,
    onInputSubmit: (cardKey: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 280.dp,
) {
    val accentColor = accentToColor(card.accent)
    val typeIcon = iconForType(card.type)
    val alreadyChosen = dispatches.firstOrNull { it.cardKey == cardKey }
    val cardDescription = stringResource(R.string.card_a11y, card.title ?: card.type)

    // Expiry clock for timed asks. Ticks once a second while the deadline
    // is ahead; freezes after. Keyed on the deadline so a re-used card id
    // with a fresh expiry restarts the loop.
    val expiresAt = card.input?.expiresAtMillis
    var nowMillis by remember(expiresAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        if (expiresAt == null) return@LaunchedEffect
        while (System.currentTimeMillis() < expiresAt) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
        nowMillis = System.currentTimeMillis()
    }
    val expired = expiresAt != null && nowMillis >= expiresAt && alreadyChosen == null

    Card(
        modifier = modifier
            .widthIn(max = maxWidth)
            .fillMaxWidth()
            .semantics {
                contentDescription = cardDescription
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

                // Input slot + actions OR a single dispatch/expiry stamp.
                // A card with an input slot owns ONE stamp for the whole
                // card — answering via chip, field, hold-confirm, or an
                // action button all collapse the same way.
                val input = card.input
                when {
                    alreadyChosen != null -> {
                        Spacer(Modifier.height(10.dp))
                        val chosenAction = card.actions.firstOrNull {
                            it.value == alreadyChosen.actionValue
                        }
                        when {
                            alreadyChosen.actionValue == HermesCardDispatch.EXPIRED_STAMP -> ChoseRow(
                                text = stringResource(R.string.card_expired),
                                icon = Icons.Filled.HourglassBottom,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            chosenAction != null -> ChoseRow("Chose: ${chosenAction.label}")
                            input?.masked == true -> ChoseRow("Secret provided · ••••")
                            input != null -> ChoseRow("Answered: ${alreadyChosen.actionValue}")
                            else -> ChoseRow("Chose: ${alreadyChosen.actionValue}")
                        }
                    }
                    expired -> {
                        Spacer(Modifier.height(10.dp))
                        ChoseRow(
                            text = stringResource(R.string.card_expired),
                            icon = Icons.Filled.HourglassBottom,
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        if (input != null) {
                            Spacer(Modifier.height(10.dp))
                            CardInputSlot(
                                input = input,
                                onSubmit = { value -> onInputSubmit(cardKey, value) },
                            )
                        }
                        if (card.actions.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
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
                        // Countdown footer for timed asks — Amber when the
                        // deadline is inside 30s.
                        if (expiresAt != null) {
                            val remainingSec = ((expiresAt - nowMillis) / 1000).coerceAtLeast(0)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.card_expires_in, remainingSec / 60, remainingSec % 60),
                                style = relayMetadataStyle(),
                                color = if (remainingSec < 30) RelayRefresh.Amber
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
private fun ChoseRow(
    text: String,
    icon: ImageVector = Icons.Filled.Check,
    iconTint: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * The interactive answer surface for ask cards, composed from the
 * [HermesCardInput] flags rather than the card type:
 *  - [HermesCardInput.choices] → AssistChip row, one tap dispatches.
 *  - [HermesCardInput.allowFreeText] → [InlineAnswerField] mini pill +
 *    18dp send affordance.
 *  - [HermesCardInput.masked] → password-style OutlinedTextField with a
 *    reveal toggle and the "Not stored in chat history" assurance line.
 *  - [HermesCardInput.holdToConfirm] → [HoldToConfirmButton] replaces the
 *    plain submit (sudo). With [HermesCardInput.masked] it submits the
 *    typed value; bare, it submits [HermesCardInput.CONFIRM_VALUE].
 *
 * Unknown kinds degrade to the free-text field so a newer ask still gets
 * an answer surface.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardInputSlot(
    input: HermesCardInput,
    onSubmit: (String) -> Unit,
) {
    // Deliberately remember, not rememberSaveable — a typed secret must
    // never be written into the saved-instance-state Bundle.
    var answerText by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }

    val showFreeText = !input.masked && (
        input.allowFreeText ||
            input.kind == HermesCardInput.Kinds.TEXT ||
            // Unknown-kind fallback: with no other surface, still offer text.
            (input.choices.isEmpty() && !input.holdToConfirm &&
                input.kind != HermesCardInput.Kinds.CONFIRM)
        )

    Column(modifier = Modifier.fillMaxWidth()) {
        // Choice chips
        if (input.choices.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                input.choices.forEach { choice ->
                    AssistChip(
                        onClick = { onSubmit(choice) },
                        label = {
                            Text(choice, style = MaterialTheme.typography.labelMedium)
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        }

        // Masked secret field
        if (input.masked) {
            if (input.choices.isNotEmpty()) Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = answerText,
                onValueChange = { answerText = it },
                singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            imageVector = if (reveal) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                            contentDescription = if (reveal) "Hide value" else "Reveal value",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.card_not_stored),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        // Free-text mini field (clarify)
        if (showFreeText) {
            if (input.choices.isNotEmpty()) Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                InlineAnswerField(
                    value = answerText,
                    onValueChange = { answerText = it },
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onSubmit(answerText.trim()) },
                    enabled = answerText.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.card_send_answer_a11y),
                        tint = if (answerText.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Submit affordance for masked / hold-to-confirm inputs
        when {
            input.holdToConfirm -> {
                Spacer(Modifier.height(10.dp))
                HoldToConfirmButton(
                    label = "Hold to confirm",
                    enabled = !input.masked || answerText.isNotEmpty(),
                    onConfirmed = {
                        onSubmit(
                            if (input.masked) answerText
                            else HermesCardInput.CONFIRM_VALUE,
                        )
                    },
                )
            }
            input.masked -> {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onSubmit(answerText) },
                    enabled = answerText.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text(stringResource(R.string.card_submit), style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

/**
 * Mini pill answer field — Navy3 surface, hairline border, bodyMedium,
 * single line growing to 3. The in-card sibling of the chat input pill.
 */
@Composable
private fun InlineAnswerField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = stringResource(R.string.card_answer_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Sudo confirm hold duration — long enough to defeat a drive-by tap. */
private const val HOLD_TO_CONFIRM_MS = 650

/**
 * Error-container button whose fill completes over [HOLD_TO_CONFIRM_MS] of
 * sustained press (pointerInput onPress racing tryAwaitRelease). Releasing
 * early snaps the fill back; holding to completion fires [onConfirmed]
 * exactly once at the moment the fill lands.
 */
@Composable
private fun HoldToConfirmButton(
    label: String,
    enabled: Boolean,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill = remember { Animatable(0f) }
    val errorColor = MaterialTheme.colorScheme.error
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(errorColor.copy(alpha = if (enabled) 0.18f else 0.08f))
            .border(1.dp, errorColor.copy(alpha = if (enabled) 0.6f else 0.25f), shape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        coroutineScope {
                            val ramp = launch {
                                fill.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(HOLD_TO_CONFIRM_MS, easing = LinearEasing),
                                )
                                onConfirmed()
                            }
                            tryAwaitRelease()
                            ramp.cancel()
                        }
                        fill.snapTo(0f)
                    },
                )
            }
            .semantics { contentDescription = "$label — press and hold" },
        contentAlignment = Alignment.Center,
    ) {
        // Press-fill layer grows left → right under the label.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(fill.value.coerceIn(0f, 1f))
                .background(errorColor.copy(alpha = 0.45f)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) errorColor else errorColor.copy(alpha = 0.5f),
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
    HermesCard.BuiltInTypes.ASK_APPROVAL -> Icons.Filled.Shield
    HermesCard.BuiltInTypes.ASK_SUDO -> Icons.Filled.Shield
    HermesCard.BuiltInTypes.ASK_CLARIFY -> Icons.Filled.AutoAwesome
    HermesCard.BuiltInTypes.ASK_SECRET -> Icons.Filled.Lock
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

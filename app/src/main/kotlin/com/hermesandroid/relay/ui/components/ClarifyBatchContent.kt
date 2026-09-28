package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.HermesCardClarifyBatch
import kotlinx.serialization.json.Json

/** One mobile question surface; confirmed qids advance without resubmitting earlier answers. */
@Composable
internal fun ClarifyBatchContent(
    batch: HermesCardClarifyBatch,
    expired: Boolean,
    onInputSubmit: (String, String) -> Unit,
) {
    val answered = batch.questions.filter { it.answer != null }
    val activeIndex = batch.questions.indexOfFirst { it.answer == null }
    val active = batch.questions.getOrNull(activeIndex)
    var showAnswers by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(active?.key, expired) { focusManager.clearFocus() }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = when {
                expired -> stringResource(R.string.clarify_batch_expired)
                active == null -> stringResource(R.string.clarify_batch_complete)
                else -> stringResource(R.string.clarify_batch_progress, activeIndex + 1, batch.questions.size)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (batch.questions.size > 1) {
            LinearProgressIndicator(
                progress = { answered.size.toFloat() / batch.questions.size },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (active != null && !expired) {
            key(active.key) {
                Text(
                    active.question,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                CardInputSlot(
                    input = active.input,
                    onSubmit = { if (!active.submitting) onInputSubmit(active.key, it) },
                    enabled = !active.submitting,
                    stackedChoices = true,
                )
                if (batch.allowSkip) {
                    TextButton(enabled = !active.submitting, onClick = { onInputSubmit(active.key, "") }) {
                        Text(stringResource(R.string.chat_approval_skip))
                    }
                }
                if (active.submitting) {
                    Text(stringResource(R.string.clarify_batch_sending), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (answered.isNotEmpty()) {
            if (active == null || expired) {
                Text(stringResource(R.string.clarify_batch_answered, answered.size), style = MaterialTheme.typography.labelLarge)
            } else {
                TextButton(onClick = { showAnswers = !showAnswers }) {
                    Text(stringResource(R.string.clarify_batch_answered, answered.size))
                }
            }
            if (showAnswers || active == null || expired) {
                answered.forEach { question ->
                    Text(question.question, style = MaterialTheme.typography.labelLarge)
                    val answer = if (question.input.multiSelect) {
                        runCatching { Json.decodeFromString<List<String>>(question.answer.orEmpty()).joinToString(", ") }
                            .getOrDefault(question.answer.orEmpty())
                    } else question.answer.orEmpty()
                    Text(answer, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

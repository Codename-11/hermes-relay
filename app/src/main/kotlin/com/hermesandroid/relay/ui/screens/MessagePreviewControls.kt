package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.UiMessageBus
import com.hermesandroid.relay.ui.UiMessageSeverity
import com.hermesandroid.relay.ui.components.HumanErrorVisuals
import com.hermesandroid.relay.ui.components.LocalMessageActionHost
import com.hermesandroid.relay.util.HumanError
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Local-only samples; no network, diagnostics, or connection-state mutations. */
@Composable
internal fun MessagePreviewControls() {
    val scope = rememberCoroutineScope()
    val actionHost = LocalMessageActionHost.current
    val jobs = remember { arrayOfNulls<Job>(1) }
    val key = "developer-message-preview"
    fun reset() {
        jobs[0]?.cancel()
        jobs[0] = null
        UiMessageBus.clear(key)
    }
    DisposableEffect(Unit) { onDispose { reset() } }
    Text(stringResource(R.string.dev_message_previews), style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dev_message_previews_desc), style = MaterialTheme.typography.bodySmall)
            val samples = listOf(
                R.string.dev_message_info to UiMessageSeverity.Info,
                R.string.dev_message_success to UiMessageSeverity.Success,
                R.string.dev_message_progress to UiMessageSeverity.Status,
                R.string.dev_message_warning to UiMessageSeverity.Warning,
                R.string.dev_message_error to UiMessageSeverity.Error,
            )
            samples.forEach { (label, severity) ->
                val text = stringResource(label)
                TestHarnessRow(text, stringResource(R.string.dev_message_preview_sample), Icons.Filled.Science, onClick = {
                    reset()
                    UiMessageBus.post(text, severity, ttlMillis = if (severity == UiMessageSeverity.Status) 0L else 10_000L, key = key)
                })
            }
            val errorTitle = stringResource(R.string.dev_message_action)
            val errorBody = stringResource(R.string.dev_message_action_body)
            val retryLabel = stringResource(R.string.dev_message_retry)
            val retryResult = stringResource(R.string.dev_message_retry_result)
            TestHarnessRow(errorTitle, errorBody, Icons.Filled.Science, onClick = {
                reset()
                jobs[0] = scope.launch {
                    val result = actionHost?.showSnackbar(HumanErrorVisuals(HumanError(
                        title = errorTitle, body = errorBody, retryable = true, actionLabel = retryLabel,
                    )))
                    if (result == SnackbarResult.ActionPerformed) {
                        UiMessageBus.post(retryResult, UiMessageSeverity.Success, key = key)
                    }
                }
            })
            TestHarnessRow(
                stringResource(R.string.dev_message_clear),
                stringResource(R.string.dev_message_clear_desc),
                Icons.Filled.Science,
                onClick = { reset() },
            )
        }
    }
}

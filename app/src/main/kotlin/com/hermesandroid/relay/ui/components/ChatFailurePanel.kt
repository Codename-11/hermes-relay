package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.viewmodel.ChatFailureNotice

@Composable
fun ChatFailurePanel(
    failure: ChatFailureNotice,
    routeLabel: String,
    onDetails: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    showDetails: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_failure_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    failureIdentity(routeLabel, failure.model, failure.provider)
                        .takeIf { it.isNotBlank() }
                        ?.let { identity ->
                            Text(
                                text = identity,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.78f),
                            )
                        }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showDetails) {
                    TextButton(onClick = onDetails) {
                        Text(stringResource(R.string.chat_failure_details))
                    }
                }
                if (failure.recoverable) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.chat_retry))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_dismiss))
                }
            }
        }
    }
}

@Composable
fun ChatFailureDetailsDialog(
    failure: ChatFailureNotice,
    routeLabel: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_failure_details_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                failureIdentity(routeLabel, failure.model, failure.provider)
                    .takeIf { it.isNotBlank() }
                    ?.let { identity ->
                        Text(text = identity, style = MaterialTheme.typography.labelLarge)
                    }
                Text(
                    text = stringResource(R.string.chat_failure_details_guidance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    SelectionContainer {
                        Text(
                            text = failure.rawError,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) {
                Text(stringResource(R.string.chat_failure_copy_details))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

internal fun failureIdentity(route: String, model: String?, provider: String?): String =
    listOfNotNull(
        route.takeIf { it.isNotBlank() },
        provider?.trim()?.takeIf { it.isNotEmpty() },
        model?.trim()?.takeIf { it.isNotEmpty() },
    ).distinct().joinToString(" · ")

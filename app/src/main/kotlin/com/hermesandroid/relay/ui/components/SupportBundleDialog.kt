package com.hermesandroid.relay.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermesandroid.relay.R
import com.hermesandroid.relay.diagnostics.DiagnosticLogEntry
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.reliability.ReliabilityEnvironment
import com.hermesandroid.relay.reliability.ReliabilityRedactor
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import com.hermesandroid.relay.reliability.ReliabilityReport
import com.hermesandroid.relay.reliability.SupportBundleBuilder
import com.hermesandroid.relay.util.IssueReport

data class SupportReviewState(
    val recordCount: Int,
    val text: String,
    val shareEnabled: Boolean,
)

internal fun buildSupportReviewState(
    reports: List<ReliabilityReport>,
    diagnostics: List<DiagnosticLogEntry> = emptyList(),
    environment: ReliabilityEnvironment? = reports.lastOrNull()?.environment,
): SupportReviewState {
    val reportCount = reports.takeLast(SupportBundleBuilder.MAX_REPORTS).size
    val diagnosticCount = diagnostics.takeLast(DiagnosticsLog.SUPPORT_ENTRY_LIMIT).size
    val diagnosticText = DiagnosticsLog.supportText(diagnostics)
    val combined = buildString {
        append(SupportBundleBuilder.build(reports))
        environment?.let {
            appendLine()
            appendLine()
            appendLine("Current environment")
            appendLine("App: ${it.versionName} (code ${it.versionCode}) ${it.flavor}")
            append(
                "Device: ${it.manufacturer} ${it.model} — " +
                    "Android ${it.androidRelease} (SDK ${it.sdkInt})",
            )
        }
        if (diagnosticText.isNotBlank()) {
            appendLine()
            appendLine()
            append(diagnosticText)
        }
    }
    val recordCount = reportCount + diagnosticCount
    return SupportReviewState(
        recordCount = recordCount,
        text = ReliabilityRedactor.redact(combined, 64_000),
        shareEnabled = recordCount > 0,
    )
}

/** Exact review surface for the local text handed to clipboard/share. */
@Composable
fun SupportBundleDialog(state: SupportReviewState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val copied = stringResource(R.string.support_bundle_copied)
    val noShare = stringResource(R.string.support_bundle_no_share)
    val chooser = stringResource(R.string.support_bundle_share_title)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f),
            shape = appearanceRoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(stringResource(R.string.support_bundle_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.support_bundle_privacy, state.recordCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 420.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            appearanceRoundedCornerShape(12.dp),
                        ),
                ) {
                    SelectionContainer {
                        Text(
                            text = state.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                    OutlinedButton(
                        enabled = state.shareEnabled,
                        onClick = {
                            IssueReport.copyToClipboard(context, state.text)
                            Toast.makeText(context, copied, Toast.LENGTH_LONG).show()
                        },
                    ) { Text(stringResource(R.string.common_copy)) }
                    Button(
                        enabled = state.shareEnabled,
                        onClick = {
                            if (!IssueReport.share(
                                    context,
                                    subject = chooser,
                                    text = state.text,
                                    chooserTitle = chooser,
                                )
                            ) {
                                IssueReport.copyToClipboard(context, state.text)
                                Toast.makeText(context, noShare, Toast.LENGTH_LONG).show()
                            }
                        },
                    ) { Text(stringResource(R.string.common_share)) }
                }
            }
        }
    }
}

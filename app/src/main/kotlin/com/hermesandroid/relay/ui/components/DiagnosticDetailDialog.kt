package com.hermesandroid.relay.ui.components

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermesandroid.relay.BuildConfig
import com.hermesandroid.relay.diagnostics.DiagnosticLogEntry
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.util.DiagnosticIssuePrefill
import com.hermesandroid.relay.util.IssueReport

/**
 * Self-contained, full-detail view for a single [DiagnosticLogEntry], opened
 * from a tapped row in [DiagnosticsLogPanel]. Renders the clean title, category,
 * severity, timestamp, sanitized route/url, elapsed, and the full redacted
 * stacktrace/detail in a monospace selectable block.
 *
 * It is a plain [Dialog] driven entirely by the panel's own state — there is NO
 * nav route and nothing to wire in RelayApp. Visual pattern mirrors
 * [CrashReportDialog]; the Copy / Export(share) / Create-GitHub-issue actions
 * all route through the shared [IssueReport] helper.
 */
@Composable
fun DiagnosticDetailDialog(entry: DiagnosticLogEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val plainText = remember(entry) { entry.toPlainText() }
    val severityName = entry.severity.name

    // Info-severity pre-flight: routine log lines only become GitHub issues once
    // the reporter says what they expected instead (that answer replaces the
    // boilerplate "What happened" line). Error entries keep the direct flow.
    val needsExpectation = entry.severity == DiagnosticSeverity.Info
    var expectationVisible by remember(entry) { mutableStateOf(false) }
    var expectation by remember(entry) { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DiagnosticSeverityChip(entry.severity)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = entry.category.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.height(10.dp))
                // Metadata rows — only render the ones that are present.
                MetaRow("When", DateFormat.format("yyyy-MM-dd HH:mm:ss", entry.timestampMs).toString())
                MetaRow("Severity", severityName)
                MetaRow("Category", entry.category.label)
                entry.endpointRole?.let { MetaRow("Route", it) }
                entry.url?.let { MetaRow("URL", it) }
                entry.elapsedMs?.let { MetaRow("Elapsed", "${it}ms") }

                Spacer(Modifier.height(14.dp))
                val body = entry.stacktrace ?: entry.detail
                if (body != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 320.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                RoundedCornerShape(12.dp),
                            ),
                    ) {
                        SelectionContainer {
                            Text(
                                text = body,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                            )
                        }
                    }
                } else {
                    Text(
                        text = "No further detail captured for this entry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (expectationVisible) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = expectation,
                        onValueChange = { expectation = it },
                        label = { Text("What were you expecting to happen?") },
                        supportingText = {
                            Text("This is a routine log entry — telling us what looked wrong turns it into an answerable report.")
                        },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(18.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    OutlinedButton(
                        onClick = {
                            IssueReport.copyToClipboard(context, plainText)
                            toast(context, "Diagnostic copied")
                        },
                    ) { Text("Copy") }
                    OutlinedButton(
                        onClick = {
                            val shared = IssueReport.share(
                                context,
                                subject = "Hermes-Relay diagnostic — ${entry.title}",
                                text = plainText,
                                chooserTitle = "Export diagnostic",
                            )
                            if (!shared) {
                                IssueReport.copyToClipboard(context, plainText)
                                toast(context, "Copied — no app found to share to")
                            }
                        },
                    ) { Text("Export") }
                    Button(
                        enabled = !expectationVisible || expectation.isNotBlank(),
                        onClick = {
                            if (needsExpectation && !expectationVisible) {
                                expectationVisible = true
                                return@Button
                            }
                            // Copy full text first; the GitHub URL only carries the
                            // head of long traces, so the user can paste the rest.
                            IssueReport.copyToClipboard(context, plainText)
                            val opened = IssueReport.openUrl(
                                context,
                                IssueReport.buildGithubIssueUrl(
                                    title = DiagnosticIssuePrefill.issueTitle(entry),
                                    bodyMarkdown = DiagnosticIssuePrefill.issueBody(
                                        entry,
                                        expectation = expectation.takeIf { expectationVisible },
                                    ),
                                    labels = DiagnosticIssuePrefill.issueLabels(entry),
                                ),
                            )
                            toast(
                                context,
                                if (opened) "Full diagnostic copied — paste it into the issue if truncated"
                                else "Copied — no browser found to open GitHub",
                            )
                        },
                    ) { Text("Report") }
                }
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(78.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun DiagnosticSeverityChip(severity: DiagnosticSeverity) {
    val (bg, fg) = when (severity) {
        DiagnosticSeverity.Info ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        DiagnosticSeverity.Warning ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        DiagnosticSeverity.Error ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(
            text = severity.name.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

private fun toast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

/** Full, copy/share-ready plain-text rendering of a single diagnostic entry. */
private fun DiagnosticLogEntry.toPlainText(): String = buildString {
    appendLine("Hermes-Relay diagnostic")
    appendLine("Title:    $title")
    appendLine("Category: ${category.label}")
    appendLine("Severity: ${severity.name}")
    appendLine("Time:     ${DateFormat.format("yyyy-MM-dd HH:mm:ss", timestampMs)}")
    appendLine("App:      ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}) ${BuildConfig.FLAVOR}")
    endpointRole?.let { appendLine("Route:    $it") }
    url?.let { appendLine("URL:      $it") }
    elapsedMs?.let { appendLine("Elapsed:  ${it}ms") }
    detail?.let {
        appendLine()
        appendLine("Detail:")
        appendLine(it)
    }
    stacktrace?.let {
        appendLine()
        appendLine("Stacktrace:")
        append(it)
    }
}

package com.hermesandroid.relay.ui.components

import android.content.Context
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermesandroid.relay.R
import com.hermesandroid.relay.util.CrashReport
import com.hermesandroid.relay.util.CrashReporter
import com.hermesandroid.relay.util.IssueReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drop-in gate that surfaces a pending crash report (if the previous session
 * crashed). Render it inside the app theme so the dialog picks up Material
 * colors — see RelayApp.
 *
 * Peeks the report on first composition (does NOT delete on read) and clears it
 * only when the user acknowledges it (Dismiss/Report). A report the user merely
 * glanced at — or never reached because the app was backgrounded — therefore
 * survives relaunches instead of being lost after one view; once acknowledged
 * it's deleted and won't reappear.
 */
@Composable
fun CrashReportGate() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<CrashReport?>(null) }
    var checked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (checked) return@LaunchedEffect
        checked = true
        report = withContext(Dispatchers.IO) { CrashReporter.peekPending(context) }
    }

    val pending = report ?: return
    CrashReportDialog(
        report = pending,
        onDismiss = {
            // Acknowledged (Dismiss/Report) → delete so it won't reappear.
            // Copy does NOT route through here, so the report stays available
            // across relaunches until the user actually dismisses or reports it.
            CrashReporter.clearPending(context)
            report = null
        },
    )
}

@Composable
private fun CrashReportDialog(report: CrashReport, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val reportText = remember(report) { report.toPlainText() }

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
                    Icon(
                        imageVector = Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.crash_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.crash_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 300.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                ) {
                    SelectionContainer {
                        Text(
                            text = reportText,
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

                Spacer(Modifier.height(18.dp))
                // FlowRow so the actions wrap instead of clipping on narrow /
                // foldable cover screens now that a fourth (Share) action exists.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_dismiss)) }
                    OutlinedButton(
                        onClick = {
                            IssueReport.copyToClipboard(context, reportText)
                            toast(context, "Crash report copied")
                        },
                    ) { Text(stringResource(R.string.common_copy)) }
                    // Universal, GitHub-free path: hand the full report to the
                    // system share sheet (email, chat apps, notes, Drive…). The
                    // user picks the destination, so nothing leaves the device
                    // until they choose to send it — same privacy posture as Copy.
                    OutlinedButton(
                        onClick = {
                            val shared = IssueReport.share(
                                context,
                                "Hermes-Relay crash report — ${report.shortTitle()}",
                                reportText,
                                chooserTitle = "Share crash report",
                            )
                            if (!shared) {
                                IssueReport.copyToClipboard(context, reportText)
                                toast(context, "Report copied — no app found to share to")
                            }
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.common_share)) }
                    Button(
                        onClick = {
                            // Copy the FULL report first; the URL only carries the
                            // head of the trace, so the user can paste the rest.
                            IssueReport.copyToClipboard(context, reportText)
                            val opened = IssueReport.openUrl(context, CrashReporter.buildGithubIssueUrl(report))
                            toast(
                                context,
                                if (opened) "Full report copied — paste into the issue if it's truncated"
                                else "Report copied — no browser found to open GitHub",
                            )
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.common_report)) }
                }
            }
        }
    }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

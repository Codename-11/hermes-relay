package com.hermesandroid.relay.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermesandroid.relay.util.CrashReport
import com.hermesandroid.relay.util.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drop-in gate that surfaces a pending crash report (if the previous session
 * crashed) exactly once per launch. Render it inside the app theme so the
 * dialog picks up Material colors — see RelayApp.
 *
 * Reads + clears the report on first composition (show-once), so a clean launch
 * is a no-op and the dialog never reappears within the same process.
 */
@Composable
fun CrashReportGate() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<CrashReport?>(null) }
    var checked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (checked) return@LaunchedEffect
        checked = true
        report = withContext(Dispatchers.IO) { CrashReporter.consumePending(context) }
    }

    val pending = report ?: return
    CrashReportDialog(report = pending, onDismiss = { report = null })
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
                        text = "Hermes-Relay closed unexpectedly",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "The last session crashed. Sending this report helps get it fixed — " +
                        "nothing is sent automatically.",
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            copyToClipboard(context, reportText)
                            toast(context, "Crash report copied")
                        },
                    ) { Text("Copy") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            // Copy the FULL report first; the URL only carries the
                            // head of the trace, so the user can paste the rest.
                            copyToClipboard(context, reportText)
                            val opened = openUrl(context, CrashReporter.buildGithubIssueUrl(report))
                            toast(
                                context,
                                if (opened) "Full report copied — paste into Logs if needed"
                                else "Report copied — no browser found to open GitHub",
                            )
                            onDismiss()
                        },
                    ) { Text("Report") }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Hermes-Relay crash report", text))
    }
}

private fun openUrl(context: Context, url: String): Boolean = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
}.getOrDefault(false)

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

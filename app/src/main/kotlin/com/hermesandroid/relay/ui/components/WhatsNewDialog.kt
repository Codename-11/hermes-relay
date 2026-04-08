package com.hermesandroid.relay.ui.components

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun WhatsNewDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val changelogText = remember { loadWhatsNew(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's New") },
        text = {
            Text(
                text = changelogText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Default
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

private fun loadWhatsNew(context: Context): String {
    return try {
        context.assets.open("whats_new.txt").bufferedReader().readText()
    } catch (_: Exception) {
        "No release notes available."
    }
}

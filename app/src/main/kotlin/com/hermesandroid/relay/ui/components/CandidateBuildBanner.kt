package com.hermesandroid.relay.ui.components

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.CandidateBuild

/** Persistent, non-dismissible identity strip for side-by-side candidate builds. */
@Composable
@SuppressLint("HardcodedText")
fun CandidateBuildBanner(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clickable { expanded = !expanded }
                    .semantics { role = Role.Button }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = CandidateBuild.heading,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = CandidateBuild.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide candidate details" else "Show candidate details",
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(18.dp),
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 6.dp,
                shadowElevation = 4.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CandidateDetailRow(label = "Commit", value = CandidateBuild.shortSha)
                    CandidateDetailRow(label = "Install", value = "Candidate slot · stable untouched")
                }
            }
        }
    }
}

@Composable
@SuppressLint("HardcodedText")
private fun CandidateDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(0.22f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.78f),
        )
    }
}

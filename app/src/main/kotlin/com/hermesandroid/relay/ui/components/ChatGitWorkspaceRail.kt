package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import com.hermesandroid.relay.ui.theme.relayMetadataStyle

/** Small, read-only Git projection supplied by the native workspace owner. */
data class ChatGitWorkspaceSummary(
    val branch: String,
    val changeCount: Int,
    val additions: Int? = null,
    val deletions: Int? = null,
)

@Composable
fun ChatGitContextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        RelayChromeIconButton(
            icon = Icons.Filled.AccountTree,
            contentDescription = stringResource(R.string.chat_git_open_workspace),
            onClick = onClick,
        )
        Surface(
            modifier = Modifier
                .size(9.dp)
                .align(Alignment.TopEnd),
            shape = CircleShape,
            color = RelayRefresh.Green,
            border = BorderStroke(1.5.dp, RelayRefresh.Background),
            content = {},
        )
    }
}

@Composable
fun ChatGitWorkspaceRail(
    summary: ChatGitWorkspaceSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val branch = summary.branch.trim()
    val changeCount = summary.changeCount.coerceAtLeast(0)
    val changeLabel = pluralStringResource(
        R.plurals.chat_git_change_count,
        changeCount,
        changeCount,
    )
    val additions = summary.additions?.coerceAtLeast(0)
    val deletions = summary.deletions?.coerceAtLeast(0)
    val a11yLabel = buildList {
        add(stringResource(R.string.chat_git_branch, branch))
        add(changeLabel)
        additions?.let { add(stringResource(R.string.chat_git_additions, it)) }
        deletions?.let { add(stringResource(R.string.chat_git_deletions, it)) }
        add(stringResource(R.string.chat_git_open_workspace))
    }.joinToString(". ")

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clearAndSetSemantics { contentDescription = a11yLabel },
        shape = appearanceRoundedCornerShape(12.dp),
        color = RelayRefresh.Background.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, RelayRefresh.LineStrong),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.AccountTree,
                contentDescription = null,
                tint = RelayRefresh.Cyan,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = branch,
                style = relayMetadataStyle(),
                color = RelayRefresh.Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 92.dp),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(RelayRefresh.LineStrong),
            )
            Text(
                text = changeLabel,
                style = relayMetadataStyle(),
                color = RelayRefresh.Muted,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.weight(1f))
            additions?.let {
                Text(
                    text = "+$it",
                    style = relayMetadataStyle(),
                    color = RelayRefresh.Green,
                    maxLines = 1,
                )
            }
            deletions?.let {
                Text(
                    text = "-$it",
                    style = relayMetadataStyle(),
                    color = RelayRefresh.Danger,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = RelayRefresh.Muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

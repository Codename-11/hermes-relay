package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.AttachmentRenderMode

/**
 * Stable, presentation-only summary for a message's attachment group.
 *
 * Attachment bytes, fetch state, retry callbacks, and persistence remain owned
 * by the existing attachment pipeline; this helper only chooses the compact
 * label shown while that pipeline is folded away.
 */
internal data class AttachmentGroupSummary(
    val count: Int,
    val firstName: String?,
    val firstType: AttachmentRenderMode,
    val remainingCount: Int,
)

internal fun attachmentGroupSummary(attachments: List<Attachment>): AttachmentGroupSummary? {
    val first = attachments.firstOrNull() ?: return null
    return AttachmentGroupSummary(
        count = attachments.size,
        firstName = first.fileName?.trim()?.takeIf(String::isNotEmpty),
        firstType = first.renderMode,
        remainingCount = (attachments.size - 1).coerceAtLeast(0),
    )
}

/**
 * Slack-style disclosure for all attachments belonging to one message.
 *
 * The fold state is saveable and keyed by the message's stable Compose
 * identity, so attachment lifecycle updates do not unexpectedly reopen a
 * group the user collapsed. The compact header always remains available,
 * making preview, retry, download, and file actions recoverable with one tap.
 */
@Composable
internal fun CollapsibleAttachmentGroup(
    messageKey: String,
    attachments: List<Attachment>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val summary = attachmentGroupSummary(attachments) ?: return
    var expanded by rememberSaveable(messageKey) { mutableStateOf(true) }
    val stateLabel = stringResource(
        if (expanded) R.string.attachment_group_expanded else R.string.attachment_group_collapsed,
    )
    val actionLabel = stringResource(
        if (expanded) R.string.attachment_group_collapse else R.string.attachment_group_expand,
    )
    val typeLabel = stringResource(summary.firstType.labelResource())
    val detail = when {
        summary.firstName != null && summary.remainingCount > 0 ->
            stringResource(
                R.string.attachment_group_named_more,
                summary.firstName,
                typeLabel,
                summary.remainingCount,
            )
        summary.firstName != null ->
            stringResource(R.string.attachment_group_named, summary.firstName, typeLabel)
        summary.remainingCount > 0 ->
            stringResource(R.string.attachment_group_typed_more, typeLabel, summary.remainingCount)
        else -> typeLabel
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("attachment-group-toggle-$messageKey")
                .semantics(mergeDescendants = true) {
                    contentDescription = actionLabel
                    role = Role.Button
                    stateDescription = stateLabel
                },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.attachment_group_count,
                            summary.count,
                            summary.count,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("attachment-group-content-$messageKey"),
            ) {
                content()
            }
        }
    }
}

private fun AttachmentRenderMode.labelResource(): Int = when (this) {
    AttachmentRenderMode.IMAGE -> R.string.attachment_type_image
    AttachmentRenderMode.VIDEO -> R.string.attachment_type_video
    AttachmentRenderMode.AUDIO -> R.string.attachment_type_audio
    AttachmentRenderMode.PDF -> R.string.attachment_type_pdf
    AttachmentRenderMode.TEXT -> R.string.attachment_type_text
    AttachmentRenderMode.GENERIC -> R.string.attachment_type_file
}

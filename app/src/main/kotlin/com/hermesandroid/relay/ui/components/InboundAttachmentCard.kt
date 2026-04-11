package com.hermesandroid.relay.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.AttachmentRenderMode
import com.hermesandroid.relay.data.AttachmentState
import com.hermesandroid.relay.viewmodel.ChatViewModel

/**
 * Discord-style inline render for any attachment — outbound (user-authored)
 * or inbound (fetched from the relay via a `MEDIA:hermes-relay://` marker).
 *
 * Dispatches on (state × renderMode):
 *   - LOADING   → small spinner card, "Tap to download" CTA when
 *                 [Attachment.errorMessage] equals [ChatViewModel.MEDIA_TAP_TO_DOWNLOAD].
 *   - FAILED    → small warning card with retry tap target.
 *   - LOADED    → IMAGE renders inline (bitmap decode), everything else
 *                 renders as a tap-to-open file card that fires ACTION_VIEW
 *                 on the cached content:// URI with FLAG_GRANT_READ_URI_PERMISSION.
 *
 * Outbound attachments always have [AttachmentState.LOADED] so they take the
 * LOADED branch immediately — no behavior change relative to the legacy
 * MessageBubble attachment code.
 */
@Composable
fun InboundAttachmentCard(
    attachment: Attachment,
    onRetry: () -> Unit,
    onManualFetch: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 280.dp
) {
    when (attachment.state) {
        AttachmentState.LOADING -> LoadingCard(
            attachment = attachment,
            onManualFetch = onManualFetch,
            modifier = modifier,
            maxWidth = maxWidth
        )
        AttachmentState.FAILED -> FailedCard(
            attachment = attachment,
            onRetry = onRetry,
            modifier = modifier,
            maxWidth = maxWidth
        )
        AttachmentState.LOADED -> LoadedAttachment(
            attachment = attachment,
            modifier = modifier,
            maxWidth = maxWidth
        )
    }
}

@Composable
private fun LoadingCard(
    attachment: Attachment,
    onManualFetch: () -> Unit,
    modifier: Modifier,
    maxWidth: Dp
) {
    val isManualCta = attachment.errorMessage == ChatViewModel.MEDIA_TAP_TO_DOWNLOAD
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .widthIn(max = maxWidth)
            .then(if (isManualCta) Modifier.clickable { onManualFetch() } else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isManualCta) {
                Text(text = "\u2B07\uFE0F", style = MaterialTheme.typography.titleMedium)
            } else {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isManualCta) "Tap to download" else "Downloading…",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val subtitle = attachment.fileName
                    ?: attachment.contentType.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FailedCard(
    attachment: Attachment,
    onRetry: () -> Unit,
    modifier: Modifier,
    maxWidth: Dp
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier
            .widthIn(max = maxWidth)
            .clickable { onRetry() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "\u26A0\uFE0F", style = MaterialTheme.typography.titleMedium)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.errorMessage ?: "Attachment failed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "Tap to retry",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun LoadedAttachment(
    attachment: Attachment,
    modifier: Modifier,
    maxWidth: Dp
) {
    when (attachment.renderMode) {
        AttachmentRenderMode.IMAGE -> ImageRender(attachment, modifier, maxWidth)
        AttachmentRenderMode.VIDEO,
        AttachmentRenderMode.AUDIO,
        AttachmentRenderMode.PDF,
        AttachmentRenderMode.TEXT,
        AttachmentRenderMode.GENERIC -> FileCardRender(attachment, modifier, maxWidth)
    }
}

/**
 * Inline image render. Prefers [Attachment.cachedUri] (inbound attachments
 * written to FileProvider cache), falls back to decoding base64 [Attachment.content]
 * for outbound attachments authored by the user.
 */
@Composable
private fun ImageRender(
    attachment: Attachment,
    modifier: Modifier,
    maxWidth: Dp
) {
    val context = LocalContext.current
    val bitmap: ImageBitmap? = remember(attachment.cachedUri, attachment.content) {
        try {
            val bytes: ByteArray? = when {
                !attachment.cachedUri.isNullOrBlank() -> {
                    context.contentResolver.openInputStream(Uri.parse(attachment.cachedUri))
                        ?.use { it.readBytes() }
                }
                attachment.content.isNotBlank() -> {
                    android.util.Base64.decode(attachment.content, android.util.Base64.DEFAULT)
                }
                else -> null
            }
            bytes?.let {
                android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
            }
        } catch (_: Exception) {
            null
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = attachment.fileName,
            modifier = modifier
                .widthIn(max = maxWidth)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.FillWidth
        )
    } else {
        // Decode failed — degrade to a generic file card so at least the
        // user can tap through to an external viewer.
        FileCardRender(
            attachment = attachment.copy(contentType = "application/octet-stream"),
            modifier = modifier,
            maxWidth = maxWidth
        )
    }
}

@Composable
private fun FileCardRender(
    attachment: Attachment,
    modifier: Modifier,
    maxWidth: Dp
) {
    val context = LocalContext.current
    val (emoji, typeLabel) = emojiAndLabelFor(attachment.renderMode, attachment.contentType)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .widthIn(max = maxWidth)
            .clickable {
                val uriStr = attachment.cachedUri
                if (!uriStr.isNullOrBlank()) {
                    try {
                        val uri = Uri.parse(uriStr)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, attachment.contentType)
                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                            )
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        // No viewer installed or malformed URI — silently ignore.
                    }
                }
            }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emoji, style = MaterialTheme.typography.headlineSmall)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.fileName ?: typeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                val sizeLabel = attachment.fileSize?.let { formatBytes(it) }
                val subtitle = listOfNotNull(
                    typeLabel.takeIf { attachment.fileName != null },
                    sizeLabel
                ).joinToString(" \u00B7 ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

private fun emojiAndLabelFor(
    mode: AttachmentRenderMode,
    contentType: String
): Pair<String, String> = when (mode) {
    AttachmentRenderMode.IMAGE -> "\uD83D\uDDBC\uFE0F" to "Image"
    AttachmentRenderMode.VIDEO -> "\uD83C\uDFAC" to "Video"
    AttachmentRenderMode.AUDIO -> "\uD83C\uDFB5" to "Audio"
    AttachmentRenderMode.PDF -> "\uD83D\uDCC4" to "PDF"
    AttachmentRenderMode.TEXT -> "\uD83D\uDCDD" to contentTypeToLabel(contentType, fallback = "Text")
    AttachmentRenderMode.GENERIC -> "\uD83D\uDCCE" to contentTypeToLabel(contentType, fallback = "File")
}

private fun contentTypeToLabel(contentType: String, fallback: String): String {
    val bare = contentType.substringBefore(';').trim()
    if (bare.isBlank() || bare == "application/octet-stream") return fallback
    val subtype = bare.substringAfter('/', missingDelimiterValue = bare)
    return subtype.uppercase().take(16)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> "%.1f MB".format(mb)
        kb >= 1.0 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}

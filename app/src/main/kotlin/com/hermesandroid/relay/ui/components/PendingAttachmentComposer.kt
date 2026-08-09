package com.hermesandroid.relay.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.AttachmentRenderMode
import com.hermesandroid.relay.data.AttachmentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pending files shown immediately above the chat composer.
 *
 * The owner keeps attachment state and handles the four stable integration
 * callbacks. [onMove] receives the current and requested indices, allowing a
 * ViewModel or session-owned draft store to perform one atomic reorder.
 */
@Composable
fun PendingAttachmentComposer(
    attachments: List<Attachment>,
    onPreview: (attachment: Attachment, index: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    imageDecoder: suspend (Attachment) -> ImageBitmap? = ::decodePendingAttachmentImage,
) {
    if (attachments.isEmpty()) return
    val attachmentLabel = stringResource(R.string.attachment_title)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .semantics {
                contentDescription = "${attachments.size} $attachmentLabel"
            }
            .testTag("pending-attachment-composer"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEachIndexed { index, attachment ->
            PendingAttachmentItem(
                attachment = attachment,
                index = index,
                count = attachments.size,
                onPreview = { onPreview(attachment, index) },
                onRemove = { onRemove(index) },
                onMoveLeft = { onMove(index, index - 1) },
                onMoveRight = { onMove(index, index + 1) },
                imageDecoder = imageDecoder,
            )
        }
    }
}

@Composable
private fun PendingAttachmentItem(
    attachment: Attachment,
    index: Int,
    count: Int,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    imageDecoder: suspend (Attachment) -> ImageBitmap?,
) {
    var previewState by remember(attachment.content, attachment.cachedUri, attachment.state) {
        mutableStateOf(initialPreviewState(attachment))
    }
    LaunchedEffect(attachment.content, attachment.cachedUri, attachment.state) {
        if (attachment.isImage && attachment.state == AttachmentState.LOADED) {
            previewState = PendingPreviewState.Loading
            previewState = imageDecoder(attachment)?.let(PendingPreviewState::Ready)
                ?: PendingPreviewState.Failed
        }
    }

    val name = attachment.fileName?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.attachment_title)
    val type = attachmentTypeLabel(attachment.renderMode)
    val size = attachment.fileSize?.let { formatAttachmentSize(it) }
    val status = when (previewState) {
        PendingPreviewState.Loading -> stringResource(R.string.pending_attachment_status_loading)
        is PendingPreviewState.Ready -> stringResource(R.string.pending_attachment_status_ready)
        PendingPreviewState.Failed -> attachment.errorMessage?.takeIf(String::isNotBlank)
            ?: stringResource(R.string.pending_attachment_status_failed)
    }
    val summary = listOfNotNull(name, type, size, status).joinToString(", ")
    val previewEnabled = previewState is PendingPreviewState.Ready

    Surface(
        modifier = Modifier
            .width(216.dp)
            .semantics { contentDescription = summary }
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                enabled = previewEnabled,
                onClickLabel = stringResource(R.string.pending_attachment_preview_named, name),
                role = Role.Button,
                onClick = onPreview,
            )
            .testTag("pending-attachment-$index"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PendingAttachmentThumbnail(previewState)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(type, size).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (previewState == PendingPreviewState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = onMoveLeft,
                    enabled = index > 0,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("pending-attachment-move-left-$index"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.pending_attachment_move_left, name),
                    )
                }
                IconButton(
                    onClick = onMoveRight,
                    enabled = index < count - 1,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("pending-attachment-move-right-$index"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.pending_attachment_move_right, name),
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("pending-attachment-remove-$index"),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.pending_attachment_remove, name),
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingAttachmentThumbnail(state: PendingPreviewState) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            PendingPreviewState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
            is PendingPreviewState.Ready -> if (state.bitmap != null) {
                Image(
                    bitmap = state.bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp),
                )
            } else {
                Icon(Icons.Filled.AttachFile, contentDescription = null)
            }
            PendingPreviewState.Failed -> Icon(
                Icons.Filled.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun attachmentTypeLabel(mode: AttachmentRenderMode): String = stringResource(
    when (mode) {
        AttachmentRenderMode.IMAGE -> R.string.attachment_type_image
        AttachmentRenderMode.VIDEO -> R.string.attachment_type_video
        AttachmentRenderMode.AUDIO -> R.string.attachment_type_audio
        AttachmentRenderMode.PDF -> R.string.attachment_type_pdf
        AttachmentRenderMode.TEXT -> R.string.attachment_type_text
        AttachmentRenderMode.GENERIC -> R.string.attachment_type_file
    },
)

private fun initialPreviewState(attachment: Attachment): PendingPreviewState = when {
    attachment.state == AttachmentState.FAILED -> PendingPreviewState.Failed
    attachment.state == AttachmentState.LOADING -> PendingPreviewState.Loading
    attachment.isImage -> PendingPreviewState.Loading
    else -> PendingPreviewState.Ready(bitmap = null)
}

@Composable
internal fun formatAttachmentSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0)
    return when {
        safeBytes >= 1024L * 1024L * 1024L -> stringResource(
            R.string.media_bytes_gb,
            safeBytes / (1024.0 * 1024.0 * 1024.0),
        )
        safeBytes >= 1024L * 1024L -> stringResource(
            R.string.media_bytes_mb,
            safeBytes / (1024.0 * 1024.0),
        )
        safeBytes >= 1024L -> stringResource(R.string.media_bytes_kb, safeBytes / 1024.0)
        else -> stringResource(R.string.media_bytes_b, safeBytes)
    }
}

private suspend fun decodePendingAttachmentImage(attachment: Attachment): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = Base64.decode(attachment.content, Base64.DEFAULT)
            if (bytes.isEmpty()) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sample = 1
            while (
                bounds.outWidth / sample > PENDING_ATTACHMENT_THUMBNAIL_TARGET_PX ||
                bounds.outHeight / sample > PENDING_ATTACHMENT_THUMBNAIL_TARGET_PX
            ) {
                sample *= 2
            }
            decodeOrientedBitmap(
                bytes,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )?.asImageBitmap()
        }.getOrNull()
    }

private sealed interface PendingPreviewState {
    data object Loading : PendingPreviewState
    data class Ready(val bitmap: ImageBitmap?) : PendingPreviewState
    data object Failed : PendingPreviewState
}

private const val PENDING_ATTACHMENT_THUMBNAIL_TARGET_PX = 256

package com.hermesandroid.relay.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.AttachmentRenderMode
import com.hermesandroid.relay.data.AttachmentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One item in a message's attachment render order. Loaded images are grouped
 * into [Gallery] only when there are at least two; every other attachment
 * keeps its original index so retry/manual-fetch callbacks still target the
 * exact [com.hermesandroid.relay.data.ChatMessage.attachments] entry.
 */
internal sealed interface AttachmentLayoutItem {
    data class Single(val attachmentIndex: Int) : AttachmentLayoutItem
    data class Gallery(val attachmentIndices: List<Int>) : AttachmentLayoutItem
}

/**
 * Build the attachment render plan without reordering non-image cards. The
 * gallery occupies the first eligible image's slot and absorbs the remaining
 * loaded images, including images separated by a PDF/file card.
 */
internal fun attachmentLayoutItems(attachments: List<Attachment>): List<AttachmentLayoutItem> {
    return buildList {
        var index = 0
        while (index < attachments.size) {
            if (!attachments[index].isGalleryImage()) {
                add(AttachmentLayoutItem.Single(index))
                index++
                continue
            }

            val run = buildList {
                var cursor = index
                while (cursor < attachments.size && attachments[cursor].isGalleryImage()) {
                    add(cursor)
                    cursor++
                }
            }
            if (run.size >= 2) add(AttachmentLayoutItem.Gallery(run))
            else add(AttachmentLayoutItem.Single(index))
            index += run.size
        }
    }
}

private fun Attachment.isGalleryImage(): Boolean =
    state == AttachmentState.LOADED && renderMode == AttachmentRenderMode.IMAGE

/** Two-column, non-lazy rows for a gallery nested inside the chat LazyColumn. */
internal fun galleryRows(itemCount: Int): List<List<Int>> =
    (0 until itemCount.coerceAtLeast(0)).chunked(GALLERY_COLUMNS)

internal fun galleryPreviewIndices(itemCount: Int): List<Int> =
    (0 until itemCount.coerceAtLeast(0)).take(GALLERY_PREVIEW_LIMIT)

/**
 * Telegram-style media group for two or more loaded image attachments.
 *
 * The chat bubble shows a compact two-column grid. Tapping a tile opens the
 * full-screen horizontal pager at that image; per-image blur reveal, long-
 * press actions, and one-tap Save remain available instead of regressing the
 * single-image attachment behavior.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AttachmentGallery(
    attachments: List<Attachment>,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 280.dp,
) {
    if (attachments.size < 2) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val blurMode = LocalMediaBlurMode.current
    val revealed = remember { mutableStateMapOf<String, Boolean>() }
    var viewerStartIndex by remember { mutableStateOf<Int?>(null) }

    viewerStartIndex?.let { startIndex ->
        AttachmentGalleryViewer(
            attachments = attachments,
            initialIndex = startIndex.coerceIn(attachments.indices),
            initiallyRevealedKeys = revealed
                .filterValues { it }
                .keys,
            onDismiss = { viewerStartIndex = null },
        )
    }

    Column(
        modifier = modifier
            .widthIn(max = maxWidth)
            .fillMaxWidth()
            .semantics { contentDescription = "${attachments.size} image gallery" },
        verticalArrangement = Arrangement.spacedBy(GALLERY_GAP),
    ) {
        val previewIndices = galleryPreviewIndices(attachments.size)
        galleryRows(previewIndices.size).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GALLERY_GAP),
            ) {
                row.forEach { previewIndex ->
                    val galleryIndex = previewIndices[previewIndex]
                    val attachment = attachments[galleryIndex]
                    val attachmentKey = galleryAttachmentKey(attachment, galleryIndex)
                    val blurred = revealed[attachmentKey] != true &&
                        shouldBlurImage(blurMode, attachment.sensitive)
                    var menuExpanded by remember(attachment, galleryIndex) { mutableStateOf(false) }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            // An odd final tile spans both columns without
                            // becoming a full-width square taller than the grid.
                            .aspectRatio(if (row.size == 1) 2f else 1f),
                    ) {
                        BlurredMedia(
                            blurred = blurred,
                            onReveal = { revealed[attachmentKey] = true },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            GalleryImageTile(
                                attachment = attachment,
                                position = galleryIndex,
                                count = attachments.size,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("attachment-gallery-tile-$galleryIndex")
                                    .clip(RoundedCornerShape(GALLERY_CORNER))
                                    .combinedClickable(
                                        onClick = { viewerStartIndex = galleryIndex },
                                        onLongClick = { menuExpanded = true },
                                    ),
                            )
                        }

                        if (!blurred) {
                            SaveOverlayButton(
                                onClick = {
                                    scope.launch { saveAttachment(context, attachment) }
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp),
                            )
                        }

                        AttachmentActionsMenu(
                            expanded = menuExpanded,
                            onDismiss = { menuExpanded = false },
                            context = context,
                            scope = scope,
                            attachment = attachment,
                        )

                        val hiddenCount = attachments.size - GALLERY_PREVIEW_LIMIT
                        if (
                            hiddenCount > 0 &&
                            previewIndex == GALLERY_PREVIEW_LIMIT - 1
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(7.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Black.copy(alpha = 0.68f))
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "+$hiddenCount",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryImageTile(
    attachment: Attachment,
    position: Int,
    count: Int,
    modifier: Modifier,
) {
    val description = listOfNotNull(
        attachment.fileName?.takeIf { it.isNotBlank() },
        "image ${position + 1} of $count",
    ).joinToString(", ")
    val cachedUri = attachment.cachedUri?.takeIf { it.isNotBlank() }

    if (cachedUri != null) {
        SubcomposeAsyncImage(
            model = Uri.parse(cachedUri),
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        ) {
            val state by painter.state.collectAsState()
            when (state) {
                is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                is AsyncImagePainter.State.Loading -> GalleryImagePlaceholder(modifier = Modifier.fillMaxSize())
                else -> GalleryImageFailure(description, Modifier.fillMaxSize())
            }
        }
        return
    }

    var bitmap by remember(attachment.content) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(attachment.content) { mutableStateOf(false) }
    LaunchedEffect(attachment.content) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = android.util.Base64.decode(
                    attachment.content,
                    android.util.Base64.DEFAULT,
                )
                decodeGalleryBitmap(bytes)?.asImageBitmap()
            }.getOrNull()
        }
        if (decoded != null) bitmap = decoded else failed = true
    }

    when {
        bitmap != null -> Image(
            bitmap = bitmap!!,
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        failed -> GalleryImageFailure(description, modifier)
        else -> GalleryImagePlaceholder(modifier)
    }
}

@Composable
private fun GalleryImagePlaceholder(modifier: Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun GalleryImageFailure(description: String, modifier: Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.BrokenImage,
            contentDescription = stringResource(R.string.attachment_load_failed_a11y, description),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}

/** Decode a bounded thumbnail rather than retaining every full-size image. */
private fun decodeGalleryBitmap(bytes: ByteArray): android.graphics.Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (
        bounds.outWidth / sample > GALLERY_DECODE_TARGET_PX ||
        bounds.outHeight / sample > GALLERY_DECODE_TARGET_PX
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private const val GALLERY_COLUMNS = 2
private const val GALLERY_PREVIEW_LIMIT = 4
private const val GALLERY_DECODE_TARGET_PX = 512
private val GALLERY_GAP = 3.dp
private val GALLERY_CORNER = 8.dp

internal fun galleryAttachmentKey(attachment: Attachment, index: Int): String =
    attachment.relayToken?.takeIf { it.isNotBlank() }
        ?: attachment.cachedUri?.takeIf { it.isNotBlank() }
        ?: buildString {
            append(attachment.fileName.orEmpty())
            append('|')
            append(attachment.contentType)
            append('|')
            append(attachment.content.hashCode())
            append('|')
            append(index)
        }

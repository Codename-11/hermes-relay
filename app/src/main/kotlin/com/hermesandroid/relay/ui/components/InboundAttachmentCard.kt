package com.hermesandroid.relay.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import com.hermesandroid.relay.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.hermesandroid.relay.data.BlurMode
import com.hermesandroid.relay.util.MediaSaver
import com.hermesandroid.relay.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Discord-style inline render for any attachment — outbound (user-authored)
 * or inbound (fetched from the relay via a `MEDIA:hermes-relay://` marker).
 *
 * Dispatches on (state × renderMode):
 *   - LOADING   → progress card; "Tap to download" CTA when
 *                 [Attachment.errorMessage] equals [ChatViewModel.MEDIA_TAP_TO_DOWNLOAD];
 *                 a cancel affordance appears when [onCancel] is wired.
 *   - FAILED    → small warning card with retry tap target.
 *   - LOADED    → IMAGE renders inline (bitmap decode); everything else renders
 *                 as a file card with a real thumbnail when one is cheap to make.
 *                 Tapping any loaded attachment opens it IN-APP via
 *                 [AttachmentViewer]; "Open externally" stays in the long-press
 *                 menu. Images flagged sensitive (or all images, per the user's
 *                 [BlurMode]) render behind a tap-to-reveal blur.
 *
 * Outbound attachments always have [AttachmentState.LOADED] so they take the
 * LOADED branch immediately — no behavior change relative to the legacy
 * MessageBubble attachment code.
 *
 * @param onCancel cancels an in-flight LOADING fetch. Null hides the cancel
 *   affordance — the actual cancellation is owned by the fetch path
 *   (ChatViewModel); this component only surfaces the control when wired.
 */
@Composable
fun InboundAttachmentCard(
    attachment: Attachment,
    onRetry: () -> Unit,
    onManualFetch: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 280.dp,
    onCancel: (() -> Unit)? = null,
) {
    when (attachment.state) {
        AttachmentState.LOADING -> LoadingCard(
            attachment = attachment,
            onManualFetch = onManualFetch,
            onCancel = onCancel,
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
    onCancel: (() -> Unit)?,
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
        Column(modifier = Modifier.padding(12.dp)) {
        Row(
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
                val sizeHint = attachment.fileSize?.takeIf { it > 0 }
                    ?.let { " · ${formatBytes(it)}" } ?: ""
                Text(
                    text = if (isManualCta) stringResource(R.string.inbound_attach_tap_download) else stringResource(R.string.inbound_attach_downloading, sizeHint),
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
            // Cancel an in-flight fetch — only shown when the fetch path wires
            // a handler (the fetch lifecycle is owned by ChatViewModel, so the
            // control stays hidden until that worker passes onCancel through).
            if (!isManualCta && onCancel != null) {
                IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.inbound_attach_cd_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        // Active download bar. Indeterminate today: a determinate % needs the
        // fetch path to publish bytes-read against Content-Length (a field on
        // the Attachment model owned by the fetch worker).
        if (!isManualCta) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        }
        } // end outer Column
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
                    text = attachment.errorMessage ?: stringResource(R.string.inbound_attach_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = stringResource(R.string.inbound_attach_tap_retry),
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
 *
 * Tapping opens the in-app [AttachmentViewer] (IMAGE case); long-press surfaces
 * the Open-externally / Share / Save menu inline images previously lacked (B1),
 * and a small download overlay gives a one-tap save affordance (B2). When the
 * image is flagged sensitive (or the user chose to blur all images) it renders
 * behind a tap-to-reveal cover (C1).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageRender(
    attachment: Attachment,
    modifier: Modifier,
    maxWidth: Dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Decode OFF the main thread — a large inbound image would otherwise block
    // composition. Null while decoding (placeholder); decodeFailed → file card.
    var bitmap by remember(attachment.cachedUri, attachment.content) {
        mutableStateOf<ImageBitmap?>(null)
    }
    var decodeFailed by remember(attachment.cachedUri, attachment.content) {
        mutableStateOf(false)
    }
    LaunchedEffect(attachment.cachedUri, attachment.content) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes: ByteArray? = when {
                    !attachment.cachedUri.isNullOrBlank() ->
                        context.contentResolver.openInputStream(Uri.parse(attachment.cachedUri))
                            ?.use { it.readBytes() }
                    attachment.content.isNotBlank() ->
                        android.util.Base64.decode(attachment.content, android.util.Base64.DEFAULT)
                    else -> null
                }
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }.getOrNull()
        }
        if (decoded != null) bitmap = decoded else decodeFailed = true
    }

    if (decodeFailed) {
        // Couldn't decode — degrade to a generic file card so the user can
        // still tap through to an external viewer.
        FileCardRender(
            attachment = attachment.copy(contentType = "application/octet-stream"),
            modifier = modifier,
            maxWidth = maxWidth
        )
        return
    }

    val blurMode = LocalMediaBlurMode.current
    var revealed by remember(attachment.cachedUri, attachment.content) { mutableStateOf(false) }
    val blurred = !revealed && shouldBlurImage(blurMode, attachment.sensitive)
    var viewerOpen by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    val bmp = bitmap
    if (bmp == null) {
        // Brief placeholder while the bitmap decodes off-thread.
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .height(120.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        return
    }

    if (viewerOpen) {
        AttachmentViewer(
            attachment = attachment,
            onDismiss = { viewerOpen = false },
            initiallyRevealed = revealed,
        )
    }

    Box(modifier = modifier) {
        BlurredMedia(blurred = blurred, onReveal = { revealed = true }) {
            Image(
                bitmap = bmp,
                contentDescription = attachment.fileName,
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClick = { viewerOpen = true },
                        onLongClick = { menuExpanded = true },
                    ),
                contentScale = ContentScale.FillWidth,
            )
        }
        // One-tap save overlay — hidden while the blur cover is up so it
        // doesn't sit over the "tap to reveal" prompt.
        if (!blurred) {
            SaveOverlayButton(
                onClick = { scope.launch { saveAttachment(context, attachment) } },
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }
        AttachmentActionsMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            context = context,
            scope = scope,
            attachment = attachment,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCardRender(
    attachment: Attachment,
    modifier: Modifier,
    maxWidth: Dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (emoji, typeLabel) = emojiAndLabelFor(attachment.renderMode, attachment.contentType)
    var menuExpanded by remember { mutableStateOf(false) }
    var viewerOpen by remember { mutableStateOf(false) }

    // Real thumbnail when one is cheap (video first frame, PDF first page);
    // null falls back to the type emoji.
    val thumbnail = rememberAttachmentThumbnail(attachment)
    val blurMode = LocalMediaBlurMode.current
    val blurThumb = thumbnail != null && shouldBlurThumb(blurMode, attachment)

    if (viewerOpen) {
        // Non-image types don't blur in the viewer; open straight through.
        AttachmentViewer(
            attachment = attachment,
            onDismiss = { viewerOpen = false },
            initiallyRevealed = true,
        )
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .widthIn(max = maxWidth)
            // Tap previews in-app; long-press surfaces Open-externally / Share / Save.
            .combinedClickable(
                onClick = { viewerOpen = true },
                onLongClick = { menuExpanded = true },
            )
    ) {
      Box {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    BlurredMedia(
                        blurred = blurThumb,
                        onReveal = {},
                        revealOnTap = false,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Image(
                            bitmap = thumbnail,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                } else {
                    Text(text = emoji, style = MaterialTheme.typography.headlineSmall)
                }
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
            // Visible one-tap save affordance (B2).
            IconButton(
                onClick = { scope.launch { saveAttachment(context, attachment) } },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = stringResource(R.string.inbound_attach_cd_save),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        AttachmentActionsMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            context = context,
            scope = scope,
            attachment = attachment,
        )
      } // end Box
    }
}

/**
 * Shared long-press menu for any attachment: Open externally / Share / Save.
 * "Open externally" preserves the legacy `ACTION_VIEW` escape hatch now that
 * the default tap previews in-app.
 */
@Composable
private fun AttachmentActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    attachment: Attachment,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.inbound_attach_open)) },
            leadingIcon = { Icon(Icons.Filled.OpenInNew, contentDescription = null) },
            onClick = {
                onDismiss()
                scope.launch { openAttachmentExternally(context, attachment) }
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.inbound_attach_share)) },
            onClick = {
                onDismiss()
                scope.launch { shareAttachment(context, attachment) }
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.inbound_attach_save)) },
            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
            onClick = {
                onDismiss()
                scope.launch { saveAttachment(context, attachment) }
            },
        )
    }
}

/** Small circular download button overlaid on inline images (B2). */
@Composable
private fun SaveOverlayButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
        modifier = modifier,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = stringResource(R.string.inbound_attach_cd_save),
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// --- Save / Share / Open helpers (shared by image + file-card paths) --------

private suspend fun shareAttachment(context: Context, attachment: Attachment) {
    val bytes = attachmentBytes(context, attachment)
    if (bytes == null) {
        attachmentToast(context, context.getString(R.string.inbound_attach_share_failed))
        return
    }
    val uri = MediaSaver.stageForShare(context, bytes, attachment.fileName, attachment.contentType)
    MediaSaver.share(context, uri, attachment.contentType)
}

private suspend fun saveAttachment(context: Context, attachment: Attachment) {
    val bytes = attachmentBytes(context, attachment)
    if (bytes == null) {
        attachmentToast(context, context.getString(R.string.inbound_attach_share_failed))
        return
    }
    val result = if (attachment.renderMode == AttachmentRenderMode.IMAGE) {
        MediaSaver.saveImage(context, bytes, attachment.fileName, attachment.contentType)
    } else {
        MediaSaver.saveFile(context, bytes, attachment.fileName, attachment.contentType)
    }
    when (result) {
        is MediaSaver.SaveResult.Saved ->
            attachmentToast(context, context.getString(R.string.inbound_attach_saved, result.location))
        MediaSaver.SaveResult.UseShareInstead -> {
            val uri = MediaSaver.stageForShare(context, bytes, attachment.fileName, attachment.contentType)
            MediaSaver.share(context, uri, attachment.contentType)
        }
        is MediaSaver.SaveResult.Failed ->
            attachmentToast(context, context.getString(R.string.inbound_attach_save_failed, result.message))
    }
}

private suspend fun openAttachmentExternally(context: Context, attachment: Attachment) {
    val cached = attachment.cachedUri
    val uri = if (!cached.isNullOrBlank()) {
        Uri.parse(cached)
    } else {
        attachmentBytes(context, attachment)?.let {
            MediaSaver.stageForShare(context, it, attachment.fileName, attachment.contentType)
        }
    }
    if (uri != null) {
        MediaSaver.open(context, uri, attachment.contentType)
    } else {
        attachmentToast(context, context.getString(R.string.inbound_attach_open_failed))
    }
}

// --- Thumbnails (A1) --------------------------------------------------------

private fun shouldBlurThumb(blurMode: BlurMode, attachment: Attachment): Boolean {
    if (blurMode == BlurMode.OFF) return false
    // Thumbnails are previews: blur a flagged-sensitive one. ALL_IMAGES blurs
    // image previews; video/PDF frames blur only when explicitly flagged.
    return if (attachment.renderMode == AttachmentRenderMode.IMAGE) {
        shouldBlurImage(blurMode, attachment.sensitive)
    } else {
        attachment.sensitive
    }
}

/**
 * Decode a real thumbnail off the main thread, falling back to null (-> emoji)
 * on any failure. Video -> first frame via [MediaMetadataRetriever]; PDF ->
 * first page via [PdfRenderer]. Images are handled inline by [ImageRender].
 */
@Composable
private fun rememberAttachmentThumbnail(attachment: Attachment): ImageBitmap? {
    val context = LocalContext.current
    var thumb by remember(attachment.cachedUri, attachment.content, attachment.renderMode) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(attachment.cachedUri, attachment.content, attachment.renderMode) {
        thumb = withContext(Dispatchers.IO) {
            runCatching { generateThumbnail(context, attachment) }.getOrNull()
        }
    }
    return thumb
}

private const val THUMB_TARGET_PX = 220

private fun generateThumbnail(context: Context, attachment: Attachment): ImageBitmap? =
    when (attachment.renderMode) {
        AttachmentRenderMode.VIDEO -> videoFrameThumb(context, attachment)
        AttachmentRenderMode.PDF -> pdfFirstPageThumb(context, attachment)
        else -> null
    }

private fun videoFrameThumb(context: Context, attachment: Attachment): ImageBitmap? {
    val uri = attachment.cachedUri?.takeIf { it.isNotBlank() } ?: return null
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, Uri.parse(uri))
        retriever.getFrameAtTime(0)?.let { scaleToThumb(it, THUMB_TARGET_PX).asImageBitmap() }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun pdfFirstPageThumb(context: Context, attachment: Attachment): ImageBitmap? {
    val uri = attachment.cachedUri?.takeIf { it.isNotBlank() } ?: return null
    return try {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) return@use null
                renderer.openPage(0).use { page ->
                    val w = THUMB_TARGET_PX
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp.asImageBitmap()
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}

private fun scaleToThumb(src: Bitmap, targetPx: Int): Bitmap {
    val w = src.width
    val h = src.height
    if (w <= 0 || h <= 0 || (w <= targetPx && h <= targetPx)) return src
    val (tw, th) = if (w >= h) {
        targetPx to (targetPx.toFloat() * h / w).toInt().coerceAtLeast(1)
    } else {
        (targetPx.toFloat() * w / h).toInt().coerceAtLeast(1) to targetPx
    }
    return Bitmap.createScaledBitmap(src, tw, th, true)
}

/**
 * Original bytes behind an attachment — read from the cached `content://` URI
 * when present (inbound), else base64-decoded from the inline content
 * (outbound). Off the main thread; null when neither source is available.
 *
 * `internal` so the in-app [AttachmentViewer] (same package) shares one byte
 * acquisition path for Save / Share / Open-externally rather than duplicating it.
 */
internal suspend fun attachmentBytes(context: Context, attachment: Attachment): ByteArray? {
    val uriStr = attachment.cachedUri
    return when {
        !uriStr.isNullOrBlank() -> MediaSaver.readUriBytes(context, Uri.parse(uriStr))
        attachment.content.isNotBlank() -> withContext(Dispatchers.IO) {
            runCatching {
                android.util.Base64.decode(attachment.content, android.util.Base64.DEFAULT)
            }.getOrNull()
        }
        else -> null
    }
}

private fun attachmentToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

@Composable
private fun emojiAndLabelFor(
    mode: AttachmentRenderMode,
    contentType: String
): Pair<String, String> {
    val imageLabel = stringResource(R.string.inbound_attach_type_image)
    val videoLabel = stringResource(R.string.inbound_attach_type_video)
    val audioLabel = stringResource(R.string.inbound_attach_type_audio)
    val pdfLabel = stringResource(R.string.inbound_attach_type_pdf)
    val textLabel = stringResource(R.string.inbound_attach_type_text)
    val fileLabel = stringResource(R.string.inbound_attach_type_file)
    return when (mode) {
        AttachmentRenderMode.IMAGE -> "\uD83D\uDDBC\uFE0F" to imageLabel
        AttachmentRenderMode.VIDEO -> "\uD83C\uDFAC" to videoLabel
        AttachmentRenderMode.AUDIO -> "\uD83C\uDFB5" to audioLabel
        AttachmentRenderMode.PDF -> "\uD83D\uDCC4" to pdfLabel
        AttachmentRenderMode.TEXT -> "\uD83D\uDCDD" to contentTypeToLabel(contentType, fallback = textLabel)
        AttachmentRenderMode.GENERIC -> "\uD83D\uDCCE" to contentTypeToLabel(contentType, fallback = fileLabel)
    }
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

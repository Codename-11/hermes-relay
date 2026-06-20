package com.hermesandroid.relay.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.SurfaceView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.AttachmentRenderMode
import com.hermesandroid.relay.data.BlurMode
import com.hermesandroid.relay.util.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Sensitive-media blur — shared across the attachment card, inline images, and
// this viewer (see docs/plans/2026-06-18-attachment-experience.md §C).
// ---------------------------------------------------------------------------

/**
 * The user's [BlurMode] made available to the render tree without threading it
 * through every call site. Provided as locally as possible (in `MessageBubble`,
 * read from `MediaSettingsRepository`); the default honors flagged media so a
 * consumer rendered outside a provider still does the safe thing.
 */
val LocalMediaBlurMode = staticCompositionLocalOf { BlurMode.FLAGGED }

/**
 * Whether an image should render behind the tap-to-reveal gate given the user's
 * [blurMode] and the model-emitted [sensitive] flag.
 *
 * - [BlurMode.OFF]        → never.
 * - [BlurMode.ALL_IMAGES] → always (no server support needed).
 * - [BlurMode.FLAGGED]    → only when the agent flagged it sensitive.
 */
fun shouldBlurImage(blurMode: BlurMode, sensitive: Boolean): Boolean = when (blurMode) {
    BlurMode.OFF -> false
    BlurMode.ALL_IMAGES -> true
    BlurMode.FLAGGED -> sensitive
}

/**
 * Wraps image-like [content] behind a blur + "tap to reveal" scrim while
 * [blurred] is true. `Modifier.blur` only takes effect on API 31+ (it is
 * RenderEffect-backed), so on older devices the scrim alpha is raised to fully
 * obscure the still-rendered content rather than leaking it. The whole gate is
 * tappable to [onReveal] when [revealOnTap]; once revealed the caller passes
 * `blurred = false` and the underlying content takes its own clicks again.
 */
@Composable
fun BlurredMedia(
    blurred: Boolean,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
    revealOnTap: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        Box(if (blurred) Modifier.blur(28.dp) else Modifier) { content() }
        if (blurred) {
            val canRenderBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = if (canRenderBlur) 0.28f else 0.95f))
                    .then(if (revealOnTap) Modifier.clickable { onReveal() } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.VisibilityOff,
                        contentDescription = "Sensitive content",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "Sensitive",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                    if (revealOnTap) {
                        Text(
                            text = "Tap to reveal",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pinch-to-zoom + pan + double-tap-to-toggle (1×/2.5×) gesture stack, factored
 * out so both [ChatImageViewer] and [AttachmentViewer]'s IMAGE case share one
 * implementation. State is local to the modifier instance.
 */
@Composable
fun Modifier.zoomable(maxScale: Float = 6f): Modifier {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    return this
        .pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, maxScale)
                offset = if (scale > 1f) offset + pan else Offset.Zero
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                },
            )
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
}

// ---------------------------------------------------------------------------
// AttachmentViewer — one full-screen modal that dispatches on renderMode and
// shares a single Share / Save / Open-externally / Close toolbar across types.
// ---------------------------------------------------------------------------

/**
 * In-app full-screen viewer for any LOADED [Attachment]. Tapping an attachment
 * opens this instead of leaving the app via `ACTION_VIEW`; "Open externally"
 * remains available from the toolbar.
 *
 * Dispatches on [Attachment.renderMode]:
 *  - IMAGE → zoomable image (Coil from the cached URI, or a decoded bitmap),
 *            honoring the sensitive blur gate.
 *  - VIDEO → ExoPlayer on a hand-wired [SurfaceView] with transport controls.
 *  - AUDIO → ExoPlayer mini-player with scrubber + a Visualizer amplitude meter.
 *  - PDF   → [PdfRenderer] paginated pages in a LazyColumn.
 *  - TEXT  → in-app monospace text viewer.
 *  - GENERIC → a notice that routes to Open externally.
 *
 * @param initiallyRevealed seed for the per-attachment reveal state. Callers
 *   that already revealed the thumbnail (the usual flow) pass `true` so the
 *   viewer doesn't re-blur; a direct open starts gated.
 */
@Composable
fun AttachmentViewer(
    attachment: Attachment,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyRevealed: Boolean = false,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val context = LocalContext.current
        AllowDeviceRotation()
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }

        val blurMode = LocalMediaBlurMode.current
        var revealed by remember(attachment.cachedUri, attachment.relayToken) {
            mutableStateOf(initiallyRevealed)
        }
        val blurred = !revealed &&
            attachment.renderMode == AttachmentRenderMode.IMAGE &&
            shouldBlurImage(blurMode, attachment.sensitive)

        val title = attachment.fileName
            ?: attachment.contentType.substringBefore(';').ifBlank { "Attachment" }

        // --- One shared Share / Save / Open-externally action set ----------
        fun runWithBytes(action: suspend (ByteArray) -> Unit) {
            scope.launch {
                busy = true
                val bytes = attachmentBytes(context, attachment)
                if (bytes == null) {
                    busy = false
                    viewerToast(context, "Couldn't read this file")
                    return@launch
                }
                action(bytes)
                busy = false
            }
        }

        val onShare = {
            runWithBytes { bytes ->
                val uri = MediaSaver.stageForShare(context, bytes, attachment.fileName, attachment.contentType)
                MediaSaver.share(context, uri, attachment.contentType)
            }
        }
        val onSave = {
            runWithBytes { bytes ->
                val result = if (attachment.renderMode == AttachmentRenderMode.IMAGE) {
                    MediaSaver.saveImage(context, bytes, attachment.fileName, attachment.contentType)
                } else {
                    MediaSaver.saveFile(context, bytes, attachment.fileName, attachment.contentType)
                }
                when (result) {
                    is MediaSaver.SaveResult.Saved ->
                        viewerToast(context, "Saved to ${result.location}")
                    MediaSaver.SaveResult.UseShareInstead -> {
                        val uri = MediaSaver.stageForShare(context, bytes, attachment.fileName, attachment.contentType)
                        MediaSaver.share(context, uri, attachment.contentType)
                    }
                    is MediaSaver.SaveResult.Failed ->
                        viewerToast(context, "Save failed: ${result.message}")
                }
            }
        }
        val onOpenExternal = {
            val cached = attachment.cachedUri
            if (!cached.isNullOrBlank()) {
                MediaSaver.open(context, Uri.parse(cached), attachment.contentType)
            } else {
                runWithBytes { bytes ->
                    val uri = MediaSaver.stageForShare(context, bytes, attachment.fileName, attachment.contentType)
                    MediaSaver.open(context, uri, attachment.contentType)
                }
            }
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f)),
        ) {
            // Body fills; toolbar floats on top. PDF/TEXT add their own top
            // inset so the first line clears the toolbar.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (attachment.renderMode) {
                    AttachmentRenderMode.IMAGE -> ImageBody(
                        attachment = attachment,
                        blurred = blurred,
                        onReveal = { revealed = true },
                    )
                    AttachmentRenderMode.VIDEO -> VideoBody(attachment)
                    AttachmentRenderMode.AUDIO -> AudioBody(attachment)
                    AttachmentRenderMode.PDF -> PdfBody(attachment)
                    AttachmentRenderMode.TEXT -> TextBody(attachment)
                    AttachmentRenderMode.GENERIC -> GenericBody(attachment, onOpenExternal)
                }
            }

            MediaViewerToolbar(
                title = title,
                busy = busy,
                onShare = onShare,
                onSave = onSave,
                onOpenExternal = onOpenExternal,
                onClose = onDismiss,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/** The single shared control bar used across every attachment type. */
@Composable
private fun MediaViewerToolbar(
    title: String,
    busy: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onOpenExternal: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.35f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, colors = tint) {
            Icon(Icons.Filled.Close, contentDescription = "Close")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        if (busy) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp).padding(end = 4.dp),
            )
        }
        IconButton(onClick = onOpenExternal, colors = tint) {
            Icon(Icons.Filled.OpenInNew, contentDescription = "Open externally")
        }
        IconButton(onClick = onShare, colors = tint) {
            Icon(Icons.Filled.Share, contentDescription = "Share")
        }
        IconButton(onClick = onSave, colors = tint) {
            Icon(Icons.Filled.Download, contentDescription = "Save")
        }
    }
}

// --- IMAGE -----------------------------------------------------------------

@Composable
private fun ImageBody(
    attachment: Attachment,
    blurred: Boolean,
    onReveal: () -> Unit,
) {
    val context = LocalContext.current
    val cached = attachment.cachedUri

    BlurredMedia(
        blurred = blurred,
        onReveal = onReveal,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (!cached.isNullOrBlank()) {
            // Coil straight off the cached content:// URI — decodes off-thread
            // and downsamples large images, with its own loading handling.
            AsyncImage(
                model = Uri.parse(cached),
                contentDescription = attachment.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().zoomable(),
            )
        } else {
            // Inline base64 — decode off-thread with explicit loading/failed
            // states so we don't flash a "couldn't load" notice mid-decode.
            var bitmap by remember(attachment.content) { mutableStateOf<ImageBitmap?>(null) }
            var failed by remember(attachment.content) { mutableStateOf(false) }
            LaunchedEffect(attachment.content) {
                val decoded = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = attachmentBytes(context, attachment)
                        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                    }.getOrNull()
                }
                if (decoded != null) bitmap = decoded else failed = true
            }
            val bmp = bitmap
            when {
                bmp != null -> Image(
                    bitmap = bmp,
                    contentDescription = attachment.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().zoomable(),
                )
                failed -> CenteredNotice("Couldn't load this image")
                else -> CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

// --- VIDEO -----------------------------------------------------------------

@OptIn(UnstableApi::class)
@Composable
private fun VideoBody(attachment: Attachment) {
    val context = LocalContext.current
    val uri = rememberPlayableUri(attachment)

    if (uri == null) {
        CenteredNotice("Preparing video…", spinner = true)
        return
    }

    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var isPlaying by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0L } ?: 0L
            delay(250)
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { player.setVideoSurfaceView(it) }
            },
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(16f / 9f),
        )
        PlaybackControls(
            isPlaying = isPlaying,
            position = position,
            duration = duration,
            onPlayPause = {
                if (player.isPlaying) player.pause() else player.play()
            },
            onSeek = { player.seekTo(it) },
            trailing = {
                IconButton(
                    onClick = {
                        muted = !muted
                        player.volume = if (muted) 0f else 1f
                    },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                ) {
                    Icon(
                        imageVector = if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = if (muted) "Unmute" else "Mute",
                    )
                }
            },
        )
    }
}

// --- AUDIO -----------------------------------------------------------------

@OptIn(UnstableApi::class)
@Composable
private fun AudioBody(attachment: Attachment) {
    val context = LocalContext.current
    val uri = rememberPlayableUri(attachment)

    if (uri == null) {
        CenteredNotice("Preparing audio…", spinner = true)
        return
    }

    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    var amplitude by remember { mutableStateOf(0f) }
    var visualizer by remember { mutableStateOf<Visualizer?>(null) }

    DisposableEffect(player) {
        onDispose {
            runCatching { visualizer?.enabled = false }
            runCatching { visualizer?.release() }
            visualizer = null
            player.release()
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) amplitude = 0f
                // Attach the Visualizer the first time playback starts — the
                // audio session id is allocated by then (reuse of the
                // VoicePlayer pattern). Failures (OEM / permission) are
                // swallowed so the player still works without the meter.
                if (playing && visualizer == null) {
                    visualizer = runCatching {
                        buildAmplitudeVisualizer(player.audioSessionId) { amplitude = it }
                    }.getOrNull()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0L } ?: 0L
            delay(250)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AmplitudeMeter(amplitude = amplitude, active = isPlaying)
        Spacer(Modifier.height(24.dp))
        Text(
            text = attachment.fileName ?: "Audio",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
        )
        Spacer(Modifier.height(16.dp))
        PlaybackControls(
            isPlaying = isPlaying,
            position = position,
            duration = duration,
            onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
            onSeek = { player.seekTo(it) },
        )
    }
}

/** A row of bars whose heights track the live [amplitude] (0..1). */
@Composable
private fun AmplitudeMeter(amplitude: Float, active: Boolean) {
    val bars = 28
    // Static per-bar weights give the meter a waveform silhouette so a single
    // amplitude value reads as a level meter rather than a flat block.
    Row(
        modifier = Modifier.fillMaxWidth().height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(bars) { i ->
            val weight = barWeight(i, bars)
            val frac = if (active) (0.08f + amplitude * weight).coerceIn(0.04f, 1f) else 0.06f
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(frac)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.85f)),
            )
        }
    }
}

private fun barWeight(index: Int, count: Int): Float {
    // Triangular window — tallest in the middle, tapering to the edges.
    val center = (count - 1) / 2f
    val dist = kotlin.math.abs(index - center) / center
    return (1f - dist * 0.7f).coerceIn(0.3f, 1f)
}

// --- PDF -------------------------------------------------------------------

private class PdfDoc(
    val renderer: PdfRenderer,
    val pfd: ParcelFileDescriptor,
    val mutex: Mutex,
) {
    @Volatile
    private var closed = false
    val isClosed: Boolean get() = closed

    // Captured ONCE at open time. A PDF's page count is immutable, and reading
    // it from the renderer lazily races onDispose: a late LazyColumn measure
    // pass calls getPageCount() AFTER close() ran → IllegalStateException
    // "Document already closed" (observed crash 2026-06-20).
    val pageCount: Int = runCatching { renderer.pageCount }.getOrDefault(0)

    fun close() {
        closed = true
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}

@Composable
private fun PdfBody(attachment: Attachment) {
    val context = LocalContext.current
    var doc by remember(attachment.cachedUri, attachment.content) { mutableStateOf<PdfDoc?>(null) }
    var pdfError by remember(attachment.cachedUri, attachment.content) { mutableStateOf<String?>(null) }
    var widthPx by remember { mutableStateOf(0) }

    LaunchedEffect(attachment.cachedUri, attachment.content) {
        val opened = withContext(Dispatchers.IO) {
            runCatching {
                val pfd = attachmentFd(context, attachment)
                    ?: throw IllegalStateException("no file descriptor")
                PdfDoc(PdfRenderer(pfd), pfd, Mutex())
            }.getOrNull()
        }
        if (opened == null) pdfError = "Couldn't open this PDF" else doc = opened
    }
    DisposableEffect(doc) { onDispose { doc?.close() } }

    val current = doc
    when {
        pdfError != null -> CenteredNotice(pdfError!!)
        current == null -> CenteredNotice("Rendering PDF…", spinner = true)
        else -> LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { widthPx = it.width }
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top spacer clears the floating toolbar.
            item { Spacer(Modifier.height(64.dp)) }
            items(current.pageCount) { index ->
                PdfPage(doc = current, index = index, widthPx = widthPx)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDoc, index: Int, widthPx: Int) {
    var bitmap by remember(index, widthPx) { mutableStateOf<ImageBitmap?>(null) }
    var ratio by remember(index) { mutableStateOf(1.4f) }

    LaunchedEffect(index, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        val rendered = withContext(Dispatchers.IO) {
            doc.mutex.withLock {
                if (doc.isClosed) return@withLock null
                runCatching {
                    doc.renderer.openPage(index).use { page ->
                        val w = widthPx
                        val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        ratio = page.width.toFloat() / page.height.toFloat()
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp.asImageBitmap()
                    }
                }.getOrNull()
            }
        }
        if (rendered != null) bitmap = rendered
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio.coerceIn(0.4f, 2.5f))
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        }
    }
}

// --- TEXT ------------------------------------------------------------------

@Composable
private fun TextBody(attachment: Attachment) {
    val context = LocalContext.current
    var text by remember(attachment.cachedUri, attachment.content) { mutableStateOf<String?>(null) }

    LaunchedEffect(attachment.cachedUri, attachment.content) {
        text = withContext(Dispatchers.IO) {
            val bytes = attachmentBytes(context, attachment) ?: return@withContext null
            // Cap to keep huge logs from blowing up text layout / memory.
            val capped = if (bytes.size > MAX_TEXT_BYTES) bytes.copyOf(MAX_TEXT_BYTES) else bytes
            runCatching { String(capped, Charsets.UTF_8) }.getOrNull()
        }
    }

    val body = text
    when {
        body == null -> CenteredNotice("Loading…", spinner = true)
        else -> SelectionContainer(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 56.dp, start = 12.dp, end = 12.dp, bottom = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.92f),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

// --- GENERIC ---------------------------------------------------------------

@Composable
private fun GenericBody(attachment: Attachment, onOpenExternal: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = attachment.fileName ?: "File",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "No in-app preview for this type.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.OutlinedButton(onClick = onOpenExternal) {
            Text("Open externally")
        }
    }
}

// --- Shared sub-pieces -----------------------------------------------------

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    var sliderPos by remember { mutableStateOf<Float?>(null) }
    val tint = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Slider(
            value = (sliderPos ?: position.toFloat()).coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
            onValueChange = { sliderPos = it },
            onValueChangeFinished = {
                sliderPos?.let { onSeek(it.toLong()) }
                sliderPos = null
            },
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            enabled = duration > 0L,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlayPause, colors = tint) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }
            Text(
                text = "${formatTime(position)} / ${formatTime(duration)}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
    }
}

@Composable
private fun CenteredNotice(message: String, spinner: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (spinner) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(12.dp))
        }
        Text(text = message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A URI ExoPlayer / PdfRenderer can read. Inbound media already has a cached
 * `content://` URI; inline base64 content is materialized to a private cache
 * file once (keyed on identity) so the player has something to open.
 */
@Composable
private fun rememberPlayableUri(attachment: Attachment): Uri? {
    val context = LocalContext.current
    var uri by remember(attachment.cachedUri, attachment.content) { mutableStateOf<Uri?>(null) }
    LaunchedEffect(attachment.cachedUri, attachment.content) {
        uri = withContext(Dispatchers.IO) { resolvePlayableUri(context, attachment) }
    }
    return uri
}

private fun viewerToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private const val MAX_TEXT_BYTES = 2 * 1024 * 1024

// --- Non-composable IO helpers ---------------------------------------------

private fun resolvePlayableUri(context: Context, attachment: Attachment): Uri? {
    val cached = attachment.cachedUri
    if (!cached.isNullOrBlank()) return runCatching { Uri.parse(cached) }.getOrNull()
    if (attachment.content.isBlank()) return null
    val bytes = runCatching {
        android.util.Base64.decode(attachment.content, android.util.Base64.DEFAULT)
    }.getOrNull() ?: return null
    return runCatching {
        val dir = File(context.cacheDir, "hermes-media-view").apply { if (!exists()) mkdirs() }
        val file = File(dir, MediaSaver.ensureNamed(attachment.fileName, attachment.contentType))
        file.writeBytes(bytes)
        Uri.fromFile(file)
    }.getOrNull()
}

private fun attachmentFd(context: Context, attachment: Attachment): ParcelFileDescriptor? {
    val cached = attachment.cachedUri
    if (!cached.isNullOrBlank()) {
        return runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(cached), "r")
        }.getOrNull()
    }
    if (attachment.content.isBlank()) return null
    val bytes = runCatching {
        android.util.Base64.decode(attachment.content, android.util.Base64.DEFAULT)
    }.getOrNull() ?: return null
    return runCatching {
        val dir = File(context.cacheDir, "hermes-media-view").apply { if (!exists()) mkdirs() }
        val file = File(dir, MediaSaver.ensureNamed(attachment.fileName, attachment.contentType))
        file.writeBytes(bytes)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }.getOrNull()
}

/**
 * Visualizer attached to [sessionId], emitting RMS amplitude (0..1) via
 * [onAmplitude]. Mirrors VoicePlayer's RMS reduction. Returns the live
 * Visualizer so the caller can release it; throws if the device refuses it.
 */
private fun buildAmplitudeVisualizer(sessionId: Int, onAmplitude: (Float) -> Unit): Visualizer? {
    if (sessionId == 0) return null
    val viz = Visualizer(sessionId)
    viz.captureSize = 1024.coerceIn(
        Visualizer.getCaptureSizeRange()[0],
        Visualizer.getCaptureSizeRange()[1],
    )
    viz.setDataCaptureListener(
        object : Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                if (waveform == null || waveform.isEmpty()) return
                onAmplitude(computeRms(waveform))
            }

            override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) { /* unused */ }
        },
        Visualizer.getMaxCaptureRate() / 2,
        true,
        false,
    )
    viz.enabled = true
    return viz
}

private fun computeRms(waveform: ByteArray): Float {
    if (waveform.isEmpty()) return 0f
    var sumSq = 0.0
    for (b in waveform) {
        val sample = (b.toInt() and 0xFF) - 128
        sumSq += (sample * sample).toDouble()
    }
    val rms = sqrt(sumSq / waveform.size)
    val normalized = (rms / 128.0).toFloat()
    return if (normalized.isNaN() || normalized.isInfinite()) 0f else normalized.coerceIn(0f, 1f)
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

package com.hermesandroid.relay.ui.components

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.hermesandroid.relay.util.MediaSaver
import kotlinx.coroutines.launch

/**
 * What the [ChatImageViewer] displays and how it obtains bytes for Save/Share.
 *
 * Display and byte-acquisition are decoupled on purpose: a remote image
 * displays straight from its URL via Coil but fetches bytes over HTTP, while an
 * inbound attachment displays from an already-decoded [ImageBitmap] but reads
 * its original bytes back from the cached `content://` URI — so Save preserves
 * the real file, not a re-encode.
 */
sealed interface ChatImageViewerSource {
    val displayName: String
    val mime: String

    /** Returns the bytes to save/share, or null if they can't be obtained. */
    val bytesProvider: suspend () -> ByteArray?

    /** Display via Coil from any Coil-native model (URL string, content:// Uri). */
    data class Coil(
        val model: Any,
        override val displayName: String,
        override val mime: String,
        override val bytesProvider: suspend () -> ByteArray?,
    ) : ChatImageViewerSource

    /** Display from an already-decoded bitmap (outbound / cached attachments). */
    data class Bitmap(
        val bitmap: ImageBitmap,
        override val displayName: String,
        override val mime: String,
        override val bytesProvider: suspend () -> ByteArray?,
    ) : ChatImageViewerSource
}

/**
 * Full-screen image viewer dialog: pinch-to-zoom + pan (double-tap to toggle
 * 1×/2.5×), with overlaid Share / Save / Close controls. Save lands in
 * `Pictures/Hermes-Relay` on Android 10+; on older versions (or any failure
 * path) it falls back to the share sheet via [MediaSaver].
 */
@Composable
fun ChatImageViewer(
    source: ChatImageViewerSource,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var busy by remember { mutableStateOf(false) }

        val gestureModifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f)),
            contentAlignment = Alignment.Center,
        ) {
            when (source) {
                is ChatImageViewerSource.Coil -> AsyncImage(
                    model = source.model,
                    contentDescription = source.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = gestureModifier,
                )

                is ChatImageViewerSource.Bitmap -> Image(
                    bitmap = source.bitmap,
                    contentDescription = source.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = gestureModifier,
                )
            }

            if (busy) {
                CircularProgressIndicator(color = Color.White)
            }

            // Control bar — top-right, inset past the status bar / notch.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val tint = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                IconButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            val bytes = runCatching { source.bytesProvider() }.getOrNull()
                            busy = false
                            if (bytes == null) {
                                toast(context, "Couldn't load this image")
                                return@launch
                            }
                            val uri = MediaSaver.stageForShare(context, bytes, source.displayName, source.mime)
                            MediaSaver.share(context, uri, source.mime)
                        }
                    },
                    colors = tint,
                ) {
                    Icon(Icons.Filled.Share, contentDescription = "Share")
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            val bytes = runCatching { source.bytesProvider() }.getOrNull()
                            if (bytes == null) {
                                busy = false
                                toast(context, "Couldn't load this image")
                                return@launch
                            }
                            when (val result = MediaSaver.saveImage(context, bytes, source.displayName, source.mime)) {
                                is MediaSaver.SaveResult.Saved -> {
                                    busy = false
                                    toast(context, "Saved to ${result.location}")
                                }
                                MediaSaver.SaveResult.UseShareInstead -> {
                                    busy = false
                                    val uri = MediaSaver.stageForShare(context, bytes, source.displayName, source.mime)
                                    MediaSaver.share(context, uri, source.mime)
                                }
                                is MediaSaver.SaveResult.Failed -> {
                                    busy = false
                                    toast(context, "Save failed: ${result.message}")
                                }
                            }
                        }
                    },
                    colors = tint,
                ) {
                    Icon(Icons.Filled.Download, contentDescription = "Save")
                }
                IconButton(onClick = onDismiss, colors = tint) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
        }
    }
}

private fun toast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

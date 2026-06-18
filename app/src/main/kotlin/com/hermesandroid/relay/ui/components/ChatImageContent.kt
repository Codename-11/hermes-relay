package com.hermesandroid.relay.ui.components

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.hermesandroid.relay.util.MediaSaver
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One markdown image reference (`![alt](src)`) pulled out of an assistant
 * message so it can be rendered as a real image (or a graceful inline notice)
 * instead of the empty/blank element the markdown renderer produces for it.
 */
data class ChatInlineImage(val alt: String, val src: String)

// `![alt](src)` and `![alt](src "title")`. src = first non-space, non-`)` run.
private val MARKDOWN_IMAGE_REGEX = Regex("""!\[([^\]]*)]\(([^)\s]+)[^)]*\)""")

/**
 * Resolves a server-local image path — an absolute path the agent put in a
 * markdown image `![alt](/abs/path)` — to raw bytes, via the relay's
 * `/media/by-path` route when a relay session is paired. Returns null when no
 * relay is available or the fetch fails, so the renderer falls back to the
 * "this image is on the server" notice. Provided by ChatScreen from
 * [com.hermesandroid.relay.viewmodel.ChatViewModel.resolveServerImage]; the
 * default is null, which preserves the standard (no-plugin) behavior where a
 * server-local path simply can't be shown.
 */
fun interface RelayServerImageResolver {
    suspend fun fetch(serverPath: String): ByteArray?
}

val LocalRelayServerImageResolver = staticCompositionLocalOf<RelayServerImageResolver?> { null }

/**
 * Small bounded LRU of decoded inline images keyed by server path, so a
 * markdown image survives LazyColumn item recycling (scroll away + back)
 * without re-fetching+decoding over the relay each time. Bounded to keep bitmap
 * memory in check; eldest-accessed is evicted first.
 */
private const val INLINE_IMAGE_CACHE_MAX = 12
private val inlineImageCache =
    object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ImageBitmap>,
        ): Boolean = size > INLINE_IMAGE_CACHE_MAX
    }

private fun cachedInlineImage(key: String): ImageBitmap? =
    synchronized(inlineImageCache) { inlineImageCache[key] }

private fun putInlineImage(key: String, bitmap: ImageBitmap) {
    synchronized(inlineImageCache) { inlineImageCache[key] = bitmap }
}

/**
 * Split assistant [content] into (markdown body without image links, parsed
 * images). The `![...]()` token is removed from the body so it doesn't render
 * as a broken/empty element; surrounding prose is preserved.
 */
fun extractChatInlineImages(content: String): Pair<String, List<ChatInlineImage>> {
    if (!content.contains("![")) return content to emptyList()
    val images = mutableListOf<ChatInlineImage>()
    val stripped = MARKDOWN_IMAGE_REGEX.replace(content) { m ->
        images += ChatInlineImage(alt = m.groupValues[1].trim(), src = m.groupValues[2].trim())
        ""
    }
    if (images.isEmpty()) return content to emptyList()
    // Collapse the blank lines the removal can leave behind.
    return stripped.replace(Regex("\n{3,}"), "\n\n").trim() to images
}

private fun ChatInlineImage.isRemote(): Boolean {
    val s = src.lowercase()
    return s.startsWith("http://") || s.startsWith("https://")
}

/**
 * An absolute server-side path (e.g. `/home/agent/out.png`) — what the relay's
 * `/media/by-path` route expects. Not a remote URL and not a relative ref.
 */
private fun ChatInlineImage.isServerLocalPath(): Boolean = src.startsWith("/")

/**
 * Render generated/inline images for an assistant bubble. Remote `http(s)`
 * URLs load via Coil with loading/error states; anything else (a server-local
 * file path, `file://`, a relative path) degrades to an inline notice that
 * explains WHY it can't be shown rather than rendering blank.
 */
@Composable
fun ChatInlineImages(
    images: List<ChatInlineImage>,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 280.dp,
) {
    if (images.isEmpty()) return
    val relayResolver = LocalRelayServerImageResolver.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        images.forEach { image ->
            when {
                image.isRemote() -> RemoteChatImage(image, maxWidth)
                // A relay session is paired and the agent referenced a
                // server-local file — fetch it through /media/by-path and
                // render it inline instead of showing the "on the server"
                // notice. Falls back to the notice if the fetch fails.
                relayResolver != null && image.isServerLocalPath() ->
                    RelayServerImage(image, maxWidth, relayResolver)
                else -> UnrenderableImageNotice(image)
            }
        }
    }
}

@Composable
private fun RemoteChatImage(image: ChatInlineImage, maxWidth: Dp) {
    var viewerOpen by remember { mutableStateOf(false) }
    if (viewerOpen) {
        ChatImageViewer(
            source = ChatImageViewerSource.Coil(
                model = image.src,
                displayName = image.alt.ifBlank { "image" },
                mime = "image/*",
                bytesProvider = { MediaSaver.fetchRemoteBytes(image.src).first },
            ),
            onDismiss = { viewerOpen = false },
        )
    }
    SubcomposeAsyncImage(
        model = image.src,
        contentDescription = image.alt.ifBlank { "Generated image" },
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .widthIn(max = maxWidth)
            .heightIn(max = 360.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { viewerOpen = true },
    ) {
        val state by painter.state.collectAsState()
        when (state) {
            is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
            is AsyncImagePainter.State.Loading -> Box(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            // Error / Empty — couldn't load. Offer to open it externally.
            else -> UnrenderableImageNotice(image, reason = "Couldn't load this image.")
        }
    }
}

private sealed interface RelayImagePhase {
    data object Loading : RelayImagePhase
    data class Loaded(val bitmap: ImageBitmap) : RelayImagePhase
    data object Failed : RelayImagePhase
}

/**
 * A server-local image fetched through the relay's `/media/by-path` route. Shows
 * a brief loading box, then the decoded image (tap → full-screen viewer), or the
 * standard notice if the relay can't return it (unpaired / sandboxed / missing).
 * Decoded bitmaps are cached by path so scrolling doesn't re-fetch.
 */
@Composable
private fun RelayServerImage(
    image: ChatInlineImage,
    maxWidth: Dp,
    resolver: RelayServerImageResolver,
) {
    var phase by remember(image.src) {
        mutableStateOf<RelayImagePhase>(
            cachedInlineImage(image.src)
                ?.let { RelayImagePhase.Loaded(it) }
                ?: RelayImagePhase.Loading,
        )
    }
    LaunchedEffect(image.src) {
        if (phase is RelayImagePhase.Loaded) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            val bytes = runCatching { resolver.fetch(image.src) }.getOrNull()
                ?: return@withContext null
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                .getOrNull()
                ?.asImageBitmap()
        }
        phase = if (bitmap != null) {
            putInlineImage(image.src, bitmap)
            RelayImagePhase.Loaded(bitmap)
        } else {
            RelayImagePhase.Failed
        }
    }
    when (val current = phase) {
        RelayImagePhase.Loading -> Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        is RelayImagePhase.Loaded -> RelayServerImageContent(image, current.bitmap, maxWidth, resolver)
        RelayImagePhase.Failed -> UnrenderableImageNotice(image)
    }
}

@Composable
private fun RelayServerImageContent(
    image: ChatInlineImage,
    bitmap: ImageBitmap,
    maxWidth: Dp,
    resolver: RelayServerImageResolver,
) {
    var viewerOpen by remember { mutableStateOf(false) }
    if (viewerOpen) {
        ChatImageViewer(
            source = ChatImageViewerSource.Bitmap(
                bitmap = bitmap,
                displayName = image.alt.ifBlank {
                    image.src.substringAfterLast('/').ifBlank { "image" }
                },
                mime = "image/*",
                // Save/Share re-fetch the original bytes on demand so we don't
                // hold them in memory next to the decoded bitmap.
                bytesProvider = { resolver.fetch(image.src) },
            ),
            onDismiss = { viewerOpen = false },
        )
    }
    androidx.compose.foundation.Image(
        bitmap = bitmap,
        contentDescription = image.alt.ifBlank { "Generated image" },
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .widthIn(max = maxWidth)
            .heightIn(max = 360.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { viewerOpen = true },
    )
}

@Composable
private fun UnrenderableImageNotice(
    image: ChatInlineImage,
    reason: String = "This image is on the server and can't be shown here.",
) {
    val context = LocalContext.current
    val remote = image.isRemote()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .widthIn(max = 280.dp)
            .then(
                if (remote) {
                    Modifier.clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, image.src.toUri()))
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (remote) Icons.Filled.BrokenImage else Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = image.alt.ifBlank { "Image" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (remote) "Tap to open · ${image.src}" else "$reason\n${image.src}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

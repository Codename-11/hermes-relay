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
 *
 * [sensitive] carries the standard-path sensitivity convention (C3): a
 * Telegram-style spoiler wrap `||![alt](url)||` or an alt-text sentinel
 * (`![nsfw]` / `![sensitive]` / `![spoiler]`). When set, the image renders
 * behind the same tap-to-reveal blur gate as relay-flagged media, honored per
 * the user's [com.hermesandroid.relay.data.BlurMode].
 */
data class ChatInlineImage(
    val alt: String,
    val src: String,
    val sensitive: Boolean = false,
) {
    /**
     * Alt text fit to show as a caption (D7) — null when the alt is blank or is
     * just a sensitivity sentinel (which is a flag, not a caption).
     */
    fun caption(): String? {
        val a = alt.trim()
        if (a.isEmpty()) return null
        return if (isSensitiveAltText(a)) null else a
    }
}

// `||` (optional) + `![alt](src)` / `![alt](src "title")` + `||` (optional).
// src = first non-space, non-`)` run. The optional `||` pair is the Telegram
// spoiler-wrap convention; both sides present ⇒ sensitive.
// Group 3 (the URL) accepts either the markdown angle-bracket form
// `<…>` (which legally contains spaces — what models emit for paths like
// `/mnt/media/Coralee Adshade/x.jpg`) OR a plain whitespace-free run. The
// brackets are stripped + the path percent-decoded in [normalizeImageSrc].
private val MARKDOWN_IMAGE_REGEX =
    Regex("""(\|\|)?!\[([^\]]*)]\((<[^>\n]*>|[^)\s]+)[^)]*\)(\|\|)?""")

private val SENSITIVE_ALT_TOKENS = setOf("nsfw", "sensitive", "spoiler")

/** True when alt text is (or is prefixed by) a sensitivity sentinel. */
private fun isSensitiveAltText(alt: String): Boolean {
    val a = alt.trim().lowercase().removeSurrounding("[", "]")
    if (a in SENSITIVE_ALT_TOKENS) return true
    return SENSITIVE_ALT_TOKENS.any { a.startsWith("$it:") || a.startsWith("$it ") }
}

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
/**
 * Outcome of a relay server-image fetch. Deliberately a purpose-built type and
 * NOT `kotlin.Result`: a `suspend` function must not return `Result<T>` — the
 * coroutine state machine's own `Result` wrapper collides with it and throws
 * `kotlin.Result cannot be cast to ...` at runtime (observed crash 2026-06-20,
 * `RelayServerImage` on app open with a server-local image in history).
 */
sealed interface ServerImageResult {
    class Success(val bytes: ByteArray, val sensitive: Boolean = false) : ServerImageResult
    class Failure(val reason: String) : ServerImageResult
}

fun interface RelayServerImageResolver {
    /** Fetch the server-local file's bytes over the relay, or a
     *  [ServerImageResult.Failure] whose reason explains why (unpaired /
     *  sandboxed / missing / decode) so the UI can surface it instead of a
     *  generic placeholder. */
    suspend fun fetch(serverPath: String): ServerImageResult
}

val LocalRelayServerImageResolver = staticCompositionLocalOf<RelayServerImageResolver?> { null }

/**
 * Small bounded LRU of decoded inline images keyed by server path, so a
 * markdown image survives LazyColumn item recycling (scroll away + back)
 * without re-fetching+decoding over the relay each time. Bounded to keep bitmap
 * memory in check; eldest-accessed is evicted first.
 */
private const val INLINE_IMAGE_CACHE_MAX = 12
private data class CachedInlineImage(
    val bitmap: ImageBitmap,
    val sensitive: Boolean,
)

private val inlineImageCache =
    object : LinkedHashMap<String, CachedInlineImage>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CachedInlineImage>,
        ): Boolean = size > INLINE_IMAGE_CACHE_MAX
    }

private fun cachedInlineImage(key: String): CachedInlineImage? =
    synchronized(inlineImageCache) { inlineImageCache[key] }

private fun putInlineImage(key: String, bitmap: ImageBitmap, sensitive: Boolean) {
    synchronized(inlineImageCache) { inlineImageCache[key] = CachedInlineImage(bitmap, sensitive) }
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
        val spoilerWrapped = m.groupValues[1].isNotEmpty() && m.groupValues[4].isNotEmpty()
        val alt = m.groupValues[2].trim()
        images += ChatInlineImage(
            alt = alt,
            src = normalizeImageSrc(m.groupValues[3].trim()),
            sensitive = spoilerWrapped || isSensitiveAltText(alt),
        )
        ""
    }
    if (images.isEmpty()) return content to emptyList()
    // Collapse the blank lines the removal can leave behind.
    return stripped.replace(Regex("\n{3,}"), "\n\n").trim() to images
}

/**
 * Normalize a markdown image URL into the form the renderer/relay expect:
 *  - Strip markdown angle-bracket wrapping (`<…>`) — models use it for URLs
 *    that contain spaces, but it would otherwise fail the `startsWith("/")`
 *    server-local check.
 *  - Percent-decode absolute paths (e.g. `Coralee%20Adshade` → `Coralee
 *    Adshade`) so `/media/by-path` finds the real file. Remote http(s) URLs are
 *    left verbatim for Coil.
 */
private fun normalizeImageSrc(raw: String): String {
    val unwrapped = raw.removeSurrounding("<", ">").trim()
    return if (unwrapped.startsWith("/")) decodePercentEscapes(unwrapped) else unwrapped
}

/** Decode `%XX` escapes, protecting a literal `+` (which URLDecoder would
 *  otherwise turn into a space). No-op when there's nothing to decode. */
private fun decodePercentEscapes(s: String): String =
    if ('%' !in s) {
        s
    } else {
        try {
            java.net.URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (_: Exception) {
            s
        }
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
                // A server-local file the agent referenced — fetch it through
                // /media/by-path and render inline; on failure the notice shows
                // the ACTUAL reason (for debugging) instead of a generic message.
                image.isServerLocalPath() ->
                    if (relayResolver != null) {
                        RelayServerImage(image, maxWidth, relayResolver)
                    } else {
                        UnrenderableImageNotice(
                            image,
                            reason = "Server image — pair the relay to show it.",
                        )
                    }
                else -> UnrenderableImageNotice(
                    image,
                    reason = "Unsupported image path: ${image.src}",
                )
            }
        }
    }
}

@Composable
private fun RemoteChatImage(image: ChatInlineImage, maxWidth: Dp) {
    var viewerOpen by remember { mutableStateOf(false) }
    val blurMode = LocalMediaBlurMode.current
    var revealed by remember(image.src) { mutableStateOf(false) }
    val blurred = !revealed && shouldBlurImage(blurMode, image.sensitive)
    if (viewerOpen) {
        ChatImageViewer(
            source = ChatImageViewerSource.Coil(
                model = image.src,
                displayName = image.alt.ifBlank { "image" },
                mime = "image/*",
                bytesProvider = { MediaSaver.fetchRemoteBytes(image.src).first },
            ),
            onDismiss = { viewerOpen = false },
            sensitive = image.sensitive,
            initiallyRevealed = revealed,
        )
    }
    InlineImageColumn(image, maxWidth) {
        BlurredMedia(blurred = blurred, onReveal = { revealed = true }) {
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
    }
}

/**
 * Wraps an inline image with its optional caption (D7). The caption is the
 * markdown alt text when it's a real caption (not a sensitivity sentinel).
 */
@Composable
private fun InlineImageColumn(
    image: ChatInlineImage,
    maxWidth: Dp,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        content()
        image.caption()?.let { caption ->
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = maxWidth),
            )
        }
    }
}

private sealed interface RelayImagePhase {
    data object Loading : RelayImagePhase
    data class Loaded(val bitmap: ImageBitmap, val sensitive: Boolean) : RelayImagePhase
    data class Failed(val reason: String?) : RelayImagePhase
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
                ?.let { RelayImagePhase.Loaded(it.bitmap, it.sensitive) }
                ?: RelayImagePhase.Loading,
        )
    }
    LaunchedEffect(image.src) {
        if (phase is RelayImagePhase.Loaded) return@LaunchedEffect
        phase = withContext(Dispatchers.IO) {
            val result = try {
                resolver.fetch(image.src)
            } catch (t: Throwable) {
                ServerImageResult.Failure(t.message ?: "relay fetch failed")
            }
            when (result) {
                is ServerImageResult.Success -> {
                    val bytes = result.bytes
                    val bmp = runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()?.asImageBitmap()
                    if (bmp != null) {
                        putInlineImage(image.src, bmp, result.sensitive)
                        RelayImagePhase.Loaded(bmp, result.sensitive)
                    } else {
                        RelayImagePhase.Failed("fetched ${bytes.size} B but couldn't decode the image")
                    }
                }
                is ServerImageResult.Failure -> RelayImagePhase.Failed(result.reason)
            }
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
        is RelayImagePhase.Loaded -> RelayServerImageContent(
            image,
            current.bitmap,
            current.sensitive,
            maxWidth,
            resolver,
        )
        is RelayImagePhase.Failed -> UnrenderableImageNotice(
            image,
            reason = "Couldn't load ${image.src}" +
                (current.reason?.let { ": $it" } ?: ""),
        )
    }
}

@Composable
private fun RelayServerImageContent(
    image: ChatInlineImage,
    bitmap: ImageBitmap,
    fetchedSensitive: Boolean,
    maxWidth: Dp,
    resolver: RelayServerImageResolver,
) {
    var viewerOpen by remember { mutableStateOf(false) }
    val blurMode = LocalMediaBlurMode.current
    var revealed by remember(image.src) { mutableStateOf(false) }
    val sensitive = image.sensitive || fetchedSensitive
    val blurred = !revealed && shouldBlurImage(blurMode, sensitive)
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
                bytesProvider = { (resolver.fetch(image.src) as? ServerImageResult.Success)?.bytes },
            ),
            onDismiss = { viewerOpen = false },
            sensitive = sensitive,
            initiallyRevealed = revealed,
        )
    }
    InlineImageColumn(image, maxWidth) {
        BlurredMedia(blurred = blurred, onReveal = { revealed = true }) {
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
    }
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

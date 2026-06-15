package com.hermesandroid.relay.ui.components

import android.content.Intent
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * One markdown image reference (`![alt](src)`) pulled out of an assistant
 * message so it can be rendered as a real image (or a graceful inline notice)
 * instead of the empty/blank element the markdown renderer produces for it.
 */
data class ChatInlineImage(val alt: String, val src: String)

// `![alt](src)` and `![alt](src "title")`. src = first non-space, non-`)` run.
private val MARKDOWN_IMAGE_REGEX = Regex("""!\[([^\]]*)]\(([^)\s]+)[^)]*\)""")

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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        images.forEach { image ->
            if (image.isRemote()) {
                RemoteChatImage(image, maxWidth)
            } else {
                UnrenderableImageNotice(image)
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

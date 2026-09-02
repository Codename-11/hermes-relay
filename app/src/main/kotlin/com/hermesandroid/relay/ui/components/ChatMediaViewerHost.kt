package com.hermesandroid.relay.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.BlurMode

private sealed interface ChatMediaViewerRequest {
    val blurMode: BlurMode
    val exportAllowed: Boolean

    data class SingleAttachment(
        val attachment: Attachment,
        val initiallyRevealed: Boolean,
        override val blurMode: BlurMode,
        override val exportAllowed: Boolean,
    ) : ChatMediaViewerRequest

    data class AttachmentGallery(
        val attachments: List<Attachment>,
        val initialIndex: Int,
        val initiallyRevealedKeys: Set<String>,
        override val blurMode: BlurMode,
        override val exportAllowed: Boolean,
    ) : ChatMediaViewerRequest

    data class InlineImage(
        val source: ChatImageViewerSource,
        val sensitive: Boolean,
        val initiallyRevealed: Boolean,
        override val blurMode: BlurMode,
        override val exportAllowed: Boolean,
    ) : ChatMediaViewerRequest
}

@Stable
internal class ChatMediaViewerController {
    private var activeRequest by mutableStateOf<ChatMediaViewerRequest?>(null)

    internal fun openAttachment(
        attachment: Attachment,
        initiallyRevealed: Boolean,
        blurMode: BlurMode,
        exportAllowed: Boolean,
    ) {
        activeRequest = ChatMediaViewerRequest.SingleAttachment(
            attachment = attachment,
            initiallyRevealed = initiallyRevealed,
            blurMode = blurMode,
            exportAllowed = exportAllowed,
        )
    }

    internal fun openGallery(
        attachments: List<Attachment>,
        initialIndex: Int,
        initiallyRevealedKeys: Set<String>,
        blurMode: BlurMode,
        exportAllowed: Boolean,
    ) {
        activeRequest = ChatMediaViewerRequest.AttachmentGallery(
            attachments = attachments,
            initialIndex = initialIndex,
            initiallyRevealedKeys = initiallyRevealedKeys,
            blurMode = blurMode,
            exportAllowed = exportAllowed,
        )
    }

    internal fun openImage(
        source: ChatImageViewerSource,
        sensitive: Boolean,
        initiallyRevealed: Boolean,
        blurMode: BlurMode,
        exportAllowed: Boolean,
    ) {
        activeRequest = ChatMediaViewerRequest.InlineImage(
            source = source,
            sensitive = sensitive,
            initiallyRevealed = initiallyRevealed,
            blurMode = blurMode,
            exportAllowed = exportAllowed,
        )
    }

    internal fun dismiss() {
        activeRequest = null
    }

    @Composable
    internal fun RenderActiveViewer() {
        val request = activeRequest ?: return
        CompositionLocalProvider(
            LocalMediaBlurMode provides request.blurMode,
            LocalImageExportAllowed provides request.exportAllowed,
        ) {
            when (request) {
                is ChatMediaViewerRequest.SingleAttachment -> AttachmentViewer(
                    attachment = request.attachment,
                    onDismiss = ::dismiss,
                    initiallyRevealed = request.initiallyRevealed,
                )
                is ChatMediaViewerRequest.AttachmentGallery -> AttachmentGalleryViewer(
                    attachments = request.attachments,
                    initialIndex = request.initialIndex,
                    onDismiss = ::dismiss,
                    initiallyRevealedKeys = request.initiallyRevealedKeys,
                )
                is ChatMediaViewerRequest.InlineImage -> ChatImageViewer(
                    source = request.source,
                    onDismiss = ::dismiss,
                    sensitive = request.sensitive,
                    initiallyRevealed = request.initiallyRevealed,
                )
            }
        }
    }
}

internal val LocalChatMediaViewerController =
    staticCompositionLocalOf<ChatMediaViewerController?> { null }

/**
 * Owns the active full-screen preview above transcript rows so responsive
 * portrait/landscape reflow cannot dispose the viewer with its source bubble.
 */
@Composable
internal fun ChatMediaViewerHost(
    vararg ownerKeys: Any?,
    content: @Composable () -> Unit,
) {
    // A preview never follows a connection/session/policy owner change. Aside
    // from avoiding stale media, this makes export permission fail closed when
    // supervised policy changes while a viewer is open.
    val controller = remember(*ownerKeys) { ChatMediaViewerController() }
    CompositionLocalProvider(LocalChatMediaViewerController provides controller) {
        content()
        controller.RenderActiveViewer()
    }
}

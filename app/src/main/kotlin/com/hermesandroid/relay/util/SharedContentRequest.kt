package com.hermesandroid.relay.util

import android.content.Intent
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SharedContentPayload(
    val text: String? = null,
    val uriStrings: List<String> = emptyList(),
    val omittedUriCount: Int = 0,
)

internal const val MAX_SHARED_CONTENT_ATTACHMENTS = 10

data class SharedContentDraftRequest(
    val id: Long,
    val payload: SharedContentPayload,
    val preparing: Boolean = false,
    val failed: Boolean = false,
    val ready: Boolean = false,
    val targetConnectionId: String? = null,
    val targetProfileId: String? = null,
    val targetSessionId: String? = null,
)

/**
 * Process-local handoff from Android's sharesheet entry point to the app-owned
 * chat runtime. The request stays pending until the exact fresh draft has
 * restored and ingested it. Identity checks prevent an older async session
 * creation from overwriting or consuming a newer share.
 */
object SharedContentRequest {
    private val nextId = AtomicLong(0L)
    private val _pending = MutableStateFlow<SharedContentDraftRequest?>(null)

    val pending: StateFlow<SharedContentDraftRequest?> = _pending.asStateFlow()

    fun tryRequest(payload: SharedContentPayload?): Boolean {
        payload ?: return false
        _pending.value = SharedContentDraftRequest(
            id = nextId.incrementAndGet(),
            payload = payload,
        )
        return true
    }

    fun markReady(
        id: Long,
        targetConnectionId: String,
        targetProfileId: String,
        targetSessionId: String?,
    ) {
        _pending.update { request ->
            request?.takeIf { it.id == id }?.copy(
                preparing = false,
                failed = false,
                ready = true,
                targetConnectionId = targetConnectionId,
                targetProfileId = targetProfileId,
                targetSessionId = targetSessionId,
            ) ?: request
        }
    }

    fun markPreparing(id: Long) {
        _pending.update { request ->
            request?.takeIf { it.id == id }?.copy(preparing = true, failed = false) ?: request
        }
    }

    fun markFailed(id: Long) {
        _pending.update { request ->
            request?.takeIf { it.id == id }?.copy(preparing = false, failed = true) ?: request
        }
    }

    /** A foreground return is the explicit retry trigger for a failed fresh-draft creation. */
    fun retryFailed() {
        _pending.update { request ->
            request?.takeIf { it.failed }?.copy(failed = false) ?: request
        }
    }

    fun consume(id: Long) {
        _pending.update { request -> request?.takeUnless { it.id == id } }
    }
}

/** Accept Android's single- and multi-item shares when they carry reviewable content. */
internal fun extractSharedContent(
    action: String?,
    texts: List<CharSequence>,
    subject: CharSequence?,
    streamUriStrings: List<String>,
    clipTexts: List<CharSequence>,
    clipUriStrings: List<String>,
): SharedContentPayload? {
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val sharedTextItems = (texts + clipTexts)
        .map(CharSequence::toString)
        .filter(String::isNotBlank)
        .distinct()
    val sharedText = sharedTextItems.takeIf(List<String>::isNotEmpty)?.joinToString("\n")
        ?: subject?.toString()?.takeIf { it.isNotBlank() }
    val eligibleUris = (streamUriStrings + clipUriStrings)
        .filter(::isAllowedSharedContentUri)
        .distinct()
    val uris = eligibleUris.take(MAX_SHARED_CONTENT_ATTACHMENTS)
    if (sharedText == null && uris.isEmpty()) return null
    return SharedContentPayload(
        text = sharedText,
        uriStrings = uris,
        omittedUriCount = eligibleUris.size - uris.size,
    )
}

/** Cross-app binary shares must use Android's grantable content-provider contract. */
internal fun isAllowedSharedContentUri(uriString: String): Boolean {
    val uri = runCatching { URI(uriString) }.getOrNull() ?: return false
    return uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank()
}

internal fun canApplySharedContent(
    request: SharedContentDraftRequest?,
    composerConnectionId: String,
    composerProfileId: String,
    composerSessionId: String,
    draftRestored: Boolean,
): Boolean {
    if (request?.ready != true || !draftRestored) return false
    return composerConnectionId == request.targetConnectionId &&
        composerProfileId == request.targetProfileId &&
        composerSessionId == (request.targetSessionId ?: "new-session")
}

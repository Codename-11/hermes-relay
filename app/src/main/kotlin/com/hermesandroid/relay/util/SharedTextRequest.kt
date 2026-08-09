package com.hermesandroid.relay.util

import android.content.Intent
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SharedTextDraftRequest(
    val id: Long,
    val text: String,
)

/**
 * Process-local handoff from Android's sharesheet entry point to the app-owned
 * chat runtime. A StateFlow keeps a cold-start request alive until RelayApp has
 * initialized its ViewModels; [consume] is identity-checked so an older UI
 * completion cannot clear a newer share intent.
 */
object SharedTextRequest {
    private val nextId = AtomicLong(0L)
    private val _pending = MutableStateFlow<SharedTextDraftRequest?>(null)

    val pending: StateFlow<SharedTextDraftRequest?> = _pending.asStateFlow()

    fun tryRequest(text: CharSequence?): Boolean {
        val value = text?.toString()?.takeIf { it.isNotBlank() } ?: return false
        _pending.value = SharedTextDraftRequest(
            id = nextId.incrementAndGet(),
            text = value,
        )
        return true
    }

    fun consume(id: Long) {
        _pending.update { request -> request?.takeUnless { it.id == id } }
    }
}

/** Accept only Android's single-item text share contract. */
internal fun extractSharedText(
    action: String?,
    mimeType: String?,
    text: CharSequence?,
): String? {
    if (action != Intent.ACTION_SEND) return null
    if (mimeType?.startsWith("text/", ignoreCase = true) != true) return null
    return text?.toString()?.takeIf { it.isNotBlank() }
}

package com.hermesandroid.relay.share

import android.content.Intent
import java.time.OffsetDateTime
import java.time.ZoneOffset

object ShareIntentParser {
    fun parse(intent: Intent?): SharePayload? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val type = intent.type.orEmpty()
        if (!type.startsWith("text/")) return null
        val rawText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        if (rawText.isNullOrBlank()) return null
        return SharePayload(
            text = rawText,
            mimeType = type.ifBlank { "text/plain" },
            sourceApp = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
                ?: intent.`package`,
            timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString(),
        )
    }
}

data class SharePayload(
    val text: String,
    val mimeType: String,
    val sourceApp: String?,
    val timestamp: String,
)

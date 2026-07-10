package com.hermesandroid.relay.voice

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * Syncs provider-native Realtime Agent turns that answered without Hermes.
 *
 * The relay/provider can answer simple prompt-contained questions directly. To
 * keep normal chat and realtime voice as one conversation, those local-only
 * turns are injected into the next Hermes chat/run request as ordinary
 * user/assistant messages before the live user message.
 */
object RealtimeTurnSyncBuilder {
    fun buildSyntheticMessages(history: List<ChatMessage>): JsonArray = buildJsonArray {
        for (msg in history) {
            val trace = msg.realtimeTurn ?: continue
            if (trace.syncedToServer) continue
            val userText = trace.userText.trim()
            val assistantText = trace.assistantText.trim()
            if (userText.isBlank() || assistantText.isBlank()) continue

            addJsonObject {
                put("role", MessageRole.USER.name.lowercase())
                put("content", userText.take(MAX_CONTENT_CHARS))
            }
            addJsonObject {
                put("role", MessageRole.ASSISTANT.name.lowercase())
                put("content", buildAssistantContent(trace).take(MAX_CONTENT_CHARS))
            }
        }
    }

    fun hasUnsynced(history: List<ChatMessage>): Boolean =
        history.any { it.realtimeTurn?.syncedToServer == false }

    private fun buildAssistantContent(trace: com.hermesandroid.relay.data.RealtimeTurnTrace): String {
        val provenance = buildList {
            trace.provider?.takeIf { it.isNotBlank() }?.let { add("provider=$it") }
            trace.model?.takeIf { it.isNotBlank() }?.let { add("model=$it") }
            trace.voice?.takeIf { it.isNotBlank() }?.let { add("voice=$it") }
        }.joinToString(", ")
        return if (provenance.isBlank()) {
            trace.assistantText
        } else {
            "${trace.assistantText}\n\n$PROVENANCE_PREFIX$provenance]"
        }
    }

    /**
     * Detect + strip the provenance marker [buildAssistantContent] appends to
     * a synced provider-answered turn. Returns the assistant text with the
     * marker removed, or null when no marker is present.
     *
     * Used by [com.hermesandroid.relay.network.upstream.ChatHandler.loadMessageHistory]
     * so a synced turn coming back in server history renders with the quiet
     * "Realtime Agent" badge instead of raw bracket noise — and so its
     * superseded local clientOnly bubble can be dropped instead of showing
     * the exchange twice. The marker must be the FINAL block of the content
     * (a single bracket line, no embedded newline/bracket) so ordinary
     * assistant prose that merely mentions the phrase is never stripped.
     */
    fun stripProvenanceMarker(content: String): String? {
        val trimmed = content.trimEnd()
        if (!trimmed.endsWith("]")) return null
        val idx = trimmed.lastIndexOf("\n\n$PROVENANCE_PREFIX")
        if (idx < 0) return null
        val inner = trimmed.substring(
            idx + 2 + PROVENANCE_PREFIX.length,
            trimmed.length - 1,
        )
        if ('\n' in inner || ']' in inner) return null
        return trimmed.substring(0, idx).trimEnd()
    }

    private const val PROVENANCE_PREFIX = "[Realtime Agent provider-native voice turn: "
    private const val MAX_CONTENT_CHARS = 4_000
}

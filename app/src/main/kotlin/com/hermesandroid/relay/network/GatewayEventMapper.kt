package com.hermesandroid.relay.network

import com.hermesandroid.relay.network.models.UsageInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Maps tui_gateway events for ONE chat turn onto [GatewayTurnCallbacks].
 *
 * Pure JVM (no Android deps) so the whole mapping table is unit-testable.
 * The caller (GatewayChatClient) filters events by live session id and
 * invokes [onEvent] in arrival order; this class owns per-turn state
 * (backfill guards, synthetic tool ids, turn-end detection).
 *
 * Forward-compat contract: unknown event types MUST be ignored — upstream
 * adds event types freely and old clients are expected to skip them. That is
 * why dispatch is a manual `when (type)` over [JsonObject] rather than a
 * sealed polymorphic hierarchy (which throws on unknown discriminators).
 */
class GatewayEventMapper(private val callbacks: GatewayTurnCallbacks) {

    /** True once `message.complete` or `error` has been seen — the turn is over. */
    var turnEnded: Boolean = false
        private set

    private var sawMessageStart = false
    private var sawTextDelta = false
    private var sawThinkingDelta = false
    private var syntheticToolCounter = 0

    /**
     * `tool.complete` events match their `tool.start` by `tool_id`; when a
     * server omits the id we synthesize one per start and match completes by
     * tool name, FIFO.
     */
    private val openSyntheticIdsByName = mutableMapOf<String, ArrayDeque<String>>()

    fun onEvent(type: String, payload: JsonObject?) {
        if (turnEnded) return
        when (type) {
            "reasoning.delta", "thinking.delta" -> {
                val text = payload.string("text")
                if (!text.isNullOrEmpty()) {
                    sawThinkingDelta = true
                    callbacks.onThinkingDelta(text)
                }
            }

            // Post-hoc reasoning (providers that don't stream it) — only
            // useful when nothing streamed live.
            "reasoning.available" -> {
                val text = payload.string("text")
                if (!text.isNullOrEmpty() && !sawThinkingDelta) {
                    sawThinkingDelta = true
                    callbacks.onThinkingDelta(text)
                }
            }

            "message.delta" -> {
                val text = payload.string("text")
                if (!text.isNullOrEmpty()) {
                    sawTextDelta = true
                    callbacks.onTextDelta(text)
                }
            }

            "message.start" -> {
                // Gateway has no server-side message id (placeholder UUID
                // stays). A second start inside one turn means a new
                // assistant message began — close out the previous one.
                if (sawMessageStart) callbacks.onTurnComplete()
                sawMessageStart = true
            }

            "tool.start" -> {
                val name = payload.string("name") ?: "unknown"
                val toolId = payload.string("tool_id") ?: syntheticToolId(name)
                callbacks.onToolCallStart(toolId, name)
            }

            "tool.complete" -> {
                val name = payload.string("name") ?: "unknown"
                val toolId = payload.string("tool_id")
                    ?: openSyntheticIdsByName[name]?.removeFirstOrNull()
                    ?: return
                val error = payload.string("error")
                if (!error.isNullOrEmpty()) {
                    callbacks.onToolCallFailed(toolId, error)
                } else {
                    callbacks.onToolCallDone(toolId, payload.string("summary"))
                }
            }

            "message.complete" -> {
                // Non-streaming servers (or error turns) deliver everything
                // here; backfill whatever never streamed.
                val text = payload.string("text")
                if (!sawTextDelta && !text.isNullOrEmpty()) {
                    callbacks.onTextDelta(text)
                }
                val reasoning = payload.string("reasoning")
                if (!sawThinkingDelta && !reasoning.isNullOrEmpty()) {
                    callbacks.onThinkingDelta(reasoning)
                }
                callbacks.onUsage(parseGatewayUsage(payload?.get("usage") as? JsonObject))
                turnEnded = true
                callbacks.onComplete()
            }

            "error" -> {
                turnEnded = true
                callbacks.onError(payload.string("message") ?: "Gateway error")
            }

            "clarify.request" -> {
                val question = payload.string("question") ?: "The agent needs clarification"
                val choices = (payload?.get("choices") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(" / ")
                callbacks.onInteractionRequest(
                    "clarification",
                    if (choices != null) "$question ($choices)" else question,
                )
            }

            "approval.request" -> {
                val command = payload.string("command")
                val description = payload.string("description")
                callbacks.onInteractionRequest(
                    "approval",
                    listOfNotNull(command, description).joinToString(" — ")
                        .ifBlank { "a command approval" },
                )
            }

            "sudo.request" -> callbacks.onInteractionRequest("sudo access", "elevated permissions")

            "secret.request" -> {
                val detail = payload.string("prompt")
                    ?: payload.string("env_var")
                    ?: "a secret value"
                callbacks.onInteractionRequest("a secret", detail)
            }

            // Known-but-unrendered (MVP) and unknown types alike: ignore.
            else -> Unit
        }
    }

    private fun syntheticToolId(name: String): String {
        val id = "gateway-tool-$name-${syntheticToolCounter++}"
        openSyntheticIdsByName.getOrPut(name) { ArrayDeque() }.addLast(id)
        return id
    }

    companion object {
        /**
         * `message.complete.usage` uses tui_gateway's own key names
         * (`input`/`output`/`total`, with `prompt`/`completion` as the raw
         * counterparts — see upstream `_get_usage()`), NOT the
         * `input_tokens`/`prompt_tokens` schemes [UsageInfo] decodes from the
         * SSE paths. Translate explicitly. Values are session-cumulative.
         */
        fun parseGatewayUsage(usage: JsonObject?): UsageInfo? {
            if (usage == null) return null
            val input = usage.int("input") ?: usage.int("prompt")
            val output = usage.int("output") ?: usage.int("completion")
            val total = usage.int("total")
            if (input == null && output == null && total == null) return null
            return UsageInfo(inputTokens = input, outputTokens = output, totalTokens = total)
        }
    }
}

private fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (get(key) as? JsonPrimitive)?.intOrNull

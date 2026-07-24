package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.network.upstream.models.UsageInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull

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
class GatewayEventMapper(
    private val callbacks: GatewayTurnCallbacks,
    private val dedupeAdjacentMessageStarts: Boolean = false,
) {

    /** True once `message.complete` or `error` has been seen — the turn is over. */
    var turnEnded: Boolean = false
        private set

    private var sawMessageStart = false
    private var previousEventType: String? = null
    private var sawTextDelta = false
    private var sawThinkingDelta = false
    private var previewedText: String? = null
    private var syntheticToolCounter = 0
    private var providerWaitStatusActive = false
    private var compactionStatusActive = false

    /**
     * `tool.complete` events match their `tool.start` by `tool_id`; when a
     * server omits the id we synthesize one per start and match completes by
     * tool name, FIFO.
     */
    private val openSyntheticIdsByName = mutableMapOf<String, ArrayDeque<String>>()

    /**
     * `tool.generating` pre-registrations awaiting their `tool.start`, per
     * name FIFO — the start adopts the pre-minted id (unless the server
     * supplied a real `tool_id`) so the "preparing" placeholder and the
     * running card stay one ToolCall.
     */
    private val generatingIdsByName = mutableMapOf<String, ArrayDeque<String>>()

    fun onEvent(type: String, payload: JsonObject?) {
        if (turnEnded) return
        when (type) {
            "reasoning.delta" -> {
                val text = payload.string("text")
                if (!text.isNullOrEmpty()) {
                    clearActivityStatuses()
                    sawThinkingDelta = true
                    callbacks.onThinkingDelta(text)
                }
            }

            "thinking.delta" -> {
                val text = payload.string("text")
                if (!text.isNullOrEmpty()) {
                    if (isProviderWaitNotice(text)) {
                        providerWaitStatusActive = true
                        callbacks.onStatusUpdate(PROVIDER_WAIT_STATUS_KIND, text)
                    } else {
                        clearActivityStatuses()
                        sawThinkingDelta = true
                        callbacks.onThinkingDelta(text)
                    }
                }
            }

            // Post-hoc reasoning (providers that don't stream it) — only
            // useful when nothing streamed live.
            "reasoning.available" -> {
                val text = payload.string("text")
                if (!text.isNullOrEmpty()) {
                    clearActivityStatuses()
                    if (!sawThinkingDelta) {
                        sawThinkingDelta = true
                        callbacks.onThinkingDelta(text)
                    }
                }
            }

            "message.delta" -> {
                val text = payload.string("text")
                if (!text.isNullOrEmpty() && !isIntentionalSilenceMarker(text)) {
                    clearActivityStatuses()
                    sawTextDelta = true
                    previewedText = null
                    callbacks.onTextDelta(text)
                }
            }

            "message.interim" -> {
                val text = payload.string("text") ?: payload.string("message")
                    ?: payload.string("preview") ?: payload.string("rendered")
                val alreadyStreamed = payload.boolean("already_streamed") == true
                if (!text.isNullOrBlank() || alreadyStreamed) {
                    clearActivityStatuses()
                    if (!sawMessageStart) {
                        sawMessageStart = true
                        callbacks.onStart()
                    }
                    callbacks.onInterimMessage(text.orEmpty(), alreadyStreamed)
                    previewedText = text
                    sawTextDelta = alreadyStreamed
                }
            }

            "message.start" -> {
                // The upstream background-completion poller currently emits
                // message.start immediately before _run_prompt_submit(), which
                // emits the same start again. Treat an adjacent pair as one
                // boundary; a later start after any other event still closes
                // the previous assistant message as before.
                if (dedupeAdjacentMessageStarts && previousEventType == "message.start") return
                // Gateway has no server-side message id (placeholder UUID
                // stays). A second start inside one turn means a new
                // assistant message began — close out the previous one.
                if (sawMessageStart) callbacks.onTurnComplete()
                sawMessageStart = true
                previewedText = null
                callbacks.onStart()
            }

            "tool.generating" -> {
                clearActivityStatuses()
                // `{name?}` with NO tool_id — the model is still streaming
                // this tool's arguments.
                val name = payload.string("name")
                if (name != null) {
                    generatingIdsByName.getOrPut(name) { ArrayDeque() }
                        .addLast("gateway-tool-$name-${syntheticToolCounter++}")
                }
                callbacks.onToolGenerating(name)
            }

            "tool.start" -> {
                clearActivityStatuses()
                val name = payload.string("name") ?: "unknown"
                // A pending generating placeholder for this name is adopted
                // (consumed FIFO) whether or not the server sent a real id.
                val adopted = generatingIdsByName[name]?.removeFirstOrNull()
                val serverId = payload.string("tool_id")
                val toolId = when {
                    serverId != null -> serverId
                    adopted != null -> adopted.also {
                        openSyntheticIdsByName.getOrPut(name) { ArrayDeque() }.addLast(it)
                    }
                    else -> syntheticToolId(name)
                }
                callbacks.onToolCallStart(toolId, name)
            }

            "tool.complete" -> {
                clearActivityStatuses()
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
                val responsePreviewed = payload.boolean("response_previewed") == true
                val duplicatesPreview = responsePreviewed &&
                    !text.isNullOrEmpty() &&
                    previewedText?.let { preview -> text.startsWith(preview) || preview.startsWith(text) } == true
                if (!text.isNullOrEmpty() &&
                    !duplicatesPreview &&
                    !isIntentionalSilenceMarker(text) &&
                    (!sawTextDelta || previewedText != null)
                ) {
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

            "subagent.start", "subagent.thinking", "subagent.tool",
            "subagent.progress", "subagent.complete",
            -> {
                clearActivityStatuses()
                val phase = when (type) {
                    "subagent.start" -> GatewaySubagentEvent.Phase.START
                    "subagent.thinking" -> GatewaySubagentEvent.Phase.THINKING
                    "subagent.tool" -> GatewaySubagentEvent.Phase.TOOL
                    "subagent.progress" -> GatewaySubagentEvent.Phase.PROGRESS
                    else -> GatewaySubagentEvent.Phase.COMPLETE
                }
                callbacks.onSubagentEvent(
                    GatewaySubagentEvent(
                        phase = phase,
                        taskIndex = payload.int("task_index") ?: 0,
                        taskCount = payload.int("task_count") ?: 1,
                        goal = payload.string("goal") ?: "",
                        status = payload.string("status"),
                        summary = payload.string("summary"),
                        toolName = payload.string("tool_name"),
                        // subagent.tool sets tool_preview AND mirrors it into
                        // text; thinking/progress carry text only.
                        preview = payload.string("tool_preview") ?: payload.string("text"),
                        durationSeconds = payload.double("duration_seconds"),
                    ),
                )
            }

            "clarify.request" -> callbacks.onInteractionRequest(
                GatewayAsk(
                    kind = GatewayAsk.Kind.CLARIFY,
                    requestId = payload.string("request_id"),
                    text = payload.string("question") ?: "The agent needs clarification",
                    choices = (payload?.get("choices") as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?.takeIf { it.isNotEmpty() },
                    timeoutSeconds = CLARIFY_TIMEOUT_SECONDS,
                ),
            )

            "approval.request" -> callbacks.onInteractionRequest(
                GatewayAsk(
                    kind = GatewayAsk.Kind.APPROVAL,
                    // Upstream approvals correlate per-SESSION, never
                    // per-request — a stray request_id must not be adopted.
                    requestId = null,
                    text = listOfNotNull(payload.string("command"), payload.string("description"))
                        .joinToString(" — ")
                        .ifBlank { "a command approval" },
                    choices = payload.approvalChoices(),
                    smartDenied = payload.boolean("smart_denied") == true,
                    // Current Hermes omits timeout metadata. Keep the legacy
                    // no-countdown behavior unless a future contract exposes
                    // the effective per-request timeout explicitly.
                    timeoutSeconds = payload.int("timeout_seconds") ?: 0,
                ),
            )

            "tool.output_risk" -> {
                val toolId = payload.string("tool_id")
                val risk = payload.string("risk")?.lowercase() ?: return
                if (!toolId.isNullOrBlank() && risk in OUTPUT_RISK_LEVELS && risk != "low") {
                    callbacks.onToolOutputRisk(
                        GatewayToolOutputRisk(
                            toolCallId = toolId,
                            toolName = payload.string("name").orEmpty(),
                            risk = risk,
                            findings = (payload?.get("findings") as? JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                                ?.filter { it.isNotEmpty() }
                                ?.distinct()
                                .orEmpty(),
                            redacted = payload.boolean("redacted") == true,
                        ),
                    )
                }
            }

            // MoA activity proves auto-compaction has resumed even though
            // Android does not currently render these upstream events.
            "moa.reference", "moa.aggregating", "tool.progress" -> clearActivityStatuses()

            "sudo.request" -> callbacks.onInteractionRequest(
                GatewayAsk(
                    kind = GatewayAsk.Kind.SUDO,
                    requestId = payload.string("request_id"),
                    // Payload carries request_id ONLY — no command to show.
                    text = "Elevated permissions requested",
                    timeoutSeconds = SUDO_TIMEOUT_SECONDS,
                ),
            )

            "secret.request" -> callbacks.onInteractionRequest(
                GatewayAsk(
                    kind = GatewayAsk.Kind.SECRET,
                    requestId = payload.string("request_id"),
                    text = payload.string("prompt") ?: "The agent needs a secret value",
                    envVar = payload.string("env_var"),
                    timeoutSeconds = SECRET_TIMEOUT_SECONDS,
                ),
            )

            "sudo.expire" -> callbacks.onInteractionExpired(
                GatewayAskExpiry(
                    kind = GatewayAsk.Kind.SUDO,
                    requestId = payload.string("request_id"),
                ),
            )

            "secret.expire" -> callbacks.onInteractionExpired(
                GatewayAskExpiry(
                    kind = GatewayAsk.Kind.SECRET,
                    requestId = payload.string("request_id"),
                ),
            )

            "clarify.expire" -> callbacks.onInteractionExpired(
                GatewayAskExpiry(
                    kind = GatewayAsk.Kind.CLARIFY,
                    requestId = payload.string("request_id"),
                ),
            )

            // Forward-compatible consumer for the proposed upstream approval
            // expiry event. Approvals correlate by session, never request id.
            "approval.expire" -> callbacks.onInteractionExpired(
                GatewayAskExpiry(
                    kind = GatewayAsk.Kind.APPROVAL,
                    requestId = null,
                ),
            )

            "status.update" -> {
                val text = payload.string("text")
                if (!text.isNullOrBlank()) {
                    providerWaitStatusActive = false
                    val kind = payload.string("kind")
                    compactionStatusActive = kind == COMPACTION_STATUS_KIND
                    callbacks.onStatusUpdate(kind, text)
                }
            }

            // Known-but-unrendered (notification.show, …) and unknown types
            // alike: ignore.
            else -> Unit
        }
        previousEventType = type
    }

    private fun syntheticToolId(name: String): String {
        val id = "gateway-tool-$name-${syntheticToolCounter++}"
        openSyntheticIdsByName.getOrPut(name) { ArrayDeque() }.addLast(id)
        return id
    }

    private fun clearProviderWaitStatus() {
        if (!providerWaitStatusActive) return
        providerWaitStatusActive = false
        callbacks.onStatusClear(PROVIDER_WAIT_STATUS_KIND)
    }

    private fun clearActivityStatuses() {
        clearProviderWaitStatus()
        if (!compactionStatusActive) return
        compactionStatusActive = false
        callbacks.onStatusClear(COMPACTION_STATUS_KIND)
    }

    private fun JsonObject?.approvalChoices(): List<String>? =
        (this?.get("choices") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
            ?.filter { it in APPROVAL_CHOICES }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }

    private fun JsonObject?.boolean(key: String): Boolean? =
        (this?.get(key) as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val PROVIDER_WAIT_STATUS_KIND = "provider_wait"
        const val COMPACTION_STATUS_KIND = "compacting"
        private val APPROVAL_CHOICES = setOf("once", "session", "always", "deny")
        private val OUTPUT_RISK_LEVELS = setOf("low", "medium", "high", "critical")

        /**
         * Hermes 2026-07-15 emits these operational wait lines through the
         * legacy `thinking.delta` display callback. Match the deliberately
         * narrow canonical prefixes so genuine legacy model thinking still
         * remains durable reasoning.
         */
        fun isProviderWaitNotice(text: String): Boolean {
            val normalized = text.trimStart()
            return normalized.startsWith("⏳ waiting on ") ||
                normalized.startsWith("⚠ no response from provider in ") ||
                normalized.startsWith("⚠ no output from provider for ") ||
                normalized.startsWith("↻ model returned reasoning with no final answer — asking it to continue")
        }

        /**
         * `message.complete.usage` uses tui_gateway's own key names
         * (`input`/`output`/`total`, with `prompt`/`completion` as the raw
         * counterparts — see upstream `_get_usage()`), NOT the
         * `input_tokens`/`prompt_tokens` schemes [UsageInfo] decodes from the
         * SSE paths. Translate explicitly. Values are session-cumulative.
         *
         * The context-window block (`context_used`/`context_max`/
         * `context_percent`) exists only when the server's context compressor
         * is active — absent fields stay null and the meter stays hidden.
         */
        fun parseGatewayUsage(usage: JsonObject?): UsageInfo? {
            if (usage == null) return null
            val input = usage.int("input") ?: usage.int("prompt")
            val output = usage.int("output") ?: usage.int("completion")
            val total = usage.int("total")
            val contextUsed = usage.int("context_used")
            val contextMax = usage.int("context_max")
            val contextPercent = usage.int("context_percent")
            if (input == null && output == null && total == null &&
                contextUsed == null && contextMax == null && contextPercent == null
            ) {
                return null
            }
            return UsageInfo(
                inputTokens = input,
                outputTokens = output,
                totalTokens = total,
                contextUsed = contextUsed,
                contextMax = contextMax,
                contextPercent = contextPercent,
            )
        }
    }
}

// Upstream `_block()` timeouts per ask kind (server.py) — the blocked thread
// resolves to "" when these elapse. Approval has none (session-scoped).
private const val CLARIFY_TIMEOUT_SECONDS = 300
private const val SUDO_TIMEOUT_SECONDS = 120
private const val SECRET_TIMEOUT_SECONDS = 300

private fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject?.int(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.intOrNull

private fun JsonObject?.double(key: String): Double? =
    (this?.get(key) as? JsonPrimitive)?.doubleOrNull

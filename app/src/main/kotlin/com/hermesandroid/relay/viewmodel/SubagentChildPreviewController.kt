package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.GatewayChildWatch
import com.hermesandroid.relay.network.upstream.GatewayTurnCallbacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

internal data class SubagentChildPreview(
    val activityKey: String,
    val parentSessionId: String,
    val parentScopeKey: String?,
    /** null while opening, false for a truthful parent-event fallback. */
    val childWatchAvailable: Boolean? = null,
    val messages: List<ChatMessage> = emptyList(),
    val running: Boolean = false,
    val status: String? = null,
    val historyTruncated: Boolean = false,
    val partialAfterGap: Boolean = false,
    val error: String? = null,
)

internal class SubagentChildPreviewController(
    private val scope: CoroutineScope,
    private val openWatch: suspend (
        GatewayChatClient,
        String,
        String?,
        GatewayTurnCallbacks,
    ) -> Result<GatewayChildWatch> = { client, sessionId, profile, callbacks ->
        client.openChildWatch(sessionId, profile, callbacks)
    },
    private val closeWatch: suspend (GatewayChatClient, GatewayChildWatch) -> Result<Unit> =
        { client, watch -> client.closeChildWatch(watch) },
) {
    private class WatchContext(
        val activity: SubagentActivity,
        val client: GatewayChatClient,
        val parentSessionId: String,
        val parentScopeKey: String?,
        val generation: Long,
        val stillOwnsParent: () -> Boolean,
    ) {
        val handler = ChatHandler()
        var messageOrdinal = 0
        var messageId = "child-watch-$generation-0"
        var contentTruncated = false
        var initialized = false
        var pendingOverflow = false
        val pendingCallbacks = mutableListOf<() -> Unit>()
    }

    private val _state = MutableStateFlow<SubagentChildPreview?>(null)
    val state: StateFlow<SubagentChildPreview?> = _state.asStateFlow()
    private val generation = AtomicLong(0)
    private var watch: GatewayChildWatch? = null
    private var watchClient: GatewayChatClient? = null

    fun open(
        activity: SubagentActivity,
        client: GatewayChatClient?,
        parentSessionId: String,
        parentScopeKey: String?,
        gatewayRouteActive: Boolean,
        stillOwnsParent: () -> Boolean,
    ) {
        if (isAlreadyOpen(activity.stableKey, parentSessionId, parentScopeKey)) return
        close(clearState = false)
        if (activity.childSessionId.isNullOrBlank() || client == null || !gatewayRouteActive) {
            _state.value = fallbackState(activity, parentSessionId, parentScopeKey)
            return
        }

        val context = WatchContext(
            activity = activity,
            client = client,
            parentSessionId = parentSessionId,
            parentScopeKey = parentScopeKey,
            generation = generation.incrementAndGet(),
            stillOwnsParent = stillOwnsParent,
        )
        _state.value = baseState(context)
        // Once upstream creates a lazy watcher, only the resume acknowledgement
        // reveals the live id needed to close it. Let a dismissed open finish;
        // generation invalidation makes [acceptOpenedWatch] close the late handle.
        scope.launch { openWatch(context) }
    }

    fun close() = close(clearState = true)

    private suspend fun openWatch(context: WatchContext) {
        openWatch(
            context.client,
            context.activity.childSessionId.orEmpty(),
            context.activity.profile,
            callbacks(context),
        ).fold(
            onSuccess = { opened -> acceptOpenedWatch(context, opened) },
            onFailure = { error -> publishOpenFailure(context, error.message) },
        )
    }

    private suspend fun acceptOpenedWatch(context: WatchContext, opened: GatewayChildWatch) {
        if (!owns(context)) {
            closeWatch(context.client, opened)
            return
        }
        watch = opened
        watchClient = context.client
        context.handler.setSessionId(opened.storedSessionId)
        context.handler.loadMessageHistory(opened.messages)
        context.contentTruncated = context.handler.boundReadOnlyPreview()
        val pending = synchronized(context.pendingCallbacks) {
            context.initialized = true
            context.pendingCallbacks.toList().also { context.pendingCallbacks.clear() }
        }
        publish(
            context = context,
            running = opened.running,
            status = opened.status,
            historyTruncated = opened.historyTruncated || context.contentTruncated,
            partial = context.activity.partialAfterGap || context.pendingOverflow,
        )
        pending.forEach { callback -> if (owns(context)) callback() }
    }

    private fun callbacks(context: WatchContext) = GatewayTurnCallbacks(
        onSessionId = { },
        onStart = { runOrQueue(context) { startMessage(context) } },
        onTextDelta = { delta ->
            runOrQueue(context) { mutate(context) { onTextDelta(context.messageId, delta) } }
        },
        onThinkingDelta = { delta ->
            runOrQueue(context) { mutate(context) { onThinkingDelta(context.messageId, delta) } }
        },
        onToolCallStart = { id, name, preview ->
            runOrQueue(context) {
                mutate(context) { onToolCallStart(context.messageId, id, name, preview) }
            }
        },
        onToolCallDone = { id, preview ->
            runOrQueue(context) {
                mutate(context, ensureMessage = false) {
                    onToolCallComplete(context.messageId, id, preview)
                }
            }
        },
        onToolCallFailed = { id, error ->
            runOrQueue(context) {
                mutate(context, ensureMessage = false) {
                    onToolCallFailed(context.messageId, id, error)
                }
            }
        },
        onTurnComplete = {
            runOrQueue(context) {
                if (owns(context)) context.handler.onTurnComplete(context.messageId)
            }
        },
        onReconcileRequired = { runOrQueue(context) { publish(context, partial = true) } },
        onComplete = { runOrQueue(context) { complete(context) } },
        onUsage = { },
        onError = { message ->
            runOrQueue(context) {
                publish(context, running = false, partial = true, error = message)
            }
        },
        onToolGenerating = { },
        onSubagentEvent = { event ->
            runOrQueue(context) { mutate(context) { onSubagentEvent(context.messageId, event) } }
        },
        onMoaReference = { },
        onInteractionRequest = { },
        onInteractionExpired = { },
        onResumeFailure = { message ->
            runOrQueue(context) {
                publish(context, running = false, partial = true, error = message)
            }
        },
    )

    private fun runOrQueue(context: WatchContext, callback: () -> Unit) {
        if (!owns(context)) return
        val runNow = synchronized(context.pendingCallbacks) {
            if (context.initialized) {
                true
            } else {
                if (context.pendingCallbacks.size >= 256) {
                    context.pendingCallbacks.removeAt(0)
                    context.pendingOverflow = true
                }
                context.pendingCallbacks += callback
                false
            }
        }
        if (runNow) callback()
    }

    private fun startMessage(context: WatchContext) {
        if (!owns(context)) return
        context.messageId = "child-watch-${context.generation}-${context.messageOrdinal++}"
        ensureLiveMessage(context)
        publish(context)
    }

    private inline fun mutate(
        context: WatchContext,
        ensureMessage: Boolean = true,
        mutation: ChatHandler.() -> Unit,
    ) {
        if (!owns(context)) return
        if (ensureMessage) ensureLiveMessage(context)
        context.handler.mutation()
        context.contentTruncated = context.handler.boundReadOnlyPreview() || context.contentTruncated
        publish(context)
    }

    private fun complete(context: WatchContext) {
        if (!owns(context)) return
        context.handler.onStreamComplete(context.messageId)
        // The child mirror's message.complete omits failed/interrupted status.
        // Keep this neutral; the parent activity lane is authoritative.
        publish(context, running = false)
    }

    private fun ensureLiveMessage(context: WatchContext) {
        if (context.handler.messages.value.any { it.id == context.messageId }) return
        context.handler.addPlaceholderMessage(
            ChatMessage(
                id = context.messageId,
                role = MessageRole.ASSISTANT,
                content = "",
                timestamp = System.currentTimeMillis(),
                isStreaming = true,
            ),
        )
    }

    private fun publish(
        context: WatchContext,
        running: Boolean = true,
        status: String? = _state.value?.status,
        historyTruncated: Boolean =
            _state.value?.historyTruncated == true || context.contentTruncated,
        partial: Boolean = _state.value?.partialAfterGap == true,
        error: String? = null,
    ) {
        if (!owns(context)) return
        _state.value = baseState(context).copy(
            childWatchAvailable = true,
            messages = context.handler.messages.value.takeLast(200),
            running = running,
            status = status,
            historyTruncated = historyTruncated,
            partialAfterGap = partial,
            error = error,
        )
    }

    private fun publishOpenFailure(context: WatchContext, message: String?) {
        if (!owns(context)) return
        _state.value = fallbackState(
            context.activity,
            context.parentSessionId,
            context.parentScopeKey,
        ).copy(error = message)
    }

    private fun owns(context: WatchContext): Boolean =
        generation.get() == context.generation && context.stillOwnsParent()

    private fun isAlreadyOpen(key: String, sessionId: String, scopeKey: String?): Boolean =
        _state.value?.let {
            it.activityKey == key &&
                it.parentSessionId == sessionId &&
                it.parentScopeKey == scopeKey &&
                it.error.isNullOrBlank() &&
                it.childWatchAvailable != false
        } == true

    private fun baseState(context: WatchContext) = SubagentChildPreview(
        activityKey = context.activity.stableKey,
        parentSessionId = context.parentSessionId,
        parentScopeKey = context.parentScopeKey,
    )

    private fun fallbackState(
        activity: SubagentActivity,
        parentSessionId: String,
        parentScopeKey: String?,
    ) = SubagentChildPreview(
        activityKey = activity.stableKey,
        parentSessionId = parentSessionId,
        parentScopeKey = parentScopeKey,
        childWatchAvailable = false,
        partialAfterGap = activity.partialAfterGap,
    )

    private fun close(clearState: Boolean) {
        generation.incrementAndGet()
        val closingWatch = watch
        val closingClient = watchClient
        watch = null
        watchClient = null
        if (closingWatch != null && closingClient != null) {
            scope.launch { closeWatch(closingClient, closingWatch) }
        }
        if (clearState) _state.value = null
    }
}

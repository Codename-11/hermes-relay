package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.network.upstream.GatewayProcess
import com.hermesandroid.relay.network.upstream.GatewayProcessCapability
import com.hermesandroid.relay.network.upstream.GatewayProcessEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Small adapter seam around [com.hermesandroid.relay.network.upstream.GatewayChatClient].
 * Keeping the controller on this interface makes its session/race behavior
 * testable without opening a WebSocket.
 */
internal interface GatewayProcessSource {
    val capability: StateFlow<GatewayProcessCapability>

    suspend fun listProcesses(): Result<List<GatewayProcess>>

    suspend fun killProcess(processId: String): Result<Unit>

    fun setEventListener(listener: ((GatewayProcessEvent) -> Unit)?)

    /** False when background polling would reopen a deliberately closed socket. */
    fun isPollingAllowed(): Boolean
}

/**
 * Owns the active chat's upstream background-process snapshot.
 *
 * The gateway RPCs are scoped by a private, live session id. The UI only knows
 * the durable chat id, so a session becomes queryable only after [sessionReady]
 * confirms that the source has resumed/created its matching live session.
 * Every async result is fenced by both binding/session generation and request
 * sequence so a slow response from a previous chat can never repaint this one.
 */
internal class GatewayProcessController(
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 5_000L,
    private val outputTailLimit: Int = 4_000,
) {
    private val _processes = MutableStateFlow<List<GatewayProcess>>(emptyList())
    val processes: StateFlow<List<GatewayProcess>> = _processes.asStateFlow()

    private val _capability = MutableStateFlow(GatewayProcessCapability.Unknown)
    val capability: StateFlow<GatewayProcessCapability> = _capability.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _stoppingProcessIds = MutableStateFlow<Set<String>>(emptySet())
    val stoppingProcessIds: StateFlow<Set<String>> = _stoppingProcessIds.asStateFlow()

    private var source: GatewayProcessSource? = null
    private var selectedSessionId: String? = null
    private var selectedScopeKey: String? = null
    private var readySessionId: String? = null
    private var generation = 0L
    private var refreshSequence = 0L
    private var allProcesses: List<GatewayProcess> = emptyList()

    /** A dismissal applies only to this concrete process identity. */
    private val dismissedIdentities = mutableMapOf<String, ProcessIdentity>()

    private var capabilityJob: Job? = null
    private var refreshJob: Job? = null
    private var pollJob: Job? = null

    /** Replace the gateway client and clear all session-owned state. */
    fun bind(newSource: GatewayProcessSource?, sessionId: String?, scopeKey: String? = null) {
        if (
            source === newSource &&
            selectedSessionId == sessionId &&
            selectedScopeKey == scopeKey
        ) return

        source?.setEventListener(null)
        capabilityJob?.cancel()
        source = newSource
        resetForSession(sessionId, scopeKey)
        _capability.value = newSource?.capability?.value ?: GatewayProcessCapability.Unknown

        if (newSource == null) return
        newSource.setEventListener { event ->
            // Gateway callbacks arrive on the socket/callback dispatcher. Keep
            // snapshot transitions serialized with refresh/kill mutations.
            scope.launch {
                if (source === newSource) handleEvent(newSource, event)
            }
        }
        capabilityJob = scope.launch {
            newSource.capability.collect { value ->
                if (source !== newSource) return@collect
                _capability.value = value
                if (value == GatewayProcessCapability.Unsupported) {
                    clearSnapshot(cancelDismissals = true)
                    _loading.value = false
                }
            }
        }
    }

    /** Select a durable chat id. The live gateway session may follow later. */
    fun selectSession(sessionId: String?, scopeKey: String? = null) {
        if (selectedSessionId == sessionId && selectedScopeKey == scopeKey) return
        resetForSession(sessionId, scopeKey)
    }

    /**
     * Admit process RPCs for [sessionId] after the gateway has created/resumed
     * that chat's live session. Stale ready callbacks are ignored.
     */
    fun sessionReady(sessionId: String) {
        if (sessionId != selectedSessionId || source == null) return
        val firstReady = readySessionId != sessionId
        readySessionId = sessionId
        // A same-session ready callback can mean the socket was resumed after
        // being offline. Re-list even when this chat was already admitted: a
        // process may have started/finished while no event stream was present.
        refresh(showLoading = firstReady && allProcesses.isEmpty())
    }

    fun refresh(showLoading: Boolean = allProcesses.isEmpty()) {
        val currentSource = source ?: return
        val sessionId = selectedSessionId ?: return
        if (readySessionId != sessionId) return
        if (_capability.value == GatewayProcessCapability.Unsupported) return

        val expectedGeneration = generation
        val request = ++refreshSequence
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (showLoading && allProcesses.isEmpty()) _loading.value = true
            val result = currentSource.listProcesses()
            if (!owns(currentSource, sessionId, expectedGeneration) || request != refreshSequence) {
                return@launch
            }
            result.onSuccess(::applySnapshot)
            _loading.value = false
        }
    }

    /** Stop one running process; the authoritative follow-up snapshot wins. */
    fun stop(processId: String, onFailure: (String) -> Unit = {}) {
        val currentSource = source ?: return
        val sessionId = selectedSessionId ?: return
        if (readySessionId != sessionId) return
        if (processId in _stoppingProcessIds.value) return
        if (allProcesses.none { it.id == processId && it.isRunning }) return

        val expectedGeneration = generation
        _stoppingProcessIds.value = _stoppingProcessIds.value + processId
        scope.launch {
            val result = currentSource.killProcess(processId)
            if (!owns(currentSource, sessionId, expectedGeneration)) return@launch
            _stoppingProcessIds.value = _stoppingProcessIds.value - processId
            result.fold(
                onSuccess = { refresh(showLoading = false) },
                onFailure = { error ->
                    onFailure(error.message ?: "Couldn't stop background process")
                },
            )
        }
    }

    /** Finished rows are dismissed locally; upstream history remains untouched. */
    fun dismiss(processId: String) {
        val process = allProcesses.firstOrNull { it.id == processId && !it.isRunning } ?: return
        dismissedIdentities[processId] = process.identity()
        publishVisibleSnapshot()
    }

    fun close() {
        source?.setEventListener(null)
        source = null
        capabilityJob?.cancel()
        capabilityJob = null
        resetForSession(null, null)
        _capability.value = GatewayProcessCapability.Unknown
    }

    private fun resetForSession(sessionId: String?, scopeKey: String?) {
        generation += 1
        refreshSequence += 1
        selectedSessionId = sessionId
        selectedScopeKey = scopeKey
        readySessionId = null
        refreshJob?.cancel()
        refreshJob = null
        pollJob?.cancel()
        pollJob = null
        _loading.value = false
        _stoppingProcessIds.value = emptySet()
        clearSnapshot(cancelDismissals = true)
    }

    private fun clearSnapshot(cancelDismissals: Boolean) {
        allProcesses = emptyList()
        _processes.value = emptyList()
        pollJob?.cancel()
        pollJob = null
        if (cancelDismissals) {
            dismissedIdentities.clear()
        }
    }

    private fun applySnapshot(incoming: List<GatewayProcess>) {
        val incomingById = incoming.associateBy(GatewayProcess::id)

        // A server can eventually reuse a process id. Never let a local
        // dismissal hide the new command instance.
        dismissedIdentities.entries.removeAll { (id, dismissedIdentity) ->
            val next = incomingById[id]
            next == null || next.identity() != dismissedIdentity
        }
        allProcesses = incoming
        publishVisibleSnapshot()
        updatePoller()
    }

    private fun publishVisibleSnapshot() {
        _processes.value = allProcesses.filterNot { process ->
            dismissedIdentities[process.id] == process.identity()
        }
    }

    private fun updatePoller() {
        val shouldPoll = allProcesses.any(GatewayProcess::isRunning) &&
            readySessionId == selectedSessionId &&
            source != null &&
            source?.isPollingAllowed() == true &&
            _capability.value != GatewayProcessCapability.Unsupported
        if (!shouldPoll) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (pollJob?.isActive == true) return

        val expectedGeneration = generation
        pollJob = scope.launch {
            while (generation == expectedGeneration && allProcesses.any(GatewayProcess::isRunning)) {
                delay(pollIntervalMs)
                if (
                    generation != expectedGeneration ||
                    !allProcesses.any(GatewayProcess::isRunning) ||
                    source?.isPollingAllowed() != true
                ) {
                    break
                }
                // Avoid repeatedly cancelling a slow RPC. An invalidation can
                // own this interval; the next tick remains the safety net.
                if (refreshJob?.isActive != true) refresh(showLoading = false)
            }
            if (generation == expectedGeneration) pollJob = null
        }
    }

    private fun handleEvent(currentSource: GatewayProcessSource, event: GatewayProcessEvent) {
        val sessionId = selectedSessionId ?: return
        if (!owns(currentSource, sessionId, generation) || readySessionId != sessionId) return
        when (event) {
            is GatewayProcessEvent.Invalidated,
            is GatewayProcessEvent.TerminalClosed -> refresh(showLoading = false)

            is GatewayProcessEvent.Output -> {
                val index = allProcesses.indexOfFirst { it.id == event.processId }
                if (index < 0) {
                    // Output can beat tool.complete/process.list by a frame.
                    refresh(showLoading = false)
                    return
                }
                val process = allProcesses[index]
                val tail = (process.outputTail.orEmpty() + event.chunk).takeLast(outputTailLimit)
                allProcesses = allProcesses.toMutableList().also {
                    it[index] = process.copy(outputTail = tail)
                }
                publishVisibleSnapshot()
            }
        }
    }

    private fun owns(
        expectedSource: GatewayProcessSource,
        expectedSessionId: String,
        expectedGeneration: Long,
    ): Boolean =
        source === expectedSource &&
            selectedSessionId == expectedSessionId &&
            readySessionId == expectedSessionId &&
            generation == expectedGeneration

    private data class ProcessIdentity(
        val id: String,
        val command: String,
        val pid: Long?,
        val startedAt: String?,
    )

    private fun GatewayProcess.identity() = ProcessIdentity(id, command, pid, startedAt)
}

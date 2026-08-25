package com.hermesandroid.relay.data

import java.util.Locale

/** Stable ownership boundary for live activity. Runtime ids are aliases, never owners. */
@ConsistentCopyVisibility
data class SessionActivityOwner private constructor(
    val connectionId: String,
    val profile: String,
    val storedSessionId: String,
) {
    companion object {
        fun of(connectionId: String, profile: String, storedSessionId: String) =
            SessionActivityOwner(
                connectionId = connectionId.trim(),
                profile = profile.trim().lowercase(Locale.ROOT),
                storedSessionId = storedSessionId.trim(),
            ).also {
                require(it.connectionId.isNotEmpty()) { "connectionId must not be blank" }
                require(it.profile.isNotEmpty()) { "profile must not be blank" }
                require(it.storedSessionId.isNotEmpty()) { "storedSessionId must not be blank" }
            }
    }
}

@ConsistentCopyVisibility
data class SessionActivityScope private constructor(
    val connectionId: String,
    val profile: String,
) {
    companion object {
        fun of(connectionId: String, profile: String) = SessionActivityScope(
            connectionId = connectionId.trim(),
            profile = profile.trim().lowercase(Locale.ROOT),
        ).also {
            require(it.connectionId.isNotEmpty()) { "connectionId must not be blank" }
            require(it.profile.isNotEmpty()) { "profile must not be blank" }
        }
    }
}

enum class SessionActivityPhase {
    Starting,
    Working,
    NeedsInput,
    BackgroundWork,
    Idle,
}

enum class SessionActivityFreshness {
    Confirmed,
    Revalidating,
    Unavailable,
}

enum class SessionActivityEvidenceSource {
    Directory,
    LocalSend,
    ActiveList,
    SessionEvent,
    PendingInput,
    Terminal,
    Checkpoint,
    Process,
}

data class SessionActivityEvidence(
    val source: SessionActivityEvidenceSource,
    val generation: Long,
    val observedAtMillis: Long,
)

data class SessionActivityRecord(
    val owner: SessionActivityOwner,
    /** Authoritative turn state before exact pending-input and background-process overlays. */
    val turnPhase: SessionActivityPhase,
    val freshness: SessionActivityFreshness,
    val evidence: SessionActivityEvidence,
    val runtimeId: String? = null,
    val pendingInputs: Map<String, Long?> = emptyMap(),
    val backgroundProcessIds: Set<String> = emptySet(),
) {
    fun phase(nowMillis: Long = Long.MIN_VALUE): SessionActivityPhase {
        val hasPendingInput = pendingInputs.any { (_, expiresAt) -> expiresAt == null || expiresAt > nowMillis }
        return when {
            hasPendingInput -> SessionActivityPhase.NeedsInput
            turnPhase != SessionActivityPhase.Idle -> turnPhase
            backgroundProcessIds.isNotEmpty() -> SessionActivityPhase.BackgroundWork
            else -> SessionActivityPhase.Idle
        }
    }

    /** Presentation projection that never labels uncertain or background activity as Working. */
    fun presentationState(nowMillis: Long = Long.MIN_VALUE): SessionActivityState? = when (freshness) {
        SessionActivityFreshness.Revalidating -> SessionActivityState.Checking
        SessionActivityFreshness.Unavailable -> SessionActivityState.Unavailable
        SessionActivityFreshness.Confirmed -> when (phase(nowMillis)) {
            SessionActivityPhase.Starting -> SessionActivityState.Starting
            SessionActivityPhase.Working -> SessionActivityState.Working
            SessionActivityPhase.NeedsInput -> SessionActivityState.NeedsInput
            SessionActivityPhase.BackgroundWork -> SessionActivityState.BackgroundWork
            SessionActivityPhase.Idle -> null
        }
    }
}

enum class SessionLiveStatus {
    Starting,
    Working,
    Waiting,
    Idle,
}

data class SessionLiveRuntime(
    /** Null when transport data cannot be resolved uniquely to a stored session owner. */
    val owner: SessionActivityOwner?,
    val runtimeId: String,
    val status: SessionLiveStatus,
)

sealed interface SessionActivityUpdate {
    val generation: Long
    val observedAtMillis: Long

    data class BeginGeneration(
        val scope: SessionActivityScope,
        override val generation: Long,
        override val observedAtMillis: Long,
    ) : SessionActivityUpdate

    data class ObserveOwner(
        val owner: SessionActivityOwner,
        override val generation: Long,
        override val observedAtMillis: Long,
    ) : SessionActivityUpdate

    data class LocalSend(
        val owner: SessionActivityOwner,
        override val generation: Long,
        override val observedAtMillis: Long,
    ) : SessionActivityUpdate

    data class LiveState(
        val owner: SessionActivityOwner,
        val runtimeId: String?,
        val status: SessionLiveStatus,
        val source: SessionActivityEvidenceSource = SessionActivityEvidenceSource.SessionEvent,
        override val generation: Long,
        override val observedAtMillis: Long,
    ) : SessionActivityUpdate

    data class RuntimeState(
        val scope: SessionActivityScope,
        val runtimeId: String,
        val status: SessionLiveStatus,
        override val generation: Long,
        override val observedAtMillis: Long,
    ) : SessionActivityUpdate

    data class ActiveList(
        val scope: SessionActivityScope,
        val runtimes: List<SessionLiveRuntime>,
        /** True only when every upstream row was safely attributable for this scope. */
        val isCompleteForScope: Boolean,
        override val generation: Long,
        override val observedAtMillis: Long,
    ) : SessionActivityUpdate

    data class StatusUnavailable(
        val scope: SessionActivityScope,
        override val generation: Long,
        override val observedAtMillis: Long,
    ) : SessionActivityUpdate

    data class Terminal(
        val owner: SessionActivityOwner,
        val runtimeId: String? = null,
        override val generation: Long,
        override val observedAtMillis: Long,
    ) : SessionActivityUpdate

    data class RestoreCheckpoint(
        val owner: SessionActivityOwner,
        val runtimeId: String?,
        val phase: SessionActivityPhase,
        override val generation: Long,
        override val observedAtMillis: Long,
    ) : SessionActivityUpdate

    data class PendingInputOpened(
        val owner: SessionActivityOwner,
        val requestId: String,
        val expiresAtMillis: Long? = null,
        val confirmed: Boolean = true,
        override val generation: Long,
        override val observedAtMillis: Long,
    ) : SessionActivityUpdate

    data class PendingInputClosed(
        val owner: SessionActivityOwner,
        val requestId: String,
        val confirmed: Boolean = true,
        override val generation: Long,
        override val observedAtMillis: Long,
    ) : SessionActivityUpdate

    data class ProcessState(
        val owner: SessionActivityOwner,
        val processId: String,
        val running: Boolean,
        override val generation: Long,
        override val observedAtMillis: Long,
    ) : SessionActivityUpdate

    data class Tick(
        val nowMillis: Long,
        override val generation: Long = Long.MAX_VALUE,
        override val observedAtMillis: Long = nowMillis,
    ) : SessionActivityUpdate
}

/**
 * Pure reducer for session activity. Every update is generation-gated per connection/profile.
 * An unsuccessful/unsupported refresh never manufactures an idle result.
 */
data class SessionActivityRegistry(
    val records: Map<SessionActivityOwner, SessionActivityRecord> = emptyMap(),
    private val runtimeAliases: Map<RuntimeAlias, SessionActivityOwner> = emptyMap(),
    private val generations: Map<SessionActivityScope, Long> = emptyMap(),
) {
    fun record(owner: SessionActivityOwner): SessionActivityRecord? = records[owner]

    fun ownerForRuntime(scope: SessionActivityScope, runtimeId: String): SessionActivityOwner? =
        runtimeAliases[RuntimeAlias(scope, runtimeId.trim(), generations[scope] ?: return null)]

    fun presentationStates(nowMillis: Long = Long.MIN_VALUE): Map<SessionActivityOwner, SessionActivityState> =
        records.mapNotNull { (owner, record) -> record.presentationState(nowMillis)?.let { owner to it } }.toMap()

    fun reduce(update: SessionActivityUpdate): SessionActivityRegistry {
        if (update is SessionActivityUpdate.Tick) return expirePendingInputs(update.nowMillis)
        val scope = update.scope()
        val currentGeneration = generations[scope]
        if (currentGeneration != null && update.generation < currentGeneration) return this

        var state = this
        if (currentGeneration == null || update.generation > currentGeneration) {
            state = state.beginGeneration(scope, update.generation)
        }

        return when (update) {
            is SessionActivityUpdate.BeginGeneration -> state
            is SessionActivityUpdate.ObserveOwner -> state.observeOwner(update)
            is SessionActivityUpdate.LocalSend -> state.putTurn(
                update.owner, null, SessionActivityPhase.Starting, SessionActivityFreshness.Confirmed,
                SessionActivityEvidenceSource.LocalSend, update.generation, update.observedAtMillis,
            )
            is SessionActivityUpdate.LiveState -> state.putLiveState(update)
            is SessionActivityUpdate.RuntimeState -> {
                val owner = state.ownerForRuntime(update.scope, update.runtimeId) ?: return state
                state.putTurn(
                    owner, update.runtimeId, update.status.phase(), SessionActivityFreshness.Confirmed,
                    SessionActivityEvidenceSource.SessionEvent, update.generation, update.observedAtMillis,
                )
            }
            is SessionActivityUpdate.ActiveList -> state.applyActiveList(update)
            is SessionActivityUpdate.StatusUnavailable -> state.markUnavailable(update.scope)
            is SessionActivityUpdate.Terminal -> state.settleTerminal(update)
            is SessionActivityUpdate.RestoreCheckpoint -> state.restoreCheckpoint(update)
            is SessionActivityUpdate.PendingInputOpened -> state.updatePendingInput(
                update.owner, update.requestId, update.expiresAtMillis, true,
                update.confirmed, update.generation, update.observedAtMillis,
            )
            is SessionActivityUpdate.PendingInputClosed -> state.updatePendingInput(
                update.owner, update.requestId, null, false,
                update.confirmed, update.generation, update.observedAtMillis,
            )
            is SessionActivityUpdate.ProcessState -> state.updateProcess(update)
            is SessionActivityUpdate.Tick -> state
        }
    }

    private fun beginGeneration(scope: SessionActivityScope, generation: Long): SessionActivityRegistry {
        val refreshedRecords = records.mapValues { (owner, record) ->
            if (owner.scope() == scope) {
                record.copy(freshness = SessionActivityFreshness.Revalidating)
            } else record
        }
        return copy(
            records = refreshedRecords,
            runtimeAliases = runtimeAliases.filterKeys { it.scope != scope },
            generations = generations + (scope to generation),
        )
    }

    private fun observeOwner(update: SessionActivityUpdate.ObserveOwner): SessionActivityRegistry {
        val existing = records[update.owner]
        if (existing?.freshness == SessionActivityFreshness.Confirmed) return this
        val observed = SessionActivityRecord(
            owner = update.owner,
            turnPhase = SessionActivityPhase.Idle,
            freshness = SessionActivityFreshness.Revalidating,
            evidence = SessionActivityEvidence(
                SessionActivityEvidenceSource.Directory,
                update.generation,
                update.observedAtMillis,
            ),
        )
        return copy(records = records + (update.owner to observed))
    }

    private fun putLiveState(update: SessionActivityUpdate.LiveState): SessionActivityRegistry = putTurn(
        owner = update.owner,
        runtimeId = update.runtimeId,
        phase = update.status.phase(),
        freshness = SessionActivityFreshness.Confirmed,
        source = update.source,
        generation = update.generation,
        observedAtMillis = update.observedAtMillis,
    )

    private fun putTurn(
        owner: SessionActivityOwner,
        runtimeId: String?,
        phase: SessionActivityPhase,
        freshness: SessionActivityFreshness,
        source: SessionActivityEvidenceSource,
        generation: Long,
        observedAtMillis: Long,
    ): SessionActivityRegistry {
        val previous = records[owner]
        val record = SessionActivityRecord(
            owner = owner,
            turnPhase = phase,
            freshness = freshness,
            evidence = SessionActivityEvidence(source, generation, observedAtMillis),
            runtimeId = runtimeId ?: previous?.runtimeId,
            pendingInputs = previous?.pendingInputs.orEmpty(),
            backgroundProcessIds = previous?.backgroundProcessIds.orEmpty(),
        )
        val alias = runtimeId?.trim()?.takeIf { it.isNotEmpty() }
        return copy(
            records = records + (owner to record),
            runtimeAliases = if (alias == null) runtimeAliases else {
                runtimeAliases + (RuntimeAlias(owner.scope(), alias, generation) to owner)
            },
        )
    }

    private fun applyActiveList(update: SessionActivityUpdate.ActiveList): SessionActivityRegistry {
        require(update.runtimes.all { it.owner == null || it.owner.scope() == update.scope }) {
            "Active-list rows must belong to the snapshot scope"
        }
        var state = copy(runtimeAliases = runtimeAliases.filterKeys { it.scope != update.scope })
        val resolvedRuntimes = update.runtimes.filter { it.owner != null }
        val observedOwners = resolvedRuntimes.mapTo(mutableSetOf()) { requireNotNull(it.owner) }
        resolvedRuntimes.forEach { runtime ->
            val resolvedOwner = requireNotNull(runtime.owner)
            state = state.putTurn(
                resolvedOwner, runtime.runtimeId, runtime.status.phase(), SessionActivityFreshness.Confirmed,
                SessionActivityEvidenceSource.ActiveList, update.generation, update.observedAtMillis,
            )
            if (runtime.status == SessionLiveStatus.Idle) {
                val idleRecord = requireNotNull(state.records[resolvedOwner]).copy(pendingInputs = emptyMap())
                state = state.copy(records = state.records + (resolvedOwner to idleRecord))
            }
        }
        val snapshotCanSettle = update.isCompleteForScope && resolvedRuntimes.size == update.runtimes.size
        if (!snapshotCanSettle) return state
        val settled = state.records.mapValues { (owner, record) ->
            if (
                owner.scope() == update.scope && owner !in observedOwners &&
                record.shouldSettleWhenAbsent()
            ) {
                record.copy(
                    turnPhase = SessionActivityPhase.Idle,
                    freshness = SessionActivityFreshness.Confirmed,
                    runtimeId = null,
                    pendingInputs = emptyMap(),
                    evidence = SessionActivityEvidence(
                        SessionActivityEvidenceSource.ActiveList,
                        update.generation,
                        update.observedAtMillis,
                    ),
                )
            } else record
        }
        return state.copy(records = settled)
    }

    private fun markUnavailable(scope: SessionActivityScope): SessionActivityRegistry = copy(
        records = records.mapValues { (owner, record) ->
            if (
                owner.scope() == scope && record.evidence.source in setOf(
                    SessionActivityEvidenceSource.ActiveList,
                    SessionActivityEvidenceSource.Directory,
                    SessionActivityEvidenceSource.Checkpoint,
                )
            ) {
                record.copy(freshness = SessionActivityFreshness.Unavailable)
            } else record
        },
    )

    private fun settleTerminal(update: SessionActivityUpdate.Terminal): SessionActivityRegistry {
        val settled = putTurn(
            update.owner,
            runtimeId = null,
            phase = SessionActivityPhase.Idle,
            freshness = SessionActivityFreshness.Confirmed,
            source = SessionActivityEvidenceSource.Terminal,
            generation = update.generation,
            observedAtMillis = update.observedAtMillis,
        )
        val record = requireNotNull(settled.records[update.owner]).copy(
            runtimeId = null,
            pendingInputs = emptyMap(),
        )
        return settled.copy(
            records = settled.records + (update.owner to record),
            runtimeAliases = settled.runtimeAliases.filterNot { (alias, owner) ->
                alias.scope == update.owner.scope() && owner == update.owner &&
                    (update.runtimeId == null || alias.runtimeId == update.runtimeId)
            },
        )
    }

    private fun restoreCheckpoint(update: SessionActivityUpdate.RestoreCheckpoint): SessionActivityRegistry {
        val existing = records[update.owner]
        if (existing?.freshness == SessionActivityFreshness.Confirmed) return this
        return putTurn(
            update.owner, update.runtimeId, update.phase, SessionActivityFreshness.Revalidating,
            SessionActivityEvidenceSource.Checkpoint, update.generation, update.observedAtMillis,
        )
    }

    private fun updatePendingInput(
        owner: SessionActivityOwner,
        requestId: String,
        expiresAtMillis: Long?,
        opened: Boolean,
        confirmed: Boolean,
        generation: Long,
        observedAtMillis: Long,
    ): SessionActivityRegistry {
        val previous = records[owner] ?: SessionActivityRecord(
            owner = owner,
            turnPhase = SessionActivityPhase.Idle,
            freshness = if (confirmed) {
                SessionActivityFreshness.Confirmed
            } else {
                SessionActivityFreshness.Revalidating
            },
            evidence = SessionActivityEvidence(
                if (confirmed) {
                    SessionActivityEvidenceSource.PendingInput
                } else {
                    SessionActivityEvidenceSource.Checkpoint
                },
                generation,
                observedAtMillis,
            ),
        )
        val pending = if (opened) {
            previous.pendingInputs + (requestId to expiresAtMillis)
        } else {
            previous.pendingInputs - requestId
        }
        return copy(records = records + (owner to previous.copy(
            pendingInputs = pending,
            freshness = if (confirmed) SessionActivityFreshness.Confirmed else previous.freshness,
            evidence = if (confirmed) {
                SessionActivityEvidence(
                    SessionActivityEvidenceSource.PendingInput,
                    generation,
                    observedAtMillis,
                )
            } else previous.evidence,
        )))
    }

    private fun updateProcess(update: SessionActivityUpdate.ProcessState): SessionActivityRegistry {
        val previous = records[update.owner] ?: SessionActivityRecord(
            owner = update.owner,
            turnPhase = SessionActivityPhase.Idle,
            freshness = SessionActivityFreshness.Confirmed,
            evidence = SessionActivityEvidence(
                SessionActivityEvidenceSource.Process,
                update.generation,
                update.observedAtMillis,
            ),
        )
        val processes = if (update.running) {
            previous.backgroundProcessIds + update.processId
        } else {
            previous.backgroundProcessIds - update.processId
        }
        return copy(records = records + (update.owner to previous.copy(
            backgroundProcessIds = processes,
            evidence = SessionActivityEvidence(
                SessionActivityEvidenceSource.Process,
                update.generation,
                update.observedAtMillis,
            ),
        )))
    }

    private fun expirePendingInputs(nowMillis: Long): SessionActivityRegistry = copy(
        records = records.mapValues { (_, record) ->
            record.copy(pendingInputs = record.pendingInputs.filterValues { it == null || it > nowMillis })
        },
    )

    private fun SessionActivityRecord.shouldSettleWhenAbsent(): Boolean =
        runtimeId != null || evidence.source in setOf(
            SessionActivityEvidenceSource.ActiveList,
            SessionActivityEvidenceSource.Checkpoint,
            SessionActivityEvidenceSource.Directory,
        )

    private fun SessionActivityUpdate.scope(): SessionActivityScope = when (this) {
        is SessionActivityUpdate.BeginGeneration -> scope
        is SessionActivityUpdate.ObserveOwner -> owner.scope()
        is SessionActivityUpdate.RuntimeState -> scope
        is SessionActivityUpdate.ActiveList -> scope
        is SessionActivityUpdate.StatusUnavailable -> scope
        is SessionActivityUpdate.Terminal -> owner.scope()
        is SessionActivityUpdate.LocalSend -> owner.scope()
        is SessionActivityUpdate.LiveState -> owner.scope()
        is SessionActivityUpdate.RestoreCheckpoint -> owner.scope()
        is SessionActivityUpdate.PendingInputOpened -> owner.scope()
        is SessionActivityUpdate.PendingInputClosed -> owner.scope()
        is SessionActivityUpdate.ProcessState -> owner.scope()
        is SessionActivityUpdate.Tick -> error("Tick has no scope")
    }

    private fun SessionActivityOwner.scope() = SessionActivityScope.of(connectionId, profile)

    private fun SessionLiveStatus.phase(): SessionActivityPhase = when (this) {
        SessionLiveStatus.Starting -> SessionActivityPhase.Starting
        SessionLiveStatus.Working -> SessionActivityPhase.Working
        SessionLiveStatus.Waiting -> SessionActivityPhase.NeedsInput
        SessionLiveStatus.Idle -> SessionActivityPhase.Idle
    }

    data class RuntimeAlias(
        val scope: SessionActivityScope,
        val runtimeId: String,
        val generation: Long,
    )
}

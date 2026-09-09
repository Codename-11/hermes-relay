package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.ChatActivityChild
import com.hermesandroid.relay.data.ChatActivityKind
import com.hermesandroid.relay.data.ChatActivityPhase
import com.hermesandroid.relay.data.ChatActivityRecord
import com.hermesandroid.relay.data.ChatActivityStore
import com.hermesandroid.relay.data.InMemoryChatActivityStore
import com.hermesandroid.relay.data.boundChatActivities
import com.hermesandroid.relay.network.upstream.GatewayProcess
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Session-owned history metadata. Call capture only with events/snapshots owned by the selected session. */
internal class ChatActivityController(
    private val scope: CoroutineScope,
    private var store: ChatActivityStore = InMemoryChatActivityStore(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableRecords = MutableStateFlow<List<ChatActivityRecord>>(emptyList())
    val records: StateFlow<List<ChatActivityRecord>> = mutableRecords.asStateFlow()
    private var owner: Pair<String, String>? = null
    private var generation = 0L
    private var deletedOwner: Pair<String, String>? = null
    private val retiredIds = mutableSetOf<String>()
    private val writes = Mutex()

    fun bindStore(store: ChatActivityStore) {
        if (this.store === store) return
        this.store = store
        generation++
        load(migrate = true)
    }

    fun selectSession(scopeKey: String?, sessionId: String?) {
        val next = if (!scopeKey.isNullOrBlank() && !sessionId.isNullOrBlank()) scopeKey to sessionId else null
        if (next != null && next == deletedOwner) return
        if (next != null && next != deletedOwner) deletedOwner = null
        if (next == owner) return
        owner = next
        generation++
        mutableRecords.value = emptyList()
        retiredIds.clear()
        load()
    }

    private fun load(migrate: Boolean = false) {
        val selected = owner ?: return
        val selectedStore = store
        val selectedGeneration = generation
        scope.launch {
            val loaded = try {
                selectedStore.read(selected.first, selected.second)
            } catch (_: IOException) {
                emptyList()
            }
            if (selectedGeneration != generation || owner != selected || selectedStore !== store) return@launch
            val fresh = mutableRecords.value.associateBy(ChatActivityRecord::id)
            val merged = loaded.filter { it.scopeKey == selected.first && it.sessionId == selected.second && it.id !in retiredIds }
                .map { restored -> fresh[restored.id]?.let { live -> mergeRestored(restored, live) } ?: restored }
                .associateByTo(linkedMapOf(), ChatActivityRecord::id)
            fresh.forEach { (id, record) -> if (id !in merged) merged[id] = record }
            // A lazy disk read can reveal a fallback only after its live event supplied delegation identity.
            merged.values.filter { it.kind == ChatActivityKind.SUBAGENTS && !it.isFallback() }.toList()
                .forEach { target ->
                    val fallbacks = merged.values.filter { candidate ->
                        candidate.isFallback() && candidate.children.any { old ->
                            target.children.any { child -> old.id == child.id ||
                                (child.childSessionId != null && old.childSessionId == child.childSessionId) }
                        }
                    }
                    var consolidated = target
                    fallbacks.forEach { fallback ->
                        consolidated = mergeRestored(fallback, consolidated)
                        merged.remove(fallback.id)
                        retire(fallback)
                    }
                    merged[target.id] = consolidated
                }
            publish(merged.values.toList())
            mutableRecords.value.filter { it.id in fresh }.forEach { record ->
                if (migrate || fresh[record.id] != record) persist(record)
            }
        }
    }

    private fun mergeRestored(restored: ChatActivityRecord, fresh: ChatActivityRecord): ChatActivityRecord {
        if (fresh.kind != ChatActivityKind.SUBAGENTS) return fresh.copy(createdAt = minOf(restored.createdAt, fresh.createdAt))
        val children = mergeChildren(restored.children, fresh.children)
        val count = maxOf(restored.taskCount, fresh.taskCount, children.size)
        return fresh.copy(
            createdAt = minOf(restored.createdAt, fresh.createdAt), children = children,
            phase = aggregate(children, count), taskCount = count,
        )
    }

    fun captureSubagents(activities: List<SubagentActivity>) {
        val selected = owner ?: return
        val identifiable = activities.mapNotNull { activity ->
            val childId = activity.subagentId?.takeIf(String::isNotBlank)
                ?: activity.childSessionId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val delegation = activity.delegationId?.takeIf(String::isNotBlank)
            val identityKind = when {
                delegation != null -> "delegation"
                !activity.subagentId.isNullOrBlank() -> "subagent"
                else -> "session"
            }
            val existing = if (delegation == null) mutableRecords.value.firstOrNull { record ->
                record.kind == ChatActivityKind.SUBAGENTS && record.children.any { it.matches(activity) }
            } else null
            val id = existing?.id ?: identity("subagents", identityKind, delegation ?: childId)
            Triple(id, existing?.sourceId ?: delegation ?: childId, activity)
        }
        identifiable.groupBy { it.first }.forEach { (id, group) ->
            val previous = mutableRecords.value.firstOrNull { it.id == id }
            val migrated = mutableRecords.value.filter { record ->
                record.id != id && record.isFallback() && group.any { (_, _, activity) ->
                    record.children.any { it.matches(activity) }
                }
            }
            val inherited = mergeChildren(migrated.flatMap { it.children }, previous?.children.orEmpty())
            val children = inherited.associateByTo(linkedMapOf(), ChatActivityChild::id)
            group.forEach { (_, _, activity) ->
                val childId = activity.subagentId?.takeIf(String::isNotBlank) ?: activity.childSessionId!!
                val old = children.values.firstOrNull { it.matches(activity) }
                if (old != null && old.id != childId) children.remove(old.id)
                children[childId] = ChatActivityChild(
                    id = childId,
                    childSessionId = activity.childSessionId ?: old?.childSessionId,
                    goal = activity.goal.take(512).ifBlank { old?.goal.orEmpty() },
                    phase = activity.phase.toHistoryPhase(),
                    // Terminal summary is bounded metadata; progress/tool-event bodies are never copied.
                    summary = if (activity.isTerminal) activity.summary?.take(512) ?: old?.summary else old?.summary,
                )
            }
            val now = clock()
            val values = children.values.toList()
            val count = maxOf(previous?.taskCount ?: 0, migrated.maxOfOrNull { it.taskCount } ?: 0,
                group.maxOf { it.third.taskCount }, values.size).coerceIn(0, 10_000)
            migrated.forEach(::retire)
            update(ChatActivityRecord(
                id = id, scopeKey = selected.first, sessionId = selected.second,
                kind = ChatActivityKind.SUBAGENTS, sourceId = group.first().second,
                title = "Subagents", phase = aggregate(values, count),
                createdAt = minOf(previous?.createdAt ?: now, migrated.minOfOrNull { it.createdAt } ?: now),
                updatedAt = previous?.updatedAt ?: now,
                children = values, taskCount = count,
            ))
        }
    }

    /** An authoritative session-scoped process.list snapshot, including an empty successful snapshot. */
    fun captureProcesses(processes: List<GatewayProcess>) {
        val selected = owner ?: return
        val seen = mutableSetOf<String>()
        processes.filter { it.id.isNotBlank() }.forEach { process ->
            val id = identity("process", process.id, process.startedAt)
            seen += id
            val previous = mutableRecords.value.firstOrNull { it.id == id }
            val now = clock()
            update(ChatActivityRecord(
                id = id, scopeKey = selected.first, sessionId = selected.second, kind = ChatActivityKind.PROCESS,
                sourceId = process.id, title = "Background command", phase = process.toHistoryPhase(),
                createdAt = previous?.createdAt ?: now, updatedAt = previous?.updatedAt ?: now,
                processId = process.id, processStartedAt = process.startedAt, exitCode = process.exitCode,
            ))
        }
        mutableRecords.value.filter {
            it.kind == ChatActivityKind.PROCESS && it.phase == ChatActivityPhase.RUNNING && it.id !in seen
        }.forEach { update(it.copy(phase = ChatActivityPhase.UNKNOWN)) }
    }

    fun removeSession(scopeKey: String, sessionId: String) {
        if (owner == (scopeKey to sessionId)) {
            deletedOwner = owner
            owner = null
            generation++
            mutableRecords.value = emptyList()
        }
        val selectedStore = store
        scope.launch { writes.withLock { selectedStore.removeSession(scopeKey, sessionId) } }
    }

    fun markUnavailable() {
        mutableRecords.value.toList().forEach { record ->
            val children = record.children.map {
                if (it.phase == ChatActivityPhase.RUNNING) it.copy(phase = ChatActivityPhase.UNKNOWN) else it
            }
            val phase = if (record.phase == ChatActivityPhase.RUNNING) ChatActivityPhase.UNKNOWN else record.phase
            update(record.copy(phase = phase, children = children))
        }
    }

    private fun retire(record: ChatActivityRecord) {
        retiredIds += record.id
        mutableRecords.value = mutableRecords.value.filterNot { it.id == record.id }
        val selectedStore = store
        scope.launch {
            try {
                writes.withLock { selectedStore.removeRecord(record.scopeKey, record.sessionId, record.id) }
            } catch (_: IOException) {
                // The live selection still suppresses the superseded entry.
            }
        }
    }

    private fun update(candidate: ChatActivityRecord) {
        val previous = mutableRecords.value.firstOrNull { it.id == candidate.id }
        if (previous == candidate) return
        val updated = candidate.copy(updatedAt = maxOf(clock(), (previous?.updatedAt ?: -1L) + 1L))
        publish(mutableRecords.value.filterNot { it.id == updated.id } + updated)
        mutableRecords.value.firstOrNull { it.id == updated.id }?.let(::persist)
    }

    private fun publish(records: List<ChatActivityRecord>) {
        // Logical timestamps can advance within one wall-clock millisecond.
        mutableRecords.value = boundChatActivities(records, maxOf(clock(), records.maxOfOrNull { it.updatedAt } ?: 0L))
            .sortedWith(compareBy<ChatActivityRecord> { it.createdAt }.thenBy { it.id })
    }

    private fun persist(record: ChatActivityRecord) {
        val selectedStore = store
        scope.launch {
            try {
                writes.withLock { selectedStore.upsert(record) }
            } catch (_: IOException) {
                // Keep the session's visible metadata when local persistence is temporarily unavailable.
            }
        }
    }
}

private fun identity(vararg parts: String?): String = parts.joinToString("") {
    if (it == null) "-1:" else "${it.length}:$it"
}

private fun SubagentActivityPhase.toHistoryPhase(): ChatActivityPhase = when (this) {
    SubagentActivityPhase.STARTED, SubagentActivityPhase.THINKING, SubagentActivityPhase.TOOL,
    SubagentActivityPhase.PROGRESS -> ChatActivityPhase.RUNNING
    SubagentActivityPhase.COMPLETED -> ChatActivityPhase.COMPLETE
    SubagentActivityPhase.FAILED -> ChatActivityPhase.FAILED
    SubagentActivityPhase.INTERRUPTED -> ChatActivityPhase.CANCELLED
    SubagentActivityPhase.ENDED_WITH_PARENT -> ChatActivityPhase.UNKNOWN
}

private fun aggregate(children: List<ChatActivityChild>, taskCount: Int): ChatActivityPhase = when {
    children.any { it.phase == ChatActivityPhase.RUNNING } -> ChatActivityPhase.RUNNING
    children.isEmpty() || children.size < taskCount || children.any { it.phase == ChatActivityPhase.UNKNOWN } -> ChatActivityPhase.UNKNOWN
    children.any { it.phase == ChatActivityPhase.FAILED } -> ChatActivityPhase.FAILED
    children.any { it.phase == ChatActivityPhase.CANCELLED } -> ChatActivityPhase.CANCELLED
    else -> ChatActivityPhase.COMPLETE
}

private fun ChatActivityChild.matches(activity: SubagentActivity): Boolean =
    (!activity.subagentId.isNullOrBlank() && id == activity.subagentId) ||
        (!activity.childSessionId.isNullOrBlank() && childSessionId == activity.childSessionId)

private fun ChatActivityRecord.isFallback(): Boolean = kind == ChatActivityKind.SUBAGENTS &&
    (id == identity("subagents", "subagent", sourceId) || id == identity("subagents", "session", sourceId))

private fun mergeChildren(old: List<ChatActivityChild>, fresh: List<ChatActivityChild>): List<ChatActivityChild> {
    val merged = old.associateByTo(linkedMapOf(), ChatActivityChild::id)
    fresh.forEach { child ->
        val alias = merged.values.firstOrNull {
            it.id == child.id || (child.childSessionId != null && it.childSessionId == child.childSessionId)
        }
        if (alias != null && alias.id != child.id) merged.remove(alias.id)
        merged[child.id] = child
    }
    return merged.values.toList()
}

private fun GatewayProcess.toHistoryPhase(): ChatActivityPhase = when {
    isRunning -> ChatActivityPhase.RUNNING
    status.lowercase() in setOf("cancelled", "canceled", "killed", "interrupted") -> ChatActivityPhase.CANCELLED
    status.equals("failed", ignoreCase = true) || (exitCode != null && exitCode != 0) -> ChatActivityPhase.FAILED
    exitCode == 0 -> ChatActivityPhase.COMPLETE
    status.lowercase() in setOf("completed", "complete", "exited", "finished", "done") -> ChatActivityPhase.COMPLETE
    else -> ChatActivityPhase.UNKNOWN
}

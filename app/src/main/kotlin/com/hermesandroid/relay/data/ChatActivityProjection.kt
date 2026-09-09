package com.hermesandroid.relay.data

/** Presentation only: canonical rows retain their wire identity, role and content. */
internal fun projectChatActivityReceipts(
    messages: List<ChatMessage>,
    records: List<ChatActivityRecord>,
    scopeKey: String?,
    sessionId: String?,
): List<ChatMessage> {
    val originals = messages.filterNot {
        it.clientOnly && it.id.startsWith("activity:") && it.activityRecord != null
    }
    if (scopeKey.isNullOrBlank() || sessionId.isNullOrBlank()) {
        return originals.map { it.copy(activityRecord = null) }
    }
    val owned = records.filter { it.scopeKey == scopeKey && it.sessionId == sessionId }
        .sortedByDescending { it.updatedAt }
        .distinctBy { it.id }
    val represented = mutableSetOf<String>()
    val canonical = originals.map { message ->
        val process = message.hermesProcessNotificationOrNull()
        val delegation = message.activitySourceId?.takeIf { it.startsWith("delegation:") }
            ?.removePrefix("delegation:")?.takeIf { it.isNotBlank() }
        val kind = when {
            message.activitySourceId != null -> ChatActivityKind.SUBAGENTS
            process != null -> ChatActivityKind.PROCESS
            else -> return@map message.copy(activityRecord = null)
        }
        val sourceId = delegation ?: process?.processId
        val processTerminal = process?.let { notice ->
            PROCESS_TERMINAL_HEADLINE.matchEntire(notice.headline)?.let { match ->
                match.groupValues[2].toIntOrNull()?.let { code ->
                    val phase = when {
                        match.groupValues[1].startsWith("terminated by ") -> ChatActivityPhase.CANCELLED
                        code == 0 -> ChatActivityPhase.COMPLETE
                        else -> ChatActivityPhase.FAILED
                    }
                    phase to code
                }
            }
        }
        // A process id can be reused after a registry restart. A canonical row has
        // no start-generation field, so multiple generations must remain unmatched.
        val matching = if (sourceId == null) emptyList() else owned.filter {
            it.kind == kind && it.sourceId == sourceId
        }
        val record = matching.singleOrNull()?.also { represented += it.id }
            ?: ChatActivityRecord(
                id = "canonical:${message.id}",
                scopeKey = scopeKey,
                sessionId = sessionId,
                kind = kind,
                sourceId = sourceId ?: "unavailable:${message.id}",
                title = process?.headline ?: message.content,
                phase = when {
                    kind == ChatActivityKind.PROCESS -> processTerminal?.first ?: ChatActivityPhase.UNKNOWN
                    (message.activityFailedCount ?: 0) > 0 ->
                        if (message.activityFailedCount == message.activityTaskCount) {
                            ChatActivityPhase.FAILED
                        } else ChatActivityPhase.UNKNOWN
                    (message.activityTaskCount ?: 0) > 0 -> ChatActivityPhase.COMPLETE
                    else -> ChatActivityPhase.UNKNOWN
                },
                createdAt = message.timestamp,
                updatedAt = message.timestamp,
                taskCount = message.activityTaskCount?.coerceAtLeast(0) ?: 0,
                processId = process?.processId,
                exitCode = processTerminal?.second,
            )
        // Aggregate completion metadata never rewrites captured child phases.
        message.copy(activityRecord = record)
    }
    val pending = owned.filter { it.phase != ChatActivityPhase.RUNNING && it.id !in represented }
        .sortedWith(compareBy<ChatActivityRecord> { it.updatedAt }.thenBy { it.id })
        .map { record ->
            ChatMessage(
                id = "activity:${record.id}",
                role = MessageRole.SYSTEM,
                content = record.title,
                timestamp = record.updatedAt,
                clientOnly = true,
                activityRecord = record,
            )
        }
    // Stable merge: history order is authoritative even if server timestamps
    // regress. Only insert local receipts; never sort canonical messages.
    var next = 0
    return buildList {
        canonical.forEach { message ->
            while (next < pending.size && pending[next].timestamp < message.timestamp) {
                add(pending[next++])
            }
            add(message)
        }
        while (next < pending.size) add(pending[next++])
    }
}

/** Upstream completion envelope only; never search arbitrary command/output text. */
private val PROCESS_TERMINAL_HEADLINE = Regex(
    """Background process \S+ (completed normally|exited|terminated by [^\r\n]+|marked lost because the process backend disappeared|failed to start) \(exit code (-?\d+)(?:, SIGTERM)?\)\.""",
)

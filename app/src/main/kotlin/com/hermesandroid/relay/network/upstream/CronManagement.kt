package com.hermesandroid.relay.network.upstream

/**
 * User-reviewed input for upstream `cron.manage` creation.
 *
 * Repeat is intentionally bounded on the client. Upstream treats zero and
 * negative values as an unlimited schedule, which is unsafe when the user
 * explicitly chose a finite run count.
 */
data class CronCreationDraft(
    val name: String,
    val schedule: String,
    val prompt: String,
    val repeat: Int? = null,
    val profile: String? = null,
) {
    fun validated(): Result<CronCreationDraft> = runCatching {
        val cleanName = name.trim()
        val cleanSchedule = schedule.trim()
        val cleanPrompt = prompt.trim()
        require(cleanName.isNotEmpty()) { "Schedule name is required" }
        require(cleanSchedule.isNotEmpty()) { "Schedule is required" }
        require(cleanPrompt.isNotEmpty()) { "Task instructions are required" }
        require(repeat == null || repeat in MIN_REPEAT..MAX_REPEAT) {
            "Run count must be between $MIN_REPEAT and $MAX_REPEAT"
        }
        copy(
            name = cleanName,
            schedule = cleanSchedule,
            prompt = cleanPrompt,
            profile = profile?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    companion object {
        const val MIN_REPEAT = 1
        const val MAX_REPEAT = 999
    }
}

/** Parse an optional finite-count field without ever coercing invalid input to unlimited. */
internal fun parseFiniteRepeat(value: String): Result<Int?> = runCatching {
    val clean = value.trim()
    if (clean.isEmpty()) return@runCatching null
    val parsed = clean.toIntOrNull() ?: throw IllegalArgumentException("Run count must be a whole number")
    require(parsed in CronCreationDraft.MIN_REPEAT..CronCreationDraft.MAX_REPEAT) {
        "Run count must be between ${CronCreationDraft.MIN_REPEAT} and ${CronCreationDraft.MAX_REPEAT}"
    }
    parsed
}

package com.hermesandroid.relay.data

/**
 * Decides whether a persisted last-session pointer should be restored on open.
 *
 * Cold open resumes the saved chat only while its last activity is still inside
 * [RESUME_TTL_MS]. Older (or unknown) activity yields a blank draft; the thread
 * remains available in the session drawer.
 */
object SessionResumePolicy {
    /** Resume saved chat only if last activity is newer than this window. */
    const val RESUME_TTL_MS: Long = 2L * 60L * 60L * 1000L // 2 hours

    fun sessionIdToRestore(
        savedSessionId: String?,
        lastActivityAtMs: Long?,
        nowMs: Long,
        directoryLastActivityAtMs: Long? = null,
        ttlMs: Long = RESUME_TTL_MS,
    ): String? {
        val id = savedSessionId?.takeIf { it.isNotBlank() } ?: return null
        val activity = firstPositive(lastActivityAtMs, directoryLastActivityAtMs) ?: return null
        val age = nowMs - activity
        if (age < 0L) return id // clock skew: treat as fresh
        return if (age < ttlMs) id else null
    }

    private fun firstPositive(vararg values: Long?): Long? =
        values.firstOrNull { it != null && it > 0L }
}

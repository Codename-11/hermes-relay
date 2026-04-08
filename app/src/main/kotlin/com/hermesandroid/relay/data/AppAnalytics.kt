package com.hermesandroid.relay.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Lightweight in-app analytics tracker.
 *
 * Tracks response times, token usage, session stats, connection health,
 * and stream outcomes. Lifetime stats are persisted to DataStore; session
 * stats reset when the app process restarts.
 *
 * All public methods are thread-safe (StateFlow + atomic updates).
 * Call [initialize] once from Application.onCreate().
 */
object AppAnalytics {

    private const val TAG = "AppAnalytics"
    private const val MAX_RECENT_TIMES = 20

    // DataStore keys for lifetime stats
    private val KEY_TOTAL_MESSAGES_SENT = intPreferencesKey("analytics_total_messages_sent")
    private val KEY_TOTAL_TOKENS_IN = longPreferencesKey("analytics_total_tokens_in")
    private val KEY_TOTAL_TOKENS_OUT = longPreferencesKey("analytics_total_tokens_out")
    private val KEY_TOTAL_RESPONSE_TIME_MS = longPreferencesKey("analytics_total_response_time_ms")
    private val KEY_TOTAL_COMPLETION_TIME_MS = longPreferencesKey("analytics_total_completion_time_ms")
    private val KEY_RESPONSE_TIME_COUNT = intPreferencesKey("analytics_response_time_count")
    private val KEY_COMPLETION_TIME_COUNT = intPreferencesKey("analytics_completion_time_count")
    private val KEY_STREAMS_COMPLETED = intPreferencesKey("analytics_streams_completed")
    private val KEY_STREAMS_ERRORED = intPreferencesKey("analytics_streams_errored")
    private val KEY_STREAMS_CANCELLED = intPreferencesKey("analytics_streams_cancelled")
    private val KEY_HEALTH_CHECKS_TOTAL = intPreferencesKey("analytics_health_checks_total")
    private val KEY_HEALTH_CHECKS_SUCCESS = intPreferencesKey("analytics_health_checks_success")
    private val KEY_TOTAL_HEALTH_LATENCY_MS = longPreferencesKey("analytics_total_health_latency_ms")
    private val KEY_SESSION_COUNT = intPreferencesKey("analytics_session_count")
    private val KEY_RECENT_RESPONSE_TIMES = stringPreferencesKey("analytics_recent_response_times")
    private val KEY_RECENT_COMPLETION_TIMES = stringPreferencesKey("analytics_recent_completion_times")

    private val _stats = MutableStateFlow(AppStats())
    val stats: StateFlow<AppStats> = _stats.asStateFlow()

    private var context: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Timing state for current in-flight message
    private var messageSentAtMs: Long = 0L
    private var firstTokenReceivedAtMs: Long = 0L
    private var firstTokenReceived: Boolean = false

    /**
     * Initialize with application context. Loads persisted lifetime stats.
     * Must be called once from Application.onCreate().
     */
    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        scope.launch { loadFromDataStore() }
    }

    // --- Event hooks (call from ChatViewModel / HermesApiClient) ---

    /** Called when a user sends a message. Starts the response timer. */
    fun onMessageSent() {
        messageSentAtMs = System.currentTimeMillis()
        firstTokenReceived = false
        firstTokenReceivedAtMs = 0L

        _stats.value = _stats.value.let { s ->
            s.copy(
                totalMessagesSent = s.totalMessagesSent + 1,
                currentSessionMessages = s.currentSessionMessages + 1
            )
        }
        persistAsync()
    }

    /** Called when the first text delta arrives. Calculates TTFT. */
    fun onFirstTokenReceived() {
        if (firstTokenReceived || messageSentAtMs == 0L) return
        firstTokenReceived = true
        firstTokenReceivedAtMs = System.currentTimeMillis()

        val ttft = firstTokenReceivedAtMs - messageSentAtMs

        _stats.value = _stats.value.let { s ->
            val newRecent = (s.recentResponseTimesMs + ttft).takeLast(MAX_RECENT_TIMES)
            val newCount = s.responseTimeCount + 1
            val newTotal = s.totalResponseTimeMs + ttft
            s.copy(
                avgResponseTimeMs = if (newCount > 0) newTotal / newCount else 0L,
                recentResponseTimesMs = newRecent,
                responseTimeCount = newCount,
                totalResponseTimeMs = newTotal
            )
        }
        persistAsync()
    }

    /**
     * Called when streaming completes successfully.
     * [inputTokens] and [outputTokens] may be null if the server doesn't report them.
     */
    fun onStreamComplete(inputTokens: Int?, outputTokens: Int?) {
        val now = System.currentTimeMillis()
        val completionTime = if (messageSentAtMs > 0L) now - messageSentAtMs else 0L

        _stats.value = _stats.value.let { s ->
            val tokIn = inputTokens?.toLong() ?: 0L
            val tokOut = outputTokens?.toLong() ?: 0L

            val newRecentCompletion = if (completionTime > 0L) {
                (s.recentCompletionTimesMs + completionTime).takeLast(MAX_RECENT_TIMES)
            } else {
                s.recentCompletionTimesMs
            }

            val newCompletionCount = if (completionTime > 0L) s.completionTimeCount + 1 else s.completionTimeCount
            val newCompletionTotal = s.totalCompletionTimeMs + completionTime

            val newStreamsCompleted = s.streamsCompleted + 1
            val totalStreams = newStreamsCompleted + s.streamsErrored + s.streamsCancelled

            s.copy(
                totalTokensIn = s.totalTokensIn + tokIn,
                totalTokensOut = s.totalTokensOut + tokOut,
                currentSessionTokensIn = s.currentSessionTokensIn + tokIn,
                currentSessionTokensOut = s.currentSessionTokensOut + tokOut,
                avgCompletionTimeMs = if (newCompletionCount > 0) newCompletionTotal / newCompletionCount else 0L,
                recentCompletionTimesMs = newRecentCompletion,
                completionTimeCount = newCompletionCount,
                totalCompletionTimeMs = newCompletionTotal,
                streamsCompleted = newStreamsCompleted,
                streamSuccessRate = if (totalStreams > 0) newStreamsCompleted.toFloat() / totalStreams else 1f
            )
        }

        // Reset timing state
        messageSentAtMs = 0L
        firstTokenReceived = false
        persistAsync()
    }

    /** Called when streaming encounters an error. */
    fun onStreamError() {
        _stats.value = _stats.value.let { s ->
            val newErrored = s.streamsErrored + 1
            val totalStreams = s.streamsCompleted + newErrored + s.streamsCancelled
            s.copy(
                streamsErrored = newErrored,
                streamSuccessRate = if (totalStreams > 0) s.streamsCompleted.toFloat() / totalStreams else 0f
            )
        }

        messageSentAtMs = 0L
        firstTokenReceived = false
        persistAsync()
    }

    /** Called when a stream is intentionally cancelled by the user. */
    fun onStreamCancelled() {
        _stats.value = _stats.value.let { s ->
            val newCancelled = s.streamsCancelled + 1
            val totalStreams = s.streamsCompleted + s.streamsErrored + newCancelled
            s.copy(
                streamsCancelled = newCancelled,
                streamSuccessRate = if (totalStreams > 0) s.streamsCompleted.toFloat() / totalStreams else 1f
            )
        }

        messageSentAtMs = 0L
        firstTokenReceived = false
        persistAsync()
    }

    /** Called after a health check completes. */
    fun onHealthCheck(success: Boolean, latencyMs: Long) {
        _stats.value = _stats.value.let { s ->
            val newTotal = s.healthChecksTotal + 1
            val newSuccess = s.healthChecksSuccess + if (success) 1 else 0
            val newLatencyTotal = s.totalHealthLatencyMs + latencyMs
            s.copy(
                healthChecksTotal = newTotal,
                healthChecksSuccess = newSuccess,
                healthCheckSuccessRate = if (newTotal > 0) newSuccess.toFloat() / newTotal else 0f,
                avgHealthLatencyMs = if (newTotal > 0) newLatencyTotal / newTotal else 0L,
                totalHealthLatencyMs = newLatencyTotal
            )
        }
        persistAsync()
    }

    /** Called when a new session is created. */
    fun onSessionCreated() {
        _stats.value = _stats.value.let { s ->
            s.copy(
                sessionCount = s.sessionCount + 1,
                currentSessionMessages = 0,
                currentSessionTokensIn = 0L,
                currentSessionTokensOut = 0L
            )
        }
        persistAsync()
    }

    /** Reset current-session counters (e.g., when switching sessions). */
    fun onSessionSwitched() {
        _stats.value = _stats.value.copy(
            currentSessionMessages = 0,
            currentSessionTokensIn = 0L,
            currentSessionTokensOut = 0L
        )
    }

    /** Reset all analytics data (called from data reset). */
    fun resetAll() {
        _stats.value = AppStats()
        messageSentAtMs = 0L
        firstTokenReceived = false
        firstTokenReceivedAtMs = 0L
        persistAsync()
    }

    // --- Persistence ---

    private fun persistAsync() {
        val ctx = context ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val s = _stats.value
                ctx.relayDataStore.edit { prefs ->
                    prefs[KEY_TOTAL_MESSAGES_SENT] = s.totalMessagesSent
                    prefs[KEY_TOTAL_TOKENS_IN] = s.totalTokensIn
                    prefs[KEY_TOTAL_TOKENS_OUT] = s.totalTokensOut
                    prefs[KEY_TOTAL_RESPONSE_TIME_MS] = s.totalResponseTimeMs
                    prefs[KEY_TOTAL_COMPLETION_TIME_MS] = s.totalCompletionTimeMs
                    prefs[KEY_RESPONSE_TIME_COUNT] = s.responseTimeCount
                    prefs[KEY_COMPLETION_TIME_COUNT] = s.completionTimeCount
                    prefs[KEY_STREAMS_COMPLETED] = s.streamsCompleted
                    prefs[KEY_STREAMS_ERRORED] = s.streamsErrored
                    prefs[KEY_STREAMS_CANCELLED] = s.streamsCancelled
                    prefs[KEY_HEALTH_CHECKS_TOTAL] = s.healthChecksTotal
                    prefs[KEY_HEALTH_CHECKS_SUCCESS] = s.healthChecksSuccess
                    prefs[KEY_TOTAL_HEALTH_LATENCY_MS] = s.totalHealthLatencyMs
                    prefs[KEY_SESSION_COUNT] = s.sessionCount
                    prefs[KEY_RECENT_RESPONSE_TIMES] = s.recentResponseTimesMs.joinToString(",")
                    prefs[KEY_RECENT_COMPLETION_TIMES] = s.recentCompletionTimesMs.joinToString(",")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist analytics: ${e.message}")
            }
        }
    }

    private suspend fun loadFromDataStore() {
        val ctx = context ?: return
        try {
            val prefs = ctx.relayDataStore.data.first()

            val recentResponse = prefs[KEY_RECENT_RESPONSE_TIMES]
                ?.split(",")
                ?.mapNotNull { it.toLongOrNull() }
                ?.takeLast(MAX_RECENT_TIMES)
                ?: emptyList()

            val recentCompletion = prefs[KEY_RECENT_COMPLETION_TIMES]
                ?.split(",")
                ?.mapNotNull { it.toLongOrNull() }
                ?.takeLast(MAX_RECENT_TIMES)
                ?: emptyList()

            val totalMessages = prefs[KEY_TOTAL_MESSAGES_SENT] ?: 0
            val totalTokensIn = prefs[KEY_TOTAL_TOKENS_IN] ?: 0L
            val totalTokensOut = prefs[KEY_TOTAL_TOKENS_OUT] ?: 0L
            val totalResponseTime = prefs[KEY_TOTAL_RESPONSE_TIME_MS] ?: 0L
            val totalCompletionTime = prefs[KEY_TOTAL_COMPLETION_TIME_MS] ?: 0L
            val responseTimeCount = prefs[KEY_RESPONSE_TIME_COUNT] ?: 0
            val completionTimeCount = prefs[KEY_COMPLETION_TIME_COUNT] ?: 0
            val streamsCompleted = prefs[KEY_STREAMS_COMPLETED] ?: 0
            val streamsErrored = prefs[KEY_STREAMS_ERRORED] ?: 0
            val streamsCancelled = prefs[KEY_STREAMS_CANCELLED] ?: 0
            val healthTotal = prefs[KEY_HEALTH_CHECKS_TOTAL] ?: 0
            val healthSuccess = prefs[KEY_HEALTH_CHECKS_SUCCESS] ?: 0
            val totalHealthLatency = prefs[KEY_TOTAL_HEALTH_LATENCY_MS] ?: 0L
            val sessionCount = prefs[KEY_SESSION_COUNT] ?: 0

            val totalStreams = streamsCompleted + streamsErrored + streamsCancelled

            _stats.value = AppStats(
                totalMessagesSent = totalMessages,
                totalTokensIn = totalTokensIn,
                totalTokensOut = totalTokensOut,
                avgResponseTimeMs = if (responseTimeCount > 0) totalResponseTime / responseTimeCount else 0L,
                avgCompletionTimeMs = if (completionTimeCount > 0) totalCompletionTime / completionTimeCount else 0L,
                streamSuccessRate = if (totalStreams > 0) streamsCompleted.toFloat() / totalStreams else 1f,
                healthCheckSuccessRate = if (healthTotal > 0) healthSuccess.toFloat() / healthTotal else 0f,
                avgHealthLatencyMs = if (healthTotal > 0) totalHealthLatency / healthTotal else 0L,
                sessionCount = sessionCount,
                currentSessionMessages = 0,
                currentSessionTokensIn = 0L,
                currentSessionTokensOut = 0L,
                recentResponseTimesMs = recentResponse,
                recentCompletionTimesMs = recentCompletion,
                // Internal accumulators
                totalResponseTimeMs = totalResponseTime,
                totalCompletionTimeMs = totalCompletionTime,
                responseTimeCount = responseTimeCount,
                completionTimeCount = completionTimeCount,
                streamsCompleted = streamsCompleted,
                streamsErrored = streamsErrored,
                streamsCancelled = streamsCancelled,
                healthChecksTotal = healthTotal,
                healthChecksSuccess = healthSuccess,
                totalHealthLatencyMs = totalHealthLatency
            )

            Log.d(TAG, "Loaded analytics: $totalMessages msgs, ${totalTokensIn + totalTokensOut} tokens")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load analytics: ${e.message}")
        }
    }
}

/**
 * Snapshot of all tracked analytics.
 *
 * UI-facing fields are the primary ones. Internal accumulators (prefixed
 * descriptions say "internal") are used for running averages and persistence.
 */
data class AppStats(
    // Lifetime totals
    val totalMessagesSent: Int = 0,
    val totalTokensIn: Long = 0L,
    val totalTokensOut: Long = 0L,
    val avgResponseTimeMs: Long = 0L,
    val avgCompletionTimeMs: Long = 0L,
    val streamSuccessRate: Float = 1f,
    val healthCheckSuccessRate: Float = 0f,
    val avgHealthLatencyMs: Long = 0L,
    val sessionCount: Int = 0,
    // Current session
    val currentSessionMessages: Int = 0,
    val currentSessionTokensIn: Long = 0L,
    val currentSessionTokensOut: Long = 0L,
    // Recent history for charting (last 20)
    val recentResponseTimesMs: List<Long> = emptyList(),
    val recentCompletionTimesMs: List<Long> = emptyList(),
    // Internal accumulators (not displayed directly, but needed for running averages)
    val totalResponseTimeMs: Long = 0L,
    val totalCompletionTimeMs: Long = 0L,
    val responseTimeCount: Int = 0,
    val completionTimeCount: Int = 0,
    val streamsCompleted: Int = 0,
    val streamsErrored: Int = 0,
    val streamsCancelled: Int = 0,
    val healthChecksTotal: Int = 0,
    val healthChecksSuccess: Int = 0,
    val totalHealthLatencyMs: Long = 0L
)

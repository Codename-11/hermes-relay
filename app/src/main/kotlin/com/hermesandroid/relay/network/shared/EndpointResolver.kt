package com.hermesandroid.relay.network.shared

import android.util.Log
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Last observed probe result for a single [EndpointCandidate], keyed by
 * [EndpointResolver.cacheKey] in [EndpointResolver.probeOutcomes]. Unlike the
 * probe *cache* (a short-TTL "don't re-ask the network" optimization), this is
 * a UI-facing record of what actually happened — it survives [EndpointResolver
 * .clearCache] so the Routes card can keep showing the most recent
 * reachability verdict between probes.
 */
data class RouteProbeOutcome(
    val reachable: Boolean,
    /** Short human-readable failure reason; null when [reachable]. */
    val detail: String? = null,
    /** Resolver-clock timestamp of when the probe finished. */
    val atMillis: Long,
)

/**
 * Picks the highest-priority **reachable** [EndpointCandidate] from a
 * per-device list, driven by ADR 24 "Multi-endpoint pairing + network-aware
 * switching" (2026-04-19).
 *
 * ### Semantics (locked by ADR 24)
 *
 *  * **Strict priority.** `priority = 0` is highest. If a priority-0
 *    candidate is reachable we use it; reachability never promotes a lower
 *    priority over a higher one. Reachability is **only** the tiebreaker
 *    among candidates that share the same priority.
 *  * **Reachability probe.** `HEAD ${api.url}/health` with a 2-second
 *    per-candidate timeout. Positive results are cached longer than negative
 *    results so repeated `connect()` calls don't hammer healthy routes, while
 *    transient handoff misses do not pin a good fallback offline.
 *  * **Network-change re-evaluate.** `ConnectionManager`'s network callback
 *    bumps the caller into `resolve()` again on `onAvailable`, and marks the
 *    active endpoint unreachable on `onLost` via [markUnreachable].
 *
 * The resolver is pure: no Context, no DataStore, no coroutine scope of its
 * own. Callers pass the pre-loaded [EndpointCandidate] list (from
 * `PairingPreferences.getDeviceEndpoints`), we run the probes, we return the
 * winner. That keeps the resolver testable from plain JUnit with a
 * MockWebServer stand-in.
 *
 * The resolver is **thread-safe** — the probe cache is a
 * [ConcurrentHashMap] so parallel probes from a race group don't tear it.
 */
class EndpointResolver(
    /**
     * OkHttp client used for probes. Callers pass the shared relay-side
     * client so TLS trust + DNS cache + cert-pinner state is consistent with
     * the eventual WSS connect. Internally the resolver applies its own
     * 2-second timeouts per call via [OkHttpClient.newBuilder], so the input
     * client's timeouts don't leak into probe behavior.
     */
    private val httpClient: OkHttpClient,
    /**
     * Swappable "now" for tests. Production uses [System.currentTimeMillis];
     * tests feed a mutable clock to exercise the 30-second TTL.
     */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Cached probe result. [expiresAt] is `clock()` + [CACHE_TTL_MS] when the
     * entry was written; after expiry the entry is re-probed.
     */
    private data class CacheEntry(val expiresAt: Long, val reachable: Boolean)

    private val probeCache = ConcurrentHashMap<String, CacheEntry>()

    private val _probeOutcomes = MutableStateFlow<Map<String, RouteProbeOutcome>>(emptyMap())

    /**
     * Last probe verdict per candidate, keyed by [cacheKey]. Drives the
     * per-row reachability line in the Routes card. Deliberately NOT wiped by
     * [clearCache] — the cache controls when we re-ask the network; this
     * records what the network last said.
     */
    val probeOutcomes: StateFlow<Map<String, RouteProbeOutcome>> = _probeOutcomes.asStateFlow()

    private fun recordOutcome(candidate: EndpointCandidate, reachable: Boolean, detail: String?) {
        _probeOutcomes.update { outcomes ->
            outcomes + (cacheKey(candidate) to RouteProbeOutcome(
                reachable = reachable,
                detail = detail,
                atMillis = clock(),
            ))
        }
    }

    companion object {
        private const val TAG = "EndpointResolver"
        /**
         * Per-candidate HEAD probe timeout. ADR 24 speced 2s which was
         * tight — LTE hand-off and slow hotel Wi-Fi routinely blew past
         * 2s on the first packet and got candidates marked unreachable
         * spuriously. 4s preserves "fast-fail on real outage" while
         * surviving the flaky-network case.
         */
        const val PROBE_TIMEOUT_MS = 4_000L
        /**
         * Successful probe-result cache TTL. Widened from ADR 24's 30s to 60s for
         * two reasons: (1) HEAD /health on every tab open was burning
         * battery unnecessarily on mobile, (2) NetworkCallback's
         * onAvailable / onLost invalidates the cache on real network
         * changes anyway, so a 60s idle cache is functionally
         * equivalent. Manual probes (EndpointsCard → "Probe now")
         * bypass the cache.
         */
        const val CACHE_TTL_MS = 60_000L

        /**
         * Failed probe-result cache TTL. Keep this intentionally short:
         * Android may report a new cellular/VPN network before Tailscale has
         * finished routing, so a single early ConnectException must not keep a
         * viable fallback route suppressed through the voice resume window.
         */
        const val NEGATIVE_CACHE_TTL_MS = 2_000L

        /** Shared timeout wording so HEAD-timeout and socket-timeout read the same. */
        private const val PROBE_TIMEOUT_DETAIL = "No answer (timed out)"

        /**
         * Stable cache key for a candidate: `"<role>|<api.host>:<api.port>"`.
         * Roles are preserved case-verbatim (HMAC canonicalization contract)
         * but hostnames are lowercased — two roles pointing at the same
         * host:port share reachability state.
         */
        internal fun cacheKey(candidate: EndpointCandidate): String =
            "${candidate.role}|${candidate.api.host.lowercase()}:${candidate.api.port}"
    }

    /**
     * Run the resolver against [candidates].
     *
     *  1. Group by `priority` ascending.
     *  2. For each priority group, race a HEAD /health probe against every
     *     candidate in the group (2 s per candidate). First 2xx wins; ties
     *     broken by whichever response lands first.
     *  3. If the entire group is unreachable, fall through to the next
     *     priority group.
     *  4. If no candidate is reachable, return `null` — the caller falls back
     *     to its legacy single-URL path.
     *
     * Candidates with an invalid api URL are skipped without affecting the
     * priority-group decision (a bad record shouldn't starve out the rest of
     * its tier). An empty [candidates] list returns null immediately without
     * touching the network.
     */
    suspend fun resolve(candidates: List<EndpointCandidate>): EndpointCandidate? {
        if (candidates.isEmpty()) return null

        // Strict priority: sort ascending so priority-0 lands first. Grouping
        // preserves emitted order within a priority class (DNS SRV parity).
        val groups = candidates.groupBy { it.priority }.toSortedMap()

        for ((priority, group) in groups) {
            Log.d(TAG, "probing priority=$priority group (size=${group.size})")
            val winner = raceGroup(group)
            if (winner != null) {
                Log.i(TAG, "resolve winner: role=${winner.role} " +
                    "api=${winner.api.host}:${winner.api.port} priority=$priority")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Endpoint,
                    severity = DiagnosticSeverity.Info,
                    title = "Endpoint selected",
                    detail = "priority=$priority",
                    endpointRole = winner.role,
                    url = winner.relay.url,
                )
                return winner
            }
        }

        Log.w(TAG, "resolve: no reachable candidate across ${candidates.size} record(s)")
        DiagnosticsLog.record(
            category = DiagnosticCategory.Endpoint,
            severity = DiagnosticSeverity.Warning,
            title = "No reachable endpoint",
            detail = "${candidates.size} configured route(s) failed health probes",
        )
        return null
    }

    /**
     * Race all candidates in [group] (same priority tier) in parallel. First
     * candidate that reports reachable — whether from cache or a fresh probe
     * — wins. Null when the entire group is unreachable.
     *
     * We **don't** await all probes before picking a winner: the spec calls
     * for "first 2xx wins" so latency matters. The losing probes' results
     * still land in the cache, though, so the next call benefits.
     */
    private suspend fun raceGroup(group: List<EndpointCandidate>): EndpointCandidate? {
        if (group.isEmpty()) return null
        if (group.size == 1) {
            val only = group.first()
            return if (isReachable(only)) only else null
        }

        // Fast-path: any cached-reachable candidate wins immediately without
        // touching the network.
        for (candidate in group) {
            val cached = probeCache[cacheKey(candidate)]
            if (cached != null && cached.expiresAt > clock() && cached.reachable) {
                return candidate
            }
        }

        return coroutineScope {
            val deferred = group.map { candidate ->
                async(Dispatchers.IO) {
                    if (isReachable(candidate)) candidate else null
                }
            }
            // Collect results in arrival order: iterate through awaitAll +
            // pick the first non-null. awaitAll preserves input order, which
            // means a slow-but-reachable priority-0 candidate would block a
            // fast-and-reachable sibling. But HEAD /health against a healthy
            // API route replies in <100ms and the timeout caps stragglers at 2s,
            // so this is acceptable in practice. A true "first to arrive"
            // would need kotlinx.coroutines Channel plumbing that's not
            // worth the weight here.
            deferred.awaitAll().firstOrNull { it != null }
        }
    }

    /**
     * Cache-aware reachability check for a single candidate. Consults
     * [probeCache] first; on miss or expiry, runs a HEAD /health probe and
     * records the result.
     */
    private suspend fun isReachable(candidate: EndpointCandidate): Boolean {
        val key = cacheKey(candidate)
        val now = clock()
        val cached = probeCache[key]
        if (cached != null && cached.expiresAt > now) {
            Log.d(TAG, "cache hit for $key reachable=${cached.reachable}")
            return cached.reachable
        }

        val reachable = probe(candidate)
        val ttl = if (reachable) CACHE_TTL_MS else NEGATIVE_CACHE_TTL_MS
        probeCache[key] = CacheEntry(expiresAt = now + ttl, reachable = reachable)
        return reachable
    }

    /**
     * One-shot HEAD /health probe against a candidate. 2-second timeout,
     * no retries — callers that need retry semantics can re-invoke after
     * the cache expires.
     *
     * Returns false on any failure (timeout, I/O, non-2xx, invalid URL).
     * We never raise: a bad record shouldn't crash the connect loop.
     */
    private suspend fun probe(candidate: EndpointCandidate): Boolean {
        val startedAtMs = clock()
        val url = "${candidate.api.url}/health".toHttpUrlOrNull()
            ?: run {
                Log.w(TAG, "probe: invalid url for role=${candidate.role}")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Endpoint,
                    severity = DiagnosticSeverity.Error,
                    title = "Endpoint probe invalid",
                    detail = "Invalid API URL",
                    endpointRole = candidate.role,
                    url = candidate.api.url,
                )
                recordOutcome(candidate, reachable = false, detail = "Invalid API URL")
                return false
            }
        val fastClient = httpClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .head()
            .header("Accept", "*/*")
            .build()
        return withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(PROBE_TIMEOUT_MS + 200L) {
                    fastClient.newCall(request).execute().use { resp ->
                        val ok = resp.isSuccessful
                        DiagnosticsLog.record(
                            category = DiagnosticCategory.Endpoint,
                            severity = if (ok) DiagnosticSeverity.Info else DiagnosticSeverity.Warning,
                            title = if (ok) "Endpoint probe ok" else "Endpoint probe failed",
                            detail = if (ok) null else "HTTP ${resp.code}",
                            endpointRole = candidate.role,
                            url = candidate.api.url,
                            elapsedMs = clock() - startedAtMs,
                        )
                        recordOutcome(
                            candidate,
                            reachable = ok,
                            detail = if (ok) null else "HTTP ${resp.code} from /health",
                        )
                        ok
                    }
                } ?: run {
                    DiagnosticsLog.record(
                        category = DiagnosticCategory.Endpoint,
                        severity = DiagnosticSeverity.Warning,
                        title = "Endpoint probe timeout",
                        detail = "No /health response in ${PROBE_TIMEOUT_MS}ms",
                        endpointRole = candidate.role,
                        url = candidate.api.url,
                        elapsedMs = clock() - startedAtMs,
                    )
                    recordOutcome(candidate, reachable = false, detail = PROBE_TIMEOUT_DETAIL)
                    false
                }
            } catch (_: TimeoutCancellationException) {
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Endpoint,
                    severity = DiagnosticSeverity.Warning,
                    title = "Endpoint probe timeout",
                    detail = "No /health response in ${PROBE_TIMEOUT_MS}ms",
                    endpointRole = candidate.role,
                    url = candidate.api.url,
                    elapsedMs = clock() - startedAtMs,
                )
                recordOutcome(candidate, reachable = false, detail = PROBE_TIMEOUT_DETAIL)
                false
            } catch (e: Exception) {
                Log.d(TAG, "probe failed role=${candidate.role} " +
                    "host=${candidate.api.host}: ${e.javaClass.simpleName}")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Endpoint,
                    severity = DiagnosticSeverity.Warning,
                    title = "Endpoint probe failed",
                    detail = e.javaClass.simpleName,
                    endpointRole = candidate.role,
                    url = candidate.api.url,
                    elapsedMs = clock() - startedAtMs,
                )
                recordOutcome(candidate, reachable = false, detail = humanProbeFailure(e))
                false
            }
        }
    }

    /**
     * Map a probe exception to a short, actionable string for the Routes
     * card. The TLS case is the headline: a route saved with `https://`
     * against a plain-HTTP Hermes API server fails its handshake on every
     * probe and previously surfaced as a silent "never switches" mystery.
     */
    private fun humanProbeFailure(e: Exception): String = when (e) {
        is SSLException -> "TLS failed — server may be http://, not https://"
        is ConnectException -> "Connection refused"
        is UnknownHostException -> "Host not found"
        is SocketTimeoutException -> PROBE_TIMEOUT_DETAIL
        is NoRouteToHostException -> "No route to host"
        else -> e.javaClass.simpleName
    }

    /**
     * Mark [candidate] unreachable without re-probing. Called from
     * `ConnectionManager`'s `NetworkCallback.onLost` so the next resolve()
     * skips the dead endpoint without waiting for its probe to time out.
     *
     * The entry is still TTL'd with the short negative TTL so a network-change
     * transition can skip the known-dead active route without suppressing a
     * valid fallback for the whole positive cache window.
     */
    fun markUnreachable(candidate: EndpointCandidate) {
        val key = cacheKey(candidate)
        probeCache[key] = CacheEntry(
            expiresAt = clock() + NEGATIVE_CACHE_TTL_MS,
            reachable = false,
        )
        recordOutcome(candidate, reachable = false, detail = "Network changed — assumed offline")
    }

    /**
     * Wipe the probe cache so the next resolve runs fresh probes. Called on
     * "the world changed" triggers — NetworkCallback events, manual "Probe
     * now", and [refreshActiveEndpoint][ConnectionManager.refreshActiveEndpoint]
     * with `clearProbeCache = true` — where a positive entry for a
     * just-died route must not outlive the handoff.
     */
    internal fun clearCache() {
        probeCache.clear()
    }

    /** Test-only: snapshot the current cache for assertion purposes. */
    internal fun cacheSnapshot(): Map<String, Pair<Long, Boolean>> =
        probeCache.mapValues { (_, v) -> v.expiresAt to v.reachable }
}

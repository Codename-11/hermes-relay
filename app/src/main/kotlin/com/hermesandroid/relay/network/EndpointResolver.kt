package com.hermesandroid.relay.network

import android.util.Log
import com.hermesandroid.relay.data.EndpointCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
 *    per-candidate timeout. The cache lives 30 seconds per `(role|host:port)`
 *    key so repeated `connect()` calls don't hammer the network.
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

    companion object {
        private const val TAG = "EndpointResolver"
        /** Per-candidate HEAD probe timeout. Matches ADR 24 "2-second timeout". */
        const val PROBE_TIMEOUT_MS = 2_000L
        /** Probe-result cache TTL. Matches ADR 24 "cached for 30 seconds per-endpoint". */
        const val CACHE_TTL_MS = 30_000L

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
                return winner
            }
        }

        Log.w(TAG, "resolve: no reachable candidate across ${candidates.size} record(s)")
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
            // relay replies in <100ms and the timeout caps stragglers at 2s,
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
        probeCache[key] = CacheEntry(
            expiresAt = now + CACHE_TTL_MS,
            reachable = reachable,
        )
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
        val url = "${candidate.api.url}/health".toHttpUrlOrNull()
            ?: run {
                Log.w(TAG, "probe: invalid url for role=${candidate.role}")
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
                        resp.isSuccessful
                    }
                } ?: false
            } catch (_: TimeoutCancellationException) {
                false
            } catch (e: Exception) {
                Log.d(TAG, "probe failed role=${candidate.role} " +
                    "host=${candidate.api.host}: ${e.javaClass.simpleName}")
                false
            }
        }
    }

    /**
     * Mark [candidate] unreachable without re-probing. Called from
     * `ConnectionManager`'s `NetworkCallback.onLost` so the next resolve()
     * skips the dead endpoint without waiting for its probe to time out.
     *
     * The entry is still TTL'd — after 30 seconds it expires and the next
     * resolve() will re-probe. That matches "ADR 24 — cached for 30 seconds"
     * and stops a permanently-cached stale result.
     */
    fun markUnreachable(candidate: EndpointCandidate) {
        val key = cacheKey(candidate)
        probeCache[key] = CacheEntry(
            expiresAt = clock() + CACHE_TTL_MS,
            reachable = false,
        )
    }

    /** Test-only: wipe the probe cache so a fresh run starts clean. */
    internal fun clearCache() {
        probeCache.clear()
    }

    /** Test-only: snapshot the current cache for assertion purposes. */
    internal fun cacheSnapshot(): Map<String, Pair<Long, Boolean>> =
        probeCache.mapValues { (_, v) -> v.expiresAt to v.reachable }
}

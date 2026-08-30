package com.hermesandroid.relay.network.shared

import android.content.Context
import android.util.Log
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpointContract
import com.hermesandroid.relay.data.primaryRouteUrl
import com.hermesandroid.relay.data.routeAuthority
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.diagnostics.NetworkDiagnosticGuidance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
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
 * Service whose reachability is being resolved. Standard Hermes surfaces and
 * Relay are intentionally independent: a healthy Dashboard must not vouch for
 * a dead Relay listener on the same host.
 */
enum class EndpointSurface {
    Standard,
    Dashboard,
    Api,
    Relay,
}

/**
 * Picks the highest-priority **reachable** [EndpointCandidate] from a
 * per-device list, driven by ADR 24 "Multi-endpoint pairing + network-aware
 * switching" (2026-04-19).
 *
 * ### Semantics (locked by ADR 24)
 *
 *  * **Strict selection priority with speculative probes.** `priority = 0`
 *    is highest. All supported priority groups start probing together so one
 *    dead route cannot add its full timeout before the fallback even starts,
 *    but a lower-priority result is considered only after every higher group
 *    has failed. Reachability is **only** the tiebreaker among candidates that
 *    share the same priority.
 *  * **Reachability probe.** Dashboard-first routes use lightweight `GET
 *    ${dashboard.url}/api/health` and fall back to `/api/status` only for a
 *    confirmed legacy host without the health route. Legacy API routes use `GET
 *    ${api.url}/health`. Relay-only routes use `GET ${relay.httpUrl}/health`.
 *    Each request has a 4-second
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
    /**
     * Application context for localized string resources. When null the
     * resolver falls back to hardcoded English strings — this is the
     * expected path for plain JVM tests.
     */
    private val context: Context? = null,
    /** Route-aware client for pinned plugin proxy probes. */
    private val clientForCandidate: ((EndpointCandidate) -> OkHttpClient?)? = null,
) {

    /**
     * Cached probe result. [expiresAt] is `clock()` + [CACHE_TTL_MS] when the
     * entry was written; after expiry the entry is re-probed.
     */
    private data class CacheEntry(val expiresAt: Long, val reachable: Boolean)

    private data class ProbeTarget(
        val baseUrl: String,
        val requestUrl: String,
        val path: String,
        val legacyFallbackRequestUrl: String? = null,
        val legacyFallbackPath: String? = null,
    )

    private data class ProbeHttpResult(
        val code: Int,
        val successful: Boolean,
        val bodyPreview: String,
    )

    private val probeCache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlightProbes = ConcurrentHashMap<String, Deferred<Boolean>>()
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeStateLock = Any()
    private var probeGeneration = 0L

    private val _probeOutcomes = MutableStateFlow<Map<String, RouteProbeOutcome>>(emptyMap())

    /**
     * Last probe verdict per candidate, keyed by [cacheKey]. Drives the
     * per-row reachability line in the Routes card. Deliberately NOT wiped by
     * [clearCache] — the cache controls when we re-ask the network; this
     * records what the network last said.
     */
    val probeOutcomes: StateFlow<Map<String, RouteProbeOutcome>> = _probeOutcomes.asStateFlow()

    /** Last independently observed verdict for one configured route surface. */
    fun outcomeFor(
        candidate: EndpointCandidate,
        surface: EndpointSurface,
    ): RouteProbeOutcome? = probeOutcomes.value[outcomeKey(candidate, surface)]

    private fun recordOutcome(
        candidate: EndpointCandidate,
        surface: EndpointSurface,
        reachable: Boolean,
        detail: String?,
    ) {
        _probeOutcomes.update { outcomes ->
            outcomes + (cacheKey(candidate, surface) to RouteProbeOutcome(
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
         * Failed probe-result cache TTL. Keep this bounded but long enough
         * that ordinary screen/profile lifecycle work cannot repeatedly pay
         * the full probe timeout:
         * Android may report a new cellular/VPN network before Tailscale has
         * finished routing, so a single early ConnectException must not keep a
         * viable fallback route suppressed for long. Network-change and
         * explicit-probe paths invalidate the cache immediately.
         */
        const val NEGATIVE_CACHE_TTL_MS = 15_000L

        /** Shared timeout wording so HEAD-timeout and socket-timeout read the same. */
        private const val PROBE_TIMEOUT_DETAIL = "No answer (timed out)"

        /**
         * Stable outcome/cache key for one candidate surface:
         * `"<surface>|<role>|<normalized service base>"`.
         * Roles are preserved case-verbatim (HMAC canonicalization contract)
         * but hostnames are lowercased — two roles pointing at the same
         * host:port share reachability state.
         */
        fun outcomeKey(
            candidate: EndpointCandidate,
            surface: EndpointSurface = EndpointSurface.Standard,
        ): String {
            val serviceIdentity = when (surface) {
                EndpointSurface.Standard ->
                    candidate.routeAuthority() ?: candidate.primaryRouteUrl().orEmpty().lowercase()
                EndpointSurface.Dashboard ->
                    routeIdentity(candidate.pluginProxyRoutesOrNull()?.dashboardBaseUrl ?: candidate.dashboard?.url)
                EndpointSurface.Api ->
                    routeIdentity(candidate.pluginProxyRoutesOrNull()?.apiBaseUrl ?: candidate.api?.url)
                EndpointSurface.Relay ->
                    routeIdentity(candidate.pluginProxyRoutesOrNull()?.relayHttpUrl ?: candidate.relay?.url)
            }
            return "${surface.name.lowercase()}|${candidate.role}|$serviceIdentity"
        }

        internal fun cacheKey(
            candidate: EndpointCandidate,
            surface: EndpointSurface = EndpointSurface.Standard,
        ): String = outcomeKey(candidate, surface)

        private fun routeAuthority(rawUrl: String?): String? {
            val candidate = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val httpUrl = when {
                candidate.startsWith("ws://", ignoreCase = true) ->
                    "http://${candidate.substringAfter("://")}"
                candidate.startsWith("wss://", ignoreCase = true) ->
                    "https://${candidate.substringAfter("://")}"
                else -> candidate
            }
            return httpUrl.toHttpUrlOrNull()?.let { url -> "${url.host}:${url.port}" }
        }

        private fun routeIdentity(rawUrl: String?): String {
            val candidate = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return ""
            val httpCandidate = when {
                candidate.startsWith("ws://", ignoreCase = true) ->
                    "http://${candidate.substringAfter("://")}"
                candidate.startsWith("wss://", ignoreCase = true) ->
                    "https://${candidate.substringAfter("://")}"
                else -> candidate
            }
            return httpCandidate.toHttpUrlOrNull()?.let { url ->
                val path = url.encodedPath.trimEnd('/').takeIf { it.isNotEmpty() }.orEmpty()
                "${url.scheme}://${url.host}:${url.port}$path"
            } ?: candidate.lowercase().trimEnd('/')
        }
    }

    /**
     * Run the resolver against [candidates].
     *
     *  1. Group by `priority` ascending and by supported/experimental tier.
     *  2. Start every supported priority group speculatively, while awaiting
     *     their results in strict priority order. Within a group, first 2xx
     *     wins. If higher groups fail, a completed fallback is ready at once.
     *  3. Probe the experimental tier only when every supported group fails.
     *  4. If no candidate is reachable, return `null` — the caller falls back
     *     to its legacy single-URL path.
     *
     * Candidates with an invalid api URL are skipped without affecting the
     * priority-group decision (a bad record shouldn't starve out the rest of
     * its tier). An empty [candidates] list returns null immediately without
     * touching the network.
     */
    suspend fun resolve(
        candidates: List<EndpointCandidate>,
        surface: EndpointSurface = EndpointSurface.Standard,
    ): EndpointCandidate? {
        val eligible = candidates.filter { probeTarget(it, surface) != null }
        if (eligible.isEmpty()) return null

        // Supported routes always run before experimental routes. Priority is
        // strict inside each stability tier, so Reach remains available as a
        // last-resort fallback without displacing Tailscale or direct TLS.
        val supported = eligible.filterNot { it.experimental || it.role.equals("outbound_broker", ignoreCase = true) }
        val experimental = eligible.filter { it.experimental || it.role.equals("outbound_broker", ignoreCase = true) }
        val tiers = listOf(
            supported.groupBy { it.priority }.toSortedMap().values.toList(),
            experimental.groupBy { it.priority }.toSortedMap().values.toList(),
        )

        for (groups in tiers) {
            val winner = racePriorityGroups(groups, surface)
            if (winner != null) {
                val priority = winner.priority
                val winnerUrl = probeTarget(winner, surface)?.baseUrl
                Log.i(TAG, "resolve winner: role=${winner.role} " +
                    "surface=$surface route=$winnerUrl priority=$priority")
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Endpoint,
                    severity = DiagnosticSeverity.Info,
                    title = context?.getString(R.string.endpoint_diag_selected) ?: "Endpoint selected",
                    detail = "priority=$priority",
                    endpointRole = winner.role,
                    url = winnerUrl,
                )
                return winner
            }
        }

        Log.w(TAG, "resolve: no reachable $surface candidate across ${eligible.size} record(s)")
        DiagnosticsLog.record(
            category = DiagnosticCategory.Endpoint,
            severity = DiagnosticSeverity.Warning,
            title = context?.getString(R.string.endpoint_diag_no_reachable) ?: "No reachable endpoint",
            detail = "${eligible.size} configured $surface route(s) failed health probes",
        )
        return null
    }

    /**
     * Start all groups in one stability tier together, but consume them in
     * strict priority order. Cancelling losing waiters never cancels the shared
     * physical probes, so their cache/outcome records still warm later calls.
     */
    private suspend fun racePriorityGroups(
        groups: List<List<EndpointCandidate>>,
        surface: EndpointSurface,
    ): EndpointCandidate? = coroutineScope {
        if (groups.isEmpty()) return@coroutineScope null
        val races = groups.map { group ->
            val priority = group.first().priority
            Log.d(TAG, "probing priority=$priority group (size=${group.size})")
            group to async(Dispatchers.IO) { raceGroup(group, surface) }
        }
        for ((_, race) in races) {
            val winner = race.await()
            if (winner != null) {
                races.forEach { (_, other) -> if (other !== race) other.cancel() }
                return@coroutineScope winner
            }
        }
        null
    }

    /**
     * Probe every independently configured route surface in parallel.
     *
     * Dashboard/Gateway, optional API fallback, and Relay do not vouch for one
     * another even when they share a hostname. Unconfigured surfaces are
     * omitted. Invalidated probes publish no result, preserving the prior
     * outcome until a fresh physical probe completes.
     */
    suspend fun probeSurfaces(
        candidate: EndpointCandidate,
    ): Map<EndpointSurface, RouteProbeOutcome> = coroutineScope {
        val configuredSurfaces = listOf(
            EndpointSurface.Dashboard,
            EndpointSurface.Api,
            EndpointSurface.Relay,
        ).filter { probeTarget(candidate, it) != null }

        configuredSurfaces
            .map { surface ->
                async {
                    isReachable(candidate, surface)
                    surface to currentCachedOutcomeFor(candidate, surface)
                }
            }
            .mapNotNull { deferred ->
                val (surface, outcome) = deferred.await()
                outcome?.let { surface to it }
            }
            .toMap()
    }

    /** Return only an outcome still backed by this generation's probe cache. */
    private fun currentCachedOutcomeFor(
        candidate: EndpointCandidate,
        surface: EndpointSurface,
    ): RouteProbeOutcome? = synchronized(probeStateLock) {
        val key = cacheKey(candidate, surface)
        val cached = probeCache[key]
        if (cached != null && cached.expiresAt > clock()) {
            probeOutcomes.value[key]
        } else {
            null
        }
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
    private suspend fun raceGroup(
        group: List<EndpointCandidate>,
        surface: EndpointSurface,
    ): EndpointCandidate? {
        if (group.isEmpty()) return null
        if (group.size == 1) {
            val only = group.first()
            return if (isReachable(only, surface)) only else null
        }

        // Fast-path: any cached-reachable candidate wins immediately without
        // touching the network.
        for (candidate in group) {
            val cached = probeCache[cacheKey(candidate, surface)]
            if (cached != null && cached.expiresAt > clock() && cached.reachable) {
                return candidate
            }
        }

        return coroutineScope {
            val completions = Channel<EndpointCandidate?>(group.size)
            val waiters = group.map { candidate ->
                launch(Dispatchers.IO) {
                    completions.send(if (isReachable(candidate, surface)) candidate else null)
                }
            }
            repeat(group.size) {
                val completed = completions.receive()
                if (completed != null) {
                    // Cancelling these waiters does not cancel the shared
                    // physical probes below; their outcomes still populate
                    // the cache for the next resolution.
                    waiters.forEach { it.cancel() }
                    return@coroutineScope completed
                }
            }
            null
        }
    }

    /**
     * Cache-aware reachability check for a single candidate. Consults
     * [probeCache] first; on miss or expiry, runs a HEAD /health probe and
     * records the result.
     */
    private suspend fun isReachable(
        candidate: EndpointCandidate,
        surface: EndpointSurface,
    ): Boolean {
        val key = cacheKey(candidate, surface)
        val now = clock()
        val cached = probeCache[key]
        if (cached != null && cached.expiresAt > now) {
            Log.d(TAG, "cache hit for $key reachable=${cached.reachable}")
            return cached.reachable
        }

        // Resolution is triggered from several independent lifecycle paths
        // (connection hydration, profile restoration, network callbacks, and
        // explicit probes). Share one physical request per route/surface so a
        // slow optional endpoint cannot accumulate duplicate 4-second probes.
        val shared = synchronized(probeStateLock) {
            inFlightProbes[key] ?: run {
                val generation = probeGeneration
                probeScope.async(start = CoroutineStart.LAZY) {
                    probe(candidate, surface, generation)
                }.also { deferred ->
                    inFlightProbes[key] = deferred
                    deferred.invokeOnCompletion { inFlightProbes.remove(key, deferred) }
                    deferred.start()
                }
            }
        }
        return try {
            shared.await()
        } catch (_: CancellationException) {
            // clearCache() owns cancellation of the shared physical probe. A
            // still-active waiter treats that invalidated result as unknown so
            // same-priority races can publish their non-winning completion.
            // Genuine caller cancellation still propagates from ensureActive.
            currentCoroutineContext().ensureActive()
            Log.d(TAG, "probe invalidated for $key")
            false
        }
    }

    /**
     * One-shot probe against a candidate's primary configured surface.
     * no retries — callers that need retry semantics can re-invoke after
     * the cache expires.
     *
     * Returns false on any failure (timeout, I/O, non-2xx, invalid URL).
     * We never raise: a bad record shouldn't crash the connect loop.
     */
    private suspend fun probe(
        candidate: EndpointCandidate,
        surface: EndpointSurface,
        generation: Long,
    ): Boolean {
        val startedAtMs = clock()
        val operation = when (surface) {
            EndpointSurface.Standard -> "Dashboard or API route health probe"
            EndpointSurface.Dashboard -> "Dashboard route health probe"
            EndpointSurface.Api -> "API route health probe"
            EndpointSurface.Relay -> "Relay route health probe"
        }
        val target = probeTarget(candidate, surface)
        val url = target?.requestUrl?.toHttpUrlOrNull()
            ?: run {
                return completeProbe(
                    candidate = candidate,
                    surface = surface,
                    generation = generation,
                    reachable = false,
                    detail = "Invalid route URL",
                ) {
                    Log.w(TAG, "probe: invalid url for role=${candidate.role}")
                    DiagnosticsLog.record(
                        category = DiagnosticCategory.Endpoint,
                        severity = DiagnosticSeverity.Error,
                        title = context?.getString(R.string.endpoint_diag_probe_invalid) ?: "Endpoint probe invalid",
                        detail = "No valid Dashboard, API, or Relay URL",
                        operation = operation,
                        endpointRole = candidate.role,
                        configuredUrl = candidate.primaryRouteUrl(),
                        suggestion = "Edit or re-pair this route so it contains a valid service URL.",
                    )
                }
            }
        val fastClient = (clientForCandidate?.invoke(candidate) ?: httpClient).newBuilder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        return withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(PROBE_TIMEOUT_MS + 200L) {
                    val primary = executeProbeHttp(fastClient, url)
                    val fallbackUrl = target.legacyFallbackRequestUrl
                        ?.takeIf { dashboardHealthNeedsLegacyFallback(primary) }
                        ?.toHttpUrlOrNull()
                    val result = fallbackUrl?.let { executeProbeHttp(fastClient, it) } ?: primary
                    val resultPath = if (fallbackUrl != null) {
                        target.legacyFallbackPath ?: target.path
                    } else {
                        target.path
                    }
                    val resultUrl = fallbackUrl?.toString() ?: target.requestUrl
                    result.let { response ->
                        val ok = response.successful
                        val probeTitle = if (ok) {
                            context?.getString(R.string.endpoint_diag_probe_ok) ?: "Endpoint probe ok"
                        } else {
                            context?.getString(R.string.endpoint_diag_probe_failed) ?: "Endpoint probe failed"
                        }
                        completeProbe(
                            candidate = candidate,
                            surface = surface,
                            generation = generation,
                            reachable = ok,
                            detail = if (ok) null else "HTTP ${response.code} from $resultPath",
                        ) {
                            DiagnosticsLog.record(
                                category = DiagnosticCategory.Endpoint,
                                severity = if (ok) DiagnosticSeverity.Info else DiagnosticSeverity.Warning,
                                title = probeTitle,
                                detail = if (ok) null else "HTTP ${response.code}",
                                operation = operation,
                                endpointRole = candidate.role,
                                configuredUrl = target.baseUrl,
                                requestUrl = resultUrl,
                                elapsedMs = clock() - startedAtMs,
                                suggestion = if (ok) {
                                    null
                                } else {
                                    NetworkDiagnosticGuidance.forHttpStatus(
                                        response.code,
                                        surface.diagnosticTarget(),
                                    )
                                },
                            )
                        }
                    }
                } ?: run {
                    completeProbe(
                        candidate = candidate,
                        surface = surface,
                        generation = generation,
                        reachable = false,
                        detail = PROBE_TIMEOUT_DETAIL,
                    ) {
                        DiagnosticsLog.record(
                            category = DiagnosticCategory.Endpoint,
                            severity = DiagnosticSeverity.Warning,
                            title = context?.getString(R.string.endpoint_diag_probe_timeout) ?: "Endpoint probe timeout",
                            detail = "No ${target.path} response in ${PROBE_TIMEOUT_MS}ms",
                            operation = operation,
                            endpointRole = candidate.role,
                            configuredUrl = target.baseUrl,
                            requestUrl = target.requestUrl,
                            elapsedMs = clock() - startedAtMs,
                            suggestion = "Check network routing or firewall rules between this device and ${surface.diagnosticTarget()}.",
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                completeProbe(
                    candidate = candidate,
                    surface = surface,
                    generation = generation,
                    reachable = false,
                    detail = PROBE_TIMEOUT_DETAIL,
                ) {
                    DiagnosticsLog.record(
                        category = DiagnosticCategory.Endpoint,
                        severity = DiagnosticSeverity.Warning,
                        title = context?.getString(R.string.endpoint_diag_probe_timeout) ?: "Endpoint probe timeout",
                        detail = "No ${target.path} response in ${PROBE_TIMEOUT_MS}ms",
                        operation = operation,
                        endpointRole = candidate.role,
                        configuredUrl = target.baseUrl,
                        requestUrl = target.requestUrl,
                        elapsedMs = clock() - startedAtMs,
                        suggestion = "Check network routing or firewall rules between this device and ${surface.diagnosticTarget()}.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                completeProbe(
                    candidate = candidate,
                    surface = surface,
                    generation = generation,
                    reachable = false,
                    detail = humanProbeFailure(e),
                ) {
                    Log.d(TAG, "probe failed role=${candidate.role} " +
                        "route=${target.baseUrl}: ${e.javaClass.simpleName}")
                    DiagnosticsLog.record(
                        category = DiagnosticCategory.Endpoint,
                        severity = DiagnosticSeverity.Warning,
                        title = context?.getString(R.string.endpoint_diag_probe_failed) ?: "Endpoint probe failed",
                        detail = humanProbeFailure(e),
                        operation = operation,
                        endpointRole = candidate.role,
                        configuredUrl = target.baseUrl,
                        requestUrl = target.requestUrl,
                        elapsedMs = clock() - startedAtMs,
                        suggestion = NetworkDiagnosticGuidance.forThrowable(e, surface.diagnosticTarget()),
                    )
                }
            }
        }
    }

    private fun executeProbeHttp(client: OkHttpClient, url: okhttp3.HttpUrl): ProbeHttpResult {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            ProbeHttpResult(
                code = response.code,
                successful = response.isSuccessful,
                bodyPreview = response.peekBody(4_096L).string(),
            )
        }
    }

    /** Official Desktop compatibility for Hermes versions predating `/api/health`. */
    private fun dashboardHealthNeedsLegacyFallback(result: ProbeHttpResult): Boolean =
        result.code == 404 ||
            (result.code == 401 && result.bodyPreview.contains("no_cookie", ignoreCase = true))

    /** Commit one physical probe only if it still belongs to the active cache generation. */
    private suspend fun completeProbe(
        candidate: EndpointCandidate,
        surface: EndpointSurface,
        generation: Long,
        reachable: Boolean,
        detail: String?,
        recordDiagnostic: () -> Unit,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        synchronized(probeStateLock) {
            if (generation != probeGeneration) {
                throw CancellationException("Endpoint probe invalidated")
            }
            recordDiagnostic()
            recordOutcome(candidate, surface, reachable, detail)
            val ttl = if (reachable) CACHE_TTL_MS else NEGATIVE_CACHE_TTL_MS
            probeCache[cacheKey(candidate, surface)] = CacheEntry(
                expiresAt = clock() + ttl,
                reachable = reachable,
            )
        }
        return reachable
    }

    /** Choose the standard Dashboard/Gateway surface first when advertised. */
    private fun probeTarget(
        candidate: EndpointCandidate,
        surface: EndpointSurface,
    ): ProbeTarget? {
        if (surface == EndpointSurface.Dashboard) {
            candidate.pluginProxyRoutesOrNull()?.dashboardBaseUrl?.let { base ->
                return ProbeTarget(
                    baseUrl = base,
                    requestUrl = "$base/api/health",
                    path = "/dashboard/api/health",
                    legacyFallbackRequestUrl = "$base/api/status",
                    legacyFallbackPath = "/dashboard/api/status",
                )
            }
            candidate.dashboard?.url?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { base ->
                return ProbeTarget(
                    baseUrl = base,
                    requestUrl = "$base/api/health",
                    path = "/api/health",
                    legacyFallbackRequestUrl = "$base/api/status",
                    legacyFallbackPath = "/api/status",
                )
            }
            return null
        }
        if (surface == EndpointSurface.Api) {
            candidate.pluginProxyRoutesOrNull()?.apiBaseUrl?.let { base ->
                return ProbeTarget(base, "$base/health", "/api/health")
            }
            candidate.api?.url?.let { base -> return ProbeTarget(base, "$base/health", "/health") }
            return null
        }
        if (surface == EndpointSurface.Relay) candidate.pluginProxyRoutesOrNull()?.let { proxy ->
            return ProbeTarget(
                baseUrl = proxy.relayHttpUrl,
                requestUrl = "${proxy.relayHttpUrl}/health",
                path = "/relay/health",
            )
        }
        if (surface == EndpointSurface.Relay) {
            return relayProbeTarget(candidate)
        }
        candidate.dashboard?.url
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?.let { base ->
                return ProbeTarget(
                    baseUrl = base,
                    requestUrl = "$base/api/health",
                    path = "/api/health",
                    legacyFallbackRequestUrl = "$base/api/status",
                    legacyFallbackPath = "/api/status",
                )
            }

        candidate.api?.url?.let { base ->
            return ProbeTarget(
                baseUrl = base,
                requestUrl = "$base/health",
                path = "/health",
            )
        }

        return relayProbeTarget(candidate)
    }

    private fun relayProbeTarget(candidate: EndpointCandidate): ProbeTarget? {
        candidate.relay?.url
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { relayUrl ->
                val endpoints = RelayEndpointContract.parseOrNull(relayUrl) ?: return null
                return ProbeTarget(
                    baseUrl = endpoints.webSocketUrl,
                    requestUrl = endpoints.healthUrl,
                    path = endpoints.healthUrl.toHttpUrlOrNull()?.encodedPath ?: return null,
                )
            }

        return null
    }

    internal fun probeRequestUrlForTest(
        candidate: EndpointCandidate,
        surface: EndpointSurface,
    ): String? = probeTarget(candidate, surface)?.requestUrl

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

    private fun EndpointSurface.diagnosticTarget(): String = when (this) {
        EndpointSurface.Standard -> "Dashboard or API server"
        EndpointSurface.Dashboard -> "Dashboard"
        EndpointSurface.Api -> "API server"
        EndpointSurface.Relay -> "Relay"
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
    fun markUnreachable(
        candidate: EndpointCandidate,
        surface: EndpointSurface = EndpointSurface.Standard,
    ) {
        synchronized(probeStateLock) {
            val key = cacheKey(candidate, surface)
            probeCache[key] = CacheEntry(
                expiresAt = clock() + NEGATIVE_CACHE_TTL_MS,
                reachable = false,
            )
            recordOutcome(
                candidate,
                surface,
                reachable = false,
                detail = "Network changed — assumed offline",
            )
        }
    }

    /**
     * Wipe the probe cache so the next resolve runs fresh probes. Called on
     * "the world changed" triggers — NetworkCallback events, manual "Probe
     * now", and [refreshActiveEndpoint][ConnectionManager.refreshActiveEndpoint]
     * with `clearProbeCache = true` — where a positive entry for a
     * just-died route must not outlive the handoff.
     */
    internal fun clearCache() {
        val staleProbes = synchronized(probeStateLock) {
            probeGeneration += 1L
            probeCache.clear()
            inFlightProbes.values.toList().also { inFlightProbes.clear() }
        }
        // An explicit re-probe must not join a request that began before the
        // invalidation signal. Cancellation is resolver-owned (not waiter-
        // owned), so ordinary lifecycle cancellation still leaves shared
        // probes alive for other callers. The generation check prevents a
        // late InterruptedIOException/response from publishing stale state.
        staleProbes.forEach { it.cancel() }
    }

    /** Test-only: snapshot the current cache for assertion purposes. */
    internal fun cacheSnapshot(): Map<String, Pair<Long, Boolean>> =
        probeCache.mapValues { (_, v) -> v.expiresAt to v.reachable }
}

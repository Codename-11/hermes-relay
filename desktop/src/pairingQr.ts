// Pairing-QR payload parser + priority-aware reachability resolver.
//
// Mirrors (in intent, not in code) the Kotlin side at:
//   app/src/main/kotlin/com/hermesandroid/relay/ui/components/QrPairingScanner.kt
//   app/src/main/kotlin/com/hermesandroid/relay/network/EndpointResolver.kt
//
// Wire schema is ADR 24 `hermes: 3`. Older `hermes: 1|2` payloads are still
// accepted — `payloadToCandidates` synthesizes a single priority-0 candidate
// from the top-level fields so callers can always iterate a non-empty array.
//
// HMAC signature verification is deliberately NOT implemented here — Android
// doesn't verify either (the phone has no way to fetch the server's secret
// in-band). The `sig` field is parsed + carried for future use.

import {
  type ApiEndpoint,
  type EndpointCandidate,
  type RelayEndpoint,
  apiUrl,
  isApiEndpointShape,
  isRelayEndpointShape,
} from './endpoint.js'

/**
 * Per-candidate HEAD `/health` probe timeout. Matches Kotlin
 * `EndpointResolver.PROBE_TIMEOUT_MS`. 4s was chosen over ADR 24's original
 * 2s because LTE hand-off + slow hotel Wi-Fi routinely blew past 2s on the
 * first packet and spuriously flagged real endpoints unreachable.
 */
export const PROBE_TIMEOUT_MS = 4_000

/**
 * In-memory probe-cache TTL. Matches Kotlin `EndpointResolver.CACHE_TTL_MS`.
 * Widened from ADR 24's 30s because NetworkCallback invalidation isn't
 * available on Node — we can't reactively flush on network change, so
 * a shorter TTL would burn extra probes without giving us anything back.
 */
export const PROBE_CACHE_TTL_MS = 60_000

/**
 * Raw relay block as it appears on the wire. `ttl_seconds` / `grants` /
 * `transport_hint` / `code` are all optional so v1 QRs with only `url`
 * still decode.
 *
 * `code` is not currently used by the CLI (the top-level `key` field
 * carries the pairing code) but is preserved so we can pivot later.
 */
export interface PairingRelay {
  url: string
  code?: string
  ttl_seconds?: number
  grants?: Record<string, number>
  transport_hint?: string
}

/**
 * Parsed pairing payload. Mirrors Kotlin's `HermesPairingPayload`. Fields
 * are kept in wire-case (snake where the server emits snake) for the
 * `endpoints` array only — everything else is a trivial rename.
 *
 * The `sig` field is parsed + carried but NOT verified. See
 * `verifyPairingSignature` for the (TODO) verification stub.
 */
export interface PairingPayload {
  hermes: number
  host: string
  port: number
  key: string
  tls: boolean
  relay?: PairingRelay
  endpoints?: EndpointCandidate[]
  sig?: string
}

/**
 * Parse an `endpoints[i]` object from the wire. Returns null on
 * malformed input so the outer parser can silently skip bad records
 * instead of rejecting the whole payload.
 *
 * The input comes from `JSON.parse` so all fields are `unknown`. We
 * validate structure via the `isApiEndpointShape` / `isRelayEndpointShape`
 * guards and then coerce into our TS shape.
 */
function parseCandidate(v: unknown): EndpointCandidate | null {
  if (typeof v !== 'object' || v === null) return null
  const o = v as Record<string, unknown>
  if (typeof o.role !== 'string') return null
  const priority = typeof o.priority === 'number' ? o.priority : 0
  if (!isApiEndpointShape(o.api)) return null
  if (!isRelayEndpointShape(o.relay)) return null
  const api: ApiEndpoint = {
    host: o.api.host,
    port: o.api.port,
    tls: typeof o.api.tls === 'boolean' ? o.api.tls : false,
  }
  const relay: RelayEndpoint = {
    url: o.relay.url,
    ...(o.relay.transport_hint !== undefined ? { transportHint: o.relay.transport_hint } : {}),
  }
  return { role: o.role, priority, api, relay }
}

/**
 * Try to parse a pairing-QR payload. Accepts either the raw compact-JSON
 * string emitted by `plugin/pair.py:build_payload` OR a base64-encoded
 * wrapper of the same (some terminals auto-wrap pasted content, and some
 * downstream tools emit base64 for transport).
 *
 * Throws with a friendly message on any failure — the CLI surfaces this
 * directly to the user.
 */
export function decodePairingPayload(raw: string): PairingPayload {
  const trimmed = raw.trim()
  if (trimmed.length === 0) {
    throw new Error('empty pairing payload')
  }

  // Try raw JSON first — this is the canonical wire form.
  let text = trimmed
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    // Fallback: maybe it's base64-wrapped. Only attempt if the content
    // looks base64-shaped; guards against hex / other encodings masquerading.
    if (!/^[A-Za-z0-9+/=_-]+$/.test(trimmed)) {
      throw new Error('pairing payload is neither valid JSON nor base64')
    }
    try {
      // Accept both standard and URL-safe base64 variants.
      const normalized = trimmed.replaceAll('-', '+').replaceAll('_', '/')
      text = Buffer.from(normalized, 'base64').toString('utf8')
      parsed = JSON.parse(text)
    } catch {
      throw new Error('pairing payload failed base64→JSON decode')
    }
  }

  if (typeof parsed !== 'object' || parsed === null) {
    throw new Error('pairing payload is not a JSON object')
  }
  const o = parsed as Record<string, unknown>

  const hermes = typeof o.hermes === 'number' ? o.hermes : 1
  if (hermes < 1) {
    throw new Error(`unsupported pairing schema version: ${hermes}`)
  }
  if (typeof o.host !== 'string' || o.host.length === 0) {
    throw new Error('pairing payload missing `host`')
  }
  if (typeof o.key !== 'string') {
    throw new Error('pairing payload missing `key`')
  }

  const payload: PairingPayload = {
    hermes,
    host: o.host,
    port: typeof o.port === 'number' ? o.port : 8642,
    key: o.key,
    tls: typeof o.tls === 'boolean' ? o.tls : false,
  }

  if (typeof o.relay === 'object' && o.relay !== null) {
    const r = o.relay as Record<string, unknown>
    if (typeof r.url === 'string') {
      const relay: PairingRelay = { url: r.url }
      if (typeof r.code === 'string') relay.code = r.code
      if (typeof r.ttl_seconds === 'number') relay.ttl_seconds = r.ttl_seconds
      if (typeof r.transport_hint === 'string') relay.transport_hint = r.transport_hint
      if (typeof r.grants === 'object' && r.grants !== null) {
        const grants: Record<string, number> = {}
        for (const [k, v] of Object.entries(r.grants as Record<string, unknown>)) {
          if (typeof v === 'number') grants[k] = v
        }
        relay.grants = grants
      }
      payload.relay = relay
    }
  }

  if (Array.isArray(o.endpoints)) {
    const candidates: EndpointCandidate[] = []
    for (const entry of o.endpoints) {
      const c = parseCandidate(entry)
      if (c !== null) candidates.push(c)
    }
    if (candidates.length > 0) payload.endpoints = candidates
  }

  if (typeof o.sig === 'string') {
    payload.sig = o.sig
  }

  return payload
}

/**
 * Heuristic — is this host a Tailscale endpoint? Mirrors Kotlin's
 * `synthesizeLegacyEndpoint` detector: `.ts.net` suffix or `100.`
 * IPv4 prefix (broader than CGNAT's 100.64.0.0/10 but keeps us
 * tolerant of operator labeling).
 */
function looksLikeTailscale(host: string): boolean {
  return host.toLowerCase().endsWith('.ts.net') || host.startsWith('100.')
}

/**
 * Resolve a parsed pairing payload into a non-empty ordered list of
 * candidates. For `hermes: 3` payloads the `endpoints` array rides
 * through verbatim; for `hermes: 1|2` a single priority-0 candidate is
 * synthesized from the top-level fields.
 *
 * Mirrors Kotlin's `parseHermesPairingQr` + `synthesizeLegacyEndpoint`
 * path — callers downstream can always assume at least one candidate.
 */
export function payloadToCandidates(p: PairingPayload): EndpointCandidate[] {
  if (p.endpoints && p.endpoints.length > 0) {
    return p.endpoints
  }
  const role = looksLikeTailscale(p.host) ? 'tailscale' : 'lan'
  const relay: RelayEndpoint = {
    url: p.relay?.url ?? '',
    ...(p.relay?.transport_hint !== undefined ? { transportHint: p.relay.transport_hint } : {}),
  }
  return [
    {
      role,
      priority: 0,
      api: { host: p.host, port: p.port, tls: p.tls },
      relay,
    },
  ]
}

/**
 * Stable cache key for a candidate: `"<role>|<api.host>:<api.port>"`.
 * Mirrors Kotlin's `EndpointResolver.cacheKey`. Role preserves case (HMAC
 * canonicalization) but host is lowercased — two roles pointing at the
 * same host:port share reachability state.
 */
function cacheKey(c: EndpointCandidate): string {
  return `${c.role}|${c.api.host.toLowerCase()}:${c.api.port}`
}

interface CacheEntry {
  expiresAt: number
  reachable: boolean
}

/**
 * In-memory probe cache. Shared across all resolver invocations in the
 * same Node process. Keyed by `cacheKey`, TTL'd to `PROBE_CACHE_TTL_MS`.
 *
 * Not exported — callers should go through `probeCandidatesByPriority`.
 * Tests that need to flush should spawn a fresh process.
 */
const probeCache = new Map<string, CacheEntry>()

/** Probe a single candidate against `{api.url}/health`. Any 2xx wins. */
export interface ProbeResult {
  candidate: EndpointCandidate
  reachable: boolean
  elapsedMs: number
  error?: string
}

/**
 * Fire one HEAD-equivalent probe. We use GET (not HEAD) because not every
 * relay flavor answers HEAD — Tailscale Serve in particular has been
 * spotty. Any 2xx counts as reachable.
 *
 * Uses the global Node `fetch` (Node ≥21) with the passed AbortSignal so
 * callers can cancel losers from the priority-group race.
 */
export async function probeCandidate(
  c: EndpointCandidate,
  signal: AbortSignal,
): Promise<ProbeResult> {
  const started = Date.now()
  const url = `${apiUrl(c.api)}/health`
  try {
    const resp = await fetch(url, {
      method: 'GET',
      signal,
      headers: { Accept: '*/*' },
    })
    return {
      candidate: c,
      reachable: resp.ok,
      elapsedMs: Date.now() - started,
    }
  } catch (err) {
    return {
      candidate: c,
      reachable: false,
      elapsedMs: Date.now() - started,
      error: err instanceof Error ? err.message : String(err),
    }
  }
}

/**
 * Resolve the highest-priority reachable candidate from `candidates`.
 *
 * Algorithm (matches ADR 24 / Kotlin `EndpointResolver.resolve`):
 *  1. Group by `priority` ascending (0 = highest).
 *  2. Within each tier, race all candidates in parallel. First probe to
 *     come back reachable wins; an `AbortController` cancels the losers.
 *  3. If the entire tier is unreachable, fall through to the next tier.
 *  4. Throws if nothing across any tier is reachable — callers decide
 *     whether to fall back to a stored URL or surface the error.
 *
 * A 60-second in-memory cache short-circuits re-probes within the TTL.
 * The cache is process-local; the CLI is typically one-shot so this
 * mostly helps the REPL case where the user cycles through sessions.
 */
export async function probeCandidatesByPriority(
  candidates: EndpointCandidate[],
): Promise<EndpointCandidate> {
  if (candidates.length === 0) {
    throw new Error('no endpoint candidates to probe')
  }

  const now = Date.now()
  // Bucket by priority ascending. Sort after the groupBy so cache-hit
  // fast-path and the live race both see tiers in the same order.
  const groups = new Map<number, EndpointCandidate[]>()
  for (const c of candidates) {
    const bucket = groups.get(c.priority) ?? []
    bucket.push(c)
    groups.set(c.priority, bucket)
  }
  const priorities = [...groups.keys()].sort((a, b) => a - b)

  for (const priority of priorities) {
    const group = groups.get(priority) ?? []

    // Fast path: any cached-reachable candidate wins without touching the
    // network. Matches Kotlin's pre-race cache scan.
    for (const c of group) {
      const cached = probeCache.get(cacheKey(c))
      if (cached && cached.expiresAt > now && cached.reachable) {
        return c
      }
    }

    // Live race. Each probe gets its own AbortSignal linked to a shared
    // controller so a winner cancels outstanding losers. Per-probe
    // timeout via AbortSignal.timeout — composed with the group controller
    // so either can trigger abort.
    const groupController = new AbortController()
    const probes = group.map(async (c) => {
      const timeout = AbortSignal.timeout(PROBE_TIMEOUT_MS)
      // AbortSignal.any is available on Node ≥20 for combining signals.
      const signal = AbortSignal.any([groupController.signal, timeout])
      const result = await probeCandidate(c, signal)
      probeCache.set(cacheKey(c), {
        expiresAt: Date.now() + PROBE_CACHE_TTL_MS,
        reachable: result.reachable,
      })
      if (!result.reachable) {
        throw new Error(result.error ?? `unreachable: ${c.role}`)
      }
      return c
    })

    try {
      // Promise.any → first fulfilled wins. Losers continue in the
      // background until their probe resolves, but their fetches are
      // aborted via the controller below.
      const winner = await Promise.any(probes)
      groupController.abort()
      return winner
    } catch {
      // All probes in this tier rejected (unreachable). Fall through to
      // the next priority tier. Make sure we abort any in-flight probes
      // so the Node event loop doesn't hold the process open.
      groupController.abort()
      continue
    }
  }

  throw new Error(
    `no reachable endpoint across ${candidates.length} candidate(s) — ` +
      'check relay is running and host is routable from this machine',
  )
}

/**
 * HMAC-SHA256 verification against the server's pairing secret.
 *
 * TODO(hmac): Android currently does not verify either — the phone has
 * no in-band channel to fetch the server-side secret at
 * `~/.hermes/hermes-relay-qr-secret`. When that changes (e.g. a
 * provisioning endpoint ships), this stub becomes the verification
 * implementation: reconstruct the canonical form via
 * `JSON.stringify(payload_without_sig)` with sorted keys + compact
 * separators, HMAC-SHA256 against the fetched secret, compare to
 * `payload.sig` via constant-time equality.
 *
 * Until then this is an intentional no-op that returns `true` so the
 * CLI matches the phone's trust model (parse + carry, don't verify).
 */
export function verifyPairingSignature(_payload: PairingPayload): boolean {
  // TODO(hmac): secret not available client-side yet — mirror Android's
  // parse-but-don't-verify posture. See ADR 24, section on signature
  // handling.
  return true
}

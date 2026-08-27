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
  type EndpointCandidate,
  type RelayEndpoint,
  apiUrl,
  isApiEndpointShape,
  isRelayEndpointShape,
} from './endpoint.js'
import { describeTransportSecurity } from './transportSecurity.js'
import https from 'node:https'
import { certificateDerToPem, comparePins, extractSpkiSha256 } from './certPin.js'

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
 * `code` is the relay one-shot pairing code. The top-level `key` field is
 * the Hermes API bearer for direct HTTP chat and must stay separate.
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

const PAIRING_CODE_RE = /^[A-Z0-9]{6}$/

function normalizeBase64Payload(raw: string): string {
  return raw.replaceAll('-', '+').replaceAll('_', '/')
}

function extractInviteCandidate(raw: string): string | null {
  const trimmed = raw.trim()
  if (!trimmed) return null
  const direct = trimmed.match(/^hermes-relay:\/\/pair(?:\/([^?\s#]+))?(?:\?([^\s#]+))?/i)
  const embedded = direct ?? trimmed.match(/hermes-relay:\/\/pair(?:\/([^?\s#]+))?(?:\?([^\s#]+))?/i)
  if (!embedded) return null
  const candidate = embedded[0]
  try {
    const url = new URL(candidate)
    const fromQuery = url.searchParams.get('payload') ?? url.searchParams.get('p')
    if (fromQuery && fromQuery.trim()) return fromQuery.trim()
    const fromPath = url.pathname.replace(/^\/+/, '')
    if (fromPath) return decodeURIComponent(fromPath)
  } catch {
    // Fall through to regex query extraction for partial clipboard lines.
  }
  const query = embedded[2]
  if (query) {
    const params = new URLSearchParams(query)
    const fromQuery = params.get('payload') ?? params.get('p')
    if (fromQuery && fromQuery.trim()) return fromQuery.trim()
  }
  const pathPayload = embedded[1]
  return pathPayload ? decodeURIComponent(pathPayload) : null
}

/**
 * Return the raw JSON/base64 payload from either a QR payload or a
 * paste-friendly ``hermes-relay://pair?payload=...`` invite URL.
 */
export function unwrapPairingPayload(raw: string): string {
  const trimmed = raw.trim()
  return extractInviteCandidate(trimmed) ?? trimmed
}

/**
 * The relay one-shot code lives in ``relay.code``. Top-level ``key`` is the
 * Hermes API bearer for direct HTTP chat and must never be used as a relay
 * pairing code.
 */
export function relayPairingCodeFromPayload(payload: PairingPayload): string {
  const code = payload.relay?.code?.trim().toUpperCase() ?? ''
  if (!code) {
    throw new Error(
      'pairing invite has no relay.code. Start the relay on the server and mint a new invite.'
    )
  }
  if (!PAIRING_CODE_RE.test(code)) {
    throw new Error('pairing invite has an invalid relay.code; expected 6 chars of A-Z or 0-9')
  }
  return code
}

export function payloadToRelayCandidates(payload: PairingPayload): EndpointCandidate[] {
  const candidates = payloadToCandidates(payload).filter((candidate) =>
    typeof candidate.relay.url === 'string' && candidate.relay.url.trim().length > 0
  )
  if (candidates.length === 0) {
    throw new Error(
      'pairing invite has no relay URL. Start the relay on the server and mint a new invite.'
    )
  }
  return candidates
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
  if (typeof o.broker === 'object' && o.broker !== null && typeof o.proxy === 'object' && o.proxy !== null) {
    if (!['outbound_broker', 'broker', 'relay_broker'].includes(o.role.toLowerCase())) return null
    const broker = o.broker as Record<string, unknown>
    const proxy = o.proxy as Record<string, unknown>
    // The v1 pairing candidate does not require a redundant protocol_version;
    // the fixed /v1/connect path supplies that version boundary. Accept an
    // explicit 1 for compatibility, but reject any other advertised version.
    const supportedVersion = broker.protocol_version === undefined || broker.protocol_version === 1
    if (supportedVersion && typeof broker.url === 'string' && typeof broker.host_id === 'string' && /^[A-Za-z0-9_-]{22}$/.test(broker.host_id) && (broker.credential_kind === 'bootstrap' || broker.credential_kind === 'route') && typeof broker.token === 'string' && /^[A-Za-z0-9_-]{43}$/.test(broker.token) && typeof proxy.url === 'string' && typeof proxy.pin_sha256 === 'string' && typeof proxy.cert_der === 'string') {
      try {
        const base = new URL(proxy.url)
        const brokerUrl = new URL(broker.url)
        if (brokerUrl.protocol !== 'wss:' || brokerUrl.pathname !== '/v1/connect' || base.protocol !== 'https:') return null
        return {
          role: o.role, priority,
          api: { host: base.hostname, port: Number(base.port || 443), tls: true },
          relay: { url: `wss://${base.host}/relay/ws`, transportHint: 'wss' },
          proxy: { url: proxy.url, pinSha256: proxy.pin_sha256, certificateDerBase64: proxy.cert_der, surfaces: ['relay'] },
          broker: { url: broker.url, hostId: broker.host_id, credentialKind: broker.credential_kind, token: broker.token, ...((typeof broker.expires_at === 'string' || typeof broker.expires_at === 'number' || broker.expires_at === null) ? { expiresAt: broker.expires_at } : {}) },
          ...(typeof o.security === 'string' ? { security: o.security } : {}),
          ...(typeof o.recommended === 'boolean' ? { recommended: o.recommended } : {}),
          ...(typeof o.experimental === 'boolean' ? { experimental: o.experimental } : {}),
        }
      } catch { return null }
    }
  }
  if (typeof o.proxy === 'object' && o.proxy !== null) {
    const proxy = o.proxy as Record<string, unknown>
    if (typeof proxy.url === 'string' && typeof proxy.pin_sha256 === 'string' && typeof proxy.cert_der === 'string') {
      try {
        const base = new URL(proxy.url)
        if (base.protocol !== 'https:' || !base.hostname) return null
        const port = Number(base.port || 443)
        return {
          role: o.role,
          priority,
          api: { host: base.hostname, port, tls: true },
          relay: {
            url: `wss://${base.host}/relay/ws`,
            transportHint: 'wss',
          },
          proxy: {
            url: proxy.url,
            pinSha256: proxy.pin_sha256,
            certificateDerBase64: proxy.cert_der,
            ...(typeof proxy.transport_hint === 'string'
              ? { transportHint: proxy.transport_hint }
              : {}),
            ...(Array.isArray(proxy.surfaces)
              ? { surfaces: proxy.surfaces.filter((surface): surface is string => typeof surface === 'string') }
              : {}),
          },
          ...(typeof o.security === 'string' ? { security: o.security } : {}),
          ...(typeof o.recommended === 'boolean' ? { recommended: o.recommended } : {}),
          ...(typeof o.experimental === 'boolean' ? { experimental: o.experimental } : {}),
        }
      } catch {
        return null
      }
    }
  }
  if (!isRelayEndpointShape(o.relay)) return null
  const relay: RelayEndpoint = {
    url: o.relay.url,
    ...(o.relay.transport_hint !== undefined ? { transportHint: o.relay.transport_hint } : {}),
  }
  const candidate: EndpointCandidate = { role: o.role, priority, relay }
  if (isApiEndpointShape(o.api)) {
    candidate.api = {
      host: o.api.host,
      port: o.api.port,
      tls: typeof o.api.tls === 'boolean' ? o.api.tls : false,
    }
  }
  if (typeof o.dashboard === 'object' && o.dashboard !== null) {
    const dashboard = o.dashboard as Record<string, unknown>
    if (typeof dashboard.url === 'string' && dashboard.url.trim().length > 0) {
      candidate.dashboard = { url: dashboard.url }
    }
  }
  if (typeof o.security === 'string') candidate.security = o.security
  if (typeof o.recommended === 'boolean') candidate.recommended = o.recommended
  if (typeof o.experimental === 'boolean') candidate.experimental = o.experimental
  return candidate
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
  const trimmed = unwrapPairingPayload(raw)
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
      const normalized = normalizeBase64Payload(trimmed)
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
  const hasExplicitRelay =
    (typeof o.relay === 'object' && o.relay !== null &&
      typeof (o.relay as Record<string, unknown>).url === 'string') ||
    (Array.isArray(o.endpoints) && o.endpoints.some((entry) => {
      if (typeof entry !== 'object' || entry === null) return false
      const candidate = entry as Record<string, unknown>
      return isRelayEndpointShape(candidate.relay) ||
        (typeof candidate.proxy === 'object' && candidate.proxy !== null)
    }))
  if ((typeof o.host !== 'string' || o.host.length === 0) && !hasExplicitRelay) {
    throw new Error('pairing payload missing `host`')
  }
  if (typeof o.key !== 'string' && !hasExplicitRelay) {
    throw new Error('pairing payload missing `key`')
  }

  const payload: PairingPayload = {
    hermes,
    host: typeof o.host === 'string' ? o.host : '',
    port: typeof o.port === 'number' ? o.port : 8642,
    key: typeof o.key === 'string' ? o.key : '',
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
  if (!p.host.trim() && !p.relay?.url?.trim()) return []
  const relayHost = (() => {
    try { return new URL(p.relay?.url ?? '').hostname } catch { return '' }
  })()
  const identityHost = p.host.trim() || relayHost
  const role = looksLikeTailscale(identityHost) ? 'tailscale' : 'lan'
  const relay: RelayEndpoint = {
    url: p.relay?.url ?? '',
    ...(p.relay?.transport_hint !== undefined ? { transportHint: p.relay.transport_hint } : {}),
  }
  return [{
    role,
    priority: 0,
    ...(p.host.trim() ? { api: { host: p.host, port: p.port, tls: p.tls } } : {}),
    relay,
  }]
}

/**
 * Stable cache key for a candidate: `"<role>|<api.host>:<api.port>"`.
 * Mirrors Kotlin's `EndpointResolver.cacheKey`. Role preserves case (HMAC
 * canonicalization) but host is lowercased — two roles pointing at the
 * same host:port share reachability state.
 */
function cacheKey(c: EndpointCandidate): string {
  const route = c.api
    ? `${c.api.host.toLowerCase()}:${c.api.port}`
    : c.dashboard?.url?.toLowerCase() ?? c.relay.url.toLowerCase()
  return `${c.role}|${route}`
}

function relayHealthUrl(raw: string): string {
  const parsed = new URL(raw)
  parsed.protocol = parsed.protocol === 'wss:' ? 'https:' : 'http:'
  const path = parsed.pathname.replace(/\/+$/, '')
  parsed.pathname = path.endsWith('/ws') ? `${path.slice(0, -3)}/health` : `${path}/health`
  parsed.search = ''
  parsed.hash = ''
  return parsed.toString()
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

/** Progress event emitted by `probeCandidatesByPriority` so callers can show
 * per-endpoint feedback during the otherwise-silent reachability race. */
export interface ProbeProgress {
  phase: 'probing' | 'result' | 'cached'
  candidate: EndpointCandidate
  /** 1-based position in the full candidate list. */
  index: number
  total: number
  reachable?: boolean
  elapsedMs?: number
  error?: string
}

export interface ProbeOptions {
  onProbe?: (ev: ProbeProgress) => void
  /** Existing Relay session required by the native proxy health route. */
  sessionToken?: string
}

export function isValidPinnedProxyCandidate(candidate: EndpointCandidate): boolean {
  return candidate.role.toLowerCase() === 'plugin_proxy' &&
    candidate.security === 'pinned_tls' && candidate.recommended === true &&
    candidate.proxy?.url.startsWith('https://') === true &&
    /^sha256\/[A-Za-z0-9+/]{43}=$/.test(candidate.proxy.pinSha256) &&
    typeof candidate.proxy.certificateDerBase64 === 'string' &&
    candidate.proxy.certificateDerBase64.length <= 8_192 &&
    /^[A-Za-z0-9+/]+={0,2}$/.test(candidate.proxy.certificateDerBase64)
}

function pinnedCertificate(candidate: EndpointCandidate): Buffer | null {
  try {
    const encoded = candidate.proxy?.certificateDerBase64
    if (!encoded || encoded.length > 8_192 || !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) return null
    const certificate = Buffer.from(encoded, 'base64')
    return certificate.length > 0 && comparePins(candidate.proxy!.pinSha256, extractSpkiSha256(certificate))
      ? certificate
      : null
  } catch {
    return null
  }
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
  sessionToken?: string,
): Promise<ProbeResult> {
  const started = Date.now()
  if (c.broker) {
    try {
      const broker = new URL(c.broker.url)
      const health = new URL('/health', `${broker.protocol === 'wss:' ? 'https:' : 'http:'}//${broker.host}`)
      // Use the runtime fetch stack for the public broker health check. The
      // packaged Bun runtime applies NODE_USE_SYSTEM_CA to fetch/WebSocket;
      // node:https can use a different compatibility trust path and falsely
      // reject the same Windows-trusted broker that the live WSS route opens.
      // No Reach credential is sent, so this cannot consume the bootstrap.
      const response = await fetch(health, { method: 'GET', signal, headers: { Accept: '*/*' } })
      const reachable = response.ok
      return { candidate: c, reachable, elapsedMs: Date.now() - started, ...(reachable ? {} : { error: 'Hermes Reach broker health check failed' }) }
    } catch (error) {
      return { candidate: c, reachable: false, elapsedMs: Date.now() - started, error: error instanceof Error ? error.message : String(error) }
    }
  }
  // The secure proxy has one pinned origin with isolated service namespaces.
  // Reachability remains anchored to its non-sensitive Relay health route.
  const url = isValidPinnedProxyCandidate(c)
    ? `${c.proxy!.url.replace(/\/+$/, '')}/relay/health`
    : c.api ? `${apiUrl(c.api)}/health` : relayHealthUrl(c.relay.url)
  try {
    if (isValidPinnedProxyCandidate(c)) {
      const url = `${c.proxy!.url.replace(/\/+$/, '')}/relay/health`
      const certificate = pinnedCertificate(c)
      if (!certificate) {
        return { candidate: c, reachable: false, elapsedMs: Date.now() - started,
          error: 'paired certificate pin did not match' }
      }
      const reachable = await new Promise<boolean>((resolve, reject) => {
        const request = https.get(url, {
          rejectUnauthorized: true,
          ca: certificateDerToPem(certificate),
          signal,
          headers: sessionToken ? { 'X-Hermes-Relay-Session': sessionToken } : undefined
        }, response => {
          response.resume()
          resolve((response.statusCode ?? 500) >= 200 && (response.statusCode ?? 500) < 300)
        })
        request.once('error', reject)
      })
      return { candidate: c, reachable, elapsedMs: Date.now() - started }
    }
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
  opts: ProbeOptions = {},
): Promise<EndpointCandidate> {
  if (candidates.length === 0) {
    throw new Error('no endpoint candidates to probe')
  }

  const total = candidates.length
  const indexOf = new Map<EndpointCandidate, number>(candidates.map((c, i) => [c, i + 1]))
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
  const orderedGroups = [
    ...priorities.map(priority => groups.get(priority) ?? []).filter(group => group.some(candidate => candidate.experimental !== true && candidate.role.toLowerCase() !== 'outbound_broker'))
      .map(group => group.filter(candidate => candidate.experimental !== true && candidate.role.toLowerCase() !== 'outbound_broker')),
    ...priorities.map(priority => groups.get(priority) ?? []).map(group => group.filter(candidate => candidate.experimental === true || candidate.role.toLowerCase() === 'outbound_broker')).filter(group => group.length > 0),
  ]

  for (const group of orderedGroups) {

    // Fast path: any cached-reachable candidate wins without touching the
    // network. Matches Kotlin's pre-race cache scan.
    for (const c of group) {
      const cached = probeCache.get(cacheKey(c))
      if (cached && cached.expiresAt > now && cached.reachable) {
        opts.onProbe?.({ phase: 'cached', candidate: c, index: indexOf.get(c) ?? 0, total, reachable: true })
        return c
      }
    }

    // Live race. Each probe gets its own AbortSignal linked to a shared
    // controller so a winner cancels outstanding losers. Per-probe
    // timeout via AbortSignal.timeout — composed with the group controller
    // so either can trigger abort.
    const groupController = new AbortController()
    const probes = group.map(async (c) => {
      const idx = indexOf.get(c) ?? 0
      opts.onProbe?.({ phase: 'probing', candidate: c, index: idx, total })
      const timeout = AbortSignal.timeout(PROBE_TIMEOUT_MS)
      // AbortSignal.any is available on Node ≥20 for combining signals.
      const signal = AbortSignal.any([groupController.signal, timeout])
      const result = await probeCandidate(c, signal, opts.sessionToken)
      probeCache.set(cacheKey(c), {
        expiresAt: Date.now() + PROBE_CACHE_TTL_MS,
        reachable: result.reachable,
      })
      opts.onProbe?.({
        phase: 'result',
        candidate: c,
        index: idx,
        total,
        reachable: result.reachable,
        elapsedMs: result.elapsedMs,
        ...(result.error !== undefined ? { error: result.error } : {}),
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

/** Keep every fallback, but move encrypted routes ahead of plain ws:// by
 * default. Original priority and array order remain the tie breakers within
 * each security tier; callers can skip this helper for invite-order routing. */
export function secureFirstCandidates(candidates: EndpointCandidate[]): EndpointCandidate[] {
  return candidates
    .map((candidate, index) => ({ candidate, index }))
    .sort((left, right) => {
      const leftSecure = isValidPinnedProxyCandidate(left.candidate) || describeTransportSecurity(left.candidate.relay.url, left.candidate.role).encrypted
      const rightSecure = isValidPinnedProxyCandidate(right.candidate) || describeTransportSecurity(right.candidate.relay.url, right.candidate.role).encrypted
      const leftExperimental = left.candidate.experimental === true || left.candidate.role.toLowerCase() === 'outbound_broker'
      const rightExperimental = right.candidate.experimental === true || right.candidate.role.toLowerCase() === 'outbound_broker'
      return Number(leftExperimental) - Number(rightExperimental) ||
        Number(rightSecure) - Number(leftSecure) ||
        left.candidate.priority - right.candidate.priority || left.index - right.index
    })
    .map(({ candidate }, priority) => ({ ...candidate, priority }))
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

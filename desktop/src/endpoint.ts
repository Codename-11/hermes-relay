// Multi-endpoint pairing primitives — pure types + tiny helpers (ADR 24).
//
// Mirrors `app/src/main/kotlin/com/hermesandroid/relay/data/Endpoint.kt`.
// The Kotlin data classes over there are the source of truth — any semantic
// change must go through ADR 24 and land in both surfaces simultaneously.
//
// Intentionally pure: no I/O, no Node globals beyond TS. Probe semantics +
// the parser live in `pairingQr.ts`; this file is safe to import from
// anywhere, including tests that have no network access.

/**
 * Known endpoint roles. The wire format treats `role` as an open string —
 * operators can emit arbitrary labels — so anything not in this set
 * normalizes to `'custom'` for display. The **raw** string is preserved
 * verbatim in `EndpointCandidate.role` for HMAC canonicalization; this
 * narrowed type is display-only.
 */
export type EndpointRole = 'lan' | 'tailscale' | 'public' | 'custom'

/**
 * API-server half of an endpoint candidate. Points at the HTTP/SSE target
 * used for `/v1/runs`, `/v1/chat/completions`, `/api/sessions/*`, etc.
 *
 * `tls` defaults to `false` on the wire so a legacy v1/v2 payload without
 * the field synthesizes cleanly (see `payloadToCandidates`).
 */
export interface ApiEndpoint {
  host: string
  port: number
  tls: boolean
}

/** Build the full API URL — mirrors Kotlin's `ApiEndpoint.url` getter. */
export function apiUrl(e: ApiEndpoint): string {
  return `${e.tls ? 'https' : 'http'}://${e.host}:${e.port}`
}

/**
 * Relay-server half of an endpoint candidate. Carries only the URL and the
 * transport hint — the pairing `code`, `ttl_seconds`, and `grants` are
 * per-pair artifacts that stay on the top-level pairing payload.
 */
export interface RelayEndpoint {
  url: string
  /** `"wss"` | `"ws"` | undefined. UI hint only — `url` scheme is authoritative. */
  transportHint?: string
}

/**
 * One entry in a v3 pairing payload's `endpoints` array. `priority` is
 * strict — `0 = highest`. Reachability is a tiebreaker **within** a
 * priority tier; it never promotes a lower tier over a higher one.
 */
export interface EndpointCandidate {
  /** Raw role string from the wire — preserved verbatim for HMAC canonicalization. */
  role: string
  priority: number
  api: ApiEndpoint
  relay: RelayEndpoint
}

/**
 * Narrow a raw role string to one of the known UI roles. Case-insensitive.
 * Anything outside the built-in set (operator-defined roles like
 * `"wireguard"`, `"zerotier"`, `"netbird-eu"`) folds to `'custom'` so
 * callers can switch on a closed set.
 *
 * This is a display-time transform. The raw role on the candidate stays
 * in its emitted form — do NOT mutate `EndpointCandidate.role` based on
 * this narrowing or the HMAC canonicalization breaks.
 */
export function parseRawRole(raw: string): EndpointRole {
  switch (raw.toLowerCase()) {
    case 'lan':
      return 'lan'
    case 'tailscale':
      return 'tailscale'
    case 'public':
      return 'public'
    default:
      return 'custom'
  }
}

/**
 * True when `role` maps to a built-in styled role. Mirrors Kotlin's
 * `EndpointCandidate.isKnownRole()`.
 */
export function isKnownRole(role: string): boolean {
  return parseRawRole(role) !== 'custom'
}

/**
 * Human-readable label for the role. Mirrors Kotlin's `displayLabel()`.
 * Unknown roles render as `"Custom VPN (<raw>)"` so the operator sees
 * exactly what they labeled it.
 */
export function displayLabel(role: string): string {
  switch (parseRawRole(role)) {
    case 'lan':
      return 'LAN'
    case 'tailscale':
      return 'Tailscale'
    case 'public':
      return 'Public'
    default:
      return `Custom VPN (${role})`
  }
}

/**
 * Type guard — is this value shaped like an `ApiEndpoint`? Used by the
 * pairing parser to filter malformed candidates without throwing.
 */
export function isApiEndpointShape(v: unknown): v is ApiEndpoint {
  if (typeof v !== 'object' || v === null) return false
  const o = v as Record<string, unknown>
  return (
    typeof o.host === 'string' &&
    typeof o.port === 'number' &&
    (typeof o.tls === 'boolean' || o.tls === undefined)
  )
}

/**
 * Type guard — is this value shaped like a `RelayEndpoint`? `transport_hint`
 * is the wire name; `transportHint` is the JS-side name. The parser handles
 * the rename.
 */
export function isRelayEndpointShape(v: unknown): v is { url: string; transport_hint?: string } {
  if (typeof v !== 'object' || v === null) return false
  const o = v as Record<string, unknown>
  return (
    typeof o.url === 'string' &&
    (typeof o.transport_hint === 'string' || o.transport_hint === undefined)
  )
}

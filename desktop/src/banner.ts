// Contextual connect banner — mirrors the Android "Route / Plain / Secure /
// LAN / Tailscale / Public" vocabulary landed in DEVLOG 2026-04-22. The
// banner lands ONCE on connect (chat + shell both use it) so the user sees
// at a glance which network path they're on and whether it's TLS-encrypted.
//
// Styled as a Relay cockpit status strip — the same `key: value` mono
// readout the tray window and desktop mockup use:
//
//   ▸ relay: connected · tailscale · secure · server 1.6.3
//
// Inputs are all optional — on older relays that don't send `transport_hint`
// or when we don't know the endpoint role, we degrade to a neutral "plain"
// or just the server version.
//
// Build (plain text) — callers pass `noColor` when the user ran `--no-color`
// so the strip degrades exactly like the rest of the CLI. The strip is written
// to stderr (chat + shell both do), so colour is decided by *that* stream:
// `chat 2>relay.log` stays escape-free even when stdout is a terminal, and
// piping stdout does not strip colour from a terminal stderr.

import { SYMBOLS, theme as makeTheme, type ColorStream } from './lib/theme.js'
import type { AuthMeta } from './transport/RelayTransport.js'

export type EndpointRole = 'lan' | 'tailscale' | 'public' | 'custom'

/**
 * Normalize the server-reported endpoint role string. Returns null if we
 * don't recognise the input — caller decides whether to fall back to url
 * scheme or leave the role blank.
 */
export function parseRole(raw: string | null | undefined): EndpointRole | null {
  if (!raw) {
    return null
  }
  const lower = raw.toLowerCase()
  if (lower === 'lan' || lower === 'tailscale' || lower === 'public' || lower === 'custom') {
    return lower
  }
  return null
}

/** Human-friendly role label. Matches Android's `displayLabel()`. */
export function roleLabel(role: EndpointRole | null): string {
  switch (role) {
    case 'lan':
      return 'LAN'
    case 'tailscale':
      return 'Tailscale'
    case 'public':
      return 'Public'
    case 'custom':
      return 'Custom VPN'
    default:
      return 'Unknown'
  }
}

/** Is this URL TLS-encrypted at the transport level? */
export function urlIsSecure(url: string): boolean {
  const s = url.trim().toLowerCase()
  return s.startsWith('wss://') || s.startsWith('https://')
}

/**
 * Transport security label. Prefers the server's `transport_hint` over the
 * URL scheme because the hint survives proxies — e.g. a wss:// URL fronting
 * a Tailscale HTTPS-terminated relay vs. a raw tailnet connection.
 *
 *   secure → `wss://` / tailnet-signed / `tailscale serve`
 *   plain  → `ws://` (no TLS on the wire)
 */
export function transportLabel(
  url: string,
  transportHint: string | null | undefined
): 'Secure' | 'Plain' {
  const hint = (transportHint ?? '').toLowerCase()
  if (hint === 'wss' || hint === 'tls' || hint === 'secure' || hint === 'https') {
    return 'Secure'
  }
  if (hint === 'ws' || hint === 'plain' || hint === 'insecure' || hint === 'http') {
    return 'Plain'
  }
  // Fall back to URL scheme when hint is absent / unknown.
  return urlIsSecure(url) ? 'Secure' : 'Plain'
}

export interface ConnectBannerOpts {
  url: string
  serverVersion: string | null
  meta?: AuthMeta | null
  endpointRole?: string | null
  /** Force-disable color regardless of env/TTY (the `--no-color` flag). */
  noColor?: boolean
  /** Stream the strip is written to; its TTY status decides colour.
   * Defaults to stderr, which is where chat/shell write it. */
  stream?: ColorStream
}

/**
 * Build the one-line connect banner as a cockpit status strip. Examples:
 *   "▸ relay: connected · LAN · plain · server 0.6.0"
 *   "▸ relay: connected · Tailscale · secure · server 0.6.0"
 *   "▸ relay: connected · Public · secure · server 0.7.2"
 *   "▸ relay: connected · plain ws:// · server 0.6.0"    [no role — degrade]
 *
 * Does NOT print itself — returns the string so the caller can route it
 * (stderr vs stdout) and the tests can assert on the shape. Pass `stream` when
 * the caller writes somewhere other than stderr, so colour follows the stream
 * the bytes actually land on. The `·` separators keep it parseable at a
 * glance; secure is green, plain is amber (no TLS on the wire is worth
 * noticing), the route is relay periwinkle.
 */
export function buildConnectBanner(opts: ConnectBannerOpts): string {
  const t = makeTheme({ noColor: opts.noColor, stream: opts.stream ?? process.stderr })
  const role = parseRole(opts.endpointRole)
  const transport = transportLabel(opts.url, opts.meta?.transportHint ?? null)
  const v = opts.serverVersion ?? '?'
  const sep = t.dim(' · ')

  const transportText = role ? transport.toLowerCase() : `${transport.toLowerCase()} ${urlIsSecure(opts.url) ? 'wss://' : 'ws://'}`
  const transportStyled = transport === 'Secure' ? t.ok(transportText) : t.warn(transportText)

  const parts = [
    t.dim('relay:') + ' ' + t.ok('connected'),
    role ? t.relay(roleLabel(role)) : null,
    transportStyled,
    t.muted(`server ${v}`)
  ].filter((p): p is string => p !== null)

  return `${t.relay(SYMBOLS.active)} ${parts.join(sep)}`
}

/**
 * Epoch-second → human-friendly relative expiry string.
 *   null        → "never"
 *   past         → "expired"
 *   under 60s    → "in Ns"
 *   under 1h     → "in Nm"
 *   under 24h    → "in Nh"
 *   over 24h     → "in Nd"
 */
export function humanExpiry(ttlExpiresAt: number | null | undefined): string {
  if (ttlExpiresAt === null || ttlExpiresAt === undefined) {
    return 'never'
  }
  const now = Date.now() / 1000
  const delta = ttlExpiresAt - now
  if (delta <= 0) {
    return 'expired'
  }
  if (delta < 60) {
    return `in ${Math.floor(delta)}s`
  }
  if (delta < 3600) {
    return `in ${Math.floor(delta / 60)}m`
  }
  if (delta < 86_400) {
    return `in ${Math.floor(delta / 3600)}h`
  }
  return `in ${Math.floor(delta / 86_400)}d`
}

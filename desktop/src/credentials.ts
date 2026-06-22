// Credential resolution with a strict precedence chain:
//   1. --token / HERMES_RELAY_TOKEN          (session token — skips pairing)
//   2. --pair-qr / HERMES_RELAY_PAIR_QR      (multi-endpoint QR payload; probe + pair)
//   3. --code  / HERMES_RELAY_CODE           (pairing code — one-time)
//   4. ~/.hermes/remote-sessions.json        (previously-minted session token)
//   5. interactive prompt                    (TTY only)
//
// The chain mirrors the TUI's resolveCredentials() in entry.tsx so users
// paired via one surface can use the other without re-pairing. Kept separate
// from commands/*.ts because pair.ts uses the prompt directly and should not
// also fall through to stored-token reuse.
//
// The `--pair-qr` slot carries a full v3 QR payload (ADR 24). When present we
// decode, run the priority-aware reachability probe against its `endpoints`
// array, and hand back the winning candidate alongside the pairing code. The
// caller is expected to inspect `resolvedEndpoint.relay.url` and override its
// `--remote` argument — we don't reach into the caller's URL state from here.

import { humanExpiry } from './banner.js'
import type { EndpointCandidate } from './endpoint.js'
import { theme as makeTheme } from './lib/theme.js'
import { promptForPairingCode } from './pairing.js'
import {
  decodePairingPayload,
  payloadToRelayCandidates,
  probeCandidatesByPriority,
  relayPairingCodeFromPayload
} from './pairingQr.js'
import { getSession } from './remoteSessions.js'

/** Warn (on a TTY only) when a stored token is at/near expiry, so a returning
 * user gets a heads-up + the exact re-pair command instead of a bare auth
 * failure on the next request. Piped/scripted output stays clean. */
function maybeWarnExpiry(url: string, ttlExpiresAt: number | null | undefined): void {
  if (ttlExpiresAt === null || ttlExpiresAt === undefined) {
    return
  }
  if (!process.stderr.isTTY) {
    return
  }
  const delta = ttlExpiresAt - Date.now() / 1000
  const t = makeTheme()
  if (delta <= 0) {
    process.stderr.write(
      t.warnLine(`stored session for ${url} has expired — re-pair: hermes-relay pair --remote ${url}`) + '\n'
    )
  } else if (delta < 3600) {
    process.stderr.write(
      t.warnLine(
        `stored session for ${url} expires ${humanExpiry(ttlExpiresAt)} — re-pair soon: hermes-relay pair --remote ${url}`
      ) + '\n'
    )
  }
}

export interface Credentials {
  sessionToken?: string
  pairingCode?: string
  /**
   * Populated when creds came from a multi-endpoint QR payload. Caller uses
   * `resolvedEndpoint.relay.url` as the WSS URL and `.role` for the banner.
   * Absent when credentials came from any other chain entry.
   */
  resolvedEndpoint?: EndpointCandidate
}

export interface ResolveOptions {
  /** --code from argv. */
  argCode?: string
  /** --token from argv. */
  argToken?: string
  /**
   * --pair-qr from argv. The raw pairing-QR payload (either compact JSON or
   * base64-wrapped compact JSON) — the same string the phone would scan.
   * When provided, credential resolution routes through the payload decoder
   * + reachability probe instead of the `--code` flow.
   */
  argPairQr?: string
  /** Refuse to prompt even if stdin is a TTY — used by scripting callers. */
  nonInteractive?: boolean
}

export async function resolveCredentials(
  url: string,
  opts: ResolveOptions = {}
): Promise<Credentials> {
  const envToken = process.env.HERMES_RELAY_TOKEN?.trim()
  const token = opts.argToken?.trim() || envToken
  if (token) {
    return { sessionToken: token }
  }

  const envPairQr = process.env.HERMES_RELAY_PAIR_QR?.trim()
  const pairQr = opts.argPairQr?.trim() || envPairQr
  if (pairQr) {
    const payload = decodePairingPayload(pairQr)
    const candidates = payloadToRelayCandidates(payload)
    const winner = await probeCandidatesByPriority(candidates)
    return {
      pairingCode: relayPairingCodeFromPayload(payload),
      resolvedEndpoint: winner,
    }
  }

  const envCode = process.env.HERMES_RELAY_CODE?.trim()
  const code = opts.argCode?.trim() || envCode
  if (code) {
    return { pairingCode: code.toUpperCase() }
  }

  const stored = await getSession(url)
  if (stored) {
    maybeWarnExpiry(url, stored.ttlExpiresAt)
    return { sessionToken: stored.token }
  }

  if (opts.nonInteractive) {
    throw new Error(
      'No credentials. Pass --code <CODE>, --token <TOKEN>, --pair-qr <PAYLOAD>, ' +
        'set HERMES_RELAY_CODE / HERMES_RELAY_TOKEN / HERMES_RELAY_PAIR_QR, or run ' +
        '`hermes-relay pair` first.'
    )
  }

  const pairingCode = await promptForPairingCode(url)
  return { pairingCode }
}

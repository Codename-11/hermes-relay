// pair — explicit pairing subcommand. Does nothing except the handshake:
// connect, auth with a one-time code (or multi-endpoint QR payload), persist
// the minted session token, exit. Useful as a first-time setup step so
// subsequent chat/tools/status calls don't prompt for a code.
//
// Two auth paths:
//   (a) 6-char pairing code + --remote URL (single endpoint, legacy).
//   (b) --pair-qr <full-QR-payload> (ADR 24 multi-endpoint — decode, probe
//       the endpoints list in priority order, connect to the first reachable
//       one, record its role in the stored session so `status` / banner
//       render "Paired via LAN / Tailscale / Public" correctly).

import type { ParsedArgs } from '../cli.js'
import {
  cleanCode,
  isValidCode,
  promptForPairingCode,
  validatePairingPayloadString
} from '../pairing.js'
import { payloadToCandidates, probeCandidatesByPriority } from '../pairingQr.js'
import { resolveFirstRunUrl } from '../relayUrlPrompt.js'
import { saveSession } from '../remoteSessions.js'
import { RelayTransport } from '../transport/RelayTransport.js'

function resolveRemote(args: ParsedArgs): string | null {
  const v = args.flags.remote
  const url = (typeof v === 'string' ? v : null) ?? process.env.HERMES_RELAY_URL ?? null
  return url ? url.trim() : null
}

interface PairTarget {
  url: string
  code: string
  /** Active-endpoint role if this came from a multi-endpoint QR probe. */
  endpointRole: string | null
}

async function resolvePairTarget(args: ParsedArgs): Promise<PairTarget | { error: string }> {
  // Path (b): --pair-qr / HERMES_RELAY_PAIR_QR — multi-endpoint payload. This
  // wins over --remote + --code because the QR carries both: the candidate
  // list AND the pairing key.
  const argPairQr = typeof args.flags['pair-qr'] === 'string' ? args.flags['pair-qr'] : null
  const envPairQr = process.env.HERMES_RELAY_PAIR_QR?.trim()
  const pairQr = argPairQr?.trim() || envPairQr

  if (pairQr) {
    const validated = validatePairingPayloadString(pairQr)
    if (!validated.ok) {
      return { error: `invalid --pair-qr payload: ${validated.reason}` }
    }
    const candidates = payloadToCandidates(validated.payload)
    if (candidates.length === 0) {
      return { error: 'pairing payload had no endpoints to probe' }
    }
    process.stderr.write(`Probing ${candidates.length} endpoint(s)...\n`)
    let winner
    try {
      winner = await probeCandidatesByPriority(candidates)
    } catch (e) {
      return { error: `no endpoints reachable: ${e instanceof Error ? e.message : String(e)}` }
    }
    process.stderr.write(
      `  → picked ${winner.role} endpoint ${winner.relay.url}\n`
    )
    return {
      url: winner.relay.url,
      code: validated.payload.key.toUpperCase(),
      endpointRole: winner.role
    }
  }

  // Path (a): --remote + --code / positional code.
  let url = resolveRemote(args)
  const nonInteractive = !!args.flags['non-interactive']

  // First-run fallback: no --remote and no env var. Either auto-pick a
  // single stored session, prompt from a numbered list of stored sessions,
  // or ask for a brand-new URL. resolveFirstRunUrl() throws if
  // non-interactive + ambiguous (multiple or zero stored sessions).
  if (!url) {
    try {
      url = await resolveFirstRunUrl({
        nonInteractive,
        banner:
          'Pair: no --remote given. Pick an existing session or enter a new relay URL.'
      })
    } catch (e) {
      return { error: e instanceof Error ? e.message : String(e) }
    }
  }

  const argCode = args.positional[0] ?? (typeof args.flags.code === 'string' ? args.flags.code : null)
  const envCode = process.env.HERMES_RELAY_CODE
  const raw = argCode ?? envCode ?? null

  let code: string
  if (raw) {
    const cleaned = cleanCode(raw)
    if (!isValidCode(cleaned)) {
      return { error: `invalid code format. Need 6 chars of A-Z or 0-9. Got "${raw}"` }
    }
    code = cleaned
  } else {
    try {
      code = await promptForPairingCode(url)
    } catch (e) {
      return { error: e instanceof Error ? e.message : String(e) }
    }
  }
  return { url, code, endpointRole: null }
}

export async function pairCommand(args: ParsedArgs): Promise<number> {
  const target = await resolvePairTarget(args)
  if ('error' in target) {
    process.stderr.write(`error: ${target.error}\n`)
    return 1
  }

  process.stderr.write(`Pairing with ${target.url}...\n`)

  const relay = new RelayTransport({
    url: target.url,
    pairingCode: target.code,
    deviceName: `hermes-relay-cli (${process.platform})`
  })

  relay.start()
  const outcome = await relay.whenAuthResolved()

  if (outcome.ok) {
    await saveSession(target.url, outcome.token, outcome.serverVersion, {
      grants: outcome.meta.grants,
      ttlExpiresAt: outcome.meta.ttlExpiresAt,
      endpointRole: target.endpointRole
    })
    process.stdout.write(`✓ Paired. Token stored in ~/.hermes/remote-sessions.json\n`)
    process.stdout.write(`  Server: ${outcome.serverVersion ?? '?'}\n`)
    process.stdout.write(`  Relay:  ${target.url}\n`)
    if (target.endpointRole) {
      process.stdout.write(`  Route:  ${target.endpointRole}\n`)
    }
    try {
      relay.kill()
    } catch {
      /* ignore */
    }
    return 0
  }

  process.stderr.write(`✗ Pairing failed: ${outcome.reason}\n`)
  process.stderr.write(`  ${relay.getLogTail(5)}\n`)
  try {
    relay.kill()
  } catch {
    /* ignore */
  }
  return 1
}

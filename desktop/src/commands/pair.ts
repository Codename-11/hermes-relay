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
import { formatError } from '../lib/hints.js'
import { SYMBOLS, theme as makeTheme } from '../lib/theme.js'
import { printUsage, type UsageSpec } from '../lib/usage.js'
import {
  cleanCode,
  isValidCode,
  promptForPairingCode,
  validatePairingPayloadString
} from '../pairing.js'
import {
  payloadToRelayCandidates,
  probeCandidatesByPriority,
  relayPairingCodeFromPayload
} from '../pairingQr.js'
import { DEFAULT_RELAY_PORT, normalizeRelayUrl, resolveFirstRunUrl } from '../relayUrlPrompt.js'
import { saveSession } from '../remoteSessions.js'
import { ensureToolsConsent } from '../tools/consent.js'
import { RelayTransport } from '../transport/RelayTransport.js'

const PAIR_USAGE: UsageSpec = {
  name: 'pair',
  summary: 'pair with a relay and store a session token',
  usage: ['pair [CODE] --remote <url>', 'pair --pair-qr "<invite>"'],
  flags: [
    {
      flag: '--pair-qr <invite>',
      desc: 'Paste a full QR payload or hermes-relay://pair invite (recommended — probes endpoints)'
    },
    { flag: '--remote <url>', desc: 'Relay URL (with [CODE] or an interactive prompt)' },
    { flag: '--code <code>', desc: '6-char pairing code (or pass it as the positional arg)' },
    {
      flag: '--grant-tools',
      desc: 'Also grant desktop-tool consent now (TTY prompt) — lets `daemon` work with no `shell` round-trip'
    },
    { flag: '--auto-grant-tools', desc: 'Grant desktop-tool consent without prompting (scripts/CI)' }
  ],
  examples: [
    'hermes-relay pair --pair-qr "hermes-relay://pair?payload=…"',
    'hermes-relay pair --remote ws://192.168.1.50:8767',
    'hermes-relay pair ABC123 --remote ws://host:8767 --grant-tools'
  ]
}

function resolveRemote(args: ParsedArgs): string | null {
  const v = args.flags.remote
  const raw = (typeof v === 'string' ? v : null) ?? process.env.HERMES_RELAY_URL ?? null
  if (!raw) {
    return null
  }
  const norm = normalizeRelayUrl(raw)
  if (norm.added) {
    process.stderr.write(`  (no port given — using :${DEFAULT_RELAY_PORT})\n`)
  }
  return norm.url
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
    let candidates
    let pairingCode
    try {
      candidates = payloadToRelayCandidates(validated.payload)
      pairingCode = relayPairingCodeFromPayload(validated.payload)
    } catch (e) {
      return { error: e instanceof Error ? e.message : String(e) }
    }
    const t = makeTheme({ noColor: !!args.flags['no-color'] })
    process.stderr.write(t.bold(`Probing ${candidates.length} endpoint(s)…`) + '\n')
    let winner
    try {
      winner = await probeCandidatesByPriority(candidates, {
        onProbe: (ev) => {
          const label = `[${ev.index}/${ev.total}] ${ev.candidate.role} ${ev.candidate.relay.url}`
          if (ev.phase === 'result' && ev.reachable) {
            process.stderr.write(`  ${t.okLine(label)} ${t.muted(`${ev.elapsedMs}ms`)}\n`)
          } else if (ev.phase === 'result') {
            process.stderr.write(`  ${t.muted(`${SYMBOLS.dot} ${label} — ${ev.error ?? 'unreachable'}`)}\n`)
          } else if (ev.phase === 'cached') {
            process.stderr.write(`  ${t.okLine(label)} ${t.muted('(cached)')}\n`)
          }
        }
      })
    } catch (e) {
      return { error: `no endpoints reachable: ${e instanceof Error ? e.message : String(e)}` }
    }
    process.stderr.write(
      `  ${t.cyan(SYMBOLS.arrow)} picked ${t.bold(winner.role)} endpoint ${winner.relay.url}\n`
    )
    return {
      url: winner.relay.url,
      code: pairingCode,
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
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  if (args.flags.help) {
    printUsage(PAIR_USAGE, t)
    return 0
  }
  const target = await resolvePairTarget(args)
  if ('error' in target) {
    process.stderr.write(formatError(target.error, { command: 'pair' }, t) + '\n')
    return 1
  }

  // Tool-consent shortcuts — let users go straight from `pair` to `daemon`
  // without the interactive `shell` round-trip. Two flavors so the consent
  // act is never implicit: `--grant-tools` prompts on TTY, `--auto-grant-tools`
  // stamps without prompting (explicit non-interactive opt-in for scripts).
  // Auto wins if both are passed — no point prompting after the user already
  // committed in writing.
  const autoGrant = !!args.flags['auto-grant-tools']
  const promptGrant = !!args.flags['grant-tools'] && !autoGrant

  process.stderr.write(t.muted(`Pairing with ${target.url}…`) + '\n')

  const relay = new RelayTransport({
    url: target.url,
    pairingCode: target.code,
    deviceName: `hermes-relay-cli (${process.platform})`
  })

  relay.start()
  const outcome = await relay.whenAuthResolved()

  if (outcome.ok) {
    // Fold --auto-grant-tools into the initial save so the consent flag and
    // the token land atomically. The interactive --grant-tools path runs
    // ensureToolsConsent() below, which writes its own follow-up save.
    await saveSession(target.url, outcome.token, outcome.serverVersion, {
      grants: outcome.meta.grants,
      ttlExpiresAt: outcome.meta.ttlExpiresAt,
      endpointRole: target.endpointRole,
      ...(autoGrant ? { toolsConsented: true } : {})
    })
    process.stdout.write(t.okLine('Paired. Token stored in ~/.hermes/remote-sessions.json') + '\n')
    process.stdout.write(t.muted(`  server: ${outcome.serverVersion ?? '?'}`) + '\n')
    process.stdout.write(t.muted(`  relay:  ${target.url}`) + '\n')
    if (target.endpointRole) {
      process.stdout.write(t.muted(`  route:  ${target.endpointRole}`) + '\n')
    }

    if (autoGrant) {
      process.stdout.write(t.okLine('Desktop tool consent granted (--auto-grant-tools).') + '\n')
    } else if (promptGrant) {
      const result = await ensureToolsConsent(target.url)
      if (result.consented) {
        process.stdout.write(t.okLine('Desktop tool consent granted.') + '\n')
      } else {
        process.stderr.write(
          t.warnLine(`Tool consent not granted: ${result.reason ?? 'declined'}`) + '\n' +
            t.muted(`  Pair succeeded; rerun \`hermes-relay pair --remote ${target.url} --grant-tools\` on a TTY to grant.`) + '\n'
        )
      }
    } else {
      // Nudge the daemon-first workflow: most users who pair from a terminal
      // want desktop tools, and discovering --grant-tools after the fact means
      // an extra `shell` round-trip. Surface it once, here.
      process.stdout.write(
        t.muted('  tip: add --grant-tools to also enable desktop tools (needed for `daemon`).') + '\n'
      )
    }

    try {
      relay.kill()
    } catch {
      /* ignore */
    }
    return 0
  }

  process.stderr.write(t.errLine(`Pairing failed: ${outcome.reason}`) + '\n')
  const hint = formatError(outcome.reason, { command: 'pair', url: target.url }, t)
  // formatError repeats the message; only emit the hint line (2nd line) if present.
  const hintLine = hint.split('\n')[1]
  if (hintLine) {
    process.stderr.write(hintLine + '\n')
  }
  process.stderr.write(t.muted(`  ${relay.getLogTail(5)}`) + '\n')
  try {
    relay.kill()
  } catch {
    /* ignore */
  }
  return 1
}

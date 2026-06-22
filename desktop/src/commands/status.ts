// status — list stored relay sessions from ~/.hermes/remote-sessions.json.
// No network — purely a read of the local file. Mirrors `hermes-pair list`
// on the server so a user can see which relays this machine is paired with,
// plus the grants (which channels this token can access) and TTL (when the
// token expires) captured from the auth.ok handshake.

import { humanExpiry, parseRole, roleLabel } from '../banner.js'
import type { ParsedArgs } from '../cli.js'
import { theme as makeTheme } from '../lib/theme.js'
import { printUsage, type UsageSpec } from '../lib/usage.js'
import { listSessions, type RemoteSessionRecord } from '../remoteSessions.js'

const STATUS_USAGE: UsageSpec = {
  name: 'status',
  summary: 'show the relays this machine is paired with (grants, TTL, desktop-tool consent)',
  usage: ['status [--json] [--reveal-tokens]'],
  flags: [
    { flag: '--json', desc: 'Machine-readable output (tokens redacted by default)' },
    { flag: '--reveal-tokens', desc: 'Include full session tokens (use with care)' }
  ],
  examples: ['hermes-relay status', 'hermes-relay status --json']
}

function humanAge(seconds: number): string {
  if (seconds < 60) {
    return `${seconds}s`
  }
  if (seconds < 3600) {
    return `${Math.floor(seconds / 60)}m`
  }
  if (seconds < 86_400) {
    return `${Math.floor(seconds / 3600)}h`
  }
  return `${Math.floor(seconds / 86_400)}d`
}

/** Default redaction: emit the static string "(redacted)" — a prefix like
 * `e35a85b2…fe2c` is copy-pasteable into issues and pairs with the URL as
 * a stable session fingerprint, which is exactly the leakage class we want
 * to avoid. Callers who need the token for scripting pass `--reveal-tokens`
 * and take responsibility for the destination. Symmetric with the `devices`
 * flow. */
const REDACTED = '(redacted — pass --reveal-tokens to show)'

export async function statusCommand(args: ParsedArgs): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  if (args.flags.help) {
    printUsage(STATUS_USAGE, t)
    return 0
  }
  const sessions = await listSessions()
  const entries = Object.entries(sessions)
  const revealTokens = !!args.flags['reveal-tokens']

  if (args.flags.json) {
    // Bearer tokens are secrets — redact by default so `status --json > file`
    // or `| tee` doesn't splash them into shell history, CI logs, or pastes.
    const out: Record<string, RemoteSessionRecord> = revealTokens
      ? sessions
      : Object.fromEntries(
          Object.entries(sessions).map(([url, rec]) => [
            url,
            { ...rec, token: '(redacted)' }
          ])
        )
    process.stdout.write(JSON.stringify(out, null, 2) + '\n')
    return 0
  }

  if (entries.length === 0) {
    process.stdout.write(
      t.muted('No paired relays. Run `hermes-relay pair --remote ws://host:port` to pair.') + '\n'
    )
    return 0
  }

  process.stdout.write(t.bold(`Paired relays (${entries.length})`) + '\n\n')
  const now = Math.floor(Date.now() / 1000)
  const kv = (label: string, value: string): string =>
    `    ${t.muted((label + ':').padEnd(9))} ${value}`
  for (const [url, rec] of entries) {
    const age = humanAge(Math.max(0, now - rec.pairedAt))
    const tokenDisplay = revealTokens ? rec.token : REDACTED
    const expiresRaw = humanExpiry(rec.ttlExpiresAt)
    const expires = expiresRaw === 'expired' ? t.err('expired') : expiresRaw
    process.stdout.write(`  ${t.cyan(url)}\n`)
    process.stdout.write(kv('server', rec.serverVersion ?? '(unknown)') + '\n')
    process.stdout.write(kv('paired', `${age} ago`) + '\n')
    process.stdout.write(kv('token', tokenDisplay) + '\n')
    process.stdout.write(kv('expires', expires) + '\n')
    const computerUse = rec.toolsConsented ? 'feature-flagged opt-in' : 'no'
    process.stdout.write(
      kv('desktop', `${t.statusDot(!!rec.toolsConsented)} tools=${rec.toolsConsented ? 'yes' : 'no'}, computer-use=${computerUse}`) + '\n'
    )
    const role = parseRole(rec.endpointRole)
    if (role) {
      process.stdout.write(kv('route', roleLabel(role)) + '\n')
    }
    if (rec.grants && Object.keys(rec.grants).length > 0) {
      const formatted = Object.entries(rec.grants)
        .map(([channel, expiry]) => {
          const when = expiry === null ? 'never' : humanExpiry(expiry)
          return `${channel} (${when})`
        })
        .sort()
      process.stdout.write(kv('grants', formatted.join(', ')) + '\n')
    }
    if (rec.certPinSha256) {
      process.stdout.write(kv('cert', `sha256:${rec.certPinSha256.slice(0, 12)}…`) + '\n')
    }
    process.stdout.write('\n')
  }

  return 0
}

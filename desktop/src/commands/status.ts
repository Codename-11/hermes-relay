// status — list stored relay sessions from ~/.hermes/remote-sessions.json.
// No network — purely a read of the local file. Mirrors `hermes-pair list`
// on the server so a user can see which relays this machine is paired with,
// plus the grants (which channels this token can access) and TTL (when the
// token expires) captured from the auth.ok handshake.

import { humanExpiry, parseRole, roleLabel } from '../banner.js'
import type { ParsedArgs } from '../cli.js'
import { listSessions, type RemoteSessionRecord } from '../remoteSessions.js'

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

function redactToken(token: string): string {
  return token.length >= 12 ? `${token.slice(0, 8)}…${token.slice(-4)}` : '(redacted)'
}

export async function statusCommand(args: ParsedArgs): Promise<number> {
  const sessions = await listSessions()
  const entries = Object.entries(sessions)
  const revealTokens = !!args.flags['reveal-tokens']

  if (args.flags.json) {
    // Bearer tokens are secrets — redact by default so `status --json > file`
    // or `| tee` doesn't splash them into shell history, CI logs, or pastes.
    // Callers who actually need the token (scripting re-auth) must opt in.
    const out: Record<string, RemoteSessionRecord> = revealTokens
      ? sessions
      : Object.fromEntries(
          Object.entries(sessions).map(([url, rec]) => [
            url,
            { ...rec, token: redactToken(rec.token) }
          ])
        )
    process.stdout.write(JSON.stringify(out, null, 2) + '\n')
    return 0
  }

  if (entries.length === 0) {
    process.stdout.write(
      'No paired relays. Run `hermes-relay pair --remote ws://host:port` to pair.\n'
    )
    return 0
  }

  process.stdout.write(`Paired relays (${entries.length}):\n\n`)
  const now = Math.floor(Date.now() / 1000)
  for (const [url, rec] of entries) {
    const age = humanAge(Math.max(0, now - rec.pairedAt))
    const tokPreview =
      rec.token.length >= 12 ? `${rec.token.slice(0, 8)}…${rec.token.slice(-4)}` : '(short)'
    process.stdout.write(`  ${url}\n`)
    process.stdout.write(`    server:   ${rec.serverVersion ?? '(unknown)'}\n`)
    process.stdout.write(`    paired:   ${age} ago\n`)
    process.stdout.write(`    token:    ${tokPreview}\n`)
    process.stdout.write(`    expires:  ${humanExpiry(rec.ttlExpiresAt)}\n`)
    const role = parseRole(rec.endpointRole)
    if (role) {
      process.stdout.write(`    route:    ${roleLabel(role)}\n`)
    }
    if (rec.grants && Object.keys(rec.grants).length > 0) {
      const formatted = Object.entries(rec.grants)
        .map(([channel, expiry]) => {
          const when = expiry === null ? 'never' : humanExpiry(expiry)
          return `${channel} (${when})`
        })
        .sort()
      process.stdout.write(`    grants:   ${formatted.join(', ')}\n`)
    }
    if (rec.certPinSha256) {
      process.stdout.write(`    cert:     sha256:${rec.certPinSha256.slice(0, 12)}…\n`)
    }
    process.stdout.write('\n')
  }

  return 0
}

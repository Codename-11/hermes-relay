// devices — server-side paired-device management.
//
// The relay exposes three HTTP endpoints (not WSS) for session management:
//   GET    /sessions                list all paired devices
//   DELETE /sessions/{token_prefix} revoke one
//   PATCH  /sessions/{token_prefix} extend TTL / change grants
//
// These live on the same port as the WSS endpoint (default 8767). Auth is
// `Authorization: Bearer <session_token>`. For local dashboards the server
// accepts loopback traffic without a bearer (plugin/relay/server.py:646+),
// but from a remote CLI we always need the token — pulled from the local
// session store.
//
// Subcommand shape (mirrors Android's "Relay Sessions" screen):
//   hermes-relay devices                      list
//   hermes-relay devices list --json          list as JSON for scripting
//   hermes-relay devices revoke <prefix>      DELETE — destroys the token
//   hermes-relay devices extend <prefix> [--ttl <seconds>]  PATCH — defaults to 24h
//
// If no `--remote` is passed we default to the single stored relay (if
// exactly one exists). Otherwise --remote is required.

import { humanExpiry } from '../banner.js'
import type { ParsedArgs } from '../cli.js'
import { getSession, listSessions } from '../remoteSessions.js'

const DEFAULT_EXTEND_TTL_SECONDS = 24 * 3600

interface ServerSession {
  token_prefix: string
  device_name?: string
  device_id?: string
  created_at?: number
  first_seen?: number
  last_seen?: number
  expires_at?: number | null
  grants?: Record<string, number | null>
  transport_hint?: string
  is_current?: boolean
}

interface SessionsListResponse {
  sessions?: ServerSession[]
}

/** Convert a ws://host:port URL to http://host:port for the sessions HTTP API.
 * The relay serves both on the same port, so the host:port swap is the whole
 * transform. `wss://` → `https://`, `ws://` → `http://`. */
function wsToHttp(url: string): string {
  const trimmed = url.trim()
  if (trimmed.startsWith('wss://')) {
    return 'https://' + trimmed.slice('wss://'.length)
  }
  if (trimmed.startsWith('ws://')) {
    return 'http://' + trimmed.slice('ws://'.length)
  }
  // Already http(s) — leave alone.
  return trimmed
}

async function resolveRemoteAndToken(
  args: ParsedArgs
): Promise<{ url: string; token: string }> {
  const argUrl = typeof args.flags.remote === 'string' ? args.flags.remote.trim() : null
  const envUrl = process.env.HERMES_RELAY_URL?.trim()
  const argToken = typeof args.flags.token === 'string' ? args.flags.token.trim() : null
  const envToken = process.env.HERMES_RELAY_TOKEN?.trim()

  // Token direct from flag / env short-circuits the stored-session lookup.
  if (argToken || envToken) {
    const url = argUrl ?? envUrl
    if (!url) {
      throw new Error('--token supplied without --remote. Pass both, or set HERMES_RELAY_URL.')
    }
    return { url, token: (argToken ?? envToken)! }
  }

  const stored = await listSessions()
  const urls = Object.keys(stored)

  let url: string
  if (argUrl || envUrl) {
    url = argUrl ?? envUrl!
  } else if (urls.length === 1) {
    url = urls[0]!
  } else if (urls.length === 0) {
    throw new Error('No paired relays. Run `hermes-relay pair --remote ws://host:port` first.')
  } else {
    throw new Error(
      `Multiple paired relays; pass --remote to pick one (${urls.join(', ')}).`
    )
  }

  const rec = await getSession(url)
  if (!rec) {
    throw new Error(`No stored session for ${url}. Run \`hermes-relay pair --remote ${url}\` first.`)
  }
  return { url, token: rec.token }
}

async function jsonFetch(
  url: string,
  token: string,
  init: { method: 'GET' | 'DELETE' | 'PATCH'; body?: unknown } = { method: 'GET' }
): Promise<{ status: number; body: unknown }> {
  const headers: Record<string, string> = {
    Authorization: `Bearer ${token}`,
    Accept: 'application/json'
  }
  if (init.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  const res = await fetch(url, {
    method: init.method,
    headers,
    body: init.body !== undefined ? JSON.stringify(init.body) : undefined
  })
  // Parse body opportunistically — not all responses are JSON (e.g. 204).
  const text = await res.text()
  let body: unknown
  if (text.length > 0) {
    try {
      body = JSON.parse(text)
    } catch {
      body = text
    }
  }
  return { status: res.status, body }
}

async function listDevices(args: ParsedArgs): Promise<number> {
  const { url, token } = await resolveRemoteAndToken(args)
  const httpBase = wsToHttp(url)
  const { status, body } = await jsonFetch(`${httpBase}/sessions`, token)

  if (status !== 200) {
    process.stderr.write(`error: GET /sessions returned ${status}: ${JSON.stringify(body)}\n`)
    return 1
  }

  const sessions = (body as SessionsListResponse)?.sessions ?? []

  if (args.flags.json) {
    process.stdout.write(JSON.stringify(sessions, null, 2) + '\n')
    return 0
  }

  if (sessions.length === 0) {
    process.stdout.write(`(no paired devices on ${url})\n`)
    return 0
  }

  process.stdout.write(`Devices paired with ${url} (${sessions.length}):\n\n`)
  for (const s of sessions) {
    const tag = s.is_current ? ' ● (this device)' : ''
    const name = s.device_name ?? '(unnamed)'
    process.stdout.write(`  ${s.token_prefix}   ${name}${tag}\n`)
    if (s.last_seen) {
      const ageSec = Math.floor(Date.now() / 1000) - s.last_seen
      const ageHuman =
        ageSec < 60
          ? `${ageSec}s`
          : ageSec < 3600
            ? `${Math.floor(ageSec / 60)}m`
            : ageSec < 86_400
              ? `${Math.floor(ageSec / 3600)}h`
              : `${Math.floor(ageSec / 86_400)}d`
      process.stdout.write(`      last seen:  ${ageHuman} ago\n`)
    }
    process.stdout.write(`      expires:    ${humanExpiry(s.expires_at ?? null)}\n`)
    if (s.transport_hint) {
      process.stdout.write(`      transport:  ${s.transport_hint}\n`)
    }
    if (s.grants && Object.keys(s.grants).length > 0) {
      const formatted = Object.entries(s.grants)
        .map(([k, v]) => `${k}=${v === null ? 'never' : humanExpiry(v)}`)
        .sort()
        .join(', ')
      process.stdout.write(`      grants:     ${formatted}\n`)
    }
    process.stdout.write('\n')
  }
  process.stdout.write(
    `  Use \`hermes-relay devices revoke <prefix>\` to delete a session, or\n` +
      `      \`hermes-relay devices extend <prefix> --ttl <seconds>\` to push the expiry.\n`
  )
  return 0
}

async function revokeDevice(args: ParsedArgs): Promise<number> {
  const prefix = args.positional[0]
  if (!prefix) {
    process.stderr.write('error: `devices revoke` needs a token prefix. Run `devices` to see them.\n')
    return 2
  }
  const { url, token } = await resolveRemoteAndToken(args)
  const httpBase = wsToHttp(url)
  const { status, body } = await jsonFetch(`${httpBase}/sessions/${encodeURIComponent(prefix)}`, token, {
    method: 'DELETE'
  })
  if (status === 200 || status === 204) {
    const revokedSelf = typeof body === 'object' && body !== null && (body as Record<string, unknown>).revoked_self === true
    process.stdout.write(`✓ revoked ${prefix}${revokedSelf ? ' (this device — subsequent commands will re-pair)' : ''}\n`)
    return 0
  }
  if (status === 404) {
    process.stderr.write(`error: no session matches prefix "${prefix}"\n`)
    return 1
  }
  if (status === 409) {
    process.stderr.write(`error: multiple sessions match "${prefix}". Use a longer prefix.\n`)
    return 1
  }
  process.stderr.write(`error: DELETE /sessions/${prefix} returned ${status}: ${JSON.stringify(body)}\n`)
  return 1
}

async function extendDevice(args: ParsedArgs): Promise<number> {
  const prefix = args.positional[0]
  if (!prefix) {
    process.stderr.write('error: `devices extend` needs a token prefix. Run `devices` to see them.\n')
    return 2
  }
  const rawTtl = typeof args.flags.ttl === 'string' ? args.flags.ttl : null
  const ttlSeconds = rawTtl === null ? DEFAULT_EXTEND_TTL_SECONDS : parseInt(rawTtl, 10)
  if (!Number.isFinite(ttlSeconds) || ttlSeconds < 0) {
    process.stderr.write(`error: --ttl must be a non-negative integer (got "${rawTtl}")\n`)
    return 2
  }
  const { url, token } = await resolveRemoteAndToken(args)
  const httpBase = wsToHttp(url)
  const { status, body } = await jsonFetch(`${httpBase}/sessions/${encodeURIComponent(prefix)}`, token, {
    method: 'PATCH',
    body: { ttl_seconds: ttlSeconds }
  })
  if (status === 200) {
    const expiresAt = (body as { expires_at?: number | null })?.expires_at ?? null
    process.stdout.write(
      `✓ extended ${prefix} — now expires ${humanExpiry(expiresAt)}` +
        (expiresAt === null ? ' (never)' : '') +
        '\n'
    )
    return 0
  }
  process.stderr.write(`error: PATCH /sessions/${prefix} returned ${status}: ${JSON.stringify(body)}\n`)
  return 1
}

export async function devicesCommand(args: ParsedArgs): Promise<number> {
  // The first positional after `devices` is the sub-verb: list (default) /
  // revoke / extend. Shift it out so the remaining positionals are available
  // to the sub-handler (which uses positional[0] for the token prefix).
  const sub = args.positional[0] ?? 'list'

  if (sub === 'list') {
    if (args.positional.length > 0 && args.positional[0] === 'list') {
      args.positional.shift()
    }
    try {
      return await listDevices(args)
    } catch (e) {
      process.stderr.write(`error: ${e instanceof Error ? e.message : String(e)}\n`)
      return 1
    }
  }

  if (sub === 'revoke') {
    args.positional.shift()
    try {
      return await revokeDevice(args)
    } catch (e) {
      process.stderr.write(`error: ${e instanceof Error ? e.message : String(e)}\n`)
      return 1
    }
  }

  if (sub === 'extend') {
    args.positional.shift()
    try {
      return await extendDevice(args)
    } catch (e) {
      process.stderr.write(`error: ${e instanceof Error ? e.message : String(e)}\n`)
      return 1
    }
  }

  process.stderr.write(`unknown devices sub-verb "${sub}". Try: list | revoke <prefix> | extend <prefix>\n`)
  return 2
}

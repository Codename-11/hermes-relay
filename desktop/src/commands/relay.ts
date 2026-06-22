// relay — inspect the relay SERVER itself (plugin v1.2.0 management surface).
//
// Three read surfaces the relay gained but the CLI never exposed:
//   GET /relay/info      version, uptime, sessions, pending  (LOOPBACK-ONLY)
//   GET /relay/security  runtime auth toggles                (LOOPBACK-ONLY)
//   GET /context/injected what the relay injects into the agent's system
//                         prompt — loopback OR a relay session bearer, so this
//                         one works from a remote laptop too.
//
// info/security are gated to the relay host (operators); when a remote caller
// hits them we get a 403 and explain that honestly rather than failing raw.

import type { ParsedArgs } from '../cli.js'
import { getActiveDesktopRelayUrl } from '../desktopConfig.js'
import { formatError } from '../lib/hints.js'
import { theme as makeTheme, type Theme } from '../lib/theme.js'
import { printUsage, unknownSubcommand, type UsageSpec } from '../lib/usage.js'
import { getSession, listSessions } from '../remoteSessions.js'

const RELAY_USAGE: UsageSpec = {
  name: 'relay',
  summary: 'inspect the relay server — info, security toggles, injected agent context',
  usage: ['relay info', 'relay security', 'relay context'],
  subcommands: [
    { verb: 'info', desc: 'Version, uptime, sessions, pending (loopback-only — run on the relay host)' },
    { verb: 'security', desc: 'Runtime security toggles (loopback-only)' },
    { verb: 'context', desc: 'Audit the system-prompt context the relay injects into the agent' }
  ],
  flags: [
    { flag: '--remote <url>', desc: 'Relay to query (default: stored/active)' },
    { flag: '--json', desc: 'Machine-readable output' }
  ],
  examples: ['hermes-relay relay context', 'hermes-relay relay info']
}

function wsToHttp(url: string): string {
  const t = url.trim()
  if (t.startsWith('wss://')) return 'https://' + t.slice(6)
  if (t.startsWith('ws://')) return 'http://' + t.slice(5)
  return t
}

async function resolveRemoteAndToken(args: ParsedArgs): Promise<{ url: string; token: string }> {
  const argUrl = typeof args.flags.remote === 'string' ? args.flags.remote.trim() : null
  const envUrl = process.env.HERMES_RELAY_URL?.trim()
  const argToken = typeof args.flags.token === 'string' ? args.flags.token.trim() : null
  const envToken = process.env.HERMES_RELAY_TOKEN?.trim()

  if (argToken || envToken) {
    const url = argUrl ?? envUrl
    if (!url) {
      throw new Error('--token supplied without --remote. Pass both, or set HERMES_RELAY_URL.')
    }
    return { url, token: (argToken ?? envToken)! }
  }

  const stored = await listSessions()
  const urls = Object.keys(stored)
  const activeDesktopUrl = await getActiveDesktopRelayUrl()
  let url: string
  if (argUrl || envUrl) {
    url = argUrl ?? envUrl!
  } else if (activeDesktopUrl) {
    url = activeDesktopUrl
  } else if (urls.length === 1) {
    url = urls[0]!
  } else if (urls.length === 0) {
    throw new Error('No paired relays. Run `hermes-relay pair --remote ws://host:port` first.')
  } else {
    throw new Error(`Multiple paired relays; pass --remote to pick one (${urls.join(', ')}).`)
  }
  const rec = await getSession(url)
  if (!rec) {
    throw new Error(`No stored session for ${url}. Run \`hermes-relay pair --remote ${url}\` first.`)
  }
  return { url, token: rec.token }
}

async function getJson(
  httpUrl: string,
  token: string
): Promise<{ status: number; body: unknown }> {
  const res = await fetch(httpUrl, {
    method: 'GET',
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' }
  })
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

function fmtUptime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '?'
  const d = Math.floor(seconds / 86_400)
  const h = Math.floor((seconds % 86_400) / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const parts: string[] = []
  if (d) parts.push(`${d}d`)
  if (h) parts.push(`${h}h`)
  if (m || parts.length === 0) parts.push(`${m}m`)
  return parts.join(' ')
}

const kv = (t: Theme) => (label: string, value: string): string =>
  `  ${t.muted((label + ':').padEnd(12))} ${value}`

/** Loopback-only routes return 403 to remote callers. Explain that instead of
 * dumping a raw error — it's expected behaviour, not a misconfiguration. */
function loopbackNote(t: Theme, route: string): string {
  return (
    t.warnLine(`${route} is loopback-only — run this on the relay host.`) + '\n' +
    t.muted('  (it serves server operators / the dashboard; remote callers get 403)')
  )
}

async function relayInfo(args: ParsedArgs, t: Theme): Promise<number> {
  const { url, token } = await resolveRemoteAndToken(args)
  const { status, body } = await getJson(`${wsToHttp(url)}/relay/info`, token)
  if (status === 403) {
    process.stderr.write(loopbackNote(t, '/relay/info') + '\n')
    return 1
  }
  if (status !== 200) {
    process.stderr.write(t.err(`error: GET /relay/info returned ${status}`) + '\n')
    return 1
  }
  if (args.flags.json) {
    process.stdout.write(JSON.stringify(body, null, 2) + '\n')
    return 0
  }
  const r = (body ?? {}) as Record<string, unknown>
  const row = kv(t)
  process.stdout.write(t.bold(`Relay ${url}`) + '\n')
  process.stdout.write(row('version', String(r.version ?? '?')) + '\n')
  process.stdout.write(row('health', String(r.health ?? '?')) + '\n')
  process.stdout.write(row('uptime', fmtUptime(Number(r.uptime_seconds))) + '\n')
  process.stdout.write(row('sessions', String(r.session_count ?? '?')) + '\n')
  process.stdout.write(row('devices', String(r.paired_device_count ?? '?')) + '\n')
  process.stdout.write(row('pending', String(r.pending_commands ?? 0)) + '\n')
  process.stdout.write(row('media', `${r.media_entry_count ?? 0} entries`) + '\n')
  return 0
}

async function relaySecurity(args: ParsedArgs, t: Theme): Promise<number> {
  const { url, token } = await resolveRemoteAndToken(args)
  const { status, body } = await getJson(`${wsToHttp(url)}/relay/security`, token)
  if (status === 403) {
    process.stderr.write(loopbackNote(t, '/relay/security') + '\n')
    return 1
  }
  if (status !== 200) {
    process.stderr.write(t.err(`error: GET /relay/security returned ${status}`) + '\n')
    return 1
  }
  if (args.flags.json) {
    process.stdout.write(JSON.stringify(body, null, 2) + '\n')
    return 0
  }
  const r = (body ?? {}) as Record<string, unknown>
  const row = kv(t)
  const insecure = r.allow_insecure_api_bearer === true
  process.stdout.write(t.bold(`Relay security — ${url}`) + '\n')
  process.stdout.write(
    row('insecure bearer', `${t.statusDot(!insecure)} ${insecure ? t.warn('allowed (plaintext API bearer)') : 'requires HTTPS off-loopback'}`) + '\n'
  )
  process.stdout.write(row('trust proxy', String(r.trust_proxy_headers ?? false)) + '\n')
  process.stdout.write(row('scope', String(r.scope ?? 'runtime')) + '\n')
  return 0
}

async function relayContext(args: ParsedArgs, t: Theme): Promise<number> {
  const { url, token } = await resolveRemoteAndToken(args)
  const { status, body } = await getJson(`${wsToHttp(url)}/context/injected`, token)
  if (status === 404) {
    process.stderr.write(
      t.muted(`relay at ${url} has no /context/injected — server predates the relay context layer.`) + '\n'
    )
    return 1
  }
  if (status !== 200) {
    process.stderr.write(t.err(`error: GET /context/injected returned ${status}`) + '\n')
    return 1
  }
  if (args.flags.json) {
    process.stdout.write(JSON.stringify(body, null, 2) + '\n')
    return 0
  }
  const r = (body ?? {}) as { enabled?: boolean; blocks?: { name?: string; text?: string }[] }
  const blocks = r.blocks ?? []
  process.stdout.write(t.bold(`Relay-injected agent context — ${url}`) + '\n')
  process.stdout.write(`  ${t.statusDot(!!r.enabled)} injection ${r.enabled ? t.ok('enabled') : t.muted('disabled')}\n`)
  if (blocks.length === 0) {
    process.stdout.write(t.muted('  (no context blocks are being injected into the agent prompt)') + '\n')
    return 0
  }
  process.stdout.write('\n')
  for (const b of blocks) {
    process.stdout.write(`  ${t.bold(b.name ?? '(unnamed)')}\n`)
    const text = (b.text ?? '').trim()
    const preview = text.length > 280 ? text.slice(0, 279) + '…' : text
    for (const line of preview.split('\n')) {
      process.stdout.write(`    ${t.muted(line)}\n`)
    }
  }
  return 0
}

export async function relayCommand(args: ParsedArgs): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  if (args.flags.help) {
    printUsage(RELAY_USAGE, t)
    return 0
  }
  const sub = args.positional[0] ?? 'info'
  const url = typeof args.flags.remote === 'string' ? args.flags.remote : undefined
  const run = async (fn: (a: ParsedArgs, th: Theme) => Promise<number>): Promise<number> => {
    try {
      return await fn(args, t)
    } catch (e) {
      process.stderr.write(formatError(e, { command: 'relay', url }, t) + '\n')
      return 1
    }
  }

  if (sub === 'info') {
    if (args.positional[0] === 'info') args.positional.shift()
    return run(relayInfo)
  }
  if (sub === 'security') {
    args.positional.shift()
    return run(relaySecurity)
  }
  if (sub === 'context') {
    args.positional.shift()
    return run(relayContext)
  }
  return unknownSubcommand(RELAY_USAGE, sub, t)
}

export default relayCommand

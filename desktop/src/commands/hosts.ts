import type { ParsedArgs } from '../cli.js'
import { getActiveDesktopRelayUrl, getDesktopHostAliases, setActiveDesktopRelayUrl, setDesktopHostAlias } from '../desktopConfig.js'
import {
  effectiveHostAccessMode,
  getHostCapabilityPolicies,
  getHostAccessMode,
  isHostAccessMode,
  setHostAccessMode,
  setHostCapabilityPolicy,
  HARDWARE_CAPABILITIES,
  CAPABILITY_ACCESS_MODES,
  type CapabilityPolicies,
  type HostAccessMode
} from '../lib/hostAccessPolicy.js'
import { theme as makeTheme } from '../lib/theme.js'
import { printUsage, type UsageSpec, unknownSubcommand } from '../lib/usage.js'
import { getSession, listSessions, saveSession } from '../remoteSessions.js'

const HOSTS_USAGE: UsageSpec = {
  name: 'hosts',
  summary: 'manage Hermes hosts paired with this PC',
  usage: [
    'hosts [list] [--json]',
    'hosts select <relay-url>',
    'hosts rename <relay-url> <name>',
    'hosts access <ask|structured|trusted|full-access> [--remote <url>] [--yes]',
    'hosts capability <usb|microphone|camera> <disabled|ask|allow> [--remote <url>] [--yes]'
  ],
  subcommands: [
    { verb: 'list', desc: 'List locally paired Hermes hosts (default)' },
    { verb: 'select <url>', desc: 'Choose the host used by the tray and daemon' },
    { verb: 'rename <url> <name>', desc: 'Set a local display name for a paired host' },
    { verb: 'access <mode>', desc: 'Set this PC access policy for one host' },
    { verb: 'capability <name> <mode>', desc: 'Set a brokered hardware capability policy' }
  ],
  flags: [
    { flag: '--remote <url>', desc: 'Host targeted by the access command' },
    { flag: '--json', desc: 'Emit machine-readable host state' },
    { flag: '--yes', desc: 'Confirm Full Access or hardware Allow non-interactively' }
  ],
  examples: [
    'hermes-relay hosts --json',
    'hermes-relay hosts select wss://home.example:8767',
    'hermes-relay hosts access structured --remote wss://home.example:8767',
    'hermes-relay hosts capability usb ask --remote wss://home.example:8767'
  ]
}

export interface LocalHostSummary {
  url: string
  host: string
  server_version: string | null
  endpoint_role: string | null
  paired_at: number
  is_active: boolean
  access_mode: HostAccessMode
  capabilities: CapabilityPolicies
}

function hostLabel(url: string): string {
  try {
    return new URL(url).hostname || url
  } catch {
    return url
  }
}

export function parseAccessMode(value: string | undefined): HostAccessMode | null {
  const normalized = value?.trim().toLowerCase().replaceAll('-', '_')
  return isHostAccessMode(normalized) ? normalized : null
}

async function localHosts(): Promise<LocalHostSummary[]> {
  const [sessions, active, aliases] = await Promise.all([listSessions(), getActiveDesktopRelayUrl(), getDesktopHostAliases()])
  return Promise.all(Object.entries(sessions).map(async ([url, session]) => ({
    url,
    host: aliases[url] ?? hostLabel(url),
    server_version: session.serverVersion,
    endpoint_role: session.endpointRole ?? null,
    paired_at: session.pairedAt,
    is_active: url === active,
    access_mode: effectiveHostAccessMode(
      await getHostAccessMode(url),
      session.toolsConsented === true
    ),
    capabilities: await getHostCapabilityPolicies(url)
  }))).then(hosts => hosts.sort((a, b) =>
    Number(b.is_active) - Number(a.is_active) || b.paired_at - a.paired_at || a.url.localeCompare(b.url)
  ))
}

async function renameHost(args: ParsedArgs): Promise<number> {
  const url = args.positional.shift()?.trim() ?? ''
  const name = args.positional.join(' ').trim()
  if (!url || !(await getSession(url))) {
    process.stderr.write('error: `hosts rename` requires a paired relay URL\n')
    return 1
  }
  if (!name || name.length > 64 || /[\r\n\t]/.test(name)) {
    process.stderr.write('error: host name must be 1-64 characters on one line\n')
    return 2
  }
  await setDesktopHostAlias(url, name)
  if (args.flags.json) process.stdout.write(JSON.stringify({ ok: true, url, host: name }) + '\n')
  else process.stdout.write(makeTheme({ noColor: !!args.flags['no-color'] }).okLine(`renamed host to ${name}`) + '\n')
  return 0
}

async function listHosts(args: ParsedArgs): Promise<number> {
  const hosts = await localHosts()
  if (args.flags.json) {
    process.stdout.write(JSON.stringify({ hosts }, null, 2) + '\n')
    return 0
  }
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  if (hosts.length === 0) {
    process.stdout.write(t.muted('No Hermes hosts are paired. Run `hermes-relay pair`.') + '\n')
    return 0
  }
  process.stdout.write(t.bold(`Paired Hermes hosts (${hosts.length})`) + '\n')
  for (const host of hosts) {
    process.stdout.write(
      `  ${host.is_active ? '*' : ' '} ${host.host}  ${host.access_mode.replace('_', '-')}\n` +
      t.muted(`      ${host.url}${host.endpoint_role ? ` (${host.endpoint_role})` : ''}`) + '\n'
    )
  }
  return 0
}

async function selectHost(args: ParsedArgs): Promise<number> {
  const url = args.positional[0]?.trim()
  if (!url) {
    process.stderr.write('error: `hosts select` requires a relay URL\n')
    return 2
  }
  if (!(await getSession(url))) {
    process.stderr.write(`error: ${url} is not paired on this PC\n`)
    return 1
  }
  await setActiveDesktopRelayUrl(url)
  if (args.flags.json) process.stdout.write(JSON.stringify({ ok: true, active_url: url }) + '\n')
  else process.stdout.write(makeTheme({ noColor: !!args.flags['no-color'] }).okLine(`selected host ${hostLabel(url)}`) + '\n')
  return 0
}

async function setAccess(args: ParsedArgs): Promise<number> {
  const mode = parseAccessMode(args.positional[0])
  if (!mode) {
    process.stderr.write('error: access mode must be ask, structured, trusted, or full-access\n')
    return 2
  }
  const requested = typeof args.flags.remote === 'string' ? args.flags.remote.trim() : ''
  const url = requested || await getActiveDesktopRelayUrl() || ''
  const session = url ? await getSession(url) : null
  if (!url || !session) {
    process.stderr.write('error: select or pass a locally paired Hermes host\n')
    return 1
  }
  if (mode === 'full_access' && args.flags.yes !== true) {
    process.stderr.write(
      'error: Full Access allows this host to use commands, files, screen, keyboard, and mouse without task grants. Pass --yes to confirm.\n'
    )
    return 2
  }

  const policy = await setHostAccessMode(url, mode)
  await saveSession(url, session.token, session.serverVersion, {
    pairedAt: session.pairedAt,
    toolsConsented: mode !== 'ask'
  })
  const payload = {
    ok: true,
    url,
    access_mode: policy.access_mode,
    restart_required: true
  }
  if (args.flags.json) process.stdout.write(JSON.stringify(payload, null, 2) + '\n')
  else {
    const t = makeTheme({ noColor: !!args.flags['no-color'] })
    process.stdout.write(t.okLine(`${hostLabel(url)} access set to ${mode.replace('_', '-')}`) + '\n')
    process.stdout.write(t.muted('Restart the daemon to apply this policy.') + '\n')
  }
  return 0
}

async function setCapability(args: ParsedArgs): Promise<number> {
  const capability = args.positional[0]?.trim().toLowerCase()
  const mode = args.positional[1]?.trim().toLowerCase()
  if (!HARDWARE_CAPABILITIES.includes(capability as typeof HARDWARE_CAPABILITIES[number])) {
    process.stderr.write('error: capability must be usb, microphone, or camera\n')
    return 2
  }
  if (!CAPABILITY_ACCESS_MODES.includes(mode as typeof CAPABILITY_ACCESS_MODES[number])) {
    process.stderr.write('error: capability mode must be disabled, ask, or allow\n')
    return 2
  }
  if (capability !== 'usb' && mode !== 'disabled') {
    process.stderr.write(`error: ${capability} brokering is not available yet; leave it disabled\n`)
    return 2
  }
  if (mode === 'allow' && args.flags.yes !== true) {
    process.stderr.write(`error: allowing ${capability} permits brokered hardware operations without per-task approval. Pass --yes to confirm.\n`)
    return 2
  }
  const requested = typeof args.flags.remote === 'string' ? args.flags.remote.trim() : ''
  const url = requested || await getActiveDesktopRelayUrl() || ''
  if (!url || !(await getSession(url))) {
    process.stderr.write('error: select or pass a locally paired Hermes host\n')
    return 1
  }
  const policy = await setHostCapabilityPolicy(
    url,
    capability as typeof HARDWARE_CAPABILITIES[number],
    mode as typeof CAPABILITY_ACCESS_MODES[number]
  )
  const payload = { ok: true, url, capability, mode, capabilities: policy.capabilities, restart_required: true }
  if (args.flags.json) process.stdout.write(JSON.stringify(payload, null, 2) + '\n')
  else {
    const t = makeTheme({ noColor: !!args.flags['no-color'] })
    process.stdout.write(t.okLine(`${hostLabel(url)} ${capability} access set to ${mode}`) + '\n')
    process.stdout.write(t.muted('Restart the daemon to apply this policy.') + '\n')
  }
  return 0
}

export async function hostsCommand(args: ParsedArgs): Promise<number> {
  if (args.flags.help) {
    printUsage(HOSTS_USAGE, makeTheme({ noColor: !!args.flags['no-color'] }))
    return 0
  }
  const subcommand = args.positional.shift() ?? 'list'
  if (subcommand === 'list') return listHosts(args)
  if (subcommand === 'select') return selectHost(args)
  if (subcommand === 'rename') return renameHost(args)
  if (subcommand === 'access') return setAccess(args)
  if (subcommand === 'capability') return setCapability(args)
  return unknownSubcommand(HOSTS_USAGE, subcommand, makeTheme({ noColor: !!args.flags['no-color'] }))
}

export default hostsCommand

import type { ParsedArgs } from '../cli.js'
import { getActiveDesktopRelayUrl, getDesktopHostAliases, setActiveDesktopRelayUrl, setDesktopHostAlias } from '../desktopConfig.js'
import {
  effectiveHostAccessMode,
  effectiveHostCapabilityPolicies,
  getHostCapabilityPolicies,
  getHostAccessMode,
  isHostAccessMode,
  setHostAccessMode,
  setHostCapabilityPolicy,
  HOST_CAPABILITIES,
  CAPABILITY_ACCESS_MODES,
  type CapabilityPolicies,
  type HostCapability,
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
    'hosts access <restricted|standard|full-access> [--remote <url>] [--yes]',
    'hosts capability <commands|files|screen-input|usb|microphone|camera> <disabled|ask|allow> [--remote <url>] [--yes]'
  ],
  subcommands: [
    { verb: 'list', desc: 'List locally paired Hermes hosts (default)' },
    { verb: 'select <url>', desc: 'Choose the host used by the tray and daemon' },
    { verb: 'rename <url> <name>', desc: 'Set a local display name for a paired host' },
    { verb: 'access <mode>', desc: 'Set a Restricted, Standard, or Full Access preset' },
    { verb: 'capability <name> <mode>', desc: 'Set one capability; creates a Custom policy' }
  ],
  flags: [
    { flag: '--remote <url>', desc: 'Host targeted by the access command' },
    { flag: '--json', desc: 'Emit machine-readable host state' },
    { flag: '--yes', desc: 'Confirm Full Access or hardware Allow non-interactively' }
  ],
  examples: [
    'hermes-relay hosts --json',
    'hermes-relay hosts select wss://home.example:8767',
    'hermes-relay hosts access standard --remote wss://home.example:8767',
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
  if (normalized === 'restricted') return 'ask'
  if (normalized === 'standard') return 'structured'
  return isHostAccessMode(normalized) && normalized !== 'custom' ? normalized : null
}

export function displayAccessMode(mode: HostAccessMode): string {
  if (mode === 'ask') return 'restricted'
  if (mode === 'structured') return 'standard'
  if (mode === 'trusted' || mode === 'custom') return 'custom'
  return 'full-access'
}

async function localHosts(): Promise<LocalHostSummary[]> {
  const [sessions, active, aliases] = await Promise.all([listSessions(), getActiveDesktopRelayUrl(), getDesktopHostAliases()])
  return Promise.all(Object.entries(sessions).map(async ([url, session]) => {
    const storedMode = await getHostAccessMode(url)
    const legacyConsented = session.toolsConsented === true
    return {
      url,
      host: aliases[url] ?? hostLabel(url),
      server_version: session.serverVersion,
      endpoint_role: session.endpointRole ?? null,
      paired_at: session.pairedAt,
      is_active: url === active,
      access_mode: effectiveHostAccessMode(storedMode, legacyConsented),
      capabilities: effectiveHostCapabilityPolicies(storedMode, legacyConsented, await getHostCapabilityPolicies(url))
    }
  })).then(hosts => hosts.sort((a, b) =>
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
      `  ${host.is_active ? '*' : ' '} ${host.host}  ${displayAccessMode(host.access_mode)}\n` +
      t.muted(`      ${host.url}${host.endpoint_role ? ` (${host.endpoint_role})` : ''}`) + '\n' +
      t.muted(`      Commands: ${host.capabilities.commands} · Files: ${host.capabilities.files} · Screen/input: ${host.capabilities.screen_input} · USB: ${host.capabilities.usb}`) + '\n'
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
    process.stderr.write('error: access mode must be restricted, standard, or full-access\n')
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
      'error: Full Access allows every available capability, including commands, files, screen, input, and Raw USB, without task grants. Pass --yes to confirm.\n'
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
    process.stdout.write(t.okLine(`${hostLabel(url)} access set to ${displayAccessMode(mode)}`) + '\n')
    process.stdout.write(t.muted('Restart the daemon to apply this policy.') + '\n')
  }
  return 0
}

async function setCapability(args: ParsedArgs): Promise<number> {
  const capability = args.positional[0]?.trim().toLowerCase()
  const mode = args.positional[1]?.trim().toLowerCase()
  const normalizedCapability = capability?.replaceAll('-', '_') as HostCapability | undefined
  if (!normalizedCapability || !HOST_CAPABILITIES.includes(normalizedCapability)) {
    process.stderr.write('error: capability must be commands, files, screen-input, usb, microphone, or camera\n')
    return 2
  }
  if (!CAPABILITY_ACCESS_MODES.includes(mode as typeof CAPABILITY_ACCESS_MODES[number])) {
    process.stderr.write('error: capability mode must be disabled, ask, or allow\n')
    return 2
  }
  if ((normalizedCapability === 'microphone' || normalizedCapability === 'camera') && mode !== 'disabled') {
    process.stderr.write(`error: ${normalizedCapability} brokering is not available yet; leave it disabled\n`)
    return 2
  }
  if (mode === 'allow' && args.flags.yes !== true) {
    process.stderr.write(`error: allowing ${normalizedCapability.replace('_', '/')} lets this host use that capability without per-operation approval. Pass --yes to confirm.\n`)
    return 2
  }
  const requested = typeof args.flags.remote === 'string' ? args.flags.remote.trim() : ''
  const url = requested || await getActiveDesktopRelayUrl() || ''
  const session = url ? await getSession(url) : null
  if (!url || !session) {
    process.stderr.write('error: select or pass a locally paired Hermes host\n')
    return 1
  }
  const policy = await setHostCapabilityPolicy(
    url,
    normalizedCapability,
    mode as typeof CAPABILITY_ACCESS_MODES[number]
  )
  await saveSession(url, session.token, session.serverVersion, {
    pairedAt: session.pairedAt,
    toolsConsented: Object.values(policy.capabilities).some(value => value !== 'disabled')
  })
  const payload = { ok: true, url, access_mode: policy.access_mode, capability: normalizedCapability, mode, capabilities: policy.capabilities, restart_required: true }
  if (args.flags.json) process.stdout.write(JSON.stringify(payload, null, 2) + '\n')
  else {
    const t = makeTheme({ noColor: !!args.flags['no-color'] })
    const label = normalizedCapability === 'usb' ? 'Raw USB' : normalizedCapability.replace('_', '/')
    process.stdout.write(t.okLine(`${hostLabel(url)} ${label} access set to ${mode} (Custom)`) + '\n')
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

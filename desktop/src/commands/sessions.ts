import type { ParsedArgs } from '../cli.js'
import { renderTable } from '../lib/table.js'
import { theme as makeTheme } from '../lib/theme.js'
import { printUsage, unknownSubcommand, type UsageSpec } from '../lib/usage.js'
import {
  clearActiveTerminalSession,
  getActiveTerminalSession
} from '../terminalSessionStore.js'
import { connectAndAuth, shellCommand } from './shell.js'

const COMMAND_TIMEOUT_MS = 15_000

const SESSIONS_USAGE: UsageSpec = {
  name: 'sessions',
  summary: 'list / resume / create / kill the relay-side Hermes TUI tmux sessions',
  usage: [
    'sessions [list]',
    'sessions resume [name]',
    'sessions new [name]',
    'sessions kill <name>'
  ],
  subcommands: [
    { verb: 'list', desc: 'List live TUI sessions (default)' },
    { verb: 'resume [name]', desc: 'Attach a shell (active/default if name omitted)' },
    { verb: 'new [name]', desc: 'Start a fresh session and attach' },
    { verb: 'kill <name>', desc: 'Terminate a tmux session' }
  ],
  flags: [
    { flag: '--remote <url>', desc: 'Relay to target' },
    { flag: '--json', desc: 'Machine-readable list output' }
  ],
  examples: [
    'hermes-relay sessions',
    'hermes-relay sessions resume default',
    'hermes-relay sessions kill scratch'
  ]
}

interface TerminalSessionInfo {
  name: string
  tmux_name?: string
  pid?: number
  shell?: string
  attached?: number
  windows?: number
  created_at?: number
  live?: boolean
  owned_by_client?: boolean
}

function asTerminalSession(raw: unknown): TerminalSessionInfo | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return null
  }
  const item = raw as Record<string, unknown>
  const name = typeof item.name === 'string' ? item.name : ''
  if (!name) {
    return null
  }
  return {
    name,
    tmux_name: typeof item.tmux_name === 'string' ? item.tmux_name : undefined,
    pid: typeof item.pid === 'number' ? item.pid : undefined,
    shell: typeof item.shell === 'string' ? item.shell : undefined,
    attached: typeof item.attached === 'number' ? item.attached : undefined,
    windows: typeof item.windows === 'number' ? item.windows : undefined,
    created_at: typeof item.created_at === 'number' ? item.created_at : undefined,
    live: typeof item.live === 'boolean' ? item.live : undefined,
    owned_by_client: typeof item.owned_by_client === 'boolean' ? item.owned_by_client : undefined
  }
}

function waitForSessions(
  relay: import('../transport/RelayTransport.js').RelayTransport
): Promise<TerminalSessionInfo[]> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      relay.onChannel('terminal', null)
      reject(new Error(`terminal.sessions timeout after ${COMMAND_TIMEOUT_MS}ms`))
    }, COMMAND_TIMEOUT_MS)
    timer.unref?.()

    relay.onChannel('terminal', (type, payload) => {
      if (type === 'terminal.sessions') {
        clearTimeout(timer)
        relay.onChannel('terminal', null)
        const sessions = Array.isArray(payload.sessions)
          ? payload.sessions.map(asTerminalSession).filter((item): item is TerminalSessionInfo => item !== null)
          : []
        resolve(sessions)
        return
      }
      if (type === 'terminal.error') {
        clearTimeout(timer)
        relay.onChannel('terminal', null)
        reject(new Error(typeof payload.message === 'string' ? payload.message : 'terminal error'))
      }
    })
  })
}

function waitForDetached(
  relay: import('../transport/RelayTransport.js').RelayTransport
): Promise<string> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      relay.onChannel('terminal', null)
      reject(new Error(`terminal.kill timeout after ${COMMAND_TIMEOUT_MS}ms`))
    }, COMMAND_TIMEOUT_MS)
    timer.unref?.()

    relay.onChannel('terminal', (type, payload) => {
      if (type === 'terminal.detached') {
        clearTimeout(timer)
        relay.onChannel('terminal', null)
        resolve(typeof payload.session_name === 'string' ? payload.session_name : '')
        return
      }
      if (type === 'terminal.error') {
        clearTimeout(timer)
        relay.onChannel('terminal', null)
        reject(new Error(typeof payload.message === 'string' ? payload.message : 'terminal error'))
      }
    })
  })
}

function formatCreated(value: number | undefined): string {
  if (!value) {
    return 'unknown'
  }
  return new Date(value * 1000).toLocaleString()
}

async function listTerminalSessions(args: ParsedArgs): Promise<number> {
  const authed = await connectAndAuth({
    ...args,
    flags: { ...args.flags, 'non-interactive': args.flags['non-interactive'] ?? true }
  })
  const { relay, url } = authed
  try {
    const wait = waitForSessions(relay)
    relay.sendChannel('terminal', 'terminal.list', {})
    const sessions = (await wait).sort((a, b) => a.name.localeCompare(b.name))
    const active = await getActiveTerminalSession(url)

    if (args.flags.json) {
      process.stdout.write(JSON.stringify({ url, active, sessions }, null, 2) + '\n')
      return 0
    }

    const t = makeTheme({ noColor: !!args.flags['no-color'] })
    if (sessions.length === 0) {
      process.stdout.write(t.muted(`No Hermes TUI sessions on ${url}.`) + '\n')
      process.stdout.write(t.muted('Run `hermes-relay` to start the default session.') + '\n')
      return 0
    }

    const rows = sessions.map((session) => {
      const activeMark = active?.name === session.name ? ` ${t.muted('(active)')}` : ''
      const attached = session.attached ?? (session.live ? 1 : 0)
      return [
        `${session.name}${activeMark}`,
        session.tmux_name ?? `hermes-${session.name}`,
        String(attached),
        session.windows !== undefined ? String(session.windows) : '—',
        formatCreated(session.created_at)
      ]
    })
    process.stdout.write(t.bold(`Hermes TUI sessions on ${url} (${sessions.length})`) + '\n\n')
    process.stdout.write(
      renderTable(
        [
          { header: 'NAME' },
          { header: 'TMUX' },
          { header: 'ATTACHED', align: 'right' },
          { header: 'WINDOWS', align: 'right' },
          { header: 'CREATED' }
        ],
        rows,
        { theme: t }
      ) + '\n'
    )
    process.stdout.write(
      '\n' +
        t.muted('resume: hermes-relay sessions resume <name>    or run bare `hermes-relay` for the active session.') +
        '\n'
    )
    return 0
  } finally {
    relay.kill()
  }
}

async function killTerminalSession(args: ParsedArgs): Promise<number> {
  const name = args.positional[0]
  if (!name) {
    process.stderr.write('error: `sessions kill` needs a tmux session name. Run `hermes-relay sessions list` first.\n')
    return 2
  }
  const authed = await connectAndAuth({
    ...args,
    flags: { ...args.flags, 'non-interactive': args.flags['non-interactive'] ?? true }
  })
  const { relay, url } = authed
  try {
    const wait = waitForDetached(relay)
    relay.sendChannel('terminal', 'terminal.kill', { session_name: name })
    await wait
    await clearActiveTerminalSession(url, name)
    process.stdout.write(`Killed Hermes TUI session "${name}" on ${url}.\n`)
    return 0
  } catch (e) {
    process.stderr.write(`error: ${e instanceof Error ? e.message : String(e)}\n`)
    return 1
  } finally {
    relay.kill()
  }
}

export async function sessionsCommand(args: ParsedArgs): Promise<number> {
  if (args.flags.help) {
    printUsage(SESSIONS_USAGE)
    return 0
  }
  const sub = args.positional[0] ?? 'list'

  if (sub === 'list') {
    if (args.positional[0] === 'list') {
      args.positional.shift()
    }
    try {
      return await listTerminalSessions(args)
    } catch (e) {
      process.stderr.write(`error: ${e instanceof Error ? e.message : String(e)}\n`)
      return 1
    }
  }

  if (sub === 'resume') {
    args.positional.shift()
    const name = args.positional[0]
    return shellCommand({
      ...args,
      command: 'shell',
      flags: name ? { ...args.flags, session: name } : { ...args.flags }
    })
  }

  if (sub === 'new') {
    args.positional.shift()
    const name = args.positional[0]
    return shellCommand({
      ...args,
      command: 'shell',
      flags: name ? { ...args.flags, new: true, session: name } : { ...args.flags, new: true }
    })
  }

  if (sub === 'kill') {
    args.positional.shift()
    return killTerminalSession(args)
  }

  return unknownSubcommand(SESSIONS_USAGE, sub)
}

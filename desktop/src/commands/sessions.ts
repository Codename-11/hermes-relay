import type { ParsedArgs } from '../cli.js'
import {
  clearActiveTerminalSession,
  getActiveTerminalSession
} from '../terminalSessionStore.js'
import { connectAndAuth, shellCommand } from './shell.js'

const COMMAND_TIMEOUT_MS = 15_000

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

    if (sessions.length === 0) {
      process.stdout.write(`No Hermes TUI sessions on ${url}.\n`)
      process.stdout.write('Run `hermes-relay` to start the default session.\n')
      return 0
    }

    process.stdout.write(`Hermes TUI sessions on ${url}:\n\n`)
    for (const session of sessions) {
      const activeMark = active?.name === session.name ? ' * active' : ''
      const attached = session.attached ?? (session.live ? 1 : 0)
      process.stdout.write(`  ${session.name}${activeMark}\n`)
      process.stdout.write(`      tmux:     ${session.tmux_name ?? `hermes-${session.name}`}\n`)
      process.stdout.write(`      attached: ${attached}\n`)
      if (session.windows !== undefined) {
        process.stdout.write(`      windows:  ${session.windows}\n`)
      }
      process.stdout.write(`      created:  ${formatCreated(session.created_at)}\n\n`)
    }
    process.stdout.write(
      '  Resume with `hermes-relay sessions resume <name>`, or run bare `hermes-relay` for the active/default session.\n'
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

  process.stderr.write('unknown sessions sub-verb. Try: list | resume [name] | new [name] | kill <name>\n')
  return 2
}

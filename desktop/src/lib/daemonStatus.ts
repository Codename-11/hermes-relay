// Daemon status file — make the headless daemon observable.
//
// The daemon parks forever and logs JSON lines to stderr; once it's a service,
// there's no easy "is it alive and connected right now?" check without tailing
// journald. This writes a small heartbeat file at ~/.hermes/daemon-status.json
// that `hermes-relay daemon --status` reads — uptime, connection state, server
// version, advertised-tool count. Mirrors the existing desktop-voice.json
// discovery-file pattern in daemon.ts.

import { promises as fs } from 'node:fs'
import { homedir } from 'node:os'
import { basename, dirname, join } from 'node:path'
import { execFileSync } from 'node:child_process'

import type { ProcessPrivilege } from './processPrivilege.js'
import type { HostAccessMode } from './hostAccessPolicy.js'
import type { ComputerControlLifecycleStatus } from '../tools/cuaDriver.js'

export type DaemonState = 'starting' | 'connected' | 'reconnecting' | 'stopped'

export interface DaemonComputerGrantStatus {
  active: boolean
  mode: string
  expires_at: string | null
  reason?: string
}

export interface DaemonStatus {
  pid: number
  /** Executable image recorded by the daemon. Used to reject a recycled PID
   * before status/stop treats an unrelated process as Hermes Relay. */
  process_name?: string
  url: string
  /** Logical host selected by the user when `url` is a resolved fallback route. */
  configured_url?: string
  /** Wire route role selected for the current connection. */
  active_route?: string
  state: DaemonState
  /** Epoch seconds. */
  started_at: number
  /** Epoch seconds — bumped on every state change + a periodic heartbeat so
   * a reader can tell a live daemon from a crashed one whose file lingers. */
  updated_at: number
  server_version?: string | null
  advertised_tools?: number
  voice_url?: string | null
  last_event?: string
  username?: string
  privilege?: ProcessPrivilege
  computer_use_enabled?: boolean
  access_mode?: HostAccessMode
  tools_enabled?: boolean
  computer_grant?: DaemonComputerGrantStatus
  computer_control?: ComputerControlLifecycleStatus
}

export function daemonStatusPath(): string {
  return join(homedir(), '.hermes', 'daemon-status.json')
}

export async function writeDaemonStatus(status: DaemonStatus): Promise<void> {
  const filePath = daemonStatusPath()
  try {
    await fs.mkdir(dirname(filePath), { recursive: true })
    await fs.writeFile(filePath, JSON.stringify(status, null, 2) + '\n', { mode: 0o600 })
  } catch {
    // Best-effort — never let status bookkeeping take down the daemon.
  }
}

export async function readDaemonStatus(): Promise<DaemonStatus | null> {
  try {
    const text = await fs.readFile(daemonStatusPath(), 'utf8')
    return JSON.parse(text) as DaemonStatus
  } catch {
    return null
  }
}

export async function clearDaemonStatus(): Promise<void> {
  try {
    await fs.unlink(daemonStatusPath())
  } catch {
    /* missing — fine */
  }
}

/** Is a process with this pid currently alive? `kill(pid, 0)` sends no signal
 * but throws ESRCH when the pid is gone — the standard cross-platform liveness
 * probe (works on Windows too via libuv). EPERM means alive-but-not-ours. */
export function isPidAlive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch (e) {
    return (e as NodeJS.ErrnoException)?.code === 'EPERM'
  }
}

function normalizeProcessName(value: string): string {
  return basename(value.trim()).replace(/\.exe$/i, '').toLocaleLowerCase('en-US')
}

function processNameForPid(pid: number): string | null {
  try {
    if (process.platform === 'win32') {
      const output = execFileSync(
        'tasklist.exe',
        ['/FI', `PID eq ${pid}`, '/FO', 'CSV', '/NH'],
        { encoding: 'utf8', windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] }
      )
      return /^"([^"]+)"/.exec(output.trim())?.[1] ?? null
    }
    const output = execFileSync('ps', ['-p', String(pid), '-o', 'comm='], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore']
    })
    return output.trim() || null
  } catch {
    return null
  }
}

/** Validate both PID existence and executable identity. PID-only probes are
 * unsafe for status/stop because operating systems can recycle a daemon PID
 * for an unrelated process immediately after a crash. */
export function isDaemonProcessAlive(
  status: Pick<DaemonStatus, 'pid' | 'process_name'>,
  lookup: (pid: number) => string | null = processNameForPid
): boolean {
  if (!isPidAlive(status.pid)) return false
  const actual = lookup(status.pid)
  if (!actual) return false
  const expected = status.process_name ?? basename(process.execPath)
  return normalizeProcessName(actual) === normalizeProcessName(expected)
}

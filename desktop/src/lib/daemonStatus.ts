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
import { dirname, join } from 'node:path'

export type DaemonState = 'starting' | 'connected' | 'reconnecting' | 'stopped'

export interface DaemonStatus {
  pid: number
  url: string
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

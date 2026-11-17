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
import { basename, dirname, join, win32 } from 'node:path'
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
  reconnect_attempt?: number | null
  retry_at?: number | null
  last_error?: string | null
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

let statusWriteChain: Promise<void> = Promise.resolve()

export async function writeDaemonStatus(status: DaemonStatus): Promise<void> {
  const filePath = daemonStatusPath()
  const snapshot = JSON.stringify(status, null, 2) + '\n'
  statusWriteChain = statusWriteChain.then(async () => {
    const temporaryPath = `${filePath}.${process.pid}.${Date.now()}.tmp`
    try {
      await fs.mkdir(dirname(filePath), { recursive: true })
      await fs.writeFile(temporaryPath, snapshot, { mode: 0o600 })
      await fs.rename(temporaryPath, filePath)
    } catch {
      // Best-effort — never let status bookkeeping take down the daemon.
      await fs.unlink(temporaryPath).catch(() => undefined)
    }
  })
  await statusWriteChain
}

export async function readDaemonStatus(): Promise<DaemonStatus | null> {
  try {
    const text = await fs.readFile(daemonStatusPath(), 'utf8')
    return JSON.parse(text) as DaemonStatus
  } catch {
    return null
  }
}

export async function clearDaemonStatus(expectedPid?: number): Promise<void> {
  await statusWriteChain
  try {
    if (expectedPid !== undefined) {
      const current = await readDaemonStatus()
      if (current?.pid !== expectedPid) return
    }
    await fs.unlink(daemonStatusPath())
  } catch {
    /* missing — fine */
  }
}

export type WindowsProcessSnapshot =
  | { state: 'gone' }
  | { state: 'unknown' }
  | { state: 'alive'; name: string; executablePath: string | null; startedAt: number | null }

const WINDOWS_PROCESS_MARKER = 'HERMES_RELAY_PID_V1:'
const WINDOWS_PROCESS_CACHE_MS = 1_000
const windowsProcessCache = new Map<number, { at: number; snapshot: WindowsProcessSnapshot }>()

/** Only an exact, successful native marker can establish absence. Localized
 * diagnostics, empty output, access errors and malformed replies stay unknown. */
export function parseWindowsProcessSnapshot(output: string): WindowsProcessSnapshot {
  const lines = output.trim().split(/\r?\n/)
  if (lines.length !== 1 || !lines[0]?.startsWith(WINDOWS_PROCESS_MARKER)) return { state: 'unknown' }
  try {
    const raw = JSON.parse(lines[0].slice(WINDOWS_PROCESS_MARKER.length)) as Record<string, unknown>
    if (raw.state === 'gone') return { state: 'gone' }
    if (raw.state !== 'alive' || typeof raw.name !== 'string' || !raw.name.trim()) return { state: 'unknown' }
    return {
      state: 'alive', name: raw.name.trim(),
      executablePath: typeof raw.executablePath === 'string' && raw.executablePath ? raw.executablePath : null,
      startedAt: typeof raw.startedAt === 'number' && Number.isFinite(raw.startedAt) && raw.startedAt > 0 ? raw.startedAt : null
    }
  } catch { return { state: 'unknown' } }
}

export function windowsProcessProbeScript(pid: number): string {
  if (!Number.isSafeInteger(pid) || pid <= 0) throw new Error('invalid process id')
  return [
    "$ErrorActionPreference='Stop'",
    '[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)',
    "$snapshot=@{state='unknown'};$p=$null",
    'try {',
    `$p=[Diagnostics.Process]::GetProcessById(${pid})`,
    "if($p.HasExited){$snapshot=@{state='gone'}}else{",
    "$snapshot=@{state='alive';name=$p.ProcessName;executablePath=$null;startedAt=$null}",
    'try {$snapshot.executablePath=$p.MainModule.FileName} catch {}',
    'try {$snapshot.startedAt=([DateTimeOffset]$p.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()} catch {}',
    '}',
    "} catch [ArgumentException] {$snapshot=@{state='gone'}} catch {$snapshot=@{state='unknown'}}",
    'finally {if($p){$p.Dispose()}}',
    `Write-Output ('${WINDOWS_PROCESS_MARKER}'+($snapshot|ConvertTo-Json -Compress))`
  ].join('\n')
}

export function queryWindowsProcessSnapshot(pid: number): WindowsProcessSnapshot {
  if (!Number.isSafeInteger(pid) || pid <= 0) return { state: 'unknown' }
  const script = windowsProcessProbeScript(pid)
  try {
    return parseWindowsProcessSnapshot(execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], {
      encoding: 'utf8', windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'], timeout: 2_000, maxBuffer: 16_384
    }))
  } catch { return { state: 'unknown' } }
}

export function windowsProcessSnapshot(pid: number, refresh = false): WindowsProcessSnapshot {
  const now = Date.now()
  const cached = windowsProcessCache.get(pid)
  if (!refresh && cached && now - cached.at < WINDOWS_PROCESS_CACHE_MS) return cached.snapshot
  const snapshot = queryWindowsProcessSnapshot(pid)
  // Never cache absence: a recycled PID may already belong to a new process.
  if (snapshot.state === 'gone') windowsProcessCache.delete(pid)
  else {
    if (windowsProcessCache.size >= 256) windowsProcessCache.delete(windowsProcessCache.keys().next().value!)
    windowsProcessCache.set(pid, { at: Date.now(), snapshot })
  }
  return snapshot
}

export interface ProcessOwnerIdentity {
  pid: number
  process_name: string
  executable_path?: string
  process_started_at?: number
}

/** Unknown identity/permission failures hold the lock. A successful native
 * absence or a positively different owner is required before reclamation. */
export function windowsProcessMatchesOwner(owner: ProcessOwnerIdentity, snapshot: WindowsProcessSnapshot): boolean {
  if (snapshot.state === 'unknown') return true
  if (snapshot.state === 'gone') return false
  if (normalizeProcessName(snapshot.name) !== normalizeProcessName(owner.process_name)) return false
  if (owner.executable_path && snapshot.executablePath &&
      win32.normalize(owner.executable_path).toLowerCase() !== win32.normalize(snapshot.executablePath).toLowerCase()) return false
  if (owner.process_started_at !== undefined && snapshot.startedAt !== null &&
      owner.process_started_at !== snapshot.startedAt) return false
  return true
}

export function processOwnerIsAlive(owner: ProcessOwnerIdentity): boolean {
  if (process.platform !== 'win32') return isPidAlive(owner.pid)
  const snapshot = windowsProcessSnapshot(owner.pid)
  if (windowsProcessMatchesOwner(owner, snapshot)) return true
  // A cached positive snapshot can refer to the previous occupant of this PID.
  // Recheck every decision that would reclaim a lock using a fresh OS lookup.
  return windowsProcessMatchesOwner(owner, windowsProcessSnapshot(owner.pid, true))
}

/** Windows uses an explicit OS exit-state query. POSIX kill(pid, 0) sends no
 * signal; ESRCH means gone and EPERM means alive-but-not-ours. */
export function isPidAlive(pid: number): boolean {
  if (process.platform === 'win32') return windowsProcessSnapshot(pid).state !== 'gone'
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
      const snapshot = windowsProcessSnapshot(pid)
      return snapshot.state === 'alive' ? snapshot.name : null
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

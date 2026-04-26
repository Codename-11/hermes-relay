// Process management handlers for the `desktop` relay channel.
//
//   desktop_spawn_detached    — fire-and-forget process; returns {pid, log_path}
//   desktop_list_processes    — filter by command substring
//   desktop_kill_process      — by pid (preferred) or by name (best-effort)
//   desktop_find_pid_by_port  — port → owning pid (loopback IPv4 + IPv6)
//
// Why a separate handler from `desktop_terminal`:
//   The user's biggest pain point was `desktop_terminal` timing out (or 502'ing)
//   when launching long-lived processes. The router enforces a 30s ceiling on
//   every command, so a `gradle assembleDebug` or `comfyui run` exceeded it
//   even though the underlying process was healthy. `desktop_spawn_detached`
//   returns within ~10ms — the child outlives the RPC, writes its own log,
//   and the agent polls `desktop_job_status`/`desktop_job_logs` (jobs.ts) or
//   inspects via `desktop_list_processes` afterward.
//
// Everything else here mirrors what a sysadmin would do at a shell — but
// typed, bounded, and platform-uniform so the LLM doesn't have to remember
// `tasklist` vs `ps` vs `lsof`.

import { spawn, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, openSync, writeFileSync } from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

import type { ToolHandler } from '../router.js'

/** Where detached-process logs land. ~/.hermes/desktop-jobs/<id>/ — shared
 * with the Job API in jobs.ts so a spawn that the agent later wants to
 * promote to a "job" already has its log file in the right place. */
function jobsRoot(): string {
  return path.join(os.homedir(), '.hermes', 'desktop-jobs')
}

function ensureJobsRoot(): string {
  const root = jobsRoot()
  mkdirSync(root, { recursive: true })
  return root
}

function argString(v: unknown, name: string): string {
  if (typeof v !== 'string' || v.length === 0) {
    throw new Error(`missing or invalid "${name}" argument`)
  }
  return v
}

function argOptionalString(v: unknown): string | null {
  if (typeof v === 'string' && v.length > 0) {
    return v
  }
  return null
}

function argNumber(v: unknown, fallback: number, min = 0): number {
  if (typeof v === 'number' && Number.isFinite(v) && v >= min) {
    return Math.floor(v)
  }
  return fallback
}

function shortId(): string {
  // 12-hex-char identifier — collision-resistant enough for jobs we expect
  // to see in a single workstation, and short enough to type / log compactly.
  return [...crypto.getRandomValues(new Uint8Array(6))]
    .map(b => b.toString(16).padStart(2, '0'))
    .join('')
}

// ── desktop_spawn_detached ──────────────────────────────────────────────────

/** Launch a long-lived process detached from the relay's 30s RPC window.
 *
 * args: { command: string,            // single command line, parsed via shell
 *         cwd?: string,
 *         env?: Record<string,string>,
 *         job_id?: string,            // optional caller-supplied id (defaults to a uuid-ish)
 *         log_dir?: string }          // override the default ~/.hermes/desktop-jobs/<id>
 *
 * returns: { pid, job_id, log_path, started_at_ms, command, cwd, shell }
 *
 * The child is spawned through a shell (`bash -lc` / `cmd /c`) so the agent
 * can pass `npm run build` without parsing it itself. stdio is redirected to
 * a single combined log file (stdout + stderr interleaved) — easier to reason
 * about than two separate streams when polling later. The Job API (jobs.ts)
 * uses separate stdout/stderr files, so prefer that when you want stream
 * separation. */
export const spawnDetachedHandler: ToolHandler = async args => {
  const command = argString(args.command, 'command')
  const cwd = argOptionalString(args.cwd) ?? process.cwd()
  const jobId = argOptionalString(args.job_id) ?? shortId()
  const envOverride = (args.env as Record<string, string> | undefined) ?? null

  const dir =
    argOptionalString(args.log_dir) ??
    path.join(ensureJobsRoot(), jobId)
  mkdirSync(dir, { recursive: true })

  const logPath = path.join(dir, 'combined.log')
  const metaPath = path.join(dir, 'meta.json')

  // Open the log file synchronously so we get an fd we can hand to spawn().
  // 'a' so multiple invocations against the same log_dir append rather than
  // truncate (rare, but sane default for re-runs).
  const fd = openSync(logPath, 'a')

  const isWin = process.platform === 'win32'
  const shellName = isWin ? 'cmd' : 'bash'
  const shellArgs = isWin ? ['/c', command] : ['-lc', command]

  const env = envOverride
    ? { ...process.env, ...envOverride }
    : process.env

  let pid: number
  try {
    const child = spawn(shellName, shellArgs, {
      cwd,
      env,
      detached: true,
      stdio: ['ignore', fd, fd],
      windowsHide: true
    })
    pid = child.pid ?? -1
    // Critical: unref so the parent (this Node process) can exit even
    // while the child runs. Without this, the relay can't ever shut down
    // cleanly while a long job is in flight.
    child.unref()
    // Don't keep our pipe to stderr / waiting on close; we just need the pid.
  } catch (e) {
    throw new Error(`spawn failed: ${e instanceof Error ? e.message : String(e)}`)
  }

  if (pid <= 0) {
    throw new Error('spawn returned no pid (process may have failed to start)')
  }

  const meta = {
    job_id: jobId,
    pid,
    command,
    cwd,
    shell: shellName,
    log_path: logPath,
    started_at_ms: Date.now(),
    detached: true
  }
  // Best-effort meta dump — the Job API also writes meta.json with more
  // fields, but a bare detached spawn still benefits from leaving a
  // breadcrumb on disk so the user can correlate the pid later.
  try {
    writeFileSync(metaPath, JSON.stringify(meta, null, 2))
  } catch {
    /* ignore — log file is the source of truth, meta is convenience */
  }

  return meta
}

// ── desktop_list_processes ──────────────────────────────────────────────────

interface ProcInfo {
  pid: number
  ppid?: number
  user?: string
  command: string
  cpu_pct?: number
  mem_mb?: number
}

/** Run a probe synchronously and return its trimmed stdout. We use sync
 * here because the listing tools are short-lived (<1s typical) and the
 * router already gives us a 30s budget. Async would force an extra layer
 * of Promise plumbing for no real benefit. */
function runSync(cmd: string, args: string[]): { stdout: string; ok: boolean } {
  try {
    const r = spawnSync(cmd, args, {
      encoding: 'utf8',
      windowsHide: true,
      maxBuffer: 16 * 1024 * 1024
    })
    if (r.error || r.status !== 0) {
      return { stdout: r.stdout ?? '', ok: false }
    }
    return { stdout: r.stdout ?? '', ok: true }
  } catch {
    return { stdout: '', ok: false }
  }
}

/** Parse `ps -eo pid,ppid,user,pcpu,rss,command` (POSIX). RSS is in KB. */
function parsePosixPs(stdout: string): ProcInfo[] {
  const lines = stdout.split('\n').slice(1) // drop header
  const out: ProcInfo[] = []
  for (const raw of lines) {
    const line = raw.trim()
    if (!line) {
      continue
    }
    // Five whitespace-separated fields, then command (which may contain spaces)
    const m = /^(\d+)\s+(\d+)\s+(\S+)\s+([\d.]+)\s+(\d+)\s+(.*)$/.exec(line)
    if (!m) {
      continue
    }
    out.push({
      pid: parseInt(m[1]!, 10),
      ppid: parseInt(m[2]!, 10),
      user: m[3]!,
      cpu_pct: parseFloat(m[4]!),
      mem_mb: Math.round(parseInt(m[5]!, 10) / 1024),
      command: m[6]!
    })
  }
  return out
}

/** Parse Windows `tasklist /FO CSV` output. Quoted CSV with comma
 * separators; we use the non-verbose form on purpose — `/V` reads each
 * process's window title (and user) which can take 30+ seconds on hosts
 * with many GUI windows. The trade-off is that we don't get the User
 * column, but agents almost never need it. */
function parseWindowsTasklist(stdout: string): ProcInfo[] {
  const lines = stdout.split(/\r?\n/).filter(Boolean)
  if (lines.length < 2) {
    return []
  }
  const out: ProcInfo[] = []
  // Skip header line (lines[0]).
  for (let i = 1; i < lines.length; i++) {
    const fields = parseCsvLine(lines[i]!)
    if (fields.length < 5) {
      continue
    }
    // CSV columns from `tasklist /FO CSV`:
    //   0:Image Name, 1:PID, 2:Session Name, 3:Session#, 4:Mem Usage
    const pid = parseInt(fields[1] ?? '', 10)
    if (!Number.isFinite(pid)) {
      continue
    }
    const memKb = parseInt((fields[4] ?? '').replace(/[^\d]/g, ''), 10)
    out.push({
      pid,
      command: fields[0] ?? '',
      mem_mb: Number.isFinite(memKb) ? Math.round(memKb / 1024) : undefined
    })
  }
  return out
}

/** Tiny RFC-4180-ish CSV line parser — `tasklist` quotes every field, so we
 * just walk the line tracking quote state. Doubled `""` inside a quoted
 * field unquotes to `"`. */
function parseCsvLine(line: string): string[] {
  const out: string[] = []
  let cur = ''
  let inQuote = false
  let i = 0
  while (i < line.length) {
    const c = line[i]!
    if (inQuote) {
      if (c === '"') {
        if (line[i + 1] === '"') {
          cur += '"'
          i += 2
          continue
        }
        inQuote = false
        i++
        continue
      }
      cur += c
      i++
      continue
    }
    if (c === ',') {
      out.push(cur)
      cur = ''
      i++
      continue
    }
    if (c === '"') {
      inQuote = true
      i++
      continue
    }
    cur += c
    i++
  }
  out.push(cur)
  return out
}

/** desktop_list_processes
 *   args: { filter?: string, limit?: number (default 200) }
 *   returns: { processes: ProcInfo[], truncated: boolean, count: number }
 *
 * Filter is a case-insensitive substring match against the command field.
 * Empty / missing filter returns everything (subject to limit). */
export const listProcessesHandler: ToolHandler = async args => {
  const filter = (argOptionalString(args.filter) ?? '').toLowerCase()
  const limit = argNumber(args.limit, 200, 1)

  let procs: ProcInfo[] = []
  if (process.platform === 'win32') {
    // Non-verbose only — see parseWindowsTasklist comment. /V can take
    // tens of seconds on a host with many GUI windows.
    const r = runSync('tasklist', ['/FO', 'CSV'])
    procs = parseWindowsTasklist(r.stdout)
  } else {
    const r = runSync('ps', ['-eo', 'pid,ppid,user,pcpu,rss,command'])
    procs = parsePosixPs(r.stdout)
  }

  const filtered = filter
    ? procs.filter(p => p.command.toLowerCase().includes(filter))
    : procs

  const out = filtered.slice(0, limit)
  return {
    processes: out,
    truncated: filtered.length > limit,
    count: out.length,
    total: filtered.length
  }
}

// ── desktop_kill_process ────────────────────────────────────────────────────

/** desktop_kill_process
 *   args: { pid?: number, name?: string,
 *           signal?: 'TERM' | 'KILL' (default 'TERM'),
 *           force?: boolean (default false) }
 *   returns: { killed: number[], failed: {pid: number, error: string}[] }
 *
 * `pid` wins over `name` when both supplied. `force=true` upgrades to SIGKILL
 * (POSIX) or `taskkill /F` (Windows). On Windows the signal arg is ignored
 * — taskkill maps roughly to TERM, /F to KILL. */
export const killProcessHandler: ToolHandler = async args => {
  const pid = typeof args.pid === 'number' && Number.isFinite(args.pid)
    ? Math.floor(args.pid)
    : null
  const name = argOptionalString(args.name)
  const force = typeof args.force === 'boolean' ? args.force : false
  const signal = (argOptionalString(args.signal) ?? 'TERM').toUpperCase()

  if (pid === null && name === null) {
    throw new Error('desktop_kill_process requires either "pid" or "name"')
  }

  const killed: number[] = []
  const failed: { pid: number; error: string }[] = []

  // Resolve target pids. By-pid is always a single-element list; by-name
  // looks up every matching process so the agent doesn't have to follow up
  // with another list call.
  let targets: number[] = []
  if (pid !== null) {
    targets = [pid]
  } else {
    // Reuse listProcesses logic — same ps/tasklist invocation, filtered.
    const list = await listProcessesHandler(
      { filter: name, limit: 100 },
      // ctx is unused inside listProcessesHandler — we supply a minimal stub
      // because TS demands one. The handler is sync-ish (no I/O depending on ctx).
      {
        cwd: process.cwd(),
        abortSignal: new AbortController().signal,
        interactive: false
      }
    )
    const procs = (list as { processes: ProcInfo[] }).processes
    targets = procs.map(p => p.pid)
  }

  for (const t of targets) {
    try {
      if (process.platform === 'win32') {
        const a = force ? ['/F', '/PID', String(t)] : ['/PID', String(t)]
        const r = runSync('taskkill', a)
        if (r.ok) {
          killed.push(t)
        } else {
          failed.push({ pid: t, error: r.stdout.trim() || 'taskkill failed' })
        }
      } else {
        const sig = force
          ? 'SIGKILL'
          : signal === 'KILL'
            ? 'SIGKILL'
            : 'SIGTERM'
        process.kill(t, sig)
        killed.push(t)
      }
    } catch (e) {
      failed.push({ pid: t, error: e instanceof Error ? e.message : String(e) })
    }
  }

  return { killed, failed, requested: targets.length }
}

// ── desktop_find_pid_by_port ────────────────────────────────────────────────

/** desktop_find_pid_by_port
 *   args: { port: number, protocol?: 'tcp' | 'udp' (default 'tcp') }
 *   returns: { port, protocol, listeners: {pid:number, address:string}[] }
 *
 * Cross-platform plumbing:
 *   - Windows: `netstat -ano` (PID column populated, deterministic format)
 *   - macOS:   `lsof -nP -iTCP:<port> -sTCP:LISTEN` (or -iUDP)
 *   - Linux:   `ss -lntp` / `ss -lnup` then fall back to `lsof` if `ss` absent
 *
 * Spawns sync and parses; matches the rest of this file's style. */
export const findPidByPortHandler: ToolHandler = async args => {
  const port = argNumber(args.port, -1, 0)
  if (port < 0) {
    throw new Error('"port" must be a non-negative number')
  }
  const protocol = (argOptionalString(args.protocol) ?? 'tcp').toLowerCase()
  if (protocol !== 'tcp' && protocol !== 'udp') {
    throw new Error('"protocol" must be "tcp" or "udp"')
  }

  const listeners: { pid: number; address: string }[] = []

  if (process.platform === 'win32') {
    const r = runSync('netstat', ['-ano'])
    const wantProto = protocol === 'tcp' ? 'TCP' : 'UDP'
    const lines = r.stdout.split(/\r?\n/)
    for (const raw of lines) {
      const line = raw.trim()
      if (!line.startsWith(wantProto)) {
        continue
      }
      // TCP rows: "TCP   0.0.0.0:8080   0.0.0.0:0   LISTENING   1234"
      // UDP rows: "UDP   0.0.0.0:53     *:*                       456"
      const fields = line.split(/\s+/)
      if (fields.length < 4) {
        continue
      }
      const local = fields[1]!
      const lastColon = local.lastIndexOf(':')
      if (lastColon < 0) {
        continue
      }
      const portStr = local.slice(lastColon + 1)
      if (parseInt(portStr, 10) !== port) {
        continue
      }
      const pidStr = fields[fields.length - 1]!
      const pid = parseInt(pidStr, 10)
      if (!Number.isFinite(pid)) {
        continue
      }
      // For TCP, only include LISTENING. For UDP there's no state column.
      if (protocol === 'tcp' && !fields.includes('LISTENING')) {
        continue
      }
      listeners.push({ pid, address: local })
    }
    return { port, protocol, listeners }
  }

  if (process.platform === 'darwin') {
    const protoArg = protocol === 'tcp' ? 'TCP' : 'UDP'
    const lsofArgs = ['-nP', `-i${protoArg}:${port}`]
    if (protocol === 'tcp') {
      lsofArgs.push('-sTCP:LISTEN')
    }
    const r = runSync('lsof', lsofArgs)
    const lines = r.stdout.split('\n').slice(1)
    for (const raw of lines) {
      const line = raw.trim()
      if (!line) {
        continue
      }
      // COMMAND  PID  USER  FD  TYPE  DEVICE  SIZE/OFF  NODE  NAME
      const fields = line.split(/\s+/)
      if (fields.length < 9) {
        continue
      }
      const pid = parseInt(fields[1]!, 10)
      if (!Number.isFinite(pid)) {
        continue
      }
      listeners.push({ pid, address: fields[8]! })
    }
    return { port, protocol, listeners }
  }

  // Linux — try ss first, fall back to lsof.
  const ssArgs = protocol === 'tcp' ? ['-lntp'] : ['-lnup']
  const ss = runSync('ss', ssArgs)
  if (ss.ok) {
    const lines = ss.stdout.split('\n').slice(1)
    for (const raw of lines) {
      const line = raw.trim()
      if (!line) {
        continue
      }
      // ss -lntp output:
      //   State Recv-Q Send-Q Local-Address Peer users:(("name",pid=N,fd=K))
      // The pid is encoded as `pid=N`; the local address has the port at the end.
      const portMatch = /:(\d+)\s/.exec(line)
      if (!portMatch || parseInt(portMatch[1]!, 10) !== port) {
        continue
      }
      const pidMatches = [...line.matchAll(/pid=(\d+)/g)]
      for (const m of pidMatches) {
        const pid = parseInt(m[1]!, 10)
        if (Number.isFinite(pid)) {
          listeners.push({ pid, address: portMatch.input!.split(/\s+/)[3] ?? '' })
        }
      }
    }
    return { port, protocol, listeners }
  }

  // ss unavailable — try lsof as the final fallback. Same shape as macOS.
  const protoArg = protocol === 'tcp' ? 'TCP' : 'UDP'
  const lsofArgs = ['-nP', `-i${protoArg}:${port}`]
  if (protocol === 'tcp') {
    lsofArgs.push('-sTCP:LISTEN')
  }
  const lsof = runSync('lsof', lsofArgs)
  const lines = lsof.stdout.split('\n').slice(1)
  for (const raw of lines) {
    const line = raw.trim()
    if (!line) {
      continue
    }
    const fields = line.split(/\s+/)
    if (fields.length < 9) {
      continue
    }
    const pid = parseInt(fields[1]!, 10)
    if (!Number.isFinite(pid)) {
      continue
    }
    listeners.push({ pid, address: fields[8]! })
  }
  return { port, protocol, listeners }
}

// Re-export so jobs.ts can co-locate with the same on-disk root.
export { jobsRoot, ensureJobsRoot }

// Suppress unused import warning when this module gets stripped during a
// platform-specific build.
void existsSync

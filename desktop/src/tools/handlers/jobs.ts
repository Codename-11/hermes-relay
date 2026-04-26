// Job API for the `desktop` relay channel — long-running tasks with
// persistent logs and pollable status.
//
//   desktop_job_start    — kick off a command, return {job_id, pid, log_paths}
//   desktop_job_status   — current state (running/exited/killed) + exit_code
//   desktop_job_logs     — read stdout/stderr with offset+limit (tail-friendly)
//   desktop_job_cancel   — TERM (or KILL with force=true) the job
//   desktop_job_list     — every job we know about, scanning the on-disk dir
//
// Layout — every job gets a directory at:
//   ~/.hermes/desktop-jobs/<job_id>/
//     meta.json   — job_id, command, cwd, pid, started_at_ms, ended_at_ms?,
//                   exit_code?, killed_by?, env_keys?
//     stdout.log  — child stdout
//     stderr.log  — child stderr
//
// Why split stdout/stderr instead of a combined log (like spawn_detached):
//   Real agent workflows want "give me the last 200 lines of stderr so I can
//   diagnose this build failure" — much more useful when the streams are
//   separate. spawn_detached's combined.log is for fire-and-forget.
//
// On-disk state survives a daemon restart. The in-memory `jobs` Map holds
// active ChildProcess refs so cancel() can reach them, but list/status/logs
// all read from disk so a freshly-launched daemon can introspect a job
// started by yesterday's daemon.

import { spawn, type ChildProcess } from 'node:child_process'
import {
  existsSync,
  mkdirSync,
  openSync,
  readdirSync,
  readFileSync,
  statSync,
  writeFileSync
} from 'node:fs'
import { promises as fsp } from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

import type { ToolHandler } from '../router.js'

// Co-locate with desktop_spawn_detached's directory tree so a detached
// process and a "real job" use identical on-disk shape.
function jobsRoot(): string {
  return path.join(os.homedir(), '.hermes', 'desktop-jobs')
}

function ensureJobsRoot(): string {
  const root = jobsRoot()
  mkdirSync(root, { recursive: true })
  return root
}

function jobDir(jobId: string): string {
  return path.join(jobsRoot(), jobId)
}

type JobState = 'running' | 'exited' | 'killed' | 'failed' | 'unknown'

interface JobMeta {
  job_id: string
  command: string
  cwd: string
  shell: string
  pid: number
  started_at_ms: number
  ended_at_ms?: number
  exit_code?: number | null
  signal?: string | null
  killed_by?: 'user' | 'timeout' | 'signal' | null
  // Captures whether stdin was passed in at start time. We never persist
  // its value — only the fact that it was supplied.
  had_stdin?: boolean
  env_keys?: string[]
}

/** Process refs for jobs we own in this process. Cleared when the child
 * exits. NOT load-bearing for status — disk meta is. This map only
 * supports cancel(), which needs a live ChildProcess to signal. */
const liveJobs = new Map<string, { child: ChildProcess; meta: JobMeta }>()

function newJobId(): string {
  return [...crypto.getRandomValues(new Uint8Array(8))]
    .map(b => b.toString(16).padStart(2, '0'))
    .join('')
}

function readMeta(jobId: string): JobMeta | null {
  const p = path.join(jobDir(jobId), 'meta.json')
  if (!existsSync(p)) {
    return null
  }
  try {
    const text = readFileSync(p, 'utf8')
    return JSON.parse(text) as JobMeta
  } catch {
    return null
  }
}

function writeMeta(meta: JobMeta): void {
  const dir = jobDir(meta.job_id)
  mkdirSync(dir, { recursive: true })
  // Atomic-ish: write to .tmp then rename. Prevents a daemon crash from
  // leaving a half-written meta.json that fails to parse on next list.
  const tmp = path.join(dir, 'meta.json.tmp')
  writeFileSync(tmp, JSON.stringify(meta, null, 2))
  // fs.renameSync would be nicer but is sync — and we already are.
  const dest = path.join(dir, 'meta.json')
  try {
    // node:fs renameSync is synchronous on all platforms. Lazily import to
    // avoid a top-level circular if process.ts ever imports this file.
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { renameSync } = require('node:fs') as typeof import('node:fs')
    renameSync(tmp, dest)
  } catch {
    // Fall back to overwrite if rename fails (Windows EEXIST quirk).
    writeFileSync(dest, JSON.stringify(meta, null, 2))
  }
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

// ── desktop_job_start ───────────────────────────────────────────────────────

/** desktop_job_start
 *   args: { command: string, cwd?: string, env?: Record<string,string>,
 *           stdin?: string, job_id?: string }
 *   returns: { job_id, pid, log_paths: {stdout, stderr}, started_at_ms,
 *              command, cwd, shell }
 *
 * Returns within ~10ms — the child runs in the background. Use
 * desktop_job_status/logs to follow up. */
export const jobStartHandler: ToolHandler = async args => {
  const command = argString(args.command, 'command')
  const cwd = argOptionalString(args.cwd) ?? process.cwd()
  const jobId = argOptionalString(args.job_id) ?? newJobId()
  const stdinText = argOptionalString(args.stdin)
  const envOverride = (args.env as Record<string, string> | undefined) ?? null

  ensureJobsRoot()
  const dir = jobDir(jobId)
  mkdirSync(dir, { recursive: true })

  const stdoutPath = path.join(dir, 'stdout.log')
  const stderrPath = path.join(dir, 'stderr.log')
  const outFd = openSync(stdoutPath, 'a')
  const errFd = openSync(stderrPath, 'a')

  const isWin = process.platform === 'win32'
  const shellName = isWin ? 'cmd' : 'bash'
  const shellArgs = isWin ? ['/c', command] : ['-lc', command]

  const env = envOverride ? { ...process.env, ...envOverride } : process.env

  const child = spawn(shellName, shellArgs, {
    cwd,
    env,
    detached: true,
    // Open stdin as a pipe iff the caller supplied stdin text; otherwise
    // 'ignore' so child can't hang reading from a closed TTY.
    stdio: [stdinText !== null ? 'pipe' : 'ignore', outFd, errFd],
    windowsHide: true
  })

  if (!child.pid || child.pid <= 0) {
    throw new Error('spawn returned no pid')
  }

  // If stdin text was provided, write it once + close to signal EOF. The
  // child has its own copy of the bytes by the time spawn returns control.
  if (stdinText !== null && child.stdin) {
    try {
      child.stdin.end(stdinText)
    } catch {
      /* best effort */
    }
  }

  const meta: JobMeta = {
    job_id: jobId,
    command,
    cwd,
    shell: shellName,
    pid: child.pid,
    started_at_ms: Date.now(),
    had_stdin: stdinText !== null,
    env_keys: envOverride ? Object.keys(envOverride) : undefined
  }
  writeMeta(meta)
  liveJobs.set(jobId, { child, meta })

  // Wire close handler so the meta.json gets the final exit fields. We
  // intentionally DON'T await this — start() returns to the agent
  // immediately. The child is unref'd so the parent can exit.
  child.on('exit', (code, signal) => {
    const live = liveJobs.get(jobId)
    if (!live) {
      return
    }
    const finalMeta: JobMeta = {
      ...live.meta,
      ended_at_ms: Date.now(),
      exit_code: typeof code === 'number' ? code : null,
      signal: typeof signal === 'string' ? signal : null,
      killed_by: signal === 'SIGTERM' || signal === 'SIGKILL' ? 'signal' : undefined
    }
    try {
      writeMeta(finalMeta)
    } catch {
      /* ignore — meta best-effort */
    }
    liveJobs.delete(jobId)
  })
  child.unref()

  return {
    job_id: jobId,
    pid: child.pid,
    started_at_ms: meta.started_at_ms,
    command,
    cwd,
    shell: shellName,
    log_paths: { stdout: stdoutPath, stderr: stderrPath }
  }
}

// ── desktop_job_status ──────────────────────────────────────────────────────

/** Compute the live state for a job by fusing in-memory + on-disk view. */
function computeState(jobId: string, meta: JobMeta): JobState {
  const live = liveJobs.get(jobId)
  if (live && !live.child.killed && live.child.exitCode === null) {
    return 'running'
  }
  // Not in memory or child reports exited — believe meta.
  if (typeof meta.exit_code === 'number' && meta.exit_code !== null) {
    if (meta.killed_by === 'user' || meta.killed_by === 'signal') {
      return 'killed'
    }
    return meta.exit_code === 0 ? 'exited' : 'failed'
  }
  // Meta says it's still running but in-memory map doesn't have it. That
  // means a previous daemon process started this job and we (the current
  // process) didn't. Probe the pid to decide.
  if (typeof meta.pid === 'number' && meta.pid > 0) {
    try {
      // Signal 0 = check existence; throws if process isn't ours/doesn't exist.
      process.kill(meta.pid, 0)
      return 'running'
    } catch {
      // pid is gone but we never wrote ended_at_ms. Best-effort: mark as
      // unknown and let the caller decide. Avoid auto-mutating meta — we
      // don't know the real exit code.
      return 'unknown'
    }
  }
  return 'unknown'
}

/** desktop_job_status
 *   args: { job_id: string }
 *   returns: { job_id, state, exit_code?, ...meta fields, log_paths }
 */
export const jobStatusHandler: ToolHandler = async args => {
  const jobId = argString(args.job_id, 'job_id')
  const meta = readMeta(jobId)
  if (!meta) {
    throw new Error(`unknown job_id: ${jobId}`)
  }
  const state = computeState(jobId, meta)
  const dir = jobDir(jobId)
  return {
    ...meta,
    state,
    log_paths: {
      stdout: path.join(dir, 'stdout.log'),
      stderr: path.join(dir, 'stderr.log')
    }
  }
}

// ── desktop_job_logs ────────────────────────────────────────────────────────

/** Read tail of a log file. Defaults to the last 8 KiB so the agent gets
 * something useful without paginating. `offset` (in bytes from start) +
 * `limit` (cap on returned bytes) let it walk forward. */
async function readTail(
  file: string,
  offset: number | null,
  limit: number
): Promise<{ content: string; total_bytes: number; truncated: boolean; offset: number }> {
  if (!existsSync(file)) {
    return { content: '', total_bytes: 0, truncated: false, offset: 0 }
  }
  const total = statSync(file).size
  let start = 0
  if (offset === null) {
    // Tail mode — start `limit` bytes before end of file.
    start = Math.max(0, total - limit)
  } else if (offset < 0) {
    // Negative offset means "from end".
    start = Math.max(0, total + offset)
  } else {
    start = Math.min(offset, total)
  }
  const end = Math.min(total, start + limit)
  const handle = await fsp.open(file, 'r')
  try {
    const buf = Buffer.alloc(end - start)
    if (buf.length > 0) {
      await handle.read(buf, 0, buf.length, start)
    }
    return {
      content: buf.toString('utf8'),
      total_bytes: total,
      truncated: end < total,
      offset: end
    }
  } finally {
    await handle.close()
  }
}

/** desktop_job_logs
 *   args: { job_id: string, stream?: 'stdout' | 'stderr' | 'both' (default 'both'),
 *           offset?: number (bytes; null → tail), limit?: number (default 8192) }
 *   returns: { stdout?: TailResult, stderr?: TailResult, state, exit_code? }
 *
 * `state` and `exit_code` are echoed so the agent can decide whether to
 * keep tailing without a separate desktop_job_status round-trip. */
export const jobLogsHandler: ToolHandler = async args => {
  const jobId = argString(args.job_id, 'job_id')
  const stream = (argOptionalString(args.stream) ?? 'both').toLowerCase()
  if (stream !== 'stdout' && stream !== 'stderr' && stream !== 'both') {
    throw new Error('"stream" must be "stdout", "stderr", or "both"')
  }
  const limit = argNumber(args.limit, 8 * 1024, 1)
  const offset =
    typeof args.offset === 'number' && Number.isFinite(args.offset)
      ? Math.floor(args.offset)
      : null

  const meta = readMeta(jobId)
  if (!meta) {
    throw new Error(`unknown job_id: ${jobId}`)
  }
  const dir = jobDir(jobId)
  const out: Record<string, unknown> = {
    job_id: jobId,
    state: computeState(jobId, meta)
  }
  if (typeof meta.exit_code === 'number') {
    out.exit_code = meta.exit_code
  }
  if (stream === 'stdout' || stream === 'both') {
    out.stdout = await readTail(path.join(dir, 'stdout.log'), offset, limit)
  }
  if (stream === 'stderr' || stream === 'both') {
    out.stderr = await readTail(path.join(dir, 'stderr.log'), offset, limit)
  }
  return out
}

// ── desktop_job_cancel ──────────────────────────────────────────────────────

/** desktop_job_cancel
 *   args: { job_id: string, force?: boolean (default false) }
 *   returns: { job_id, ok: boolean, signaled: 'TERM' | 'KILL' | null,
 *              already_exited: boolean }
 *
 * `force=true` upgrades to SIGKILL. By default we send SIGTERM and let the
 * child drain — well-written processes (npm, gradle, ffmpeg) trap SIGTERM
 * and clean up. Agents that want a hard kill pass force=true. */
export const jobCancelHandler: ToolHandler = async args => {
  const jobId = argString(args.job_id, 'job_id')
  const force = typeof args.force === 'boolean' ? args.force : false
  const meta = readMeta(jobId)
  if (!meta) {
    throw new Error(`unknown job_id: ${jobId}`)
  }

  const live = liveJobs.get(jobId)
  if (!live) {
    // Daemon-started jobs from a prior process — fall back to process.kill
    // by pid. This is best-effort: the pid may have been recycled. We
    // require the pid to still be alive (sig 0 probes existence) before
    // attempting the actual kill, which mitigates the recycling risk.
    if (typeof meta.pid === 'number' && meta.pid > 0) {
      try {
        process.kill(meta.pid, 0)
      } catch {
        return {
          job_id: jobId,
          ok: false,
          signaled: null,
          already_exited: true
        }
      }
      try {
        if (process.platform === 'win32') {
          // No SIGTERM on Windows; taskkill /F is the closest analog.
          spawn('taskkill', force ? ['/F', '/PID', String(meta.pid)] : ['/PID', String(meta.pid)], {
            stdio: 'ignore',
            windowsHide: true
          }).unref()
        } else {
          process.kill(meta.pid, force ? 'SIGKILL' : 'SIGTERM')
        }
        // Stamp meta with kill intent so a later status() call can
        // describe how it ended.
        const updated: JobMeta = { ...meta, killed_by: 'user' }
        writeMeta(updated)
        return {
          job_id: jobId,
          ok: true,
          signaled: force ? 'KILL' : 'TERM',
          already_exited: false
        }
      } catch (e) {
        return {
          job_id: jobId,
          ok: false,
          signaled: null,
          already_exited: false,
          error: e instanceof Error ? e.message : String(e)
        }
      }
    }
    return {
      job_id: jobId,
      ok: false,
      signaled: null,
      already_exited: true
    }
  }

  // Live in this process — just kill the ChildProcess.
  try {
    if (process.platform === 'win32') {
      // ChildProcess.kill on Windows is unreliable for shell-spawned trees.
      // taskkill /T also kills the child shell's grandchildren — most
      // long-running processes (npm/node, gradle/java) are grandchildren.
      spawn(
        'taskkill',
        force
          ? ['/F', '/T', '/PID', String(live.child.pid)]
          : ['/T', '/PID', String(live.child.pid)],
        { stdio: 'ignore', windowsHide: true }
      ).unref()
    } else {
      live.child.kill(force ? 'SIGKILL' : 'SIGTERM')
    }
    const updated: JobMeta = { ...live.meta, killed_by: 'user' }
    writeMeta(updated)
    return {
      job_id: jobId,
      ok: true,
      signaled: force ? 'KILL' : 'TERM',
      already_exited: false
    }
  } catch (e) {
    return {
      job_id: jobId,
      ok: false,
      signaled: null,
      already_exited: false,
      error: e instanceof Error ? e.message : String(e)
    }
  }
}

// ── desktop_job_list ────────────────────────────────────────────────────────

/** desktop_job_list
 *   args: { state?: JobState, since_ms?: number, limit?: number (default 100) }
 *   returns: { jobs: Array<JobMeta + state>, count, truncated }
 *
 * Scans the on-disk dir, NOT the in-memory map — so a list issued after a
 * daemon restart still sees jobs from the previous process. */
export const jobListHandler: ToolHandler = async args => {
  const wantState = argOptionalString(args.state) as JobState | null
  const sinceMs = argNumber(args.since_ms, 0)
  const limit = argNumber(args.limit, 100, 1)

  const root = jobsRoot()
  if (!existsSync(root)) {
    return { jobs: [], count: 0, truncated: false }
  }

  const entries = readdirSync(root, { withFileTypes: true })
  const collected: Array<JobMeta & { state: JobState }> = []
  for (const ent of entries) {
    if (!ent.isDirectory()) {
      continue
    }
    const meta = readMeta(ent.name)
    if (!meta) {
      continue
    }
    if (sinceMs > 0 && meta.started_at_ms < sinceMs) {
      continue
    }
    const state = computeState(meta.job_id, meta)
    if (wantState && state !== wantState) {
      continue
    }
    collected.push({ ...meta, state })
  }
  // Sort newest first — most useful default for an agent asking "what's
  // running right now."
  collected.sort((a, b) => b.started_at_ms - a.started_at_ms)
  const out = collected.slice(0, limit)
  return {
    jobs: out,
    count: out.length,
    truncated: collected.length > limit
  }
}

export { jobsRoot, jobDir, ensureJobsRoot }

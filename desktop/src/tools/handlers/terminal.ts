// Shell-exec handler for the `desktop` channel. Spawns a child under the
// user's login-ish shell, captures stdout/stderr, kills on abort/timeout,
// returns a structured exit envelope.
//
// Platform split:
//   - POSIX: `bash -lc '<command>'` — -l loads login files (aliases, PATH).
//   - Windows: `cmd /c <command>` — closest equivalent that accepts a
//     single string. A future revision could prefer PowerShell; cmd was
//     chosen for zero-config parity with git-bash users.
//
// The router caps the whole handler at 30s via its AbortController, but
// callers can ALSO pass a per-call `timeout` in SECONDS — the wire spec
// in `plugin/tools/desktop_tool.py` passes `timeout: int(timeout)` where
// `timeout` is the seconds value from the tool schema (default 30s). We
// convert to ms internally. Whichever fires first (per-call timeout OR
// router AbortController) triggers SIGKILL on the child.

import { spawn } from 'node:child_process'

import type { ToolHandler } from '../router.js'

const DEFAULT_TIMEOUT_MS = 30_000
// Absolute ceiling so a malicious / confused caller can't ask for a
// 1-hour shell command on our machine. Bounded at 10 minutes — above
// that, split the work into multiple calls.
const MAX_TIMEOUT_MS = 10 * 60_000

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

/**
 * Convert an incoming `timeout` (SECONDS per wire spec) into milliseconds,
 * with defensive clamping. Accepts the value from either `timeout` (seconds,
 * canonical) or `timeout_ms` (milliseconds, opt-in override). This matches
 * Python's idiomatic `time.sleep(seconds)` convention while still letting
 * Node-native callers send precise ms values when they need to.
 */
function resolveTimeoutMs(args: Record<string, unknown>): number {
  // timeout_ms wins if both provided — more specific.
  const rawMs = args.timeout_ms
  if (typeof rawMs === 'number' && Number.isFinite(rawMs) && rawMs > 0) {
    return Math.min(Math.floor(rawMs), MAX_TIMEOUT_MS)
  }
  const rawSec = args.timeout
  if (typeof rawSec === 'number' && Number.isFinite(rawSec) && rawSec > 0) {
    return Math.min(Math.floor(rawSec * 1000), MAX_TIMEOUT_MS)
  }
  return DEFAULT_TIMEOUT_MS
}

export const terminalHandler: ToolHandler = async (args, ctx) => {
  const command = argString(args.command, 'command')
  const cwd = argOptionalString(args.cwd) ?? ctx.cwd
  const timeoutMs = resolveTimeoutMs(args)

  const isWin = process.platform === 'win32'
  const cmd = isWin ? 'cmd' : 'bash'
  const shellArgs = isWin ? ['/c', command] : ['-lc', command]

  const start = Date.now()
  const child = spawn(cmd, shellArgs, {
    cwd,
    env: process.env,
    // Don't inherit stdin — the remote agent has no way to type into it
    // and a command that reads stdin would otherwise hang until timeout.
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true
  })

  let stdout = ''
  let stderr = ''
  let killedBy: 'timeout' | 'abort' | null = null

  child.stdout?.on('data', (chunk: Buffer) => {
    stdout += chunk.toString('utf8')
  })
  child.stderr?.on('data', (chunk: Buffer) => {
    stderr += chunk.toString('utf8')
  })

  // Per-call timeout (bounded above by the router's AbortController).
  const timer = setTimeout(() => {
    killedBy = 'timeout'
    try {
      child.kill('SIGKILL')
    } catch {
      /* ignore */
    }
  }, timeoutMs)
  timer.unref?.()

  // Honor transport-level abort (router timeout or relay teardown).
  const onAbort = () => {
    if (!killedBy) {
      killedBy = 'abort'
    }
    try {
      child.kill('SIGKILL')
    } catch {
      /* ignore */
    }
  }
  ctx.abortSignal.addEventListener('abort', onAbort, { once: true })

  try {
    const exitCode = await new Promise<number>((resolve, reject) => {
      child.on('error', e => reject(e))
      child.on('close', (code, signal) => {
        if (killedBy) {
          reject(new Error(killedBy === 'timeout' ? `timed out after ${timeoutMs}ms` : 'aborted'))
          return
        }
        if (code !== null) {
          resolve(code)
          return
        }
        // Killed by signal (not our kill) — surface as non-zero exit for the agent.
        resolve(128 + (typeof signal === 'string' ? 15 : 1))
      })
    })

    return {
      stdout,
      stderr,
      exit_code: exitCode,
      duration_ms: Date.now() - start
    }
  } finally {
    clearTimeout(timer)
    ctx.abortSignal.removeEventListener('abort', onAbort)
  }
}

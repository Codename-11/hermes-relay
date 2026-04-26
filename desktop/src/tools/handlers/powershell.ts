// PowerShell handler for the `desktop` relay channel.
//
//   args: { script: string, cwd?: string, timeout?: number (seconds, default 30),
//           timeout_ms?: number, prefer?: 'pwsh' | 'powershell' | 'auto' }
//   returns: { stdout, stderr, exit_code, duration_ms, shell, version? }
//
// The pain this fixes: trying to run PowerShell *through* `desktop_terminal`
// means `cmd /c "powershell -Command \"...\""` — the shell parser eats
// quotes, single quotes inside the script need to be doubled, here-strings
// are unusable. Real-world fallout: the user reported scripts echoing back
// to the prompt instead of executing.
//
// We avoid all of that by spawning PowerShell directly (no cmd wrapper) and
// piping the script through stdin with `-Command -`. Script text never
// touches argv, so PowerShell's own parser doesn't have to compete with
// a host shell's quoting rules first.
//
// Discovery order (override with `prefer`):
//   - 'pwsh'       PowerShell 7+ (cross-platform). Preferred when present.
//   - 'powershell' Windows PowerShell 5.1 (built-in on Windows).
//   - 'auto'       (default) try pwsh first, fall back to powershell on Windows.
//
// On non-Windows hosts without `pwsh` we fail loud rather than pretending —
// PowerShell is required for this tool by definition.

import { spawn } from 'node:child_process'

import type { ToolHandler } from '../router.js'

const DEFAULT_TIMEOUT_MS = 30_000
// Mirror terminalHandler — bound at 10 minutes. Anything longer should be a
// detached job (desktop_job_start) so the agent can poll instead of holding
// a 10-minute open RPC.
const MAX_TIMEOUT_MS = 10 * 60_000
// Cap stdout/stderr per stream. PowerShell can produce a lot when the agent
// asks for `Get-Process`, but a runaway loop shouldn't OOM the relay.
const MAX_OUTPUT_BYTES = 4 * 1024 * 1024

type ShellPick = 'pwsh' | 'powershell'

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

function resolveTimeoutMs(args: Record<string, unknown>): number {
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

/** Try spawning `which` (POSIX) / `where` (Windows) to confirm a binary
 * exists on PATH. We can't `await fs.access` it — PATH lookup needs to
 * mirror what spawn() will actually do. Returns the resolved name on
 * success, null otherwise. Cheap (<10ms typical), called at most twice. */
function probeBinary(name: string): Promise<boolean> {
  return new Promise(resolve => {
    const probe = process.platform === 'win32' ? 'where' : 'which'
    let child
    try {
      child = spawn(probe, [name], { stdio: 'ignore', windowsHide: true })
    } catch {
      resolve(false)
      return
    }
    child.on('error', () => resolve(false))
    child.on('close', code => resolve(code === 0))
  })
}

async function pickShell(prefer: string | null): Promise<ShellPick> {
  if (prefer === 'pwsh') {
    if (await probeBinary('pwsh')) {
      return 'pwsh'
    }
    throw new Error('pwsh requested but not on PATH')
  }
  if (prefer === 'powershell') {
    if (process.platform !== 'win32') {
      throw new Error('powershell (Windows PowerShell) is only available on Windows')
    }
    if (await probeBinary('powershell')) {
      return 'powershell'
    }
    throw new Error('powershell requested but not on PATH')
  }
  // auto: prefer pwsh, fall back to powershell on Windows.
  if (await probeBinary('pwsh')) {
    return 'pwsh'
  }
  if (process.platform === 'win32' && (await probeBinary('powershell'))) {
    return 'powershell'
  }
  throw new Error(
    'no PowerShell on PATH (tried pwsh' +
      (process.platform === 'win32' ? ', powershell' : '') +
      ')'
  )
}

export const powershellHandler: ToolHandler = async (args, ctx) => {
  const script = argString(args.script, 'script')
  const cwd = argOptionalString(args.cwd) ?? ctx.cwd
  const timeoutMs = resolveTimeoutMs(args)
  const prefer = argOptionalString(args.prefer)

  const shell = await pickShell(prefer)

  // Common args that suppress the user's $PROFILE (deterministic execution),
  // refuse to read from a TTY (we're not interactive), and read the script
  // from stdin (the `-` after -Command). We then close stdin so the script
  // can't hang on Read-Host.
  const shellArgs = ['-NoProfile', '-NonInteractive', '-Command', '-']

  const start = Date.now()
  const child = spawn(shell, shellArgs, {
    cwd,
    env: process.env,
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true
  })

  let stdout = ''
  let stderr = ''
  let stdoutBytes = 0
  let stderrBytes = 0
  let truncated = false
  let killedBy: 'timeout' | 'abort' | null = null

  child.stdout?.on('data', (chunk: Buffer) => {
    stdoutBytes += chunk.length
    if (stdoutBytes > MAX_OUTPUT_BYTES) {
      truncated = true
      try {
        child.kill('SIGKILL')
      } catch {
        /* ignore */
      }
      return
    }
    stdout += chunk.toString('utf8')
  })
  child.stderr?.on('data', (chunk: Buffer) => {
    stderrBytes += chunk.length
    if (stderrBytes > MAX_OUTPUT_BYTES) {
      truncated = true
      try {
        child.kill('SIGKILL')
      } catch {
        /* ignore */
      }
      return
    }
    stderr += chunk.toString('utf8')
  })

  // Pipe script text through stdin verbatim — PowerShell parses it as if
  // typed into an interactive session. Single quotes, here-strings, $vars,
  // multiline blocks all work without re-quoting.
  try {
    child.stdin?.end(script + '\n')
  } catch {
    /* if write fails, the close handler will still resolve with whatever we got */
  }

  const timer = setTimeout(() => {
    killedBy = 'timeout'
    try {
      child.kill('SIGKILL')
    } catch {
      /* ignore */
    }
  }, timeoutMs)
  timer.unref?.()

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
        if (truncated) {
          // Output cap fired SIGKILL — surface as a non-zero exit so the
          // agent doesn't treat the truncated output as authoritative.
          resolve(code ?? 137)
          return
        }
        if (killedBy === 'timeout') {
          reject(new Error(`timed out after ${timeoutMs}ms`))
          return
        }
        if (killedBy === 'abort') {
          reject(new Error('aborted'))
          return
        }
        if (code !== null) {
          resolve(code)
          return
        }
        resolve(128 + (typeof signal === 'string' ? 15 : 1))
      })
    })

    return {
      stdout,
      stderr,
      exit_code: exitCode,
      duration_ms: Date.now() - start,
      shell,
      truncated
    }
  } finally {
    clearTimeout(timer)
    ctx.abortSignal.removeEventListener('abort', onAbort)
  }
}

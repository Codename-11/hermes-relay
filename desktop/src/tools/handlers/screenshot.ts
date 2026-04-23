// Screenshot handler for the `desktop` relay channel.
//
//   desktop_screenshot(save_to?, display?) — captures the primary display
//
// Platform dispatch (all shell-outs, no native image libraries):
//   - Windows: PowerShell + System.Windows.Forms + System.Drawing. We write
//              the capture script to a temp .ps1 file and execute it —
//              quoting a multi-line C#-ish PS script through `-Command` is
//              fragile and this is robust to line-ending surprises.
//   - macOS:   `screencapture -x -t png <path>`. `-x` silences the shutter
//              sound. `-t png` pins the format (default is already png but
//              explicit is safer for future macOS changes).
//   - Linux:   Try `grim <path>` (Wayland), then `scrot <path>`, then
//              `import -window root <path>` (ImageMagick). If none exist,
//              return a structured error — don't crash.
//
// `save_to` absent → capture to an OS tempfile, return base64 + size, and
// delete the tempfile. `save_to` set → keep the file, return its path.
//
// `display` is accepted but only wired on Windows (and even there it just
// picks the matching Screen from AllScreens; default 0 = PrimaryScreen).
// macOS/Linux capture the primary display — document in the tool schema.
//
// 10 s hard timeout on capture, 50 MB hard cap on PNG size.

import { spawn } from 'node:child_process'
import { promises as fs } from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

import type { ToolHandler } from '../router.js'

const CAPTURE_TIMEOUT_MS = 10_000
const MAX_PNG_BYTES = 50 * 1024 * 1024

interface SpawnExit {
  exitCode: number
  signal: NodeJS.Signals | null
  stderr: string
  ran: boolean
}

/** Try spawning a command; return `{ran:false}` on ENOENT so the caller
 * can fall through to the next alternative without an uncaught error. */
function tryRun(
  cmd: string,
  args: string[],
  timeoutMs: number
): Promise<SpawnExit> {
  return new Promise(resolve => {
    let child
    try {
      child = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true })
    } catch {
      resolve({ exitCode: -1, signal: null, stderr: '', ran: false })
      return
    }

    let stderr = ''
    let timedOut = false
    let errored = false

    child.stderr?.on('data', (chunk: Buffer) => {
      stderr += chunk.toString('utf8')
    })

    const timer = setTimeout(() => {
      timedOut = true
      try {
        child.kill('SIGKILL')
      } catch {
        /* ignore */
      }
    }, timeoutMs)
    timer.unref?.()

    child.on('error', (err: NodeJS.ErrnoException) => {
      clearTimeout(timer)
      errored = true
      if (err.code === 'ENOENT') {
        resolve({ exitCode: -1, signal: null, stderr: '', ran: false })
        return
      }
      resolve({ exitCode: -1, signal: null, stderr: err.message, ran: true })
    })

    child.on('close', (code, signal) => {
      clearTimeout(timer)
      if (errored) {
        return // already resolved via 'error'
      }
      if (timedOut) {
        resolve({
          exitCode: -1,
          signal,
          stderr: stderr + `\n[timed out after ${timeoutMs}ms]`,
          ran: true
        })
        return
      }
      resolve({
        exitCode: typeof code === 'number' ? code : -1,
        signal,
        stderr,
        ran: true
      })
    })
  })
}

/** Build the PowerShell capture script. `display` picks an index into
 * AllScreens; -1 (or out of range) falls back to PrimaryScreen. We write
 * this to a .ps1 temp file rather than `-Command` because:
 *   (a) Add-Type strings with brackets and commas survive quoting poorly
 *   (b) .ps1 matches ExecutionPolicy Bypass via `-ExecutionPolicy Bypass`
 *       which is the idiomatic non-interactive invocation
 *   (c) debuggable — we can `type` the file if capture fails in prod */
function windowsCaptureScript(outputPath: string, display: number): string {
  // Use Out-Null on Dispose() calls so stdout stays clean — the caller
  // only cares about the file. The JSON-quoted output path dodges path
  // separator issues on Windows.
  const psPath = JSON.stringify(outputPath)
  return `
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$screens = [System.Windows.Forms.Screen]::AllScreens
$target = $null
if (${display} -ge 0 -and ${display} -lt $screens.Length) {
    $target = $screens[${display}]
} else {
    $target = [System.Windows.Forms.Screen]::PrimaryScreen
}
$bounds = $target.Bounds
$bmp = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
$graphics = [System.Drawing.Graphics]::FromImage($bmp)
$graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
$bmp.Save(${psPath}, [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose() | Out-Null
$bmp.Dispose() | Out-Null
`.trim()
}

async function captureWindows(outputPath: string, display: number): Promise<SpawnExit> {
  const tmpScript = path.join(os.tmpdir(), `hermes-relay-shot-${Date.now()}-${process.pid}.ps1`)
  await fs.writeFile(tmpScript, windowsCaptureScript(outputPath, display), 'utf8')
  try {
    return await tryRun(
      'powershell',
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', tmpScript],
      CAPTURE_TIMEOUT_MS
    )
  } finally {
    try {
      await fs.unlink(tmpScript)
    } catch {
      /* ignore — temp script cleanup is best-effort */
    }
  }
}

async function captureMac(outputPath: string): Promise<SpawnExit> {
  return tryRun('screencapture', ['-x', '-t', 'png', outputPath], CAPTURE_TIMEOUT_MS)
}

/** Try grim → scrot → import, in that order. Return the first one that
 * actually ran AND exited cleanly. If none are installed, return a
 * sentinel with ran=false and the stderr accumulated from attempts so
 * the caller can surface a useful "install one of ..." message. */
async function captureLinux(outputPath: string): Promise<SpawnExit & { attempted: string[] }> {
  const attempted: string[] = []
  // grim (Wayland)
  let r = await tryRun('grim', [outputPath], CAPTURE_TIMEOUT_MS)
  if (r.ran) {
    attempted.push('grim')
    if (r.exitCode === 0) {
      return { ...r, attempted }
    }
  }
  // scrot (X11 lightweight)
  r = await tryRun('scrot', [outputPath], CAPTURE_TIMEOUT_MS)
  if (r.ran) {
    attempted.push('scrot')
    if (r.exitCode === 0) {
      return { ...r, attempted }
    }
  }
  // ImageMagick (usually `import` on X11; fallback)
  r = await tryRun('import', ['-window', 'root', outputPath], CAPTURE_TIMEOUT_MS)
  if (r.ran) {
    attempted.push('import')
    if (r.exitCode === 0) {
      return { ...r, attempted }
    }
  }
  // Nothing worked.
  return { exitCode: -1, signal: null, stderr: '', ran: false, attempted }
}

function argOptionalString(v: unknown): string | null {
  if (typeof v === 'string' && v.length > 0) {
    return v
  }
  return null
}

function argNumber(v: unknown, fallback: number): number {
  if (typeof v === 'number' && Number.isFinite(v) && v >= 0) {
    return Math.floor(v)
  }
  return fallback
}

/** desktop_screenshot
 *   args: { save_to?: string, display?: number }
 *   returns: {
 *     format: 'png',
 *     bytes_base64?: string,     // when save_to omitted
 *     saved_path?: string,       // when save_to provided
 *     size_bytes: number
 *   }
 * Note: `display` is only honored on Windows. macOS/Linux always capture
 * the primary display in this revision — document in tool schema. */
export const screenshotHandler: ToolHandler = async (args, ctx) => {
  const saveTo = argOptionalString(args.save_to)
  const display = argNumber(args.display, 0)

  // Resolve output path. If save_to is set, we honor it verbatim
  // (resolved against ctx.cwd for relative paths so the user's expected
  // working directory wins over Node's process.cwd()). Otherwise generate
  // a tempfile in os.tmpdir().
  const tsTag = `${Date.now()}-${process.pid}`
  const outputPath = saveTo
    ? path.resolve(ctx.cwd, saveTo)
    : path.join(os.tmpdir(), `hermes-relay-screenshot-${tsTag}.png`)

  // Make sure the parent directory exists when save_to was user-specified;
  // otherwise the capture command fails with a confusing error.
  if (saveTo) {
    try {
      await fs.mkdir(path.dirname(outputPath), { recursive: true })
    } catch {
      /* ignore — capture command will surface a clearer error */
    }
  }

  const platform = process.platform
  let result: SpawnExit
  let attempted: string[] = []

  if (platform === 'win32') {
    result = await captureWindows(outputPath, display)
  } else if (platform === 'darwin') {
    result = await captureMac(outputPath)
  } else if (platform === 'linux') {
    const linuxResult = await captureLinux(outputPath)
    attempted = linuxResult.attempted
    result = linuxResult
  } else {
    throw new Error(`screenshot not supported on platform: ${platform}`)
  }

  if (!result.ran) {
    if (platform === 'linux') {
      throw new Error(
        `no screenshot tool available; install one of grim/scrot/imagemagick${attempted.length ? ` (tried: ${attempted.join(', ')})` : ''}`
      )
    }
    throw new Error(`screenshot command not found on ${platform}`)
  }
  if (result.exitCode !== 0) {
    const detail = result.stderr.trim().slice(0, 500) || `exit code ${result.exitCode}`
    // Clean up the (likely empty/partial) output file on failure when we
    // owned the temp path.
    if (!saveTo) {
      try {
        await fs.unlink(outputPath)
      } catch {
        /* ignore */
      }
    }
    throw new Error(`screenshot capture failed: ${detail}`)
  }

  // Verify the file exists and size it. `stat` surfaces a proper error
  // when the command "succeeded" but silently wrote nothing (misconfigured
  // Wayland compositor, etc).
  let size = 0
  try {
    const stat = await fs.stat(outputPath)
    size = stat.size
  } catch (e) {
    throw new Error(
      `screenshot capture claimed success but output file is missing: ${e instanceof Error ? e.message : String(e)}`
    )
  }
  if (size === 0) {
    throw new Error('screenshot capture produced an empty file')
  }
  if (size > MAX_PNG_BYTES) {
    if (!saveTo) {
      try {
        await fs.unlink(outputPath)
      } catch {
        /* ignore */
      }
    }
    throw new Error(`screenshot too large (${size} bytes > ${MAX_PNG_BYTES} limit)`)
  }

  if (saveTo) {
    return {
      format: 'png' as const,
      saved_path: outputPath,
      size_bytes: size
    }
  }

  // save_to absent → read back as base64 and delete the tempfile.
  const buf = await fs.readFile(outputPath)
  try {
    await fs.unlink(outputPath)
  } catch {
    /* ignore — tempfile cleanup is best-effort */
  }
  return {
    format: 'png' as const,
    bytes_base64: buf.toString('base64'),
    size_bytes: buf.length
  }
}

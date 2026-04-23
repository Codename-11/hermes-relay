// Clipboard bridge handlers for the `desktop` relay channel.
//
//   desktop_clipboard_read  — returns the user's clipboard as plain text
//   desktop_clipboard_write — writes plain text to the clipboard
//
// Platform dispatch (all shell-outs, no native deps):
//   - Windows: PowerShell `Get-Clipboard -Raw` / `Set-Clipboard` via stdin.
//              We avoid `clip.exe` because it has CP1252 encoding quirks and
//              lacks a read counterpart.
//   - macOS:   `pbpaste` / `pbcopy`.
//   - Linux:   `wl-paste` (Wayland) first, falling back to
//              `xclip -selection clipboard -o` (X11). Write uses the matching
//              counterpart (`wl-copy` or `xclip -selection clipboard`).
//
// Text-only by design. No HTML/image/RTF support — a remote agent shouldn't
// be able to silently paste a file attachment. Keep it one-shot reads;
// continuous monitoring is explicitly out of scope for alpha.6.
//
// Size cap: 10 MB both directions. Larger clipboards reject rather than
// streaming a huge base64 blob back over WSS. 10 MB of plaintext is
// already comfortably more than any realistic clipboard copy.

import { spawn } from 'node:child_process'

import type { ToolHandler } from '../router.js'

/** Shell-out timeout — clipboard reads/writes should be near-instant. */
const CLIPBOARD_TIMEOUT_MS = 5_000
/** Hard cap on both read output and write input. */
const MAX_CLIPBOARD_BYTES = 10 * 1024 * 1024

interface SpawnResult {
  stdout: Buffer
  stderr: string
  exitCode: number
  signal: NodeJS.Signals | null
  oversize: boolean
}

/** Small spawn wrapper: pipes optional stdin, enforces a timeout via
 * SIGKILL, caps stdout at MAX_CLIPBOARD_BYTES so a pathological clipboard
 * doesn't OOM us. Returns everything as structured fields rather than
 * throwing so handlers can decide their own error wording. */
function runClipboardCmd(
  cmd: string,
  args: string[],
  stdinText: string | null,
  timeoutMs: number
): Promise<SpawnResult> {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, {
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true
    })

    const stdoutChunks: Buffer[] = []
    let stdoutLen = 0
    let oversize = false
    let stderr = ''
    let timedOut = false

    child.stdout?.on('data', (chunk: Buffer) => {
      if (oversize) {
        return
      }
      stdoutLen += chunk.length
      if (stdoutLen > MAX_CLIPBOARD_BYTES) {
        // Keep just enough to know we blew the cap; don't push more.
        oversize = true
        try {
          child.kill('SIGKILL')
        } catch {
          /* ignore */
        }
        return
      }
      stdoutChunks.push(chunk)
    })
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

    child.on('error', err => {
      clearTimeout(timer)
      reject(err)
    })

    child.on('close', (code, signal) => {
      clearTimeout(timer)
      if (timedOut) {
        reject(new Error(`clipboard command timed out after ${timeoutMs}ms: ${cmd}`))
        return
      }
      resolve({
        stdout: Buffer.concat(stdoutChunks, stdoutLen > MAX_CLIPBOARD_BYTES ? MAX_CLIPBOARD_BYTES : stdoutLen),
        stderr,
        exitCode: typeof code === 'number' ? code : -1,
        signal,
        oversize
      })
    })

    if (stdinText !== null) {
      // Fire-and-forget stdin write; close stdin once done so the child
      // knows there's no more input. PowerShell's Set-Clipboard will hang
      // forever otherwise.
      try {
        child.stdin?.write(stdinText, 'utf8', () => {
          try {
            child.stdin?.end()
          } catch {
            /* ignore */
          }
        })
      } catch (e) {
        // Treat stdin failures as a spawn error.
        clearTimeout(timer)
        try {
          child.kill('SIGKILL')
        } catch {
          /* ignore */
        }
        reject(e instanceof Error ? e : new Error(String(e)))
        return
      }
    } else {
      try {
        child.stdin?.end()
      } catch {
        /* ignore */
      }
    }
  })
}

/** Decide which Linux clipboard tool is live. Wayland's `wl-paste` is
 * preferred when `WAYLAND_DISPLAY` is set; otherwise fall back to X11
 * `xclip`. We don't try `xsel` — xclip is the more universally-installed
 * of the two and the agent-error path already covers "neither available". */
function linuxClipboardFlavor(): 'wayland' | 'x11' {
  if (process.env.WAYLAND_DISPLAY && process.env.WAYLAND_DISPLAY.length > 0) {
    return 'wayland'
  }
  return 'x11'
}

/** desktop_clipboard_read
 *   args: {}
 *   returns: { content: string, format: 'text' }
 * Errors throw — the router serializes throws into `{ok:false, error}`. */
export const clipboardReadHandler: ToolHandler = async () => {
  const platform = process.platform
  let result: SpawnResult
  let cmdLabel: string

  if (platform === 'win32') {
    cmdLabel = 'powershell Get-Clipboard'
    // -Raw preserves newlines; -NoProfile keeps startup fast and avoids
    // profile scripts that might emit junk to stdout.
    result = await runClipboardCmd(
      'powershell',
      ['-NoProfile', '-NonInteractive', '-Command', 'Get-Clipboard -Raw'],
      null,
      CLIPBOARD_TIMEOUT_MS
    )
  } else if (platform === 'darwin') {
    cmdLabel = 'pbpaste'
    result = await runClipboardCmd('pbpaste', [], null, CLIPBOARD_TIMEOUT_MS)
  } else if (platform === 'linux') {
    const flavor = linuxClipboardFlavor()
    if (flavor === 'wayland') {
      cmdLabel = 'wl-paste'
      try {
        result = await runClipboardCmd('wl-paste', ['--no-newline'], null, CLIPBOARD_TIMEOUT_MS)
      } catch (e) {
        // wl-paste not installed — try xclip as a fallback even under
        // Wayland, since XWayland often keeps xclip functional.
        cmdLabel = 'xclip -selection clipboard -o'
        result = await runClipboardCmd(
          'xclip',
          ['-selection', 'clipboard', '-o'],
          null,
          CLIPBOARD_TIMEOUT_MS
        )
        // If even xclip fails, let the outer try fall through to the
        // throw below.
        if (result.exitCode !== 0 && !result.stdout.length) {
          throw new Error(
            `no clipboard reader available; install wl-clipboard or xclip (wl-paste error: ${e instanceof Error ? e.message : String(e)})`
          )
        }
      }
    } else {
      cmdLabel = 'xclip -selection clipboard -o'
      result = await runClipboardCmd(
        'xclip',
        ['-selection', 'clipboard', '-o'],
        null,
        CLIPBOARD_TIMEOUT_MS
      )
    }
  } else {
    throw new Error(`clipboard read not supported on platform: ${platform}`)
  }

  if (result.oversize) {
    throw new Error(`clipboard too large (>${MAX_CLIPBOARD_BYTES} bytes)`)
  }
  if (result.exitCode !== 0) {
    // On macOS/Linux an empty clipboard typically exits 0 with empty
    // stdout. A non-zero exit is a real error — surface stderr.
    const detail = result.stderr.trim().slice(0, 500) || `exit code ${result.exitCode}`
    throw new Error(`${cmdLabel} failed: ${detail}`)
  }

  let content = result.stdout.toString('utf8')
  // PowerShell's `Get-Clipboard -Raw` appends a trailing CRLF that isn't
  // part of the clipboard content. Strip exactly one trailing newline
  // pair if present — users don't expect to see it, and pbpaste/xclip
  // both already return content without it.
  if (platform === 'win32' && content.endsWith('\r\n')) {
    content = content.slice(0, -2)
  }

  return { content, format: 'text' as const }
}

/** desktop_clipboard_write
 *   args: { content: string }
 *   returns: { wrote: number }  — byte length of UTF-8 encoding
 * Empty-string writes are allowed; they clear the clipboard on most
 * platforms (pbcopy, xclip). Windows Set-Clipboard with empty input
 * also clears. */
export const clipboardWriteHandler: ToolHandler = async args => {
  const content = args.content
  if (typeof content !== 'string') {
    throw new Error('missing or invalid "content" argument')
  }
  const byteLen = Buffer.byteLength(content, 'utf8')
  if (byteLen > MAX_CLIPBOARD_BYTES) {
    throw new Error(`content too large (${byteLen} bytes > ${MAX_CLIPBOARD_BYTES} limit)`)
  }

  const platform = process.platform
  let result: SpawnResult
  let cmdLabel: string

  if (platform === 'win32') {
    cmdLabel = 'powershell Set-Clipboard'
    // `$input | Set-Clipboard` consumes stdin verbatim. `-Command -` reads
    // the command from stdin, so we use -Command '<script>' directly with
    // stdin feeding `$input`.
    result = await runClipboardCmd(
      'powershell',
      [
        '-NoProfile',
        '-NonInteractive',
        '-Command',
        '$input | Set-Clipboard'
      ],
      content,
      CLIPBOARD_TIMEOUT_MS
    )
  } else if (platform === 'darwin') {
    cmdLabel = 'pbcopy'
    result = await runClipboardCmd('pbcopy', [], content, CLIPBOARD_TIMEOUT_MS)
  } else if (platform === 'linux') {
    const flavor = linuxClipboardFlavor()
    if (flavor === 'wayland') {
      cmdLabel = 'wl-copy'
      try {
        result = await runClipboardCmd('wl-copy', [], content, CLIPBOARD_TIMEOUT_MS)
      } catch (e) {
        // Fall back to xclip.
        cmdLabel = 'xclip -selection clipboard'
        result = await runClipboardCmd(
          'xclip',
          ['-selection', 'clipboard'],
          content,
          CLIPBOARD_TIMEOUT_MS
        )
        if (result.exitCode !== 0) {
          throw new Error(
            `no clipboard writer available; install wl-clipboard or xclip (wl-copy error: ${e instanceof Error ? e.message : String(e)})`
          )
        }
      }
    } else {
      cmdLabel = 'xclip -selection clipboard'
      result = await runClipboardCmd(
        'xclip',
        ['-selection', 'clipboard'],
        content,
        CLIPBOARD_TIMEOUT_MS
      )
    }
  } else {
    throw new Error(`clipboard write not supported on platform: ${platform}`)
  }

  if (result.exitCode !== 0) {
    const detail = result.stderr.trim().slice(0, 500) || `exit code ${result.exitCode}`
    throw new Error(`${cmdLabel} failed: ${detail}`)
  }

  return { wrote: byteLen }
}

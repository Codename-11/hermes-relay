// chatAttach — library for resolving an image attachment payload from one of
// three sources (clipboard / screenshot / local file) and base64-encoding it
// for upload to the relay via the `image.attach.bytes` RPC.
//
// Used exclusively by the REPL slash-commands (`/paste`, `/screenshot`,
// `/image <path>`) in `commands/chat.ts`. All platform-specific shell-outs
// stay here so the REPL loop reads linearly.
//
// Design notes:
//   - `captureClipboardImage` returns `null` on "no image on clipboard" so
//     the REPL can surface a friendly hint and let the user fall through to a
//     normal text message. All other failure modes (spawn errors, timeouts,
//     decode errors) ALSO return null — we prefer "silent fall-through" over
//     "surface a scary error for what is usually just 'no image copied'".
//   - `captureScreenshot` reuses `screenshotHandler` so we don't duplicate the
//     per-OS capture logic. We fake a minimal `ToolContext` — the handler only
//     needs `cwd` to resolve optional `save_to` paths, which we never set.
//   - `readImageFile` enforces a 25 MB cap matching the server-side limit on
//     `image.attach.bytes`. Size cap mismatch would let the server reject a
//     base64 blob we've already pumped over the wire, which is wasteful.

import { spawn } from 'node:child_process'
import { promises as fs } from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

import { screenshotHandler } from './tools/handlers/screenshot.js'

export interface AttachPayload {
  format: 'png' | 'jpeg' | 'webp' | 'gif'
  bytes_base64: string
  size_bytes: number
  width?: number
  height?: number
  source: 'clipboard' | 'screenshot' | 'file'
  filename_hint?: string
}

/** 5s timeout for any single clipboard shell-out. */
const CLIPBOARD_TIMEOUT_MS = 5_000
/** Matches the server-side cap on `image.attach.bytes`. */
const MAX_IMAGE_BYTES = 25 * 1024 * 1024

interface SpawnCapture {
  stdout: Buffer
  stderr: string
  exitCode: number
  ran: boolean
  timedOut: boolean
}

/** Lean spawn helper — captures stdout as Buffer (binary-safe), stderr as
 * text, and resolves with a uniform shape. Never throws; callers branch on
 * `ran` / `exitCode` / `timedOut`. */
function runCapture(
  cmd: string,
  args: string[],
  timeoutMs: number
): Promise<SpawnCapture> {
  return new Promise(resolve => {
    let child
    try {
      child = spawn(cmd, args, {
        stdio: ['ignore', 'pipe', 'pipe'],
        windowsHide: true
      })
    } catch {
      resolve({ stdout: Buffer.alloc(0), stderr: '', exitCode: -1, ran: false, timedOut: false })
      return
    }

    const stdoutChunks: Buffer[] = []
    let stderr = ''
    let timedOut = false
    let errored = false

    child.stdout?.on('data', (chunk: Buffer) => {
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

    child.on('error', (err: NodeJS.ErrnoException) => {
      clearTimeout(timer)
      errored = true
      if (err.code === 'ENOENT') {
        resolve({ stdout: Buffer.alloc(0), stderr: '', exitCode: -1, ran: false, timedOut: false })
        return
      }
      resolve({ stdout: Buffer.alloc(0), stderr: err.message, exitCode: -1, ran: true, timedOut: false })
    })

    child.on('close', code => {
      clearTimeout(timer)
      if (errored) {
        return // already resolved
      }
      resolve({
        stdout: Buffer.concat(stdoutChunks),
        stderr,
        exitCode: typeof code === 'number' ? code : -1,
        ran: true,
        timedOut
      })
    })
  })
}

/** Windows: ask PowerShell to dump the clipboard image as base64 PNG. Empty
 * stdout means "no image on clipboard" — the idiomatic success-but-null
 * signal. Non-empty means base64 PNG bytes we can round-trip.
 *
 * `-STA` is load-bearing. powershell.exe's default for `-Command` is MTA
 * (Multi-Threaded Apartment), and `[System.Windows.Forms.Clipboard]::
 * GetImage()` only returns a valid image from an STA thread — from MTA it
 * silently returns null, indistinguishable from "clipboard has no image."
 * Added in alpha.10 after Bailey reported clipboard reads always coming
 * back empty even when an image was clearly on the clipboard.
 */
async function captureClipboardWindows(): Promise<AttachPayload | null> {
  const ps = [
    "Add-Type -AssemblyName System.Windows.Forms;",
    "Add-Type -AssemblyName System.Drawing;",
    "$c = [System.Windows.Forms.Clipboard]::GetImage();",
    "if ($c) {",
    "  $ms = New-Object IO.MemoryStream;",
    "  $c.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png);",
    "  [Convert]::ToBase64String($ms.ToArray())",
    "}"
  ].join(' ')

  const r = await runCapture(
    'powershell',
    ['-NoProfile', '-NonInteractive', '-STA', '-Command', ps],
    CLIPBOARD_TIMEOUT_MS
  )
  if (!r.ran || r.timedOut || r.exitCode !== 0) {
    return null
  }
  // PowerShell trims nothing by default; base64 may include trailing CRLF.
  const b64 = r.stdout.toString('utf8').trim()
  if (!b64) {
    return null // empty clipboard (no image)
  }
  let buf: Buffer
  try {
    buf = Buffer.from(b64, 'base64')
  } catch {
    return null
  }
  if (buf.length === 0) {
    return null
  }
  const dims = probePngDimensions(buf)
  const payload: AttachPayload = {
    format: 'png',
    bytes_base64: b64,
    size_bytes: buf.length,
    source: 'clipboard'
  }
  if (dims) {
    payload.width = dims.width
    payload.height = dims.height
  }
  return payload
}

/** macOS: try `osascript` to write the clipboard image to a tempfile, then
 * read it back. `try … end try` swallows the AppleScript error when the
 * clipboard doesn't hold a PNG-class object, leaving no file — that's our
 * null signal. */
async function captureClipboardMac(): Promise<AttachPayload | null> {
  const tmpPath = path.join(os.tmpdir(), `hermes-relay-clip-${Date.now()}-${process.pid}.png`)
  const tmpPosix = tmpPath.replace(/\\/g, '/') // AppleScript wants POSIX
  const script = [
    'try',
    'set theData to the clipboard as «class PNGf»',
    `set theFile to POSIX file "${tmpPosix}"`,
    'set fRef to open for access theFile with write permission',
    'write theData to fRef',
    'close access fRef',
    'end try'
  ]
  const args: string[] = []
  for (const line of script) {
    args.push('-e', line)
  }
  const r = await runCapture('osascript', args, CLIPBOARD_TIMEOUT_MS)
  if (!r.ran || r.timedOut) {
    return null
  }
  try {
    const buf = await fs.readFile(tmpPath)
    try {
      await fs.unlink(tmpPath)
    } catch {
      /* ignore — tempfile cleanup is best-effort */
    }
    if (buf.length === 0) {
      return null
    }
    const dims = probePngDimensions(buf)
    const payload: AttachPayload = {
      format: 'png',
      bytes_base64: buf.toString('base64'),
      size_bytes: buf.length,
      source: 'clipboard'
    }
    if (dims) {
      payload.width = dims.width
      payload.height = dims.height
    }
    return payload
  } catch {
    // File never got written → no image on clipboard.
    return null
  }
}

/** Linux: probe xclip TARGETS for `image/png`; if present, read bytes into a
 * tempfile. Falls back to wl-paste on Wayland. Either missing tool → null
 * (the user may simply not have wl-clipboard/xclip installed, which is the
 * same UX as "no image"). */
async function captureClipboardLinux(): Promise<AttachPayload | null> {
  const useWayland = !!(process.env.WAYLAND_DISPLAY && process.env.WAYLAND_DISPLAY.length > 0)

  if (useWayland) {
    // wl-paste --list-types; if image/png listed, `wl-paste --type image/png`
    const types = await runCapture('wl-paste', ['--list-types'], CLIPBOARD_TIMEOUT_MS)
    if (!types.ran || types.timedOut || types.exitCode !== 0) {
      // fall through to xclip below
    } else {
      const listing = types.stdout.toString('utf8')
      if (!/\bimage\/png\b/i.test(listing)) {
        // wl-paste works but no image mime
        return null
      }
      const bin = await runCapture('wl-paste', ['--type', 'image/png'], CLIPBOARD_TIMEOUT_MS)
      if (!bin.ran || bin.timedOut || bin.exitCode !== 0 || bin.stdout.length === 0) {
        return null
      }
      return pngBufferToPayload(bin.stdout, 'clipboard')
    }
  }

  // xclip path (X11 or wl-paste unavailable)
  const targets = await runCapture(
    'xclip',
    ['-selection', 'clipboard', '-t', 'TARGETS', '-o'],
    CLIPBOARD_TIMEOUT_MS
  )
  if (!targets.ran || targets.timedOut || targets.exitCode !== 0) {
    return null
  }
  const listing = targets.stdout.toString('utf8')
  if (!/\bimage\/png\b/i.test(listing)) {
    return null
  }
  const bin = await runCapture(
    'xclip',
    ['-selection', 'clipboard', '-t', 'image/png', '-o'],
    CLIPBOARD_TIMEOUT_MS
  )
  if (!bin.ran || bin.timedOut || bin.exitCode !== 0 || bin.stdout.length === 0) {
    return null
  }
  return pngBufferToPayload(bin.stdout, 'clipboard')
}

/** Shared helper: wrap a PNG buffer in an AttachPayload, sniffing dimensions
 * from the IHDR chunk when possible. Returns null on empty. */
function pngBufferToPayload(
  buf: Buffer,
  source: AttachPayload['source']
): AttachPayload | null {
  if (buf.length === 0) {
    return null
  }
  const dims = probePngDimensions(buf)
  const payload: AttachPayload = {
    format: 'png',
    bytes_base64: buf.toString('base64'),
    size_bytes: buf.length,
    source
  }
  if (dims) {
    payload.width = dims.width
    payload.height = dims.height
  }
  return payload
}

/** Read width/height from a PNG buffer's IHDR chunk. PNG signature is 8 bytes,
 * then a 4-byte length, 4-byte "IHDR" tag, then 4-byte width + 4-byte height
 * (big-endian). Returns null for non-PNG or truncated input. */
function probePngDimensions(buf: Buffer): { width: number; height: number } | null {
  // 8-byte signature + 8-byte chunk header + 8-byte w/h = 24 minimum.
  if (buf.length < 24) {
    return null
  }
  // PNG signature: 89 50 4E 47 0D 0A 1A 0A
  if (
    buf[0] !== 0x89 ||
    buf[1] !== 0x50 ||
    buf[2] !== 0x4e ||
    buf[3] !== 0x47 ||
    buf[4] !== 0x0d ||
    buf[5] !== 0x0a ||
    buf[6] !== 0x1a ||
    buf[7] !== 0x0a
  ) {
    return null
  }
  // First chunk must be IHDR (offset 12–15).
  if (buf.slice(12, 16).toString('ascii') !== 'IHDR') {
    return null
  }
  const width = buf.readUInt32BE(16)
  const height = buf.readUInt32BE(20)
  return { width, height }
}

/** Platform dispatch. All branches return `null` on any failure (spawn error,
 * timeout, empty clipboard) so the REPL slash-command can surface a single
 * "no image on clipboard" hint regardless of the underlying cause. */
export async function captureClipboardImage(): Promise<AttachPayload | null> {
  try {
    if (process.platform === 'win32') {
      return await captureClipboardWindows()
    }
    if (process.platform === 'darwin') {
      return await captureClipboardMac()
    }
    if (process.platform === 'linux') {
      return await captureClipboardLinux()
    }
    return null
  } catch {
    return null
  }
}

/** Screenshot the primary display via the existing tool handler. We fake a
 * minimal ToolContext — the handler only consults `cwd` for `save_to`, which
 * we never pass. `abortSignal` is required by the type but never fires here
 * (the handler's own CAPTURE_TIMEOUT_MS keeps things bounded). */
export async function captureScreenshot(opts?: { display?: number | string }): Promise<AttachPayload> {
  // screenshotHandler defaults to display=-1 (all monitors) when args.display
  // is omitted. Callers pass `display: 'primary'` / 0 / 1 / … to narrow.
  const args: Record<string, unknown> = {}
  if (opts?.display !== undefined) {
    args.display = opts.display
  }
  const ctx = {
    cwd: process.cwd(),
    abortSignal: new AbortController().signal,
    interactive: false
  }
  const res = (await screenshotHandler(args, ctx)) as {
    format: 'png'
    bytes_base64?: string
    size_bytes: number
    saved_path?: string
  }
  if (!res.bytes_base64) {
    // Shouldn't happen — we passed no save_to — but guard so the server
    // doesn't get an undefined-bytes payload.
    throw new Error('screenshot returned no bytes (unexpected save_to path?)')
  }
  const buf = Buffer.from(res.bytes_base64, 'base64')
  const dims = probePngDimensions(buf)
  const payload: AttachPayload = {
    format: 'png',
    bytes_base64: res.bytes_base64,
    size_bytes: res.size_bytes,
    source: 'screenshot'
  }
  if (dims) {
    payload.width = dims.width
    payload.height = dims.height
  }
  return payload
}

/** Accepted file extensions → payload `format` tag. The server validates
 * independently; this is just for the quick "don't bother uploading a .bmp"
 * client-side reject. */
const EXT_TO_FORMAT: Record<string, AttachPayload['format']> = {
  '.png': 'png',
  '.jpg': 'jpeg',
  '.jpeg': 'jpeg',
  '.webp': 'webp',
  '.gif': 'gif'
}

/** Read a local image file, validate extension + size, and wrap it in an
 * AttachPayload. Over-limit or wrong-extension files throw — this is a user-
 * typed path so a clear error message is better than a silent null. */
export async function readImageFile(filePath: string): Promise<AttachPayload> {
  if (!filePath) {
    throw new Error('/image requires a file path')
  }
  const resolved = path.resolve(filePath)
  const ext = path.extname(resolved).toLowerCase()
  const format = EXT_TO_FORMAT[ext]
  if (!format) {
    throw new Error(
      `unsupported image extension ${ext || '(none)'} — expected one of png/jpg/jpeg/webp/gif`
    )
  }
  let stat
  try {
    stat = await fs.stat(resolved)
  } catch (e) {
    throw new Error(`cannot read ${resolved}: ${e instanceof Error ? e.message : String(e)}`)
  }
  if (!stat.isFile()) {
    throw new Error(`${resolved} is not a regular file`)
  }
  if (stat.size === 0) {
    throw new Error(`${resolved} is empty`)
  }
  if (stat.size > MAX_IMAGE_BYTES) {
    throw new Error(
      `${resolved} is ${stat.size} bytes — exceeds ${MAX_IMAGE_BYTES} byte limit`
    )
  }
  const buf = await fs.readFile(resolved)
  const payload: AttachPayload = {
    format,
    bytes_base64: buf.toString('base64'),
    size_bytes: buf.length,
    source: 'file',
    filename_hint: path.basename(resolved)
  }
  // PNG dimensions are the only format we sniff — JPEG/WEBP/GIF would each
  // need their own header parser and the server can derive them anyway.
  if (format === 'png') {
    const dims = probePngDimensions(buf)
    if (dims) {
      payload.width = dims.width
      payload.height = dims.height
    }
  }
  return payload
}

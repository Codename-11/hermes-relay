// Foreign-process bridge to the tray app.
//
// The Tauri tray hosts a tiny localhost HTTP listener on a random port,
// recorded along with a token in `~/.hermes/desktop-tray-control.json`.
// Sibling processes (notably `hermes-relay voice mode`) read that file
// and POST `/voice/show` to bring the tray window forward + activate
// the voice tab — instead of opening a system browser.
//
// Why HTTP and not Tauri IPC: Tauri's `invoke()` is webview-only. A
// foreign process has no entry point into the Tauri runtime, so the
// tray opens its own loopback HTTP port. Token + loopback are the gate.

import { promises as fs } from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

interface TrayControl {
  port: number
  token: string
  pid?: number
  started_at?: number
}

const CONTROL_FILE = 'desktop-tray-control.json'

function controlPath(): string {
  return path.join(os.homedir(), '.hermes', CONTROL_FILE)
}

/** Read the tray control file. Returns null if the tray isn't running or
 * if the file is missing / malformed. Callers should treat null as the
 * normal "tray unavailable" case, not an error. */
export async function discoverTray(): Promise<TrayControl | null> {
  let raw: string
  try {
    raw = await fs.readFile(controlPath(), 'utf8')
  } catch {
    return null
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null
  const o = parsed as Record<string, unknown>
  const port = typeof o.port === 'number' ? o.port : NaN
  const token = typeof o.token === 'string' ? o.token : ''
  if (!Number.isFinite(port) || port <= 0 || port > 65535) return null
  if (!token) return null
  const out: TrayControl = { port, token }
  if (typeof o.pid === 'number') out.pid = o.pid
  if (typeof o.started_at === 'number') out.started_at = o.started_at
  return out
}

/** Ask the tray to focus the voice tab. Returns true on 200, false otherwise
 * (including network failure, 401, etc.). Best-effort — the caller falls
 * back to a system-browser open when this returns false. */
export async function notifyTrayShowVoice(control: TrayControl, timeoutMs = 2000): Promise<boolean> {
  const ctl = new AbortController()
  const timer = setTimeout(() => ctl.abort(), timeoutMs)
  try {
    const res = await fetch(`http://127.0.0.1:${control.port}/voice/show`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: control.token }),
      signal: ctl.signal
    })
    return res.status === 200
  } catch {
    return false
  } finally {
    clearTimeout(timer)
  }
}

/** Convenience helper: discover + notify in one call. Returns true if the
 * tray accepted the wakeup; false in every other case (no tray, mismatched
 * token, dropped connection, etc.). Caller treats false as "open browser". */
export async function tryTrayShowVoice(): Promise<boolean> {
  const ctl = await discoverTray()
  if (!ctl) return false
  return notifyTrayShowVoice(ctl)
}

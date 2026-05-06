// Vendored from hermes-agent/ui-tui/src/remoteSessions.ts — identical format
// so a user who paired once via the TUI can run the CLI (and vice-versa)
// without re-pairing. Storage lives at `~/.hermes/remote-sessions.json`,
// mode 0600, atomic tempfile→rename. Fails closed to null on any error.

import { promises as fs } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'

export interface RemoteSessionRecord {
  token: string
  serverVersion: string | null
  pairedAt: number // epoch seconds
  certPinSha256: string | null
  /** Per-channel grant expiry (epoch seconds; `null` = never expires). Captured
   * from `auth.ok.grants`. Used by `hermes-relay status` to tell the user which
   * channels the token can access. Shape mirrors what the server persists on
   * `Session.grants` (plugin/relay/auth.py). */
  grants?: Record<string, number | null> | null
  /** Epoch seconds when the session token itself expires (not a specific grant);
   * `null` = never. Captured from `auth.ok.expires_at`. */
  ttlExpiresAt?: number | null
  /** Active endpoint role at pair time — one of "lan", "tailscale", "public",
   * "custom", or null if unknown. Drives the contextual connect banner and
   * the "Plain (on LAN)" style labels copied from the Android app. */
  endpointRole?: string | null
  /** Per-URL consent for exposing desktop tool handlers (file read/write,
   * shell exec, search) to the remote agent. Granted explicitly on first
   * chat/shell connect when tools would be wired. Missing → prompt; true
   * → tools attached silently; false → tools suppressed even if set via
   * `--no-tools` override later. See desktop/src/tools/router.ts. */
  toolsConsented?: boolean
  /** Legacy separate per-URL consent for experimental desktop computer-use.
   * Current clients expose the surface with normal desktop-tool consent and
   * gate mouse/keyboard input on visible task-scoped grant approval. */
  computerUseConsented?: boolean
}

interface StoredRecord {
  token: string
  server_version?: string | null
  paired_at: number
  cert_pin_sha256?: string | null
  /** Serialized as a plain object. `null` values mean never-expires; missing
   * means the channel isn't granted. See RemoteSessionRecord.grants. */
  grants?: Record<string, number | null> | null
  ttl_expires_at?: number | null
  endpoint_role?: string | null
  tools_consented?: boolean
  computer_use_consented?: boolean
}

interface StoredFile {
  version: number
  sessions: Record<string, StoredRecord>
}

const STORE_VERSION = 1

const defaultPath = () => join(homedir(), '.hermes', 'remote-sessions.json')

/** Override for tests — restore with `setStorePath(null)`. */
let pathOverride: string | null = null

export const setStorePath = (p: string | null) => {
  pathOverride = p
}

const storePath = () => pathOverride ?? defaultPath()

const emptyFile = (): StoredFile => ({ version: STORE_VERSION, sessions: {} })

const toRecord = (raw: StoredRecord): RemoteSessionRecord => ({
  token: raw.token,
  serverVersion: raw.server_version ?? null,
  pairedAt: raw.paired_at,
  certPinSha256: raw.cert_pin_sha256 ?? null,
  grants: raw.grants ?? null,
  ttlExpiresAt: raw.ttl_expires_at ?? null,
  endpointRole: raw.endpoint_role ?? null,
  toolsConsented: raw.tools_consented ?? false,
  computerUseConsented: raw.computer_use_consented ?? false
})

const fromRecord = (r: RemoteSessionRecord): StoredRecord => ({
  token: r.token,
  server_version: r.serverVersion,
  paired_at: r.pairedAt,
  cert_pin_sha256: r.certPinSha256,
  grants: r.grants ?? null,
  ttl_expires_at: r.ttlExpiresAt ?? null,
  endpoint_role: r.endpointRole ?? null,
  tools_consented: r.toolsConsented ?? false,
  computer_use_consented: r.computerUseConsented ?? false
})

const readFile = async (): Promise<StoredFile> => {
  try {
    const raw = await fs.readFile(storePath(), 'utf8')
    const parsed = JSON.parse(raw) as unknown

    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return emptyFile()
    }

    const obj = parsed as Record<string, unknown>
    const sessions = obj.sessions

    if (!sessions || typeof sessions !== 'object' || Array.isArray(sessions)) {
      return emptyFile()
    }

    return { version: STORE_VERSION, sessions: sessions as Record<string, StoredRecord> }
  } catch {
    return emptyFile()
  }
}

const writeFile = async (file: StoredFile): Promise<void> => {
  const path = storePath()
  const dir = dirname(path)
  await fs.mkdir(dir, { recursive: true, mode: 0o700 })

  const tmp = `${path}.tmp-${process.pid}-${Date.now()}`
  await fs.writeFile(tmp, JSON.stringify(file, null, 2), { mode: 0o600 })
  await fs.rename(tmp, path)
}

export const getSession = async (url: string): Promise<RemoteSessionRecord | null> => {
  try {
    const file = await readFile()
    const raw = file.sessions[url]

    if (!raw || typeof raw.token !== 'string' || !raw.token) {
      return null
    }

    return toRecord(raw)
  } catch {
    return null
  }
}

export interface SaveSessionOptions {
  certPin?: string | null
  grants?: Record<string, number | null> | null
  ttlExpiresAt?: number | null
  endpointRole?: string | null
  toolsConsented?: boolean
  computerUseConsented?: boolean
}

export const saveSession = async (
  url: string,
  token: string,
  serverVersion: string | null,
  opts: SaveSessionOptions | string | null = null
): Promise<void> => {
  // Back-compat: the original signature took `certPin` as the fourth param.
  // Older callers still pass a string or null. Detect and route.
  const options: SaveSessionOptions =
    typeof opts === 'string' || opts === null || opts === undefined
      ? { certPin: (opts as string | null) ?? undefined }
      : opts

  try {
    const file = await readFile()
    const prev = file.sessions[url]
    file.sessions[url] = fromRecord({
      token,
      serverVersion,
      pairedAt: Math.floor(Date.now() / 1000),
      certPinSha256: options.certPin ?? prev?.cert_pin_sha256 ?? null,
      grants: options.grants ?? prev?.grants ?? null,
      ttlExpiresAt: options.ttlExpiresAt ?? prev?.ttl_expires_at ?? null,
      endpointRole: options.endpointRole ?? prev?.endpoint_role ?? null,
      toolsConsented:
        options.toolsConsented !== undefined
          ? options.toolsConsented
          : (prev?.tools_consented ?? false),
      computerUseConsented:
        options.computerUseConsented !== undefined
          ? options.computerUseConsented
          : (prev?.computer_use_consented ?? false)
    })
    await writeFile(file)
  } catch {
    // Persistence failures are non-fatal — next run just re-pairs.
  }
}

export const deleteSession = async (url: string): Promise<void> => {
  try {
    const file = await readFile()

    if (!(url in file.sessions)) {
      return
    }

    delete file.sessions[url]
    await writeFile(file)
  } catch {
    /* fail-closed */
  }
}

export const listSessions = async (): Promise<Record<string, RemoteSessionRecord>> => {
  try {
    const file = await readFile()
    const out: Record<string, RemoteSessionRecord> = {}

    for (const [url, raw] of Object.entries(file.sessions)) {
      if (raw && typeof raw.token === 'string' && raw.token) {
        out[url] = toRecord(raw)
      }
    }

    return out
  } catch {
    return {}
  }
}

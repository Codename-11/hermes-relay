import { promises as fs } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'

export interface ActiveTerminalSession {
  name: string
  conversationId?: string | null
  endpointRole?: string | null
  serverVersion?: string | null
  status?: string | null
  lastAttachedAt: number
}

interface StoredActiveTerminalSession {
  name: string
  conversation_id?: string | null
  endpoint_role?: string | null
  server_version?: string | null
  status?: string | null
  last_attached_at: number
}

interface StoredFile {
  version: number
  active_by_relay: Record<string, StoredActiveTerminalSession>
}

const STORE_VERSION = 1
const defaultPath = () => join(homedir(), '.hermes', 'desktop-sessions.json')
const emptyFile = (): StoredFile => ({ version: STORE_VERSION, active_by_relay: {} })

let pathOverride: string | null = null

export const setTerminalSessionStorePath = (path: string | null) => {
  pathOverride = path
}

export const terminalSessionStorePath = () => pathOverride ?? defaultPath()

const toRecord = (raw: StoredActiveTerminalSession): ActiveTerminalSession | null => {
  if (!raw || typeof raw.name !== 'string' || !raw.name.trim()) {
    return null
  }
  return {
    name: raw.name,
    conversationId: raw.conversation_id ?? null,
    endpointRole: raw.endpoint_role ?? null,
    serverVersion: raw.server_version ?? null,
    status: raw.status ?? null,
    lastAttachedAt: Number.isFinite(raw.last_attached_at) ? raw.last_attached_at : 0
  }
}

const fromRecord = (record: ActiveTerminalSession): StoredActiveTerminalSession => ({
  name: record.name,
  conversation_id: record.conversationId ?? null,
  endpoint_role: record.endpointRole ?? null,
  server_version: record.serverVersion ?? null,
  status: record.status ?? null,
  last_attached_at: record.lastAttachedAt
})

const readFile = async (): Promise<StoredFile> => {
  try {
    const raw = await fs.readFile(terminalSessionStorePath(), 'utf8')
    const parsed = JSON.parse(raw) as unknown
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return emptyFile()
    }
    const obj = parsed as Record<string, unknown>
    const active = obj.active_by_relay
    if (!active || typeof active !== 'object' || Array.isArray(active)) {
      return emptyFile()
    }
    return {
      version: STORE_VERSION,
      active_by_relay: active as Record<string, StoredActiveTerminalSession>
    }
  } catch {
    return emptyFile()
  }
}

const writeFile = async (file: StoredFile): Promise<void> => {
  const path = terminalSessionStorePath()
  await fs.mkdir(dirname(path), { recursive: true, mode: 0o700 })
  const tmp = `${path}.tmp-${process.pid}-${Date.now()}`
  await fs.writeFile(tmp, JSON.stringify(file, null, 2), { mode: 0o600 })
  await fs.rename(tmp, path)
}

export const getActiveTerminalSession = async (url: string): Promise<ActiveTerminalSession | null> => {
  try {
    const file = await readFile()
    const raw = file.active_by_relay[url]
    return raw ? toRecord(raw) : null
  } catch {
    return null
  }
}

export const saveActiveTerminalSession = async (
  url: string,
  record: Omit<ActiveTerminalSession, 'lastAttachedAt'> & { lastAttachedAt?: number }
): Promise<void> => {
  try {
    const name = record.name.trim()
    if (!url.trim() || !name) {
      return
    }
    const file = await readFile()
    file.active_by_relay[url] = fromRecord({
      ...record,
      name,
      lastAttachedAt: record.lastAttachedAt ?? Date.now()
    })
    await writeFile(file)
  } catch {
    /* persistence failures must not break the terminal session */
  }
}

export const clearActiveTerminalSession = async (url: string, name?: string): Promise<void> => {
  try {
    const file = await readFile()
    const current = file.active_by_relay[url]
    if (!current) {
      return
    }
    if (name && current.name !== name) {
      return
    }
    delete file.active_by_relay[url]
    await writeFile(file)
  } catch {
    /* fail closed */
  }
}

export const listActiveTerminalSessions = async (): Promise<Record<string, ActiveTerminalSession>> => {
  try {
    const file = await readFile()
    const out: Record<string, ActiveTerminalSession> = {}
    for (const [url, raw] of Object.entries(file.active_by_relay)) {
      const record = toRecord(raw)
      if (record) {
        out[url] = record
      }
    }
    return out
  } catch {
    return {}
  }
}

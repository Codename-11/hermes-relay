// Local desktop-tool audit log — "what did the agent run on THIS machine?"
//
// The relay keeps a server-side ring buffer of desktop commands, but that
// route (`GET /desktop/health`) is loopback-only — a laptop CLI talking to a
// remote relay can't read it. Since the CLI client is the actual EXECUTOR of
// every desktop tool, it is the right place to record activity: a JSONL log at
// ~/.hermes/desktop-audit.jsonl that `hermes-relay audit` tails. No network,
// no auth, works regardless of where the relay lives.
//
// Best-effort by design: a logging failure must never break a tool dispatch.

import { appendFile, mkdir, readFile, rename, stat } from 'node:fs/promises'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'

export interface AuditEntry {
  /** Epoch milliseconds when the command completed. */
  ts: number
  /** Stable event type for consumers that do not want to infer it from fields. */
  kind?: 'tool.completed'
  tool: string
  category?: AuditCategory
  ok: boolean
  aborted?: boolean
  request_id?: string
  /** Relay identity that issued the action, when known. */
  host_url?: string
  /** Handler wall time, excluding relay transport latency. */
  duration_ms?: number
  /** Process exit code when the handler returns one. Non-zero is attention-worthy. */
  exit_code?: number
  /** Truncated preview of the call args, for context. */
  args_preview?: string
  /** Short success summary (path / exit code / first stdout line). */
  summary?: string
  error?: string
}

export type AuditCategory = 'command' | 'files' | 'screen' | 'input' | 'devices' | 'system' | 'other'

/** Rotate the log once it crosses ~1 MB, keeping a single `.1` backup. */
const MAX_BYTES = 1_000_000

export function auditLogPath(): string {
  return join(homedir(), '.hermes', 'desktop-audit.jsonl')
}

export async function appendAudit(entry: AuditEntry): Promise<void> {
  const path = auditLogPath()
  try {
    await mkdir(dirname(path), { recursive: true })
    try {
      const st = await stat(path)
      if (st.size > MAX_BYTES) {
        await rename(path, path + '.1').catch(() => {})
      }
    } catch {
      /* missing file — nothing to rotate */
    }
    await appendFile(path, JSON.stringify(entry) + '\n', { mode: 0o600 })
  } catch {
    // Audit is best-effort; never throw into the dispatch path.
  }
}

export async function readRecentAudit(limit = 50): Promise<AuditEntry[]> {
  let text: string
  try {
    text = await readFile(auditLogPath(), 'utf8')
  } catch {
    return []
  }
  const lines = text.split('\n').filter((l) => l.trim().length > 0)
  const out: AuditEntry[] = []
  for (const l of lines.slice(-limit)) {
    try {
      out.push(JSON.parse(l) as AuditEntry)
    } catch {
      /* skip a torn/partial line */
    }
  }
  return out
}

/** Best-effort one-line preview of tool args (paths, commands) for the log. */
export function previewArgs(args: Record<string, unknown>): string | undefined {
  try {
    const parts: string[] = []
    for (const key of ['serial', 'source', 'destination', 'apk', 'path', 'command', 'cmd', 'pattern', 'cwd', 'pid', 'port', 'name']) {
      const v = (args as Record<string, unknown>)[key]
      if (v !== undefined && v !== null && typeof v !== 'object') {
        parts.push(`${key}=${String(v)}`)
      }
      if (parts.length >= 2) {
        break
      }
    }
    const s = parts.length ? parts.join(' ') : JSON.stringify(args)
    return s.length > 120 ? s.slice(0, 119) + '…' : s
  } catch {
    return undefined
  }
}

/** Best-effort short success summary from a handler result. */
export function summarizeResult(result: unknown): string | undefined {
  if (result === null || typeof result !== 'object') {
    return undefined
  }
  const r = result as Record<string, unknown>
  if (typeof r.exit_code === 'number') {
    return `exit ${r.exit_code}`
  }
  if (typeof r.path === 'string') {
    return r.path
  }
  if (typeof r.pid === 'number') {
    return `pid ${r.pid}`
  }
  if (typeof r.stdout === 'string' && r.stdout.trim()) {
    const first = r.stdout.trim().split('\n')[0]!
    return first.length > 80 ? first.slice(0, 79) + '…' : first
  }
  return undefined
}

export function resultExitCode(result: unknown): number | undefined {
  if (result === null || typeof result !== 'object') return undefined
  const value = (result as Record<string, unknown>).exit_code
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

/** A small stable taxonomy shared by CLI audit consumers and the tray UI. */
export function categorizeTool(tool: string): AuditCategory {
  const value = tool.toLowerCase()
  if (value.includes('adb') || value.includes('usb')) return 'devices'
  if (value.includes('computer_screenshot') || value.includes('screen')) return 'screen'
  if (value.includes('computer_') || value.includes('mouse') || value.includes('keyboard')) return 'input'
  if (value.includes('file') || value.includes('directory') || value.includes('patch')) return 'files'
  if (value.includes('shell') || value.includes('terminal') || value.includes('powershell') || value.includes('job')) return 'command'
  if (value.includes('daemon') || value.includes('connect')) return 'system'
  return 'other'
}

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
  tool: string
  ok: boolean
  aborted?: boolean
  request_id?: string
  /** Truncated preview of the call args, for context. */
  args_preview?: string
  /** Short success summary (path / exit code / first stdout line). */
  summary?: string
  error?: string
}

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
    for (const key of ['path', 'command', 'cmd', 'pattern', 'cwd', 'pid', 'port', 'name']) {
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

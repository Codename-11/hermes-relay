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

import { appendFile, mkdir, readFile, readdir, rename, stat, unlink, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'

export interface AuditEntry {
  /** Epoch milliseconds when the command completed. */
  ts: number
  /** Stable event type for consumers that do not want to infer it from fields. */
  kind?: 'tool.completed' | 'connection.state'
  tool: string
  category?: AuditCategory
  ok: boolean
  aborted?: boolean
  request_id?: string
  /** Relay identity that issued the action, when known. */
  host_url?: string
  relay_session_id?: string
  requester_device_id?: string
  run_id?: string
  target_device_id?: string
  backend?: 'cua' | 'legacy_compat' | 'system_capture'
  dispatch?: 'background' | 'foreground_compatibility'
  control_session_id?: string
  target_app?: string
  target_title?: string
  target_pid?: number
  target_window_id?: number
  action?: string
  verification?: 'snapshot_captured' | 'not_requested' | 'failed'
  phase?: 'structured_primary' | 'explicit_compatibility' | 'pre_session_safe_fallback'
  /** Handler wall time, excluding relay transport latency. */
  duration_ms?: number
  /** Process exit code when the handler returns one. Non-zero is attention-worthy. */
  exit_code?: number
  /** Truncated preview of the call args, for context. */
  args_preview?: string
  /** Bounded, redacted request detail for the local activity drilldown. */
  request_detail?: string
  /** Bounded command streams when returned by the handler. */
  stdout?: string
  stderr?: string
  /** Bounded structured result after stdout/stderr are removed. */
  result_detail?: string
  request_truncated?: boolean
  stdout_truncated?: boolean
  stderr_truncated?: boolean
  result_truncated?: boolean
  /** Short success summary (path / exit code / first stdout line). */
  summary?: string
  error?: string
  /** Opaque local evidence identifier. Screenshot pixels never enter JSONL. */
  screenshot_evidence_id?: string
  screenshot_mime_type?: 'image/png'
  screenshot_width?: number
  screenshot_height?: number
}

export type AuditCategory = 'command' | 'files' | 'screen' | 'input' | 'devices' | 'system' | 'other'

/** Rotate the log once it crosses ~1 MB, keeping a single `.1` backup. */
const MAX_BYTES = 1_000_000
const MAX_DETAIL_BYTES = 32_768
const MAX_SCREENSHOT_BYTES = 10_000_000
const MAX_SCREENSHOT_FILES = 20

export function auditLogPath(): string {
  return join(homedir(), '.hermes', 'desktop-audit.jsonl')
}

export function auditEvidenceDirectory(): string {
  return process.env.HERMES_RELAY_ACTIVITY_EVIDENCE_DIR ?? join(homedir(), '.hermes', 'activity-evidence')
}

type ScreenshotEvidence = Pick<AuditEntry, 'screenshot_evidence_id' | 'screenshot_mime_type' | 'screenshot_width' | 'screenshot_height'>

function screenshotPayload(result: unknown): { base64: string; width?: number; height?: number } | null {
  if (!result || typeof result !== 'object' || Array.isArray(result)) return null
  const record = result as Record<string, unknown>
  const nested = record.after_screenshot
  if (nested && typeof nested === 'object' && !Array.isArray(nested)) {
    const found = screenshotPayload(nested)
    if (found) return found
  }
  const base64 = typeof record.screenshot_base64 === 'string'
    ? record.screenshot_base64
    : typeof record.bytes_base64 === 'string'
      ? record.bytes_base64
      : null
  if (!base64) return null
  const display = record.display && typeof record.display === 'object' && !Array.isArray(record.display)
    ? record.display as Record<string, unknown>
    : null
  const width = typeof record.screenshot_width === 'number' ? record.screenshot_width
    : typeof display?.width === 'number' ? display.width : undefined
  const height = typeof record.screenshot_height === 'number' ? record.screenshot_height
    : typeof display?.height === 'number' ? display.height : undefined
  return { base64, width, height }
}

async function pruneScreenshotEvidence(directory: string, retentionDays: number): Promise<void> {
  const now = Date.now()
  const maxAgeMs = retentionDays * 24 * 60 * 60 * 1000
  const records = await Promise.all((await readdir(directory, { withFileTypes: true }))
    .filter(entry => entry.isFile() && /^[a-f0-9]{32}\.png$/.test(entry.name))
    .map(async entry => ({ name: entry.name, stats: await stat(join(directory, entry.name)) })))
  records.sort((left, right) => right.stats.mtimeMs - left.stats.mtimeMs)
  await Promise.all(records
    .filter((record, index) => index >= MAX_SCREENSHOT_FILES || now - record.stats.mtimeMs > maxAgeMs)
    .map(record => unlink(join(directory, record.name)).catch(() => {})))
}

export async function pruneAuditScreenshotEvidence(retentionDays: 1 | 7 | 30): Promise<void> {
  try {
    await pruneScreenshotEvidence(auditEvidenceDirectory(), retentionDays)
  } catch {
    /* Missing or unreadable evidence is equivalent to an empty store. */
  }
}

/** Retain a bounded, local screenshot for the activity evidence viewer. The
 * audit log stores only an opaque identifier; clearing activity removes the
 * evidence directory too. */
export async function persistAuditScreenshot(result: unknown, requestId: string, retentionDays = 7): Promise<ScreenshotEvidence> {
  const payload = screenshotPayload(result)
  if (!payload) return {}
  let bytes: Buffer
  try {
    bytes = Buffer.from(payload.base64, 'base64')
  } catch {
    return {}
  }
  if (!bytes.length || bytes.length > MAX_SCREENSHOT_BYTES || bytes.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a') return {}
  const id = createHash('sha256').update(`${requestId}:${Date.now()}`).digest('hex').slice(0, 32)
  const directory = auditEvidenceDirectory()
  try {
    await mkdir(directory, { recursive: true })
    await writeFile(join(directory, `${id}.png`), bytes, { mode: 0o600, flag: 'wx' })
    await pruneScreenshotEvidence(directory, retentionDays)
    return {
      screenshot_evidence_id: id,
      screenshot_mime_type: 'image/png',
      ...(payload.width ? { screenshot_width: payload.width } : {}),
      ...(payload.height ? { screenshot_height: payload.height } : {})
    }
  } catch {
    return {}
  }
}

export async function clearAuditScreenshotEvidence(): Promise<void> {
  const { rm } = await import('node:fs/promises')
  await rm(auditEvidenceDirectory(), { recursive: true, force: true })
}

export async function auditScreenshotEvidenceStatus(): Promise<{ count: number; bytes: number }> {
  try {
    const records = await Promise.all((await readdir(auditEvidenceDirectory(), { withFileTypes: true }))
      .filter(entry => entry.isFile() && /^[a-f0-9]{32}\.png$/.test(entry.name))
      .map(entry => stat(join(auditEvidenceDirectory(), entry.name))))
    return { count: records.length, bytes: records.reduce((total, value) => total + value.size, 0) }
  } catch {
    return { count: 0, bytes: 0 }
  }
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
    for (const key of ['serial', 'source', 'destination', 'apk', 'path', 'command', 'script', 'executable', 'cmd', 'pattern', 'cwd', 'pid', 'port', 'name']) {
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

function boundedText(value: string): { text: string; truncated: boolean } {
  const bytes = Buffer.from(value, 'utf8')
  if (bytes.length <= MAX_DETAIL_BYTES) return { text: value, truncated: false }
  return {
    text: `${bytes.subarray(0, MAX_DETAIL_BYTES).toString('utf8')}\n[… truncated locally at ${MAX_DETAIL_BYTES} bytes]`,
    truncated: true
  }
}

/** Preserve useful local drilldown evidence without logging secrets, file
 * bodies, environment values, or unbounded process output. */
export function auditDetails(
  args: Record<string, unknown>,
  result?: unknown,
  options: { redactComputerContent?: boolean } = {}
): Pick<AuditEntry, 'request_detail' | 'stdout' | 'stderr' | 'result_detail' | 'request_truncated' | 'stdout_truncated' | 'stderr_truncated' | 'result_truncated'> {
  const safeRequest: Record<string, unknown> = {}
  for (const key of [
    'command', 'script', 'executable', 'arguments', 'cwd', 'path', 'source', 'destination',
    'pattern', 'serial', 'apk', 'pid', 'port', 'job_id', 'offset', 'limit', 'timeout', 'reason'
  ]) {
    if (args[key] !== undefined) safeRequest[key] = args[key]
  }
  const request = boundedText(JSON.stringify(safeRequest, null, 2))
  const details: Pick<AuditEntry, 'request_detail' | 'stdout' | 'stderr' | 'result_detail' | 'request_truncated' | 'stdout_truncated' | 'stderr_truncated' | 'result_truncated'> = {
    request_detail: request.text,
    request_truncated: request.truncated || undefined
  }
  if (options.redactComputerContent) {
    if (result && typeof result === 'object' && !Array.isArray(result)) {
      const record = result as Record<string, unknown>
      const target = record.target && typeof record.target === 'object' && !Array.isArray(record.target)
        ? record.target as Record<string, unknown>
        : {}
      const safeResult = {
        backend: record.backend,
        action: record.action,
        status: record.status,
        code: record.code,
        target: {
          pid: target.pid,
          windowId: target.windowId
        },
        redacted: true
      }
      const value = boundedText(JSON.stringify(safeResult, null, 2))
      details.result_detail = value.text
      details.result_truncated = value.truncated || undefined
    }
    return details
  }
  if (!result || typeof result !== 'object') {
    if (result !== undefined) {
      const value = boundedText(String(result))
      details.result_detail = value.text
      details.result_truncated = value.truncated || undefined
    }
    return details
  }
  const record = { ...(result as Record<string, unknown>) }
  if (typeof record.stdout === 'string') {
    const value = boundedText(record.stdout)
    details.stdout = value.text
    details.stdout_truncated = value.truncated || undefined
    delete record.stdout
  }
  if (typeof record.stderr === 'string') {
    const value = boundedText(record.stderr)
    details.stderr = value.text
    details.stderr_truncated = value.truncated || undefined
    delete record.stderr
  }
  const serialized = JSON.stringify(record, null, 2)
  if (serialized !== '{}') {
    const value = boundedText(serialized)
    details.result_detail = value.text
    details.result_truncated = value.truncated || undefined
  }
  return details
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

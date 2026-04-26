import { spawn } from 'node:child_process'

import {
  cancelComputerGrant,
  getActiveComputerGrant,
  getComputerGrantSummary,
  hasComputerInputGrant,
  requestComputerGrant,
  type ComputerGrantMode
} from '../computerGrants.js'
import type { ToolHandler } from '../router.js'
import { screenshotHandler } from './screenshot.js'

const STATUS_TIMEOUT_MS = 5_000
const EXPERIMENTAL_META = Object.freeze({
  experimental: true,
  phase: 'phase_1_observe_first',
  control_model: 'fail_closed_without_task_scoped_grant'
})

interface SpawnOutput {
  stdout: string
  stderr: string
  exitCode: number
  ran: boolean
}

interface DisplayInfo {
  id: string
  index?: number
  name?: string
  x?: number
  y?: number
  width?: number
  height?: number
  scale?: number
  primary: boolean
}

function argString(value: unknown, fallback = ''): string {
  if (typeof value === 'string') {
    return value
  }
  return fallback
}

function argBool(value: unknown, fallback: boolean): boolean {
  if (typeof value === 'boolean') {
    return value
  }
  return fallback
}

function isObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

function runText(cmd: string, args: string[], timeoutMs: number): Promise<SpawnOutput> {
  return new Promise(resolve => {
    let child
    try {
      child = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true })
    } catch {
      resolve({ stdout: '', stderr: '', exitCode: -1, ran: false })
      return
    }

    let stdout = ''
    let stderr = ''
    let timedOut = false
    let errored = false

    child.stdout?.on('data', (chunk: Buffer) => {
      stdout += chunk.toString('utf8')
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
        resolve({ stdout: '', stderr: '', exitCode: -1, ran: false })
        return
      }
      resolve({ stdout: '', stderr: err.message, exitCode: -1, ran: true })
    })

    child.on('close', code => {
      clearTimeout(timer)
      if (errored) {
        return
      }
      resolve({
        stdout,
        stderr: timedOut ? `${stderr}\n[timed out after ${timeoutMs}ms]` : stderr,
        exitCode: typeof code === 'number' ? code : -1,
        ran: true
      })
    })
  })
}

async function windowsDisplays(): Promise<DisplayInfo[]> {
  const script = [
    "$ErrorActionPreference = 'Stop'",
    'Add-Type -AssemblyName System.Windows.Forms',
    '$screens = [System.Windows.Forms.Screen]::AllScreens',
    '$items = for ($i = 0; $i -lt $screens.Length; $i++) {',
    '  $s = $screens[$i]',
    '  [pscustomobject]@{',
    '    id = [string]$i',
    '    index = $i',
    '    name = $s.DeviceName',
    '    x = $s.Bounds.X',
    '    y = $s.Bounds.Y',
    '    width = $s.Bounds.Width',
    '    height = $s.Bounds.Height',
    '    primary = $s.Primary',
    '    scale = 1.0',
    '  }',
    '}',
    '$items | ConvertTo-Json -Depth 4 -Compress'
  ].join('; ')
  const result = await runText(
    'powershell',
    ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script],
    STATUS_TIMEOUT_MS
  )
  if (!result.ran || result.exitCode !== 0 || !result.stdout.trim()) {
    return []
  }
  const parsed: unknown = JSON.parse(result.stdout)
  const items = Array.isArray(parsed) ? parsed : [parsed]
  return items.filter(isObject).map((raw, idx) => ({
    id: argString(raw.id, String(idx)),
    index: typeof raw.index === 'number' ? raw.index : idx,
    name: typeof raw.name === 'string' ? raw.name : undefined,
    x: typeof raw.x === 'number' ? raw.x : undefined,
    y: typeof raw.y === 'number' ? raw.y : undefined,
    width: typeof raw.width === 'number' ? raw.width : undefined,
    height: typeof raw.height === 'number' ? raw.height : undefined,
    scale: typeof raw.scale === 'number' ? raw.scale : undefined,
    primary: typeof raw.primary === 'boolean' ? raw.primary : idx === 0
  }))
}

async function getDisplays(): Promise<DisplayInfo[]> {
  if (process.platform === 'win32') {
    try {
      const displays = await windowsDisplays()
      if (displays.length > 0) {
        return displays
      }
    } catch {
      /* fall through to generic placeholder */
    }
  }

  return [
    {
      id: 'primary',
      primary: true
    }
  ]
}

function pngDimensions(base64: string): { width: number; height: number } | null {
  const buf = Buffer.from(base64, 'base64')
  if (buf.length < 24) {
    return null
  }
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
  if (buf.slice(12, 16).toString('ascii') !== 'IHDR') {
    return null
  }
  return {
    width: buf.readUInt32BE(16),
    height: buf.readUInt32BE(20)
  }
}

function failure(code: string, message: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    ok: false,
    code,
    message,
    ...EXPERIMENTAL_META,
    grant: getComputerGrantSummary(),
    ...extra
  }
}

function parseGrantMode(value: unknown): ComputerGrantMode | null {
  if (value === 'observe' || value === 'assist' || value === 'control') {
    return value
  }
  return null
}

export const computerStatusHandler: ToolHandler = async () => {
  const grant = getActiveComputerGrant()
  return {
    ok: true,
    ...EXPERIMENTAL_META,
    platform: process.platform,
    displays: await getDisplays(),
    permissions: {
      screenshot: 'available',
      input: grant?.mode === 'assist' || grant?.mode === 'control' ? 'granted' : 'not_granted',
      accessibility: 'not_implemented'
    },
    grant: getComputerGrantSummary(),
    overlay: {
      visible: false,
      state: grant ? 'cli_observe' : 'not_available',
      message: 'Native overlay/tray UI is not implemented in Phase 1.'
    },
    safety: {
      host_input: 'not_implemented',
      action_policy: 'desktop_computer_action fails closed without an assist/control grant'
    }
  }
}

export const computerScreenshotHandler: ToolHandler = async (args, ctx) => {
  if (args.region !== undefined && args.region !== null) {
    return failure(
      'not_implemented',
      'Region capture/cropping is planned but not implemented in Phase 1. Capture a full display instead.'
    )
  }

  const display = args.display ?? 'primary'
  const screenshotArgs: Record<string, unknown> = { display }
  if (typeof args.save_to === 'string' && args.save_to.trim()) {
    screenshotArgs.save_to = args.save_to.trim()
  }

  const result = await screenshotHandler(screenshotArgs, ctx) as {
    format: 'png'
    bytes_base64?: string
    saved_path?: string
    size_bytes: number
  }
  const dimensions = result.bytes_base64 ? pngDimensions(result.bytes_base64) : null

  return {
    ok: true,
    ...EXPERIMENTAL_META,
    mode: 'observe',
    format: result.format,
    bytes_base64: result.bytes_base64,
    saved_path: result.saved_path,
    size_bytes: result.size_bytes,
    display: {
      requested: display,
      coordinate_origin: 'screenshot_top_left',
      width: dimensions?.width,
      height: dimensions?.height
    },
    cursor: {
      requested: argBool(args.include_cursor, true),
      included: 'unknown'
    },
    redaction: {
      requested: argBool(args.redact_sensitive, true),
      applied: false,
      reason: 'Sensitive-window redaction is planned but not implemented in Phase 1.'
    },
    grant: getComputerGrantSummary()
  }
}

export const computerActionHandler: ToolHandler = async (args) => {
  const action = argString(args.action).trim()
  if (!action) {
    return failure('invalid_request', 'desktop_computer_action requires an action name.')
  }
  if (!hasComputerInputGrant()) {
    return failure(
      'grant_required',
      'Host input is disabled. Requesting or using assist/control requires a visible local overlay or per-action confirmation, which is not implemented in Phase 1.',
      { action }
    )
  }
  return failure(
    'not_implemented',
    'Mouse/keyboard automation is deliberately not implemented in Phase 1.',
    { action }
  )
}

export const computerGrantRequestHandler: ToolHandler = async (args) => {
  const mode = parseGrantMode(args.mode)
  if (!mode) {
    return failure('invalid_request', 'mode must be one of observe, assist, or control.')
  }
  return {
    ...requestComputerGrant({
      mode,
      scope: args.scope,
      duration_seconds: args.duration_seconds,
      reason: args.reason
    }),
    ...EXPERIMENTAL_META
  }
}

export const computerCancelHandler: ToolHandler = async (args) => {
  const reason = typeof args.reason === 'string' && args.reason.trim()
    ? args.reason.trim()
    : 'cancelled by desktop_computer_cancel'
  return {
    ...cancelComputerGrant(reason),
    ...EXPERIMENTAL_META
  }
}

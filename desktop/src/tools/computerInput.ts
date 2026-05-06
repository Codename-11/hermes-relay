import { spawn } from 'node:child_process'
import { promises as fs } from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

export interface ComputerDisplayBounds {
  id: string
  x?: number
  y?: number
  width?: number
  height?: number
}

export interface NormalizedComputerAction {
  action: 'mouse_move' | 'left_click' | 'right_click' | 'double_click' | 'scroll' | 'key' | 'type' | 'wait'
  x?: number
  y?: number
  text?: string
  keys?: string
  scrollY?: number
  durationMs?: number
  intent?: string
  returnScreenshot: boolean
}

export type ComputerActionValidation = {
  ok: true
  action: NormalizedComputerAction
} | {
  ok: false
  code: string
  message: string
}

export interface ComputerInputResult {
  backend: string
  platform: NodeJS.Platform
  stdout?: string
  stderr?: string
}

interface SpawnOutput {
  stdout: string
  stderr: string
  exitCode: number
  ran: boolean
}

const INPUT_TIMEOUT_MS = 10_000
const MAX_TYPE_CHARS = 500
const MAX_WAIT_MS = 10_000

const KEY_ALIASES: Record<string, string> = {
  enter: '{ENTER}',
  tab: '{TAB}',
  escape: '{ESC}',
  esc: '{ESC}',
  backspace: '{BACKSPACE}',
  delete: '{DELETE}',
  del: '{DELETE}',
  up: '{UP}',
  down: '{DOWN}',
  left: '{LEFT}',
  right: '{RIGHT}',
  home: '{HOME}',
  end: '{END}',
  pageup: '{PGUP}',
  pagedown: '{PGDN}',
  space: ' ',
  ctrlc: '^c',
  'ctrl+c': '^c',
  ctrlv: '^v',
  'ctrl+v': '^v',
  ctrla: '^a',
  'ctrl+a': '^a'
}

function numberArg(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return null
  }
  return Math.round(value)
}

function boolArg(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback
}

function stringArg(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null
  }
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

function normalizeActionName(value: unknown): NormalizedComputerAction['action'] | null {
  if (typeof value !== 'string') {
    return null
  }
  const normalized = value.trim().toLowerCase()
  switch (normalized) {
    case 'move':
    case 'mouse_move':
      return 'mouse_move'
    case 'click':
    case 'left_click':
      return 'left_click'
    case 'right_click':
      return 'right_click'
    case 'double_click':
      return 'double_click'
    case 'scroll':
      return 'scroll'
    case 'key':
    case 'hotkey':
      return 'key'
    case 'type':
    case 'type_text':
      return 'type'
    case 'wait':
      return 'wait'
    default:
      return null
  }
}

function coordinate(args: Record<string, unknown>): { x: number; y: number } | null {
  const raw = args.coordinate
  if (Array.isArray(raw) && raw.length === 2) {
    const x = numberArg(raw[0])
    const y = numberArg(raw[1])
    if (x !== null && y !== null) {
      return { x, y }
    }
  }

  const x = numberArg(args.x)
  const y = numberArg(args.y)
  if (x !== null && y !== null) {
    return { x, y }
  }
  return null
}

function pointWithinDisplays(x: number, y: number, displays: ComputerDisplayBounds[]): boolean {
  const bounded = displays.filter(d =>
    typeof d.x === 'number' &&
    typeof d.y === 'number' &&
    typeof d.width === 'number' &&
    typeof d.height === 'number'
  )
  if (bounded.length === 0) {
    return true
  }
  return bounded.some(d =>
    x >= d.x! &&
    y >= d.y! &&
    x < d.x! + d.width! &&
    y < d.y! + d.height!
  )
}

function normalizeKeys(value: string): string | null {
  const compact = value.replaceAll(/\s+/g, '').toLowerCase()
  return KEY_ALIASES[compact] ?? null
}

export function summarizeComputerAction(action: NormalizedComputerAction): string {
  switch (action.action) {
    case 'mouse_move':
      return `move pointer to (${action.x}, ${action.y})`
    case 'left_click':
    case 'right_click':
    case 'double_click':
      return `${action.action.replace('_', ' ')} at (${action.x}, ${action.y})`
    case 'scroll':
      return `scroll ${action.scrollY} at ${action.x === undefined ? 'current pointer' : `(${action.x}, ${action.y})`}`
    case 'key':
      return `send key ${action.keys}`
    case 'type':
      return `type ${action.text?.length ?? 0} characters`
    case 'wait':
      return `wait ${action.durationMs}ms`
  }
}

export function validateComputerAction(
  args: Record<string, unknown>,
  displays: ComputerDisplayBounds[]
): ComputerActionValidation {
  const action = normalizeActionName(args.action)
  if (!action) {
    return {
      ok: false,
      code: 'invalid_request',
      message: 'desktop_computer_action requires a supported action name.'
    }
  }

  const base = {
    action,
    intent: stringArg(args.intent) ?? undefined,
    returnScreenshot: boolArg(args.return_screenshot, false)
  }

  if (action === 'wait') {
    const raw = numberArg(args.duration_ms)
    const durationMs = raw ?? 500
    if (durationMs < 0 || durationMs > MAX_WAIT_MS) {
      return {
        ok: false,
        code: 'invalid_request',
        message: `wait duration_ms must be between 0 and ${MAX_WAIT_MS}.`
      }
    }
    return { ok: true, action: { ...base, action, durationMs } }
  }

  if (action === 'type') {
    const text = typeof args.text === 'string' ? args.text : ''
    if (!text) {
      return { ok: false, code: 'invalid_request', message: 'type action requires non-empty text.' }
    }
    if (text.length > MAX_TYPE_CHARS) {
      return {
        ok: false,
        code: 'invalid_request',
        message: `type action text is capped at ${MAX_TYPE_CHARS} characters.`
      }
    }
    return { ok: true, action: { ...base, action, text } }
  }

  if (action === 'key') {
    const raw = stringArg(args.keys) ?? stringArg(args.key) ?? stringArg(args.text)
    const keys = raw ? normalizeKeys(raw) : null
    if (!keys) {
      return {
        ok: false,
        code: 'invalid_request',
        message: 'key action requires an allowed key such as Enter, Tab, Escape, Backspace, arrows, Ctrl+C, Ctrl+V, or Ctrl+A.'
      }
    }
    return { ok: true, action: { ...base, action, keys } }
  }

  const point = coordinate(args)
  if (!point && action !== 'scroll') {
    return {
      ok: false,
      code: 'invalid_request',
      message: `${action} requires coordinate: [x, y].`
    }
  }
  if (point && !pointWithinDisplays(point.x, point.y, displays)) {
    return {
      ok: false,
      code: 'coordinate_out_of_bounds',
      message: `coordinate [${point.x}, ${point.y}] is outside the advertised display bounds.`
    }
  }

  if (action === 'scroll') {
    const scrollObj = args.scroll && typeof args.scroll === 'object' && !Array.isArray(args.scroll)
      ? args.scroll as Record<string, unknown>
      : {}
    const scrollY = numberArg(args.delta_y) ?? numberArg(scrollObj.dy) ?? numberArg(scrollObj.y) ?? 0
    if (scrollY === 0 || Math.abs(scrollY) > 5000) {
      return {
        ok: false,
        code: 'invalid_request',
        message: 'scroll requires non-zero delta_y/dy with absolute value <= 5000.'
      }
    }
    return {
      ok: true,
      action: {
        ...base,
        action,
        x: point?.x,
        y: point?.y,
        scrollY
      }
    }
  }

  return {
    ok: true,
    action: {
      ...base,
      action,
      x: point!.x,
      y: point!.y
    }
  }
}

function escapeSendKeysText(text: string): string {
  return text.replaceAll(/([+^%~(){}\[\]])/g, '{$1}')
}

function windowsScript(action: NormalizedComputerAction): string {
  const payloadBase64 = Buffer.from(JSON.stringify({
    action: action.action,
    x: action.x,
    y: action.y,
    text: action.text ? escapeSendKeysText(action.text) : undefined,
    keys: action.keys,
    scrollY: action.scrollY
  }), 'utf8').toString('base64')

  return `
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -Namespace HermesRelay -Name Win32Input -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("user32.dll")]
public static extern bool SetCursorPos(int X, int Y);
[System.Runtime.InteropServices.DllImport("user32.dll")]
public static extern void mouse_event(uint dwFlags, uint dx, uint dy, int dwData, System.UIntPtr dwExtraInfo);
'@
$payloadJson = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('${payloadBase64}'))
$p = $payloadJson | ConvertFrom-Json
$MOUSEEVENTF_LEFTDOWN = 0x0002
$MOUSEEVENTF_LEFTUP = 0x0004
$MOUSEEVENTF_RIGHTDOWN = 0x0008
$MOUSEEVENTF_RIGHTUP = 0x0010
$MOUSEEVENTF_WHEEL = 0x0800
function MoveIfNeeded {
  if ($null -ne $p.x -and $null -ne $p.y) {
    [HermesRelay.Win32Input]::SetCursorPos([int]$p.x, [int]$p.y) | Out-Null
    Start-Sleep -Milliseconds 50
  }
}
switch ($p.action) {
  'mouse_move' {
    MoveIfNeeded
  }
  'left_click' {
    MoveIfNeeded
    [HermesRelay.Win32Input]::mouse_event($MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 40
    [HermesRelay.Win32Input]::mouse_event($MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
  }
  'right_click' {
    MoveIfNeeded
    [HermesRelay.Win32Input]::mouse_event($MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 40
    [HermesRelay.Win32Input]::mouse_event($MOUSEEVENTF_RIGHTUP, 0, 0, 0, [UIntPtr]::Zero)
  }
  'double_click' {
    MoveIfNeeded
    for ($i = 0; $i -lt 2; $i++) {
      [HermesRelay.Win32Input]::mouse_event($MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
      Start-Sleep -Milliseconds 35
      [HermesRelay.Win32Input]::mouse_event($MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
      Start-Sleep -Milliseconds 80
    }
  }
  'scroll' {
    MoveIfNeeded
    [HermesRelay.Win32Input]::mouse_event($MOUSEEVENTF_WHEEL, 0, 0, [int]$p.scrollY, [UIntPtr]::Zero)
  }
  'type' {
    [System.Windows.Forms.SendKeys]::SendWait([string]$p.text)
  }
  'key' {
    [System.Windows.Forms.SendKeys]::SendWait([string]$p.keys)
  }
  default {
    throw "unsupported action: $($p.action)"
  }
}
`.trim()
}

function runPowerShellScript(script: string, signal: AbortSignal): Promise<SpawnOutput> {
  return new Promise(resolve => {
    void (async () => {
      const tmpScript = path.join(os.tmpdir(), `hermes-computer-input-${Date.now()}-${process.pid}.ps1`)
      try {
        await fs.writeFile(tmpScript, script, 'utf8')
      } catch (e) {
        resolve({
          stdout: '',
          stderr: e instanceof Error ? e.message : String(e),
          exitCode: -1,
          ran: true
        })
        return
      }

      let child
      try {
        child = spawn(
          'powershell',
          ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', tmpScript],
          { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true }
        )
      } catch {
        await fs.unlink(tmpScript).catch(() => undefined)
        resolve({ stdout: '', stderr: '', exitCode: -1, ran: false })
        return
      }

      let stdout = ''
      let stderr = ''
      let settled = false
      const finish = async (output: SpawnOutput) => {
        if (settled) {
          return
        }
        settled = true
        clearTimeout(timer)
        signal.removeEventListener('abort', onAbort)
        await fs.unlink(tmpScript).catch(() => undefined)
        resolve(output)
      }
      const onAbort = () => {
        try {
          child.kill('SIGKILL')
        } catch {
          /* ignore */
        }
      }
      const timer = setTimeout(onAbort, INPUT_TIMEOUT_MS)
      timer.unref?.()
      signal.addEventListener('abort', onAbort, { once: true })

      child.stdout?.on('data', (chunk: Buffer) => {
        stdout += chunk.toString('utf8')
      })
      child.stderr?.on('data', (chunk: Buffer) => {
        stderr += chunk.toString('utf8')
      })
      child.on('error', (err: NodeJS.ErrnoException) => {
        void finish({
          stdout,
          stderr: err.code === 'ENOENT' ? '' : err.message,
          exitCode: -1,
          ran: err.code !== 'ENOENT'
        })
      })
      child.on('close', code => {
        void finish({
          stdout,
          stderr,
          exitCode: typeof code === 'number' ? code : -1,
          ran: true
        })
      })
    })()
  })
}

export async function runComputerInputAction(
  action: NormalizedComputerAction,
  signal: AbortSignal
): Promise<ComputerInputResult> {
  if (action.action === 'wait') {
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(resolve, action.durationMs ?? 500)
      signal.addEventListener('abort', () => {
        clearTimeout(timer)
        reject(new Error('aborted'))
      }, { once: true })
    })
    return { backend: 'timer', platform: process.platform }
  }

  if (process.platform !== 'win32') {
    throw new Error(`desktop computer input is currently implemented only on Windows; platform=${process.platform}`)
  }

  const result = await runPowerShellScript(windowsScript(action), signal)
  if (!result.ran) {
    throw new Error('PowerShell is required for the Windows computer input backend')
  }
  if (result.exitCode !== 0) {
    const detail = result.stderr.trim().slice(0, 500) || `exit code ${result.exitCode}`
    throw new Error(`Windows input action failed: ${detail}`)
  }

  return {
    backend: 'windows_powershell_user32',
    platform: process.platform,
    stdout: result.stdout,
    stderr: result.stderr
  }
}

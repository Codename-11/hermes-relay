import { spawn } from 'node:child_process'

import {
  cancelComputerGrant,
  computerGrantAllowsTarget,
  getActiveComputerGrant,
  getComputerGrantSummary,
  getComputerUseRuntimeSummary,
  hasComputerInputGrant,
  hasComputerObserveGrant,
  normalizeComputerGrantDurationSeconds,
  normalizeComputerGrantReason,
  normalizeComputerGrantScope,
  requestComputerGrant,
  type ComputerGrantMode
} from '../computerGrants.js'
import { evaluateSensitiveTarget, hasAuthenticatedControlIdentity, type ComputerTarget } from '../computerControlSecurity.js'
import {
  CuaDriverAdapter,
  closeCuaControlSession,
  computerControlLifecycleStatus,
  getCuaControlSession,
  recordCuaActionEvent,
  selectComputerControlBackend
} from '../cuaDriver.js'
import { approveComputerGrant } from '../computerActionApproval.js'
import {
  runComputerInputAction,
  validateComputerAction
} from '../computerInput.js'
import type { ToolContext, ToolHandler } from '../router.js'
import { readDesktopUseSettingsSync } from '../../lib/desktopUseSettings.js'
import { screenshotHandler } from './screenshot.js'

const STATUS_TIMEOUT_MS = 5_000
const EXPERIMENTAL_META = Object.freeze({
  experimental: true,
  phase: 'phase_3_task_grant_approval',
  control_model: 'desktop_tool_consent_plus_task_grant_approval'
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

function failure(
  code: string,
  message: string,
  extra: Record<string, unknown> = {},
  authority?: ToolContext['controlSession']
): Record<string, unknown> {
  return {
    ok: false,
    code,
    message,
    ...EXPERIMENTAL_META,
    grant: getComputerGrantSummary(authority),
    ...extra
  }
}

function parseGrantMode(value: unknown): ComputerGrantMode | null {
  if (value === 'observe' || value === 'assist' || value === 'control') {
    return value
  }
  return null
}

function positiveInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null
}

function cuaTarget(args: Record<string, unknown>): Pick<ComputerTarget, 'pid' | 'windowId'> | null {
  const pid = positiveInteger(args.pid)
  const windowId = positiveInteger(args.window_id)
  if (pid === null || windowId === null) return null
  return { pid, windowId }
}

async function cuaPreflight(args: Record<string, unknown>, ctx: ToolContext): Promise<
  | { ok: true; target: ComputerTarget; grantId: string | null; session: Awaited<ReturnType<typeof getCuaControlSession>> }
  | { ok: false; result: Record<string, unknown> }
> {
  if (!hasAuthenticatedControlIdentity(ctx.controlSession) || !ctx.controlSecurity) {
    return { ok: false, result: failure('authenticated_control_session_required', 'Structured CUA control requires server-attested relay, requester, run, and target identity.', {}, ctx.controlSession) }
  }
  const requested = cuaTarget(args)
  if (!requested) return { ok: false, result: failure('invalid_target', 'Structured CUA control requires positive pid and window_id.', {}, ctx.controlSession) }
  const session = await getCuaControlSession({ ...ctx.controlSession, targetDeviceId: ctx.controlSession.targetDeviceId })
  const listed = await session.listWindows(requested.pid, ctx.abortSignal)
  const windows = Array.isArray(listed.windows) ? listed.windows : []
  const exact = windows.find(item => isObject(item) && item.window_id === requested.windowId)
  if (!isObject(exact)) {
    return { ok: false, result: failure('target_not_found', 'CUA Driver did not report the exact requested PID and window.', {}, ctx.controlSession) }
  }
  const target: ComputerTarget = {
    ...requested,
    app: typeof exact.app_name === 'string' ? exact.app_name : undefined,
    title: typeof exact.title === 'string' ? exact.title : undefined,
    executable: typeof exact.executable === 'string' ? exact.executable : undefined
  }
  const sensitive = evaluateSensitiveTarget(target)
  if (!sensitive.allowed) {
    return { ok: false, result: failure('sensitive_target_blocked', 'Structured control is blocked for missing or sensitive application identity.', { reason: sensitive.reason }, ctx.controlSession) }
  }
  if (!computerGrantAllowsTarget(ctx.controlSession, target)) {
    return { ok: false, result: failure('grant_target_mismatch', 'The active computer grant does not cover this application target.', {}, ctx.controlSession) }
  }
  return { ok: true, target, grantId: getActiveComputerGrant(ctx.controlSession)?.id ?? null, session }
}

async function cuaSnapshot(args: Record<string, unknown>, ctx: ToolContext): Promise<Record<string, unknown>> {
  const check = await cuaPreflight(args, ctx)
  if (!check.ok) return check.result
  const authority = ctx.controlSession!
  const raw = await check.session.snapshot({
    pid: check.target.pid,
    windowId: check.target.windowId,
    query: argString(args.query).trim() || undefined,
    includeScreenshot: args.include_screenshot !== false,
    maxElements: typeof args.max_elements === 'number' ? args.max_elements : undefined,
    maxDepth: typeof args.max_depth === 'number' ? args.max_depth : undefined
  }, ctx.abortSignal)
  const generation = typeof raw.snapshot_id === 'string' ? raw.snapshot_id : ''
  if (!generation) return failure('invalid_backend_response', 'CUA Driver snapshot did not include a generation.', {}, authority)
  const elements = Array.isArray(raw.elements) ? raw.elements : []
  const safeElements = elements.slice(0, 1_000).map(item => {
    if (!isObject(item)) return item
    const driverToken = typeof item.element_token === 'string' ? item.element_token : undefined
    const output = { ...item }
    delete output.element_token
    if (driverToken) {
      output.snapshot_token = ctx.controlSecurity!.issueSnapshotToken({
        authority,
        grantId: check.grantId,
        target: check.target,
        snapshotGeneration: generation,
        driverElementToken: driverToken
      })
    }
    return output
  })
  return {
    ok: true,
    ...EXPERIMENTAL_META,
    backend: 'cua_driver',
    snapshot_generation: generation,
    target: check.target,
    elements: safeElements,
    tree_markdown: raw.tree_markdown,
    screenshot_base64: raw.screenshot_base64,
    screenshot_width: raw.screenshot_width,
    screenshot_height: raw.screenshot_height,
    truncated: elements.length > safeElements.length
  }
}

async function cuaAction(args: Record<string, unknown>, ctx: ToolContext): Promise<Record<string, unknown>> {
  const check = await cuaPreflight(args, ctx)
  if (!check.ok) return check.result
  const authority = ctx.controlSession!
  const token = argString(args.snapshot_token).trim()
  const binding = token ? ctx.controlSecurity!.consumeSnapshotToken(token, {
    authority,
    grantId: check.grantId,
    target: check.target,
    ...(typeof args.snapshot_generation === 'string' ? { snapshotGeneration: args.snapshot_generation } : {})
  }) : null
  if (!binding?.driverElementToken) {
    return failure('invalid_or_stale_snapshot', 'A fresh one-use snapshot_token for this exact session, grant, PID, and window is required.', {}, authority)
  }
  const element = { pid: check.target.pid, windowId: check.target.windowId, elementToken: binding.driverElementToken }
  const action = argString(args.action).trim()
  let result: Record<string, unknown>
  if (action === 'click_element') result = await check.session.clickElement(element, ctx.abortSignal)
  else if (action === 'set_value') result = await check.session.setElementValue(element, argString(args.value), ctx.abortSignal)
  else if (action === 'press_key') result = await check.session.pressKey(check.target, argString(args.key), ctx.abortSignal)
  else if (action === 'scroll_element') {
    const direction = argString(args.direction) as 'up' | 'down' | 'left' | 'right'
    if (!['up', 'down', 'left', 'right'].includes(direction)) return failure('invalid_request', 'direction must be up, down, left, or right.', {}, authority)
    result = await check.session.scroll(element, direction, positiveInteger(args.amount) ?? 1, ctx.abortSignal)
  } else return failure('invalid_request', 'Unsupported structured CUA action.', {}, authority)

  const after = await check.session.snapshot({ pid: check.target.pid, windowId: check.target.windowId, includeScreenshot: false }, ctx.abortSignal)
  recordCuaActionEvent({
    action,
    target_app: check.target.app,
    target_pid: check.target.pid,
    target_window_id: check.target.windowId,
    verification: 'snapshot_captured'
  })
  return {
    ok: true,
    ...EXPERIMENTAL_META,
    backend: 'cua',
    dispatch: 'background',
    control_session_id: authority.controlSessionId,
    target_app: check.target.app,
    target_title: check.target.title,
    target_pid: check.target.pid,
    target_window_id: check.target.windowId,
    action,
    verification: 'snapshot_captured',
    phase: 'structured_primary',
    result,
    verification_snapshot: after
  }
}

export const computerStatusHandler: ToolHandler = async (_args, ctx) => {
  const grant = getActiveComputerGrant(ctx.controlSession)
  const runtime = getComputerUseRuntimeSummary(ctx.controlSession)
  const inputBackendReady = runtime.consented === true && process.platform === 'win32'
  const fullAccess = runtime.full_access === true
  const settings = readDesktopUseSettingsSync()
  const cua = settings.computer_control_engine === 'cua' ? await CuaDriverAdapter.status() : null
  const lifecycle = computerControlLifecycleStatus()
  return {
    ok: true,
    ...EXPERIMENTAL_META,
    platform: process.platform,
    displays: await getDisplays(),
    runtime,
    computer_control_engine: {
      ...lifecycle,
      selected: settings.computer_control_engine,
      effective: lifecycle.active_backend === 'cua'
        ? 'cua'
        : lifecycle.active_backend === 'legacy_compat'
          ? 'legacy'
          : settings.computer_control_engine === 'cua' && cua?.ready ? 'cua' : 'legacy',
      cursor_enabled: settings.cua_cursor_enabled,
      foreground_escalation_enabled: false,
      cua,
      message: settings.computer_control_engine === 'cua' && !cua?.ready
        ? `CUA is unavailable before control starts; new sessions use explicit compatibility mode. ${cua?.reason ?? ''}`.trim()
        : null
    },
    permissions: {
      screenshot: fullAccess ? 'full_access' : grant ? 'granted' : 'grant_required',
      input: fullAccess || grant?.mode === 'assist' || grant?.mode === 'control'
        ? inputBackendReady
          ? fullAccess ? 'full_access' : 'granted_until_expiry'
          : 'grant_active_but_input_backend_unavailable'
        : 'not_granted',
      accessibility: cua?.ready ? 'available' : 'unavailable'
    },
    grant: getComputerGrantSummary(ctx.controlSession),
    overlay: {
      visible: ctx.interactive,
      state: ctx.interactive ? 'cli_grant_prompt_available' : 'not_available',
      message: ctx.interactive
        ? 'Local grant approval is available through the CLI or Hermes-Relay CLI UI.'
        : 'This client is non-interactive; use the Hermes-Relay CLI UI approval card or hermes-relay grants.'
    },
    safety: {
      host_input: process.platform === 'win32' ? 'windows_only_with_local_approval' : 'unsupported_platform',
      action_policy:
        'desktop_computer_action requires desktop-tool consent and an approved assist/control grant'
    }
  }
}

export const computerScreenshotHandler: ToolHandler = async (args, ctx) => {
  if (!hasComputerObserveGrant(ctx.controlSession)) {
    return failure(
      'grant_required',
      'Screenshot observe mode requires an active observe/assist/control grant. Call desktop_computer_grant_request first.',
      {},
      ctx.controlSession
    )
  }

  const settings = readDesktopUseSettingsSync()
  const selection = ctx.controlSession
    ? await selectComputerControlBackend(ctx.controlSession.controlSessionId, settings.computer_control_engine, settings.cua_cursor_enabled)
    : null
  if (selection?.backend === 'cua') {
    // Window-scoped observation belongs to CUA. A caller may still request the
    // existing read-only display capture; it is not an input-backend fallback.
    if (args.pid !== undefined || args.window_id !== undefined) return cuaSnapshot(args, ctx)
  }

  if (args.region !== undefined && args.region !== null) {
    return failure(
      'not_implemented',
      'Region capture/cropping is planned but not implemented yet. Capture a full display instead.'
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
    backend: 'system_capture',
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
      reason: 'Sensitive-window redaction is planned but not implemented yet.'
    },
    grant: getComputerGrantSummary(ctx.controlSession)
  }
}

export const computerActionHandler: ToolHandler = async (args, ctx) => {
  const action = argString(args.action).trim()
  if (!action) {
    return failure('invalid_request', 'desktop_computer_action requires an action name.')
  }
  const settings = readDesktopUseSettingsSync()
  const selection = ctx.controlSession
    ? await selectComputerControlBackend(ctx.controlSession.controlSessionId, settings.computer_control_engine, settings.cua_cursor_enabled)
    : null
  if (selection?.backend === 'cua') {
    if (!['click_element', 'set_value', 'press_key', 'scroll_element'].includes(action)) {
      return failure(
        'cua_structured_action_required',
        'CUA is selected; legacy coordinate and foreground-routed input is disabled. Take a structured snapshot and use an element action.',
        { action },
        ctx.controlSession
      )
    }
    if (!hasComputerInputGrant(ctx.controlSession)) {
      return failure('grant_required', 'Structured host input requires an active assist/control grant.', { action }, ctx.controlSession)
    }
    return cuaAction(args, ctx)
  }
  if (['click_element', 'set_value', 'press_key', 'scroll_element'].includes(action)) {
    return failure('cua_unavailable', 'Structured CUA actions are unavailable in the compatibility backend.', { action }, ctx.controlSession)
  }
  const displays = await getDisplays()
  const validation = validateComputerAction(args, displays)
  if (!validation.ok) {
    return failure(validation.code, validation.message, { action })
  }

  if (!hasComputerInputGrant(ctx.controlSession)) {
    return failure(
      'grant_required',
      'Host input is disabled. Request and locally approve an assist/control grant first.',
      { action },
      ctx.controlSession
    )
  }

  const normalized = validation.action
  const started = Date.now()
  const inputResult = await runComputerInputAction(normalized, ctx.abortSignal)
  const response: Record<string, unknown> = {
    ok: true,
    ...EXPERIMENTAL_META,
    action: normalized.action,
    status: 'executed',
    performed_at: new Date().toISOString(),
    duration_ms: Date.now() - started,
    input_backend: inputResult.backend,
    backend: 'legacy_compat',
    dispatch: 'foreground_compatibility',
    phase: selection?.reason === 'cua_unavailable_before_session' ? 'pre_session_safe_fallback' : 'explicit_compatibility',
    control_session_id: ctx.controlSession?.controlSessionId,
    verification: normalized.returnScreenshot ? 'snapshot_captured' : 'not_requested',
    platform: inputResult.platform,
    grant: getComputerGrantSummary(ctx.controlSession)
  }
  if (normalized.returnScreenshot) {
    response.after_screenshot = await computerScreenshotHandler({ display: args.display ?? 'primary' }, ctx)
  }
  recordCuaActionEvent({
    action: normalized.action,
    verification: normalized.returnScreenshot ? 'snapshot_captured' : 'not_requested'
  })
  return response
}

export const computerGrantRequestHandler: ToolHandler = async (args, ctx) => {
  const mode = parseGrantMode(args.mode)
  if (!mode) {
    return failure('invalid_request', 'mode must be one of observe, assist, or control.')
  }
  const runtime = getComputerUseRuntimeSummary(ctx.controlSession)
  if (runtime.full_access === true) {
    return {
      ...requestComputerGrant({
        mode,
        scope: args.scope,
        duration_seconds: args.duration_seconds,
        reason: args.reason
      }, ctx.controlSession),
      ...EXPERIMENTAL_META
    }
  }
  if (mode !== 'observe') {
    if (runtime.consented !== true) {
      return failure(
        'computer_use_consent_required',
        'Assist/control grants require local desktop-tool consent for this relay URL before task-scoped input grants can be created.',
        {},
        ctx.controlSession
      )
    }
    const approval = await approveComputerGrant({
      mode,
      durationSeconds: normalizeComputerGrantDurationSeconds(args.duration_seconds),
      reason: normalizeComputerGrantReason(args.reason),
      scope: normalizeComputerGrantScope(args.scope) as Record<string, unknown>,
      interactive: ctx.interactive
    })
    if (!approval.approved) {
      return failure(
        approval.reason.startsWith('non-interactive mode') ? 'not_interactive' : 'rejected',
        approval.reason,
        { requested_mode: mode }
      )
    }
  }
  return {
    ...requestComputerGrant({
      mode,
      scope: args.scope,
      duration_seconds: args.duration_seconds,
      reason: args.reason
    }, ctx.controlSession),
    ...EXPERIMENTAL_META
  }
}

export const computerCancelHandler: ToolHandler = async (args, ctx) => {
  const reason = typeof args.reason === 'string' && args.reason.trim()
    ? args.reason.trim()
    : 'cancelled by desktop_computer_cancel'
  if (ctx.controlSession) await closeCuaControlSession(ctx.controlSession.controlSessionId, reason)
  return {
    ...cancelComputerGrant(reason, ctx.controlSession),
    ...EXPERIMENTAL_META
  }
}

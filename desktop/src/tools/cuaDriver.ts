import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { createHash } from 'node:crypto'
import { access, realpath } from 'node:fs/promises'
import { homedir } from 'node:os'
import { basename, dirname, join, relative, resolve, sep } from 'node:path'

import { readDesktopUseSettingsSync } from '../lib/desktopUseSettings.js'

const SUPPORTED_MIN_VERSION = [0, 19, 3] as const
const SUPPORTED_MAX_VERSION = [0, 20, 0] as const
const DEFAULT_TIMEOUT_MS = 8_000
const MAX_OUTPUT_BYTES = 4 * 1024 * 1024
const REQUIRED_TOOLS = Object.freeze([
  'health_report',
  'start_session',
  'end_session',
  'list_windows',
  'get_window_state',
  'click',
  'set_value',
  'press_key',
  'scroll'
])
const ALLOWED_TOOLS = Object.freeze([...REQUIRED_TOOLS, 'set_agent_cursor_enabled'])

export interface CuaProcessResult {
  stdout: string
  stderr: string
  exitCode: number
}

export interface CuaProcessRunner {
  run(
    executable: string,
    args: readonly string[],
    options?: { stdin?: string; timeoutMs?: number; signal?: AbortSignal; env?: NodeJS.ProcessEnv }
  ): Promise<CuaProcessResult>
}

export interface CuaRuntimeStatus {
  available: boolean
  ready: boolean
  binaryPath?: string
  binaryVersion?: string
  permissionMode?: 'standard' | 'bounded'
  health?: 'ok' | 'not_checked'
  reason?: string
}

export interface CuaHealthStatus {
  state: 'healthy' | 'degraded' | 'error'
  checkedAt: string
  overall?: string
  reason?: string
  temporaryWindowsCompatibility: true
}

export interface CuaControlSessionIdentity {
  controlSessionId: string
  targetDeviceId: string
  hostUrl?: string
  requestId?: string
  relaySessionId?: string
  requesterDeviceId?: string
  chatSessionId?: string
  runId?: string
}

export interface CuaWindowTarget {
  pid: number
  windowId: number
}

export interface CuaSnapshotOptions extends CuaWindowTarget {
  query?: string
  includeScreenshot?: boolean
  maxElements?: number
  maxDepth?: number
}

export interface CuaElementTarget extends CuaWindowTarget {
  elementToken: string
}

export type CuaToolResult = Record<string, unknown>

export type ComputerControlBackend = 'cua' | 'legacy_compat'

export interface ComputerControlBackendSelection {
  backend: ComputerControlBackend
  reason: 'cua_ready' | 'explicit_compatibility' | 'cua_unavailable_before_session'
  selectedAt: string
  cursorEnabled: boolean
}

export interface CuaActionEvent {
  action: string
  target_app?: string
  target_pid?: number
  target_window_id?: number
  verification: 'snapshot_captured' | 'not_requested' | 'failed'
  occurred_at: string
}

export interface ComputerControlLifecycleStatus {
  active_sessions: number
  cursor_enabled: boolean
  active_backend: 'cua' | 'legacy_compat' | 'mixed' | 'idle'
  last_action: CuaActionEvent | null
}

interface CuaManifest {
  schema_version?: unknown
  binary_version?: unknown
  binary_path?: unknown
}

interface CuaHealthReport {
  schema_version?: unknown
  driver_version?: unknown
  overall?: unknown
}

interface JsonRpcResponse {
  id?: number
  result?: unknown
  error?: { message?: unknown }
}

/**
 * CUA is a separately maintained child process. Do not copy the relay daemon's
 * environment into it: that environment can contain pairing tokens, provider
 * credentials, and other secrets unrelated to desktop control.
 */
export function cuaDriverEnvironment(extra: NodeJS.ProcessEnv = {}): NodeJS.ProcessEnv {
  const allowed = [
    'SystemRoot', 'WINDIR', 'COMSPEC', 'PATHEXT', 'PATH', 'TEMP', 'TMP',
    'LOCALAPPDATA', 'APPDATA', 'USERPROFILE', 'HOMEDRIVE', 'HOMEPATH',
    'PROCESSOR_ARCHITECTURE', 'PROCESSOR_ARCHITEW6432', 'NUMBER_OF_PROCESSORS'
  ]
  const env: NodeJS.ProcessEnv = {}
  for (const key of allowed) {
    const value = process.env[key]
    if (value !== undefined) env[key] = value
  }
  return { ...env, ...extra, CUA_DRIVER_RS_TELEMETRY_ENABLED: '0' }
}

class CuaMcpClient {
  private readonly child: ChildProcessWithoutNullStreams
  private readonly pending = new Map<number, { resolve(value: unknown): void; reject(error: Error): void; timer: NodeJS.Timeout }>()
  private nextId = 1
  private stdout = ''
  private closed = false

  private constructor(binaryPath: string) {
    this.child = spawn(binaryPath, ['mcp', '--socket', '\\\\.\\pipe\\cua-driver'], {
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
      env: cuaDriverEnvironment()
    })
    this.child.stdout.on('data', (chunk: Buffer) => this.receive(chunk))
    this.child.on('error', error => this.failAll(new CuaRuntimeError(`CUA MCP transport failed: ${error.message}`, 'transport')))
    this.child.on('close', code => this.failAll(new CuaRuntimeError(`CUA MCP transport closed (${code ?? 'unknown'})`, 'transport')))
  }

  static async connect(binaryPath: string, expectedVersion: string): Promise<CuaMcpClient> {
    const client = new CuaMcpClient(binaryPath)
    const initialized = await client.request('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'hermes-relay-desktop', version: '1' }
    }) as { protocolVersion?: unknown; serverInfo?: { version?: unknown } }
    if (initialized.protocolVersion !== '2025-06-18') {
      client.close()
      throw new CuaRuntimeError('CUA MCP protocol version is incompatible', 'incompatible')
    }
    if (initialized.serverInfo?.version !== expectedVersion) {
      client.close()
      throw new CuaRuntimeError(
        `CUA MCP daemon version ${String(initialized.serverInfo?.version ?? 'unknown')} does not match the verified binary ${expectedVersion}`,
        'incompatible'
      )
    }
    client.notify('notifications/initialized', {})
    return client
  }

  async call(tool: string, args: Record<string, unknown>): Promise<CuaToolResult> {
    const result = await this.request('tools/call', { name: tool, arguments: args }) as {
      isError?: unknown
      structuredContent?: unknown
      content?: Array<{ type?: unknown; text?: unknown; data?: unknown; mimeType?: unknown }>
    }
    if (result.isError === true) {
      // Driver errors can quote UI labels or entered values. Treat the broker
      // result as sensitive and expose only the stable operation name.
      throw new CuaRuntimeError(`CUA Driver ${tool} rejected the action`, 'transport')
    }
    if (result.structuredContent && typeof result.structuredContent === 'object' && !Array.isArray(result.structuredContent)) {
      const structured = { ...(result.structuredContent as CuaToolResult) }
      const image = result.content?.find(item => item.type === 'image' && typeof item.data === 'string')
      if (image) {
        structured.screenshot_base64 = image.data
        structured.screenshot_mime_type = image.mimeType
      }
      return structured
    }
    const text = result.content?.find(item => item.type === 'text' && typeof item.text === 'string')?.text
    return text ? parseJsonObject(String(text), `CUA Driver ${tool}`) : {}
  }

  close(): void {
    if (this.closed) return
    this.closed = true
    this.child.stdin.end()
    const timer = setTimeout(() => this.child.kill('SIGKILL'), 1_000)
    timer.unref?.()
  }

  private request(method: string, params: Record<string, unknown>): Promise<unknown> {
    if (this.closed) return Promise.reject(new CuaRuntimeError('CUA MCP transport is closed', 'closed'))
    const id = this.nextId++
    return new Promise((resolvePromise, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new CuaRuntimeError(`CUA MCP ${method} timed out`, 'transport'))
      }, DEFAULT_TIMEOUT_MS)
      timer.unref?.()
      this.pending.set(id, { resolve: resolvePromise, reject, timer })
      this.child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`)
    })
  }

  private notify(method: string, params: Record<string, unknown>): void {
    this.child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method, params })}\n`)
  }

  private receive(chunk: Buffer): void {
    this.stdout += chunk.toString('utf8')
    if (Buffer.byteLength(this.stdout, 'utf8') > MAX_OUTPUT_BYTES) {
      this.failAll(new CuaRuntimeError('CUA MCP response exceeded the output limit', 'transport'))
      this.child.kill('SIGKILL')
      return
    }
    let newline = this.stdout.indexOf('\n')
    while (newline >= 0) {
      const line = this.stdout.slice(0, newline).trim()
      this.stdout = this.stdout.slice(newline + 1)
      if (line) this.resolveLine(line)
      newline = this.stdout.indexOf('\n')
    }
  }

  private resolveLine(line: string): void {
    let message: JsonRpcResponse
    try { message = JSON.parse(line) as JsonRpcResponse } catch { return }
    if (typeof message.id !== 'number') return
    const pending = this.pending.get(message.id)
    if (!pending) return
    this.pending.delete(message.id)
    clearTimeout(pending.timer)
    if (message.error) pending.reject(new CuaRuntimeError(`CUA MCP error: ${String(message.error.message ?? 'unknown')}`, 'transport'))
    else pending.resolve(message.result)
  }

  private failAll(error: Error): void {
    if (this.closed && this.pending.size === 0) return
    this.closed = true
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer)
      pending.reject(error)
    }
    this.pending.clear()
  }
}

export interface CuaRuntimeOptions {
  platform?: NodeJS.Platform
  homeDir?: string
  runner?: CuaProcessRunner
}

export class CuaRuntimeError extends Error {
  constructor(
    message: string,
    readonly code: 'unavailable' | 'incompatible' | 'degraded' | 'transport' | 'closed'
  ) {
    super(message)
    this.name = 'CuaRuntimeError'
  }
}

export class SpawnCuaProcessRunner implements CuaProcessRunner {
  run(
    executable: string,
    args: readonly string[],
    options: { stdin?: string; timeoutMs?: number; signal?: AbortSignal; env?: NodeJS.ProcessEnv } = {}
  ): Promise<CuaProcessResult> {
    return new Promise((resolvePromise, reject) => {
      const child = spawn(executable, [...args], {
        stdio: ['pipe', 'pipe', 'pipe'],
        windowsHide: true,
        env: options.env ?? cuaDriverEnvironment()
      })
      let stdout = ''
      let stderr = ''
      let settled = false
      let outputBytes = 0
      const finish = (error?: Error, exitCode = -1) => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        options.signal?.removeEventListener('abort', abort)
        if (error) reject(error)
        else resolvePromise({ stdout, stderr, exitCode })
      }
      const abort = () => {
        child.kill('SIGKILL')
        finish(new CuaRuntimeError('CUA Driver process was cancelled', 'transport'))
      }
      const append = (current: string, chunk: Buffer): string => {
        outputBytes += chunk.length
        if (outputBytes > MAX_OUTPUT_BYTES) {
          child.kill('SIGKILL')
          finish(new CuaRuntimeError('CUA Driver response exceeded the output limit', 'transport'))
          return current
        }
        return current + chunk.toString('utf8')
      }
      const timer = setTimeout(() => {
        child.kill('SIGKILL')
        finish(new CuaRuntimeError('CUA Driver process timed out', 'transport'))
      }, options.timeoutMs ?? DEFAULT_TIMEOUT_MS)
      timer.unref?.()
      options.signal?.addEventListener('abort', abort, { once: true })
      child.stdout.on('data', (chunk: Buffer) => { stdout = append(stdout, chunk) })
      child.stderr.on('data', (chunk: Buffer) => { stderr = append(stderr, chunk) })
      child.on('error', error => finish(new CuaRuntimeError(`CUA Driver process failed: ${error.message}`, 'transport')))
      child.on('close', code => finish(undefined, code ?? -1))
      child.stdin.end(options.stdin ?? '')
    })
  }
}

function compareVersion(left: readonly number[], right: readonly number[]): number {
  for (let i = 0; i < 3; i += 1) {
    const delta = (left[i] ?? 0) - (right[i] ?? 0)
    if (delta !== 0) return delta
  }
  return 0
}

function parseVersion(value: string): [number, number, number] | null {
  const match = /(?:^|\s)(\d+)\.(\d+)\.(\d+)(?:\s|$)/.exec(value.trim())
  return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : null
}

function parseJsonObject(text: string, label: string): Record<string, unknown> {
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new CuaRuntimeError(`${label} returned invalid JSON`, 'transport')
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new CuaRuntimeError(`${label} returned a non-object response`, 'transport')
  }
  return parsed as Record<string, unknown>
}

function validatePositiveInteger(value: number, name: string): void {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new CuaRuntimeError(`${name} must be a positive integer`, 'transport')
  }
}

function validateElementToken(value: string): void {
  if (!/^e[0-9a-f]+$/i.test(value)) {
    throw new CuaRuntimeError('elementToken is not a valid opaque CUA element token', 'transport')
  }
}

function derivedSessionId(identity: CuaControlSessionIdentity): string {
  const fields = [
    identity.controlSessionId,
    identity.targetDeviceId,
    identity.hostUrl ?? '',
    identity.relaySessionId ?? '',
    identity.requesterDeviceId ?? '',
    identity.chatSessionId ?? '',
    identity.runId ?? ''
  ]
  if (!identity.controlSessionId.trim() || !identity.targetDeviceId.trim()) {
    throw new CuaRuntimeError('CUA control session identity is incomplete', 'transport')
  }
  return `hermes-${createHash('sha256').update(fields.join('\u0000')).digest('hex').slice(0, 32)}`
}

function controlIdentityFingerprint(identity: CuaControlSessionIdentity): string {
  return createHash('sha256').update([
    identity.controlSessionId,
    identity.targetDeviceId,
    identity.hostUrl ?? '',
    identity.relaySessionId ?? '',
    identity.requesterDeviceId ?? '',
    identity.chatSessionId ?? '',
    identity.runId ?? ''
  ].join('\u0000')).digest('hex')
}

async function canonicalBinary(options: CuaRuntimeOptions): Promise<string> {
  const platform = options.platform ?? process.platform
  if (platform !== 'win32') {
    throw new CuaRuntimeError(`CUA Driver adapter is not enabled on ${platform}`, 'unavailable')
  }
  const home = resolve(options.homeDir ?? homedir())
  const packages = join(home, '.cua-driver', 'packages')
  const releases = join(packages, 'releases')
  const candidate = join(packages, 'current', 'cua-driver.exe')
  let resolvedCandidate: string
  let resolvedReleases: string
  try {
    ;[resolvedCandidate, resolvedReleases] = await Promise.all([realpath(candidate), realpath(releases)])
    await access(resolvedCandidate)
  } catch {
    throw new CuaRuntimeError('Canonical CUA Driver package is not installed', 'unavailable')
  }
  const inside = relative(resolvedReleases, resolvedCandidate)
  if (!inside || inside.startsWith(`..${sep}`) || inside === '..' || resolve(resolvedCandidate) === resolve(resolvedReleases)) {
    throw new CuaRuntimeError('Canonical CUA Driver package resolved outside its release store', 'incompatible')
  }
  if (basename(resolvedCandidate).toLowerCase() !== 'cua-driver.exe' || basename(dirname(resolvedCandidate)).length === 0) {
    throw new CuaRuntimeError('Canonical CUA Driver executable path is invalid', 'incompatible')
  }
  return resolvedCandidate
}

export class CuaDriverAdapter {
  readonly binaryPath: string
  readonly binaryVersion: string
  readonly permissionMode: 'standard' | 'bounded'
  private readonly sessions = new Map<string, CuaControlSession>()

  private constructor(
    binaryPath: string,
    binaryVersion: string,
    permissionMode: 'standard' | 'bounded',
    private readonly runner: CuaProcessRunner,
    private readonly usePersistentMcp: boolean,
    private readonly supportsCursorToggle: boolean
  ) {
    this.binaryPath = binaryPath
    this.binaryVersion = binaryVersion
    this.permissionMode = permissionMode
  }

  static async connect(options: CuaRuntimeOptions = {}): Promise<CuaDriverAdapter> {
    const runner = options.runner ?? new SpawnCuaProcessRunner()
    const binaryPath = await canonicalBinary(options)
    const run = async (args: readonly string[], stdin?: string): Promise<string> => {
      const result = await runner.run(binaryPath, args, {
        stdin,
        env: cuaDriverEnvironment()
      })
      if (result.exitCode !== 0) {
        throw new CuaRuntimeError(`CUA Driver probe ${args[0] ?? 'command'} failed`, 'transport')
      }
      return result.stdout.trim()
    }
    const versionText = await run(['--version'])
    const versionTuple = parseVersion(versionText)
    if (!versionTuple || compareVersion(versionTuple, SUPPORTED_MIN_VERSION) < 0 || compareVersion(versionTuple, SUPPORTED_MAX_VERSION) >= 0) {
      throw new CuaRuntimeError(`Unsupported CUA Driver version: ${versionText || 'unknown'}`, 'incompatible')
    }
    const manifest = parseJsonObject(await run(['manifest', '--pretty']), 'CUA Driver manifest') as CuaManifest
    if (manifest.schema_version !== '1' || manifest.binary_version !== versionTuple.join('.')) {
      throw new CuaRuntimeError('CUA Driver manifest schema or version is incompatible', 'incompatible')
    }
    const manifestPath = typeof manifest.binary_path === 'string' ? await realpath(manifest.binary_path).catch(() => '') : ''
    if (resolve(manifestPath).toLowerCase() !== resolve(binaryPath).toLowerCase()) {
      throw new CuaRuntimeError('CUA Driver manifest identifies a different executable', 'incompatible')
    }
    const toolNames = new Set((await run(['list-tools'])).split(/\r?\n/).map(line => line.split(':', 1)[0]?.trim()).filter(Boolean))
    const missingTools = REQUIRED_TOOLS.filter(tool => !toolNames.has(tool))
    if (missingTools.length > 0) {
      throw new CuaRuntimeError(`CUA Driver is missing required tools: ${missingTools.join(', ')}`, 'incompatible')
    }
    const statusText = await run(['status'])
    const permissionMatch = /permission mode:\s*(standard|bounded|unrestricted)\b/i.exec(statusText)
    if (!permissionMatch || permissionMatch[1]?.toLowerCase() === 'unrestricted') {
      throw new CuaRuntimeError('CUA Driver permission mode is unavailable or unrestricted', 'incompatible')
    }
    const permissionMode = permissionMatch[1]!.toLowerCase() as 'standard' | 'bounded'
    // Temporary Windows compatibility policy for trycua/cua#3103. The global
    // health_report performs a whole-desktop UIA walk with a fixed timeout and
    // can poison the driver's busy flag after a false timeout. Runtime
    // readiness is therefore based on the canonical binary, manifest, tool
    // contract, daemon status, and safe permission mode. Individual structured
    // actions still fail closed. Keep health_report as an explicit diagnostic
    // via healthStatus(), and remove this split when upstream fixes #3103.
    return new CuaDriverAdapter(
      binaryPath,
      versionTuple.join('.'),
      permissionMode,
      runner,
      options.runner === undefined,
      toolNames.has('set_agent_cursor_enabled')
    )
  }

  static async status(options: CuaRuntimeOptions = {}): Promise<CuaRuntimeStatus> {
    try {
      const adapter = await CuaDriverAdapter.connect(options)
      return {
        available: true,
        ready: true,
        binaryPath: adapter.binaryPath,
        binaryVersion: adapter.binaryVersion,
        permissionMode: adapter.permissionMode,
        health: 'not_checked',
        reason: 'Runtime checks passed; Windows accessibility health is checked separately.'
      }
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error)
      return { available: error instanceof CuaRuntimeError && error.code !== 'unavailable', ready: false, reason }
    }
  }

  static async healthStatus(options: CuaRuntimeOptions = {}): Promise<CuaHealthStatus> {
    const checkedAt = new Date().toISOString()
    try {
      const adapter = await CuaDriverAdapter.connect(options)
      const runner = options.runner ?? new SpawnCuaProcessRunner()
      const result = await runner.run(adapter.binaryPath, ['call', 'health_report'], {
        stdin: '{}',
        timeoutMs: DEFAULT_TIMEOUT_MS,
        env: cuaDriverEnvironment()
      })
      if (result.exitCode !== 0) {
        return {
          state: 'error', checkedAt, temporaryWindowsCompatibility: true,
          reason: `CUA Driver health probe exited ${result.exitCode}`
        }
      }
      const health = parseJsonObject(result.stdout.trim(), 'CUA Driver health report') as CuaHealthReport
      if (health.schema_version !== '1' || health.driver_version !== adapter.binaryVersion) {
        return {
          state: 'error', checkedAt, temporaryWindowsCompatibility: true,
          reason: 'CUA Driver health report schema or version is incompatible'
        }
      }
      const overall = String(health.overall ?? 'unknown')
      return overall === 'ok'
        ? { state: 'healthy', checkedAt, overall, temporaryWindowsCompatibility: true }
        : {
            state: 'degraded', checkedAt, overall, temporaryWindowsCompatibility: true,
            reason: `CUA Driver reported ${overall}; runtime remains available and actions still fail closed.`
          }
    } catch (error) {
      return {
        state: 'error', checkedAt, temporaryWindowsCompatibility: true,
        reason: error instanceof Error ? error.message : String(error)
      }
    }
  }

  async openSession(identity: CuaControlSessionIdentity, signal?: AbortSignal, cursorEnabled = false): Promise<CuaControlSession> {
    const id = derivedSessionId(identity)
    if (this.sessions.has(id)) {
      throw new CuaRuntimeError('CUA control session is already active', 'transport')
    }
    if (signal?.aborted) throw new CuaRuntimeError('CUA control session was cancelled', 'transport')
    const mcp = this.usePersistentMcp ? await CuaMcpClient.connect(this.binaryPath, this.binaryVersion) : null
    const invoke = async (tool: string, args: Record<string, unknown>, invokeSignal?: AbortSignal): Promise<CuaToolResult> => {
      if (invokeSignal?.aborted) throw new CuaRuntimeError('CUA action was cancelled', 'transport')
      if (!ALLOWED_TOOLS.includes(tool)) throw new CuaRuntimeError('Attempted to invoke a non-allowlisted CUA Driver tool', 'transport')
      if (mcp) return mcp.call(tool, args)
      const result = await this.runner.run(this.binaryPath, ['call', tool], {
        stdin: JSON.stringify(args),
        timeoutMs: DEFAULT_TIMEOUT_MS,
        signal: invokeSignal,
        env: cuaDriverEnvironment()
      })
      if (result.exitCode !== 0) {
        throw new CuaRuntimeError(`CUA Driver ${tool} failed`, 'transport')
      }
      const payload = parseJsonObject(result.stdout.trim(), `CUA Driver ${tool}`)
      if (payload.isError === true) throw new CuaRuntimeError(`CUA Driver ${tool} rejected the action`, 'transport')
      return payload
    }
    await invoke('start_session', { session: id, capture_scope: 'window' }, signal).catch(error => {
      mcp?.close()
      throw error
    })
    if (this.supportsCursorToggle) {
      await invoke('set_agent_cursor_enabled', { session: id, enabled: cursorEnabled }, signal).catch(error => {
        mcp?.close()
        throw error
      })
    }
    const session = new CuaControlSession(
      id,
      invoke,
      () => mcp?.close(),
      () => this.sessions.delete(id)
    )
    this.sessions.set(id, session)
    return session
  }

  async closeAll(reason = 'Hermes control authority ended'): Promise<void> {
    const sessions = [...this.sessions.values()]
    await Promise.allSettled(sessions.map(session => session.close(reason)))
  }

}

export class CuaControlSession {
  private closed = false

  constructor(
    private readonly id: string,
    private readonly invoke: (tool: string, args: Record<string, unknown>, signal?: AbortSignal) => Promise<CuaToolResult>,
    private readonly closeTransport: () => void,
    private readonly onClose: () => void
  ) {}

  private ensureOpen(): void {
    if (this.closed) throw new CuaRuntimeError('CUA control session is closed', 'closed')
  }

  async listWindows(pid?: number, signal?: AbortSignal): Promise<CuaToolResult> {
    this.ensureOpen()
    if (pid !== undefined) validatePositiveInteger(pid, 'pid')
    return this.invoke('list_windows', pid === undefined ? {} : { pid }, signal)
  }

  async snapshot(options: CuaSnapshotOptions, signal?: AbortSignal): Promise<CuaToolResult> {
    this.ensureOpen()
    validatePositiveInteger(options.pid, 'pid')
    validatePositiveInteger(options.windowId, 'windowId')
    return this.invoke('get_window_state', {
      pid: options.pid,
      window_id: options.windowId,
      session: this.id,
      query: options.query,
      include_screenshot: options.includeScreenshot,
      max_elements: options.maxElements ?? 1_000,
      max_depth: options.maxDepth ?? 20
    }, signal)
  }

  async clickElement(target: CuaElementTarget, signal?: AbortSignal): Promise<CuaToolResult> {
    this.ensureOpen()
    validatePositiveInteger(target.pid, 'pid')
    validatePositiveInteger(target.windowId, 'windowId')
    validateElementToken(target.elementToken)
    return this.invoke('click', {
      pid: target.pid,
      window_id: target.windowId,
      element_token: target.elementToken,
      session: this.id,
      scope: 'window',
      delivery_mode: 'background'
    }, signal)
  }

  async setElementValue(target: CuaElementTarget, value: string, signal?: AbortSignal): Promise<CuaToolResult> {
    this.ensureOpen()
    validatePositiveInteger(target.pid, 'pid')
    validatePositiveInteger(target.windowId, 'windowId')
    validateElementToken(target.elementToken)
    return this.invoke('set_value', {
      pid: target.pid,
      window_id: target.windowId,
      element_token: target.elementToken,
      value,
      session: this.id
    }, signal)
  }

  async pressKey(target: CuaWindowTarget, key: string, signal?: AbortSignal): Promise<CuaToolResult> {
    this.ensureOpen()
    validatePositiveInteger(target.pid, 'pid')
    validatePositiveInteger(target.windowId, 'windowId')
    if (!/^(?:return|tab|escape|up|down|left|right|space|delete|home|end|pageup|pagedown|f(?:[1-9]|1[0-2])|[a-z0-9])$/i.test(key)) {
      throw new CuaRuntimeError('key is not in the allowlisted CUA key vocabulary', 'transport')
    }
    return this.invoke('press_key', {
      pid: target.pid,
      window_id: target.windowId,
      key: key.toLowerCase(),
      session: this.id,
      scope: 'window',
      delivery_mode: 'background'
    }, signal)
  }

  async scroll(target: CuaElementTarget, direction: 'up' | 'down' | 'left' | 'right', amount: number, signal?: AbortSignal): Promise<CuaToolResult> {
    this.ensureOpen()
    validatePositiveInteger(target.pid, 'pid')
    validatePositiveInteger(target.windowId, 'windowId')
    validateElementToken(target.elementToken)
    if (!Number.isSafeInteger(amount) || amount < 1 || amount > 20) {
      throw new CuaRuntimeError('scroll amount must be an integer from 1 to 20', 'transport')
    }
    return this.invoke('scroll', {
      pid: target.pid,
      window_id: target.windowId,
      element_token: target.elementToken,
      direction,
      amount,
      session: this.id,
      delivery_mode: 'background'
    }, signal)
  }

  async close(_reason = 'Hermes control session ended'): Promise<void> {
    if (this.closed) return
    this.closed = true
    this.onClose()
    try {
      await this.invoke('end_session', { session: this.id })
    } finally {
      this.closeTransport()
    }
  }
}

let sharedAdapter: Promise<CuaDriverAdapter> | null = null
const sharedSessions = new Map<string, Promise<CuaControlSession>>()
const sharedSessionIdentities = new Map<string, string>()
const backendSelections = new Map<string, ComputerControlBackendSelection>()
let lastActionEvent: CuaActionEvent | null = null
let lifecycleListener: ((status: ComputerControlLifecycleStatus) => void) | null = null

export function setComputerControlLifecycleListener(
  listener: ((status: ComputerControlLifecycleStatus) => void) | null
): () => void {
  lifecycleListener = listener
  listener?.(computerControlLifecycleStatus())
  return () => { if (lifecycleListener === listener) lifecycleListener = null }
}

function publishLifecycle(): void {
  lifecycleListener?.(computerControlLifecycleStatus())
}
let testSessionFactory: ((identity: CuaControlSessionIdentity) => Promise<CuaControlSession>) | null = null

/** Test-only dependency seam; production callers must never configure this. */
export function setCuaControlSessionFactoryForTests(
  factory: ((identity: CuaControlSessionIdentity) => Promise<CuaControlSession>) | null
): void {
  testSessionFactory = factory
  sharedSessions.clear()
  sharedSessionIdentities.clear()
  backendSelections.clear()
  sharedAdapter = null
}

/** Select once per outer Hermes control session; an active session never changes backend. */
export async function selectComputerControlBackend(
  controlSessionId: string,
  selected: 'legacy' | 'cua',
  cursorEnabled: boolean
): Promise<ComputerControlBackendSelection> {
  const existing = backendSelections.get(controlSessionId)
  if (existing) return existing
  let selection: ComputerControlBackendSelection
  if (selected === 'legacy') {
    selection = {
      backend: 'legacy_compat',
      reason: 'explicit_compatibility',
      selectedAt: new Date().toISOString(),
      cursorEnabled: false
    }
  } else if (testSessionFactory) {
    selection = { backend: 'cua', reason: 'cua_ready', selectedAt: new Date().toISOString(), cursorEnabled }
  } else {
    try {
      sharedAdapter ??= CuaDriverAdapter.connect()
      await sharedAdapter
      selection = { backend: 'cua', reason: 'cua_ready', selectedAt: new Date().toISOString(), cursorEnabled }
    } catch {
      sharedAdapter = null
      selection = {
        backend: 'legacy_compat',
        reason: 'cua_unavailable_before_session',
        selectedAt: new Date().toISOString(),
        cursorEnabled: false
      }
    }
  }
  backendSelections.set(controlSessionId, selection)
  publishLifecycle()
  return selection
}

export function recordCuaActionEvent(event: Omit<CuaActionEvent, 'occurred_at'>): void {
  lastActionEvent = { ...event, occurred_at: new Date().toISOString() }
  publishLifecycle()
}

export function computerControlLifecycleStatus(): ComputerControlLifecycleStatus {
  const active = [...backendSelections.values()]
  const backends = new Set(active.map(item => item.backend))
  return {
    active_sessions: active.length,
    cursor_enabled: active.some(item => item.backend === 'cua' && item.cursorEnabled),
    active_backend: active.length === 0 ? 'idle' : backends.size > 1 ? 'mixed' : active[0]!.backend,
    last_action: lastActionEvent
  }
}

/** Return the one bounded CUA session owned by an authenticated Hermes control session. */
export async function getCuaControlSession(identity: CuaControlSessionIdentity): Promise<CuaControlSession> {
  const key = identity.controlSessionId
  const fingerprint = controlIdentityFingerprint(identity)
  const existingFingerprint = sharedSessionIdentities.get(key)
  if (existingFingerprint && existingFingerprint !== fingerprint) {
    throw new CuaRuntimeError('CUA control session identity changed after session creation', 'incompatible')
  }
  let session = sharedSessions.get(key)
  if (!session) {
    if (testSessionFactory) {
      session = testSessionFactory(identity)
    } else {
      sharedAdapter ??= CuaDriverAdapter.connect()
      const settings = readDesktopUseSettingsSync()
      const selection = backendSelections.get(key)
      session = sharedAdapter.then(adapter => adapter.openSession(
        identity,
        undefined,
        selection?.backend === 'cua' ? selection.cursorEnabled : settings.cua_cursor_enabled
      ))
    }
    sharedSessions.set(key, session)
    sharedSessionIdentities.set(key, fingerprint)
    void session.catch(() => {
      sharedSessions.delete(key)
      sharedSessionIdentities.delete(key)
    })
  }
  return session
}

/** Revoke a CUA cursor/session when its outer Hermes authority ends. */
export async function closeCuaControlSession(controlSessionId: string, reason?: string): Promise<void> {
  const session = sharedSessions.get(controlSessionId)
  sharedSessions.delete(controlSessionId)
  sharedSessionIdentities.delete(controlSessionId)
  backendSelections.delete(controlSessionId)
  publishLifecycle()
  if (session) await session.then(value => value.close(reason)).catch(() => undefined)
}

export async function closeAllCuaControlSessions(reason?: string): Promise<void> {
  const sessions = [...sharedSessions.values()]
  sharedSessions.clear()
  sharedSessionIdentities.clear()
  backendSelections.clear()
  publishLifecycle()
  await Promise.allSettled(sessions.map(session => session.then(value => value.close(reason))))
  sharedAdapter = null
}

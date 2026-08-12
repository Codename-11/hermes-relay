import { spawn } from 'node:child_process'
import { platform } from 'node:os'
import { resolve } from 'node:path'

import { approveComputerGrant } from '../computerActionApproval.js'
import { capabilityPolicy } from '../capabilityRuntime.js'
import type { ToolContext, ToolHandler } from '../router.js'

const MAX_OUTPUT_BYTES = 1024 * 1024
const DEFAULT_TIMEOUT_MS = 30_000
const MAX_TIMEOUT_MS = 120_000
const MAX_ARGUMENTS = 128
const MAX_ARGUMENT_LENGTH = 8192

function requiredString(value: unknown, name: string): string {
  if (typeof value !== 'string' || !value.trim() || value.includes('\0')) {
    throw new Error(`missing or invalid "${name}" argument`)
  }
  return value.trim()
}

function argumentsList(value: unknown): string[] {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.length > MAX_ARGUMENTS) {
    throw new Error(`"arguments" must be an array with at most ${MAX_ARGUMENTS} entries`)
  }
  return value.map((argument, index) => {
    if (typeof argument !== 'string' || argument.includes('\0') || argument.length > MAX_ARGUMENT_LENGTH) {
      throw new Error(`invalid USB utility argument at index ${index}`)
    }
    return argument
  })
}

function timeoutMs(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
    ? Math.min(Math.floor(value * 1000), MAX_TIMEOUT_MS)
    : DEFAULT_TIMEOUT_MS
}

async function authorize(
  operation: string,
  scope: Record<string, unknown>,
  reason: unknown,
  ctx: ToolContext
): Promise<void> {
  const policy = capabilityPolicy('usb')
  if (policy === 'disabled') throw new Error('Raw USB access is disabled for this Hermes host')
  if (policy === 'allow') return
  const approval = await approveComputerGrant({
    mode: `usb.${operation}`,
    durationSeconds: 120,
    reason: typeof reason === 'string' && reason.trim() ? reason.trim() : `Use raw USB ${operation}`,
    scope,
    interactive: ctx.interactive
  })
  if (!approval.approved) throw new Error(approval.reason || 'Raw USB request rejected locally')
}

async function runProcess(
  executable: string,
  args: string[],
  ctx: ToolContext,
  cwd?: string,
  timeout = DEFAULT_TIMEOUT_MS
) {
  const child = spawn(executable, args, {
    cwd,
    windowsHide: true,
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
    env: process.env
  })
  let stdout = Buffer.alloc(0)
  let stderr = Buffer.alloc(0)
  let stdoutBytes = 0
  let stderrBytes = 0
  let killedBy: 'timeout' | 'abort' | null = null
  child.stdout.on('data', (chunk: Buffer) => {
    stdoutBytes += chunk.length
    if (stdout.length < MAX_OUTPUT_BYTES) stdout = Buffer.concat([stdout, chunk.subarray(0, MAX_OUTPUT_BYTES - stdout.length)])
  })
  child.stderr.on('data', (chunk: Buffer) => {
    stderrBytes += chunk.length
    if (stderr.length < MAX_OUTPUT_BYTES) stderr = Buffer.concat([stderr, chunk.subarray(0, MAX_OUTPUT_BYTES - stderr.length)])
  })
  const timer = setTimeout(() => { killedBy = 'timeout'; child.kill('SIGKILL') }, timeout)
  timer.unref?.()
  const abort = () => { killedBy = 'abort'; child.kill('SIGKILL') }
  ctx.abortSignal.addEventListener('abort', abort, { once: true })
  try {
    const exitCode = await new Promise<number>((resolveExit, reject) => {
      child.once('error', reject)
      child.once('close', code => {
        if (killedBy) reject(new Error(killedBy === 'timeout' ? `USB utility timed out after ${timeout}ms` : 'USB request aborted'))
        else resolveExit(code ?? 1)
      })
    })
    return {
      stdout: stdout.toString('utf8'),
      stderr: stderr.toString('utf8'),
      exit_code: exitCode,
      executable,
      arguments: args,
      output: {
        limit_bytes_per_stream: MAX_OUTPUT_BYTES,
        stdout: { bytes: stdoutBytes, captured_bytes: stdout.length, truncated: stdoutBytes > stdout.length },
        stderr: { bytes: stderrBytes, captured_bytes: stderr.length, truncated: stderrBytes > stderr.length }
      }
    }
  } finally {
    clearTimeout(timer)
    ctx.abortSignal.removeEventListener('abort', abort)
  }
}

function enumerationCommand(): { executable: string; arguments: string[]; format: string } {
  if (platform() === 'win32') {
    return {
      executable: 'powershell.exe',
      arguments: [
        '-NoLogo', '-NoProfile', '-NonInteractive', '-Command',
        "$ErrorActionPreference='Stop'; @(Get-CimInstance Win32_PnPEntity | Where-Object { $_.PNPDeviceID -like 'USB\\*' } | Select-Object Name,PNPDeviceID,Status,Service,Manufacturer) | ConvertTo-Json -Compress"
      ],
      format: 'windows-pnp-json'
    }
  }
  if (platform() === 'darwin') {
    return { executable: 'system_profiler', arguments: ['SPUSBDataType', '-json'], format: 'system-profiler-json' }
  }
  return { executable: 'lsusb', arguments: [], format: 'lsusb-text' }
}

export const usbDevicesHandler: ToolHandler = async (args, ctx) => {
  await authorize('devices', {}, args.reason, ctx)
  const command = enumerationCommand()
  const result = await runProcess(command.executable, command.arguments, ctx)
  let devices: unknown = result.stdout.split(/\r?\n/).filter(Boolean)
  if (command.format.endsWith('-json') && result.stdout.trim()) {
    try { devices = JSON.parse(result.stdout) } catch { /* retain bounded raw lines */ }
  }
  return { ...result, format: command.format, devices }
}

export const usbRunHandler: ToolHandler = async (args, ctx) => {
  const executable = requiredString(args.executable, 'executable')
  const utilityArgs = argumentsList(args.arguments)
  const cwd = typeof args.cwd === 'string' && args.cwd.trim() ? resolve(ctx.cwd, args.cwd.trim()) : ctx.cwd
  await authorize('run', { executable, arguments: utilityArgs, cwd }, args.reason, ctx)
  return runProcess(executable, utilityArgs, ctx, cwd, timeoutMs(args.timeout))
}

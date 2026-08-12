import { spawn, spawnSync } from 'node:child_process'
import { resolve } from 'node:path'

import { approveComputerGrant } from '../computerActionApproval.js'
import { capabilityPolicy } from '../capabilityRuntime.js'
import type { ToolContext, ToolHandler } from '../router.js'

const MAX_OUTPUT_BYTES = 1024 * 1024
const DEFAULT_TIMEOUT_MS = 30_000
const MAX_TIMEOUT_MS = 120_000
const SERIAL_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/

export function adbBackendAvailable(env: NodeJS.ProcessEnv = process.env): boolean {
  const executable = env.HERMES_RELAY_ADB_PATH?.trim() || 'adb'
  const result = spawnSync(executable, ['version'], { windowsHide: true, stdio: 'ignore', env })
  return !result.error && result.status === 0
}

function requiredString(value: unknown, name: string): string {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`missing or invalid "${name}" argument`)
  return value.trim()
}

function serialArg(value: unknown): string {
  const serial = requiredString(value, 'serial')
  if (!SERIAL_PATTERN.test(serial)) throw new Error('invalid ADB serial')
  return serial
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
  if (policy === 'disabled') throw new Error('USB/ADB capability is disabled for this Hermes host')
  if (policy === 'allow') return
  const approval = await approveComputerGrant({
    mode: `usb.${operation}`,
    durationSeconds: 120,
    reason: typeof reason === 'string' && reason.trim() ? reason.trim() : `Run brokered ADB ${operation}`,
    scope,
    interactive: ctx.interactive
  })
  if (!approval.approved) throw new Error(approval.reason || 'USB/ADB request rejected locally')
}

async function runAdb(args: string[], ctx: ToolContext, timeout = DEFAULT_TIMEOUT_MS) {
  const executable = process.env.HERMES_RELAY_ADB_PATH?.trim() || 'adb'
  const child = spawn(executable, args, {
    windowsHide: true,
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
    const exitCode = await new Promise<number>((resolve, reject) => {
      child.once('error', reject)
      child.once('close', code => {
        if (killedBy) reject(new Error(killedBy === 'timeout' ? `ADB timed out after ${timeout}ms` : 'ADB request aborted'))
        else resolve(code ?? 1)
      })
    })
    return {
      stdout: stdout.toString('utf8'),
      stderr: stderr.toString('utf8'),
      exit_code: exitCode,
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

export const adbDevicesHandler: ToolHandler = async (args, ctx) => {
  await authorize('devices', {}, args.reason, ctx)
  const result = await runAdb(['devices', '-l'], ctx)
  const devices = result.stdout.split(/\r?\n/).slice(1).filter(Boolean).map(line => {
    const [serial = '', state = '', ...details] = line.trim().split(/\s+/)
    const metadata = Object.fromEntries(details.filter(item => item.includes(':')).map(item => item.split(/:(.*)/s).slice(0, 2)))
    return { serial, state, ...metadata }
  })
  return { ...result, devices }
}

export const adbShellHandler: ToolHandler = async (args, ctx) => {
  const serial = serialArg(args.serial)
  const command = requiredString(args.command, 'command')
  await authorize('shell', { serial, command }, args.reason, ctx)
  return runAdb(['-s', serial, 'shell', command], ctx, timeoutMs(args.timeout))
}

export const adbPushHandler: ToolHandler = async (args, ctx) => {
  const serial = serialArg(args.serial)
  const source = resolve(ctx.cwd, requiredString(args.source, 'source'))
  const destination = requiredString(args.destination, 'destination')
  await authorize('push', { serial, source, destination }, args.reason, ctx)
  return runAdb(['-s', serial, 'push', source, destination], ctx, timeoutMs(args.timeout))
}

export const adbPullHandler: ToolHandler = async (args, ctx) => {
  const serial = serialArg(args.serial)
  const source = requiredString(args.source, 'source')
  const destination = resolve(ctx.cwd, requiredString(args.destination, 'destination'))
  await authorize('pull', { serial, source, destination }, args.reason, ctx)
  return runAdb(['-s', serial, 'pull', source, destination], ctx, timeoutMs(args.timeout))
}

export const adbInstallHandler: ToolHandler = async (args, ctx) => {
  const serial = serialArg(args.serial)
  const apk = resolve(ctx.cwd, requiredString(args.apk, 'apk'))
  const replace = args.replace !== false
  await authorize('install', { serial, apk, replace }, args.reason, ctx)
  return runAdb(['-s', serial, 'install', ...(replace ? ['-r'] : []), apk], ctx, timeoutMs(args.timeout))
}

export const adbLogcatHandler: ToolHandler = async (args, ctx) => {
  const serial = serialArg(args.serial)
  const lines = typeof args.lines === 'number' && Number.isFinite(args.lines)
    ? Math.max(1, Math.min(Math.floor(args.lines), 5000))
    : 500
  await authorize('logcat', { serial, lines }, args.reason, ctx)
  return runAdb(['-s', serial, 'logcat', '-d', '-t', String(lines)], ctx, timeoutMs(args.timeout))
}

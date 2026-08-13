// daemon — headless WSS + DesktopToolRouter, runs forever.
//
// The missing piece between "it works" and "feels local": the interactive
// `shell` / `chat` commands only serve desktop tools while a terminal is
// open. The daemon closes that gap — install it once (see
// scripts/install-service-*) and the agent can reach the user's machine
// any time of day, not just when they have a shell attached.
//
// Design contract:
//   - Connects in locked mode when a paired host has not granted desktop tools.
//     Starting the connectivity daemon is safe and must not itself grant access.
//   - Inherits RelayTransport's reconnect state machine as-is. No custom
//     retry loop here — the transport's exp-backoff-to-30s + auth-resolve
//     semantics are already daemon-appropriate. Terminal auth failures
//     (auth.fail) exit non-zero so the service manager restarts us fresh
//     after the user re-pairs; the transient failure case is handled
//     inside the transport.
//   - `onChannel('desktop', ...)` listeners survive reconnects (the
//     transport's channelListeners Map is persistent), so the router's
//     attach only fires once at startup — no re-attach on every 'reconnected'.
//   - Logs are JSON-line by default (parseable by journald / log shippers);
//     --log-human opts into a pretty rendering, and it's auto-enabled when
//     stderr is a TTY so `hermes-relay daemon` in a terminal looks sane.
//
// Deferred to follow-ups:
//   - Service installer scripts (scripts/install-service-{win,linux,mac}).
//   - Foreground/daemon coordination when a shell attaches (server-side
//     single-client policy currently handles this — daemon reconnects
//     after the shell detaches; see roadmap for pause-while-interactive).
//   - --log-file <path>: for now, redirect stderr if you need a file.

import { spawn } from 'node:child_process'
import { closeSync, openSync, promises as fs } from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

import type { ParsedArgs } from '../cli.js'
import { desktopRelayIdentity } from '../deviceIdentity.js'
import { GatewayClient } from '../gatewayClient.js'
import type { GatewayEvent, SessionCreateResponse } from '../gatewayTypes.js'
import {
  clearDaemonStatus,
  isDaemonProcessAlive,
  isPidAlive,
  readDaemonStatus,
  writeDaemonStatus,
  type DaemonComputerGrantStatus,
  type DaemonState,
  type DaemonStatus
} from '../lib/daemonStatus.js'
import { rpcErrorMessage, asRpcResult } from '../lib/rpc.js'
import { effectiveHostAccessMode, effectiveHostCapabilityPolicies, getHostAccessMode, getHostCapabilityPolicies } from '../lib/hostAccessPolicy.js'
import { theme as makeTheme } from '../lib/theme.js'
import { printUsage, type UsageSpec } from '../lib/usage.js'
import { resolveFirstRunUrl } from '../relayUrlPrompt.js'
import { getSession } from '../remoteSessions.js'
import { probeCandidatesByPriority, secureFirstCandidates } from '../pairingQr.js'
import {
  advertisedDesktopTools,
  desktopHandlers,
  shouldAdvertiseComputerUse
} from '../tools/handlerSet.js'
import {
  cancelComputerGrant,
  configureComputerUseRuntime,
  getActiveComputerGrant,
  setComputerGrantChangeListener,
  type ComputerGrant
} from '../tools/computerGrants.js'
import { DesktopToolRouter } from '../tools/router.js'
import { configureCapabilityPolicies } from '../tools/capabilityRuntime.js'
import { adbBackendAvailable } from '../tools/handlers/adb.js'
import { RelayTransport } from '../transport/RelayTransport.js'
import { setupGracefulExit } from '../lib/gracefulExit.js'
import { grantBridgeDir } from '../lib/grantBridge.js'
import { currentProcessIdentity } from '../lib/processPrivilege.js'
import { consumeComputerGrantCancellation } from '../lib/desktopUseSettings.js'
import { startVoiceServer, type VoiceServer } from '../voiceServer.js'

const VOICE_DISCOVERY_FILE = 'desktop-voice.json'

/** Refresh the status-file `updated_at` on this cadence so `--status` can tell
 * a live daemon from a crashed one whose file lingers. */
const STATUS_HEARTBEAT_MS = 30_000
const DETACHED_START_TIMEOUT_MS = 20_000
const DETACHED_START_POLL_MS = 100

const DAEMON_USAGE: UsageSpec = {
  name: 'daemon',
  summary: 'run headless — expose desktop tools to the agent even when no shell is open',
  usage: [
    'daemon [run]',
    'daemon start [--administrator]',
    'daemon stop [--administrator]',
    'daemon restart [--administrator|--user]',
    'daemon status'
  ],
  subcommands: [
    { verb: 'run', desc: 'Run in the foreground (current console; default)' },
    { verb: 'start', desc: 'Start in the background — no console window; survives terminal close' },
    { verb: 'stop', desc: 'Stop the background daemon' },
    { verb: 'restart', desc: 'Restart the background daemon, preserving caller privileges by default' },
    { verb: 'status', desc: 'Print state + uptime of the running daemon (alias: --status)' }
  ],
  flags: [
    { flag: '--detach', desc: 'Alias for `daemon start` — run in the background' },
    { flag: '--administrator', desc: 'Windows: explicitly request UAC and run the daemon as Administrator' },
    { flag: '--user', desc: 'Windows: restart the daemon as the current unelevated user' },
    { flag: '--remote <url>', desc: 'Relay to connect to (default: stored/active session)' },
    { flag: '--token <token>', desc: 'Use an explicit session token (CI/provisioning)' },
    { flag: '--allow-tools', desc: 'Skip the stored-consent gate (only with --token; implies trust)' },
    { flag: '--no-voice', desc: 'Do not start the loopback voice server' },
    { flag: '--log-human', desc: 'Human-readable logs (auto on a TTY)' },
    { flag: '--log-json', desc: 'Force JSON-line logs even on a TTY' },
    { flag: '--experimental-computer-use', desc: 'One-process computer-use enable override (see top-level help)' }
  ],
  examples: [
    'hermes-relay daemon start',
    'hermes-relay daemon status',
    'hermes-relay daemon restart --administrator',
    'hermes-relay daemon restart --user',
    'hermes-relay daemon stop'
  ]
}

type LogLevel = 'info' | 'warn' | 'error'

interface LogFields {
  event: string
  [k: string]: unknown
}

/** Structured logger — writes one JSON object per line to stderr (the
 * daemon convention that lets journald / logrotate / jq interoperate), or
 * a human-readable line when --log-human is on (or stderr is a TTY). */
function makeLogger(human: boolean): {
  info: (fields: LogFields) => void
  warn: (fields: LogFields) => void
  error: (fields: LogFields) => void
} {
  const write = (level: LogLevel, fields: LogFields) => {
    const ts = new Date().toISOString()
    if (human) {
      const { event, ...rest } = fields
      const extras = Object.keys(rest).length
        ? ' ' + Object.entries(rest).map(([k, v]) => `${k}=${JSON.stringify(v)}`).join(' ')
        : ''
      const pad = level === 'info' ? 'INFO ' : level === 'warn' ? 'WARN ' : 'ERROR'
      process.stderr.write(`${ts} ${pad} ${event}${extras}\n`)
      return
    }
    process.stderr.write(JSON.stringify({ ts, level, ...fields }) + '\n')
  }
  return {
    info: (fields) => write('info', fields),
    warn: (fields) => write('warn', fields),
    error: (fields) => write('error', fields)
  }
}

function resolveRemoteOrNull(args: ParsedArgs): string | null {
  const v = args.flags.remote
  const url = (typeof v === 'string' ? v : null) ?? process.env.HERMES_RELAY_URL ?? null
  return url ? url.trim() : null
}

function fmtAge(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`
  if (seconds < 86_400) return `${Math.floor(seconds / 3600)}h`
  return `${Math.floor(seconds / 86_400)}d`
}

/** `daemon --status` — read the status file and report. Exit 0 if a daemon is
 * live, 1 if the file is stale (pid gone) so scripts can branch on it. */
async function printDaemonStatus(args: ParsedArgs): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  const status = await readDaemonStatus()
  if (!status) {
    process.stdout.write(
      t.muted('No daemon status file — the daemon is not running (or has never run).') + '\n'
    )
    return 1
  }
  const alive = isDaemonProcessAlive(status)
  if (args.flags.json) {
    process.stdout.write(JSON.stringify({ ...status, alive }, null, 2) + '\n')
    return alive ? 0 : 1
  }
  const now = Math.floor(Date.now() / 1000)
  const staleSec = Math.max(0, now - status.updated_at)
  const stale = staleSec > (STATUS_HEARTBEAT_MS / 1000) * 3
  const kv = (label: string, value: string): string => `  ${t.muted((label + ':').padEnd(9))} ${value}`

  process.stdout.write(t.bold('hermes-relay daemon') + '\n')
  if (!alive) {
    process.stdout.write(kv('state', `${t.err('not running')} ${t.muted(`(pid ${status.pid} gone — stale file)`)}`) + '\n')
  } else {
    const label =
      status.state === 'connected'
        ? t.ok('connected')
        : status.state === 'reconnecting'
          ? t.warn('reconnecting')
          : status.state
    process.stdout.write(
      kv('state', `${t.statusDot(status.state === 'connected')} ${label}${stale ? t.warn(' (heartbeat stale)') : ''}`) + '\n'
    )
  }
  process.stdout.write(kv('pid', String(status.pid)) + '\n')
  process.stdout.write(kv('relay', status.url) + '\n')
  process.stdout.write(kv('uptime', fmtAge(Math.max(0, now - status.started_at))) + '\n')
  process.stdout.write(kv('updated', `${fmtAge(staleSec)} ago`) + '\n')
  if (status.server_version) {
    process.stdout.write(kv('server', status.server_version) + '\n')
  }
  if (typeof status.advertised_tools === 'number') {
    process.stdout.write(kv('tools', `${status.advertised_tools} advertised`) + '\n')
  }
  if (status.voice_url) {
    process.stdout.write(kv('voice', status.voice_url) + '\n')
  }
  if (status.username || status.privilege) {
    const user = status.username ?? 'unknown user'
    const privilege = status.privilege === 'administrator' ? 'Administrator' : 'User'
    process.stdout.write(kv('account', `${user} (${privilege})`) + '\n')
  }
  process.stdout.write(kv('desktop', status.computer_use_enabled ? 'enabled' : 'disabled') + '\n')
  if (status.computer_grant?.active) {
    process.stdout.write(
      kv(
        'grant',
        `${status.computer_grant.mode} until ${status.computer_grant.expires_at ?? 'expiry unknown'}`
      ) + '\n'
    )
  }
  return alive ? 0 : 1
}

function daemonLogPath(): string {
  return path.join(os.homedir(), '.hermes', 'daemon.log')
}

type DetachedStartupResult =
  | { outcome: 'ready'; status: DaemonStatus }
  | { outcome: 'failed'; detail: string | null }
  | { outcome: 'timeout'; status: DaemonStatus | null }

interface DetachedStartupProbe {
  readStatus: () => Promise<DaemonStatus | null>
  isAlive: (pid: number) => boolean
  readFailure: () => Promise<string | null>
  now: () => number
  sleep: (ms: number) => Promise<void>
}

function daemonStatusIsReady(status: DaemonStatus | null, pid: number): boolean {
  return status?.pid === pid && status.state === 'connected'
}

/** Wait until the child proves it crossed the configuration, consent, and
 * authentication gates. A PID match is required so a stale status file from
 * an earlier daemon can never make a new start look successful. */
async function waitForDetachedStartup(
  pid: number,
  timeoutMs: number,
  probe: DetachedStartupProbe
): Promise<DetachedStartupResult> {
  const deadline = probe.now() + timeoutMs
  let lastStatus: DaemonStatus | null = null

  while (probe.now() < deadline) {
    lastStatus = await probe.readStatus()
    if (lastStatus && daemonStatusIsReady(lastStatus, pid)) {
      return { outcome: 'ready', status: lastStatus }
    }

    const failure = await probe.readFailure()
    if (failure) return { outcome: 'failed', detail: failure }
    if (!probe.isAlive(pid)) return { outcome: 'failed', detail: null }
    await probe.sleep(DETACHED_START_POLL_MS)
  }

  lastStatus = await probe.readStatus()
  if (lastStatus && daemonStatusIsReady(lastStatus, pid)) {
    return { outcome: 'ready', status: lastStatus }
  }
  const failure = await probe.readFailure()
  if (failure || !probe.isAlive(pid)) {
    return { outcome: 'failed', detail: failure }
  }
  return {
    outcome: 'timeout',
    status: lastStatus?.pid === pid ? lastStatus : null
  }
}

/** Read only log bytes written by this start attempt. Detached daemons use
 * JSON logs by default, but keep a conservative human-log fallback for an
 * explicit --log-human invocation. */
async function readDetachedStartupFailure(logPath: string, offset: number): Promise<string | null> {
  try {
    const handle = await fs.open(logPath, 'r')
    try {
      const stat = await handle.stat()
      if (stat.size <= offset) return null
      const length = Math.min(stat.size - offset, 64 * 1024)
      const start = stat.size - length
      const buffer = Buffer.alloc(length)
      await handle.read(buffer, 0, length, start)
      const lines = buffer.toString('utf8').split(/\r?\n/).filter(Boolean)
      for (let i = lines.length - 1; i >= 0; i -= 1) {
        const line = lines[i] ?? ''
        try {
          const record = JSON.parse(line) as Record<string, unknown>
          if (record.level !== 'error') continue
          const event = typeof record.event === 'string' ? record.event : 'startup_error'
          const detail =
            typeof record.message === 'string'
              ? record.message
              : typeof record.reason === 'string'
                ? record.reason
                : null
          return detail ? `${event}: ${detail}` : event
        } catch {
          if (/\bERROR\b/i.test(line)) return line.slice(0, 500)
        }
      }
    } finally {
      await handle.close()
    }
  } catch {
    /* best-effort diagnostic; process liveness remains authoritative */
  }
  return null
}

/** Rebuild the child argv for the foreground daemon from this invocation's
 * flags, so `daemon start --remote … --experimental-computer-use` forwards. */
function buildDaemonChildArgs(args: ParsedArgs): string[] {
  const out: string[] = ['daemon']
  const fwdValue = (name: string) => {
    const v = args.flags[name]
    if (typeof v === 'string') {
      out.push(`--${name}`, v)
    }
  }
  const fwdBool = (name: string) => {
    if (args.flags[name] === true) {
      out.push(`--${name}`)
    }
  }
  fwdValue('remote')
  fwdValue('token')
  for (const f of [
    'allow-tools',
    'no-voice',
    'log-json',
    'log-human',
    'experimental-computer-use',
    'no-computer-use',
    'no-color'
  ]) {
    fwdBool(f)
  }
  return out
}

type DaemonLifecycleSubcommand = 'start' | 'stop' | 'restart'

interface ElevationLaunchPlan {
  program: string
  args: string[]
  env: NodeJS.ProcessEnv
  targetProgram: string
  targetArgs: string[]
}

/** Quote one argv item for CommandLineToArgvW. Start-Process accepts a single
 * ArgumentList string, so preserve spaces, quotes, and trailing backslashes. */
function quoteWindowsArgument(value: string): string {
  if (value.length > 0 && !/[\s"]/u.test(value)) return value
  let out = '"'
  let slashes = 0
  for (const char of value) {
    if (char === '\\') {
      slashes += 1
      continue
    }
    if (char === '"') {
      out += '\\'.repeat(slashes * 2 + 1) + '"'
      slashes = 0
      continue
    }
    out += '\\'.repeat(slashes) + char
    slashes = 0
  }
  return out + '\\'.repeat(slashes * 2) + '"'
}

function executableInvocationPrefix(): { program: string; args: string[] } {
  const execIsNode = /node(\.exe)?$/i.test(path.basename(process.execPath))
  return {
    program: process.execPath,
    args: execIsNode ? [process.argv[1] ?? ''] : []
  }
}

/** Build the UAC launcher separately from execution so the exact privilege
 * boundary and forwarded argv are regression-testable. */
function buildElevationLaunchPlan(
  args: ParsedArgs,
  subcommand: DaemonLifecycleSubcommand
): ElevationLaunchPlan {
  const invocation = executableInvocationPrefix()
  const targetArgs = [
    ...invocation.args,
    'daemon',
    subcommand,
    ...buildDaemonChildArgs(args).slice(1),
    '--elevation-child'
  ]
  const commandLine = targetArgs.map(quoteWindowsArgument).join(' ')
  const script = [
    "$ErrorActionPreference = 'Stop'",
    '$argumentLine = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:HERMES_RELAY_ELEVATE_ARGS))',
    'try {',
    "  $child = Start-Process -FilePath $env:HERMES_RELAY_ELEVATE_PROGRAM -ArgumentList $argumentLine -Verb RunAs -WindowStyle Hidden -Wait -PassThru",
    '  exit $child.ExitCode',
    '} catch {',
    "  [Console]::Error.WriteLine('Administrator request failed or was canceled: ' + $_.Exception.Message)",
    '  exit 1',
    '}'
  ].join('\n')
  return {
    program: 'powershell.exe',
    args: ['-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script],
    env: {
      ...process.env,
      HERMES_RELAY_ELEVATE_PROGRAM: invocation.program,
      HERMES_RELAY_ELEVATE_ARGS: Buffer.from(commandLine, 'utf8').toString('base64')
    },
    targetProgram: invocation.program,
    targetArgs
  }
}

async function runElevatedDaemonLifecycle(
  args: ParsedArgs,
  subcommand: DaemonLifecycleSubcommand
): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  if (process.platform !== 'win32') {
    process.stderr.write(t.err('--administrator is supported only on Windows') + '\n')
    return 1
  }
  const plan = buildElevationLaunchPlan(args, subcommand)
  return new Promise(resolve => {
    let settled = false
    const child = spawn(plan.program, plan.args, {
      env: plan.env,
      stdio: 'inherit',
      windowsHide: true
    })
    child.once('error', error => {
      if (settled) return
      settled = true
      process.stderr.write(t.err(`failed to request Administrator access: ${error.message}`) + '\n')
      resolve(1)
    })
    child.once('exit', code => {
      if (settled) return
      settled = true
      resolve(code === 0 ? 0 : 1)
    })
  })
}

/** `daemon start` / `--detach` — spawn the foreground daemon as a detached
 * background process (no console window on Windows), logging to a file. */
async function startDetachedDaemon(args: ParsedArgs): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })

  const existing = await readDaemonStatus()
  if (existing && isDaemonProcessAlive(existing)) {
    process.stderr.write(
      t.warnLine(`daemon already running (pid ${existing.pid}) — stop it first: hermes-relay daemon stop`) + '\n'
    )
    return 1
  }
  // Remove stale state before spawning. Besides keeping status truthful while
  // the new child starts, this prevents an unlikely recycled PID from matching
  // a previous daemon's connected record.
  await clearDaemonStatus()

  const logPath = daemonLogPath()
  try {
    await fs.mkdir(path.dirname(logPath), { recursive: true })
  } catch {
    /* best-effort */
  }
  let logOffset = 0
  try {
    logOffset = (await fs.stat(logPath)).size
  } catch {
    /* new log file */
  }
  const logFd = openSync(logPath, 'a')

  // Compiled binary: the entry is embedded, so exe + args is enough. Running
  // via node/tsx during dev: include the script path so the child re-enters
  // the CLI (`node dist/cli.js daemon …`).
  const childArgs = buildDaemonChildArgs(args)
  const execIsNode = /node(\.exe)?$/i.test(path.basename(process.execPath))
  const spawnArgs = execIsNode ? [process.argv[1] ?? '', ...childArgs] : childArgs

  let child
  try {
    child = spawn(process.execPath, spawnArgs, {
      detached: true,
      stdio: ['ignore', logFd, logFd],
      windowsHide: true
    })
  } catch (error) {
    closeSync(logFd)
    process.stderr.write(t.err(`failed to start daemon: ${(error as Error).message}`) + '\n')
    process.stderr.write(t.muted(`  logs: ${logPath}`) + '\n')
    return 1
  }
  closeSync(logFd)

  let spawnError: Error | null = null
  child.once('error', error => {
    spawnError = error
  })
  if (typeof child.pid !== 'number') {
    process.stderr.write(t.err('failed to start daemon: the child process returned no pid') + '\n')
    process.stderr.write(t.muted(`  logs: ${logPath}`) + '\n')
    return 1
  }
  child.unref()

  const result = await waitForDetachedStartup(child.pid, DETACHED_START_TIMEOUT_MS, {
    readStatus: readDaemonStatus,
    isAlive: pid => !spawnError && isPidAlive(pid),
    readFailure: async () =>
      spawnError
        ? `process error: ${spawnError.message}`
        : readDetachedStartupFailure(logPath, logOffset),
    now: Date.now,
    sleep: ms => new Promise(resolve => setTimeout(resolve, ms))
  })

  if (result.outcome === 'failed') {
    process.stderr.write(t.err('daemon failed before it became ready') + '\n')
    if (result.detail) process.stderr.write(t.err(`  ${result.detail}`) + '\n')
    process.stderr.write(t.muted(`  logs: ${logPath}`) + '\n')
    return 1
  }
  if (result.outcome === 'timeout') {
    const state = result.status?.state ?? 'no matching status'
    process.stderr.write(
      t.err(`daemon did not become ready within ${DETACHED_START_TIMEOUT_MS / 1000} seconds (${state})`) + '\n'
    )
    process.stderr.write(t.muted(`  logs:   ${logPath}`) + '\n')
    process.stderr.write(t.muted('  status: hermes-relay daemon status') + '\n')
    return 1
  }

  process.stdout.write(t.okLine(`daemon started in the background (pid ${child.pid})`) + '\n')
  process.stdout.write(t.muted(`  logs:   ${logPath}`) + '\n')
  process.stdout.write(t.muted('  status: hermes-relay daemon status') + '\n')
  process.stdout.write(t.muted('  stop:   hermes-relay daemon stop') + '\n')
  return 0
}

/** `daemon stop` — terminate the running background daemon by its status pid. */
async function stopDaemon(args: ParsedArgs): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  const status = await readDaemonStatus()
  if (!status) {
    process.stdout.write(t.muted('No daemon status file — nothing to stop.') + '\n')
    return 1
  }
  if (!isDaemonProcessAlive(status)) {
    await clearDaemonStatus()
    process.stdout.write(t.muted(`Daemon (pid ${status.pid}) is already gone — cleared stale status.`) + '\n')
    return 0
  }
  try {
    // Default SIGTERM; on Windows this terminates the process. The daemon's own
    // cleanup may not run on a hard Windows terminate, so we clear status here.
    process.kill(status.pid)
  } catch (e) {
    process.stderr.write(t.err(`failed to stop daemon pid ${status.pid}: ${(e as Error).message}`) + '\n')
    return 1
  }
  await clearDaemonStatus()
  process.stdout.write(t.okLine(`stopped daemon (pid ${status.pid})`) + '\n')
  return 0
}

async function restartDaemon(args: ParsedArgs): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  const existing = await readDaemonStatus()
  if (existing && isDaemonProcessAlive(existing)) {
    try {
      process.kill(existing.pid)
    } catch (error) {
      process.stderr.write(
        t.err(`failed to stop daemon pid ${existing.pid}: ${(error as Error).message}`) + '\n'
      )
      return 1
    }

    const deadline = Date.now() + 5_000
    while (isDaemonProcessAlive(existing) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 50))
    }
    if (isDaemonProcessAlive(existing)) {
      process.stderr.write(t.err(`daemon pid ${existing.pid} did not stop within 5 seconds`) + '\n')
      return 1
    }
  }
  await clearDaemonStatus()
  return startDetachedDaemon(args)
}

async function restartDaemonAsUser(args: ParsedArgs): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  const identity = currentProcessIdentity()
  if (identity.privilege === 'administrator') {
    process.stderr.write(
      t.err('cannot launch a user daemon from an Administrator process; run this command from the normal desktop UI or an unelevated terminal') + '\n'
    )
    return 1
  }

  const existing = await readDaemonStatus()
  if (
    process.platform === 'win32' &&
    existing &&
    isDaemonProcessAlive(existing) &&
    existing.privilege === 'administrator'
  ) {
    // An unelevated caller cannot terminate an elevated daemon. Elevate only
    // the stop operation, wait for it to finish, then start the replacement
    // from this original unelevated process.
    const stopped = await runElevatedDaemonLifecycle(args, 'stop')
    if (stopped !== 0) return stopped
    return startDetachedDaemon(args)
  }

  return restartDaemon(args)
}

export async function daemonCommand(args: ParsedArgs): Promise<number> {
  if (args.flags.help) {
    printUsage(DAEMON_USAGE, makeTheme({ noColor: !!args.flags['no-color'] }))
    return 0
  }
  const sub = args.positional[0]
  const administrator = args.flags.administrator === true
  const user = args.flags.user === true
  const elevationChild = args.flags['elevation-child'] === true
  if ((administrator || user || elevationChild) && process.platform !== 'win32') {
    process.stderr.write('daemon: --administrator and --user are supported only on Windows\n')
    return 1
  }
  if (administrator && user) {
    process.stderr.write('daemon: --administrator and --user are mutually exclusive\n')
    return 1
  }
  if ((administrator || user || elevationChild) && (sub === 'status' || args.flags.status)) {
    process.stderr.write('daemon: privilege flags apply only to start, stop, or restart\n')
    return 1
  }
  if (user && sub !== 'start' && sub !== 'restart') {
    process.stderr.write('daemon: --user applies only to start or restart\n')
    return 1
  }
  if (elevationChild && currentProcessIdentity().privilege !== 'administrator') {
    process.stderr.write('daemon: internal elevation helper did not receive an Administrator token\n')
    return 1
  }
  if (administrator && !elevationChild && currentProcessIdentity().privilege !== 'administrator') {
    if (sub !== 'start' && sub !== 'stop' && sub !== 'restart') {
      process.stderr.write('daemon: --administrator requires start, stop, or restart\n')
      return 1
    }
    return runElevatedDaemonLifecycle(args, sub)
  }
  if (args.flags.status || sub === 'status') {
    return printDaemonStatus(args)
  }
  if (sub === 'stop') {
    return stopDaemon(args)
  }
  if (sub === 'restart') {
    if (user) return restartDaemonAsUser(args)
    return restartDaemon(args)
  }
  if (sub === 'start' || args.flags.detach) {
    if (user && currentProcessIdentity().privilege === 'administrator') {
      process.stderr.write(
        'daemon: cannot launch a user daemon from an Administrator process; run this command from an unelevated terminal\n'
      )
      return 1
    }
    return startDetachedDaemon(args)
  }
  // Bare `daemon` (or `daemon run`) → foreground, the existing behavior below.

  // Default log shape: JSON-line for service-manager deploys, human if a
  // human is watching (TTY stderr) or asked for it explicitly.
  const humanFlag = !!args.flags['log-human']
  const human = humanFlag || (!args.flags['log-json'] && !!process.stderr.isTTY)
  const log = makeLogger(human)

  // URL resolution mirrors chat/shell — explicit --remote / HERMES_RELAY_URL
  // win, otherwise fall back to a stored session. resolveFirstRunUrl with
  // nonInteractive:true auto-picks when exactly one session exists, throws a
  // clear error when zero or many. Lets `hermes-relay daemon` with no flags
  // Just Work for the common case (one paired server) — same UX as `hermes-relay`
  // bare invocation and `chat` first-run.
  let url = resolveRemoteOrNull(args)
  if (!url) {
    try {
      url = await resolveFirstRunUrl({ nonInteractive: true })
    } catch (e) {
      log.error({
        event: 'config_missing',
        message:
          'daemon: ' +
          (e instanceof Error ? e.message : String(e)) +
          ' Pass --remote <url>, set HERMES_RELAY_URL, or pair first with `hermes-relay pair`.'
      })
      return 1
    }
  }

  // Keep the selected host as the policy/storage identity, while resolving
  // its current network route independently. A v3 pair can therefore prefer
  // WSS/Tailscale and fall back to LAN without creating duplicate hosts.
  const configuredUrl = url
  const configuredSession = await getSession(configuredUrl)
  let useSessionHeader = false
  if (!resolveRemoteOrNull(args) && configuredSession?.routeCandidates?.length) {
    const candidates = configuredSession.preferSecureRoutes
      ? secureFirstCandidates(configuredSession.routeCandidates)
      : configuredSession.routeCandidates
    try {
      const route = await probeCandidatesByPriority(candidates, { sessionToken: configuredSession.token })
      url = route.relay.url
      useSessionHeader = route.role.toLowerCase() === 'plugin_proxy'
      log.info({ event: 'route_selected', configured_url: configuredUrl, url, role: route.role })
    } catch (e) {
      log.warn({
        event: 'route_probe_failed',
        configured_url: configuredUrl,
        message: e instanceof Error ? e.message : String(e),
        fallback_url: configuredUrl
      })
    }
  }

  // Resolve credentials: daemon takes ONLY --token or stored session. No
  // pairing code path (the daemon can't do the one-time code → token
  // trade safely — the token should already be stored). No interactive
  // fallback (headless).
  const argToken = typeof args.flags.token === 'string' ? args.flags.token : undefined
  const envToken = process.env.HERMES_RELAY_TOKEN
  const stored = configuredSession ?? await getSession(url)
  const token = argToken ?? envToken ?? stored?.token

  if (!token) {
    log.error({
      event: 'no_credentials',
      url,
      message: 'no session token. Run `hermes-relay pair --remote <url>` once, then start the daemon.'
    })
    return 1
  }

  // Access gates control which tools are attached, not whether the daemon may
  // connect. This lets the tray manage a healthy locked daemon without making
  // connectivity itself a privileged operation.
  const consented = stored?.toolsConsented === true
  const allowToolsFlag = !!args.flags['allow-tools']
  const storedAccessMode = await getHostAccessMode(configuredUrl)
  const accessMode = effectiveHostAccessMode(storedAccessMode, stored?.toolsConsented === true)
  const toolsEnabled = consented || allowToolsFlag || accessMode !== 'ask'

  log.info({
    event: 'starting',
    url,
    pid: process.pid,
    platform: process.platform,
    node: process.version
  })

  // Observable status file — `hermes-relay daemon --status` reads this.
  const nowSec = () => Math.floor(Date.now() / 1000)
  const identity = currentProcessIdentity()
  const computerUseEnabled = toolsEnabled && (
    accessMode === 'full_access' || shouldAdvertiseComputerUse(args.flags)
  )
  const status: DaemonStatus = {
    pid: process.pid,
    process_name: path.basename(process.execPath),
    url,
    configured_url: configuredUrl,
    state: 'starting',
    started_at: nowSec(),
    updated_at: nowSec(),
    last_event: 'starting',
    username: identity.username,
    privilege: identity.privilege,
    computer_use_enabled: computerUseEnabled,
    access_mode: accessMode,
    tools_enabled: toolsEnabled,
    computer_grant: { active: false, mode: 'none', expires_at: null }
  }
  const updateStatus = (partial: Partial<DaemonStatus> & { state?: DaemonState }) => {
    Object.assign(status, partial, { updated_at: nowSec() })
    void writeDaemonStatus(status)
  }
  updateStatus({})

  const relay = new RelayTransport({
    url,
    sessionToken: token,
    sessionHeader: useSessionHeader,
    ...desktopRelayIdentity()
  })

  // Lifecycle wiring — every event the transport emits that a daemon
  // should log. These install before `start()` so we don't race the
  // connect-completes-before-listener-attached window.
  relay.on('reconnecting', (info: unknown) => {
    const { attempt, delayMs } =
      info && typeof info === 'object'
        ? (info as { attempt?: number; delayMs?: number })
        : {}
    log.warn({ event: 'reconnecting', attempt: attempt ?? null, delay_ms: delayMs ?? null })
    updateStatus({ state: 'reconnecting', last_event: 'reconnecting' })
  })
  relay.on('reconnected', () => {
    log.info({ event: 'reconnected' })
    updateStatus({ state: 'connected', last_event: 'reconnected' })
  })
  relay.on('exit', (code: unknown) => {
    // Transport gave up (auth.fail, reconnect gate returned false, or
    // reconnect attempts exhausted). Daemon exits non-zero so the
    // service manager decides whether to restart.
    log.error({ event: 'transport_exited', code: typeof code === 'number' ? code : null })
    // Defer exit so the log line flushes before the process dies.
    setImmediate(() => process.exit(1))
  })

  relay.start()

  const outcome = await relay.whenAuthResolved()
  if (!outcome.ok) {
    log.error({ event: 'auth_failed', reason: outcome.reason })
    try {
      relay.kill()
    } catch {
      /* ignore */
    }
    return 1
  }

  log.info({
    event: 'authed',
    server_version: relay.serverVersion ?? null,
    transport: relay.authMeta?.transportHint ?? null
  })
  updateStatus({ state: 'connected', server_version: relay.serverVersion ?? null, last_event: 'authed' })

  // Signal downstream handlers that we're running headless. The router
  // also checks this env var in its detectInteractive() fallback, so any
  // future code path that constructs a router from the daemon without
  // passing `interactive: false` explicitly still gets the right default.
  process.env.HERMES_RELAY_DAEMON = '1'
  process.env.HERMES_RELAY_GRANT_BRIDGE_DIR ??= grantBridgeDir()

  // Wire the desktop tool router. consentGranted is true by this point —
  // we gated on stored consent (or --allow-tools override) above.
  // A foreground daemon with a real TTY may show approval prompts; a service
  // or redirected daemon still fails host input closed because no visible
  // local grant approval prompt can run.
  const interactive = !!process.stdin.isTTY && !!process.stderr.isTTY
  const capabilities = effectiveHostCapabilityPolicies(
    storedAccessMode,
    stored?.toolsConsented === true,
    await getHostCapabilityPolicies(configuredUrl)
  )
  configureComputerUseRuntime({
    url: configuredUrl,
    computerUseConsented: computerUseEnabled,
    consentSource: consented ? 'stored' : toolsEnabled ? 'override' : 'none',
    accessMode,
    capabilities
  })
  configureCapabilityPolicies(capabilities)
  const usb = capabilities.usb !== 'disabled'
  const adb = usb && adbBackendAvailable()
  const advertisedTools = toolsEnabled
    ? advertisedDesktopTools({ computerUse: computerUseEnabled, capabilities, usb, adb })
    : []
  const toDaemonGrantStatus = (grant: ComputerGrant | null): DaemonComputerGrantStatus => ({
    active: grant !== null,
    mode: grant?.mode ?? 'none',
    expires_at: grant?.expires_at ?? null,
    reason: grant?.reason
  })
  const restoreGrantListener = setComputerGrantChangeListener(grant => {
    updateStatus({ computer_grant: toDaemonGrantStatus(grant), last_event: 'grant_changed' })
  })

  let cancellationCheckRunning = false
  const grantControlInterval = setInterval(async () => {
    if (cancellationCheckRunning) return
    cancellationCheckRunning = true
    try {
      const request = await consumeComputerGrantCancellation()
      if (request) {
        const result = cancelComputerGrant(request.reason)
        log.info({ event: 'computer_grant_cancelled_locally', reason: request.reason, result })
      } else {
        getActiveComputerGrant()
      }
    } catch (error) {
      log.warn({ event: 'computer_grant_control_failed', message: rpcErrorMessage(error) })
    } finally {
      cancellationCheckRunning = false
    }
  }, 500)
  grantControlInterval.unref?.()

  const router = toolsEnabled
    ? new DesktopToolRouter({
        consentGranted: true,
        interactive,
        hostUrl: url,
        handlers: desktopHandlers({ computerUse: computerUseEnabled, capabilities, usb, adb }),
        advertisedTools: [...advertisedTools]
      })
    : null
  router?.attach(relay)

  log.info({
    event: toolsEnabled ? 'ready' : 'ready_locked',
    advertised_tools: [...advertisedTools],
    experimental_computer_use: computerUseEnabled,
    interactive
  })
  updateStatus({
    advertised_tools: [...advertisedTools].length,
    last_event: toolsEnabled ? 'ready' : 'ready_locked'
  })

  // Keep the status file's updated_at fresh so `--status` can distinguish a
  // live daemon from a crashed one whose file lingers (belt-and-suspenders
  // with the pid liveness check).
  const statusHeartbeat = setInterval(() => updateStatus({}), STATUS_HEARTBEAT_MS)
  statusHeartbeat.unref?.()

  // ── Voice server ──────────────────────────────────────────────────
  // Hosts the same loopback HTTP voice surface that `voice mode` starts
  // ad-hoc, but kept alive for the whole daemon lifetime. The tray reads
  // ~/.hermes/desktop-voice.json to find the URL. Failures here are
  // non-fatal — the tool router is the daemon's primary job, voice is
  // a bonus.
  const noVoice = !!args.flags['no-voice']
  let voiceServer: VoiceServer | null = null
  let voiceSessionId: string | null = null

  if (!noVoice) {
    const gateway = new GatewayClient(relay)
    try {
      // Attach the gateway.ready listener BEFORE drain — drain replays
      // events buffered since the transport started, and gateway.ready
      // already arrived (the router attached above is silent on it).
      const ready = waitForGatewayReady(gateway, 30_000)
      gateway.start()
      gateway.drain()
      await ready
      voiceSessionId = await createVoiceSession(gateway)
      voiceServer = await startVoiceServer({
        token,
        relayUrl: url,
        gateway,
        sessionId: voiceSessionId
      })
      await writeVoiceDiscovery(voiceServer.url, voiceSessionId, log)
      log.info({
        event: 'voice_ready',
        url: voiceServer.url,
        session_id: voiceSessionId.slice(0, 8)
      })
      updateStatus({ voice_url: voiceServer.url, last_event: 'voice_ready' })
    } catch (e) {
      log.warn({
        event: 'voice_unavailable',
        message: rpcErrorMessage(e)
      })
      voiceServer = null
    }
  }

  // Graceful shutdown: detach router (stops heartbeats), kill transport
  // (closes the WSS), then let setupGracefulExit's failsafe exit us.
  const cleanup = async () => {
    log.info({ event: 'shutdown' })
    clearInterval(statusHeartbeat)
    clearInterval(grantControlInterval)
    restoreGrantListener()
    try {
      await clearDaemonStatus()
    } catch {
      /* ignore */
    }
    try {
      if (voiceServer) await voiceServer.close()
    } catch {
      /* ignore */
    }
    try {
      await removeVoiceDiscovery()
    } catch {
      /* ignore */
    }
    try {
      router?.detach()
    } catch {
      /* ignore */
    }
    try {
      relay.kill()
    } catch {
      /* ignore */
    }
  }
  setupGracefulExit({ cleanups: [cleanup] })

  // Park forever — all work happens through event handlers and the router.
  // Resolve only when process.exit fires from 'exit' handler or a signal.
  return new Promise<number>(() => {
    /* never resolves; lifecycle is driven by signals + transport events */
  })
}

// Module-default: used only when the file is imported directly (tests).
// The CLI dispatches via `cli.ts` → `daemonCommand`. The try/catch at the
// shim handles unexpected throws.
export default daemonCommand

// Small utility function re-exported for tests that need to stub the logger.
export type { LogFields }
export {
  daemonStatusIsReady as __daemonStatusIsReadyForTests,
  buildDaemonChildArgs as __buildDaemonChildArgsForTests,
  buildElevationLaunchPlan as __buildElevationLaunchPlanForTests,
  quoteWindowsArgument as __quoteWindowsArgumentForTests,
  makeLogger as __makeLoggerForTests,
  readDetachedStartupFailure as __readDetachedStartupFailureForTests,
  rpcErrorMessage as __rpcErrorMessageForTests,
  waitForDetachedStartup as __waitForDetachedStartupForTests
}

// ── Voice-server helpers ───────────────────────────────────────────────

function voiceDiscoveryPath(): string {
  return path.join(os.homedir(), '.hermes', VOICE_DISCOVERY_FILE)
}

async function writeVoiceDiscovery(
  url: string,
  sessionId: string,
  log: { info: (f: LogFields) => void; warn: (f: LogFields) => void; error: (f: LogFields) => void }
): Promise<void> {
  const payload = {
    url,
    pid: process.pid,
    session_id: sessionId,
    started_at: Math.floor(Date.now() / 1000)
  }
  const filePath = voiceDiscoveryPath()
  try {
    await fs.mkdir(path.dirname(filePath), { recursive: true })
    await fs.writeFile(filePath, JSON.stringify(payload, null, 2) + '\n', { mode: 0o600 })
  } catch (e) {
    log.warn({ event: 'voice_discovery_write_failed', message: rpcErrorMessage(e), path: filePath })
  }
}

async function removeVoiceDiscovery(): Promise<void> {
  const filePath = voiceDiscoveryPath()
  try {
    await fs.unlink(filePath)
  } catch (e) {
    // ENOENT is fine; nothing else should bubble up — cleanup is best-effort.
    if ((e as NodeJS.ErrnoException)?.code !== 'ENOENT') throw e
  }
}

function waitForGatewayReady(gateway: GatewayClient, timeoutMs: number): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      gateway.off('event', handler)
      reject(new Error(`gateway.ready timeout after ${timeoutMs}ms`))
    }, timeoutMs)
    timer.unref?.()
    const handler = (ev: GatewayEvent) => {
      if (ev.type === 'gateway.ready') {
        clearTimeout(timer)
        gateway.off('event', handler)
        resolve()
      }
    }
    gateway.on('event', handler)
  })
}

async function createVoiceSession(gateway: GatewayClient): Promise<string> {
  const raw = await gateway.request<SessionCreateResponse>('session.create', { cols: 80 })
  const r = asRpcResult<SessionCreateResponse>(raw)
  if (!r?.session_id) throw new Error('voice session.create returned no session_id')
  return r.session_id
}

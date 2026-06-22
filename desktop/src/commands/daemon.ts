// daemon — headless WSS + DesktopToolRouter, runs forever.
//
// The missing piece between "it works" and "feels local": the interactive
// `shell` / `chat` commands only serve desktop tools while a terminal is
// open. The daemon closes that gap — install it once (see
// scripts/install-service-*) and the agent can reach the user's machine
// any time of day, not just when they have a shell attached.
//
// Design contract:
//   - Fails closed if no stored session or desktop-tool consent isn't already true.
//     A headless binary must never be the thing that grants tool access;
//     the user must have previously consented via interactive `shell`/`chat`
//     or `pair --grant-tools`.
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
import { openSync, promises as fs } from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

import type { ParsedArgs } from '../cli.js'
import { GatewayClient } from '../gatewayClient.js'
import type { GatewayEvent, SessionCreateResponse } from '../gatewayTypes.js'
import {
  clearDaemonStatus,
  isPidAlive,
  readDaemonStatus,
  writeDaemonStatus,
  type DaemonState,
  type DaemonStatus
} from '../lib/daemonStatus.js'
import { rpcErrorMessage, asRpcResult } from '../lib/rpc.js'
import { theme as makeTheme } from '../lib/theme.js'
import { printUsage, type UsageSpec } from '../lib/usage.js'
import { resolveFirstRunUrl } from '../relayUrlPrompt.js'
import { getSession } from '../remoteSessions.js'
import {
  advertisedDesktopTools,
  desktopHandlers,
  shouldAdvertiseComputerUse
} from '../tools/handlerSet.js'
import { configureComputerUseRuntime } from '../tools/computerGrants.js'
import { DesktopToolRouter } from '../tools/router.js'
import { RelayTransport } from '../transport/RelayTransport.js'
import { setupGracefulExit } from '../lib/gracefulExit.js'
import { startVoiceServer, type VoiceServer } from '../voiceServer.js'

const VOICE_DISCOVERY_FILE = 'desktop-voice.json'

/** Refresh the status-file `updated_at` on this cadence so `--status` can tell
 * a live daemon from a crashed one whose file lingers. */
const STATUS_HEARTBEAT_MS = 30_000

const DAEMON_USAGE: UsageSpec = {
  name: 'daemon',
  summary: 'run headless — expose desktop tools to the agent even when no shell is open',
  usage: ['daemon [run]', 'daemon start', 'daemon stop', 'daemon status'],
  subcommands: [
    { verb: 'run', desc: 'Run in the foreground (current console; default)' },
    { verb: 'start', desc: 'Start in the background — no console window; survives terminal close' },
    { verb: 'stop', desc: 'Stop the background daemon' },
    { verb: 'status', desc: 'Print state + uptime of the running daemon (alias: --status)' }
  ],
  flags: [
    { flag: '--detach', desc: 'Alias for `daemon start` — run in the background' },
    { flag: '--remote <url>', desc: 'Relay to connect to (default: stored/active session)' },
    { flag: '--token <token>', desc: 'Use an explicit session token (CI/provisioning)' },
    { flag: '--allow-tools', desc: 'Skip the stored-consent gate (only with --token; implies trust)' },
    { flag: '--no-voice', desc: 'Do not start the loopback voice server' },
    { flag: '--log-human', desc: 'Human-readable logs (auto on a TTY)' },
    { flag: '--log-json', desc: 'Force JSON-line logs even on a TTY' },
    { flag: '--experimental-computer-use', desc: 'Also advertise computer-use tools (see top-level help)' }
  ],
  examples: ['hermes-relay daemon start', 'hermes-relay daemon status', 'hermes-relay daemon stop']
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
  const alive = isPidAlive(status.pid)
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
  return alive ? 0 : 1
}

function daemonLogPath(): string {
  return path.join(os.homedir(), '.hermes', 'daemon.log')
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

/** `daemon start` / `--detach` — spawn the foreground daemon as a detached
 * background process (no console window on Windows), logging to a file. */
async function startDetachedDaemon(args: ParsedArgs): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })

  const existing = await readDaemonStatus()
  if (existing && isPidAlive(existing.pid)) {
    process.stderr.write(
      t.warnLine(`daemon already running (pid ${existing.pid}) — stop it first: hermes-relay daemon stop`) + '\n'
    )
    return 1
  }

  const logPath = daemonLogPath()
  try {
    await fs.mkdir(path.dirname(logPath), { recursive: true })
  } catch {
    /* best-effort */
  }
  const logFd = openSync(logPath, 'a')

  // Compiled binary: the entry is embedded, so exe + args is enough. Running
  // via node/tsx during dev: include the script path so the child re-enters
  // the CLI (`node dist/cli.js daemon …`).
  const childArgs = buildDaemonChildArgs(args)
  const execIsNode = /node(\.exe)?$/i.test(path.basename(process.execPath))
  const spawnArgs = execIsNode ? [process.argv[1] ?? '', ...childArgs] : childArgs

  const child = spawn(process.execPath, spawnArgs, {
    detached: true,
    stdio: ['ignore', logFd, logFd],
    windowsHide: true
  })
  child.unref()

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
  if (!isPidAlive(status.pid)) {
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

export async function daemonCommand(args: ParsedArgs): Promise<number> {
  if (args.flags.help) {
    printUsage(DAEMON_USAGE, makeTheme({ noColor: !!args.flags['no-color'] }))
    return 0
  }
  const sub = args.positional[0]
  if (args.flags.status || sub === 'status') {
    return printDaemonStatus(args)
  }
  if (sub === 'stop') {
    return stopDaemon(args)
  }
  if (sub === 'start' || args.flags.detach) {
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

  // Resolve credentials: daemon takes ONLY --token or stored session. No
  // pairing code path (the daemon can't do the one-time code → token
  // trade safely — the token should already be stored). No interactive
  // fallback (headless).
  const argToken = typeof args.flags.token === 'string' ? args.flags.token : undefined
  const envToken = process.env.HERMES_RELAY_TOKEN
  const stored = await getSession(url)
  const token = argToken ?? envToken ?? stored?.token

  if (!token) {
    log.error({
      event: 'no_credentials',
      url,
      message: 'no session token. Run `hermes-relay pair --remote <url>` once, then start the daemon.'
    })
    return 1
  }

  // Consent gate: the daemon must not grant tool access on its own. The
  // interactive `shell` command is the canonical place consent is captured,
  // and it writes `toolsConsented: true` onto the stored session. If the
  // token came from --token but we have no stored record, assume the user
  // knows what they're doing ONLY if they also pass --allow-tools; otherwise
  // bail.
  const tokenFromStored = !argToken && !envToken
  const consented = stored?.toolsConsented === true
  const allowToolsFlag = !!args.flags['allow-tools']

  if (tokenFromStored && !consented) {
    log.error({
      event: 'consent_missing',
      url,
      message:
        'tools not consented for this URL. Re-pair with `hermes-relay pair --remote <url> --grant-tools` ' +
        '(prompts on TTY) or `--auto-grant-tools` (no prompt). `hermes-relay shell` also works. ' +
        '`--allow-tools` overrides this gate when invoking with --token directly.'
    })
    return 1
  }
  if (!tokenFromStored && !consented && !allowToolsFlag) {
    log.error({
      event: 'consent_missing',
      url,
      message:
        'no stored consent for this URL. Pass --allow-tools to override, or pair first with ' +
        '`hermes-relay pair --remote <url> --grant-tools`.'
    })
    return 1
  }

  log.info({
    event: 'starting',
    url,
    pid: process.pid,
    platform: process.platform,
    node: process.version
  })

  // Observable status file — `hermes-relay daemon --status` reads this.
  const nowSec = () => Math.floor(Date.now() / 1000)
  const status: DaemonStatus = {
    pid: process.pid,
    url,
    state: 'starting',
    started_at: nowSec(),
    updated_at: nowSec(),
    last_event: 'starting'
  }
  const updateStatus = (partial: Partial<DaemonStatus> & { state?: DaemonState }) => {
    Object.assign(status, partial, { updated_at: nowSec() })
    void writeDaemonStatus(status)
  }
  updateStatus({})

  const relay = new RelayTransport({
    url,
    sessionToken: token,
    deviceName: `hermes-relay-cli daemon (${process.platform})`
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

  // Wire the desktop tool router. consentGranted is true by this point —
  // we gated on stored consent (or --allow-tools override) above.
  // A foreground daemon with a real TTY may show approval prompts; a service
  // or redirected daemon still fails host input closed because no visible
  // local grant approval prompt can run.
  const interactive = !!process.stdin.isTTY && !!process.stderr.isTTY
  const computerUseEnabled = shouldAdvertiseComputerUse(args.flags)
  configureComputerUseRuntime({
    url,
    computerUseConsented: computerUseEnabled,
    consentSource: consented ? 'stored' : 'override'
  })
  const advertisedTools = advertisedDesktopTools({ computerUse: computerUseEnabled })
  const router = new DesktopToolRouter({
    consentGranted: true,
    interactive,
    handlers: desktopHandlers({ computerUse: computerUseEnabled }),
    advertisedTools: [...advertisedTools]
  })
  router.attach(relay)

  log.info({
    event: 'ready',
    advertised_tools: [...advertisedTools],
    experimental_computer_use: computerUseEnabled,
    interactive
  })
  updateStatus({ advertised_tools: [...advertisedTools].length, last_event: 'ready' })

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
      router.detach()
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
export { makeLogger as __makeLoggerForTests, rpcErrorMessage as __rpcErrorMessageForTests }

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

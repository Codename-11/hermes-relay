// voice — probe the relay's native Hermes voice passthrough.
//
// Hermes voice mode is configured server-side in `~/.hermes/config.yaml`
// (stt.provider / tts.provider). The relay exposes that configuration
// through three HTTP endpoints on the same port as WSS:
//   GET  /voice/config              — basic STT+TTS provider snapshot
//   GET  /voice/realtime/config     — realtime/WS speech-to-speech surface
//   POST /voice/transcribe          — multipart audio → text   (future)
//   POST /voice/synthesize          — text → audio/mpeg bytes  (future)
//
// `voice status` calls the two GET routes and prints what the user has
// configured. Auth is `Authorization: Bearer <session_token>` pulled from
// the local session store, mirroring `devices.ts`. Voice grants
// (`voice:config`, `voice:stt`, `voice:tts`, `voice:realtime`) are minted
// with no expiry at pair time (plugin/relay/auth.py:129), so any paired
// session can probe these without an extra consent step.
//
// Sub-verb shape mirrors `devices`:
//   hermes-relay voice                (alias for `voice status`)
//   hermes-relay voice status         basic + realtime snapshot
//   hermes-relay voice status --json  raw JSON for scripting
//
// Future sub-verbs (`voice say <text>`, `voice transcribe <file>`) slot in
// here without restructuring — the resolver and HTTP helper are reusable.

import type { ParsedArgs } from '../cli.js'
import { resolveCredentials } from '../credentials.js'
import { GatewayClient } from '../gatewayClient.js'
import type {
  GatewayEvent,
  SessionCreateResponse,
  SessionResumeResponse
} from '../gatewayTypes.js'
import {
  sessionProjectName,
  sessionQueuedPromptPreview,
  sessionResumeActivity,
  type SessionResumeActivity
} from '../gatewayTypes.js'
import { getActiveDesktopRelayUrl } from '../desktopConfig.js'
import { formatError } from '../lib/hints.js'
import { setupGracefulExit } from '../lib/gracefulExit.js'
import { asRpcResult, rpcErrorMessage } from '../lib/rpc.js'
import { theme as makeTheme, type Theme } from '../lib/theme.js'
import { printUsage, unknownSubcommand, type UsageSpec } from '../lib/usage.js'
import { resolveFirstRunUrl } from '../relayUrlPrompt.js'
import { SessionResumeDrain } from '../resumeDrain.js'
import { deleteSession, getSession, listSessions, saveSession } from '../remoteSessions.js'
import { RelayTransport } from '../transport/RelayTransport.js'
import { openInBrowser, startVoiceServer } from '../voiceServer.js'

const READY_TIMEOUT_MS = 60_000

const VOICE_USAGE: UsageSpec = {
  name: 'voice',
  summary: 'inspect native Hermes voice config (STT/TTS/realtime) and run push-to-talk',
  usage: ['voice [status]', 'voice mode [--port <n>] [--no-open]'],
  subcommands: [
    { verb: 'status', desc: 'Show STT/TTS/realtime providers + enhanced-voice capabilities (default)' },
    { verb: 'mode', desc: 'Push-to-talk in a browser tab, proxied through this CLI' }
  ],
  flags: [
    { flag: '--remote <url>', desc: 'Relay to query (default: stored/active)' },
    { flag: '--json', desc: 'status: raw JSON for scripting' },
    { flag: '--port <n>', desc: 'mode: local voice-server port (default: ephemeral)' },
    { flag: '--no-open', desc: 'mode: do not auto-open the browser' }
  ],
  examples: ['hermes-relay voice', 'hermes-relay voice mode']
}

/** Per-provider enhanced-voice capability hint, surfaced by `/voice/config`
 * (plugin v1.2.0). Gemini carries tone-tags + persona; xAI carries speech-tags
 * + language. The CLI previously dropped this block entirely. */
interface VoiceEnhanced {
  audio_tags_enabled?: boolean
  audio_tags_label?: string | null
  supports_persona?: boolean
  persona_prompt_file?: string | null
  overrides?: string[]
}

interface VoiceProvider {
  provider?: string | null
  model?: string | null
  voice?: string | null
  voice_id?: string | null
  enabled?: boolean
  available?: boolean
  enhanced?: VoiceEnhanced | null
}

interface VoiceConfigResponse {
  success?: boolean
  tts?: VoiceProvider | null
  stt?: VoiceProvider | null
  requirements?: Record<string, unknown> | null
  error?: string
}

interface RealtimeProvider {
  id: string
  name?: string | null
  status?: string | null
  description?: string | null
  supports_tts?: boolean
  supports_stt?: boolean
  supports_speech_to_speech?: boolean
  supports_interruption?: boolean
}

interface RealtimeVoiceConfigResponse {
  success?: boolean
  enabled?: boolean
  protocol?: string | null
  default_provider?: string | null
  default_model?: string | null
  default_voice?: string | null
  sample_rate?: number
  providers?: RealtimeProvider[]
  error?: string
}

/** `ws(s)://host:port` → `http(s)://host:port` — voice routes are HTTP. */
function wsToHttp(url: string): string {
  const trimmed = url.trim()
  if (trimmed.startsWith('wss://')) {
    return 'https://' + trimmed.slice('wss://'.length)
  }
  if (trimmed.startsWith('ws://')) {
    return 'http://' + trimmed.slice('ws://'.length)
  }
  return trimmed
}

async function resolveRemoteAndToken(
  args: ParsedArgs
): Promise<{ url: string; token: string }> {
  const argUrl = typeof args.flags.remote === 'string' ? args.flags.remote.trim() : null
  const envUrl = process.env.HERMES_RELAY_URL?.trim()
  const argToken = typeof args.flags.token === 'string' ? args.flags.token.trim() : null
  const envToken = process.env.HERMES_RELAY_TOKEN?.trim()

  if (argToken || envToken) {
    const url = argUrl ?? envUrl
    if (!url) {
      throw new Error('--token supplied without --remote. Pass both, or set HERMES_RELAY_URL.')
    }
    return { url, token: (argToken ?? envToken)! }
  }

  const stored = await listSessions()
  const urls = Object.keys(stored)
  const activeDesktopUrl = await getActiveDesktopRelayUrl()

  let url: string
  if (argUrl || envUrl) {
    url = argUrl ?? envUrl!
  } else if (activeDesktopUrl) {
    url = activeDesktopUrl
  } else if (urls.length === 1) {
    url = urls[0]!
  } else if (urls.length === 0) {
    throw new Error('No paired relays. Run `hermes-relay pair --remote ws://host:port` first.')
  } else {
    throw new Error(
      `Multiple paired relays; pass --remote to pick one (${urls.join(', ')}).`
    )
  }

  const rec = await getSession(url)
  if (!rec) {
    throw new Error(`No stored session for ${url}. Run \`hermes-relay pair --remote ${url}\` first.`)
  }
  return { url, token: rec.token }
}

async function getJson<T>(
  httpUrl: string,
  token: string
): Promise<{ status: number; body: T | { error?: string } | string | undefined }> {
  const res = await fetch(httpUrl, {
    method: 'GET',
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/json'
    }
  })
  const text = await res.text()
  let body: unknown
  if (text.length > 0) {
    try {
      body = JSON.parse(text)
    } catch {
      body = text
    }
  }
  return { status: res.status, body: body as T | { error?: string } | string | undefined }
}

function formatProvider(p: VoiceProvider | null | undefined, label: string, t: Theme): string {
  if (!p || !p.provider) {
    return `  ${t.muted(`${label}: (not configured)`)}`
  }
  const model = p.model ? ` · ${p.model}` : ''
  const voice = p.voice ?? p.voice_id
  const voiceTag = voice ? ` · voice=${voice}` : ''
  return `  ${t.statusDot(p.enabled !== false)} ${t.bold(label)}: ${p.provider}${model}${voiceTag}`
}

/** Render the enhanced-voice capability sub-line under a provider, if present.
 * Surfaces what the relay's per-request `/voice/synthesize` overrides can do
 * (Gemini tone-tags + persona, xAI speech-tags + language). */
function formatEnhanced(p: VoiceProvider | null | undefined, t: Theme): string | null {
  const e = p?.enhanced
  if (!e) {
    return null
  }
  const bits: string[] = []
  if (e.audio_tags_label) {
    bits.push(`${e.audio_tags_label} ${e.audio_tags_enabled ? t.ok('(on)') : t.muted('(off)')}`)
  }
  if (e.supports_persona) {
    bits.push('persona supported')
  }
  if (e.overrides?.length) {
    bits.push(`overrides: ${e.overrides.join(', ')}`)
  }
  if (bits.length === 0) {
    return null
  }
  return `      ${t.muted('enhanced: ' + bits.join(' · '))}`
}

function formatRealtime(rt: RealtimeVoiceConfigResponse | null, t: Theme): string[] {
  if (!rt) {
    return [`  ${t.muted('Realtime: (unavailable)')}`]
  }
  if (rt.success === false) {
    return [`  ${t.warn(`Realtime: error — ${rt.error ?? 'unknown'}`)}`]
  }
  const lines: string[] = []
  const provider = rt.default_provider ?? '(none)'
  const model = rt.default_model ? ` · ${rt.default_model}` : ''
  const voice = rt.default_voice ? ` · voice=${rt.default_voice}` : ''
  const rate = rt.sample_rate ? ` @ ${rt.sample_rate}Hz` : ''
  lines.push(`  ${t.statusDot(!!rt.enabled)} ${t.bold('Realtime')}: ${provider}${model}${voice}${rate}`)
  const providers = (rt.providers ?? []).filter((p) => p.status && p.status !== 'unavailable')
  if (providers.length > 0) {
    const labels = providers.map((p) => p.name ?? p.id)
    lines.push(`      ${t.muted('available: ' + labels.join(', '))}`)
  }
  return lines
}

async function voiceStatus(args: ParsedArgs): Promise<number> {
  const { url, token } = await resolveRemoteAndToken(args)
  const httpBase = wsToHttp(url)

  // Run both probes in parallel — they're independent and fast.
  const [basic, realtime] = await Promise.all([
    getJson<VoiceConfigResponse>(`${httpBase}/voice/config`, token),
    getJson<RealtimeVoiceConfigResponse>(`${httpBase}/voice/realtime/config`, token).catch(
      () => ({ status: 0, body: undefined as undefined })
    )
  ])

  if (args.flags.json) {
    const payload = {
      url,
      voice: basic.status === 200 ? basic.body : { status: basic.status, error: basic.body },
      realtime:
        realtime.status === 200
          ? realtime.body
          : realtime.status === 0
            ? null
            : { status: realtime.status, error: realtime.body }
    }
    process.stdout.write(JSON.stringify(payload, null, 2) + '\n')
    // Non-zero on the primary probe so scripts can `voice status --json | jq`
    // AND still rely on exit code as the canonical success signal.
    return basic.status === 200 ? 0 : 1
  }

  if (basic.status !== 200) {
    const detail =
      typeof basic.body === 'string'
        ? basic.body
        : JSON.stringify(basic.body)
    if (basic.status === 401 || basic.status === 403) {
      process.stderr.write(
        `error: voice auth rejected (${basic.status}). Re-pair: hermes-relay pair --remote ${url}\n` +
          `       (${detail})\n`
      )
    } else if (basic.status === 404) {
      process.stderr.write(
        `error: relay at ${url} has no /voice/config — server is too old or voice plugin not loaded.\n`
      )
    } else {
      process.stderr.write(
        `error: GET /voice/config returned ${basic.status}: ${detail}\n`
      )
    }
    return 1
  }

  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  const cfg = basic.body as VoiceConfigResponse
  const rt = (realtime.status === 200 ? (realtime.body as RealtimeVoiceConfigResponse) : null)

  process.stdout.write(t.bold(`Voice on ${url}`) + '\n\n')
  process.stdout.write(formatProvider(cfg.stt, 'STT', t) + '\n')
  const sttEnhanced = formatEnhanced(cfg.stt, t)
  if (sttEnhanced) {
    process.stdout.write(sttEnhanced + '\n')
  }
  process.stdout.write(formatProvider(cfg.tts, 'TTS', t) + '\n')
  const ttsEnhanced = formatEnhanced(cfg.tts, t)
  if (ttsEnhanced) {
    process.stdout.write(ttsEnhanced + '\n')
  }
  for (const line of formatRealtime(rt, t)) {
    process.stdout.write(line + '\n')
  }

  const sttOk = cfg.stt?.enabled !== false && !!cfg.stt?.provider
  const ttsOk = cfg.tts?.enabled !== false && !!cfg.tts?.provider
  process.stdout.write('\n')
  if (sttOk && ttsOk) {
    process.stdout.write(t.okLine('Native Hermes voice is configured on the server.') + '\n')
  } else if (!sttOk && !ttsOk) {
    process.stdout.write(
      t.warnLine('Neither STT nor TTS is configured.') + '\n' +
        t.muted('  Edit ~/.hermes/config.yaml on the server (stt.provider / tts.provider) and restart.') + '\n'
    )
  } else {
    process.stdout.write(
      t.warnLine(`Partial: ${sttOk ? 'STT' : 'TTS'} is configured, ${sttOk ? 'TTS' : 'STT'} is not.`) + '\n' +
        t.muted('  See ~/.hermes/config.yaml on the server.') + '\n'
    )
  }
  process.stdout.write(`\n  ${t.statusDot(true)} ${t.muted('enabled')}   ${t.statusDot(false)} ${t.muted('available but off')}\n`)

  return 0
}

// ─────────────────────────────────────────────────────────────────────────
// voice mode — push-to-talk in a browser, proxied through this Node process
// to the relay's existing /voice/transcribe + prompt.submit + /voice/synthesize.
// ─────────────────────────────────────────────────────────────────────────

interface AuthedRelay {
  relay: RelayTransport
  url: string
  endpointRole: string | null
  token: string
}

/** Mirror of chat.ts's connectAndAuth but also exposes the final token so
 * we can hand it to the voice server. Stays inline rather than refactoring
 * chat.ts because the chat path doesn't need the token externally. */
async function connectAndAuth(args: ParsedArgs): Promise<AuthedRelay> {
  let urlFlag =
    (typeof args.flags.remote === 'string' ? args.flags.remote.trim() : null) ??
    process.env.HERMES_RELAY_URL?.trim() ??
    null
  const argCode = typeof args.flags.code === 'string' ? args.flags.code : undefined
  const argToken = typeof args.flags.token === 'string' ? args.flags.token : undefined
  const argPairQr =
    typeof args.flags['pair-qr'] === 'string' ? args.flags['pair-qr'] : process.env.HERMES_RELAY_PAIR_QR
  const nonInteractive = !!args.flags['non-interactive']

  if (!urlFlag && !argPairQr) {
    urlFlag = await resolveFirstRunUrl({ nonInteractive })
  }

  const probeUrl = urlFlag ?? 'ws://pair-qr-pending'

  for (let attempt = 0; attempt < 2; attempt++) {
    const creds = await resolveCredentials(probeUrl, {
      argCode,
      argToken,
      argPairQr,
      nonInteractive
    })

    const url = (creds.resolvedEndpoint?.relay.url ?? urlFlag)!.trim()
    const endpointRole = creds.resolvedEndpoint?.role ?? null

    const cfg: ConstructorParameters<typeof RelayTransport>[0] = {
      url,
      deviceName: `hermes-relay-cli voice (${process.platform})`
    }
    if (creds.pairingCode) cfg.pairingCode = creds.pairingCode
    if (creds.sessionToken) cfg.sessionToken = creds.sessionToken

    const relay = new RelayTransport(cfg)

    let mintedToken: string | null = creds.sessionToken ?? null
    relay.onAuthSuccess((token, ver, meta) => {
      mintedToken = token
      void saveSession(url, token, ver, {
        grants: meta.grants,
        ttlExpiresAt: meta.ttlExpiresAt,
        endpointRole
      })
    })

    relay.start()
    const outcome = await relay.whenAuthResolved()

    if (outcome.ok && mintedToken) {
      return { relay, url, endpointRole, token: mintedToken }
    }

    try { relay.kill() } catch { /* ignore */ }
    if (creds.sessionToken) await deleteSession(url)

    if (attempt === 1 || nonInteractive) {
      throw new Error(`relay rejected credentials: ${outcome.ok ? 'no token issued' : outcome.reason}`)
    }
    process.stderr.write(`\nRelay rejected credentials: ${outcome.ok ? 'no token issued' : outcome.reason}\n`)
  }

  throw new Error('unreachable: connectAndAuth exhausted loop')
}

function waitForReady(gw: GatewayClient, timeoutMs = READY_TIMEOUT_MS): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      gw.off('event', handler)
      reject(new Error(`gateway.ready timeout after ${timeoutMs}ms`))
    }, timeoutMs)
    const handler = (ev: GatewayEvent) => {
      if (ev.type === 'gateway.ready') {
        clearTimeout(timer)
        gw.off('event', handler)
        resolve()
      }
    }
    gw.on('event', handler)
  })
}

async function createOrResumeSession(
  gw: GatewayClient,
  resumeId: string | null
): Promise<{
  sessionId: string
  model: string | null
  projectName: string | null
  queuedPrompt: string | null
  resumeActivity: SessionResumeActivity
  resumeDrain: Promise<void> | null
}> {
  const cols = process.stdout.columns ?? 80
  if (resumeId) {
    const drain = new SessionResumeDrain(gw)
    try {
      const raw = await gw.request<SessionResumeResponse>('session.resume', { session_id: resumeId, cols })
      const r = asRpcResult<SessionResumeResponse>(raw)
      if (!r?.session_id) throw new Error(`failed to resume session ${resumeId}`)
      const resumeActivity = sessionResumeActivity(r)
      const drained = drain.activate(r.session_id, resumeActivity)
      return {
        sessionId: r.session_id,
        model: r.info?.model ?? null,
        projectName: sessionProjectName(r.info),
        queuedPrompt: sessionQueuedPromptPreview(r),
        resumeActivity,
        resumeDrain: resumeActivity === 'idle' ? null : drained
      }
    } catch (error) {
      drain.cancel()
      throw error
    }
  }
  const raw = await gw.request<SessionCreateResponse>('session.create', { cols })
  const r = asRpcResult<SessionCreateResponse>(raw)
  if (!r?.session_id) throw new Error('failed to create session')
  return {
    sessionId: r.session_id,
    model: r.info?.model ?? null,
    projectName: sessionProjectName(r.info),
    queuedPrompt: null,
    resumeActivity: 'idle',
    resumeDrain: null
  }
}

async function voiceMode(args: ParsedArgs): Promise<number> {
  const noOpen = !!args.flags['no-open']
  const portFlag = typeof args.flags.port === 'string' ? parseInt(args.flags.port, 10) : NaN
  const port = Number.isFinite(portFlag) && portFlag >= 0 && portFlag <= 65535 ? portFlag : 0
  const conversation =
    (typeof args.flags.conversation === 'string' ? args.flags.conversation : null) ??
    (typeof args.flags.session === 'string' ? args.flags.session : null)

  process.stderr.write(`Connecting to relay...\n`)
  let authed: AuthedRelay
  try {
    authed = await connectAndAuth(args)
  } catch (e) {
    process.stderr.write(`error: ${rpcErrorMessage(e)}\n`)
    return 1
  }
  const { relay, url, token } = authed

  const gw = new GatewayClient(relay)

  const tearDownState = { closed: false }
  let voiceServerHandle: { url: string; close: () => Promise<void> } | null = null

  const tearDown = async () => {
    if (tearDownState.closed) return
    tearDownState.closed = true
    try { await voiceServerHandle?.close() } catch { /* ignore */ }
    try { gw.kill() } catch { /* ignore */ }
  }
  setupGracefulExit({ cleanups: [tearDown] })

  const ready = waitForReady(gw)
  gw.start()
  gw.drain()
  try {
    await ready
  } catch (e) {
    process.stderr.write(`error: ${rpcErrorMessage(e)}\n`)
    await tearDown()
    return 1
  }

  let session: {
    sessionId: string
    model: string | null
    projectName: string | null
    queuedPrompt: string | null
    resumeActivity: SessionResumeActivity
    resumeDrain: Promise<void> | null
  }
  try {
    session = await createOrResumeSession(gw, conversation)
  } catch (e) {
    process.stderr.write(`error: ${rpcErrorMessage(e)}\n`)
    await tearDown()
    return 1
  }
  if (session.model || session.projectName) {
    const modelDetail = session.model ? ` on ${session.model}` : ''
    const projectDetail = session.projectName ? ` · ${session.projectName}` : ''
    process.stderr.write(`Session ${session.sessionId.slice(0, 8)}…${modelDetail}${projectDetail}\n`)
  }
  if (session.resumeActivity === 'running-and-queued') {
    process.stderr.write('Resumed live turn; the next accepted prompt is queued.\n')
  } else if (session.resumeActivity === 'running') {
    process.stderr.write('Resumed live turn still in progress.\n')
  } else if (session.resumeActivity === 'queued') {
    process.stderr.write('Resumed session with an accepted prompt waiting to run.\n')
  }
  if (session.queuedPrompt) {
    process.stderr.write(`Queued prompt: ${session.queuedPrompt}\n`)
  }
  if (session.resumeDrain) {
    try {
      await session.resumeDrain
      process.stderr.write('Resumed activity completed.\n')
    } catch (e) {
      process.stderr.write(`error: ${rpcErrorMessage(e)}\n`)
      await tearDown()
      return 1
    }
  }

  try {
    voiceServerHandle = await startVoiceServer({
      token,
      relayUrl: url,
      gateway: gw,
      sessionId: session.sessionId,
      port
    })
  } catch (e) {
    process.stderr.write(`error: failed to start voice server: ${rpcErrorMessage(e)}\n`)
    await tearDown()
    return 1
  }

  process.stdout.write(
    `\nVoice mode ready.\n` +
      `  Open: ${voiceServerHandle.url}\n` +
      `  Relay: ${url}\n` +
      `  Press Ctrl+C to stop.\n\n`
  )

  if (!noOpen) {
    openInBrowser(voiceServerHandle.url)
  }

  // Park until the gateway transport exits (user Ctrl+C also triggers
  // tearDown via setupGracefulExit). Returning here ends the process.
  await new Promise<void>((resolve) => {
    gw.on('exit', () => resolve())
  })
  await tearDown()
  return 0
}

export async function voiceCommand(args: ParsedArgs): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  if (args.flags.help) {
    printUsage(VOICE_USAGE, t)
    return 0
  }
  const sub = args.positional[0] ?? 'status'
  const url = typeof args.flags.remote === 'string' ? args.flags.remote : undefined

  if (sub === 'status') {
    if (args.positional[0] === 'status') {
      args.positional.shift()
    }
    try {
      return await voiceStatus(args)
    } catch (e) {
      process.stderr.write(formatError(e, { command: 'voice', url }, t) + '\n')
      return 1
    }
  }

  if (sub === 'mode') {
    args.positional.shift()
    try {
      return await voiceMode(args)
    } catch (e) {
      process.stderr.write(formatError(e, { command: 'voice', url }, t) + '\n')
      return 1
    }
  }

  return unknownSubcommand(VOICE_USAGE, sub, t)
}

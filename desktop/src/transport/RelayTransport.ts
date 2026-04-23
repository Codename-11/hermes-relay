// Vendored from hermes-agent/ui-tui/src/transport/RelayTransport.ts
// (feat/tui-transport-pluggable, 2026-04-23). Changes from source:
//   - Dropped `deviceId` auto-generation — CLI leaves it to the caller.
//   - `sendResize` kept even though CLI doesn't currently wire SIGWINCH;
//     leaving it hot avoids a surprise when we add streaming REPL later.
//   - Added reconnect-on-drop state machine + TOFU cert pinning. The
//     TUI upstream stays one-shot; this divergence is local-only.
// Envelope protocol (docs/relay-protocol.md §3.7) stays in lockstep with
// the TUI — only the lifecycle around `open`/`close` changed.

import { randomUUID } from 'node:crypto'
import { EventEmitter } from 'node:events'
import { connect as tlsConnect } from 'node:tls'

import { comparePins, extractSpkiSha256, isSecureUrl, pinKey } from '../certPin.js'
import type { GatewayEvent } from '../gatewayTypes.js'
import { CircularBuffer } from '../lib/circularBuffer.js'
import { getSession, saveSession } from '../remoteSessions.js'

import type { Transport } from './Transport.js'

const MAX_LOG_LINES = 200
const MAX_LOG_LINE_BYTES = 4096
const MAX_BUFFERED_EVENTS = 2000
const REQUEST_TIMEOUT_MS = Math.max(30000, parseInt(process.env.HERMES_RELAY_RPC_TIMEOUT_MS ?? '120000', 10) || 120000)
const AUTH_TIMEOUT_MS = Math.max(5000, parseInt(process.env.HERMES_RELAY_AUTH_TIMEOUT_MS ?? '15000', 10) || 15000)

// Reconnect knobs — mirrored from Android ConnectionManager.kt.
const RECONNECT_BASE_MS = 1000
const RECONNECT_MAX_MS = 30_000
const RECONNECT_BACKOFF_CEIL = 4 // cap the exponent so 2^n doesn't overflow past MAX
const RECONNECT_RATE_LIMITED_MS = 5 * 60 * 1000
const TLS_PROBE_TIMEOUT_MS = 10_000

const truncateLine = (line: string) =>
  line.length > MAX_LOG_LINE_BYTES ? `${line.slice(0, MAX_LOG_LINE_BYTES)}… [truncated ${line.length} bytes]` : line

const asGatewayEvent = (value: unknown): GatewayEvent | null =>
  value && typeof value === 'object' && !Array.isArray(value) && typeof (value as { type?: unknown }).type === 'string'
    ? (value as GatewayEvent)
    : null

interface Pending {
  id: string
  method: string
  reject: (e: Error) => void
  resolve: (v: unknown) => void
  timeout: ReturnType<typeof setTimeout>
}

interface WSMessageEvent { data: unknown }
interface WSCloseEvent { code: number; reason: string }
interface WSErrorEvent { message?: string }

interface WSLike {
  readyState: number
  send(data: string): void
  close(code?: number, reason?: string): void
  addEventListener(type: 'open', listener: () => void): void
  addEventListener(type: 'message', listener: (ev: WSMessageEvent) => void): void
  addEventListener(type: 'close', listener: (ev: WSCloseEvent) => void): void
  addEventListener(type: 'error', listener: (ev: WSErrorEvent) => void): void
}

type WSFactory = (url: string) => WSLike

export interface AuthMeta {
  /** Per-channel grant expiry (epoch seconds; `null` = never). Shape matches
   * `auth.ok.grants` — typical keys: `chat`, `terminal`, `bridge`, `tui`. */
  grants: Record<string, number | null> | null
  /** Session token expiry (epoch seconds; `null` = never). */
  ttlExpiresAt: number | null
  /** Server's hint about the transport it's running on — `"wss"` / `"ws"` / `"unknown"`.
   * Used by the contextual connect banner so we can tell the user what they're on. */
  transportHint: string | null
}

export type AuthOutcome =
  | { ok: true; serverVersion: null | string; token: string; meta: AuthMeta }
  | { ok: false; reason: string }

/** Internal state for the reconnect machine. `idle` = pre-start or fully
 * stopped; `connecting` = initial connect in flight; `connected` =
 * auth.ok seen; `reconnecting` = socket dropped, backoff timer armed. */
type ReconnectState = 'idle' | 'connecting' | 'connected' | 'reconnecting'

const defaultWSFactory: WSFactory = url => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const Ctor = (globalThis as any).WebSocket

  if (typeof Ctor !== 'function') {
    throw new Error('RelayTransport: global WebSocket not available. Need Node >=21.')
  }

  return new Ctor(url) as WSLike
}

export interface RelayTransportConfig {
  url: string
  /** One-time pairing code. Mutually exclusive with sessionToken. */
  pairingCode?: string
  /** Previously-minted session token for reconnection. */
  sessionToken?: string
  /** Human-readable label for the "Paired Devices" list. */
  deviceName?: string
  /** Stable per-install identifier. */
  deviceId?: string
  /** Requested session lifetime in seconds (0 = never expire). */
  ttlSeconds?: number
  /** Test hook. */
  wsFactory?: WSFactory
  /** Auto-reconnect on WSS close. Default: true. Set false for one-shot
   * commands (pair, tools list). */
  autoReconnect?: boolean
  /** Max reconnect attempts before giving up and emitting 'exit'. 0 =
   * unlimited. Default: 0. */
  maxReconnectAttempts?: number
  /** Fires after a successful reconnect — caller should re-send whatever
   * attach envelope it depends on (tui.attach / terminal.attach) since the
   * server-side subprocess was lost when the socket closed. */
  onReconnect?: () => void
  /** Predicate checked both at schedule time and after backoff expires.
   * Returning false aborts reconnect — used for credential-purge races
   * (e.g. user ran `hermes-relay pair --reset` mid-session). */
  reconnectGate?: () => boolean
  /** When set false, suppress the `desktop.workspace` advertisement
   * fired on first auth.ok. Default: true — every connection sends a
   * one-shot workspace envelope (cwd / git state / hostname). One-shot
   * or hostile-on-slow-disk callers can disable this to skip the git
   * shell-outs, but it costs <100ms and send errors are swallowed, so
   * most callers should leave it on. */
  emitWorkspaceEnvelope?: boolean
  /** When true, after auth resolves, start an `ActiveEditor` poller that
   * advertises tmux/VSCode hints as `desktop.active_editor` envelopes
   * every 5 s (deduped by value). Stopped on `kill()`. Default: false —
   * opt-in via the top-level `--watch-editor` flag. */
  watchEditor?: boolean
}

/**
 * RelayTransport pipes JSON-RPC to a remote `tui_gateway` subprocess via the
 * hermes-relay `tui` channel (docs/relay-protocol.md §3.7). Outbound JSON-RPC
 * is wrapped in `tui.rpc.request` envelopes; inbound `tui.rpc.response` and
 * `tui.rpc.event` envelopes are unwrapped back to the flat JSON-RPC shape.
 *
 * Reconnect semantics:
 * - First `start()` opens the socket, runs TOFU (if wss://), then auths.
 * - On unexpected close AFTER auth.ok, schedules a reconnect with exponential
 *   backoff (1s, 2s, 4s, 8s, 16s, 30s, 30s, …). Rate-limit closes get 5min.
 * - `'reconnecting'` fires with `{attempt, delayMs}` before each backoff.
 *   `'reconnected'` fires once auth.ok lands on the new socket.
 * - The original `whenAuthResolved()` promise settles on the FIRST connect
 *   only — subsequent reconnects do not re-resolve it. Callers that care
 *   listen for the `'reconnected'` event.
 */
export class RelayTransport extends EventEmitter implements Transport {
  private ws: WSLike | null = null
  private wsFactory: WSFactory
  private cfg: RelayTransportConfig
  private reqId = 0
  private logs = new CircularBuffer<string>(MAX_LOG_LINES)
  private pending = new Map<string, Pending>()
  private bufferedEvents = new CircularBuffer<GatewayEvent>(MAX_BUFFERED_EVENTS)
  private pendingExit: number | null | undefined
  private subscribed = false
  private authResolved = false
  private authTimer: ReturnType<typeof setTimeout> | null = null
  sessionToken: string | null = null
  serverVersion: string | null = null
  /** Auth.ok metadata captured on handshake — surfaces grants / ttl / transport
   * hint to the CLI so `hermes-relay status`, the connect banner, and future
   * TTL-aware flows don't have to re-RPC for data the handshake already carried. */
  authMeta: AuthMeta = { grants: null, ttlExpiresAt: null, transportHint: null }
  private authSuccessObservers: Array<(token: string, serverVersion: string | null, meta: AuthMeta) => void> = []
  private authSettlers: Array<(r: AuthOutcome) => void> = []
  private authFailReason: null | string = null
  private started = false
  private tornDown = false
  /** Listeners keyed by channel name. Lets the `shell` subcommand receive raw
   * `terminal.*` envelopes without routing through the JSON-RPC wrapper the
   * `tui` channel uses. If a channel has no listener, frames fall through to
   * the existing log-and-drop path so unknown channels stay non-fatal. */
  private channelListeners = new Map<string, (type: string, payload: Record<string, unknown>) => void>()

  // Workspace / editor awareness — fired after each successful auth.ok.
  // `workspaceAdvertised` prevents duplicate sends on reconnect (the
  // server already knows the workspace; reconnects just resume session
  // scope, and the git tree has probably not changed). `editorStop` is
  // the teardown for the active-editor poller.
  private workspaceAdvertised = false
  private editorStop: (() => void) | null = null

  // Reconnect state ------------------------------------------------------
  private state: ReconnectState = 'idle'
  /** Count of reconnect ATTEMPTS (not successes). Resets to 0 on each
   * successful auth.ok. */
  private reconnectAttempt = 0
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  /** True once a reconnect has succeeded (auth.ok on a new socket). Used to
   * pick which auth handler path runs — subsequent auth.ok should fire
   * `'reconnected'`, not settle the initial `whenAuthResolved()` promise. */
  private reconnectInFlight = false

  constructor(cfg: RelayTransportConfig) {
    super()
    this.setMaxListeners(0)
    this.cfg = cfg
    this.wsFactory = cfg.wsFactory ?? defaultWSFactory
    this.sessionToken = cfg.sessionToken ?? null
  }

  onAuthSuccess(cb: (token: string, serverVersion: string | null, meta: AuthMeta) => void): void {
    this.authSuccessObservers.push(cb)
  }

  /** Current reconnect-machine state — exposed for diagnostics and tests.
   * `idle` before `start()` / after terminal teardown; `connecting` during
   * initial connect; `connected` after auth.ok; `reconnecting` while a
   * backoff timer is armed. */
  getState(): ReconnectState {
    return this.state
  }

  whenAuthResolved(): Promise<AuthOutcome> {
    if (this.authResolved && this.sessionToken) {
      return Promise.resolve({
        ok: true,
        serverVersion: this.serverVersion,
        token: this.sessionToken,
        meta: this.authMeta
      })
    }
    if (this.tornDown) {
      return Promise.resolve({ ok: false, reason: this.authFailReason ?? 'disconnected before auth' })
    }

    return new Promise<AuthOutcome>(resolve => {
      this.authSettlers.push(resolve)
    })
  }

  private settleAuth(outcome: AuthOutcome): void {
    const settlers = this.authSettlers
    this.authSettlers = []
    for (const s of settlers) {
      try {
        s(outcome)
      } catch {
        /* ignore */
      }
    }
  }

  sendResize(cols: number, rows: number): void {
    if (!this.ws || !this.authResolved) {
      return
    }
    this.sendEnvelope('tui', 'tui.resize', { cols, rows })
  }

  getAuthInfo(): { serverVersion: string | null; token: string } | null {
    if (!this.authResolved || !this.sessionToken) {
      return null
    }

    return { serverVersion: this.serverVersion, token: this.sessionToken }
  }

  start() {
    if (this.started) {
      return
    }
    this.started = true
    this.pendingExit = undefined
    this.authResolved = false
    this.tornDown = false
    this.authFailReason = null
    this.state = 'connecting'
    void this.connectOnce()
  }

  /** Shared first-connect + reconnect path. Runs TOFU (wss://-only), opens
   * the WS, and wires lifecycle listeners. Any failure tears the transport
   * down; the close handler decides whether to re-schedule. */
  private async connectOnce(): Promise<void> {
    // Arm auth timeout per-attempt. Cancelled by auth.ok or by teardown.
    if (this.authTimer) {
      clearTimeout(this.authTimer)
    }
    this.authTimer = setTimeout(() => {
      if (this.authResolved) {
        return
      }
      const msg = `auth timed out after ${AUTH_TIMEOUT_MS}ms`
      this.pushLog(`[auth] ${msg} (url=${this.cfg.url})`)
      this.publish({ type: 'gateway.start_timeout', payload: {} })
      this.teardownSocket(-1, msg)
    }, AUTH_TIMEOUT_MS)

    // TOFU: probe the TLS peer cert BEFORE the WebSocket handshake so we can
    // refuse to open the WS on a pin mismatch. No-op for ws://.
    if (isSecureUrl(this.cfg.url)) {
      try {
        await this.verifyOrCapturePin()
      } catch (e) {
        const msg = e instanceof Error ? e.message : String(e)
        this.pushLog(`[tofu] ${msg}`)
        this.publish({ type: 'gateway.stderr', payload: { line: `[tofu] ${msg}` } })
        this.authFailReason = msg
        this.teardownSocket(-1, msg)

        return
      }
    }

    let ws: WSLike

    try {
      ws = this.wsFactory(this.cfg.url)
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      this.pushLog(`[ws] factory failed: ${msg}`)
      this.publish({ type: 'gateway.stderr', payload: { line: `[ws] ${msg}` } })
      this.teardownSocket(-1, msg)

      return
    }

    this.ws = ws

    ws.addEventListener('open', () => {
      this.pushLog(`[ws] open → ${this.cfg.url}`)
      this.sendAuth()
    })

    ws.addEventListener('message', ev => {
      const raw = typeof ev.data === 'string' ? ev.data : String(ev.data ?? '')
      this.handleFrame(raw)
    })

    ws.addEventListener('close', ev => {
      this.pushLog(`[ws] close code=${ev.code} reason=${ev.reason || ''}`)
      this.handleClose(ev.code, ev.reason)
    })

    ws.addEventListener('error', ev => {
      const msg = ev?.message ?? 'WebSocket error'
      this.pushLog(`[ws] error: ${msg}`)
      this.publish({ type: 'gateway.stderr', payload: { line: `[ws] ${msg}` } })
    })
  }

  /** Runs a short TLS probe to the target host:port, extracts the leaf
   * cert's SPKI sha256, and either captures it (first-time) or compares
   * against the stored pin. Rejects on mismatch or probe failure.
   *
   * Why a pre-WS probe? Node's built-in WebSocket (from undici) hides the
   * underlying TLSSocket, so we can't call `getPeerCertificate()` on the
   * live WS. A throwaway `tls.connect()` to the same host:port runs the
   * exact same TLS handshake (same SNI, same cert selection), costs
   * ~10–30ms once per connect, and surfaces `rawCert` directly. */
  private async verifyOrCapturePin(): Promise<void> {
    const u = new URL(this.cfg.url)
    const host = u.hostname
    const port = parseInt(u.port || '443', 10)
    const key = pinKey(this.cfg.url)
    const stored = await getSession(this.cfg.url)
    const expectedPin = stored?.certPinSha256 ?? null

    const actualPin = await new Promise<string>((resolve, reject) => {
      const socket = tlsConnect({
        host,
        port,
        servername: host,
        // Let Node's default CA store run. Self-signed servers still TOFU-
        // pin on subsequent connects, but the first probe requires a valid
        // chain. If users need a self-signed flow, they can pre-seed a pin
        // via the Android app or a future `--trust-self-signed` flag.
        rejectUnauthorized: true
      })

      const timer = setTimeout(() => {
        socket.destroy(new Error(`TLS probe to ${key} timed out after ${TLS_PROBE_TIMEOUT_MS}ms`))
      }, TLS_PROBE_TIMEOUT_MS)
      timer.unref?.()

      socket.once('secureConnect', () => {
        clearTimeout(timer)
        try {
          // `detailed=false` here; we only need `raw` (DER). We always pin
          // the leaf — intermediates rotate on CA renewal and would cause
          // spurious mismatches.
          const peer = socket.getPeerCertificate(false)
          const raw = (peer as unknown as { raw?: Buffer })?.raw

          if (!raw || !Buffer.isBuffer(raw) || raw.length === 0) {
            socket.destroy()
            reject(new Error(`TLS probe to ${key} returned no peer cert`))

            return
          }
          const pin = extractSpkiSha256(raw)
          socket.end()
          resolve(pin)
        } catch (e) {
          socket.destroy()
          reject(e instanceof Error ? e : new Error(String(e)))
        }
      })

      socket.once('error', err => {
        clearTimeout(timer)
        reject(err)
      })
    })

    if (expectedPin) {
      if (!comparePins(expectedPin, actualPin)) {
        // Surface a user-friendly remediation path. The session file carries
        // the pin so a re-pair clears it; `saveSession(..., {certPin: null})`
        // also wipes it if a `--reset-pin` flag lands.
        throw new Error(
          `cert pin mismatch for ${key}: expected ${expectedPin}, got ${actualPin}. ` +
            `If this server was legitimately rotated, re-pair with \`hermes-relay pair\` ` +
            `to capture the new pin.`
        )
      }
      this.pushLog(`[tofu] pin match for ${key}`)

      return
    }

    // First-time capture. Don't overwrite a token we don't have — merge via
    // saveSession (which preserves token/version if omitted isn't safe; we
    // need both, so capture only when we have a stored record to merge
    // into). If there's no stored session yet (initial pair path), the
    // pair flow itself will call saveSession with the pin.
    if (stored) {
      await saveSession(this.cfg.url, stored.token, stored.serverVersion, { certPin: actualPin })
      this.pushLog(`[tofu] captured pin for ${key}: ${actualPin}`)
    } else {
      // No stored session; we're on the initial pair path. The pair command
      // doesn't call verifyOrCapturePin directly (it runs with autoReconnect
      // off and saves the pin itself), so this branch is mostly defensive.
      this.pushLog(`[tofu] observed pin for ${key} (no stored session to merge into): ${actualPin}`)
    }
  }

  private sendAuth() {
    if (!this.ws) {
      return
    }

    const payload: Record<string, unknown> = {}

    if (this.cfg.pairingCode) {
      payload.pairing_code = this.cfg.pairingCode
    } else if (this.sessionToken) {
      // Prefer the live token over the initial cfg — after a reconnect,
      // the stored token is what we must present.
      payload.session_token = this.sessionToken
    } else if (this.cfg.sessionToken) {
      payload.session_token = this.cfg.sessionToken
    } else {
      const msg = 'RelayTransport: neither pairingCode nor sessionToken provided'
      this.pushLog(`[auth] ${msg}`)
      this.publish({ type: 'gateway.stderr', payload: { line: msg } })
      this.teardownSocket(-1, msg)

      return
    }

    if (this.cfg.deviceName) {
      payload.device_name = this.cfg.deviceName
    }

    if (this.cfg.deviceId) {
      payload.device_id = this.cfg.deviceId
    }

    if (typeof this.cfg.ttlSeconds === 'number') {
      payload.ttl_seconds = this.cfg.ttlSeconds
    }

    this.sendEnvelope('system', 'auth', payload)
  }

  private sendEnvelope(channel: string, type: string, payload: Record<string, unknown>, id?: string) {
    if (!this.ws) {
      return
    }
    const envelope = { channel, type, id: id ?? randomUUID(), payload }

    try {
      this.ws.send(JSON.stringify(envelope))
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      this.pushLog(`[ws] send failed: ${msg}`)
    }
  }

  private handleFrame(raw: string) {
    let msg: Record<string, unknown>

    try {
      msg = JSON.parse(raw) as Record<string, unknown>
    } catch {
      this.pushLog(`[protocol] malformed frame: ${raw.slice(0, 240)}`)
      this.publish({ type: 'gateway.protocol_error', payload: { preview: raw.slice(0, 240) } })

      return
    }

    const channel = typeof msg.channel === 'string' ? msg.channel : ''
    const type = typeof msg.type === 'string' ? msg.type : ''
    const payload = (msg.payload ?? {}) as Record<string, unknown>

    if (channel === 'system') {
      this.handleSystem(type, payload)

      return
    }

    if (channel === 'tui') {
      this.handleTui(type, payload)

      return
    }

    const listener = this.channelListeners.get(channel)
    if (listener) {
      try {
        listener(type, payload)
      } catch (e) {
        const msg = e instanceof Error ? e.message : String(e)
        this.pushLog(`[ws] ${channel} listener threw: ${msg}`)
      }
      return
    }

    this.pushLog(`[ws] ignoring ${channel}:${type}`)
  }

  /** Register a listener for raw envelopes on `channel`. Used by non-RPC
   * channels like `terminal` where payloads aren't wrapped in JSON-RPC.
   * Only one listener per channel — subsequent calls replace. Pass `null`
   * to unregister. */
  onChannel(
    channel: string,
    listener: ((type: string, payload: Record<string, unknown>) => void) | null
  ): void {
    if (listener) {
      this.channelListeners.set(channel, listener)
    } else {
      this.channelListeners.delete(channel)
    }
  }

  /** Send a raw envelope on an arbitrary channel. Caller owns the `type` +
   * `payload` shape — no RPC wrapping, no request/response tracking. Used by
   * the `shell` subcommand to pipe `terminal.input` / `terminal.resize`. */
  sendChannel(channel: string, type: string, payload: Record<string, unknown>): void {
    this.sendEnvelope(channel, type, payload)
  }

  private handleSystem(type: string, payload: Record<string, unknown>) {
    if (type === 'auth.ok') {
      const isReconnect = this.reconnectInFlight
      this.authResolved = true
      this.state = 'connected'
      this.reconnectAttempt = 0
      this.reconnectInFlight = false

      if (this.authTimer) {
        clearTimeout(this.authTimer)
        this.authTimer = null
      }

      const token = payload.session_token

      if (typeof token === 'string') {
        this.sessionToken = token
      }
      const ver = payload.server_version

      if (typeof ver === 'string') {
        this.serverVersion = ver
      }

      // Capture the metadata fields the server added in v0.6.x+. All are
      // optional — older relays send only {session_token, server_version}.
      // Grants: Record<channel, epoch|null> per plugin/relay/server.py:2745.
      const rawGrants = payload.grants
      let grants: Record<string, number | null> | null = null
      if (rawGrants && typeof rawGrants === 'object' && !Array.isArray(rawGrants)) {
        const norm: Record<string, number | null> = {}
        for (const [k, v] of Object.entries(rawGrants as Record<string, unknown>)) {
          if (v === null) {
            norm[k] = null
          } else if (typeof v === 'number' && Number.isFinite(v)) {
            norm[k] = v
          }
          // Non-null non-number values (bools, strings) are silently dropped —
          // defensive against schema drift.
        }
        grants = norm
      }
      const rawTtl = payload.expires_at
      const ttlExpiresAt =
        rawTtl === null
          ? null
          : typeof rawTtl === 'number' && Number.isFinite(rawTtl)
            ? rawTtl
            : null
      const rawHint = payload.transport_hint
      const transportHint = typeof rawHint === 'string' ? rawHint : null
      this.authMeta = { grants, ttlExpiresAt, transportHint }

      this.pushLog(
        `[auth] ok (server ${this.serverVersion ?? '?'}, transport=${transportHint ?? '?'}, ttl=${
          ttlExpiresAt === null ? 'never' : new Date(ttlExpiresAt * 1000).toISOString()
        })`
      )

      if (this.sessionToken) {
        for (const cb of this.authSuccessObservers) {
          try {
            cb(this.sessionToken, this.serverVersion, this.authMeta)
          } catch {
            /* persistence failures must not take down the transport */
          }
        }

        if (!isReconnect) {
          // First connect settles the original promise. Reconnects don't —
          // whenAuthResolved() is documented as first-settlement-only;
          // callers use the 'reconnected' event to re-attach.
          this.settleAuth({
            ok: true,
            serverVersion: this.serverVersion,
            token: this.sessionToken,
            meta: this.authMeta
          })
        }
      }

      this.sendEnvelope('tui', 'tui.attach', {
        cols: process.stdout.columns ?? 80,
        rows: process.stdout.rows ?? 24
      })

      // Fire-and-forget: advertise the local workspace to the relay so
      // downstream tooling (agent prompt-hook, session stickiness) can
      // answer "which repo is the user in?" without a round-trip. We
      // don't block auth completion on this. First connect only — on
      // reconnects the server-side session already has the context and
      // the git tree likely hasn't changed. If auto-reconnect is off
      // (one-shot commands like pair / tools list), this still fires
      // but is equally cheap to ignore server-side.
      // Default-on: the workspace envelope is a small best-effort hint
      // and the server drops unknown fields gracefully. Callers can set
      // `emitWorkspaceEnvelope: false` to suppress for ultra-short ops.
      if (this.cfg.emitWorkspaceEnvelope !== false && !this.workspaceAdvertised) {
        this.workspaceAdvertised = true
        void this.advertiseWorkspace()
      }
      // Start the editor poller on first auth.ok — survives reconnects
      // (ws reference re-captured per send via sendEnvelope). Guarded so
      // a dropped + resumed socket doesn't double-start the interval.
      if (this.cfg.watchEditor && !this.editorStop) {
        void this.startEditorPollerSafely()
      }

      if (isReconnect) {
        // Clear any stale buffered events from the pre-drop socket — they'd
        // confuse the caller post-reconnect (e.g. a dangling tool.started
        // whose tool.completed never arrives).
        this.bufferedEvents = new CircularBuffer<GatewayEvent>(MAX_BUFFERED_EVENTS)
        try {
          this.cfg.onReconnect?.()
        } catch (e) {
          const m = e instanceof Error ? e.message : String(e)
          this.pushLog(`[reconnect] onReconnect threw: ${m}`)
        }
        this.emit('reconnected')
      }

      return
    }

    if (type === 'auth.fail') {
      const reason = typeof payload.reason === 'string' ? payload.reason : 'auth failed'
      this.authFailReason = reason
      this.pushLog(`[auth] fail: ${reason}`)
      this.publish({ type: 'gateway.stderr', payload: { line: `[auth] ${reason}` } })
      // Auth failures are terminal — server rejected credentials, so
      // reconnecting with the same token would just fail again.
      this.teardownFinal(-1, reason)

      return
    }

    if (type === 'ping') {
      this.sendEnvelope('system', 'pong', { ts: typeof payload.ts === 'number' ? payload.ts : Date.now() })

      return
    }
  }

  private handleTui(type: string, payload: Record<string, unknown>) {
    if (type === 'tui.attached') {
      this.pushLog(`[tui] attached pid=${payload.pid ?? '?'} server=${payload.server_version ?? '?'}`)

      return
    }

    if (type === 'tui.rpc.response') {
      this.dispatchRpc(payload)

      return
    }

    if (type === 'tui.rpc.event') {
      const params = payload.params

      const ev = asGatewayEvent(
        params && typeof params === 'object' && !Array.isArray(params)
          ? (params as Record<string, unknown>)
          : null
      )

      if (ev) {
        this.publish(ev)
      }

      return
    }

    if (type === 'tui.error') {
      const message = typeof payload.message === 'string' ? payload.message : 'tui channel error'
      this.pushLog(`[tui] error: ${message}`)
      this.publish({ type: 'gateway.stderr', payload: { line: `[tui] ${message}` } })
      // Channel-level error — socket is probably unusable. Treat like a
      // drop; reconnect policy decides whether to retry.
      this.teardownSocket(-1, message)

      return
    }
  }

  private dispatchRpc(msg: Record<string, unknown>) {
    const id = msg.id as string | undefined
    const p = id ? this.pending.get(id) : undefined

    if (!p) {
      return
    }
    this.settle(p, msg.error ? this.toError(msg.error) : null, msg.result)
  }

  private toError(raw: unknown): Error {
    const err = raw as { message?: unknown } | null | undefined

    return new Error(typeof err?.message === 'string' ? err.message : 'request failed')
  }

  private settle(p: Pending, err: Error | null, result: unknown) {
    clearTimeout(p.timeout)
    this.pending.delete(p.id)

    if (err) {
      p.reject(err)
    } else {
      p.resolve(result)
    }
  }

  private publish(ev: GatewayEvent) {
    if (this.subscribed) {
      this.emit('event', ev)

      return
    }

    this.bufferedEvents.push(ev)
  }

  private pushLog(line: string) {
    this.logs.push(truncateLine(line))
  }

  private rejectPending(err: Error) {
    for (const p of this.pending.values()) {
      clearTimeout(p.timeout)
      p.reject(err)
    }

    this.pending.clear()
  }

  private onTimeout = (id: string) => {
    const p = this.pending.get(id)

    if (p) {
      this.pending.delete(id)
      p.reject(new Error(`timeout: ${p.method}`))
    }
  }

  /** Closes the socket, rejects pending, but does NOT emit `'exit'`. Routes
   * the close through `handleClose` so the reconnect policy runs. Used when
   * the transport itself decides the current socket is dead (auth timeout,
   * tui.error, TOFU failure). */
  private teardownSocket(code: number | null, reason: string): void {
    if (this.authTimer) {
      clearTimeout(this.authTimer)
      this.authTimer = null
    }
    this.rejectPending(new Error(`relay disconnected: ${reason || 'unknown'}`))
    const ws = this.ws
    this.ws = null

    try {
      ws?.close()
    } catch {
      /* ignore */
    }

    this.handleClose(code ?? -1, reason)
  }

  /** Unconditionally tears the transport down and emits `'exit'`. Used for
   * auth failures, exhausted reconnect attempts, and `kill()`. */
  private teardownFinal(code: number | null, reason: string): void {
    if (this.tornDown) {
      return
    }
    this.tornDown = true
    this.state = 'idle'

    // Stop the editor poller — it's the only interval the transport owns
    // directly, and leaving it running would keep the process alive past
    // a clean exit.
    if (this.editorStop) {
      try {
        this.editorStop()
      } catch {
        /* ignore */
      }
      this.editorStop = null
    }

    if (this.authTimer) {
      clearTimeout(this.authTimer)
      this.authTimer = null
    }

    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }

    if (!this.authResolved) {
      this.settleAuth({ ok: false, reason: this.authFailReason ?? (reason || 'disconnected before auth') })
    }

    this.rejectPending(new Error(`relay disconnected: ${reason || 'unknown'}`))
    const ws = this.ws
    this.ws = null

    try {
      ws?.close()
    } catch {
      /* ignore */
    }

    if (this.subscribed) {
      this.emit('exit', code)
    } else {
      this.pendingExit = code
    }
  }

  /** Routes a socket close through reconnect policy. Either schedules a
   * reconnect or escalates to `teardownFinal`. */
  private handleClose(code: number | null, reason: string): void {
    if (this.tornDown) {
      return
    }

    // Drop in-flight pending — callers see the disconnect immediately.
    // They'll retry their own requests once they hear 'reconnected'.
    this.rejectPending(new Error(`relay disconnected: ${reason || 'unknown'}`))
    const ws = this.ws
    this.ws = null
    try {
      ws?.close()
    } catch {
      /* ignore */
    }

    if (this.authTimer) {
      clearTimeout(this.authTimer)
      this.authTimer = null
    }

    const canReconnect =
      this.cfg.autoReconnect === true &&
      this.authResolved && // only reconnect sessions that were once healthy
      !this.authFailReason && // terminal auth failure blocks reconnect
      (this.cfg.reconnectGate?.() ?? true) &&
      this.withinAttemptLimit()

    if (!canReconnect) {
      this.teardownFinal(code, reason)

      return
    }

    this.scheduleReconnect(code, reason)
  }

  private withinAttemptLimit(): boolean {
    const max = this.cfg.maxReconnectAttempts ?? 0
    if (max <= 0) {
      return true
    }

    return this.reconnectAttempt < max
  }

  private backoffFor(attempt: number, reason: string): number {
    if (this.isRateLimited(reason)) {
      return RECONNECT_RATE_LIMITED_MS
    }
    const exp = Math.min(Math.max(attempt - 1, 0), RECONNECT_BACKOFF_CEIL)

    return Math.min(RECONNECT_BASE_MS * 2 ** exp, RECONNECT_MAX_MS)
  }

  private isRateLimited(reason: string): boolean {
    if (!reason) {
      return false
    }
    const lower = reason.toLowerCase()

    return lower.includes('429') || lower.includes('rate-limited') || lower.includes('rate limited')
  }

  private scheduleReconnect(_code: number | null, reason: string): void {
    this.state = 'reconnecting'
    this.reconnectAttempt += 1
    const delayMs = this.backoffFor(this.reconnectAttempt, reason)

    this.pushLog(
      `[reconnect] attempt ${this.reconnectAttempt} in ${delayMs}ms (reason=${reason || 'unknown'})`
    )
    this.emit('reconnecting', { attempt: this.reconnectAttempt, delayMs })

    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
    }

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null

      if (this.tornDown) {
        return
      }

      // Re-check gate post-backoff — the token could have been purged
      // while we slept.
      if (this.cfg.reconnectGate && !this.cfg.reconnectGate()) {
        this.teardownFinal(-1, 'reconnect gate rejected')

        return
      }

      if (!this.withinAttemptLimit()) {
        this.teardownFinal(-1, 'reconnect attempts exhausted')

        return
      }

      this.reconnectInFlight = true
      this.authResolved = false // gate RPC until the new socket auths
      this.state = 'connecting'
      void this.connectOnce()
    }, delayMs)

    this.reconnectTimer.unref?.()
  }

  drain() {
    this.subscribed = true

    for (const ev of this.bufferedEvents.drain()) {
      this.emit('event', ev)
    }

    if (this.pendingExit !== undefined) {
      const code = this.pendingExit
      this.pendingExit = undefined
      this.emit('exit', code)
    }
  }

  getLogTail(limit = 20): string {
    return this.logs.tail(Math.max(1, limit)).join('\n')
  }

  request<T = unknown>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    if (!this.ws) {
      return Promise.reject(new Error('relay transport not connected'))
    }

    if (!this.authResolved) {
      return Promise.reject(new Error('relay not authenticated yet'))
    }

    const id = `r${++this.reqId}`

    return new Promise<T>((resolve, reject) => {
      const timeout = setTimeout(this.onTimeout, REQUEST_TIMEOUT_MS, id)
      timeout.unref?.()

      this.pending.set(id, {
        id,
        method,
        reject,
        resolve: v => resolve(v as T),
        timeout
      })

      try {
        this.sendEnvelope('tui', 'tui.rpc.request', { id, jsonrpc: '2.0', method, params })
      } catch (e) {
        const pending = this.pending.get(id)

        if (pending) {
          clearTimeout(pending.timeout)
          this.pending.delete(id)
        }

        reject(e instanceof Error ? e : new Error(String(e)))
      }
    })
  }

  kill() {
    if (this.editorStop) {
      try {
        this.editorStop()
      } catch {
        /* ignore */
      }
      this.editorStop = null
    }
    this.teardownFinal(null, 'kill')
  }

  /** Detect the local workspace and send it as a single
   * `desktop.workspace` envelope. Errors are swallowed — the relay may
   * not recognize the envelope yet (alpha.6 ships the client side; the
   * server-side stash is additive), and a detection failure should not
   * take down the transport. */
  private async advertiseWorkspace(): Promise<void> {
    try {
      // Lazy import to avoid paying the git-shellout cost on short-lived
      // one-shots (pair, tools list) that never opt-in via
      // `emitWorkspaceEnvelope`.
      const { detectWorkspaceContext } = await import('../workspaceContext.js')
      const ctx = await detectWorkspaceContext()
      this.sendEnvelope('desktop', 'workspace', ctx as unknown as Record<string, unknown>)
      this.pushLog(`[workspace] advertised repo=${ctx.repo_name ?? '(none)'} branch=${ctx.git_branch ?? '(none)'}`)
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      this.pushLog(`[workspace] advertise failed: ${msg}`)
    }
  }

  private async startEditorPollerSafely(): Promise<void> {
    try {
      const { startEditorPoller } = await import('../activeEditor.js')
      // The poller emits `desktop.active_editor` envelopes via
      // `sendChannel`, which short-circuits when the ws is null — so a
      // transient drop + reconnect just has one wasted tick.
      this.editorStop = startEditorPoller(this, 5000)
      this.pushLog(`[workspace] active-editor poller started`)
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      this.pushLog(`[workspace] editor poller failed to start: ${msg}`)
    }
  }
}

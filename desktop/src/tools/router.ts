// DesktopToolRouter — Node mirror of Android's BridgeCommandHandler for the
// `desktop` relay channel. Server sends `desktop.command` envelopes; we look
// up the named tool, run it under an AbortController, and send a matching
// `desktop.response`. Periodic `desktop.status` heartbeats advertise which
// tools we handle so the server's Python-side `DesktopHandler.advertised`
// set knows which clients can service which calls.
//
// Wire contract (agreed with server-side agent, see commit 2026-04-23):
//   server → client:
//     { channel:'desktop', type:'desktop.command', id:'<uuid>',
//       payload:{ request_id, tool, args } }
//   client → server:
//     { channel:'desktop', type:'desktop.response',
//       payload:{ request_id, ok:true|false, result?, error? } }
//   client → server (periodic, every 30s + on attach):
//     { channel:'desktop', type:'desktop.status',
//       payload:{ advertised_tools: string[] } }
//
// Safety: the router is a no-op unless the caller has explicitly flagged
// `consentGranted: true`. Consent is a per-URL toggle stored in
// `~/.hermes/remote-sessions.json` and driven by chat.ts / shell.ts —
// this module just honors the flag.

import * as os from 'node:os'

import type { RelayTransport } from '../transport/RelayTransport.js'

import { VERSION } from '../version.js'
import { getComputerGrantSummary } from './computerGrants.js'

/** The payload shape server → client for a single tool invocation. */
export interface ToolCallPayload {
  request_id: string
  tool: string
  args: Record<string, unknown>
}

/** Either a success with a free-form result, or a failure with an error
 * message. The router serializes handler throws into the failure shape
 * automatically — handlers should just throw. */
export type ToolResponsePayload =
  | { request_id: string; ok: true; result: unknown }
  | { request_id: string; ok: false; error: string }

/** Context passed to every handler. `cwd` defaults to `process.cwd()` but
 * per-call overrides (e.g. terminalHandler's own `cwd` arg) still apply
 * inside the handler — this is just the router-level default. `abortSignal`
 * fires on transport teardown or 30s handler timeout; handlers should
 * honor it wherever they spawn children / do long I/O.
 *
 * `interactive` tells handlers whether they may prompt on stdin/stderr.
 * The patch approval flow (see `patchApproval.ts`) auto-rejects when this
 * is false so the daemon can't silently apply agent-proposed edits. It's
 * computed once at router-construction time from isTTY + daemon marker,
 * not re-probed per call — a daemon that foregrounds briefly shouldn't
 * flip the semantics of an in-flight tool invocation. */
export interface ToolContext {
  cwd: string
  abortSignal: AbortSignal
  interactive: boolean
}

/** A tool handler. Throws → router responds with `{ok:false, error}`. */
export type ToolHandler = (
  args: Record<string, unknown>,
  ctx: ToolContext
) => Promise<unknown>

export interface DesktopToolRouterOpts {
  handlers: Record<string, ToolHandler>
  /** Optional override for the heartbeat `advertised_tools` list — default
   * is `Object.keys(handlers)`. Useful when some handlers are stubs that
   * should not be advertised. */
  advertisedTools?: string[]
  /** If false, `attach()` logs a warning and does nothing. Wire chat.ts /
   * shell.ts to this so --no-tools or missing consent refuses cleanly. */
  consentGranted?: boolean
  /** Whether this router is running under an interactive TTY (shell / chat)
   * or headless (daemon, piped). Forwarded to handlers via ToolContext so
   * the patch-approval prompt can auto-reject in non-interactive mode
   * instead of hanging on stdin that no human will ever type into.
   * Default detection: stdin.isTTY && !HERMES_RELAY_DAEMON env var. */
  interactive?: boolean
}

/** Heartbeat cadence — matches server-side `DesktopHandler` expectation.
 * Server stamps `last_status` on receipt and clears advertised tools if
 * a client goes silent longer than ~90s. */
const HEARTBEAT_MS = 30_000

/** Per-handler timeout — if the handler hasn't resolved by then, the abort
 * signal fires and we send `{ok:false, error:'aborted'}`. 30s matches the
 * Android bridge's timeout for the same class of request/response RPCs. */
const HANDLER_TIMEOUT_MS = 30_000

function isToolCallPayload(x: unknown): x is ToolCallPayload {
  if (!x || typeof x !== 'object') {
    return false
  }
  const r = x as Record<string, unknown>
  return (
    typeof r.request_id === 'string' &&
    typeof r.tool === 'string' &&
    !!r.args &&
    typeof r.args === 'object' &&
    !Array.isArray(r.args)
  )
}

/** Default interactive detection — true iff stdin is a TTY AND we're not
 * flagged as the daemon subcommand. The daemon command sets
 * HERMES_RELAY_DAEMON=1 in its own process.env before constructing the
 * router; that's cheaper than threading a flag through every call site
 * and survives future callers (test harness, smoke, etc.) by default. */
function detectInteractive(): boolean {
  if (process.env.HERMES_RELAY_DAEMON === '1') {
    return false
  }
  return !!process.stdin.isTTY
}

export class DesktopToolRouter {
  private readonly handlers: Record<string, ToolHandler>
  private readonly advertisedTools: string[]
  private readonly consentGranted: boolean
  private readonly interactive: boolean
  private relay: RelayTransport | null = null
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null
  private attached = false
  /** Wall-clock time the router was constructed. Surfaced in the heartbeat
   * so the relay (and a `desktop_health` introspection tool) can report how
   * long the desktop client has been live. */
  private readonly startedAtMs: number = Date.now()
  /** Last error string from a tool dispatch (any cause: handler throw,
   * timeout, abort). Surfaced in the heartbeat so an agent or the dashboard
   * can ask "is this client healthy?" without parsing transcript history. */
  private lastError: { message: string; tool: string; ts: number } | null = null

  constructor(opts: DesktopToolRouterOpts) {
    this.handlers = opts.handlers
    this.advertisedTools = opts.advertisedTools ?? Object.keys(opts.handlers)
    this.consentGranted = opts.consentGranted ?? false
    // Explicit opts.interactive wins; otherwise detect from env/TTY. We
    // capture it once at construct time so a short shell session that
    // happens to get its stdin redirected mid-flight doesn't flip mode.
    this.interactive = opts.interactive ?? detectInteractive()
  }

  /** Install the `onChannel('desktop')` listener and start heartbeats.
   * Idempotent — calling twice is a no-op. Refuses silently if consent
   * wasn't granted; upstream code is expected to skip the whole router
   * in that case, this is just a defense-in-depth guard. */
  attach(relay: RelayTransport): void {
    if (this.attached) {
      return
    }
    if (!this.consentGranted) {
      // Defensive: caller should not have constructed us, but if they did,
      // don't silently serve commands.
      return
    }
    this.attached = true
    this.relay = relay

    relay.onChannel('desktop', (type, payload) => {
      if (type === 'desktop.command') {
        if (!isToolCallPayload(payload)) {
          // Malformed envelope — no request_id to echo back, so just drop.
          return
        }
        void this.dispatch(payload)
        return
      }
      // Unknown desktop.* types — ignore; server may extend later.
    })

    // Fire one heartbeat immediately so the server learns about us on attach
    // without waiting a full 30s cycle.
    this.sendHeartbeat()
    this.heartbeatTimer = setInterval(() => this.sendHeartbeat(), HEARTBEAT_MS)
    // Don't keep the event loop alive just for the heartbeat.
    this.heartbeatTimer.unref?.()
  }

  /** Remove the channel listener and stop heartbeats. Idempotent. */
  detach(): void {
    if (!this.attached) {
      return
    }
    this.attached = false
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
    try {
      this.relay?.onChannel('desktop', null)
    } catch {
      /* ignore */
    }
    this.relay = null
  }

  /** Broadcast the advertised-tools heartbeat. Safe to call when detached
   * — just no-ops. Now enriched with host/platform/version/uptime/last_error
   * so a `desktop_health` introspection tool can answer "what client is
   * connected, since when, and is it healthy?" without round-tripping. */
  private sendHeartbeat(): void {
    if (!this.relay) {
      return
    }
    try {
      const payload: Record<string, unknown> = {
        advertised_tools: this.advertisedTools,
        host: os.hostname(),
        platform: process.platform,
        arch: process.arch,
        node: process.version,
        version: VERSION,
        pid: process.pid,
        started_at_ms: this.startedAtMs,
        uptime_ms: Date.now() - this.startedAtMs,
        interactive: this.interactive
      }
      if (this.advertisedTools.some(name => name.startsWith('desktop_computer_'))) {
        payload.computer_use = {
          stage: 'experimental',
          protocol_version: 1,
          enabled: true,
          capabilities: ['status', 'screenshot', 'grant_request', 'cancel'],
          input: 'not_implemented',
          grant: getComputerGrantSummary(),
          overlay: {
            visible: false,
            state: 'not_available'
          }
        }
      }
      if (this.lastError) {
        payload.last_error = this.lastError
      }
      this.relay.sendChannel('desktop', 'desktop.status', payload)
    } catch {
      // Heartbeat failures are non-fatal; next cycle retries.
    }
  }

  /** Look up the handler, run it under a 30s AbortController, and reply.
   * Always sends a response — even unknown-tool, timeout, and thrown-error
   * paths — so the server's pending-request map never hangs. */
  private async dispatch(cmd: ToolCallPayload): Promise<void> {
    const { request_id, tool, args } = cmd
    const handler = this.handlers[tool]
    if (!handler) {
      this.sendResponse({
        request_id,
        ok: false,
        error: `unknown tool: ${tool}`
      })
      return
    }

    const controller = new AbortController()
    const timeoutTimer = setTimeout(() => {
      controller.abort()
    }, HANDLER_TIMEOUT_MS)
    // Timeout fires abort, which the handler should honor. Timer itself
    // is unref'd so it doesn't keep the process alive.
    timeoutTimer.unref?.()

    const ctx: ToolContext = {
      cwd: process.cwd(),
      abortSignal: controller.signal,
      interactive: this.interactive
    }

    try {
      const result = await handler(args, ctx)
      clearTimeout(timeoutTimer)
      // If the abort fired but the handler returned anyway, still treat
      // the outcome as the canonical result — the handler decided the
      // work was completable.
      this.sendResponse({ request_id, ok: true, result })
    } catch (e) {
      clearTimeout(timeoutTimer)
      // Distinguish aborts (timeout or transport teardown) from genuine
      // handler errors so the user-visible error message is accurate.
      const aborted = controller.signal.aborted
      const message = aborted
        ? 'aborted'
        : e instanceof Error
          ? e.message
          : String(e)
      // Stamp into lastError so the next heartbeat surfaces it to the
      // server's status snapshot — desktop_health uses this to answer
      // "is the connected client throwing on every dispatch?" without
      // grep'ing through journal logs.
      this.lastError = { message, tool, ts: Date.now() }
      this.sendResponse({ request_id, ok: false, error: message })
    }
  }

  private sendResponse(payload: ToolResponsePayload): void {
    if (!this.relay) {
      return
    }
    try {
      this.relay.sendChannel(
        'desktop',
        'desktop.response',
        payload as unknown as Record<string, unknown>
      )
    } catch {
      // If send fails, the server will time out the pending request; no
      // local recovery possible.
    }
  }
}

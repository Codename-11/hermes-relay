// chat — the default subcommand. One-shot when a prompt is given as
// positional args; REPL when stdin is a TTY; read-all-stdin-and-reply when
// stdin is piped. Re-uses the TUI's pairing flow patterns exactly (see
// docs/relay-protocol.md §3.7 for the handshake).

import { createInterface } from 'node:readline/promises'

import { buildConnectBanner } from '../banner.js'
import type { ParsedArgs } from '../cli.js'
import { resolveCredentials } from '../credentials.js'
import { GatewayClient } from '../gatewayClient.js'
import { resolveFirstRunUrl } from '../relayUrlPrompt.js'
import { getSession } from '../remoteSessions.js'
import type {
  GatewayEvent,
  PromptSubmitResponse,
  SessionCreateResponse,
  SessionInterruptResponse,
  SessionResumeResponse
} from '../gatewayTypes.js'
import { setupGracefulExit } from '../lib/gracefulExit.js'
import { asRpcResult, rpcErrorMessage } from '../lib/rpc.js'
import { deleteSession, saveSession } from '../remoteSessions.js'
import { CliRenderer } from '../renderer.js'
import { ensureToolsConsent } from '../tools/consent.js'
import { readFileHandler, writeFileHandler, patchHandler } from '../tools/handlers/fs.js'
import { searchFilesHandler } from '../tools/handlers/search.js'
import { terminalHandler } from '../tools/handlers/terminal.js'
import { DesktopToolRouter } from '../tools/router.js'
import { RelayTransport } from '../transport/RelayTransport.js'

// (getSession is imported above with the other remoteSessions exports so we
// can render the endpoint-role banner without changing the saveSession/auth
// persistence path.)

const READY_TIMEOUT_MS = 60_000
const TURN_TIMEOUT_MS = 10 * 60_000

function flag(args: ParsedArgs, name: string): string | null {
  const v = args.flags[name]
  return typeof v === 'string' ? v : null
}

function resolveRemoteOrNull(args: ParsedArgs): string | null {
  const url = flag(args, 'remote') ?? process.env.HERMES_RELAY_URL ?? null
  return url ? url.trim() : null
}

interface AuthedRelay {
  relay: RelayTransport
  /** Final URL actually connected to — from --remote OR from the --pair-qr
   * probe's winning endpoint. This is what the banner + saveSession use. */
  url: string
  /** Active endpoint role ("lan" / "tailscale" / "public") if the connection
   * was resolved via a multi-endpoint QR probe. Null for single-URL auth. */
  endpointRole: string | null
}

async function connectAndAuth(args: ParsedArgs): Promise<AuthedRelay> {
  let urlFlag = resolveRemoteOrNull(args)
  const argCode = flag(args, 'code') ?? undefined
  const argToken = flag(args, 'token') ?? undefined
  const argPairQr = flag(args, 'pair-qr') ?? process.env.HERMES_RELAY_PAIR_QR
  const nonInteractive = !!args.flags['non-interactive']

  // First-run fallback: no --remote, no env var, no QR payload. Auto-pick
  // a single stored session, pick from a list, or walk a new user through
  // URL entry. resolveFirstRunUrl throws on non-interactive + ambiguous.
  if (!urlFlag && !argPairQr) {
    urlFlag = await resolveFirstRunUrl({ nonInteractive })
  }

  // When --pair-qr supplies the URL, we don't yet know it at credential-
  // resolution time. Use the --remote value if present, else a placeholder
  // (harmless — the credentials module only uses it for the stored-session
  // lookup, which falls back to the QR path anyway).
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
      deviceName: `hermes-relay-cli (${process.platform})`
    }
    if (creds.pairingCode) {
      cfg.pairingCode = creds.pairingCode
    }
    if (creds.sessionToken) {
      cfg.sessionToken = creds.sessionToken
    }

    const relay = new RelayTransport(cfg)

    relay.onAuthSuccess((token, ver, meta) => {
      void saveSession(url, token, ver, {
        grants: meta.grants,
        ttlExpiresAt: meta.ttlExpiresAt,
        endpointRole
      })
    })

    relay.start()
    const outcome = await relay.whenAuthResolved()

    if (outcome.ok) {
      return { relay, url, endpointRole }
    }

    try {
      relay.kill()
    } catch {
      /* ignore */
    }

    if (creds.sessionToken) {
      // Stored token was rejected — purge so next run starts clean.
      await deleteSession(url)
    }

    if (attempt === 1 || nonInteractive) {
      throw new Error(`relay rejected credentials: ${outcome.reason}`)
    }

    process.stderr.write(`\nRelay rejected credentials: ${outcome.reason}\n`)
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

interface TurnHandle {
  /** Resolves on `message.complete` or when `cancel()` was called before an
   * `error` arrives. Rejects on `error` (when not cancelled) or turn timeout. */
  promise: Promise<void>
  /** Mark this turn as cancelled and fire `session.interrupt` at the server.
   * Must be called on the handle returned for *this* turn — the cancelled flag
   * is closed over locally so it can't be stomped by a later turn's SIGINT. */
  cancel: () => void
}

/** Start one prompt→response turn. Listener is attached BEFORE `prompt.submit`
 * so we can't miss early events. Returns a handle instead of a bare Promise so
 * the caller (REPL) can interrupt just this turn without mutating shared state
 * — a shared `{ interrupted }` box was racey because `finally { reset = false }`
 * could fire while the old handler was still in the queue. */
function runOneTurn(
  gw: GatewayClient,
  sessionId: string,
  prompt: string,
  renderer: CliRenderer
): TurnHandle {
  let cancelled = false
  let settled = false
  let detach: (() => void) | null = null

  const promise = new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => {
      if (settled) {
        return
      }
      settled = true
      detach?.()
      reject(new Error(`turn timeout after ${TURN_TIMEOUT_MS}ms`))
    }, TURN_TIMEOUT_MS)
    timer.unref?.()

    const handler = (ev: GatewayEvent) => {
      renderer.handle(ev)
      if (settled) {
        return
      }

      if (ev.type === 'message.complete') {
        settled = true
        clearTimeout(timer)
        detach?.()
        resolve()
        return
      }

      if (ev.type === 'error') {
        settled = true
        clearTimeout(timer)
        detach?.()
        if (cancelled) {
          resolve()
        } else {
          reject(new Error(ev.payload?.message ?? 'agent error'))
        }
      }
    }

    detach = () => gw.off('event', handler)
    gw.on('event', handler)

    gw.request<PromptSubmitResponse>('prompt.submit', { session_id: sessionId, text: prompt }).catch((e: unknown) => {
      if (settled) {
        return
      }
      settled = true
      clearTimeout(timer)
      detach?.()
      reject(new Error(rpcErrorMessage(e)))
    })
  })

  const cancel = () => {
    if (settled) {
      return
    }
    cancelled = true
    gw.request<SessionInterruptResponse>('session.interrupt', { session_id: sessionId }).catch(() => {
      /* best-effort; the error event we're about to receive will settle the turn */
    })
  }

  return { promise, cancel }
}

async function createOrResumeSession(gw: GatewayClient, args: ParsedArgs): Promise<{ sessionId: string; model: string | null }> {
  const cols = process.stdout.columns ?? 80
  const resumeId = flag(args, 'session')

  if (resumeId) {
    const raw = await gw.request<SessionResumeResponse>('session.resume', { session_id: resumeId, cols })
    const r = asRpcResult<SessionResumeResponse>(raw)
    if (!r?.session_id) {
      throw new Error(`failed to resume session ${resumeId}`)
    }
    return { sessionId: r.session_id, model: r.info?.model ?? null }
  }

  const raw = await gw.request<SessionCreateResponse>('session.create', { cols })
  const r = asRpcResult<SessionCreateResponse>(raw)
  if (!r?.session_id) {
    throw new Error('failed to create session')
  }
  return { sessionId: r.session_id, model: r.info?.model ?? null }
}

async function readAllStdin(): Promise<string> {
  const chunks: Buffer[] = []
  for await (const chunk of process.stdin) {
    chunks.push(chunk as Buffer)
  }
  return Buffer.concat(chunks).toString('utf8').trim()
}

export async function chatCommand(args: ParsedArgs): Promise<number> {
  const renderer = new CliRenderer({
    json: !!args.flags.json,
    verbose: !!args.flags.verbose,
    quiet: !!args.flags.quiet,
    noColor: !!args.flags['no-color']
  })

  process.stderr.write(`Connecting...\n`)

  let authed: AuthedRelay
  try {
    authed = await connectAndAuth(args)
  } catch (e) {
    process.stderr.write(`error: ${rpcErrorMessage(e)}\n`)
    return 1
  }
  const { relay, url, endpointRole: resolvedRole } = authed

  const gw = new GatewayClient(relay)

  // Wire desktop tool handlers (file read/write, shell, search) onto the
  // relay's `desktop` channel. Suppressed by `--no-tools`, and gated
  // behind a one-time consent prompt stored per-URL in remote-sessions.json.
  let toolRouter: DesktopToolRouter | null = null
  const toolsDisabled = !!args.flags['no-tools']
  if (!toolsDisabled) {
    const consent = await ensureToolsConsent(url)
    if (consent.consented) {
      toolRouter = new DesktopToolRouter({
        consentGranted: true,
        handlers: {
          desktop_read_file: readFileHandler,
          desktop_write_file: writeFileHandler,
          desktop_patch: patchHandler,
          desktop_terminal: terminalHandler,
          desktop_search_files: searchFilesHandler
        }
      })
      toolRouter.attach(relay)
      process.stderr.write(
        'Desktop tools: 5 handlers advertised (read_file, write_file, terminal, search_files, patch)\n'
      )
    } else if (consent.reason) {
      process.stderr.write(`Desktop tools: disabled (${consent.reason})\n`)
    }
  }

  // Single teardown path — detach the tool router first so in-flight tool
  // calls get "aborted" responses rather than blocking the gw.kill() close.
  // Previously this recursed into itself; now it does the right thing.
  const tearDown = () => {
    try {
      toolRouter?.detach()
    } catch {
      /* ignore */
    }
    try {
      gw.kill()
    } catch {
      /* ignore */
    }
  }

  setupGracefulExit({ cleanups: [tearDown] })

  gw.start()
  gw.drain()

  try {
    await waitForReady(gw)
  } catch (e) {
    process.stderr.write(`error: ${rpcErrorMessage(e)}\n`)
    tearDown()
    return 1
  }

  // Prefer the role we resolved from --pair-qr this invocation; fall back to
  // whatever was stored at pair time for older flows.
  const storedForBanner = await getSession(url)
  const bannerRole = resolvedRole ?? storedForBanner?.endpointRole ?? null
  process.stderr.write(
    buildConnectBanner({
      url,
      serverVersion: relay.serverVersion,
      meta: relay.authMeta,
      endpointRole: bannerRole
    }) + '\n'
  )

  let session: { sessionId: string; model: string | null }
  try {
    session = await createOrResumeSession(gw, args)
  } catch (e) {
    process.stderr.write(`error: ${rpcErrorMessage(e)}\n`)
    tearDown()
    return 1
  }

  if (session.model) {
    process.stderr.write(`Session ${session.sessionId.slice(0, 8)}… on ${session.model}\n`)
  }

  // Mode detection — one-shot vs piped vs REPL.

  const oneShotPrompt = args.positional.join(' ').trim()
  if (oneShotPrompt) {
    try {
      await runOneTurn(gw, session.sessionId, oneShotPrompt, renderer).promise
      tearDown()
      return 0
    } catch (e) {
      process.stderr.write(`\nerror: ${rpcErrorMessage(e)}\n`)
      tearDown()
      return 1
    }
  }

  if (!process.stdin.isTTY) {
    const piped = (await readAllStdin()).trim()
    if (!piped) {
      process.stderr.write('no input on stdin; exiting.\n')
      tearDown()
      return 0
    }
    try {
      await runOneTurn(gw, session.sessionId, piped, renderer).promise
      tearDown()
      return 0
    } catch (e) {
      process.stderr.write(`\nerror: ${rpcErrorMessage(e)}\n`)
      tearDown()
      return 1
    }
  }

  // REPL mode.
  process.stderr.write('\nType a message. Ctrl+C to interrupt a turn, /quit to exit.\n')

  const rl = createInterface({ input: process.stdin, output: process.stderr, terminal: true })

  // SIGINT target is the currently-running turn's handle. Keeping it per-turn
  // (instead of a shared mutable flag) means a late-arriving `error` event for
  // a cancelled turn can't be misread by the NEXT turn's handler — the flag
  // lives inside runOneTurn's closure and dies with the turn.
  let currentTurn: TurnHandle | null = null

  rl.on('SIGINT', () => {
    if (currentTurn) {
      currentTurn.cancel()
      process.stderr.write('\n[interrupted]\n')
    } else {
      process.stderr.write('\nbye\n')
      rl.close()
    }
  })

  let exitCode = 0
  while (true) {
    let line: string
    try {
      line = await rl.question('\n> ')
    } catch {
      break // readline closed
    }

    const trimmed = line.trim()
    if (!trimmed) {
      continue
    }
    if (trimmed === '/quit' || trimmed === '/exit' || trimmed === ':q') {
      break
    }

    const turn = runOneTurn(gw, session.sessionId, trimmed, renderer)
    currentTurn = turn
    try {
      await turn.promise
    } catch (e) {
      process.stderr.write(`\nerror: ${rpcErrorMessage(e)}\n`)
      exitCode = 1
    } finally {
      currentTurn = null
    }
  }

  rl.close()
  tearDown()
  return exitCode
}

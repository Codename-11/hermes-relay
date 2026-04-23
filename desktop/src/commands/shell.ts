// shell — pipe a PTY on the relay to the local terminal.
//
// Drives the existing `terminal` relay channel (docs/relay-protocol.md §3.4,
// already used by the Android TerminalViewModel). After the attach handshake
// we inject `clear; exec hermes\n` so the tmux-hosted login shell replaces
// itself with the full `hermes` CLI — banner, skin, session ID, all of it,
// verbatim, no re-rendering. Zero server changes.
//
// Why the post-attach `exec` dance and not a `shell: "hermes"` attach param?
// The relay's terminal channel spawns `tmux new-session -A` when tmux is
// present (always, on this deploy) and tmux always launches the user's
// default login shell — the `shell` attach field is stored for display
// only. `exec` replaces bash in-place so Ctrl+C / EOF map to hermes, not
// to an outer shell that would keep running after hermes exits.
//
// Wire contract (`plugin/relay/channels/terminal.py`, verified 2026-04-23):
//   attach (c→s)    { channel:'terminal', type:'terminal.attach',
//                     payload:{ cols, rows, session_name? } }
//   attached (s→c)  { channel:'terminal', type:'terminal.attached',
//                     payload:{ session_name, pid, shell, cols, rows,
//                               tmux_available, reattach } }
//   input  (c→s)    { channel:'terminal', type:'terminal.input',
//                     payload:{ session_name?, data: <utf8 string> } }
//   output (s→c)    { channel:'terminal', type:'terminal.output',
//                     payload:{ session_name, data: <utf8 string> } }
//     — output is batched ≤16ms / ≤4KB; raw ANSI embedded.
//   resize (c→s)    { channel:'terminal', type:'terminal.resize',
//                     payload:{ session_name?, cols, rows } }
//   detached (s→c)  { channel:'terminal', type:'terminal.detached',
//                     payload:{ session_name, reason } }
//   error    (s→c)  { channel:'terminal', type:'terminal.error',
//                     payload:{ message } }
//
// Client-side escape: Ctrl+A as a prefix (tmux-style). `Ctrl+A .` detaches
// (closes the WSS cleanly but preserves the tmux session on the server, so
// the next `hermes-relay shell` re-attaches to the same hermes instance).
// `Ctrl+A Ctrl+A` sends a literal Ctrl+A. `Ctrl+A k` kills the tmux session
// for real (destructive, bypasses tmux persistence). Anything else after
// Ctrl+A is swallowed with a one-line hint so the user isn't guessing.
// Ctrl+C is NOT intercepted — it passes through as byte 0x03 to interrupt
// whatever's running on the remote side, which is what the user expects.

import { buildConnectBanner } from '../banner.js'
import type { ParsedArgs } from '../cli.js'
import { resolveCredentials } from '../credentials.js'
import { setupGracefulExit } from '../lib/gracefulExit.js'
import { rpcErrorMessage } from '../lib/rpc.js'
import { resolveFirstRunUrl } from '../relayUrlPrompt.js'
import { deleteSession, getSession, saveSession } from '../remoteSessions.js'
import { ensureToolsConsent } from '../tools/consent.js'
import { readFileHandler, writeFileHandler, patchHandler } from '../tools/handlers/fs.js'
import { searchFilesHandler } from '../tools/handlers/search.js'
import { terminalHandler } from '../tools/handlers/terminal.js'
import { DesktopToolRouter } from '../tools/router.js'
import { RelayTransport } from '../transport/RelayTransport.js'

const ATTACH_TIMEOUT_MS = 30_000
const CTRL_A = 0x01
/** Time between `terminal.attached` and the auto-`exec` we inject. tmux
 * needs a beat to settle the new-session → login-shell prompt; if we
 * blast `exec hermes` in too fast, the shell sees it before the prompt
 * is drawn and bash eats the first keystroke of your subsequent input.
 * 350 ms is empirical — under 200 eats chars on a cold tmux session;
 * over 500 feels laggy. */
const EXEC_SETTLE_MS = 350

function resolveRemoteOrNull(args: ParsedArgs): string | null {
  const v = args.flags.remote
  const url = (typeof v === 'string' ? v : null) ?? process.env.HERMES_RELAY_URL ?? null
  return url ? url.trim() : null
}

interface AuthedRelay {
  relay: RelayTransport
  url: string
  endpointRole: string | null
}

async function connectAndAuth(args: ParsedArgs): Promise<AuthedRelay> {
  let urlFlag = resolveRemoteOrNull(args)
  const argCode = typeof args.flags.code === 'string' ? args.flags.code : undefined
  const argToken = typeof args.flags.token === 'string' ? args.flags.token : undefined
  const argPairQr =
    typeof args.flags['pair-qr'] === 'string'
      ? args.flags['pair-qr']
      : process.env.HERMES_RELAY_PAIR_QR
  const nonInteractive = !!args.flags['non-interactive']

  // First-run fallback: no --remote, no env var, no QR payload. Either
  // auto-pick a single stored session, pick from a list, or walk a brand-new
  // user through URL entry. resolveFirstRunUrl throws on non-interactive +
  // ambiguous. Once we have a URL, resolveCredentials handles the rest of
  // the chain (stored token → pairing-code prompt on TTY).
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
      deviceName: `hermes-relay-cli shell (${process.platform})`
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
      await deleteSession(url)
    }
    if (attempt === 1 || nonInteractive) {
      throw new Error(`relay rejected credentials: ${outcome.reason}`)
    }
    process.stderr.write(`\nRelay rejected credentials: ${outcome.reason}\n`)
  }
  throw new Error('unreachable: connectAndAuth exhausted loop')
}

interface AttachedInfo {
  sessionName: string
  pid?: number
  shell?: string
  tmuxAvailable?: boolean
  reattach?: boolean
}

/** Wait for the server's `terminal.attached` ack (or error) after we've
 * sent `terminal.attach`. Rejects on timeout, on `terminal.error`, or if
 * the transport tears down before the ack arrives. */
function waitForAttached(relay: RelayTransport): Promise<AttachedInfo> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      relay.onChannel('terminal', null)
      reject(new Error(`terminal.attached timeout after ${ATTACH_TIMEOUT_MS}ms`))
    }, ATTACH_TIMEOUT_MS)

    relay.onChannel('terminal', (type, payload) => {
      if (type === 'terminal.attached') {
        clearTimeout(timer)
        const sessionName = typeof payload.session_name === 'string' ? payload.session_name : ''
        if (!sessionName) {
          reject(new Error('terminal.attached ack missing session_name'))
          return
        }
        resolve({
          sessionName,
          pid: typeof payload.pid === 'number' ? payload.pid : undefined,
          shell: typeof payload.shell === 'string' ? payload.shell : undefined,
          tmuxAvailable: typeof payload.tmux_available === 'boolean' ? payload.tmux_available : undefined,
          reattach: typeof payload.reattach === 'boolean' ? payload.reattach : undefined
        })
        return
      }
      if (type === 'terminal.error') {
        clearTimeout(timer)
        const msg = typeof payload.message === 'string' ? payload.message : 'terminal error'
        reject(new Error(msg))
        return
      }
      // Drop any other envelopes that arrive before attached.
    })
  })
}

/** Feature-detect raw mode — a CI environment or a non-TTY stdin won't
 * have it. We need raw mode to forward every keystroke (including
 * modifiers and escape sequences) to the remote PTY. */
function canRawMode(): boolean {
  const anyStdin = process.stdin as NodeJS.ReadStream & { setRawMode?: (v: boolean) => void }
  return typeof anyStdin.setRawMode === 'function' && !!process.stdin.isTTY
}

export async function shellCommand(args: ParsedArgs): Promise<number> {
  const sessionNameArg = typeof args.flags.session === 'string' ? args.flags.session : undefined
  const raw = !!args.flags.raw
  const execOverride = typeof args.flags.exec === 'string' ? args.flags.exec : null
  // Default: exec the full hermes CLI after the tmux shell settles.
  // --raw disables this (plain bash/tmux). --exec overrides the command.
  const postAttachExec: string | null = raw ? null : (execOverride ?? 'hermes')

  if (!canRawMode()) {
    process.stderr.write(
      'error: `shell` requires an interactive TTY on stdin. Pipe-mode is not supported ' +
        '(use a proper terminal: Windows Terminal, iTerm, etc.).\n'
    )
    return 1
  }

  process.stderr.write(`Connecting...\n`)

  let authed: AuthedRelay
  try {
    authed = await connectAndAuth(args)
  } catch (e) {
    process.stderr.write(`error: ${rpcErrorMessage(e)}\n`)
    return 1
  }
  const { relay, url, endpointRole: resolvedRole } = authed

  // Prefer this-invocation's resolved role over the stored one (the QR might
  // have been probed onto a different endpoint than the last session).
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

  // Wire desktop tool handlers BEFORE the terminal attach hands the TTY
  // over to raw-mode PTY forwarding. The consent prompt (if shown) uses
  // readline on stdin/stderr and needs a cooked TTY.
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

  const cols = process.stdout.columns ?? 120
  const rows = process.stdout.rows ?? 30

  // Kick off the attach handshake. Listener is installed INSIDE waitForAttached
  // so we can't miss the ack — `terminal.attached` can arrive before the
  // promise resolves this microtask.
  const attachPromise = waitForAttached(relay)

  const attachPayload: Record<string, unknown> = { cols, rows }
  if (sessionNameArg) {
    attachPayload.session_name = sessionNameArg
  }
  relay.sendChannel('terminal', 'terminal.attach', attachPayload)

  let attached: AttachedInfo
  try {
    attached = await attachPromise
  } catch (e) {
    process.stderr.write(`error: ${rpcErrorMessage(e)}\n`)
    try {
      toolRouter?.detach()
    } catch {
      /* ignore */
    }
    relay.kill()
    return 1
  }

  // This session_name is the authoritative key for every subsequent outgoing
  // envelope. The server accepts omitted session_name (falls back to most-
  // recent), but echoing it makes the wire self-describing and survives a
  // future multi-session client.
  const sessionName = attached.sessionName

  const reattachMsg = attached.reattach ? ' — re-attached to existing session' : ''
  process.stderr.write(
    `Attached${attached.tmuxAvailable ? ` (tmux session "${sessionName}")` : ''}${reattachMsg}.\n` +
      `Escape: Ctrl+A then . (detach, preserves tmux) · Ctrl+A then k (kill tmux) · Ctrl+A Ctrl+A (literal Ctrl+A)\n\n`
  )

  // Swap handler from attach-waiter to steady-state output pump. Re-registering
  // replaces the previous listener, so `terminal.output` frames now flow to
  // stdout instead of the attach-waiter's resolve path (which already settled).
  let bytesReceived = 0
  let exiting = false
  relay.onChannel('terminal', (type, payload) => {
    if (type === 'terminal.output') {
      const data = typeof payload.data === 'string' ? payload.data : null
      if (data) {
        process.stdout.write(data)
        bytesReceived += data.length
      }
      return
    }
    if (type === 'terminal.error') {
      const msg = typeof payload.message === 'string' ? payload.message : 'terminal error'
      process.stderr.write(`\n\x1b[31m[terminal] error: ${msg}\x1b[0m\n`)
      return
    }
    if (type === 'terminal.detached') {
      // Server-side PTY ended (or was explicitly detached). Exit with code
      // depending on reason: user-initiated "client detach" / "client kill"
      // are clean exits; anything else is treated as abnormal teardown.
      const reason = typeof payload.reason === 'string' ? payload.reason : 'detached'
      const clean = reason === 'client detach' || reason === 'client kill' || reason === 'eof'
      if (!exiting) {
        exiting = true
        process.stderr.write(`\n\x1b[90m[shell] ${reason}\x1b[0m\n`)
        cleanup()
        process.exit(clean ? 0 : 1)
      }
      return
    }
    // Unknown types — ignore, don't pollute the terminal.
  })

  // Raw mode: every keystroke goes to the PTY as a byte. No local line
  // buffering, no local Ctrl+C interception (the PTY sees 0x03 and interrupts
  // whatever's running remotely, which is what the user expects).
  process.stdin.setRawMode(true)
  process.stdin.resume()

  let escapePending = false

  const sendInput = (data: string) => {
    relay.sendChannel('terminal', 'terminal.input', { session_name: sessionName, data })
  }

  const forwardInput = (chunk: Buffer) => {
    if (exiting) {
      return
    }
    // Escape filter — strip Ctrl+A verbs from the client-side byte stream.
    // All other bytes (including a literal Ctrl+A after Ctrl+A Ctrl+A) pass
    // through. We accumulate into a byte array and emit once per chunk so
    // sendInput fires with a coherent payload instead of one envelope per byte.
    const out: number[] = []
    for (let i = 0; i < chunk.length; i++) {
      const b = chunk[i]!
      if (escapePending) {
        escapePending = false
        if (b === 0x2e /* '.' */) {
          // Ctrl+A . → clean detach (tmux preserved on server)
          exiting = true
          relay.sendChannel('terminal', 'terminal.detach', { session_name: sessionName })
          process.stderr.write('\n\x1b[90m[shell] detached (tmux preserved)\x1b[0m\n')
          cleanup()
          process.exit(0)
        }
        if (b === 0x6b /* 'k' */) {
          // Ctrl+A k → destructive kill (tmux session destroyed)
          exiting = true
          relay.sendChannel('terminal', 'terminal.kill', { session_name: sessionName })
          process.stderr.write('\n\x1b[90m[shell] killed tmux session "' + sessionName + '"\x1b[0m\n')
          cleanup()
          process.exit(0)
        }
        if (b === CTRL_A) {
          // Ctrl+A Ctrl+A → forward a literal Ctrl+A
          out.push(CTRL_A)
          continue
        }
        process.stderr.write(
          `\n\x1b[90m[shell] escape: . detach · k kill · Ctrl+A literal\x1b[0m\n`
        )
        continue
      }
      if (b === CTRL_A) {
        escapePending = true
        continue
      }
      out.push(b)
    }
    if (out.length > 0) {
      sendInput(Buffer.from(out).toString('utf8'))
    }
  }

  process.stdin.on('data', forwardInput)

  // Forward SIGWINCH as a `terminal.resize` envelope. The relay TIOCSWINSZs
  // the master fd, so `hermes`'s Ink layer re-flows immediately.
  let lastCols = cols
  let lastRows = rows
  const onResize = () => {
    const c = process.stdout.columns ?? lastCols
    const r = process.stdout.rows ?? lastRows
    if (c === lastCols && r === lastRows) {
      return
    }
    lastCols = c
    lastRows = r
    relay.sendChannel('terminal', 'terminal.resize', { session_name: sessionName, cols: c, rows: r })
  }
  process.stdout.on('resize', onResize)

  const cleanup = () => {
    try {
      process.stdin.off('data', forwardInput)
    } catch {
      /* ignore */
    }
    try {
      process.stdout.off('resize', onResize)
    } catch {
      /* ignore */
    }
    try {
      process.stdin.setRawMode(false)
    } catch {
      /* ignore */
    }
    try {
      process.stdin.pause()
    } catch {
      /* ignore */
    }
    try {
      relay.onChannel('terminal', null)
    } catch {
      /* ignore */
    }
    try {
      toolRouter?.detach()
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

  relay.on('exit', () => {
    if (exiting) {
      return
    }
    exiting = true
    process.stderr.write(
      `\n\x1b[90m[shell] transport closed (received ${bytesReceived} bytes)\x1b[0m\n`
    )
    cleanup()
    process.exit(1)
  })

  // Auto-exec the target command (default: `hermes`) once tmux has settled.
  // On a fresh tmux attach the login shell takes ~250-350ms to paint its first
  // prompt; injecting `exec` too early means bash swallows the first keystroke
  // and the command never runs. Skipped entirely when --raw is set.
  if (postAttachExec) {
    setTimeout(() => {
      if (exiting) {
        return
      }
      // `clear` wipes the shell's welcome/prompt first so the hermes banner
      // starts from a clean viewport. `exec` replaces bash in place so Ctrl+C
      // and EOF signal hermes directly — no outer shell to catch them and
      // drop the user back to bash after hermes exits.
      sendInput(`clear; exec ${postAttachExec}\n`)
    }, EXEC_SETTLE_MS)
  }

  // Park — everything flows through listeners now. Resolve only when
  // cleanup() → process.exit() fires.
  return new Promise<number>(() => {
    /* never resolves directly; process.exit paths above */
  })
}

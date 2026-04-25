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
// Client-side escape: Ctrl+A as a prefix (tmux-style).
//   Ctrl+A .         clean detach (tmux preserved on server; next
//                    `hermes-relay shell` re-attaches to the same hermes)
//   Ctrl+A k         destructive kill (tmux session destroyed for real)
//   Ctrl+A v         read this machine's clipboard image, stage to the
//                    server's /clipboard/inbox, then type `/paste\r`
//                    into the PTY — cohesive in-session paste, no
//                    leaving tmux
//   Ctrl+A ?         re-print the chord-help banner (also Ctrl+A h)
//   Ctrl+A Ctrl+A    forward a literal Ctrl+A (for nested tmux)
// Anything else after Ctrl+A is swallowed with a one-line hint so the
// user isn't guessing. Ctrl+C is NOT intercepted — it passes through as
// byte 0x03 to interrupt whatever's running on the remote side, which is
// what the user expects.

import { buildConnectBanner } from '../banner.js'
import type { ParsedArgs } from '../cli.js'
import { resolveCredentials } from '../credentials.js'
import { GatewayClient } from '../gatewayClient.js'
import type { GatewayEvent } from '../gatewayTypes.js'
import { setupGracefulExit } from '../lib/gracefulExit.js'
import { rpcErrorMessage } from '../lib/rpc.js'
import { resolveFirstRunUrl } from '../relayUrlPrompt.js'
import { deleteSession, getSession, saveSession } from '../remoteSessions.js'
import { stageClipboardImageToInbox } from './paste.js'
import { fetchRecentSessions, pickSession } from '../sessionPicker.js'
import { ensureToolsConsent } from '../tools/consent.js'
import { clipboardReadHandler, clipboardWriteHandler } from '../tools/handlers/clipboard.js'
import { openInEditorHandler } from '../tools/handlers/editor.js'
import { readFileHandler, writeFileHandler, patchHandler } from '../tools/handlers/fs.js'
import { screenshotHandler } from '../tools/handlers/screenshot.js'
import { searchFilesHandler } from '../tools/handlers/search.js'
import { terminalHandler } from '../tools/handlers/terminal.js'
import { DesktopToolRouter } from '../tools/router.js'
import { RelayTransport } from '../transport/RelayTransport.js'

const ATTACH_TIMEOUT_MS = 30_000
const CTRL_A = 0x01

/** The chord-help banner shown once on attach AND on `Ctrl+A ?`. Kept as
 * a constant so both call sites can't drift. The `?` chord is the
 * "show me the bindings again" mnemonic — useful when the attach banner
 * has scrolled off-screen, or when you're on a fresh tmux pane and
 * forgot the verb list. */
const CHORD_HELP =
  'Escape: Ctrl+A then . (detach, preserves tmux) · ' +
  'Ctrl+A then k (kill tmux) · ' +
  'Ctrl+A then v (paste clipboard image → /paste) · ' +
  'Ctrl+A then ? (show this help) · ' +
  'Ctrl+A Ctrl+A (literal Ctrl+A)'
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

const READY_TIMEOUT_MS = 60_000

/** Same waitForReady helper used by chat.ts — the tui_gateway emits
 * `gateway.ready` once it's past startup and able to serve `session.list`.
 * We need to wait for it before fetching recent sessions, otherwise the
 * RPC will race and either time out or return stale data. */
function waitForGatewayReady(gw: GatewayClient): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      gw.off('event', handler)
      reject(new Error(`gateway.ready timeout after ${READY_TIMEOUT_MS}ms`))
    }, READY_TIMEOUT_MS)

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

/** Resolve the hermes conversation id to resume inside tmux, or null for
 * a fresh session. Precedence:
 *   1. --conversation <id>   explicit flag, skips the picker
 *   2. --new                 explicit fresh, skips the picker
 *   3. picker                prompts when ≥1 recent session exists on a TTY
 *   4. null                  no pick / non-interactive / empty → fresh
 *
 * NOTE: `--session <name>` on shell is the **tmux** session name, not the
 * hermes conversation id. We deliberately don't reuse it — the two are
 * different concepts and conflating them made `hermes-relay shell
 * --session foo` ambiguous. See README for the flag split. */
async function resolveHermesConversationId(
  relay: import('../transport/RelayTransport.js').RelayTransport,
  args: ParsedArgs,
  url: string
): Promise<string | null | 'cancel'> {
  const convArg = typeof args.flags.conversation === 'string' ? args.flags.conversation : null
  if (convArg) {
    return convArg
  }
  if (args.flags.new) {
    return null
  }

  // Picker path. Spin up a short-lived GatewayClient over the existing
  // authed relay, wait for gateway.ready, RPC `session.list`, render the
  // picker, tear the gw down (the terminal channel below doesn't use the
  // tui wrapper — the same relay socket keeps running).
  const gw = new GatewayClient(relay)
  gw.start()
  gw.drain()
  try {
    await waitForGatewayReady(gw)
  } catch {
    // If gateway.ready never comes (server without tui_gateway, stripped
    // build, etc.) we silently fall through to a fresh session — the
    // picker is a nice-to-have, not a hard dependency.
    return null
  }

  const nonInteractive = !!args.flags['non-interactive']
  const recent = await fetchRecentSessions(gw, { limit: 10 })
  const pick = await pickSession(recent, { nonInteractive, serverLabel: url })
  // We intentionally do NOT gw.kill() here — that would tear down the WSS
  // socket we're about to attach the terminal channel to. GatewayClient is
  // a thin re-emitter around the same relay, so dropping our reference is
  // enough. The `tui.rpc.*` subscription stays idle for the rest of the
  // shell session; it's harmless.

  if (pick === 'cancel') {
    return 'cancel'
  }
  if (pick === 'new') {
    return null
  }
  return pick
}

export async function shellCommand(args: ParsedArgs): Promise<number> {
  const sessionNameArg = typeof args.flags.session === 'string' ? args.flags.session : undefined
  const raw = !!args.flags.raw
  const execOverride = typeof args.flags.exec === 'string' ? args.flags.exec : null
  // Default: exec the full hermes CLI after the tmux shell settles.
  // --raw disables this (plain bash/tmux). --exec overrides the command.
  // The picker (below, post-auth) may append `--resume <id>` to this so
  // tmux launches `hermes --resume <id>` instead of a fresh `hermes`.
  let postAttachExec: string | null = raw ? null : (execOverride ?? 'hermes')

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

  // Conversation picker — runs on a TTY when the user didn't pass
  // --conversation / --new, and the default exec is still `hermes` (so
  // we're actually about to launch the CLI, not a custom --exec target).
  // We append `--resume <id>` to the exec command when the user picks
  // one; 'cancel' exits cleanly; 'new' leaves the exec command alone.
  // --raw / --exec-override / --new all skip the picker entirely.
  const skipPicker = raw || execOverride !== null || !!args.flags.new
  if (!skipPicker) {
    const picked = await resolveHermesConversationId(relay, args, url)
    if (picked === 'cancel') {
      process.stderr.write('bye\n')
      try {
        relay.kill()
      } catch {
        /* ignore */
      }
      return 0
    }
    if (picked && postAttachExec === 'hermes') {
      // Shell-quote defensively — session ids are typically [a-z0-9-] but
      // we can't assume, and the tmux shell will bash-eval the exec line.
      const safeId = picked.replace(/'/g, "'\\''")
      postAttachExec = `hermes --resume '${safeId}'`
      process.stderr.write(`Resuming conversation ${picked.slice(0, 8)}…\n`)
    }
  }

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
          desktop_search_files: searchFilesHandler,
          desktop_clipboard_read: clipboardReadHandler,
          desktop_clipboard_write: clipboardWriteHandler,
          desktop_screenshot: screenshotHandler,
          desktop_open_in_editor: openInEditorHandler
        }
      })
      toolRouter.attach(relay)
      process.stderr.write(
        'Desktop tools: 9 handlers advertised (read_file, write_file, terminal, search_files, patch, clipboard_read, clipboard_write, screenshot, open_in_editor)\n'
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
      `${CHORD_HELP}\n\n`
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
  /** Reentrancy guard for `Ctrl+A v` so a fast double-press doesn't race two
   * staging requests at once (the server'd then attach two images on /paste). */
  let pasteInFlight = false

  const sendInput = (data: string) => {
    relay.sendChannel('terminal', 'terminal.input', { session_name: sessionName, data })
  }

  /** Ctrl+A v handler: read this machine's clipboard image, ship it to the
   * relay's /clipboard/inbox, then type `/paste\r` into the PTY so the
   * upstream Hermes TUI's /paste handler consumes the inbox file in the
   * same flow the user would type by hand. Status line goes to stderr so
   * it doesn't pollute the PTY stream. */
  const pasteFromClipboardChord = async () => {
    if (pasteInFlight) {
      process.stderr.write('\n\x1b[90m[shell] paste already in flight — ignoring\x1b[0m\n')
      return
    }
    pasteInFlight = true
    try {
      const rec = await getSession(url)
      if (!rec) {
        process.stderr.write(
          '\n\x1b[90m[shell] paste: no stored session for ' + url + '\x1b[0m\n'
        )
        return
      }
      const result = await stageClipboardImageToInbox(url, rec.token)
      if (!result.ok) {
        const what = result.noImage ? 'no image on clipboard' : (result.error ?? 'unknown error')
        process.stderr.write(`\n\x1b[90m[shell] paste: ${what}\x1b[0m\n`)
        return
      }
      // Stage succeeded — type /paste\r into the PTY so the TUI consumes it.
      // Carriage-return alone (\r) is the standard "submit" key; \n would
      // emit a literal newline in some TUIs.
      sendInput('/paste\r')
      process.stderr.write(
        `\n\x1b[90m[shell] pasted ${result.dims} (${result.sizeKb} KB) → /paste\x1b[0m\n`
      )
    } finally {
      pasteInFlight = false
    }
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
        if (b === 0x76 /* 'v' */) {
          // Ctrl+A v → read this machine's clipboard image, stage to the
          // server's inbox, then type `/paste\r` into the PTY so the TUI
          // consumes it. Async — fire and forget; the in-flight guard
          // inside pasteFromClipboardChord prevents double-staging on a
          // fast repeat press. No need to flush `out` first because Ctrl+A
          // already consumed; nothing buffered for this byte.
          void pasteFromClipboardChord()
          continue
        }
        if (b === 0x3f /* '?' */ || b === 0x68 /* 'h' */) {
          // Ctrl+A ? (or Ctrl+A h) → re-print the chord-help banner.
          // Useful when the attach banner has scrolled off and the user
          // forgets the verb list mid-session. Goes to stderr (not the
          // PTY) so it doesn't appear inside the TUI's input buffer.
          process.stderr.write(`\n\x1b[90m${CHORD_HELP}\x1b[0m\n`)
          continue
        }
        process.stderr.write(
          `\n\x1b[90m[shell] escape: . detach · k kill · v paste · ? help · Ctrl+A literal\x1b[0m\n`
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

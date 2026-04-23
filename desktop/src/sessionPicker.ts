// sessionPicker — "resume where you left off" prompt before shell/chat.
//
// Rendered after the relay authenticates and `gateway.ready` fires but BEFORE
// the REPL loop / PTY attach takes over. Reads the remote agent's recent
// sessions via the tui_gateway's `session.list` RPC (same call the upstream
// TUI uses in ui-tui/src/components/sessionPicker.tsx), renders a numbered
// list on stderr with age + first-prompt preview, and returns the picked
// session id (or the literal string `'new'` / `'cancel'`).
//
// Design choices:
//  - readline on stderr — doesn't fight the chat renderer that writes to
//    stdout, and leaves a clean byte stream for piped callers.
//  - 3 input retries before giving up → 'cancel'. Invalid input just nags.
//  - Non-interactive mode returns 'new' silently so piped / scripted
//    invocations never block on a prompt.
//  - 404 / "method not found" from the RPC returns [] gracefully — servers
//    built before session.list shipped just fall through to a fresh start
//    with no error to the user.
//  - NO_COLOR + !isTTY suppress ANSI — the picker UI is plain ASCII by
//    default and only dims the hint line when color is available.

import { createInterface } from 'node:readline/promises'

import type { GatewayClient } from './gatewayClient.js'
import type { SessionListItem, SessionListResponse } from './gatewayTypes.js'
import { asRpcResult } from './lib/rpc.js'

/** The shape exposed to callers — narrowed from SessionListItem so the picker
 * module owns its public surface and any server-side shape drift stays
 * contained. The `title` column shows the first user prompt (truncated to
 * 80 chars) or `undefined` when empty. */
export interface SessionSummary {
  id: string
  title?: string
  /** Epoch **milliseconds** of the most recent activity. Converted from the
   * gateway's epoch-seconds `started_at` so callers don't have to do the
   * multiply at every display site. */
  last_activity_ts: number
  message_count?: number
  model?: string
}

const DEFAULT_LIMIT = 10
const MAX_TITLE_LEN = 80
const MAX_INVALID_ATTEMPTS = 3

/** Fetch the most-recent sessions from the remote tui_gateway. Returns `[]`
 * on any failure (404/method-not-found, invalid shape, network) so the
 * caller can fall through to a fresh session without surfacing noise.
 * `gw` must already be past `gateway.ready` — call this *after* waitForReady
 * in the owning command. */
export async function fetchRecentSessions(
  gw: GatewayClient,
  opts: { limit?: number } = {}
): Promise<SessionSummary[]> {
  const limit = opts.limit ?? DEFAULT_LIMIT
  try {
    const raw = await gw.request<SessionListResponse>('session.list', { limit })
    const parsed = asRpcResult<SessionListResponse>(raw)
    const items: SessionListItem[] = parsed?.sessions ?? []

    return items.slice(0, limit).map(toSummary)
  } catch (e) {
    // RPC errors are swallowed — the server may not support session.list
    // (pre-fork, or a stripped-down gateway). Surface is "no picker, fresh
    // start" not a hard failure.
    const msg = e instanceof Error ? e.message.toLowerCase() : ''
    if (
      msg.includes('method not found') ||
      msg.includes('unknown method') ||
      msg.includes('404') ||
      msg.includes('not implemented')
    ) {
      return []
    }
    // Unexpected errors — still return [] (picker gracefully becomes a no-op)
    // but log to stderr if the caller wants diagnostics via HERMES_DEBUG.
    if (process.env.HERMES_DEBUG) {
      process.stderr.write(
        `[sessionPicker] session.list failed: ${e instanceof Error ? e.message : String(e)}\n`
      )
    }
    return []
  }
}

function toSummary(item: SessionListItem): SessionSummary {
  // The gateway gives us the first user prompt either verbatim (`preview`)
  // or a curated summary (`title`). Prefer title when non-empty, fall back
  // to preview, truncate either to 80 chars with an ellipsis.
  const rawTitle =
    (item.title && item.title.trim()) ||
    (item.preview && item.preview.trim()) ||
    ''
  const title =
    rawTitle.length === 0
      ? undefined
      : rawTitle.length > MAX_TITLE_LEN
        ? rawTitle.slice(0, MAX_TITLE_LEN - 1).trimEnd() + '…'
        : rawTitle

  const summary: SessionSummary = {
    id: item.id,
    // started_at is epoch seconds on the wire; we expose ms so display
    // code can use Date.now() arithmetic without a unit-mismatch trap.
    last_activity_ts: Math.round(item.started_at * 1000)
  }
  if (title !== undefined) {
    summary.title = title
  }
  if (typeof item.message_count === 'number') {
    summary.message_count = item.message_count
  }
  // SessionListItem doesn't carry `model` today — leave undefined. If the
  // server shape grows a model field later, we can read it here.
  return summary
}

/** Format a ms timestamp as "5 min ago" / "2 h ago" / "1 d ago". Right-pads
 * to 10 chars so the column lines up against the title regardless of unit. */
function formatAge(tsMs: number): string {
  const nowMs = Date.now()
  const deltaSec = Math.max(0, Math.floor((nowMs - tsMs) / 1000))

  let label: string
  if (deltaSec < 60) {
    label = `${deltaSec} sec ago`
  } else if (deltaSec < 3600) {
    label = `${Math.floor(deltaSec / 60)} min ago`
  } else if (deltaSec < 86_400) {
    label = `${Math.floor(deltaSec / 3600)} h ago`
  } else if (deltaSec < 30 * 86_400) {
    label = `${Math.floor(deltaSec / 86_400)} d ago`
  } else {
    label = `${Math.floor(deltaSec / (30 * 86_400))} mo ago`
  }
  return label.padEnd(10, ' ')
}

/** True when the caller's terminal can render ANSI without corrupting a
 * piped consumer. Mirrors the renderer's noColor precedence. */
function canUseColor(): boolean {
  if (process.env.NO_COLOR) {
    return false
  }
  // stderr is our render target — check its TTY-ness not stdout's. A user
  // running `hermes-relay | jq` has stdout=pipe but stderr=TTY and should
  // still see the picker in color.
  return !!(process.stderr as NodeJS.WriteStream).isTTY
}

const DIM = (useColor: boolean, s: string) =>
  useColor ? `\x1b[2m${s}\x1b[0m` : s

const BOLD = (useColor: boolean, s: string) =>
  useColor ? `\x1b[1m${s}\x1b[0m` : s

export type PickResult = string | 'new' | 'cancel'

/** Render the numbered picker on stderr and return the user's selection.
 *
 * Non-interactive mode (`nonInteractive=true`, or `!process.stdin.isTTY`)
 * returns `'new'` silently — the caller then falls through to session
 * creation without blocking a pipeline.
 *
 * Invalid input (not a digit in range, not 'n', not 'q') prompts again up
 * to 3 times, then returns `'cancel'` so a mashed keyboard doesn't trap
 * the user in a loop.
 *
 * The `serverLabel` in the header is not part of the interface — callers
 * pass the URL they connected to via the closure, so this stays pure. */
export async function pickSession(
  sessions: SessionSummary[],
  opts: {
    nonInteractive: boolean
    /** Optional header line like "ws://host:port" — rendered if provided. */
    serverLabel?: string
  }
): Promise<PickResult> {
  // Nothing to pick from → fall straight through. This isn't an error
  // condition; it's just "brand new machine, no history yet".
  if (sessions.length === 0) {
    return 'new'
  }

  // Scripted / non-interactive callers can't answer a prompt. Silently
  // pick 'new' so `echo "hi" | hermes-relay chat` doesn't hang on a
  // readline that has no TTY to read from. Callers that truly want to
  // force a resume must pass --conversation <id>.
  if (opts.nonInteractive || !process.stdin.isTTY) {
    return 'new'
  }

  const useColor = canUseColor()
  const out = process.stderr

  // Header. Match the style of connectBanner — plain ASCII, single blank
  // line before the list. Keeps the picker visually separate from the
  // connect line above it.
  out.write('\n')
  if (opts.serverLabel) {
    out.write(
      BOLD(useColor, `Recent sessions on ${opts.serverLabel}`) + '\n\n'
    )
  } else {
    out.write(BOLD(useColor, 'Recent sessions') + '\n\n')
  }

  // Numbered list. Max 9 entries get single-digit shortcuts; anything
  // beyond that requires typing the full number. 10 is the advertised
  // default limit so in practice we stay single-digit. Pad the index so
  // "  1." and " 10." line up.
  const maxIdxWidth = String(sessions.length).length
  for (let i = 0; i < sessions.length; i++) {
    const s = sessions[i]!
    const idx = String(i + 1).padStart(maxIdxWidth, ' ')
    const age = formatAge(s.last_activity_ts)
    const title = s.title ?? '(untitled)'
    // "  — " separator is intentional em-dash to match the connect banner.
    out.write(`  ${idx}. ${age} — "${title}"\n`)
  }
  out.write(`  ${'n'.padStart(maxIdxWidth, ' ')}. Start a new conversation\n`)
  out.write(`  ${'q'.padStart(maxIdxWidth, ' ')}. Quit\n\n`)

  const rangeHint =
    sessions.length === 1 ? '1' : `1-${sessions.length}`
  const promptText = DIM(useColor, `Pick [${rangeHint}/n/q] > `)

  // readline on stderr — same pattern as chat.ts's REPL prompt, keeps
  // stdout clean for the renderer.
  const rl = createInterface({
    input: process.stdin,
    output: process.stderr,
    terminal: true
  })

  try {
    for (let attempt = 0; attempt < MAX_INVALID_ATTEMPTS; attempt++) {
      const line = (await rl.question(promptText)).trim().toLowerCase()
      if (line === '') {
        // Empty line = same as 'n' → fresh start. Matches the "hit enter
        // to proceed" muscle memory of most installers.
        return 'new'
      }
      if (line === 'q' || line === 'quit' || line === 'exit' || line === ':q') {
        return 'cancel'
      }
      if (line === 'n' || line === 'new') {
        return 'new'
      }
      const n = parseInt(line, 10)
      if (Number.isFinite(n) && n >= 1 && n <= sessions.length) {
        return sessions[n - 1]!.id
      }
      out.write(
        DIM(useColor, `(invalid — try ${rangeHint} to pick, n for new, q to quit)`) + '\n'
      )
    }
    out.write(DIM(useColor, '(too many invalid tries — cancelled)') + '\n')
    return 'cancel'
  } finally {
    rl.close()
  }
}

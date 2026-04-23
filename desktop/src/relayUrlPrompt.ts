// First-run relay-URL prompt. Used by `pair` (when --remote is absent and the
// user hasn't stored any sessions yet) and by the bare-invocation fallback in
// chat/shell/tools (when a new user types `hermes-relay` on a fresh machine).
//
// Why a separate module from pairing.ts: the pairing-code prompt is called
// AFTER we already know the URL (it needs the URL for the retry message and
// for `getSession()` lookups). This prompt runs BEFORE the URL is known, so
// sharing a readline instance across the two would mean holding stderr
// bracketed-paste state open across an authentication boundary — clunky and
// prone to leaking escape sequences on error paths.
//
// Validation: ws:// or wss:// scheme followed by at least one non-whitespace
// char. We intentionally don't do stricter host/port parsing here — the
// transport layer's TLS probe will catch malformed URLs with a clearer error
// ("TOFU probe failed: ENOTFOUND host") than a regex rejection would.
//
// Output discipline: every prompt line goes to stderr, not stdout. Keeps
// `hermes-relay "hi" > out.txt` clean — only the assistant's reply lands in
// out.txt, prompts + spinner stream past to the user's terminal.

import { createInterface } from 'node:readline/promises'

import { listSessions } from './remoteSessions.js'

const URL_RE = /^wss?:\/\/\S+$/

export const isValidRelayUrl = (raw: string): boolean => URL_RE.test(raw.trim())

export interface PromptRelayUrlOptions {
  /** Max attempts before we give up. Default 3. */
  maxAttempts?: number
  /** First-line banner. Default: "Relay URL (ws:// or wss://)". */
  banner?: string
}

/**
 * Prompt the user for a relay WSS URL. Reads stdin, writes to stderr.
 * Throws on no-TTY, EOF, or exhausted attempts — callers decide whether to
 * exit(1) or fall back.
 *
 * stdin.isTTY is the one check we can't skip: a piped invocation
 * (`echo url | hermes-relay pair`) sets isTTY=false, which must fail closed
 * here — we don't accept pairing URLs from pipes (too easy to smuggle in via
 * a malicious `curl | sh` one-liner). The non-interactive branch has a more
 * actionable error message pointing back at --remote.
 */
export async function promptForRelayUrl(
  opts: PromptRelayUrlOptions = {}
): Promise<string> {
  if (!process.stdin.isTTY) {
    throw new Error(
      'No TTY for interactive URL entry. Pass --remote ws://host:port, ' +
        'set HERMES_RELAY_URL, or use --pair-qr with a full QR payload.'
    )
  }

  const rl = createInterface({
    input: process.stdin,
    output: process.stderr,
    terminal: true
  })

  try {
    const banner = opts.banner ?? 'Relay URL (ws:// or wss://)'
    process.stderr.write(`\n${banner}\n`)

    const maxAttempts = opts.maxAttempts ?? 3

    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      const raw = await rl.question('URL: ')
      const trimmed = raw.trim()

      if (!trimmed) {
        process.stderr.write('  (empty — try again)\n')
        continue
      }

      if (isValidRelayUrl(trimmed)) {
        return trimmed
      }

      process.stderr.write(
        `  invalid — need ws://host:port or wss://host:port. ` +
          `Got "${trimmed.slice(0, 60)}${trimmed.length > 60 ? '…' : ''}".\n`
      )
    }

    throw new Error(`no valid relay URL after ${maxAttempts} attempts`)
  } finally {
    rl.close()
  }
}

export interface ResolveFirstRunUrlOptions {
  /** Refuse to prompt even on TTY — used by `--non-interactive`. */
  nonInteractive?: boolean
  /** Banner shown before the URL prompt. Customize per-command. */
  banner?: string
}

/**
 * When neither --remote nor HERMES_RELAY_URL nor --pair-qr is set, figure out
 * what URL the user wants to talk to:
 *
 *   1. If `~/.hermes/remote-sessions.json` has exactly one entry → return it
 *      and note the pick to stderr (zero-friction re-use).
 *   2. If it has multiple entries → show a numbered list, let the user pick
 *      or type "n" to enter a new URL.
 *   3. If it has zero entries → print the first-run banner and prompt for
 *      a URL directly.
 *
 * Non-interactive callers (daemon, CI scripts with --non-interactive) never
 * reach the prompts — they hit the case (1) fast-path if there's a single
 * stored session, otherwise throw. This keeps scripted invocations deterministic.
 */
export async function resolveFirstRunUrl(
  opts: ResolveFirstRunUrlOptions = {}
): Promise<string> {
  const sessions = await listSessions()
  const urls = Object.keys(sessions)

  // Case (1): exactly one stored session — auto-pick. Both interactive and
  // non-interactive callers benefit (it's the happy path for repeat users).
  if (urls.length === 1) {
    const url = urls[0]!
    process.stderr.write(`Using stored session for ${url}\n`)
    return url
  }

  // From here down we need a TTY. Fail fast for scripted callers so they
  // don't hang on stdin.
  if (opts.nonInteractive) {
    if (urls.length === 0) {
      throw new Error(
        'no relay URL and no stored sessions. Pass --remote ws://host:port or ' +
          're-run interactively to be prompted.'
      )
    }
    throw new Error(
      `no relay URL and ${urls.length} stored sessions. Pass --remote to pick one, ` +
        'or re-run interactively.'
    )
  }

  if (!process.stdin.isTTY) {
    throw new Error(
      'no relay URL and no TTY. Pass --remote ws://host:port, set HERMES_RELAY_URL, ' +
        'or use --pair-qr.'
    )
  }

  // Case (3): zero stored sessions — first-run path. Show a welcoming banner
  // before the URL prompt so a brand-new user knows they're in the right place.
  if (urls.length === 0) {
    const banner =
      opts.banner ??
      "Welcome to hermes-relay. No stored sessions yet — let's pair with a relay server."
    process.stderr.write(`\n${banner}\n`)
    return promptForRelayUrl()
  }

  // Case (2): multiple stored sessions — numbered picker with "n" for new URL.
  process.stderr.write('\nStored sessions:\n')
  urls.forEach((u, i) => {
    process.stderr.write(`  ${i + 1}. ${u}\n`)
  })
  process.stderr.write(`  n. (new relay URL)\n`)

  const rl = createInterface({
    input: process.stdin,
    output: process.stderr,
    terminal: true
  })

  try {
    for (let attempt = 0; attempt < 3; attempt++) {
      const raw = (await rl.question(`Pick 1-${urls.length} or n: `)).trim().toLowerCase()

      if (raw === 'n' || raw === 'new') {
        rl.close()
        return promptForRelayUrl({ banner: 'New relay URL (ws:// or wss://)' })
      }

      const idx = Number.parseInt(raw, 10)
      if (Number.isInteger(idx) && idx >= 1 && idx <= urls.length) {
        return urls[idx - 1]!
      }

      process.stderr.write(`  invalid — type 1-${urls.length} or n.\n`)
    }

    throw new Error('no valid selection after 3 attempts')
  } finally {
    rl.close()
  }
}

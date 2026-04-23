// Headless (readline) pairing-code prompt. Mirrors the TUI's Ink prompt in
// validation + retry semantics, differs only in the rendering substrate —
// the TUI uses Ink, we use `node:readline/promises`. Keep the regex + clean
// rules identical across both so a user who pastes a code into either
// surface sees the same feedback.

import { createInterface } from 'node:readline/promises'

import { decodePairingPayload, type PairingPayload } from './pairingQr.js'

const CODE_RE = /^[A-Z0-9]{6}$/

// Bracketed-paste mode control sequences. Windows Terminal / iTerm2 / etc.
// wrap pasted content in `\x1b[200~...\x1b[201~` so apps can distinguish
// typed vs. pasted input. `readline` in terminal mode captures those markers
// into the input string, which mangles pasted pairing codes — the digits
// `200` / `201` survive our alphanumeric filter and end up in the "cleaned"
// code. We disable bracketed paste for the duration of the prompt and
// re-enable on exit; belt-and-suspenders by also stripping escape
// sequences inside cleanCode in case a terminal refuses to honor the flag.
const DISABLE_BRACKETED_PASTE = '\x1b[?2004l'
const ENABLE_BRACKETED_PASTE = '\x1b[?2004h'

/** Strip ANSI escape sequences (CSI/OSC) and stray control chars. Keeps
 * tab, LF, CR; drops everything else below 0x20 plus DEL (0x7f). */
const stripAnsiAndControls = (s: string): string =>
  s
    // CSI: ESC [ ...params... finalByte (`~` included for bracketed paste)
    .replaceAll(/\x1b\[[?\d;]*[a-zA-Z~]/g, '')
    // OSC: ESC ] ...; ...BEL or ESC \
    .replaceAll(/\x1b\][^\x07]*(?:\x07|\x1b\\)/g, '')
    // Bare ESC or leftover control chars (not TAB / LF / CR)
    .replaceAll(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g, '')

/** Strip non-[A-Z0-9] and clamp to 6 chars. Defensively strips ANSI + control
 * bytes first so a bracketed-paste-wrapped input doesn't smuggle the `200`
 * from `\x1b[200~` into the final code. Idempotent. */
export const cleanCode = (raw: string): string =>
  stripAnsiAndControls(raw).toUpperCase().replaceAll(/[^A-Z0-9]/g, '').slice(0, 6)

export const isValidCode = (raw: string): boolean => CODE_RE.test(raw)

export interface PromptOptions {
  retryReason?: string
  /** Max attempts before we give up. Default 3. */
  maxAttempts?: number
}

/**
 * Prompt the user for a 6-char pairing code. Reads from stdin, writes to
 * stderr so that piping `hermes-relay chat "prompt" > out.txt` still shows
 * the prompt.
 *
 * Throws on EOF, no-TTY, or exhausted attempts. Callers decide whether to
 * exit(1) or fall through to a different credential source.
 */
export async function promptForPairingCode(
  relayUrl: string,
  opts: PromptOptions = {}
): Promise<string> {
  if (!process.stdin.isTTY) {
    throw new Error(
      'No TTY for interactive pairing. Pass --code <CODE>, set HERMES_RELAY_CODE, or ' +
        'run `hermes-relay pair <CODE>` first to store a session token.'
    )
  }

  // Disable bracketed paste BEFORE readline opens so pasted content arrives
  // as plain keystrokes. Restore on exit regardless of which branch returns
  // (even if the user Ctrl+Cs out of the prompt).
  process.stderr.write(DISABLE_BRACKETED_PASTE)

  const rl = createInterface({
    input: process.stdin,
    output: process.stderr,
    terminal: true
  })

  try {
    if (opts.retryReason) {
      process.stderr.write(`\n${opts.retryReason}\n`)
    } else {
      process.stderr.write(`\nRelay: ${relayUrl}\n`)
      process.stderr.write(
        'Need a pairing code — run `/hermes-relay-pair` (or `hermes-pair`) on the relay host.\n'
      )
      process.stderr.write(
        '(Paste works; cleaned code shown before submit.)\n\n'
      )
    }

    const maxAttempts = opts.maxAttempts ?? 3

    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      const raw = await rl.question('Pairing code (6 chars): ')
      const cleaned = cleanCode(raw)

      if (isValidCode(cleaned)) {
        // Echo the cleaned code back so the user can sanity-check before
        // we commit to the handshake. Especially useful if the terminal
        // ignored our bracketed-paste disable and escape markers slipped in.
        process.stderr.write(`  → using code: ${cleaned}\n`)
        return cleaned
      }

      process.stderr.write(
        `  invalid — need 6 chars of A-Z or 0-9. ` +
          `Got "${raw.trim().slice(0, 40)}${raw.trim().length > 40 ? '…' : ''}" ` +
          `(${cleaned.length} valid chars after strip). Try typing it manually.\n`
      )
    }

    throw new Error(`no valid pairing code after ${maxAttempts} attempts`)
  } finally {
    rl.close()
    process.stderr.write(ENABLE_BRACKETED_PASTE)
  }
}

/**
 * Defensive wrapper around `decodePairingPayload` that returns a discriminated
 * union instead of throwing. Use this from flag parsers / help surfaces
 * where a friendly "bad QR, paste again" message is more useful than a
 * stack trace.
 *
 * The success branch exposes the parsed payload for callers that want to
 * peek at `hermes` version or `endpoints` before committing to the full
 * probe-and-pair flow.
 */
export type ValidatePairingResult =
  | { ok: true; payload: PairingPayload }
  | { ok: false; reason: string }

export function validatePairingPayloadString(raw: string): ValidatePairingResult {
  try {
    const payload = decodePairingPayload(raw)
    return { ok: true, payload }
  } catch (err) {
    const reason = err instanceof Error ? err.message : String(err)
    return { ok: false, reason }
  }
}

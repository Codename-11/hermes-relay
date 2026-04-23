// One-time consent prompt for desktop tool exposure.
//
// The tool handlers run IN-PROCESS with full filesystem + shell access.
// A compromised relay could ask us to `rm -rf /`. To keep the blast radius
// bounded, we gate the router behind an explicit per-URL consent toggle
// stored in `~/.hermes/remote-sessions.json`.
//
// Flow (driven from chat.ts / shell.ts):
//   1. Check stored `toolsConsented` for this URL.
//   2. If granted → return true, wire the router silently.
//   3. If missing + TTY stdin → print prompt, read "yes" / anything-else.
//      Persist grant on "yes"; return false otherwise (tools not wired).
//   4. If missing + non-TTY stdin → fail closed. Stderr error telling
//      the user to rerun with --no-tools or a TTY to consent.

import { createInterface } from 'node:readline/promises'

import { getSession, saveSession } from '../remoteSessions.js'

export const CONSENT_PROMPT = `
Desktop tools are about to be exposed to the remote Hermes agent.
The agent can read/write files, run shell commands, and search your filesystem.
This is AGENT-CONTROLLED access. Only use with trusted Hermes installs.
Type 'yes' to enable, or rerun with --no-tools to disable.
`

export interface EnsureConsentResult {
  consented: boolean
  /** Reason the router was not wired. Only set when consented=false. */
  reason?: string
}

/** Check stored consent for `url`, prompt the user if missing, persist
 * the decision. Never throws — persistence failures fall through as
 * "consented this session only". */
export async function ensureToolsConsent(url: string): Promise<EnsureConsentResult> {
  const existing = await getSession(url)
  if (existing?.toolsConsented === true) {
    return { consented: true }
  }

  // Must have a TTY on stdin AND stderr to render the prompt — writing to
  // stderr avoids stepping on piped-JSON stdout in one-shot chat mode.
  if (!process.stdin.isTTY) {
    return {
      consented: false,
      reason:
        'desktop tools require one-time consent; rerun on an interactive TTY, or pass --no-tools to skip'
    }
  }

  // Same bracketed-paste suppression as the pairing-code prompt — keeps
  // `yes`/`no` clean on Windows Terminal (ConPTY otherwise wraps pasted
  // content in `\x1b[200~...\x1b[201~` escape markers that readline
  // captures into the input string).
  process.stderr.write(CONSENT_PROMPT + '\x1b[?2004l')
  const rl = createInterface({
    input: process.stdin,
    output: process.stderr,
    terminal: true
  })
  let answer = ''
  try {
    const raw = await rl.question('> ')
    // Strip ANSI + control chars defensively, then lower + trim.
    answer = raw
      .replaceAll(/\x1b\[[?\d;]*[a-zA-Z~]/g, '')
      .replaceAll(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g, '')
      .trim()
      .toLowerCase()
  } catch {
    answer = ''
  } finally {
    rl.close()
    process.stderr.write('\x1b[?2004h')
  }

  if (answer !== 'yes') {
    return { consented: false, reason: 'user declined tool consent' }
  }

  // Persist on the existing session record. We pass the existing token /
  // server version unchanged — saveSession merges onto `prev` so we don't
  // clobber grants or certPin.
  if (existing) {
    try {
      await saveSession(url, existing.token, existing.serverVersion, {
        toolsConsented: true
      })
    } catch {
      // Non-fatal — consent still holds for this process.
    }
  }

  return { consented: true }
}

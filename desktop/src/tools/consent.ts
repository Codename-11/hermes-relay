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

export const COMPUTER_USE_CONSENT_PROMPT = `
Experimental desktop computer-use tools are about to be exposed.
The agent may request screenshots and task-scoped mouse/keyboard actions.
Mouse/keyboard actions still require a local grant and a visible per-action
approval prompt before anything is sent to the OS.
Type 'ALLOW COMPUTER USE' to enable for this relay URL, or press Enter to keep it disabled.
`

export interface EnsureConsentResult {
  consented: boolean
  /** Reason the router was not wired. Only set when consented=false. */
  reason?: string
  source?: 'stored' | 'prompted'
}

function cleanPromptAnswer(raw: string): string {
  return raw
    .replaceAll(/\x1b\[[?\d;]*[a-zA-Z~]/g, '')
    .replaceAll(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g, '')
    .trim()
}

async function promptForAnswer(prompt: string, question = '> '): Promise<string> {
  process.stderr.write(prompt + '\x1b[?2004l')
  const rl = createInterface({
    input: process.stdin,
    output: process.stderr,
    terminal: true
  })
  try {
    return cleanPromptAnswer(await rl.question(question))
  } catch {
    return ''
  } finally {
    rl.close()
    process.stderr.write('\x1b[?2004h')
  }
}

/** Check stored consent for `url`, prompt the user if missing, persist
 * the decision. Never throws — persistence failures fall through as
 * "consented this session only". */
export async function ensureToolsConsent(url: string): Promise<EnsureConsentResult> {
  const existing = await getSession(url)
  if (existing?.toolsConsented === true) {
    return { consented: true, source: 'stored' }
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
  const answer = (await promptForAnswer(CONSENT_PROMPT)).toLowerCase()

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

  return { consented: true, source: 'prompted' }
}

/** Separate durable consent for the experimental computer-use tool surface.
 * This does not create a task grant and does not permit silent host input;
 * action handlers still require an in-memory assist/control grant plus local
 * per-action approval. */
export async function ensureComputerUseConsent(url: string): Promise<EnsureConsentResult> {
  const existing = await getSession(url)
  if (existing?.computerUseConsented === true) {
    return { consented: true, source: 'stored' }
  }

  if (!process.stdin.isTTY) {
    return {
      consented: false,
      reason:
        'desktop computer-use requires interactive consent; rerun on a TTY or omit --experimental-computer-use'
    }
  }

  const answer = await promptForAnswer(COMPUTER_USE_CONSENT_PROMPT)
  if (answer !== 'ALLOW COMPUTER USE') {
    return { consented: false, reason: 'user declined computer-use consent' }
  }

  if (existing) {
    try {
      await saveSession(url, existing.token, existing.serverVersion, {
        computerUseConsented: true
      })
    } catch {
      // Non-fatal — consent still holds for this process.
    }
  }

  return { consented: true, source: 'prompted' }
}

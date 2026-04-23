// activeEditor — best-effort detection of "what the operator is editing
// right now", advertised to the relay as a hint so the agent can say
// "I see you're in foo.rs on line 14" instead of guessing.
//
// We deliberately stay out of the editor's process space:
//   * tmux: `tmux display-message -p` reads the current pane's cwd/cmd.
//     Useful when `hermes-relay shell` runs inside an existing tmux on
//     a remote host, or when the operator drove a tmux from their
//     terminal manually.
//   * VSCode / Cursor / other LSP-flavored editors: these set
//     `VSCODE_IPC_HOOK_CLI` in every terminal they open. We don't read
//     the socket (we'd need an extension); the env var's mere presence
//     is enough to say "a code-like editor is running here". The
//     `editor` field names whichever we detected via `TERM_PROGRAM`.
//
// Anything richer (active buffer, cursor position, selection) requires
// editor-side cooperation — out of scope for alpha.6.

import { exec } from 'node:child_process'

import type { RelayTransport } from './transport/RelayTransport.js'

export interface ActiveEditorHint {
  version: 1
  /** epoch ms — lets the server decide whether the hint is stale. */
  ts: number
  source: 'tmux' | 'vscode_env' | 'none'
  cwd?: string
  command?: string
  /** e.g. 'code' | 'cursor' | 'vim' | 'nvim' | 'helix'. Absent when
   * source='none' or the source doesn't tell us. */
  editor?: string
}

/** Budget for the tmux shell-out. Anything longer and we punt — we'd
 * rather emit a `source:'none'` hint than block the 5s poll loop. */
const TMUX_PROBE_MS = 750

function runTmuxProbe(signal: AbortSignal): Promise<string | null> {
  return new Promise((resolve) => {
    const child = exec(
      'tmux display-message -p "#{pane_current_path}|#{pane_current_command}"',
      { signal, windowsHide: true, timeout: TMUX_PROBE_MS },
      (err, stdout) => {
        if (err) {
          resolve(null)
          return
        }
        const out = stdout?.toString().trim()
        resolve(out || null)
      }
    )
    signal.addEventListener('abort', () => {
      try {
        child.kill('SIGKILL')
      } catch {
        /* ignore */
      }
    }, { once: true })
  })
}

function normalizeEditor(termProgram: string | undefined): string | undefined {
  if (!termProgram) return undefined
  const lower = termProgram.toLowerCase()
  // Known prefixes: `vscode` (stable + insiders), `cursor`, `windsurf`,
  // `hyper`, `iterm.app`. Only the editor-like entries matter for the
  // hint; plain terminals shouldn't pretend to be editors.
  if (lower.includes('vscode')) return 'code'
  if (lower.includes('cursor')) return 'cursor'
  if (lower.includes('windsurf')) return 'windsurf'
  return undefined
}

export async function detectActiveEditor(): Promise<ActiveEditorHint> {
  const now = Date.now()

  // Path 1: tmux. Strongest signal — real cwd + real command name.
  if (process.env.TMUX) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), TMUX_PROBE_MS)
    try {
      const raw = await runTmuxProbe(controller.signal)
      if (raw) {
        const [cwd, command] = raw.split('|', 2)
        const hint: ActiveEditorHint = { version: 1, ts: now, source: 'tmux' }
        if (cwd) hint.cwd = cwd
        if (command) {
          hint.command = command
          // Treat well-known TUI editors as the effective editor.
          const lc = command.toLowerCase()
          if (['vim', 'nvim', 'emacs', 'helix', 'hx', 'nano', 'micro'].includes(lc)) {
            hint.editor = lc
          }
        }
        return hint
      }
    } finally {
      clearTimeout(timer)
    }
  }

  // Path 2: VSCode-ish env vars. The CLI was spawned from the editor's
  // integrated terminal, so SOMETHING editor-shaped is running on this
  // box. We can't cheaply tell if it's focused, but "present" is still
  // useful.
  if (process.env.VSCODE_IPC_HOOK_CLI) {
    const editor = normalizeEditor(process.env.TERM_PROGRAM) ?? 'code'
    const hint: ActiveEditorHint = {
      version: 1,
      ts: now,
      source: 'vscode_env',
      editor
    }
    if (process.env.PWD) hint.cwd = process.env.PWD
    return hint
  }

  return { version: 1, ts: now, source: 'none' }
}

/** Equality check — two hints compare equal if every field we advertise
 * matches. `ts` is excluded because it's monotonic per call. Used to
 * skip duplicate envelopes and keep the WSS quiet when nothing changed. */
function hintEquals(a: ActiveEditorHint | null, b: ActiveEditorHint): boolean {
  if (!a) return false
  return (
    a.source === b.source &&
    a.cwd === b.cwd &&
    a.command === b.command &&
    a.editor === b.editor
  )
}

/** Starts a poller that samples `detectActiveEditor` every `intervalMs`
 * and forwards changed hints to the relay as `desktop.active_editor`
 * envelopes. Returns a stop function. Safe to call before auth — the
 * transport will just drop sends until auth resolves. */
export function startEditorPoller(
  relay: RelayTransport,
  intervalMs: number = 5000
): () => void {
  let last: ActiveEditorHint | null = null
  let stopped = false

  const tick = async () => {
    if (stopped) return
    try {
      const hint = await detectActiveEditor()
      if (stopped) return
      if (!hintEquals(last, hint)) {
        last = hint
        try {
          relay.sendChannel('desktop', 'active_editor', hint as unknown as Record<string, unknown>)
        } catch {
          /* transport send failures are non-fatal; a close will schedule a
           * reconnect and the next tick will re-emit the current hint. */
        }
      }
    } catch {
      /* detectActiveEditor is already defensive; swallow anything that
       * slips past it so one bad poll doesn't kill the loop. */
    }
  }

  const handle: NodeJS.Timeout = setInterval(() => {
    void tick()
  }, Math.max(1000, intervalMs))
  handle.unref?.()

  // Fire once immediately so the relay gets a hint before the first tick
  // interval elapses. Don't await — the CLI shouldn't block on it.
  void tick()

  return () => {
    stopped = true
    clearInterval(handle)
  }
}

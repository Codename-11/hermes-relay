// Editor detection — figure out which `$EDITOR`-style launcher is available
// on this machine and how to invoke it with a file:line:col target.
//
// This is pure detection — no spawn, no file launch. Consumers
// (`tools/handlers/editor.ts`, `tools/patchApproval.ts`) use the returned
// descriptor to build an argv and spawn themselves.
//
// Detection precedence (first match wins):
//   1. $VISUAL
//   2. $EDITOR
//   3. Probe PATH for: code, cursor, subl, nvim, vim
//   4. Platform fallback: notepad (Windows), nano (POSIX)
//
// Line/column support differs by editor. VS Code / Cursor / Sublime use
// `-g <file>:<line>:<col>`. Vim / nvim use `+<line> <file>` (no column).
// Everything else is treated as a plain `<file>` launcher — we still
// return a descriptor, we just set `supportsLineCol: false` so callers
// don't advertise a jump that won't happen.

import { exec } from 'node:child_process'
import * as path from 'node:path'

/** Stable identifier for the detected launcher. `generic` is the escape
 * hatch for an unknown `$EDITOR`/`$VISUAL` — we still spawn it, but we
 * can't promise :line:col semantics. */
export type EditorName =
  | 'code'
  | 'cursor'
  | 'subl'
  | 'vim'
  | 'nvim'
  | 'nano'
  | 'notepad'
  | 'generic'

export interface EditorLauncher {
  /** Identifier we key behavior off in callers. */
  name: EditorName
  /** argv ready to spawn MINUS the file argument. Callers append the
   * target (possibly with `:line:col` for GUI editors). Includes `-g`
   * for GUI editors that need it to enable line/column jumping. */
  command: string[]
  /** If true, the launcher accepts `path:line:col` (GUI editors). If the
   * launcher is vim/nvim we set this false — vim accepts `+<line> file`
   * but not a column, so the handler has to choose a different arg shape. */
  supportsLineCol: boolean
}

/** How long we're willing to wait for a `where`/`which` probe to return.
 * 1s is generous — `which` is usually microseconds. Any slower and the
 * platform is sick enough that we'd rather just fall back. */
const PROBE_TIMEOUT_MS = 1_000

/** Try running `where <name>` (Windows) or `which <name>` (POSIX) under a
 * hard timeout. Resolves to true iff the command exits 0. Any error, any
 * non-zero exit, any timeout → false. Never throws. */
function onPath(name: string): Promise<boolean> {
  return new Promise(resolve => {
    const isWin = process.platform === 'win32'
    const cmd = isWin ? `where ${name}` : `command -v ${name}`
    let settled = false
    const child = exec(cmd, { timeout: PROBE_TIMEOUT_MS, windowsHide: true }, err => {
      if (settled) {
        return
      }
      settled = true
      resolve(!err)
    })
    // Extra safety in case `exec`'s timeout doesn't fire for some reason.
    const guard = setTimeout(() => {
      if (settled) {
        return
      }
      settled = true
      try {
        child.kill()
      } catch {
        /* ignore */
      }
      resolve(false)
    }, PROBE_TIMEOUT_MS + 100)
    guard.unref?.()
  })
}

/** Describe a named launcher. Consolidates the arg-shape knowledge so
 * both `detectEditor()` and the env-var path below stay consistent. */
function describeLauncher(name: EditorName, command: string[]): EditorLauncher {
  const supportsLineCol = name === 'code' || name === 'cursor' || name === 'subl'
  return { name, command, supportsLineCol }
}

/** Parse an `$EDITOR` / `$VISUAL` value into an argv. Respects quoted
 * paths with spaces (e.g. `"C:\Program Files\Sublime Text\subl.exe" --wait`).
 * Not a full shell parser — we handle `"..."` and whitespace splits, which
 * covers the common cases without pulling in a dep. */
function parseEnvArgv(s: string): string[] {
  const out: string[] = []
  let buf = ''
  let inQuote = false
  for (let i = 0; i < s.length; i++) {
    const c = s[i]!
    if (c === '"') {
      inQuote = !inQuote
      continue
    }
    if (!inQuote && /\s/.test(c)) {
      if (buf.length > 0) {
        out.push(buf)
        buf = ''
      }
      continue
    }
    buf += c
  }
  if (buf.length > 0) {
    out.push(buf)
  }
  return out
}

/** Given a bare exe name, classify it to the EditorName enum. We look at
 * the basename without extension so `code.cmd` and `/usr/bin/code` both
 * resolve to 'code'. Used by both env-var and PATH-probe branches. */
function classify(exe: string): EditorName {
  const base = path.basename(exe).toLowerCase().replace(/\.(exe|cmd|bat)$/, '')
  if (base === 'code' || base === 'code-insiders') {
    return 'code'
  }
  if (base === 'cursor') {
    return 'cursor'
  }
  if (base === 'subl' || base === 'sublime_text') {
    return 'subl'
  }
  if (base === 'nvim') {
    return 'nvim'
  }
  if (base === 'vim' || base === 'vi') {
    return 'vim'
  }
  if (base === 'nano') {
    return 'nano'
  }
  if (base === 'notepad') {
    return 'notepad'
  }
  return 'generic'
}

/** Build the launcher descriptor from an env-var value (`$VISUAL`/`$EDITOR`).
 * For GUI editors that need `-g` to jump to a line we inject it; for vim
 * family we leave it bare (caller adds `+<line>`). For `generic` we keep
 * whatever flags the user supplied and disable line/col. */
function fromEnvValue(value: string): EditorLauncher | null {
  const argv = parseEnvArgv(value)
  if (argv.length === 0) {
    return null
  }
  const exe = argv[0]!
  const name = classify(exe)

  // Respect extra user-supplied flags verbatim for generic/editor cases.
  // For code/cursor/subl, ensure `-g` is present so :line:col works.
  if (name === 'code' || name === 'cursor' || name === 'subl') {
    const hasG = argv.slice(1).some(a => a === '-g')
    const rest = hasG ? argv.slice(1) : [...argv.slice(1), '-g']
    return describeLauncher(name, [exe, ...rest])
  }
  return describeLauncher(name, argv)
}

/** Detect the user's editor, with a hard 1 s total timeout budget across
 * all probes. Never throws — worst case we fall back to the platform
 * default (notepad / nano) even if it might not be installed; callers
 * surface the spawn error if launch fails. */
export async function detectEditor(): Promise<EditorLauncher> {
  // 1) $VISUAL — idiomatic "interactive editor" override, takes precedence
  //    over $EDITOR per `man environ` (7) on POSIX and common Unix convention.
  const visual = process.env.VISUAL
  if (visual && visual.trim().length > 0) {
    const parsed = fromEnvValue(visual.trim())
    if (parsed) {
      return parsed
    }
  }
  // 2) $EDITOR — the more-widely-set var; git, crontab, psql, etc. read it.
  const editor = process.env.EDITOR
  if (editor && editor.trim().length > 0) {
    const parsed = fromEnvValue(editor.trim())
    if (parsed) {
      return parsed
    }
  }

  // 3) PATH probe, in preference order. We deliberately prefer GUI editors
  //    over terminal ones — someone running our CLI with VS Code installed
  //    almost certainly wants VS Code to pop up, not vim-in-the-shell.
  const candidates: Array<{ exe: string; name: EditorName }> = [
    { exe: 'code', name: 'code' },
    { exe: 'cursor', name: 'cursor' },
    { exe: 'subl', name: 'subl' },
    { exe: 'nvim', name: 'nvim' },
    { exe: 'vim', name: 'vim' }
  ]
  for (const { exe, name } of candidates) {
    if (await onPath(exe)) {
      if (name === 'code' || name === 'cursor' || name === 'subl') {
        return describeLauncher(name, [exe, '-g'])
      }
      return describeLauncher(name, [exe])
    }
  }

  // 4) Platform fallback. nano is almost always present on POSIX; notepad
  //    is part of the base Windows install. Neither supports :line:col.
  if (process.platform === 'win32') {
    return describeLauncher('notepad', ['notepad'])
  }
  return describeLauncher('nano', ['nano'])
}

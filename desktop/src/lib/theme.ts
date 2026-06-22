// Shared terminal theme — one visual language for the whole CLI.
//
// Before this module, color/symbol choices were scattered: renderer.ts had a
// full ANSI layer, while status/devices/voice/tools each hardcoded "●"/"○" or
// raw `\x1b[..m` strings. That made the CLI feel inconsistent and was a
// maintenance burden. This is the single source of truth.
//
// Zero runtime deps (the package ships no chalk/ora) — just escape codes and
// an env-aware enable check. Construct a `Theme` per command via `theme(opts)`
// so `--no-color` / NO_COLOR / non-TTY all degrade to plain text uniformly.

export const ANSI = {
  reset: '\x1b[0m',
  dim: '\x1b[2m',
  bold: '\x1b[1m',
  italic: '\x1b[3m',
  underline: '\x1b[4m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  magenta: '\x1b[35m',
  cyan: '\x1b[36m',
  gray: '\x1b[90m'
} as const

/**
 * Decide whether ANSI color should be emitted. Precedence mirrors renderer.ts
 * so behavior is identical everywhere:
 *   explicit noColor flag → off
 *   NO_COLOR (any value)  → off            (https://no-color.org)
 *   FORCE_COLOR=0         → off
 *   FORCE_COLOR=<other>   → on
 *   else                  → on iff stdout is a TTY
 */
export function colorEnabled(noColor?: boolean): boolean {
  if (noColor) {
    return false
  }
  if (process.env.NO_COLOR) {
    return false
  }
  if (process.env.FORCE_COLOR === '0') {
    return false
  }
  if (process.env.FORCE_COLOR && process.env.FORCE_COLOR !== '0') {
    return true
  }
  return !!process.stdout.isTTY
}

/** Semantic glyphs. Kept ASCII-adjacent so they render in Windows Terminal,
 * the iTerm/Terminal.app default fonts, and over SSH. */
export const SYMBOLS = {
  ok: '✓',
  err: '✗',
  warn: '⚠',
  arrow: '→',
  on: '●',
  off: '○',
  dot: '·',
  bullet: '•',
  spinner: ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏']
} as const

export class Theme {
  constructor(public readonly enabled: boolean) {}

  /** Wrap `text` in `code` (+reset) when color is on, else return it bare. */
  paint(code: string, text: string): string {
    return this.enabled ? `${code}${text}${ANSI.reset}` : text
  }

  // Raw color helpers.
  bold = (t: string): string => this.paint(ANSI.bold, t)
  dim = (t: string): string => this.paint(ANSI.dim, t)
  red = (t: string): string => this.paint(ANSI.red, t)
  green = (t: string): string => this.paint(ANSI.green, t)
  yellow = (t: string): string => this.paint(ANSI.yellow, t)
  blue = (t: string): string => this.paint(ANSI.blue, t)
  cyan = (t: string): string => this.paint(ANSI.cyan, t)
  magenta = (t: string): string => this.paint(ANSI.magenta, t)
  gray = (t: string): string => this.paint(ANSI.gray, t)

  // Semantic helpers — prefer these in commands so intent (not color) drives
  // the call site; a future palette swap stays in one file.
  ok = (t: string): string => this.green(t)
  warn = (t: string): string => this.yellow(t)
  err = (t: string): string => this.red(t)
  muted = (t: string): string => this.gray(t)
  heading = (t: string): string => this.bold(t)

  /** Green ● for live/enabled, gray ○ for off — used by tools/voice/devices
   * status columns so "on vs off" is scannable at a glance. */
  statusDot(on: boolean): string {
    return on ? this.green(SYMBOLS.on) : this.muted(SYMBOLS.off)
  }

  /** "✓ label" / "✗ label" / "⚠ label" prefixed status lines. */
  okLine = (t: string): string => `${this.ok(SYMBOLS.ok)} ${t}`
  errLine = (t: string): string => `${this.err(SYMBOLS.err)} ${t}`
  warnLine = (t: string): string => `${this.warn(SYMBOLS.warn)} ${t}`

  /** A section header: bold title with a trailing rule of dim dots. Used by
   * status/doctor/voice to break dense output into scannable groups. */
  section(title: string): string {
    return this.bold(title)
  }
}

export interface ThemeOptions {
  /** Force-disable color regardless of env/TTY (the `--no-color` flag). */
  noColor?: boolean
}

/** Build a Theme for a command, honoring env + the `--no-color` flag. */
export function theme(opts: ThemeOptions = {}): Theme {
  return new Theme(colorEnabled(opts.noColor))
}

const ANSI_RE = /\x1b\[[0-9;]*m/g

/** Visible width of a string, ignoring ANSI escape sequences. Data in this
 * CLI is ASCII-dominant (urls, token prefixes, tool names) so a codepoint
 * count is accurate enough; we don't pull in a full East-Asian-width table. */
export function visibleWidth(s: string): number {
  return stripAnsi(s).length
}

export function stripAnsi(s: string): string {
  return s.replace(ANSI_RE, '')
}

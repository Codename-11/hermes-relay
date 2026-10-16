// Shared terminal theme — one visual language for the whole CLI.
//
// Before this module, color/symbol choices were scattered: renderer.ts had a
// full ANSI layer, while status/devices/voice/tools each hardcoded "●"/"○" or
// raw `\x1b[..m` strings. That made the CLI feel inconsistent and was a
// maintenance burden. This is the single source of truth.
//
// Palette: the Relay cockpit language from docs/mockups/hermes-relay-ui-refresh
// (see also docs/android-ui-design-reference.md) — navy-black base with a
// periwinkle accent for active/primary state, purple reserved for identity,
// status green/amber/red for state, warm-white text with muted/dim stairs.
// Terminal colors are the nearest xterm-256 entries for those design tokens;
// 256-color SGR is emitted whenever color is enabled (modern terminals —
// Windows Terminal, iTerm, VS Code — all support it, and terminals that don't
// simply fall back to the default foreground).
//
// Zero runtime deps (the package ships no chalk/ora) — just escape codes and
// an env-aware enable check. Construct a `Theme` per command via `theme(opts)`
// so `--no-color` / NO_COLOR / non-TTY all degrade to plain text uniformly.

export const ANSI = {
  reset: '\x1b[0m',
  dim: '\x1b[2m',
  bold: '\x1b[1m',
  italic: '\x1b[3m',
  underline: '\x1b[4m'
} as const

/**
 * Relay cockpit palette — ANSI-256 approximations of the desktop design
 * tokens (hex in the comments is the design value each code approximates).
 * Only foreground codes; attributes live in ANSI above.
 */
export const PALETTE_256 = {
  ink: '\x1b[38;5;255m', // #F7F6F0 — primary text (warm white)
  muted: '\x1b[38;5;248m', // #A7A4B7 — secondary text
  dim: '\x1b[38;5;60m', // #68647D — tertiary text / rules
  relay: '\x1b[38;5;147m', // #AEBFFF — periwinkle: links, active, primary
  electric: '\x1b[38;5;62m', // #4F5BD5 — electric indigo: primary fills
  identity: '\x1b[38;5;99m', // #8C5CFF — brand identity only
  cyan: '\x1b[38;5;81m', // #6BDCFF — informational accents
  green: '\x1b[38;5;77m', // #58D36F — ok / online
  amber: '\x1b[38;5;215m', // #F2B14B — degraded / attention
  danger: '\x1b[38;5;204m' // #FF6B78 — error / denied
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
  active: '▸',
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

  // Raw helpers. Color names map to the cockpit palette so old call sites
  // still read correctly: blue is the primary accent, magenta is identity,
  // red/yellow/green are status.
  bold = (t: string): string => this.paint(ANSI.bold, t)
  dim = (t: string): string => this.paint(PALETTE_256.dim, t)
  red = (t: string): string => this.paint(PALETTE_256.danger, t)
  green = (t: string): string => this.paint(PALETTE_256.green, t)
  yellow = (t: string): string => this.paint(PALETTE_256.amber, t)
  blue = (t: string): string => this.paint(PALETTE_256.electric, t)
  cyan = (t: string): string => this.paint(PALETTE_256.cyan, t)
  magenta = (t: string): string => this.paint(PALETTE_256.identity, t)
  gray = (t: string): string => this.paint(PALETTE_256.muted, t)

  // Semantic helpers — prefer these in commands so intent (not color) drives
  // the call site; a future palette swap stays in one file.
  relay = (t: string): string => this.paint(PALETTE_256.relay, t)
  electric = (t: string): string => this.paint(PALETTE_256.electric, t)
  identity = (t: string): string => this.paint(PALETTE_256.identity, t)
  ink = (t: string): string => this.paint(PALETTE_256.ink, t)
  ok = (t: string): string => this.green(t)
  warn = (t: string): string => this.yellow(t)
  err = (t: string): string => this.red(t)
  muted = (t: string): string => this.paint(PALETTE_256.muted, t)
  heading = (t: string): string => this.bold(t)

  /** Green ● for live/enabled, dim ○ for off — used by tools/voice/devices
   * status columns so "on vs off" is scannable at a glance. */
  statusDot(on: boolean): string {
    return on ? this.green(SYMBOLS.on) : this.dim(SYMBOLS.off)
  }

  /** "✓ label" / "✗ label" / "⚠ label" prefixed status lines. */
  okLine = (t: string): string => `${this.ok(SYMBOLS.ok)} ${t}`
  errLine = (t: string): string => `${this.err(SYMBOLS.err)} ${t}`
  warnLine = (t: string): string => `${this.warn(SYMBOLS.warn)} ${t}`

  /** A section header: dim leading rule + bold title, so dense output keeps
   * cockpit-style hard structure instead of decorative whitespace. */
  section(title: string): string {
    return `${this.dim('──')} ${this.bold(title)}`
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

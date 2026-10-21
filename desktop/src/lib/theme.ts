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
//
// Palette selection is background-adaptive, because one fixed 256-colour table
// cannot serve both terminal profiles: the cockpit codes are tuned for the navy
// background, where periwinkle 147 is ~1.8:1 on white and ink 255 is warm white
// (1.1:1 — effectively invisible). COLORFGBG is the only portable hint about
// the terminal background, so:
//
//   dark    → PALETTE_256        cockpit codes (unchanged)
//   light   → PALETTE_256_LIGHT  HermesLight tokens, darkened to >=4.5:1 on white
//   unknown → PALETTE_ANSI       16-colour profile codes, so the user's own
//                                terminal theme decides what is legible
//
// COLORFGBG is advisory, not authoritative: when it is absent or unparseable we
// fall back to the profile palette instead of guessing a 256-colour table —
// guessing is exactly what made the CLI unreadable on light terminals.
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

/** The colour roles every Theme helper paints with. A palette is just one
 * escape code per role, so swapping palettes never changes call sites. */
export interface Palette {
  ink: string
  muted: string
  dim: string
  relay: string
  electric: string
  identity: string
  cyan: string
  green: string
  amber: string
  danger: string
}

/**
 * Relay cockpit palette — ANSI-256 approximations of the desktop design
 * tokens (hex in the comments is the design value each code approximates),
 * tuned for the navy cockpit background. Used when COLORFGBG reports a dark
 * terminal, and as the default palette for code that constructs `Theme`
 * directly.
 */
export const PALETTE_256: Palette = {
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
}

/**
 * Light-terminal palette — nearest xterm-256 codes to the Android
 * `BrandPalette.HermesLight` tokens (app/.../ui/theme/BrandPalette.kt), with
 * every role darkened to at least ~4.5:1 on white. Each comment is
 * `<rendered hex> · <design token hex> · <contrast on white>`.
 *
 * The cockpit values cannot be reused here: ink 255 and relay 147 are 1.1:1
 * and 1.8:1 on white. Where the light design token was already dark enough
 * (muted, relay, electric, identity, danger) the nearest code is used as-is;
 * cyan, green, amber and dim fell below 4.5:1 and move to the nearest darker
 * code (dim drops to the gray ramp, since no cube entry is closer).
 */
export const PALETTE_256_LIGHT: Palette = {
  ink: '\x1b[38;5;234m', // #1C1C1C · ink #171826 · 17.0:1
  muted: '\x1b[38;5;60m', // #5F5F87 · muted #5A576C · 6.1:1
  dim: '\x1b[38;5;242m', // #6C6C6C · dim #83809A (3.8:1 as designed) · 5.3:1
  relay: '\x1b[38;5;62m', // #5F5FD7 · relay #3D4BC9 · 5.1:1
  electric: '\x1b[38;5;20m', // #0000D7 · electric #1A24D6 · 10.6:1
  identity: '\x1b[38;5;92m', // #8700D7 · purple #6A3FE0 · 7.0:1 (kept distinct from relay 62)
  cyan: '\x1b[38;5;24m', // #005F87 · cyan #0E84B8 (4.2:1 as designed) · 7.0:1
  green: '\x1b[38;5;28m', // #008700 · green #2E9E48 (3.4:1 as designed) · 4.7:1
  amber: '\x1b[38;5;130m', // #AF5F00 · amber #B5791E (3.7:1 as designed) · 4.7:1
  danger: '\x1b[38;5;124m' // #AF0000 · danger #C7283A · 7.4:1
}

/**
 * 16-colour profile palette — the pre-cockpit codes. Emitted when we cannot
 * tell what background the terminal has: these names are resolved by the
 * user's own profile, so they are legible on light and dark alike. `ink` is
 * the default foreground (SGR 39) and `dim` is the SGR 2 attribute rather
 * than a colour, so both follow the profile too.
 */
export const PALETTE_ANSI: Palette = {
  ink: '\x1b[39m', // default foreground
  muted: '\x1b[90m', // bright black / gray
  dim: '\x1b[2m', // dim attribute
  relay: '\x1b[34m', // blue — links, active, primary
  electric: '\x1b[34m', // blue — primary fills
  identity: '\x1b[35m', // magenta — brand identity only
  cyan: '\x1b[36m',
  green: '\x1b[32m',
  amber: '\x1b[33m',
  danger: '\x1b[31m'
}

/** A writable stream, structurally. Defaults are real stdio streams. */
export type ColorStream = { isTTY?: boolean }

/** The environment keys this module reads, as a plain map so tests (and
 * callers that resolve config elsewhere) can pass one instead of mutating
 * `process.env`. */
export type ColorEnv = Record<string, string | undefined>

export type BackgroundMode = 'dark' | 'light' | 'unknown'

/** ANSI background indexes that mean "dark". 0-6 are the saturated base
 * colours, 8 is bright black; 7 (light gray) and 9-15 are light. */
const DARK_BACKGROUNDS = new Set([0, 1, 2, 3, 4, 5, 6, 8])

/**
 * Read the terminal background from COLORFGBG.
 *
 * The variable is `fg;bg` or `fg;default;bg`; the background is the LAST
 * field, so `15;0` (white on black) is dark and `0;15` (black on white) is
 * light. Anything we cannot parse — absent, a single field, a non-numeric or
 * out-of-range index, a trailing separator — reports `unknown` rather than a
 * guess.
 */
export function detectBackground(env: ColorEnv = process.env): BackgroundMode {
  const raw = env.COLORFGBG
  if (!raw) {
    return 'unknown'
  }
  const fields = raw.split(';')
  if (fields.length < 2) {
    return 'unknown'
  }
  const last = fields[fields.length - 1]?.trim() ?? ''
  if (!/^\d{1,2}$/.test(last)) {
    return 'unknown'
  }
  const bg = Number.parseInt(last, 10)
  if (DARK_BACKGROUNDS.has(bg)) {
    return 'dark'
  }
  if (bg === 7 || (bg >= 9 && bg <= 15)) {
    return 'light'
  }
  return 'unknown'
}

/** Pick the palette that matches the terminal background. */
export function paletteFor(env: ColorEnv = process.env): Palette {
  switch (detectBackground(env)) {
    case 'dark':
      return PALETTE_256
    case 'light':
      return PALETTE_256_LIGHT
    default:
      return PALETTE_ANSI
  }
}

/**
 * Decide whether ANSI color should be emitted. Precedence mirrors renderer.ts
 * so behavior is identical everywhere:
 *   explicit noColor flag → off
 *   NO_COLOR (any value)  → off            (https://no-color.org)
 *   FORCE_COLOR=0         → off
 *   FORCE_COLOR=<other>   → on
 *   else                  → on iff `stream` is a TTY
 *
 * `stream` is the stream the output is written to (stdout by default), not
 * whatever happens to be a terminal: a banner written to stderr must not pick
 * up colour from a redirected stdout, and must not lose it either. `env` is
 * the environment to read (defaults to `process.env`) so callers and tests can
 * supply one instead of mutating the real environment.
 */
export function colorEnabled(
  noColor?: boolean,
  stream: ColorStream = process.stdout,
  env: ColorEnv = process.env
): boolean {
  if (noColor) {
    return false
  }
  if (env.NO_COLOR) {
    return false
  }
  if (env.FORCE_COLOR === '0') {
    return false
  }
  if (env.FORCE_COLOR && env.FORCE_COLOR !== '0') {
    return true
  }
  return !!stream.isTTY
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
  constructor(
    public readonly enabled: boolean,
    public readonly palette: Palette = PALETTE_256
  ) {}

  /** Wrap `text` in `code` (+reset) when color is on, else return it bare. */
  paint(code: string, text: string): string {
    return this.enabled ? `${code}${text}${ANSI.reset}` : text
  }

  // Raw helpers. Color names map to the active palette so old call sites
  // still read correctly: blue is the primary accent, magenta is identity,
  // red/yellow/green are status.
  bold = (t: string): string => this.paint(ANSI.bold, t)
  dim = (t: string): string => this.paint(this.palette.dim, t)
  red = (t: string): string => this.paint(this.palette.danger, t)
  green = (t: string): string => this.paint(this.palette.green, t)
  yellow = (t: string): string => this.paint(this.palette.amber, t)
  blue = (t: string): string => this.paint(this.palette.electric, t)
  cyan = (t: string): string => this.paint(this.palette.cyan, t)
  magenta = (t: string): string => this.paint(this.palette.identity, t)
  gray = (t: string): string => this.paint(this.palette.muted, t)

  // Semantic helpers — prefer these in commands so intent (not color) drives
  // the call site; a future palette swap stays in one file.
  relay = (t: string): string => this.paint(this.palette.relay, t)
  electric = (t: string): string => this.paint(this.palette.electric, t)
  identity = (t: string): string => this.paint(this.palette.identity, t)
  ink = (t: string): string => this.paint(this.palette.ink, t)
  ok = (t: string): string => this.green(t)
  warn = (t: string): string => this.yellow(t)
  err = (t: string): string => this.red(t)
  muted = (t: string): string => this.paint(this.palette.muted, t)
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
  /** Stream the output is written to; its TTY status decides auto-colour.
   * Defaults to stdout, which is where most commands write. */
  stream?: ColorStream
  /** Env source for NO_COLOR / FORCE_COLOR / COLORFGBG. Defaults to
   * `process.env`; pass a map in tests instead of mutating the real one. */
  env?: ColorEnv
}

/** Build a Theme for a command, honoring env + the `--no-color` flag. */
export function theme(opts: ThemeOptions = {}): Theme {
  const env = opts.env ?? process.env
  return new Theme(colorEnabled(opts.noColor, opts.stream, env), paletteFor(env))
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

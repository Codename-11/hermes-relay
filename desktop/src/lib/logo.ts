// CLI brand logo — slim box-drawing "Hermes Relay" wordmark.
//
// Shown atop `--help`, the first-run welcome, the REPL header, and on demand
// via `hermes-relay logo`. Callers gate it to interactive/help contexts and
// pick the stream — it must never land on stdout for `--json`/`--quiet`/piped
// output. Color flows through the shared Theme so `--no-color`/NO_COLOR/non-TTY
// degrade to a plain (still legible) box-drawing wordmark.

import { VERSION } from '../version.js'
import { theme as makeTheme, type Theme } from './theme.js'

/** The wordmark — 3 rows of box-drawing glyphs spelling "Hermes Relay". */
const WORDMARK = [
  '╦ ╦┌─┐┬─┐┌┬┐┌─┐┌─┐  ┬─┐┌─┐┬  ┌─┐┬ ┬',
  '╠═╣├┤ ├┬┘│││├┤ └─┐  ├┬┘├┤ │  ├─┤└┬┘',
  '╩ ╩└─┘┴└─┴ ┴└─┘└─┘  ┴└─└─┘┴─┘┴ ┴ ┴'
]

const TAGLINE = 'thin client · remote Hermes agent over WSS'

export interface LogoOptions {
  theme?: Theme
  /** Append the tagline + version lines (default true). */
  subtitle?: boolean
}

/** Render the logo block (no trailing newline handling beyond a single `\n`). */
export function renderLogo(opts: LogoOptions = {}): string {
  const t = opts.theme ?? makeTheme()
  const lines = WORDMARK.map((l) => t.cyan(l))
  if (opts.subtitle ?? true) {
    lines.push(t.muted(TAGLINE))
    lines.push(t.muted(`v${VERSION}`))
  }
  return lines.join('\n') + '\n'
}

/** Print the logo to stdout (used by the `logo` command). */
export function printLogo(opts: LogoOptions = {}): void {
  process.stdout.write(renderLogo(opts))
}

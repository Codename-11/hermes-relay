import assert from 'node:assert/strict'
import test from 'node:test'

import {
  ANSI,
  PALETTE_256,
  PALETTE_256_LIGHT,
  PALETTE_ANSI,
  Theme,
  detectBackground,
  paletteFor,
  theme
} from '../src/lib/theme.js'

// The theme module owns two review-driven contracts:
//   1. the palette adapts to the terminal background (COLORFGBG) instead of
//      assuming the navy cockpit everywhere, and
//   2. the palette is chosen from a supplied env, so tests and callers never
//      have to mutate process.env to exercise it.
// The light palette additionally has to stay legible on white — that is the
// whole point of the split, so it is asserted numerically below.

const ESC = '\u001b'

test('detectBackground reads the background index from COLORFGBG', () => {
  const cases: Array<[string | undefined, string]> = [
    // `fg;bg` — the background is the last field.
    ['15;0', 'dark'],
    ['0;0', 'dark'],
    ['7;8', 'dark'],
    ['0;15', 'light'],
    ['0;7', 'light'],
    ['0;9', 'light'],
    ['0;12', 'light'],
    // `fg;default;bg` — three fields, still the last one.
    ['15;default;0', 'dark'],
    ['default;default;0', 'dark'],
    ['0;default;15', 'light'],
    ['15;default;15', 'light'],
    // Absent / unparseable → we do not guess.
    [undefined, 'unknown'],
    ['', 'unknown'],
    ['garbage', 'unknown'],
    ['15', 'unknown'], // single field: not a `fg;bg` pair
    ['0;', 'unknown'],
    ['0;16', 'unknown'],
    ['0;-1', 'unknown'],
    ['0;abc', 'unknown'],
    ['0;7;', 'unknown']
  ]
  for (const [raw, expected] of cases) {
    assert.equal(detectBackground({ COLORFGBG: raw }), expected, `COLORFGBG=${String(raw)}`)
  }
  assert.equal(detectBackground({}), 'unknown', 'missing COLORFGBG is unknown')
})

test('paletteFor maps the detected background to a palette', () => {
  assert.equal(paletteFor({ COLORFGBG: '15;0' }), PALETTE_256, 'dark keeps the cockpit palette')
  assert.equal(paletteFor({ COLORFGBG: '0;15' }), PALETTE_256_LIGHT, 'light uses the light palette')
  assert.equal(paletteFor({}), PALETTE_ANSI, 'unknown falls back to 16-colour profile codes')
})

test('the dark palette keeps the cockpit values', () => {
  assert.equal(PALETTE_256.ink, `${ESC}[38;5;255m`)
  assert.equal(PALETTE_256.relay, `${ESC}[38;5;147m`)
  assert.equal(PALETTE_256.muted, `${ESC}[38;5;248m`)
  assert.equal(PALETTE_256.danger, `${ESC}[38;5;204m`)
})

test('the 16-colour fallback uses profile-resolved codes only', () => {
  assert.equal(PALETTE_ANSI.ink, `${ESC}[39m`, 'ink is the default foreground')
  assert.equal(PALETTE_ANSI.dim, ANSI.dim, 'dim is the SGR 2 attribute')
  assert.equal(PALETTE_ANSI.relay, `${ESC}[34m`)
  assert.equal(PALETTE_ANSI.electric, `${ESC}[34m`)
  assert.equal(PALETTE_ANSI.identity, `${ESC}[35m`)
  assert.equal(PALETTE_ANSI.muted, `${ESC}[90m`)
  assert.equal(PALETTE_ANSI.green, `${ESC}[32m`)
  for (const code of Object.values(PALETTE_ANSI)) {
    assert.ok(!code.includes('38;5;'), `16-colour fallback must not emit 256-colour codes: ${code}`)
  }
})

// --- xterm-256 → sRGB → WCAG contrast, so the light palette's legibility is
// pinned rather than trusted. Only the codes this module emits are resolved.
const CUBE = [0, 95, 135, 175, 215, 255]

function rgbFor(code: number): [number, number, number] {
  if (code >= 232) {
    const v = 8 + (code - 232) * 10
    return [v, v, v]
  }
  const n = code - 16
  return [CUBE[Math.floor(n / 36)], CUBE[Math.floor(n / 6) % 6], CUBE[n % 6]]
}

function luminance([r, g, b]: [number, number, number]): number {
  const channel = (value: number): number => {
    const c = value / 255
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

function contrastOnWhite(rgb: [number, number, number]): number {
  const l = luminance(rgb)
  return 1.05 / (l + 0.05)
}

function codeOf(escape: string): number {
  const match = /^\u001b\[38;5;(\d+)m$/.exec(escape)
  if (!match) {
    throw new Error(`expected a 256-colour foreground code, got ${JSON.stringify(escape)}`)
  }
  return Number(match[1])
}

test('every light-palette role clears 4.5:1 on white', () => {
  for (const [role, escape] of Object.entries(PALETTE_256_LIGHT)) {
    const code = codeOf(escape)
    const ratio = contrastOnWhite(rgbFor(code))
    assert.ok(ratio >= 4.5, `${role} (code ${code}) is only ${ratio.toFixed(2)}:1 on white`)
    assert.notEqual(code, 147, `${role} must not reuse the navy periwinkle`)
    assert.notEqual(code, 255, `${role} must not reuse the warm-white ink`)
  }
})

test('the light palette stays distinct from the dark one on the roles that matter', () => {
  // Relay (route) and identity (brand) must not collapse into the same code,
  // otherwise the cockpit's colour language is lost on light terminals.
  assert.notEqual(PALETTE_256_LIGHT.relay, PALETTE_256_LIGHT.identity)
  assert.notEqual(PALETTE_256_LIGHT.relay, PALETTE_256_LIGHT.electric)
  assert.notEqual(PALETTE_256_LIGHT.muted, PALETTE_256_LIGHT.dim)
})

test('theme() selects the palette and colour enablement from the supplied env', () => {
  const light = theme({ stream: { isTTY: true }, env: { COLORFGBG: '0;15' } })
  assert.equal(light.enabled, true)
  assert.equal(light.ok('x'), `${ESC}[38;5;28mx${ESC}[0m`)
  assert.equal(light.relay('x'), `${ESC}[38;5;62mx${ESC}[0m`)

  const dark = theme({ stream: { isTTY: true }, env: { COLORFGBG: '15;0' } })
  assert.equal(dark.relay('x'), `${ESC}[38;5;147mx${ESC}[0m`)

  const unknown = theme({ stream: { isTTY: true }, env: {} })
  assert.equal(unknown.ok('x'), `${ESC}[32mx${ESC}[0m`)
  assert.equal(unknown.ink('x'), `${ESC}[39mx${ESC}[0m`)

  const piped = theme({ stream: { isTTY: false }, env: { COLORFGBG: '0;15' } })
  assert.equal(piped.enabled, false)
  assert.equal(piped.ok('x'), 'x', 'a non-TTY stream degrades to bare text')

  const forcedOff = theme({ noColor: true, stream: { isTTY: true }, env: { COLORFGBG: '15;0' } })
  assert.equal(forcedOff.enabled, false, '--no-color wins over the palette')

  const envNoColor = theme({ stream: { isTTY: true }, env: { COLORFGBG: '15;0', NO_COLOR: '1' } })
  assert.equal(envNoColor.enabled, false, 'NO_COLOR from the supplied env wins')

  const envForced = theme({ stream: { isTTY: false }, env: { COLORFGBG: '15;0', FORCE_COLOR: '1' } })
  assert.equal(envForced.enabled, true, 'FORCE_COLOR from the supplied env wins over a non-TTY stream')
})

test('the Theme helper surface is unchanged for callers', () => {
  const t = new Theme(true, PALETTE_256_LIGHT)
  assert.equal(t.bold('x'), `${ESC}[1mx${ESC}[0m`)
  assert.equal(t.dim('x'), `${ESC}[38;5;242mx${ESC}[0m`)
  assert.equal(t.red('x'), `${ESC}[38;5;124mx${ESC}[0m`)
  assert.equal(t.green('x'), `${ESC}[38;5;28mx${ESC}[0m`)
  assert.equal(t.yellow('x'), `${ESC}[38;5;130mx${ESC}[0m`)
  assert.equal(t.blue('x'), `${ESC}[38;5;20mx${ESC}[0m`)
  assert.equal(t.cyan('x'), `${ESC}[38;5;24mx${ESC}[0m`)
  assert.equal(t.magenta('x'), `${ESC}[38;5;92mx${ESC}[0m`)
  assert.equal(t.gray('x'), `${ESC}[38;5;60mx${ESC}[0m`)
  assert.equal(t.electric('x'), `${ESC}[38;5;20mx${ESC}[0m`)
  assert.equal(t.identity('x'), `${ESC}[38;5;92mx${ESC}[0m`)
  assert.equal(t.muted('x'), `${ESC}[38;5;60mx${ESC}[0m`)
  assert.equal(t.warn('x'), t.yellow('x'))
  assert.equal(t.err('x'), t.red('x'))
  assert.equal(t.statusDot(true), `${ESC}[38;5;28m●${ESC}[0m`)
  assert.equal(t.statusDot(false), `${ESC}[38;5;242m○${ESC}[0m`)
  assert.equal(t.section('tools'), `${ESC}[38;5;242m──${ESC}[0m ${ESC}[1mtools${ESC}[0m`)
  assert.equal(t.okLine('done'), `${ESC}[38;5;28m✓${ESC}[0m done`)
  assert.equal(new Theme(false).ok('x'), 'x', 'a disabled Theme ignores the palette')
})

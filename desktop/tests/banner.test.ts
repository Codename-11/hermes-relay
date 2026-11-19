import assert from 'node:assert/strict'
import test from 'node:test'

import { buildConnectBanner, parseRole, roleLabel, transportLabel, urlIsSecure } from '../src/banner.js'

// The connect banner is the CLI's cockpit status strip. These tests pin the
// strip shape and, more importantly, the color contract: --no-color / NO_COLOR
// / FORCE_COLOR=0 / non-TTY all degrade to the exact same plain text, colour is
// decided by the stream the strip is actually written to (stderr), and the
// palette follows the terminal background reported by COLORFGBG.

const ESC = '\u001b'

test('banner renders the cockpit strip shape when color is off', () => {
  const line = buildConnectBanner({
    url: 'ws://relay.example.test:8767',
    serverVersion: '1.0.0',
    endpointRole: 'lan',
    noColor: true
  })
  assert.equal(line, '▸ relay: connected · LAN · plain · server 1.0.0')
})

test('banner marks a secure tailscale route', () => {
  const line = buildConnectBanner({
    url: 'wss://relay.example.test:8767',
    serverVersion: '1.0.0',
    endpointRole: 'tailscale',
    noColor: true
  })
  assert.equal(line, '▸ relay: connected · Tailscale · secure · server 1.0.0')
})

test('banner degrades to the bare scheme when the role is unknown', () => {
  const line = buildConnectBanner({
    url: 'wss://relay.example.test:8767',
    serverVersion: '1.0.0',
    endpointRole: null,
    noColor: true
  })
  assert.equal(line, '▸ relay: connected · secure wss:// · server 1.0.0')
})

test('transport hint wins over the url scheme', () => {
  assert.equal(transportLabel('ws://relay.example.test', 'wss'), 'Secure')
  assert.equal(transportLabel('wss://relay.example.test', 'ws'), 'Plain')
  assert.equal(transportLabel('wss://relay.example.test', null), 'Secure')
  assert.equal(urlIsSecure('wss://relay.example.test'), true)
  assert.equal(urlIsSecure('ws://relay.example.test'), false)
})

test('parseRole only accepts known roles', () => {
  assert.equal(parseRole('LAN'), 'lan')
  assert.equal(parseRole('Tailscale'), 'tailscale')
  assert.equal(parseRole('nonsense'), null)
  assert.equal(parseRole(undefined), null)
  assert.equal(roleLabel(null), 'Unknown')
})

const COLOR_ENV_KEYS = ['NO_COLOR', 'FORCE_COLOR', 'COLORFGBG'] as const

interface StreamCase {
  stdout: boolean
  stderr: boolean
  /** Env overrides; every COLOR_ENV_KEYS entry is cleared first, so `undefined`
   * really means "unset" rather than "inherit whatever the host has". */
  env?: Record<string, string | undefined>
}

function withStreams<T>(opts: StreamCase, fn: () => T): T {
  const saved = new Map<string, string | undefined>()
  for (const key of COLOR_ENV_KEYS) {
    saved.set(key, process.env[key])
  }
  const stdoutDesc = Object.getOwnPropertyDescriptor(process.stdout, 'isTTY')
  const stderrDesc = Object.getOwnPropertyDescriptor(process.stderr, 'isTTY')
  Object.defineProperty(process.stdout, 'isTTY', { value: opts.stdout, configurable: true })
  Object.defineProperty(process.stderr, 'isTTY', { value: opts.stderr, configurable: true })
  for (const key of COLOR_ENV_KEYS) {
    delete process.env[key]
  }
  for (const [key, value] of Object.entries(opts.env ?? {})) {
    if (value === undefined) {
      delete process.env[key]
    } else {
      process.env[key] = value
    }
  }
  try {
    return fn()
  } finally {
    for (const [stream, desc] of [
      [process.stdout, stdoutDesc],
      [process.stderr, stderrDesc]
    ] as const) {
      if (desc) {
        Object.defineProperty(stream, 'isTTY', desc)
      } else {
        delete (stream as { isTTY?: boolean }).isTTY
      }
    }
    for (const [key, value] of saved) {
      if (value === undefined) {
        delete process.env[key]
      } else {
        process.env[key] = value
      }
    }
  }
}

const BANNER = { url: 'wss://relay.example.test', serverVersion: '1.0.0', endpointRole: 'tailscale' as const }

// NOTE: this test used to assert the 256-colour periwinkle on a bare TTY.
// Palette selection is now background-adaptive, so "no COLORFGBG" means
// "unknown background" and correctly falls back to 16-colour codes. The dark
// palette is pinned by setting COLORFGBG explicitly, which is also what a real
// dark terminal reports.
test('color is emitted on a TTY and suppressed by NO_COLOR / FORCE_COLOR=0', () => {
  withStreams({ stdout: true, stderr: true, env: { COLORFGBG: '15;0' } }, () => {
    assert.ok(
      buildConnectBanner(BANNER).includes(`${ESC}[38;5;147m`),
      'dark TTY should emit the periwinkle route code'
    )

    process.env.NO_COLOR = '1'
    assert.ok(!buildConnectBanner(BANNER).includes(ESC), 'NO_COLOR must suppress all escapes')

    delete process.env.NO_COLOR
    process.env.FORCE_COLOR = '0'
    assert.ok(!buildConnectBanner(BANNER).includes(ESC), 'FORCE_COLOR=0 must suppress all escapes')

    delete process.env.FORCE_COLOR
    assert.ok(!buildConnectBanner({ ...BANNER, noColor: true }).includes(ESC), 'explicit noColor must win on a TTY')
  })
})

test('colour follows the stream the banner is written to, not stdout', () => {
  // `chat 2>relay.log` on an interactive terminal: the strip goes to the file,
  // so it must stay plain even though stdout is a TTY.
  withStreams({ stdout: true, stderr: false }, () => {
    assert.ok(!buildConnectBanner(BANNER).includes(ESC), 'piped stderr must not carry escapes')
  })

  // `chat >reply.txt` on an interactive terminal: stdout is a file but the
  // strip is still on the terminal, so colour stays on.
  withStreams({ stdout: false, stderr: true }, () => {
    assert.ok(
      buildConnectBanner(BANNER).includes(`${ESC}[34m`),
      'terminal stderr should keep colour when stdout is redirected'
    )
  })

  // An explicit stream wins over the stderr default.
  withStreams({ stdout: true, stderr: true }, () => {
    assert.ok(!buildConnectBanner({ ...BANNER, stream: { isTTY: false } }).includes(ESC))
    assert.ok(buildConnectBanner({ ...BANNER, stream: { isTTY: true } }).includes(`${ESC}[34m`))
  })
})

test('palette follows the terminal background reported by COLORFGBG', () => {
  withStreams({ stdout: true, stderr: true, env: { COLORFGBG: '15;0' } }, () => {
    const line = buildConnectBanner(BANNER)
    assert.ok(line.includes(`${ESC}[38;5;147m`), 'dark background keeps the cockpit periwinkle')
    assert.ok(line.includes(`${ESC}[38;5;77m`), 'dark background keeps the cockpit green')
    assert.ok(line.includes(`${ESC}[38;5;248m`), 'dark background keeps the cockpit muted')
  })

  withStreams({ stdout: true, stderr: true, env: { COLORFGBG: '0;15' } }, () => {
    const line = buildConnectBanner(BANNER)
    assert.ok(!line.includes(`${ESC}[38;5;147m`), 'light background must not use the navy periwinkle (~1.8:1 on white)')
    assert.ok(line.includes(`${ESC}[38;5;62m`), 'light background uses the darkened periwinkle')
    assert.ok(line.includes(`${ESC}[38;5;28m`), 'secure transport uses the darkened green')
    assert.ok(line.includes(`${ESC}[38;5;60m`), 'server version uses the light muted code')
  })

  withStreams({ stdout: true, stderr: true }, () => {
    const line = buildConnectBanner(BANNER)
    assert.ok(!line.includes('38;5;'), 'unknown background must not guess a 256-colour palette')
    assert.ok(line.includes(`${ESC}[32m`), 'ok is the profile green')
    assert.ok(line.includes(`${ESC}[34m`), 'the route is the profile blue')
    assert.ok(line.includes(`${ESC}[90m`), 'muted is the profile bright black')
  })
})

test('NO_COLOR beats every palette and the TTY', () => {
  for (const fgbg of ['15;0', '0;15', undefined]) {
    withStreams({ stdout: true, stderr: true, env: { COLORFGBG: fgbg, NO_COLOR: '1' } }, () => {
      assert.ok(!buildConnectBanner(BANNER).includes(ESC), `NO_COLOR must win for COLORFGBG=${String(fgbg)}`)
    })
  }
})

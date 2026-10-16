import assert from 'node:assert/strict'
import test from 'node:test'

import { buildConnectBanner, parseRole, roleLabel, transportLabel, urlIsSecure } from '../src/banner.js'

// The connect banner is the CLI's cockpit status strip. These tests pin the
// strip shape and, more importantly, the color contract: --no-color / NO_COLOR
// / FORCE_COLOR=0 / non-TTY all degrade to the exact same plain text.

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

function withTty<T>(isTty: boolean, fn: () => T): T {
  const original = Object.getOwnPropertyDescriptor(process.stdout, 'isTTY')
  Object.defineProperty(process.stdout, 'isTTY', { value: isTty, configurable: true })
  const env = { ...process.env }
  try {
    return fn()
  } finally {
    if (original) {
      Object.defineProperty(process.stdout, 'isTTY', original)
    } else {
      delete (process.stdout as { isTTY?: boolean }).isTTY
    }
    for (const key of ['NO_COLOR', 'FORCE_COLOR']) {
      if (env[key] === undefined) {
        delete process.env[key]
      } else {
        process.env[key] = env[key]
      }
    }
  }
}

test('color is emitted on a TTY and suppressed by NO_COLOR / FORCE_COLOR=0', () => {
  const opts = { url: 'wss://relay.example.test', serverVersion: '1.0.0', endpointRole: 'tailscale' as const }

  withTty(true, () => {
    delete process.env.NO_COLOR
    delete process.env.FORCE_COLOR
    assert.ok(buildConnectBanner(opts).includes(`${ESC}[38;5;147m`), 'TTY should emit the periwinkle route code')

    process.env.NO_COLOR = '1'
    assert.ok(!buildConnectBanner(opts).includes(ESC), 'NO_COLOR must suppress all escapes')

    delete process.env.NO_COLOR
    process.env.FORCE_COLOR = '0'
    assert.ok(!buildConnectBanner(opts).includes(ESC), 'FORCE_COLOR=0 must suppress all escapes')

    delete process.env.FORCE_COLOR
    assert.ok(!buildConnectBanner({ ...opts, noColor: true }).includes(ESC), 'explicit noColor must win on a TTY')
  })
})

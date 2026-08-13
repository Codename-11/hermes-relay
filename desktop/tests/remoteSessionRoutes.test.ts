import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { getSession, listSessions, saveSession, setStorePath } from '../src/remoteSessions.js'

test('one paired host persists multiple routes without duplicating the host list', async t => {
  const root = await mkdtemp(join(tmpdir(), 'hermes-route-session-'))
  t.after(async () => { setStorePath(null); await rm(root, { recursive: true, force: true }) })
  setStorePath(join(root, 'sessions.json'))
  const lan = 'ws://192.168.1.8:8767'
  const tail = 'ws://100.100.20.3:8767'
  await saveSession(lan, 'token', '1.6.4', {
    endpointRole: 'lan',
    preferSecureRoutes: true,
    routeCandidates: [
      { role: 'lan', priority: 0, api: { host: '192.168.1.8', port: 8767, tls: false }, relay: { url: lan } },
      { role: 'tailscale', priority: 1, api: { host: '100.100.20.3', port: 8767, tls: false }, relay: { url: tail } }
    ]
  })
  assert.deepEqual(Object.keys(await listSessions()), [lan])
  const alternate = await getSession(tail)
  assert.equal(alternate?.token, 'token')
  assert.equal(alternate?.endpointRole, 'tailscale')
  assert.equal(alternate?.preferSecureRoutes, true)
})

test('certificate pins are isolated per route in a shared session', async t => {
  const root = await mkdtemp(join(tmpdir(), 'hermes-route-pins-'))
  t.after(async () => { setStorePath(null); await rm(root, { recursive: true, force: true }) })
  setStorePath(join(root, 'sessions.json'))
  const primary = 'wss://relay.example.test'
  const alternate = 'wss://relay.tailnet.ts.net'
  const routes = [
    { role: 'public', priority: 0, api: { host: 'relay.example.test', port: 443, tls: true }, relay: { url: primary } },
    { role: 'tailscale', priority: 1, api: { host: 'relay.tailnet.ts.net', port: 443, tls: true }, relay: { url: alternate } }
  ]
  await saveSession(primary, 'token', '1.6.4', { certPin: 'pin-primary', routeCandidates: routes })
  await saveSession(alternate, 'token', '1.6.4', { certPin: 'pin-alternate' })
  assert.equal((await getSession(primary))?.certPinSha256, 'pin-primary')
  assert.equal((await getSession(alternate))?.certPinSha256, 'pin-alternate')
  assert.deepEqual(Object.keys(await listSessions()), [primary])
})

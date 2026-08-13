import assert from 'node:assert/strict'
import test from 'node:test'

import { describeTransportSecurity, isOverlayRoute } from '../src/transportSecurity.js'
import { decodePairingPayload, isValidPinnedProxyCandidate, secureFirstCandidates } from '../src/pairingQr.js'
import { RelayTransport } from '../src/transport/RelayTransport.js'

test('classifies WSS as TLS encrypted', () => {
  assert.deepEqual(describeTransportSecurity('wss://relay.example.test:8767'), {
    kind: 'tls', encrypted: true, label: 'Encrypted with TLS', detail: 'Relay traffic is protected end to end with WSS.'
  })
})

test('classifies Tailscale DNS and CGNAT addresses as overlay encrypted', () => {
  assert.equal(describeTransportSecurity('ws://host.tailnet.ts.net:8767').kind, 'overlay')
  assert.equal(describeTransportSecurity('ws://100.100.20.3:8767').kind, 'overlay')
  assert.equal(isOverlayRoute('ws://100.63.255.255:8767'), false)
  assert.equal(isOverlayRoute('ws://100.128.0.1:8767'), false)
})

test('uses an explicit WireGuard route role and does not bless ordinary LAN WS', () => {
  assert.equal(describeTransportSecurity('ws://10.8.0.2:8767', 'wireguard').label, 'Encrypted by WireGuard')
  assert.equal(describeTransportSecurity('ws://192.168.1.8:8767', 'lan').encrypted, false)
})

test('secure-first ranking retains plain LAN as a fallback', () => {
  const ranked = secureFirstCandidates([
    { role: 'lan', priority: 0, api: { host: '192.168.1.8', port: 8767, tls: false }, relay: { url: 'ws://192.168.1.8:8767' } },
    { role: 'tailscale', priority: 1, api: { host: '100.100.20.3', port: 8767, tls: false }, relay: { url: 'ws://100.100.20.3:8767' } },
    { role: 'public', priority: 2, api: { host: 'relay.example.test', port: 443, tls: true }, relay: { url: 'wss://relay.example.test' } }
  ])
  assert.deepEqual(ranked.map(item => item.role), ['tailscale', 'public', 'lan'])
  assert.deepEqual(ranked.map(item => item.priority), [0, 1, 2])
})

test('preserves and validates the Relay-only pinned proxy advertisement', () => {
  const pin = `sha256/${'A'.repeat(43)}=`
  const payload = decodePairingPayload(JSON.stringify({
    hermes: 3, host: '192.168.1.8', port: 8642, key: '',
    endpoints: [{
      role: 'plugin_proxy', priority: 0, recommended: true, security: 'pinned_tls',
      api: { host: '192.168.1.8', port: 8642, tls: false },
      relay: { url: 'wss://relay.example.test:9443/relay/ws', transport_hint: 'wss' },
      proxy: { url: 'https://relay.example.test:9443', transport_hint: 'https', pin_sha256: pin }
    }]
  }))
  const proxy = payload.endpoints?.[0]
  assert.equal(proxy?.proxy?.pinSha256, pin)
  assert.equal(proxy?.security, 'pinned_tls')
  assert.equal(proxy ? isValidPinnedProxyCandidate(proxy) : false, true)
})

test('does not trust a proxy label without a valid HTTPS endpoint and pin', () => {
  const candidate = {
    role: 'plugin_proxy', priority: 0, recommended: true, security: 'pinned_tls',
    api: { host: '192.168.1.8', port: 8642, tls: false },
    relay: { url: 'ws://192.168.1.8:8767' },
    proxy: { url: 'http://192.168.1.8:9443', pinSha256: 'not-a-pin' }
  }
  assert.equal(isValidPinnedProxyCandidate(candidate), false)
})

test('secure proxy transport sends the Relay session on the WebSocket upgrade', () => {
  let headers: Record<string, string> | undefined
  const socket = {
    readyState: 0,
    send() {}, close() {},
    addEventListener() {}
  }
  const transport = new RelayTransport({
    url: 'ws://proxy.test/relay/ws',
    sessionToken: 'session-secret',
    sessionHeader: true,
    wsFactory: (_url, received) => { headers = received; return socket }
  })
  transport.start()
  assert.deepEqual(headers, { 'X-Hermes-Relay-Session': 'session-secret' })
  transport.kill()
})

import assert from 'node:assert/strict'
import test from 'node:test'

import { describeTransportSecurity, isOverlayRoute } from '../src/transportSecurity.js'
import { decodePairingPayload, isValidPinnedProxyCandidate, probeCandidate, secureFirstCandidates } from '../src/pairingQr.js'
import { RelayTransport } from '../src/transport/RelayTransport.js'
import { brokerRouteForCandidate, rankPairingCandidates } from '../src/commands/pair.js'
import { candidateDisplayLabel, displayLabel, inferEndpointRole } from '../src/endpoint.js'

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

test('infers missing roles for legacy direct sessions without calling LAN a custom VPN', () => {
  assert.equal(inferEndpointRole('ws://172.16.24.250:8767'), 'lan')
  assert.equal(inferEndpointRole('ws://192.168.1.8:8767'), 'lan')
  assert.equal(inferEndpointRole('wss://host.tailnet.ts.net:8767'), 'tailscale')
  assert.equal(inferEndpointRole('wss://relay.example.test'), 'public')
  assert.equal(inferEndpointRole('not a url'), 'custom')
  assert.equal(candidateDisplayLabel({ role: 'custom', relay: { url: 'ws://172.16.24.250:8767' } }), 'LAN')
  assert.equal(candidateDisplayLabel({ role: 'custom', relay: { url: 'ws://100.71.8.56:8767' } }), 'Tailscale')
  assert.equal(candidateDisplayLabel({ role: 'wireguard', relay: { url: 'ws://10.8.0.2:8767' } }), 'Custom VPN (wireguard)')
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

test('preserves and validates the unified pinned proxy advertisement', () => {
  const pin = `sha256/${'A'.repeat(43)}=`
  const payload = decodePairingPayload(JSON.stringify({
    hermes: 3, host: '192.168.1.8', port: 8642, key: '',
    endpoints: [{
      role: 'plugin_proxy', priority: 0, recommended: true, security: 'pinned_tls',
      proxy: {
        url: 'https://relay.example.test:9443', transport_hint: 'https',
        pin_sha256: pin, surfaces: ['relay', 'api', 'dashboard']
      }
    }]
  }))
  const proxy = payload.endpoints?.[0]
  assert.equal(proxy?.proxy?.pinSha256, pin)
  assert.equal(proxy?.security, 'pinned_tls')
  assert.equal(proxy?.relay.url, 'wss://relay.example.test:9443/relay/ws')
  assert.deepEqual(proxy?.proxy?.surfaces, ['relay', 'api', 'dashboard'])
  assert.equal(proxy ? isValidPinnedProxyCandidate(proxy) : false, true)
})

test('Hermes Secure Link is user-facing only and remains eligible for secure-only bootstrap', () => {
  const pin = `sha256/${'A'.repeat(43)}=`
  const candidate = {
    role: 'plugin_proxy', priority: 0, recommended: true, security: 'pinned_tls',
    api: { host: 'relay.example.test', port: 9443, tls: true },
    relay: { url: 'wss://relay.example.test:9443/relay/ws' },
    proxy: { url: 'https://relay.example.test:9443', pinSha256: pin, surfaces: ['relay'] }
  }
  assert.equal(displayLabel(candidate.role), 'Hermes Secure Link')
  assert.deepEqual(rankPairingCandidates([candidate], true), [{ ...candidate, priority: 0 }])
  assert.equal(describeTransportSecurity(candidate.relay.url, candidate.role).detail, 'Pinned TLS protects this Relay connection and verifies the paired endpoint.')
})

test('Hermes Reach is presented as experimental reachability without implying payload E2E protection', () => {
  assert.equal(displayLabel('outbound_broker'), 'Hermes Reach (experimental)')
  const security = describeTransportSecurity('wss://broker.example.test/connect', 'outbound_broker')
  assert.equal(security.kind, 'broker')
  assert.equal(security.encrypted, true)
  assert.match(security.detail, /provides outbound reachability/)
  assert.match(security.detail, /broker hop only/)
})

test('parses the exact server-emitted Hermes Reach bootstrap without consuming it during discovery', () => {
  const pin = `sha256/${'A'.repeat(43)}=`
  const payload = decodePairingPayload(JSON.stringify({
    hermes: 3, host: '192.0.2.10', port: 8642, key: '',
    endpoints: [{
      role: 'outbound_broker', priority: 0, recommended: false, experimental: true, security: 'e2ee_pinned_tls',
      broker: { url: 'wss://reach.example.test/v1/connect', host_id: 'h'.repeat(22), credential_kind: 'bootstrap', token: 'x'.repeat(43), expires_at: 2_000_000_000 },
      proxy: { url: 'https://paired.example.test:9443', transport_hint: 'https', pin_sha256: pin, surfaces: ['relay'] }
    }]
  }))
  const candidate = payload.endpoints?.[0]
  assert.equal(candidate?.role, 'outbound_broker')
  assert.equal(candidate?.broker?.credentialKind, 'bootstrap')
  assert.equal(candidate?.recommended, false)
  assert.equal(candidate?.experimental, true)
  assert.equal(candidate?.broker?.token, 'x'.repeat(43))
  assert.equal(candidate ? candidateDisplayLabel(candidate) : '', 'Hermes Reach (experimental)')
  assert.equal(candidate?.relay.url, 'wss://paired.example.test:9443/relay/ws')
  assert.deepEqual(candidate ? brokerRouteForCandidate(candidate) : undefined, {
    url: 'wss://reach.example.test/v1/connect', hostId: 'h'.repeat(22),
    credentialKind: 'bootstrap', token: 'x'.repeat(43),
    innerUrl: 'wss://paired.example.test:9443/relay/ws', innerPinSha256: pin,
  })
})

test('supported routes are ranked ahead of experimental Reach', () => {
  const ranked = secureFirstCandidates([
    { role: 'outbound_broker', priority: 0, experimental: true, api: { host: 'reach.example', port: 443, tls: true }, relay: { url: 'wss://reach.example/relay/ws' } },
    { role: 'tailscale', priority: 1, recommended: true, api: { host: 'host.ts.net', port: 443, tls: true }, relay: { url: 'wss://host.ts.net/relay/ws' } },
  ])
  assert.deepEqual(ranked.map(item => item.role), ['tailscale', 'outbound_broker'])
})

test('Reach health probe uses runtime fetch trust and sends no bootstrap credential', async () => {
  const originalFetch = globalThis.fetch
  let request: { url: string; init?: RequestInit } | undefined
  globalThis.fetch = (async (input: URL | RequestInfo, init?: RequestInit) => {
    request = { url: String(input), init }
    return new Response('', { status: 200 })
  }) as typeof fetch
  try {
    const pin = `sha256/${'A'.repeat(43)}=`
    const candidate = {
      role: 'outbound_broker', priority: 0, security: 'e2ee_pinned_tls',
      api: { host: 'paired.example.test', port: 9443, tls: true },
      relay: { url: 'wss://paired.example.test:9443/relay/ws' },
      broker: { url: 'wss://reach.example.test:19444/v1/connect', hostId: 'h'.repeat(22), credentialKind: 'bootstrap' as const, token: 'x'.repeat(43) },
      proxy: { url: 'https://paired.example.test:9443', pinSha256: pin },
    }
    const result = await probeCandidate(candidate, AbortSignal.timeout(1_000))
    assert.equal(result.reachable, true)
    assert.equal(request?.url, 'https://reach.example.test:19444/health')
    assert.deepEqual(request?.init?.headers, { Accept: '*/*' })
    assert.equal(JSON.stringify(request).includes('x'.repeat(20)), false)
  } finally {
    globalThis.fetch = originalFetch
  }
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

import assert from 'node:assert/strict'
import test from 'node:test'

import {
  decodePairingPayload,
  payloadToRelayCandidates,
  probeCandidate,
} from '../src/pairingQr.js'

test('accepts an API-less Dashboard ingress candidate with a direct Relay fallback', () => {
  const payload = decodePairingPayload(JSON.stringify({
    hermes: 3,
    dashboard_url: 'https://hermes.example.test',
    relay: { url: 'ws://192.168.1.20:8767', code: 'ABC123' },
    endpoints: [
      {
        role: 'https',
        priority: 0,
        recommended: true,
        dashboard: { url: 'https://hermes.example.test' },
        relay: {
          url: 'wss://hermes.example.test/api/plugins/hermes-relay/transport',
          transport_hint: 'wss',
        },
      },
      {
        role: 'lan',
        priority: 1,
        relay: { url: 'ws://192.168.1.20:8767', transport_hint: 'ws' },
      },
    ],
  }))

  const candidates = payloadToRelayCandidates(payload)
  assert.equal(payload.host, '')
  assert.equal(payload.key, '')
  assert.equal(candidates.length, 2)
  assert.equal(candidates[0]?.api, undefined)
  assert.deepEqual(candidates[0]?.dashboard, { url: 'https://hermes.example.test' })
  assert.equal(candidates[1]?.relay.url, 'ws://192.168.1.20:8767')
})

test('API-less candidate probes Relay health without inventing an API server', async () => {
  const originalFetch = globalThis.fetch
  let probed = ''
  globalThis.fetch = (async (input: URL | RequestInfo) => {
    probed = String(input)
    return new Response('', { status: 200 })
  }) as typeof fetch
  try {
    const candidate = payloadToRelayCandidates(decodePairingPayload(JSON.stringify({
      hermes: 3,
      relay: { url: 'ws://192.168.1.20:8767', code: 'ABC123' },
      endpoints: [{
        role: 'lan',
        priority: 0,
        relay: { url: 'ws://192.168.1.20:8767', transport_hint: 'ws' },
      }],
    })))[0]!
    const result = await probeCandidate(candidate, AbortSignal.timeout(1_000))
    assert.equal(result.reachable, true)
    assert.equal(probed, 'http://192.168.1.20:8767/health')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('legacy top-level API and direct Relay payload remains compatible', () => {
  const candidate = payloadToRelayCandidates(decodePairingPayload(JSON.stringify({
    hermes: 2,
    host: '192.168.1.20',
    port: 8642,
    key: 'api-key',
    tls: false,
    relay: { url: 'ws://192.168.1.20:8767', code: 'ABC123' },
  })))[0]!

  assert.deepEqual(candidate.api, { host: '192.168.1.20', port: 8642, tls: false })
  assert.equal(candidate.relay.url, 'ws://192.168.1.20:8767')
})

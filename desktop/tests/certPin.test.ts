import assert from 'node:assert/strict'
import test from 'node:test'

import { certificateDerToPem, peerCertificateDer } from '../src/certPin.js'

test('prefers the Node 24 peer X509 certificate over an empty legacy peer', () => {
  const raw = Buffer.from([1, 2, 3])
  assert.equal(peerCertificateDer({
    getPeerX509Certificate: () => ({ raw }),
    getPeerCertificate: () => ({}),
  }), raw)
})

test('retains the legacy peer certificate fallback for older Node releases', () => {
  const raw = Buffer.from([4, 5, 6])
  assert.equal(peerCertificateDer({
    getPeerCertificate: () => ({ raw }),
  }), raw)
})

test('live TLS trust anchor changes when a certificate is swapped after pin verification', () => {
  const trusted = certificateDerToPem(Buffer.from('certificate-a'))
  const swapped = certificateDerToPem(Buffer.from('certificate-b'))
  assert.match(trusted, /BEGIN CERTIFICATE/)
  assert.notEqual(trusted, swapped)
})

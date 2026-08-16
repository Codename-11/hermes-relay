import assert from 'node:assert/strict'
import test from 'node:test'

import { installWindowsSystemCaTrust } from '../src/windowsSystemCa.js'

const fakeTlsApi = (
  defaults: string[] = ['bundled-ca', 'extra-ca'],
  system: string[] = ['windows-root-ca']
) => {
  const installed: string[][] = []

  return {
    installed,
    getCACertificates(type: 'default' | 'system' = 'default') {
      return type === 'system' ? [...system] : [...defaults]
    },
    setDefaultCACertificates(certificates: string[]) {
      installed.push([...certificates])
    }
  }
}

test('adds Windows system CAs without dropping bundled or extra defaults', () => {
  const tlsApi = fakeTlsApi(['mozilla-root', 'extra-ca'], ['machine-root', 'user-root'])

  const result = installWindowsSystemCaTrust(tlsApi, 'win32', false)

  assert.deepEqual(tlsApi.installed, [['mozilla-root', 'extra-ca', 'machine-root', 'user-root']])
  assert.deepEqual(result, {
    applied: true,
    reason: 'applied',
    systemCertificateCount: 2,
    totalCertificateCount: 4
  })
})

test('does not inspect or replace CAs outside Windows', () => {
  let reads = 0
  const tlsApi = {
    getCACertificates() {
      reads += 1
      return []
    },
    setDefaultCACertificates() {
      throw new Error('should not install')
    }
  }

  const result = installWindowsSystemCaTrust(tlsApi, 'linux', false)

  assert.equal(reads, 0)
  assert.equal(result.reason, 'not-windows')
})

test('leaves Bun trust behavior unchanged', () => {
  let reads = 0
  const tlsApi = {
    getCACertificates() {
      reads += 1
      return []
    },
    setDefaultCACertificates() {
      throw new Error('should not install')
    }
  }

  const result = installWindowsSystemCaTrust(tlsApi, 'win32', true)

  assert.equal(reads, 0)
  assert.equal(result.reason, 'bun')
})

test('leaves older Node runtimes unchanged when the CA APIs are unavailable', () => {
  const result = installWindowsSystemCaTrust({}, 'win32', false)

  assert.deepEqual(result, {
    applied: false,
    reason: 'unsupported',
    systemCertificateCount: 0,
    totalCertificateCount: 0
  })
})

test('does not replace defaults when the Windows store is empty', () => {
  const tlsApi = fakeTlsApi(['mozilla-root', 'extra-ca'], [])

  const result = installWindowsSystemCaTrust(tlsApi, 'win32', false)

  assert.deepEqual(tlsApi.installed, [])
  assert.deepEqual(result, {
    applied: false,
    reason: 'empty',
    systemCertificateCount: 0,
    totalCertificateCount: 2
  })
})

test('does not alter defaults when the Windows certificate store fails', () => {
  const tlsApi = {
    getCACertificates(type: 'default' | 'system' = 'default') {
      if (type === 'system') {
        throw new Error('certificate store unavailable')
      }
      return ['mozilla-root']
    },
    setDefaultCACertificates() {
      throw new Error('should not install')
    }
  }

  const result = installWindowsSystemCaTrust(tlsApi, 'win32', false)

  assert.deepEqual(result, {
    applied: false,
    reason: 'error',
    systemCertificateCount: 0,
    totalCertificateCount: 0,
    error: 'certificate store unavailable'
  })
})

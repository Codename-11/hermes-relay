import assert from 'node:assert/strict'
import { hostname } from 'node:os'
import test from 'node:test'

import { desktopRelayIdentity } from '../src/deviceIdentity.js'

test('desktop relay identity uses hostname as the primary paired-device name', () => {
  const identity = desktopRelayIdentity()
  assert.equal(identity.deviceName, hostname().trim() || 'Hermes-Relay desktop')
  assert.equal(identity.deviceHostname, identity.deviceName)
  assert.equal(identity.clientSurface, 'desktop')
  assert.equal(identity.deviceFormFactor, 'desktop')
  assert.ok(identity.deviceModel.length > 0)
  assert.ok(identity.devicePlatform.length > 0)
})

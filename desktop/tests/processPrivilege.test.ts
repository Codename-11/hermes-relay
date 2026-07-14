import assert from 'node:assert/strict'
import test from 'node:test'

import { windowsIntegrityIsElevated } from '../src/lib/processPrivilege.js'

test('Windows integrity groups distinguish user and administrator tokens', () => {
  assert.equal(windowsIntegrityIsElevated('Mandatory Label\\Medium Mandatory Level S-1-16-8192'), false)
  assert.equal(windowsIntegrityIsElevated('Mandatory Label\\High Mandatory Level S-1-16-12288'), true)
  assert.equal(windowsIntegrityIsElevated('Mandatory Label\\System Mandatory Level S-1-16-16384'), true)
})

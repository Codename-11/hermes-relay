import assert from 'node:assert/strict'
import test from 'node:test'

import { shouldRelaunchBunWithWindowsSystemCa } from '../src/bunSystemCaBootstrap.js'

test('relaunches a Windows Bun binary when system CA trust is not enabled', () => {
  assert.equal(
    shouldRelaunchBunWithWindowsSystemCa({
      platform: 'win32',
      bunVersion: '1.3.11'
    }),
    true
  )
})

test('does not relaunch after Bun starts with Windows system CA trust', () => {
  assert.equal(
    shouldRelaunchBunWithWindowsSystemCa({
      platform: 'win32',
      bunVersion: '1.3.11',
      nodeUseSystemCa: '1'
    }),
    false
  )
})

test('does not relaunch Node or non-Windows Bun runtimes', () => {
  assert.equal(
    shouldRelaunchBunWithWindowsSystemCa({
      platform: 'win32'
    }),
    false
  )
  assert.equal(
    shouldRelaunchBunWithWindowsSystemCa({
      platform: 'linux',
      bunVersion: '1.3.11'
    }),
    false
  )
})

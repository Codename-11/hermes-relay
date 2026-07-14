import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import {
  consumeComputerGrantCancellation,
  readDesktopUseSettings,
  readDesktopUseSettingsSync,
  requestComputerGrantCancellation,
  setDesktopUseEnabled
} from '../src/lib/desktopUseSettings.js'
import { shouldAdvertiseComputerUse } from '../src/tools/handlerSet.js'
import {
  cancelComputerGrant,
  configureComputerUseRuntime,
  requestComputerGrant,
  setComputerGrantChangeListener,
  type ComputerGrant
} from '../src/tools/computerGrants.js'

test('desktop-use preference defaults off and persists explicit changes', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'hermes-desktop-use-'))
  const settingsPath = join(dir, 'desktop-settings.json')
  try {
    assert.equal(readDesktopUseSettingsSync(settingsPath).computer_use_enabled, false)
    await setDesktopUseEnabled(true, settingsPath)
    assert.equal((await readDesktopUseSettings(settingsPath)).computer_use_enabled, true)
    assert.equal(readDesktopUseSettingsSync(settingsPath).computer_use_enabled, true)
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})

test('grant cancellation bridge is consumed exactly once', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'hermes-desktop-cancel-'))
  const cancelPath = join(dir, 'cancel-active.json')
  try {
    await requestComputerGrantCancellation('tray emergency cancel', cancelPath)
    const request = await consumeComputerGrantCancellation(cancelPath)
    assert.equal(request?.reason, 'tray emergency cancel')
    assert.equal(await consumeComputerGrantCancellation(cancelPath), null)
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
})

test('persistent desktop-use preference participates in advertisement precedence', () => {
  assert.equal(shouldAdvertiseComputerUse({}, {}, true), true)
  assert.equal(shouldAdvertiseComputerUse({ 'no-computer-use': true }, {}, true), false)
  assert.equal(shouldAdvertiseComputerUse({ 'experimental-computer-use': true }, {}, false), true)
})

test('grant changes publish immediately for daemon status and tray cancellation', () => {
  const changes: Array<ComputerGrant | null> = []
  configureComputerUseRuntime({ computerUseConsented: true })
  const restore = setComputerGrantChangeListener(grant => changes.push(grant))
  try {
    requestComputerGrant({ mode: 'control', duration_seconds: 60, reason: 'status test' })
    cancelComputerGrant('test complete')
    assert.equal(changes.length, 2)
    assert.equal(changes[0]?.mode, 'control')
    assert.equal(changes[1], null)
  } finally {
    restore()
    cancelComputerGrant('test cleanup')
  }
})

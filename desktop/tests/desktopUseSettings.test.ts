import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import {
  consumeComputerGrantCancellation,
  readDesktopUseSettings,
  readDesktopUseSettingsSync,
  requestComputerGrantCancellation,
  setComputerControlSettings,
  setDesktopUseEnabled
} from '../src/lib/desktopUseSettings.js'
import { shouldAdvertiseComputerUse } from '../src/tools/handlerSet.js'
import { patchHandler } from '../src/tools/handlers/fs.js'
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

test('computer control settings default fail-closed and survive desktop-use changes', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'hermes-computer-control-'))
  const settingsPath = join(dir, 'desktop-settings.json')
  try {
    assert.deepEqual(await readDesktopUseSettings(settingsPath), {
      computer_use_enabled: false,
      computer_control_engine: 'legacy',
      cua_cursor_enabled: false
    })
    await setComputerControlSettings({
      computer_control_engine: 'cua',
      cua_cursor_enabled: true
    }, settingsPath)
    await setDesktopUseEnabled(true, settingsPath)
    const settings = await readDesktopUseSettings(settingsPath)
    assert.equal(settings.computer_use_enabled, true)
    assert.equal(settings.computer_control_engine, 'cua')
    assert.equal(settings.cua_cursor_enabled, true)
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
  configureComputerUseRuntime({ computerUseConsented: true, accessMode: 'ask' })
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

test('full access bypasses expiring computer grants for the selected host', () => {
  configureComputerUseRuntime({
    url: 'wss://trusted.example.test',
    computerUseConsented: true,
    consentSource: 'stored',
    accessMode: 'full_access'
  })
  const result = requestComputerGrant({ mode: 'control', reason: 'remote operation' })
  assert.equal(result.ok, true)
  assert.equal(result.full_access, true)
  assert.equal((result.grant as { mode: string }).mode, 'full_access')
  configureComputerUseRuntime({ accessMode: 'ask' })
})

test('full access applies file patches without an interactive task prompt', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'hermes-full-access-patch-'))
  const file = join(dir, 'note.txt')
  try {
    await writeFile(file, 'before\n')
    configureComputerUseRuntime({
      url: 'wss://trusted.example.test',
      computerUseConsented: true,
      consentSource: 'stored',
      accessMode: 'full_access'
    })
    const result = await patchHandler(
      { path: file, patch: '@@ -1,1 +1,1 @@\n-before\n+after' },
      { cwd: dir, abortSignal: new AbortController().signal, interactive: false }
    ) as { approved: string }
    assert.equal(result.approved, 'auto')
    assert.equal(await readFile(file, 'utf8'), 'after\n')
  } finally {
    configureComputerUseRuntime({ accessMode: 'ask' })
    await rm(dir, { recursive: true, force: true })
  }
})

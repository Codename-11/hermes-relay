import assert from 'node:assert/strict'
import test from 'node:test'

import { advertisedDesktopTools, desktopHandlers, RAW_EXECUTION_TOOLS } from '../src/tools/handlerSet.js'
import { configureCapabilityPolicies } from '../src/tools/capabilityRuntime.js'

test('structured mode omits raw execution but retains serial-bound ADB operations', () => {
  const tools = advertisedDesktopTools({ structuredOnly: true, usb: true })
  for (const raw of RAW_EXECUTION_TOOLS) assert.equal(tools.includes(raw), false, raw)
  for (const brokered of ['desktop_adb_devices', 'desktop_adb_shell', 'desktop_adb_push', 'desktop_adb_pull', 'desktop_adb_install', 'desktop_adb_logcat']) {
    assert.equal(tools.includes(brokered), true, brokered)
  }
  assert.equal(advertisedDesktopTools().includes('desktop_powershell'), true)
  assert.equal(advertisedDesktopTools().includes('desktop_adb_devices'), false)
})

test('ADB handlers fail closed before process execution when USB is disabled', async () => {
  configureCapabilityPolicies({ usb: 'disabled', microphone: 'disabled', camera: 'disabled' })
  const handler = desktopHandlers({ structuredOnly: true, usb: true }).desktop_adb_devices!
  await assert.rejects(
    handler({}, { cwd: process.cwd(), abortSignal: new AbortController().signal, interactive: false }),
    /USB\/ADB capability is disabled/
  )
})

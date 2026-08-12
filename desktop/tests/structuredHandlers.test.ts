import assert from 'node:assert/strict'
import test from 'node:test'

import { advertisedDesktopTools, desktopHandlers, RAW_EXECUTION_TOOLS } from '../src/tools/handlerSet.js'
import { configureCapabilityPolicies } from '../src/tools/capabilityRuntime.js'

test('structured mode omits general raw execution but retains host-gated USB operations', () => {
  const tools = advertisedDesktopTools({ structuredOnly: true, usb: true, adb: true })
  for (const raw of RAW_EXECUTION_TOOLS) assert.equal(tools.includes(raw), false, raw)
  for (const brokered of ['desktop_usb_devices', 'desktop_usb_run', 'desktop_adb_devices', 'desktop_adb_shell', 'desktop_adb_push', 'desktop_adb_pull', 'desktop_adb_install', 'desktop_adb_logcat']) {
    assert.equal(tools.includes(brokered), true, brokered)
  }
  assert.equal(advertisedDesktopTools().includes('desktop_powershell'), true)
  assert.equal(advertisedDesktopTools().includes('desktop_adb_devices'), false)
})

test('raw USB execution is a direct-spawn path governed by the USB policy', async () => {
  const handler = desktopHandlers({ structuredOnly: true, usb: true }).desktop_usb_run!
  configureCapabilityPolicies({ usb: 'disabled', microphone: 'disabled', camera: 'disabled' })
  await assert.rejects(
    handler({ executable: process.execPath, arguments: ['--version'] }, { cwd: process.cwd(), abortSignal: new AbortController().signal, interactive: false }),
    /Raw USB access is disabled/
  )

  configureCapabilityPolicies({ usb: 'allow', microphone: 'disabled', camera: 'disabled' })
  const result = await handler(
    { executable: process.execPath, arguments: ['--version'] },
    { cwd: process.cwd(), abortSignal: new AbortController().signal, interactive: false }
  ) as { exit_code: number; stdout: string; arguments: string[] }
  assert.equal(result.exit_code, 0)
  assert.match(result.stdout, /^v\d+/)
  assert.deepEqual(result.arguments, ['--version'])
})

test('ADB handlers fail closed before process execution when USB is disabled', async () => {
  configureCapabilityPolicies({ usb: 'disabled', microphone: 'disabled', camera: 'disabled' })
  const handler = desktopHandlers({ structuredOnly: true, usb: true, adb: true }).desktop_adb_devices!
  await assert.rejects(
    handler({}, { cwd: process.cwd(), abortSignal: new AbortController().signal, interactive: false }),
    /Raw USB access is disabled/
  )
})

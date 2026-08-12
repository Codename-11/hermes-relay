import assert from 'node:assert/strict'
import test from 'node:test'

import { advertisedDesktopTools, desktopHandlers, RAW_EXECUTION_TOOLS } from '../src/tools/handlerSet.js'
import { configureCapabilityPolicies } from '../src/tools/capabilityRuntime.js'
import { PRESET_CAPABILITY_POLICIES } from '../src/lib/hostAccessPolicy.js'

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
  configureCapabilityPolicies(PRESET_CAPABILITY_POLICIES.ask)
  await assert.rejects(
    handler({ executable: process.execPath, arguments: ['--version'] }, { cwd: process.cwd(), abortSignal: new AbortController().signal, interactive: false }),
    /Raw USB access is disabled/
  )

  configureCapabilityPolicies(PRESET_CAPABILITY_POLICIES.full_access)
  const result = await handler(
    { executable: process.execPath, arguments: ['--version'] },
    { cwd: process.cwd(), abortSignal: new AbortController().signal, interactive: false }
  ) as { exit_code: number; stdout: string; arguments: string[] }
  assert.equal(result.exit_code, 0)
  assert.match(result.stdout, /^v\d+/)
  assert.deepEqual(result.arguments, ['--version'])
})

test('ADB handlers fail closed before process execution when USB is disabled', async () => {
  configureCapabilityPolicies(PRESET_CAPABILITY_POLICIES.ask)
  const handler = desktopHandlers({ structuredOnly: true, usb: true, adb: true }).desktop_adb_devices!
  await assert.rejects(
    handler({}, { cwd: process.cwd(), abortSignal: new AbortController().signal, interactive: false }),
    /Raw USB access is disabled/
  )
})

test('capability policies filter the matching tool groups and Full Access exposes all available tools', () => {
  const structured = advertisedDesktopTools({
    computerUse: true,
    capabilities: PRESET_CAPABILITY_POLICIES.structured,
    adb: true
  })
  assert.equal(structured.includes('desktop_powershell'), false)
  assert.equal(structured.includes('desktop_read_file'), true)
  assert.equal(structured.includes('desktop_computer_screenshot'), true)
  assert.equal(structured.includes('desktop_usb_devices'), true)

  const full = advertisedDesktopTools({
    computerUse: true,
    capabilities: PRESET_CAPABILITY_POLICIES.full_access,
    adb: true
  })
  for (const tool of ['desktop_powershell', 'desktop_read_file', 'desktop_computer_action', 'desktop_usb_run', 'desktop_adb_shell']) {
    assert.equal(full.includes(tool), true, tool)
  }
})

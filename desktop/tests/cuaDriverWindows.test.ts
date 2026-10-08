import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createInterface } from 'node:readline'
import test from 'node:test'

import { CuaDriverAdapter, setCuaControlSessionFactoryForTests } from '../src/tools/cuaDriver.js'
import { ComputerControlSecurityState } from '../src/tools/computerControlSecurity.js'
import { cancelComputerGrant, configureComputerUseRuntime } from '../src/tools/computerGrants.js'
import { computerActionHandler, computerScreenshotHandler } from '../src/tools/handlers/computer.js'
import type { ToolContext } from '../src/tools/router.js'
import { computerActivityDetail } from '../tray/ui/computerActivity.js'

// On demand only: owns a disposable native window and a direct driver child.
test('real Windows CUA handles round-trip through Relay snapshot authority', {
  skip: process.platform !== 'win32' || process.env.HERMES_CUA_WINDOWS_TEST !== '1',
  timeout: 60_000
}, async () => {
  const root = await mkdtemp(join(tmpdir(), 'hermes-cua-window-'))
  const previousSettings = process.env.HERMES_RELAY_DESKTOP_SETTINGS_PATH
  const settings = join(root, 'settings.json')
  await writeFile(settings, JSON.stringify({ computer_use_enabled: true, computer_control_engine: 'cua', cua_cursor_enabled: false }))
  process.env.HERMES_RELAY_DESKTOP_SETTINGS_PATH = settings
  const adapter = await CuaDriverAdapter.connect()
  const authority = {
    controlSessionId: 'live-token-fixture', relaySessionId: 'fixture-relay',
    requesterDeviceId: 'fixture-requester', targetDeviceId: 'fixture-target', runId: 'fixture-run'
  }
  const session = await adapter.openSession(authority)
  setCuaControlSessionFactoryForTests(async () => session)
  configureComputerUseRuntime({ computerUseConsented: true, accessMode: 'full_access' }, authority)
  const child = spawn('powershell.exe', ['-NoProfile', '-STA', '-File',
    fileURLToPath(new URL('./fixtures/cua-token-window.ps1', import.meta.url))], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
  const ctx: ToolContext = { cwd: root, abortSignal: AbortSignal.timeout(50_000), interactive: false,
    controlSession: authority, controlSecurity: new ComputerControlSecurityState(authority) }
  try {
    const lines = createInterface({ input: child.stdout })
    let errors = ''
    child.stderr.on('data', data => { errors += String(data) })
    const target = await new Promise<{ pid: number; windowId: number }>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`Fixture startup timed out: ${errors}`)), 10_000)
      lines.on('line', line => {
        if (!line.startsWith('{')) return
        clearTimeout(timer)
        try { resolve(JSON.parse(line)) } catch (error) { reject(error) }
      })
      child.once('error', error => { clearTimeout(timer); reject(error) })
      child.once('exit', code => { clearTimeout(timer); reject(new Error(`Fixture exited ${code}: ${errors}`)) })
    })
    const args = { pid: target.pid, window_id: target.windowId, include_screenshot: false }
    const snapshot = async () => {
      const result = await computerScreenshotHandler(args, ctx) as Record<string, any>
      assert.equal(result.ok, true, JSON.stringify(result))
      assert.equal(computerActivityDetail(result), 'CUA')
      return result
    }
    for (const [action, label, extra] of [
      ['click_element', 'Fixture button', {}],
      ['set_value', 'Fixture input', { value: Array.from({ length: 60 }, (_, i) => `Fixture line ${i}`).join('\r\n') }],
      ['scroll_element', 'Fixture input', { direction: 'down', amount: 2 }]
    ] as const) {
      const observed = await snapshot()
      const element = observed.elements.find((item: Record<string, unknown>) => item.label === label)
      assert.ok(element, JSON.stringify(observed.elements))
      assert.equal(element.element_token, undefined)
      const request = { ...args, action, ...extra, snapshot_token: element.snapshot_token, snapshot_generation: observed.snapshot_generation }
      const acted = await computerActionHandler(request, ctx) as Record<string, any>
      assert.equal(acted.ok, true, JSON.stringify(acted))
      assert.equal(acted.dispatch, 'background')
      assert.equal(computerActivityDetail(acted), 'CUA · background')
      assert.ok(acted.verification_snapshot.elements.some((item: Record<string, unknown>) => /^s\d+:\d+$/.test(String(item.element_token))))
      if (action === 'click_element') assert.match(acted.verification_snapshot.tree_markdown, /Fixture clicked/)
      if (action === 'set_value') assert.match(JSON.stringify(acted.verification_snapshot), /Fixture line 0/)
      const replay = await computerActionHandler(request, ctx) as Record<string, unknown>
      assert.equal(replay.code, 'invalid_or_stale_snapshot')
    }
  } finally {
    child.kill()
    await adapter.closeAll()
    cancelComputerGrant('fixture complete', authority)
    setCuaControlSessionFactoryForTests(null)
    if (previousSettings === undefined) delete process.env.HERMES_RELAY_DESKTOP_SETTINGS_PATH
    else process.env.HERMES_RELAY_DESKTOP_SETTINGS_PATH = previousSettings
    await rm(root, { recursive: true, force: true })
  }
})

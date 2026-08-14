import assert from 'node:assert/strict'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { ComputerControlSecurityState, type ComputerControlAuthority } from '../src/tools/computerControlSecurity.js'
import {
  cancelComputerGrant,
  configureComputerUseRuntime,
  requestComputerGrant
} from '../src/tools/computerGrants.js'
import {
  setCuaControlSessionFactoryForTests,
  type CuaControlSession,
  type CuaControlSessionIdentity
} from '../src/tools/cuaDriver.js'
import {
  computerActionHandler,
  computerCancelHandler,
  computerScreenshotHandler
} from '../src/tools/handlers/computer.js'
import type { ToolContext } from '../src/tools/router.js'

interface FakeCall {
  name: string
  args?: unknown
}

class FakeCuaSession {
  readonly calls: FakeCall[] = []
  closed = false
  snapshotNumber = 0
  appName = 'Calculator'

  async listWindows(pid?: number): Promise<Record<string, unknown>> {
    this.calls.push({ name: 'listWindows', args: pid })
    return {
      windows: [{
        window_id: 200,
        app_name: this.appName,
        title: this.appName,
        executable: 'C:\\WindowsApps\\CalculatorApp.exe'
      }]
    }
  }

  async snapshot(args: unknown): Promise<Record<string, unknown>> {
    this.snapshotNumber += 1
    this.calls.push({ name: 'snapshot', args })
    return {
      snapshot_id: `s0000000${this.snapshotNumber}`,
      elements: [{ element_index: 7, element_token: 'e1234abcd', role: 'button', label: 'Seven' }],
      tree_markdown: '[7] button Seven'
    }
  }

  async clickElement(args: unknown): Promise<Record<string, unknown>> {
    this.calls.push({ name: 'clickElement', args })
    return { clicked: true }
  }

  async setElementValue(args: unknown, value: string): Promise<Record<string, unknown>> {
    this.calls.push({ name: 'setElementValue', args: { args, value } })
    return { changed: true }
  }

  async pressKey(args: unknown, key: string): Promise<Record<string, unknown>> {
    this.calls.push({ name: 'pressKey', args: { args, key } })
    return { pressed: true }
  }

  async scroll(args: unknown, direction: string, amount: number): Promise<Record<string, unknown>> {
    this.calls.push({ name: 'scroll', args: { args, direction, amount } })
    return { scrolled: true }
  }

  async close(reason?: string): Promise<void> {
    this.closed = true
    this.calls.push({ name: 'close', args: reason })
  }
}

async function fixture(): Promise<{
  authority: ComputerControlAuthority
  ctx: ToolContext
  session: FakeCuaSession
  cleanup(): Promise<void>
}> {
  const dir = await mkdtemp(join(tmpdir(), 'hermes-cua-handlers-'))
  const settingsPath = join(dir, 'desktop-settings.json')
  await writeFile(settingsPath, JSON.stringify({
    computer_use_enabled: true,
    computer_control_engine: 'cua',
    cua_cursor_enabled: true,
    cua_foreground_escalation_enabled: false
  }))
  process.env.HERMES_RELAY_DESKTOP_SETTINGS_PATH = settingsPath
  const authority: ComputerControlAuthority = {
    controlSessionId: 'control-handler-test',
    requestId: 'request-handler-test',
    relaySessionId: 'relay-session',
    requesterDeviceId: 'requester-device',
    runId: 'run-1',
    targetDeviceId: 'desktop-target'
  }
  const session = new FakeCuaSession()
  setCuaControlSessionFactoryForTests(async (_identity: CuaControlSessionIdentity) => session as unknown as CuaControlSession)
  configureComputerUseRuntime({
    url: 'wss://relay.example.test',
    computerUseConsented: true,
    consentSource: 'stored',
    accessMode: 'ask'
  }, authority)
  requestComputerGrant({ mode: 'control', scope: { app: 'Calculator' }, duration_seconds: 60 }, authority)
  const ctx: ToolContext = {
    cwd: dir,
    abortSignal: new AbortController().signal,
    interactive: false,
    controlSession: authority,
    controlSecurity: new ComputerControlSecurityState(authority)
  }
  return {
    authority,
    ctx,
    session,
    cleanup: async () => {
      cancelComputerGrant('test cleanup', authority)
      setCuaControlSessionFactoryForTests(null)
      delete process.env.HERMES_RELAY_DESKTOP_SETTINGS_PATH
      await rm(dir, { recursive: true, force: true })
    }
  }
}

test('CUA handlers issue a Hermes token, execute once, and verify with a fresh snapshot', async () => {
  const item = await fixture()
  try {
    const observed = await computerScreenshotHandler({ pid: 100, window_id: 200 }, item.ctx) as {
      ok: boolean
      backend: string
      elements: Array<{ snapshot_token: string; element_token?: string }>
    }
    assert.equal(observed.ok, true)
    assert.equal(observed.backend, 'cua_driver')
    assert.equal(observed.elements[0]!.element_token, undefined)
    assert.match(observed.elements[0]!.snapshot_token, /^hermes-snapshot-/)

    const acted = await computerActionHandler({
      action: 'click_element',
      pid: 100,
      window_id: 200,
      snapshot_token: observed.elements[0]!.snapshot_token,
      snapshot_generation: 's00000001'
    }, item.ctx) as { ok: boolean; backend: string; verification_snapshot: { snapshot_id: string } }
    assert.equal(acted.ok, true)
    assert.equal(acted.backend, 'cua')
    assert.equal(acted.verification_snapshot.snapshot_id, 's00000002')
    assert.deepEqual(item.session.calls.map(call => call.name), [
      'listWindows', 'snapshot', 'listWindows', 'clickElement', 'snapshot'
    ])

    const replayed = await computerActionHandler({
      action: 'click_element',
      pid: 100,
      window_id: 200,
      snapshot_token: observed.elements[0]!.snapshot_token
    }, item.ctx) as { ok: boolean; code: string }
    assert.equal(replayed.ok, false)
    assert.equal(replayed.code, 'invalid_or_stale_snapshot')
    assert.equal(item.session.calls.filter(call => call.name === 'clickElement').length, 1)
  } finally {
    await item.cleanup()
  }
})

test('CUA handlers reject unauthenticated and mismatched targets before input', async () => {
  const item = await fixture()
  try {
    const unauthenticatedAuthority = { controlSessionId: 'legacy-only' }
    requestComputerGrant({ mode: 'observe', duration_seconds: 60 }, unauthenticatedAuthority)
    const unauthenticated = await computerScreenshotHandler(
      { pid: 100, window_id: 200 },
      { ...item.ctx, controlSession: unauthenticatedAuthority }
    ) as { ok: boolean; code: string }
    assert.equal(unauthenticated.ok, false)
    assert.equal(unauthenticated.code, 'authenticated_control_session_required')
    cancelComputerGrant('test cleanup', unauthenticatedAuthority)

    const missing = await computerScreenshotHandler({ pid: 100, window_id: 201 }, item.ctx) as {
      ok: boolean
      code: string
    }
    assert.equal(missing.ok, false)
    assert.equal(missing.code, 'target_not_found')

    requestComputerGrant({ mode: 'control', scope: { app: 'Notepad' }, duration_seconds: 60 }, item.authority)
    const wrongGrant = await computerScreenshotHandler({ pid: 100, window_id: 200 }, item.ctx) as {
      ok: boolean
      code: string
    }
    assert.equal(wrongGrant.ok, false)
    assert.equal(wrongGrant.code, 'grant_target_mismatch')

    item.session.appName = 'Windows Security'
    configureComputerUseRuntime({
      url: 'wss://relay.example.test',
      computerUseConsented: true,
      consentSource: 'stored',
      accessMode: 'full_access'
    }, item.authority)
    const sensitive = await computerScreenshotHandler({ pid: 100, window_id: 200 }, item.ctx) as {
      ok: boolean
      code: string
    }
    assert.equal(sensitive.ok, false)
    assert.equal(sensitive.code, 'sensitive_target_blocked')
    assert.equal(item.session.calls.some(call => call.name === 'clickElement'), false)
  } finally {
    await item.cleanup()
  }
})

test('desktop computer cancel closes the matching CUA session', async () => {
  const item = await fixture()
  try {
    await computerScreenshotHandler({ pid: 100, window_id: 200 }, item.ctx)
    const result = await computerCancelHandler({ reason: 'operator stop' }, item.ctx) as { ok: boolean }
    assert.equal(result.ok, true)
    assert.equal(item.session.closed, true)
    assert.deepEqual(item.session.calls.at(-1), { name: 'close', args: 'operator stop' })
  } finally {
    await item.cleanup()
  }
})

test('selected CUA retains system display observation but never falls back to legacy input', async () => {
  const item = await fixture()
  try {
    if (process.platform === 'win32') {
      const screenshot = await computerScreenshotHandler({ display: 'primary' }, item.ctx) as {
        ok: boolean
        backend: string
      }
      assert.equal(screenshot.ok, true)
      assert.equal(screenshot.backend, 'system_capture')
    }

    const coordinate = await computerActionHandler({
      action: 'left_click',
      x: 100,
      y: 200
    }, item.ctx) as { ok: boolean; code: string }
    assert.equal(coordinate.ok, false)
    assert.equal(coordinate.code, 'cua_structured_action_required')
    assert.equal(item.session.calls.some(call => call.name === 'clickElement'), false)
  } finally {
    await item.cleanup()
  }
})

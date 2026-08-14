import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import {
  CuaDriverAdapter,
  CuaRuntimeError,
  closeAllCuaControlSessions,
  getCuaControlSession,
  selectComputerControlBackend,
  setCuaControlSessionFactoryForTests,
  type CuaControlSession,
  type CuaProcessResult,
  type CuaProcessRunner
} from '../src/tools/cuaDriver.js'

const REQUIRED_TOOL_LINES = [
  'health_report', 'start_session', 'end_session', 'list_windows', 'get_window_state',
  'click', 'set_value', 'press_key', 'scroll'
].map(name => `${name}: test tool`).join('\n')

class FakeRunner implements CuaProcessRunner {
  readonly calls: Array<{ executable: string; args: readonly string[]; stdin?: string }> = []

  constructor(
    private readonly binaryPath: string,
    private readonly health = 'healthy',
    private readonly version = '0.19.3',
    private readonly permissionMode = 'standard'
  ) {}

  async run(executable: string, args: readonly string[], options = {}): Promise<CuaProcessResult> {
    this.calls.push({ executable, args, stdin: options.stdin })
    const command = args.join(' ')
    if (command === '--version') return ok(`cua-driver ${this.version}`)
    if (command === 'manifest --pretty') {
      return ok(JSON.stringify({ schema_version: '1', binary_version: this.version, binary_path: this.binaryPath }))
    }
    if (command === 'list-tools') return ok(REQUIRED_TOOL_LINES)
    if (command === 'status') return ok(`Cua Driver daemon is running\n  permission mode: ${this.permissionMode} (test)`)
    if (command === 'call health_report') {
      return ok(JSON.stringify({ schema_version: '1', driver_version: this.version, overall: this.health }))
    }
    return ok(JSON.stringify({ ok: true, tool: args[1] }))
  }
}

function ok(stdout: string): CuaProcessResult {
  return { stdout, stderr: '', exitCode: 0 }
}

async function fakeInstall(): Promise<{ home: string; binary: string; cleanup(): Promise<void> }> {
  const home = await mkdtemp(join(tmpdir(), 'hermes-cua-'))
  const releases = join(home, '.cua-driver', 'packages', 'releases')
  const release = join(releases, '0.19.3-x86_64-pc-windows-msvc')
  const current = join(home, '.cua-driver', 'packages', 'current')
  await mkdir(release, { recursive: true })
  const binary = join(release, 'cua-driver.exe')
  await writeFile(binary, '')
  await symlink(release, current, 'junction')
  return { home, binary, cleanup: () => rm(home, { recursive: true, force: true }) }
}

test('discovers only the canonical package/current executable and negotiates readiness', async () => {
  const install = await fakeInstall()
  try {
    const runner = new FakeRunner(install.binary)
    const adapter = await CuaDriverAdapter.connect({ platform: 'win32', homeDir: install.home, runner })
    assert.equal(adapter.binaryPath, install.binary)
    assert.equal(adapter.binaryVersion, '0.19.3')
    assert.equal(adapter.permissionMode, 'standard')
    assert.equal(runner.calls[0]?.executable, install.binary)
    assert.deepEqual(runner.calls.map(call => call.args.join(' ')).slice(0, 5), [
      '--version', 'manifest --pretty', 'list-tools', 'status', 'call health_report'
    ])
  } finally {
    await install.cleanup()
  }
})
test('fails closed for degraded health and unrestricted permission mode', async () => {
  const install = await fakeInstall()
  try {
    await assert.rejects(
      CuaDriverAdapter.connect({ platform: 'win32', homeDir: install.home, runner: new FakeRunner(install.binary, 'degraded') }),
      (error: unknown) => error instanceof CuaRuntimeError && error.code === 'degraded'
    )
    await assert.rejects(
      CuaDriverAdapter.connect({ platform: 'win32', homeDir: install.home, runner: new FakeRunner(install.binary, 'healthy', '0.19.3', 'unrestricted') }),
      (error: unknown) => error instanceof CuaRuntimeError && error.code === 'incompatible'
    )
  } finally {
    await install.cleanup()
  }
})

test('uses a locally derived session and exposes only typed background actions', async () => {
  const install = await fakeInstall()
  try {
    const runner = new FakeRunner(install.binary)
    const adapter = await CuaDriverAdapter.connect({ platform: 'win32', homeDir: install.home, runner })
    const session = await adapter.openSession({ controlSessionId: 'control-1', targetDeviceId: 'desktop-1', runId: 'run-1' })
    await session.snapshot({ pid: 123, windowId: 456, includeScreenshot: false })
    await session.clickElement({ pid: 123, windowId: 456, elementToken: 'e123abc' })
    await session.pressKey({ pid: 123, windowId: 456 }, 'escape')
    await session.close()

    const invocations = runner.calls.filter(call => call.args[0] === 'call' && call.args[1] !== 'health_report')
    assert.deepEqual(invocations.map(call => call.args[1]), [
      'start_session', 'get_window_state', 'click', 'press_key', 'end_session'
    ])
    const start = JSON.parse(invocations[0]!.stdin!) as { session: string; capture_scope: string }
    assert.match(start.session, /^hermes-[0-9a-f]{32}$/)
    assert.equal(start.capture_scope, 'window')
    const click = JSON.parse(invocations[2]!.stdin!) as { session: string; delivery_mode: string; scope: string }
    assert.equal(click.session, start.session)
    assert.equal(click.delivery_mode, 'background')
    assert.equal(click.scope, 'window')
    await assert.rejects(session.listWindows(), /closed/)
  } finally {
    await install.cleanup()
  }
})

test('rejects invalid tokens and keys before invoking the driver', async () => {
  const install = await fakeInstall()
  try {
    const runner = new FakeRunner(install.binary)
    const adapter = await CuaDriverAdapter.connect({ platform: 'win32', homeDir: install.home, runner })
    const session = await adapter.openSession({ controlSessionId: 'control-2', targetDeviceId: 'desktop-1' })
    const before = runner.calls.length
    await assert.rejects(session.clickElement({ pid: 1, windowId: 2, elementToken: '../bad' }), /elementToken/)
    await assert.rejects(session.pressKey({ pid: 1, windowId: 2 }, 'win+r'), /allowlisted/)
    assert.equal(runner.calls.length, before)
    await session.close()
  } finally {
    await install.cleanup()
  }
})

test('shared control sessions reject identity changes under the same authority id', async () => {
  const fake = { close: async () => undefined } as unknown as CuaControlSession
  setCuaControlSessionFactoryForTests(async () => fake)
  try {
    await getCuaControlSession({
      controlSessionId: 'control-bound',
      targetDeviceId: 'desktop-1',
      relaySessionId: 'relay-1',
      requesterDeviceId: 'agent-1',
      runId: 'run-1'
    })
    await assert.rejects(getCuaControlSession({
      controlSessionId: 'control-bound',
      targetDeviceId: 'desktop-1',
      relaySessionId: 'relay-1',
      requesterDeviceId: 'agent-2',
      runId: 'run-1'
    }), /identity changed/)
  } finally {
    await closeAllCuaControlSessions('test cleanup')
    setCuaControlSessionFactoryForTests(null)
  }
})

test('control backend selection is immutable for the active session', async () => {
  const fake = { close: async () => undefined } as unknown as CuaControlSession
  setCuaControlSessionFactoryForTests(async () => fake)
  try {
    const primary = await selectComputerControlBackend('backend-cua', 'cua', true)
    const attemptedDowngrade = await selectComputerControlBackend('backend-cua', 'legacy', false)
    assert.equal(primary.backend, 'cua')
    assert.deepEqual(attemptedDowngrade, primary)

    const compatibility = await selectComputerControlBackend('backend-legacy', 'legacy', false)
    const attemptedUpgrade = await selectComputerControlBackend('backend-legacy', 'cua', true)
    assert.equal(compatibility.backend, 'legacy_compat')
    assert.deepEqual(attemptedUpgrade, compatibility)
  } finally {
    await closeAllCuaControlSessions('test cleanup')
    setCuaControlSessionFactoryForTests(null)
  }
})

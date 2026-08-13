import assert from 'node:assert/strict'
import { mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import {
  CuaDriverAdapter,
  CuaRuntimeError,
  type CuaProcessResult,
  type CuaProcessRunner
} from '../src/tools/cuaDriver.js'

const tools = [
  'health_report', 'start_session', 'end_session', 'list_windows', 'get_window_state',
  'click', 'set_value', 'press_key', 'scroll'
]

class StatefulFakeCua implements CuaProcessRunner {
  readonly calls: Array<{ args: readonly string[]; payload: Record<string, unknown> | null; signal?: AbortSignal }> = []
  rejectClicksAsStale = false

  constructor(private readonly binary: string) {}

  async run(
    _executable: string,
    args: readonly string[],
    options: { stdin?: string; signal?: AbortSignal } = {}
  ): Promise<CuaProcessResult> {
    const payload = options.stdin ? JSON.parse(options.stdin) as Record<string, unknown> : null
    this.calls.push({ args: [...args], payload, signal: options.signal })
    if (options.signal?.aborted) throw new CuaRuntimeError('fake action cancelled', 'transport')
    const command = args.join(' ')
    if (command === '--version') return ok('cua-driver 0.19.3')
    if (command === 'manifest --pretty') {
      return ok(JSON.stringify({ schema_version: '1', binary_version: '0.19.3', binary_path: this.binary }))
    }
    if (command === 'list-tools') return ok(tools.map(tool => `${tool}: fake`).join('\n'))
    if (command === 'status') return ok('permission mode: bounded')
    if (command === 'call health_report') {
      return ok(JSON.stringify({ schema_version: '1', driver_version: '0.19.3', overall: 'healthy' }))
    }
    if (command === 'call get_window_state') {
      return ok(JSON.stringify({
        snapshot_id: 's1234abcd',
        elements: [{ element_index: 7, element_token: 'e1234abcd', role: 'button', label: 'Seven' }]
      }))
    }
    if (command === 'call click' && this.rejectClicksAsStale) {
      return ok(JSON.stringify({ isError: true, code: 'stale_element_token' }))
    }
    return ok(JSON.stringify({ ok: true }))
  }
}

function ok(stdout: string): CuaProcessResult {
  return { stdout, stderr: '', exitCode: 0 }
}

async function harness(): Promise<{
  adapter: CuaDriverAdapter
  runner: StatefulFakeCua
  cleanup(): Promise<void>
}> {
  const home = await mkdtemp(join(tmpdir(), 'hermes-cua-integration-'))
  const release = join(home, '.cua-driver', 'packages', 'releases', '0.19.3-test')
  await mkdir(release, { recursive: true })
  const binary = join(release, 'cua-driver.exe')
  await writeFile(binary, '')
  await symlink(release, join(home, '.cua-driver', 'packages', 'current'), 'junction')
  const runner = new StatefulFakeCua(binary)
  const adapter = await CuaDriverAdapter.connect({ platform: 'win32', homeDir: home, runner })
  return { adapter, runner, cleanup: () => rm(home, { recursive: true, force: true }) }
}

function identity(id: string) {
  return { controlSessionId: id, targetDeviceId: 'desktop-target', runId: 'run-1' }
}

test('semantic CUA action can be bracketed by fresh snapshots without foreground dispatch', async () => {
  const { adapter, runner, cleanup } = await harness()
  try {
    const session = await adapter.openSession(identity('control-snapshot'))
    const before = await session.snapshot({ pid: 100, windowId: 200, includeScreenshot: true })
    const token = (before.elements as Array<{ element_token: string }>)[0]!.element_token
    await session.clickElement({ pid: 100, windowId: 200, elementToken: token })
    await session.snapshot({ pid: 100, windowId: 200, includeScreenshot: false })
    await session.close()
    const calls = runner.calls.filter(call => call.args[0] === 'call' && call.args[1] !== 'health_report')
    assert.deepEqual(calls.map(call => call.args[1]), [
      'start_session', 'get_window_state', 'click', 'get_window_state', 'end_session'
    ])
    assert.deepEqual(calls[2]!.payload, {
      pid: 100,
      window_id: 200,
      element_token: 'e1234abcd',
      session: calls[0]!.payload!.session,
      scope: 'window',
      delivery_mode: 'background'
    })
  } finally {
    await cleanup()
  }
})

test('stale driver token errors and cancellation both fail closed', async () => {
  const { adapter, runner, cleanup } = await harness()
  try {
    const session = await adapter.openSession(identity('control-failure'))
    runner.rejectClicksAsStale = true
    await assert.rejects(
      session.clickElement({ pid: 100, windowId: 200, elementToken: 'edeadbeef' }),
      /rejected the action/
    )
    const controller = new AbortController()
    controller.abort()
    await assert.rejects(
      session.pressKey({ pid: 100, windowId: 200 }, 'escape', controller.signal),
      /cancelled/
    )
    const key = runner.calls.find(call => call.args[1] === 'press_key')
    assert.equal(key, undefined)
    await session.close()
  } finally {
    await cleanup()
  }
})

test('two control authorities get isolated virtual cursor sessions and independent teardown', async () => {
  const { adapter, runner, cleanup } = await harness()
  try {
    const first = await adapter.openSession(identity('control-one'))
    const second = await adapter.openSession(identity('control-two'))
    const starts = runner.calls.filter(call => call.args[1] === 'start_session')
    assert.equal(starts.length, 2)
    assert.notEqual(starts[0]!.payload!.session, starts[1]!.payload!.session)
    assert.match(String(starts[0]!.payload!.session), /^hermes-[0-9a-f]{32}$/)
    assert.equal(starts[0]!.payload!.capture_scope, 'window')
    assert.equal(starts[1]!.payload!.capture_scope, 'window')
    await first.close()
    await assert.rejects(first.snapshot({ pid: 100, windowId: 200 }), /closed/)
    await second.snapshot({ pid: 300, windowId: 400 })
    await second.close()
    const ends = runner.calls.filter(call => call.args[1] === 'end_session').map(call => call.payload!.session)
    assert.deepEqual(new Set(ends), new Set(starts.map(call => call.payload!.session)))
  } finally {
    await cleanup()
  }
})

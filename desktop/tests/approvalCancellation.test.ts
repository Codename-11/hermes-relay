import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { EventEmitter } from 'node:events'
import { access, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import test from 'node:test'

import { listPendingGrantRequests, resolveGrantRequest } from '../src/lib/grantBridge.js'
import { DEFAULT_CAPABILITY_POLICIES } from '../src/lib/hostAccessPolicy.js'
import { configureCapabilityPolicies } from '../src/tools/capabilityRuntime.js'
import { approveComputerGrant, setComputerActionPromptCoordinator } from '../src/tools/computerActionApproval.js'
import { desktopHandlers } from '../src/tools/handlerSet.js'
import { patchHandler, writeFileHandler } from '../src/tools/handlers/fs.js'
import { adbDevicesHandler } from '../src/tools/handlers/adb.js'
import { approveOrReject } from '../src/tools/patchApproval.js'
import type { RelayTransport } from '../src/transport/RelayTransport.js'
import { DesktopToolRouter, type ToolResponsePayload } from '../src/tools/router.js'

async function fixture(run: (dir: string) => Promise<void>): Promise<void> {
  const dir = await mkdtemp(join(tmpdir(), 'relay-approval-cancel-'))
  const previous = process.env.HERMES_RELAY_GRANT_BRIDGE_DIR
  process.env.HERMES_RELAY_GRANT_BRIDGE_DIR = dir
  try { await run(dir) } finally {
    if (previous === undefined) delete process.env.HERMES_RELAY_GRANT_BRIDGE_DIR
    else process.env.HERMES_RELAY_GRANT_BRIDGE_DIR = previous
    configureCapabilityPolicies(DEFAULT_CAPABILITY_POLICIES)
    await rm(dir, { recursive: true, force: true })
  }
}

async function pendingId(dir: string): Promise<string> {
  for (let attempt = 0; attempt < 100; attempt++) {
    const pending = await listPendingGrantRequests(dir)
    if (pending.length === 1) return pending[0].id
    await delay(10)
  }
  throw new Error('isolated approval did not become pending')
}

const policies = { ...DEFAULT_CAPABILITY_POLICIES, files: 'ask' as const, commands: 'ask' as const, usb: 'ask' as const }
const context = (dir: string, controller: AbortController) => ({ cwd: dir, abortSignal: controller.signal, interactive: false })

// Only the wire boundary is replaced. Approval, dispatch and file effects use
// the native router, guarded desktopHandlers and native on-disk grant bridge.
class ApprovalTransport extends EventEmitter {
  private listener: ((type: string, payload: Record<string, unknown>) => void) | null = null
  readonly responses: ToolResponsePayload[] = []
  onChannel(channel: string, listener: typeof this.listener): void {
    assert.equal(channel, 'desktop')
    this.listener = listener
  }
  sendChannel(channel: string, type: string, payload: ToolResponsePayload): void {
    assert.equal(channel, 'desktop')
    if (type === 'desktop.response') this.responses.push(payload)
  }
  receive(type: string, payload: Record<string, unknown>): void {
    assert.ok(this.listener)
    this.listener(type, payload)
  }
}

async function routerFixture(run: (dir: string, transport: ApprovalTransport) => Promise<void>): Promise<void> {
  await fixture(async dir => {
    const previousHome = process.env.HOME
    const previousProfile = process.env.USERPROFILE
    process.env.HOME = dir
    process.env.USERPROFILE = dir
    let router: DesktopToolRouter | undefined
    try {
      assert.equal(homedir(), dir, 'all native router state must remain in the isolated fixture')
      const transport = new ApprovalTransport()
      router = new DesktopToolRouter({ handlers: desktopHandlers({ capabilities: policies }), consentGranted: true, interactive: false })
      router.attach(transport as unknown as RelayTransport)
      await run(dir, transport)
    } finally {
      router?.detach()
      // Native audit appends follow the wire response asynchronously.
      await delay(100)
      if (previousHome === undefined) delete process.env.HOME
      else process.env.HOME = previousHome
      if (previousProfile === undefined) delete process.env.USERPROFILE
      else process.env.USERPROFILE = previousProfile
    }
  })
}

async function oneResponse(transport: ApprovalTransport): Promise<ToolResponsePayload> {
  for (let attempt = 0; attempt < 200; attempt++) {
    if (transport.responses.length) {
      assert.equal(transport.responses.length, 1)
      return transport.responses[0]
    }
    await delay(10)
  }
  throw new Error('native router did not respond')
}

test('real router keeps native Ask file approval pending beyond former 30 seconds, then writes once', async () => {
  await routerFixture(async (dir, transport) => {
    const path = join(dir, 'router-approved.txt')
    transport.receive('desktop.command', { request_id: 'slow-native-approval', tool: 'desktop_write_file', args: { path, content: 'one approved native write' } })
    const id = await pendingId(dir)
    const started = performance.now()
    // Real timers deliberately exercise timers/promises polling alongside real
    // async filesystem I/O; no synthetic approval touches the real user profile.
    await delay(31_000)
    assert.ok(performance.now() - started >= 30_000)
    assert.deepEqual(transport.responses, [])
    await assert.rejects(access(path), /ENOENT/)
    assert.equal((await listPendingGrantRequests(dir))[0]?.id, id)
    await resolveGrantRequest(dir, id, true)
    const response = await oneResponse(transport)
    assert.equal(response.request_id, 'slow-native-approval')
    assert.equal(response.ok, true)
    if (response.ok) assert.deepEqual(response.result, { ok: true, path, bytes_written: Buffer.byteLength('one approved native write') })
    assert.equal(await readFile(path, 'utf8'), 'one approved native write')
    assert.deepEqual(await listPendingGrantRequests(dir), [])
    // Replacing the completed fixture content also detects an automatic retry.
    await writeFile(path, 'completed marker', 'utf8')
    await delay(300)
    assert.equal(await readFile(path, 'utf8'), 'completed marker')
    assert.equal(transport.responses.length, 1)
  })
})

test('real router desktop.cancel retires pending native Ask approval without a file effect', async () => {
  await routerFixture(async (dir, transport) => {
    const path = join(dir, 'router-canceled.txt')
    transport.receive('desktop.command', { request_id: 'cancel-native-approval', tool: 'desktop_write_file', args: { path, content: 'must not run' } })
    const id = await pendingId(dir)
    transport.receive('desktop.cancel', { request_id: 'cancel-native-approval' })
    assert.equal((await oneResponse(transport)).ok, false)
    // Router cancellation can respond before the bridge's async finally ends.
    for (let attempt = 0; attempt < 100 && (await listPendingGrantRequests(dir)).length; attempt++) await delay(10)
    assert.deepEqual(await listPendingGrantRequests(dir), [])
    await assert.rejects(resolveGrantRequest(dir, id, true), /not found/)
    await delay(100)
    await assert.rejects(access(path), /ENOENT/)
    assert.equal(transport.responses.length, 1)
  })
})

test('canceled pending native file approval cannot write, approve later, or retain bridge artifacts', async () => {
  await fixture(async dir => {
    const controller = new AbortController()
    const path = join(dir, 'must-not-exist.txt')
    const handler = desktopHandlers({ capabilities: policies }).desktop_write_file!
    const operation = handler({ path, content: 'not approved' }, context(dir, controller))
    const rejected = assert.rejects(operation, /abort|cancel/i)
    const id = await pendingId(dir)
    controller.abort()
    await rejected
    await assert.rejects(access(path), /ENOENT/)
    assert.deepEqual(await readdir(dir), [])
    await assert.rejects(resolveGrantRequest(dir, id, true), /not found/)
    await assert.rejects(access(path), /ENOENT/)
  })
})

test('timely native bridge approval writes the actual isolated file once', async () => {
  await fixture(async dir => {
    const controller = new AbortController()
    const path = join(dir, 'approved.txt')
    const handler = desktopHandlers({ capabilities: policies }).desktop_write_file!
    const operation = handler({ path, content: 'approved fixture' }, context(dir, controller))
    await resolveGrantRequest(dir, await pendingId(dir), true)
    const result = await operation as { ok: boolean; bytes_written: number }
    assert.equal(result.ok, true)
    assert.equal(result.bytes_written, Buffer.byteLength('approved fixture'))
    assert.equal(await readFile(path, 'utf8'), 'approved fixture')
    assert.deepEqual(await readdir(dir), ['approved.txt'])
  })
})

test('pre-aborted requests create neither native approval nor file effects', async () => {
  await fixture(async dir => {
    const controller = new AbortController()
    controller.abort()
    const path = join(dir, 'not-created.txt')
    const ctx = context(dir, controller)
    await assert.rejects(desktopHandlers({ capabilities: policies }).desktop_write_file!({ path, content: 'x' }, ctx), /abort/i)
    await assert.rejects(writeFileHandler({ path, content: 'x' }, ctx), /abort/i)
    const decision = await approveComputerGrant({ mode: 'files.write', durationSeconds: 120, reason: 'fixture', scope: {}, interactive: false, abortSignal: controller.signal })
    assert.equal(decision.approved, false)
    assert.deepEqual(await readdir(dir), [])
  })
})

test('canceling a queued native prompt resolves denial and cannot open it later', async () => {
  const controller = new AbortController()
  let release: (() => Promise<void>) | undefined
  const restore = setComputerActionPromptCoordinator(run => new Promise(resolve => {
    release = async () => { resolve(await run()) }
  }))
  try {
    const result = approveComputerGrant({ mode: 'files.write', durationSeconds: 120, reason: 'queued fixture', scope: {}, interactive: true, abortSignal: controller.signal })
    await delay(0)
    assert.ok(release)
    controller.abort()
    assert.equal((await result).approved, false)
    // The real queued callback checks the signal and returns before creating a
    // readline; this completes without waiting for any stdin input.
    await release()
  } finally { restore() }
})

test('native ADB approval consumer cancels without spawning a backend', async () => {
  await fixture(async dir => {
    configureCapabilityPolicies(policies)
    const controller = new AbortController()
    const operation = adbDevicesHandler({}, context(dir, controller))
    const rejected = assert.rejects(operation, /abort|cancel/i)
    await pendingId(dir)
    controller.abort()
    await rejected
    assert.deepEqual(await readdir(dir), [])
  })
})

test('pre-aborted patch approval leaves the actual source file unchanged', async () => {
  await fixture(async dir => {
    const path = join(dir, 'source.txt')
    await writeFile(path, 'old\n', 'utf8')
    const controller = new AbortController()
    controller.abort()
    const patch = '--- a/source.txt\n+++ b/source.txt\n@@ -1 +1 @@\n-old\n+new\n'
    const decision = await approveOrReject(patch, { targetFile: path, interactive: true, abortSignal: controller.signal })
    assert.equal(decision.accepted, false)
    await assert.rejects(patchHandler({ path, patch }, context(dir, controller)), /abort/i)
    assert.equal(await readFile(path, 'utf8'), 'old\n')
  })
})

async function activePrompt(kind: 'grant' | 'patch'): Promise<{ output: string; prompts: string }> {
  const module = kind === 'grant' ? 'computerActionApproval' : 'patchApproval'
  const invoke = kind === 'grant'
    ? "approveComputerGrant({mode:'fixture',durationSeconds:120,reason:'fixture',scope:{},interactive:true,abortSignal:c.signal})"
    : "approveOrReject('--- a/x\\n+++ b/x\\n@@ -1 +1 @@\\n-old\\n+new\\n',{targetFile:'fixture',interactive:true,abortSignal:c.signal})"
  const exported = kind === 'grant' ? 'approveComputerGrant' : 'approveOrReject'
  const code = `import {${exported}} from './src/tools/${module}.ts'; const c=new AbortController(); const p=${invoke}; setTimeout(()=>c.abort(),30); console.log(JSON.stringify(await p)); process.stdin.destroy();`
  const child = spawn(process.execPath, ['--import', 'tsx', '--input-type=module', '--eval', code], {
    cwd: fileURLToPath(new URL('../', import.meta.url)), stdio: ['pipe', 'pipe', 'pipe']
  })
  let output = '', prompts = ''
  child.stdout.on('data', chunk => { output += String(chunk) })
  child.stderr.on('data', chunk => { prompts += String(chunk) })
  const timeout = setTimeout(() => child.kill(), 5000)
  try {
    await new Promise<void>((resolve, reject) => {
      child.once('error', reject)
      child.once('exit', code => code === 0 ? resolve() : reject(new Error(`isolated prompt child exit ${code}: ${prompts}`)))
    })
    return { output, prompts }
  } finally { clearTimeout(timeout); child.stdin.destroy() }
}

test('active native grant and patch readline prompts close on abort without answering yes', async () => {
  for (const kind of ['grant', 'patch'] as const) {
    const result = await activePrompt(kind)
    assert.match(result.prompts, kind === 'grant' ? /Approve grant/ : /Apply patch/)
    const decision = JSON.parse(result.output)
    assert.equal(kind === 'grant' ? decision.approved : decision.accepted, false)
    assert.match(decision.reason, /cancel/i)
  }
})

import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { syncBuiltinESMExports } from 'node:module'
import os from 'node:os'
import { join } from 'node:path'
import readline from 'node:readline/promises'
import test, { type TestContext } from 'node:test'

import type { ParsedArgs } from '../src/cli.js'
import { chatCommand } from '../src/commands/chat.js'
import { hostsCommand } from '../src/commands/hosts.js'
import { connectAndAuth } from '../src/commands/shell.js'
import { toolsCommand } from '../src/commands/tools.js'
import { voiceCommand } from '../src/commands/voice.js'
import { resolveCredentials } from '../src/credentials.js'
import { setDesktopConfigPath } from '../src/desktopConfig.js'
import { getSession, saveSession, setStorePath } from '../src/remoteSessions.js'

const primary = 'ws://primary.example.test:8767'
const alternate = 'ws://alternate.example.test:8767'
const originalToken = 'saved-test-session-token'
const replacementToken = 'replacement-test-session-token'
type Outcome = 'network' | 'rejected' | 'success'
type Listener = (event: unknown) => void

async function setup(t: TestContext, outcomes: Outcome[] = []) {
  const root = await mkdtemp(join(os.tmpdir(), 'hermes-credential-persistence-'))
  const store = join(root, 'sessions.json')
  const envNames = ['HERMES_RELAY_TOKEN', 'HERMES_RELAY_CODE', 'HERMES_RELAY_PAIR_QR', 'HERMES_RELAY_HOST_ACCESS_POLICY_PATH']
  const env = Object.fromEntries(envNames.map(name => [name, process.env[name]]))
  for (const name of envNames) delete process.env[name]
  process.env.HERMES_RELAY_HOST_ACCESS_POLICY_PATH = join(root, 'host-access.json')
  setStorePath(store)
  setDesktopConfigPath(join(root, 'desktop-control.json'))
  // Isolate the default device identity too; never read or modify real pairings.
  t.mock.method(os, 'homedir', () => root)
  syncBuiltinESMExports()
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'WebSocket')!
  const tty = Object.getOwnPropertyDescriptor(process.stdin, 'isTTY')
  const attempts: Record<string, unknown>[] = []
  const sockets: Array<{ close(): void }> = []

  class Socket {
    readyState = 0
    private listeners = new Map<string, Listener[]>()
    private outcome: Outcome
    constructor() {
      this.outcome = outcomes.shift() ?? 'rejected'
      sockets.push(this)
      queueMicrotask(() => {
        if (this.readyState === 3) return
        this.readyState = 1
        this.emit('open', undefined)
      })
    }
    addEventListener(type: string, listener: Listener): void {
      this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
    }
    send(data: string): void {
      const frame = JSON.parse(data)
      if (frame.channel !== 'system' || frame.type !== 'auth') return
      attempts.push(frame.payload)
      queueMicrotask(() => {
        if (this.outcome === 'network') {
          this.readyState = 3
          this.emit('close', { code: 1006, reason: 'network unreachable' })
        } else {
          this.emit('message', { data: JSON.stringify({
            channel: 'system', type: this.outcome === 'success' ? 'auth.ok' : 'auth.fail',
            payload: this.outcome === 'success'
              ? { session_token: replacementToken, server_version: 'test', grants: { terminal: 2000000000 }, expires_at: 2000000000 }
              : { reason: 'invalid session token' }
          }) })
        }
      })
    }
    close(): void { this.readyState = 3 }
    private emit(type: string, event: unknown): void {
      for (const listener of this.listeners.get(type) ?? []) listener(event)
    }
  }
  Object.defineProperty(globalThis, 'WebSocket', { ...descriptor, value: Socket })
  t.mock.method(process.stderr, 'write', () => true)
  t.mock.method(process.stdout, 'write', () => true)
  t.after(async () => {
    for (const socket of sockets) socket.close()
    Object.defineProperty(globalThis, 'WebSocket', descriptor)
    if (tty) Object.defineProperty(process.stdin, 'isTTY', tty)
    else delete (process.stdin as { isTTY?: boolean }).isTTY
    t.mock.restoreAll()
    syncBuiltinESMExports()
    setStorePath(null)
    setDesktopConfigPath(null)
    for (const name of envNames) {
      if (env[name] === undefined) delete process.env[name]
      else process.env[name] = env[name]
    }
    await rm(root, { recursive: true, force: true })
  })
  await saveSession(primary, originalToken, 'test', {
    pairedAt: 1700000000, grants: { terminal: 1900000000, chat: null },
    ttlExpiresAt: 1900000000, toolsConsented: true, certPin: 'primary-pin',
    routeCandidates: [
      { role: 'lan', priority: 0, relay: { url: primary } },
      { role: 'tailscale', priority: 1, relay: { url: alternate } }
    ]
  })
  await saveSession(alternate, originalToken, 'test', { certPin: 'alternate-pin', pairedAt: 1700000000 })
  const before = await readFile(store, 'utf8')
  const args = (command: string, interactive = false): ParsedArgs => ({
    command, positional: command === 'voice' ? ['mode'] : [],
    flags: { remote: alternate, 'non-interactive': !interactive, 'no-color': true, quiet: true, 'no-tools': true }
  })
  const prompt = (answer: () => Promise<string>) => {
    Object.defineProperty(process.stdin, 'isTTY', { value: true, configurable: true })
    t.mock.method(readline, 'createInterface', () => ({ question: answer, close() {} }) as ReturnType<typeof readline.createInterface>)
    syncBuiltinESMExports()
  }
  return { store, before, attempts, args, prompt }
}

for (const command of ['shell', 'chat', 'voice', 'tools']) {
  for (const outcome of ['network', 'rejected'] as const) {
    test(`${command} preserves the entire saved host after ${outcome} failure`, async t => {
      const f = await setup(t, [outcome])
      const args = f.args(command)
      if (command === 'shell') await assert.rejects(connectAndAuth(args), /relay rejected credentials/)
      else {
        const run = command === 'chat' ? chatCommand : command === 'voice' ? voiceCommand : toolsCommand
        assert.equal(await run(args), 1)
      }
      assert.equal(f.attempts.length, 1)
      assert.equal(f.attempts[0]!.session_token, originalToken)
      assert.equal(await readFile(f.store, 'utf8'), f.before)
      assert.equal((await getSession(primary))?.certPinSha256, 'primary-pin')
      assert.equal((await getSession(alternate))?.certPinSha256, 'alternate-pin')
    })
  }
}

test('interactive retry prompts without deleting the old session, and replaces it only on success', async t => {
  const f = await setup(t, ['rejected', 'success'])
  let prompts = 0
  f.prompt(async () => {
    prompts++
    assert.equal(await readFile(f.store, 'utf8'), f.before)
    return 'ABC123'
  })
  const { relay } = await connectAndAuth(f.args('shell', true))
  t.after(() => relay.kill())
  assert.equal(prompts, 1)
  assert.equal(f.attempts.length, 2)
  assert.equal(f.attempts[1]!.pairing_code, 'ABC123')
  assert.equal(f.attempts[1]!.session_token, undefined)
  // Auth-success observers persist asynchronously.
  const deadline = Date.now() + 2000
  while ((await getSession(primary))?.token !== replacementToken) {
    assert.ok(Date.now() < deadline, 'new session was not persisted')
    await new Promise(resolve => setTimeout(resolve, 10))
  }
  const session = await getSession(alternate)
  assert.equal(session?.token, replacementToken)
  assert.equal(session?.ttlExpiresAt, 2000000000)
  assert.deepEqual(session?.grants, { terminal: 2000000000 })
  assert.equal(session?.certPinSha256, 'alternate-pin')
  assert.equal(session?.toolsConsented, true)
})

test('a failed replacement pairing leaves the original host intact', async t => {
  const f = await setup(t, ['rejected', 'rejected'])
  f.prompt(async () => 'ABC123')
  await assert.rejects(connectAndAuth(f.args('shell', true)), /invalid session token/)
  assert.equal(f.attempts.length, 2)
  assert.equal(f.attempts[1]!.pairing_code, 'ABC123')
  assert.equal(await readFile(f.store, 'utf8'), f.before)
})

test('canceling a replacement prompt leaves the original host intact', async t => {
  const f = await setup(t, ['rejected'])
  f.prompt(async () => { throw new Error('pairing cancelled') })
  await assert.rejects(connectAndAuth(f.args('shell', true)), /pairing cancelled/)
  assert.equal(f.attempts.length, 1)
  assert.equal(await readFile(f.store, 'utf8'), f.before)
})

test('skipping a saved session never falls back to that same token in non-interactive mode', async t => {
  const f = await setup(t)
  await assert.rejects(resolveCredentials(alternate, { skipStoredSession: true, nonInteractive: true }), /No credentials/)
  assert.equal(await readFile(f.store, 'utf8'), f.before)
  assert.deepEqual(await resolveCredentials(alternate, { nonInteractive: true }), { sessionToken: originalToken })
})

test('explicitly forgetting a host still deletes its session and alternate routes', async t => {
  const f = await setup(t)
  assert.equal(await hostsCommand({ command: 'hosts', positional: ['forget', primary], flags: { yes: true, json: true } }), 0)
  assert.equal(await getSession(primary), null)
  assert.equal(await getSession(alternate), null)
  assert.notEqual(await readFile(f.store, 'utf8'), f.before)
})

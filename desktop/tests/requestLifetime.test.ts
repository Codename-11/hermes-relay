import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { mkdtemp, rm } from 'node:fs/promises'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'
import test, { type TestContext } from 'node:test'

import { RelayTransport } from '../src/transport/RelayTransport.js'
import { DesktopToolRouter, type ToolHandler, type ToolResponsePayload } from '../src/tools/router.js'

type ChannelListener = (type: string, payload: Record<string, unknown>) => void

// Only the transport boundary is fake: commands run through the real router,
// including its timeout, cancellation, generation and response handling.
class FakeTransport extends EventEmitter {
  private listener: ChannelListener | null = null
  readonly responses: ToolResponsePayload[] = []

  onChannel(channel: string, listener: ChannelListener | null): void {
    assert.equal(channel, 'desktop')
    this.listener = listener
  }

  sendChannel(channel: string, type: string, payload: ToolResponsePayload): void {
    assert.equal(channel, 'desktop')
    if (type === 'desktop.response') this.responses.push(payload)
  }

  command(requestId: string, tool = 'desktop_read_file'): void {
    this.receive('desktop.command', { request_id: requestId, tool, args: {} })
  }

  receive(type: string, payload: Record<string, unknown>): void {
    assert.ok(this.listener, 'transport must be attached')
    this.listener(type, payload)
  }
}

type SocketListener = (event: { code: number; reason: string } | { data: string } | { message?: string } | undefined) => void

// Native RelayTransport owns close/reconnect/drain behavior. This fixture
// replaces only its wire socket, so no Relay server or network is needed.
class FakeWebSocket {
  readyState = 0
  readonly sent: string[] = []
  private readonly listeners = new Map<string, SocketListener[]>()

  addEventListener(type: string, listener: SocketListener): void {
    const listeners = this.listeners.get(type) ?? []
    listeners.push(listener)
    this.listeners.set(type, listeners)
  }

  send(data: string): void { this.sent.push(data) }
  close(): void { this.readyState = 3 }
  open(): void { this.readyState = 1; this.emit('open', undefined) }

  authenticate(): void {
    this.receive('system', 'auth.ok', { session_token: 'fixture-session-token', server_version: 'test' })
  }

  command(requestId: string): void {
    this.receive('desktop', 'desktop.command', { request_id: requestId, tool: 'desktop_read_file', args: {} })
  }

  drop(): void {
    this.readyState = 3
    this.emit('close', { code: 1006, reason: 'fixture socket interrupted' })
  }

  responses(): ToolResponsePayload[] {
    return this.sent.map(frame => JSON.parse(frame) as {
      type: string; payload: ToolResponsePayload
    }).filter(frame => frame.type === 'desktop.response').map(frame => frame.payload)
  }

  private receive(channel: string, type: string, payload: Record<string, unknown>): void {
    this.emit('message', { data: JSON.stringify({ channel, type, payload }) })
  }

  private emit(type: string, event: Parameters<SocketListener>[0]): void {
    for (const listener of this.listeners.get(type) ?? []) listener(event)
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

async function nextTurn(): Promise<void> {
  await new Promise<void>(resolve => setImmediate(resolve))
}

async function harness(t: TestContext, handlers: Record<string, ToolHandler>) {
  const directory = await mkdtemp(join(tmpdir(), 'hermes-request-lifetime-'))
  const previousHome = process.env.HOME
  const previousUserProfile = process.env.USERPROFILE
  process.env.HOME = directory
  process.env.USERPROFILE = directory
  assert.equal(homedir(), directory, 'router state must stay inside the owned fixture')
  const transport = new FakeTransport()
  const router = new DesktopToolRouter({ handlers, consentGranted: true, interactive: false })
  router.attach(transport as unknown as RelayTransport)
  t.after(async () => {
    router.detach()
    t.mock.timers.reset()
    await nextTurn()
    if (previousHome === undefined) delete process.env.HOME
    else process.env.HOME = previousHome
    if (previousUserProfile === undefined) delete process.env.USERPROFILE
    else process.env.USERPROFILE = previousUserProfile
    await rm(directory, { recursive: true, force: true, maxRetries: 5, retryDelay: 10 })
  })
  return { router, transport }
}

test('ordinary requests can finish after the former 30-second router deadline', async t => {
  const pending = deferred<{ contents: string }>()
  let signal!: AbortSignal
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] })
  const { transport } = await harness(t, {
    desktop_read_file: async (_args, ctx) => { signal = ctx.abortSignal; return pending.promise }
  })
  transport.command('slow-read')
  t.mock.timers.tick(60_000)
  await nextTurn()
  assert.equal(signal.aborted, false)
  assert.deepEqual(transport.responses, [])

  pending.resolve({ contents: 'completed after 60 seconds' })
  await nextTurn()
  assert.deepEqual(transport.responses, [{
    request_id: 'slow-read', ok: true, result: { contents: 'completed after 60 seconds' }
  }])
})

test('the 170-second deadline replies once even when the handler ignores abort and finishes late', async t => {
  const pending = deferred<string>()
  let signal!: AbortSignal
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] })
  const { transport } = await harness(t, {
    desktop_read_file: async (_args, ctx) => { signal = ctx.abortSignal; return pending.promise }
  })
  transport.command('timed-out')
  t.mock.timers.tick(169_999)
  await nextTurn()
  assert.equal(signal.aborted, false)
  assert.deepEqual(transport.responses, [])

  t.mock.timers.tick(1)
  await nextTurn()
  assert.equal(signal.aborted, true)
  assert.equal(transport.responses.length, 1)
  assert.equal(transport.responses[0]?.request_id, 'timed-out')
  assert.equal(transport.responses[0]?.ok, false)
  if (transport.responses[0]?.ok === false) assert.match(transport.responses[0].error, /abort|timeout|timed out/i)

  pending.resolve('must not become a late success')
  await nextTurn()
  assert.equal(transport.responses.length, 1)
})

test('device requests retain their existing 180-second deadline', async t => {
  const pending = deferred<string>()
  let signal!: AbortSignal
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] })
  const { transport } = await harness(t, {
    desktop_adb_devices: async (_args, ctx) => { signal = ctx.abortSignal; return pending.promise }
  })
  transport.command('device-request', 'desktop_adb_devices')
  t.mock.timers.tick(170_000)
  await nextTurn()
  assert.equal(signal.aborted, false)
  assert.deepEqual(transport.responses, [])

  t.mock.timers.tick(10_000)
  await nextTurn()
  assert.equal(signal.aborted, true)
  assert.equal(transport.responses.length, 1)
  assert.equal(transport.responses[0]?.ok, false)
  pending.resolve('late device result')
  await nextTurn()
  assert.equal(transport.responses.length, 1)
})

test('desktop.cancel cancels only its matching request and leaves its neighbor running', async t => {
  const first = deferred<string>()
  const neighbor = deferred<string>()
  const signals: AbortSignal[] = []
  const { transport } = await harness(t, {
    desktop_read_file: async (_args, ctx) => {
      signals.push(ctx.abortSignal)
      return signals.length === 1 ? first.promise : neighbor.promise
    }
  })
  transport.command('cancel-me')
  transport.command('neighbor')
  transport.receive('desktop.cancel', { request_id: 'not-pending' })
  assert.equal(signals[0]?.aborted, false)
  assert.equal(signals[1]?.aborted, false)

  transport.receive('desktop.cancel', { request_id: 'cancel-me' })
  await nextTurn()
  assert.equal(signals[0]?.aborted, true)
  assert.equal(signals[1]?.aborted, false)
  assert.equal(transport.responses.length, 1)
  assert.equal(transport.responses[0]?.request_id, 'cancel-me')
  assert.equal(transport.responses[0]?.ok, false)

  first.resolve('late canceled result')
  neighbor.resolve('neighbor completed')
  await nextTurn()
  assert.deepEqual(transport.responses.filter(response => response.request_id === 'neighbor'), [{
    request_id: 'neighbor', ok: true, result: 'neighbor completed'
  }])
  assert.equal(transport.responses.filter(response => response.request_id === 'cancel-me').length, 1)
})

test('canceling a request on one transport cannot cancel the same ID on another transport', async t => {
  const local = deferred<string>()
  const foreign = deferred<string>()
  let localSignal!: AbortSignal
  let foreignSignal!: AbortSignal
  const { transport } = await harness(t, {
    desktop_read_file: async (_args, ctx) => { localSignal = ctx.abortSignal; return local.promise }
  })
  const otherTransport = new FakeTransport()
  const otherRouter = new DesktopToolRouter({
    consentGranted: true,
    interactive: false,
    handlers: {
      desktop_read_file: async (_args, ctx) => { foreignSignal = ctx.abortSignal; return foreign.promise }
    }
  })
  otherRouter.attach(otherTransport as unknown as RelayTransport)
  try {
    transport.command('same-request-id')
    otherTransport.command('same-request-id')
    transport.receive('desktop.cancel', { request_id: 'same-request-id' })
    await nextTurn()
    assert.equal(localSignal.aborted, true)
    assert.equal(foreignSignal.aborted, false)
    assert.equal(transport.responses.length, 1)
    assert.equal(transport.responses[0]?.ok, false)
    assert.deepEqual(otherTransport.responses, [])

    local.resolve('late canceled result')
    foreign.resolve('other transport completed')
    await nextTurn()
    assert.equal(transport.responses.length, 1)
    assert.deepEqual(otherTransport.responses, [{
      request_id: 'same-request-id', ok: true, result: 'other transport completed'
    }])
  } finally {
    otherRouter.detach()
  }
})

test('detach aborts pending work and a reattached transport never receives its old response', async t => {
  const old = deferred<string>()
  let signal!: AbortSignal
  let calls = 0
  const { router, transport } = await harness(t, {
    desktop_read_file: async (_args, ctx) => {
      if (++calls === 1) { signal = ctx.abortSignal; return old.promise }
      return 'new attachment completed'
    }
  })
  transport.command('old-attachment')
  router.detach()
  assert.equal(signal.aborted, true)
  const replacement = new FakeTransport()
  router.attach(replacement as unknown as RelayTransport)
  replacement.command('new-attachment')
  old.resolve('old attachment result')
  await nextTurn()

  assert.deepEqual(transport.responses, [])
  assert.deepEqual(replacement.responses, [{
    request_id: 'new-attachment', ok: true, result: 'new attachment completed'
  }])
})

for (const event of ['disconnected']) {
  test(`${event} aborts pending work without leaking its completion into a later connection`, async t => {
    const old = deferred<string>()
    let signal!: AbortSignal
    let calls = 0
    const { transport } = await harness(t, {
      desktop_read_file: async (_args, ctx) => {
        if (++calls === 1) { signal = ctx.abortSignal; return old.promise }
        return 'later connection completed'
      }
    })
    transport.command('before-disconnect')
    transport.emit(event)
    assert.equal(signal.aborted, true)
    await nextTurn()
    assert.deepEqual(transport.responses, [])

    transport.command('after-disconnect')
    old.reject(new Error('late handler failure'))
    await nextTurn()
    assert.deepEqual(transport.responses, [{
      request_id: 'after-disconnect', ok: true, result: 'later connection completed'
    }])
  })
}

for (const autoReconnect of [false, true]) {
  test(`native socket close aborts router work before drain (autoReconnect=${autoReconnect})`, async t => {
    const old = deferred<string>()
    let signal!: AbortSignal
    let calls = 0
    t.mock.timers.enable({ apis: ['setTimeout', 'setInterval'] })
    const { router } = await harness(t, {
      desktop_read_file: async (_args, ctx) => {
        if (++calls === 1) { signal = ctx.abortSignal; return old.promise }
        return 'replacement socket completed'
      }
    })
    const sockets: FakeWebSocket[] = []
    const makeTransport = () => new RelayTransport({
      url: 'ws://relay.example.test:8767',
      sessionToken: 'fixture-session-token',
      autoReconnect,
      emitWorkspaceEnvelope: false,
      wsFactory: () => {
        const socket = new FakeWebSocket()
        sockets.push(socket)
        return socket
      }
    })
    const relay = makeTransport()
    let currentRelay = relay
    let exitEvents = 0
    relay.on('exit', () => { exitEvents += 1 })
    router.detach()
    router.attach(relay)
    try {
      relay.start()
      assert.equal(sockets.length, 1)
      const original = sockets[0]!
      original.open()
      original.authenticate()
      assert.equal((await relay.whenAuthResolved()).ok, true)
      original.command('before-native-close')
      assert.equal(signal.aborted, false)

      // Intentionally never call drain(): --no-voice daemon consumers do not
      // drain the stream-event queue. Cancellation must not depend on exit.
      original.drop()
      assert.equal(signal.aborted, true, 'socket close must abort synchronously before drain')
      assert.equal(exitEvents, 0, 'the stream exit is still buffered without drain')
      await nextTurn()
      assert.deepEqual(original.responses(), [])

      if (autoReconnect) {
        assert.equal(relay.getState(), 'reconnecting')
        t.mock.timers.tick(1_000)
      } else {
        router.detach()
        currentRelay = makeTransport()
        router.attach(currentRelay)
        currentRelay.start()
      }
      assert.equal(sockets.length, 2)
      const replacement = sockets[1]!
      replacement.open()
      replacement.authenticate()
      assert.equal(currentRelay.getState(), 'connected')
      replacement.command('after-native-close')
      old.resolve('must not reply on a new socket')
      await nextTurn()

      assert.deepEqual(original.responses(), [])
      assert.deepEqual(replacement.responses(), [{
        request_id: 'after-native-close', ok: true, result: 'replacement socket completed'
      }])
      assert.equal(exitEvents, 0)
    } finally {
      router.detach()
      currentRelay.kill()
      if (currentRelay !== relay) relay.kill()
    }
  })
}

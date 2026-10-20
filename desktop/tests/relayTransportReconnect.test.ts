import assert from 'node:assert/strict'
import test, { type TestContext } from 'node:test'

import { RelayTransport } from '../src/transport/RelayTransport.js'

type Listener = (event: { code: number; reason: string } | { data: string } | { message?: string } | undefined) => void

class FakeWebSocket {
  readyState = 0
  sent: string[] = []
  private listeners = new Map<string, Listener[]>()

  addEventListener(type: string, listener: Listener): void {
    const listeners = this.listeners.get(type) ?? []
    listeners.push(listener)
    this.listeners.set(type, listeners)
  }

  send(data: string): void {
    this.sent.push(data)
  }

  close(): void {
    this.readyState = 3
  }

  open(): void {
    this.readyState = 1
    this.emit('open', undefined)
  }

  frame(channel: string, type: string, payload: Record<string, unknown>): void {
    this.emit('message', { data: JSON.stringify({ channel, type, payload }) })
  }

  authOk(): void {
    this.emit('message', {
      data: JSON.stringify({
        channel: 'system',
        type: 'auth.ok',
        payload: { session_token: 'session-token', server_version: 'test' }
      })
    })
  }

  fail(message: string): void {
    this.emit('error', { message })
  }

  drop(code: number, reason: string): void {
    this.readyState = 3
    this.emit('close', { code, reason })
  }

  private emit(type: string, event: Parameters<Listener>[0]): void {
    for (const listener of this.listeners.get(type) ?? []) listener(event)
  }
}

async function waitFor(predicate: () => boolean, timeoutMs = 2_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error('timed out waiting for reconnect state')
    await new Promise(resolve => setTimeout(resolve, 10))
  }
}

test('authenticated transport keeps retrying when the first reconnect socket also drops', async t => {
  const sockets: FakeWebSocket[] = []
  const attempts: number[] = []
  const relay = new RelayTransport({
    url: 'ws://relay.example.test:8767',
    sessionToken: 'session-token',
    autoReconnect: true,
    emitWorkspaceEnvelope: false,
    wsFactory: () => {
      const socket = new FakeWebSocket()
      sockets.push(socket)
      return socket
    }
  })
  t.after(() => relay.kill())
  relay.on('reconnecting', (info: { attempt: number }) => attempts.push(info.attempt))

  relay.start()
  await waitFor(() => sockets.length === 1)
  sockets[0]!.open()
  sockets[0]!.authOk()
  assert.equal((await relay.whenAuthResolved()).ok, true)

  sockets[0]!.drop(1006, 'first socket interrupted')
  await waitFor(() => sockets.length === 2)
  sockets[1]!.open()
  sockets[1]!.drop(1006, 'replacement socket interrupted')

  await waitFor(() => attempts.length === 2)
  assert.deepEqual(attempts, [1, 2])
  assert.equal(relay.getState(), 'reconnecting')
})


function fixture(t: TestContext, overrides: Partial<ConstructorParameters<typeof RelayTransport>[0]> = {}) {
  const sockets: FakeWebSocket[] = []
  const relay = new RelayTransport({
    url: 'ws://relay.example.test:8767', sessionToken: 'session-token',
    autoReconnect: true, emitWorkspaceEnvelope: false,
    wsFactory: () => { const socket = new FakeWebSocket(); sockets.push(socket); return socket },
    ...overrides
  })
  t.after(() => relay.kill())
  return { relay, sockets }
}

test('cold start retries transient failures, resolves initial auth, and honors exactly one allowed retry', async t => {
  let calls = 0
  const socket = new FakeWebSocket()
  const { relay } = fixture(t, {
    maxReconnectAttempts: 1,
    wsFactory: () => { if (++calls === 1) throw new Error('network unreachable'); return socket }
  })
  const reasons: string[] = []
  let restored = 0
  relay.on('reconnecting', info => reasons.push(info.reason))
  relay.on('reconnected', () => restored++)
  const auth = relay.whenAuthResolved()
  relay.start()
  assert.equal(relay.getState(), 'reconnecting')
  await waitFor(() => calls === 2)
  socket.open(); socket.authOk()
  assert.equal((await auth).ok, true)
  assert.equal(restored, 0, 'first successful auth must follow the initial startup path')
  assert.deepEqual(reasons, ['network unreachable'])
  assert.equal(relay.getState(), 'connected')
})

test('startup retry budget exhaustion settles auth without another attempt', async t => {
  let calls = 0
  const { relay } = fixture(t, {
    maxReconnectAttempts: 1,
    wsFactory: () => { calls++; throw new Error('network unreachable') }
  })
  relay.start()
  assert.deepEqual(await relay.whenAuthResolved(), { ok: false, reason: 'network unreachable' })
  assert.equal(calls, 2)
  assert.equal(relay.getState(), 'idle')
})

test('late events from a replaced socket cannot disconnect or authenticate the replacement', async t => {
  const { relay, sockets } = fixture(t)
  relay.start()
  const old = sockets[0]!
  old.open(); old.authOk(); old.drop(1006, 'network gap')
  assert.equal(relay.getAuthInfo(), null)
  const auth = relay.whenAuthResolved()
  await waitFor(() => sockets.length === 2)
  old.authOk()
  old.frame('system', 'auth.fail', { reason: 'obsolete rejection' })
  old.drop(1006, 'delayed close')
  assert.equal(relay.getState(), 'connecting')
  const current = sockets[1]!
  assert.equal(current.readyState, 0)
  current.open(); current.authOk()
  assert.equal((await auth).ok, true)
  old.drop(1006, 'another delayed close')
  assert.equal(relay.getState(), 'connected')
  assert.equal(current.readyState, 1)
})

test('auth rejection is terminal even when startup retry is enabled', async t => {
  const { relay, sockets } = fixture(t)
  let retries = 0
  relay.on('reconnecting', () => retries++)
  relay.start(); sockets[0]!.open()
  sockets[0]!.frame('system', 'auth.fail', { reason: 'invalid session token' })
  assert.deepEqual(await relay.whenAuthResolved(), { ok: false, reason: 'invalid session token' })
  assert.equal(retries, 0)
})

test('one-shot commands and unconsumed pairing codes do not retry', async t => {
  for (const config of [{ autoReconnect: false }, { sessionToken: undefined, pairingCode: 'PAIRME' }]) {
    const { relay, sockets } = fixture(t, config)
    relay.start(); sockets[0]!.drop(1006, 'startup interrupted')
    assert.equal((await relay.whenAuthResolved()).ok, false)
    assert.equal(relay.getState(), 'idle')
  }
})

test('a paired session reconnects with its minted token instead of replaying the pairing code', async t => {
  const { relay, sockets } = fixture(t, { sessionToken: undefined, pairingCode: 'PAIRME' })
  relay.start(); sockets[0]!.open(); sockets[0]!.authOk(); sockets[0]!.drop(1006, 'gap')
  await waitFor(() => sockets.length === 2)
  sockets[1]!.open()
  const auth = JSON.parse(sockets[1]!.sent[0]!)
  assert.equal(auth.payload.session_token, 'session-token')
  assert.equal(auth.payload.pairing_code, undefined)
})

test('kill during startup backoff cancels retry and resolves the pending authentication', async t => {
  const { relay, sockets } = fixture(t)
  relay.start(); sockets[0]!.drop(1006, 'startup interrupted')
  relay.kill()
  assert.deepEqual(await relay.whenAuthResolved(), { ok: false, reason: 'kill' })
  await new Promise(resolve => setTimeout(resolve, 1100))
  assert.equal(sockets.length, 1)
})

test('credential gate is checked again before retrying', async t => {
  let allowed = true
  const { relay, sockets } = fixture(t, { reconnectGate: () => allowed })
  relay.start(); sockets[0]!.drop(1006, 'startup interrupted')
  allowed = false
  assert.deepEqual(await relay.whenAuthResolved(), { ok: false, reason: 'reconnect gate rejected' })
  assert.equal(sockets.length, 1)
})

test('a late TLS completion after kill cannot open a WebSocket', async t => {
  const { relay, sockets } = fixture(t, { url: 'wss://relay.example.test' })
  let finish!: () => void
  // Hold the certificate phase to reproduce an async completion after cancellation.
  Object.defineProperty(relay, 'verifyOrCapturePin', { value: () => new Promise<void>(resolve => { finish = resolve }) })
  relay.start(); relay.kill(); finish()
  await new Promise<void>(resolve => setImmediate(resolve))
  assert.equal(sockets.length, 0)
  assert.equal(relay.getState(), 'idle')
})

test('TUI errors reject chat requests while desktop commands remain connected', async t => {
  const { relay, sockets } = fixture(t)
  let commands = 0
  relay.onChannel('desktop', () => commands++)
  relay.start(); const socket = sockets[0]!; socket.open(); socket.authOk()
  const pending = relay.request('session.list')
  const rejected = assert.rejects(pending, /gateway owns this home/)
  socket.frame('tui', 'tui.error', { message: 'gateway owns this home' })
  await rejected
  await assert.rejects(relay.request('session.list'), /gateway owns this home/)
  socket.frame('desktop', 'desktop.command', { request_id: 'test-command' })
  assert.equal(commands, 1)
  assert.equal(relay.getState(), 'connected')
  assert.equal(socket.readyState, 1)
  socket.frame('tui', 'tui.attached', {})
  const recovered = relay.request('session.list')
  const request = JSON.parse(socket.sent.at(-1)!)
  socket.frame('tui', 'tui.rpc.response', { id: request.payload.id, result: [] })
  assert.deepEqual(await recovered, [])
})

test('desktop-only transport never attaches TUI and cannot silently queue chat RPCs', async t => {
  const { relay, sockets } = fixture(t, { attachTui: false })
  relay.start(); sockets[0]!.open(); sockets[0]!.authOk()
  assert.equal(sockets[0]!.sent.some(raw => JSON.parse(raw).type === 'tui.attach'), false)
  await assert.rejects(relay.request('session.list'), /TUI channel is disabled/)
})

async function tlsFixture(t: TestContext, stallFirst = false) {
  const [{ createServer: createTcpServer }, { createServer: createTlsServer }, { readFile, mkdtemp, rm }, { tmpdir }, { join }, { extractSpkiSha256 }, { X509Certificate }, { setStorePath }] = await Promise.all([
    import('node:net'), import('node:tls'), import('node:fs/promises'), import('node:os'),
    import('node:path'), import('../src/certPin.js'), import('node:crypto'), import('../src/remoteSessions.js')
  ])
  const cert = await readFile(new URL('./fixtures/relay-tls/cert.pem', import.meta.url))
  const key = await readFile(new URL('./fixtures/relay-tls/key.pem', import.meta.url))
  const dir = await mkdtemp(join(tmpdir(), 'relay-tls-test-'))
  setStorePath(join(dir, 'sessions.json'))
  const clients = new Set<import('node:net').Socket>()
  let connections = 0
  const secure = createTlsServer({ cert, key }, socket => socket.end())
  secure.on('tlsClientError', () => {})
  const server = createTcpServer(socket => {
    connections++
    clients.add(socket)
    socket.on('close', () => clients.delete(socket))
    socket.on('error', () => {})
    if (!stallFirst || connections > 1) secure.emit('connection', socket)
  })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(async () => {
    for (const socket of clients) socket.destroy()
    await new Promise<void>(resolve => server.close(() => resolve()))
    setStorePath(null)
    await rm(dir, { recursive: true, force: true })
  })
  const port = (server.address() as import('node:net').AddressInfo).port
  return { url: `wss://127.0.0.1:${port}`, pin: extractSpkiSha256(new X509Certificate(cert).raw) }
}

test('a real stalled TLS handshake retries and authenticates after the path recovers', { timeout: 20_000 }, async t => {
  const { url, pin } = await tlsFixture(t, true)
  const socket = new FakeWebSocket()
  const reasons: string[] = []
  const { relay } = fixture(t, {
    url, expectedCertPin: pin, maxReconnectAttempts: 1,
    wsFactory: () => {
      queueMicrotask(() => { socket.open(); socket.authOk() })
      return socket
    }
  })
  relay.on('reconnecting', info => reasons.push(info.reason))
  relay.start()
  assert.equal((await relay.whenAuthResolved()).ok, true)
  assert.equal(reasons.length, 1)
  assert.match(reasons[0]!, /TLS probe.*timed out after 10000ms/)
})

test('a real certificate pin mismatch remains terminal and never opens the WebSocket', async t => {
  const { url } = await tlsFixture(t)
  const { relay, sockets } = fixture(t, { url, expectedCertPin: `sha256/${'A'.repeat(43)}=` })
  let retries = 0
  relay.on('reconnecting', () => retries++)
  relay.start()
  const outcome = await relay.whenAuthResolved()
  assert.equal(outcome.ok, false)
  assert.match(!outcome.ok ? outcome.reason : '', /cert pin mismatch/)
  assert.equal(retries, 0)
  assert.equal(sockets.length, 0)
})


test('WebSocket error without close retires the socket and starts exactly one retry', async t => {
  const { relay, sockets } = fixture(t)
  const reasons: string[] = []
  relay.on('reconnecting', info => reasons.push(info.reason))
  relay.start(); sockets[0]!.open(); sockets[0]!.authOk()
  sockets[0]!.fail('upgrade or transport failed')
  assert.equal(relay.getState(), 'reconnecting')
  assert.equal(sockets[0]!.readyState, 3)
  sockets[0]!.drop(1006, 'late close after error')
  assert.deepEqual(reasons, ['upgrade or transport failed'])
  await waitFor(() => sockets.length === 2)
  sockets[1]!.open(); sockets[1]!.authOk()
  assert.equal(relay.getState(), 'connected')
})

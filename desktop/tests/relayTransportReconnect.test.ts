import assert from 'node:assert/strict'
import test from 'node:test'

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

  authOk(): void {
    this.emit('message', {
      data: JSON.stringify({
        channel: 'system',
        type: 'auth.ok',
        payload: { session_token: 'session-token', server_version: 'test' }
      })
    })
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

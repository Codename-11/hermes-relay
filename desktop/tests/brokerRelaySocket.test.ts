import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import test from 'node:test'
import type { TLSSocket } from 'node:tls'

import { BrokerRelaySocket } from '../src/transport/BrokerRelaySocket.js'

class FakeTlsSocket extends EventEmitter {
  write(): boolean { return true }
  end(): this { return this }
  destroy(): this { return this }
}

test('late open listener fires after the broker socket already opened', async () => {
  const socket = new BrokerRelaySocket(new FakeTlsSocket() as unknown as TLSSocket)
  socket.opened()
  await new Promise<void>(resolve => queueMicrotask(resolve))

  let opens = 0
  socket.addEventListener('open', () => { opens += 1 })
  await new Promise<void>(resolve => queueMicrotask(resolve))

  assert.equal(opens, 1)
})

test('listener attached before queued open fires exactly once', async () => {
  const socket = new BrokerRelaySocket(new FakeTlsSocket() as unknown as TLSSocket)
  let opens = 0
  socket.addEventListener('open', () => { opens += 1 })
  socket.opened()
  await new Promise<void>(resolve => queueMicrotask(resolve))

  assert.equal(opens, 1)
})

import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import test from 'node:test'

import type { GatewayEvent } from '../src/gatewayTypes.js'
import { SessionResumeDrain } from '../src/resumeDrain.js'

function event(type: string, sessionId: string): GatewayEvent {
  return { payload: {}, session_id: sessionId, type }
}

test('resume drain captures completion emitted before resume acknowledgement', async () => {
  const source = new EventEmitter()
  const seen: string[] = []
  const drain = new SessionResumeDrain(source, (item) => seen.push(item.type), 1_000)
  source.emit('event', event('message.delta', 'live-1'))
  source.emit('event', event('message.complete', 'live-1'))

  await drain.activate('live-1', 'running')

  assert.deepEqual(seen, ['message.delta', 'message.complete'])
  assert.equal(source.listenerCount('event'), 0)
})

test('running and queued resume drains two terminal turn boundaries', async () => {
  const source = new EventEmitter()
  const drain = new SessionResumeDrain(source, undefined, 1_000)
  let settled = false
  const finished = drain.activate('live-1', 'running-and-queued').then(() => { settled = true })

  source.emit('event', event('message.complete', 'live-1'))
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(settled, false)
  source.emit('event', event('message.complete', 'foreign-live'))
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(settled, false)
  source.emit('event', event('message.complete', 'live-1'))

  await finished
  assert.equal(source.listenerCount('event'), 0)
})

test('queued-only resume drains before releasing the caller', async () => {
  const source = new EventEmitter()
  const drain = new SessionResumeDrain(source, undefined, 1_000)
  const finished = drain.activate('live-1', 'queued')
  source.emit('event', event('message.start', 'live-1'))
  source.emit('event', event('message.complete', 'live-1'))
  await finished
})

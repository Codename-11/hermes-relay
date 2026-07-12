import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import type { GatewayEvent } from '../src/gatewayTypes.js'
import { runRelayChatTurn } from '../src/relayChat.js'
import type { CliRenderer } from '../src/renderer.js'
import type { RelayTransport } from '../src/transport/RelayTransport.js'
import { TypedStreamRenderer } from '../src/typedStream.js'

test('golden typed stream v1 maps to CLI renderer lifecycle', async () => {
  const fixtureUrl = new URL('../../docs/fixtures/typed-stream-v1.jsonl', import.meta.url)
  const lines = (await readFile(fixtureUrl, 'utf8')).trim().split(/\r?\n/)
  const renderer = new TypedStreamRenderer()
  const rendered: GatewayEvent[] = []
  let terminalAt: number | null = null

  for (const [index, line] of lines.entries()) {
    const result = renderer.accept(JSON.parse(line) as Record<string, unknown>)
    assert.ok(result, `fixture line ${index + 1} should be typed stream v1`)
    rendered.push(...result.events)
    if (result.terminal) {
      terminalAt = index + 1
      break
    }
  }

  assert.equal(terminalAt, 10, 'run.completed is the first terminal event')
  assert.deepEqual(
    rendered.map(event => event.type),
    [
      'status.update',
      'message.start',
      'status.update',
      'tool.start',
      'tool.complete',
      'artifact.created',
      'message.delta',
      'message.complete'
    ]
  )

  const toolStart = rendered.find(event => event.type === 'tool.start')
  assert.equal(toolStart?.type === 'tool.start' ? toolStart.payload.name : null, 'terminal')
  const artifact = rendered.find(event => event.type === 'artifact.created')
  assert.equal(
    artifact?.type === 'artifact.created' ? artifact.payload.title : null,
    'terminal-log.txt'
  )
  const delta = rendered.find(event => event.type === 'message.delta')
  assert.equal(
    delta?.type === 'message.delta' ? delta.payload?.text : null,
    'Command completed successfully.'
  )
})

test('typed stream renderer drops duplicates and reports sequence gaps', () => {
  const renderer = new TypedStreamRenderer()
  const base = {
    type: 'stream.event',
    schema_version: 1,
    session_id: 'sess',
    run_id: 'run',
    event: 'assistant.delta',
    payload: { delta: 'hello' }
  } as const

  assert.equal(renderer.accept({ ...base, seq: 1 })?.events.length, 1)
  assert.equal(renderer.accept({ ...base, seq: 1 })?.events.length, 0)
  const gap = renderer.accept({ ...base, seq: 3 })
  assert.deepEqual(gap?.events.map(event => event.type), ['status.update', 'message.delta'])
})

test('typed stream renderer rejects incompatible schemas', () => {
  const renderer = new TypedStreamRenderer()
  assert.equal(
    renderer.accept({
      type: 'stream.event',
      schema_version: 2,
      session_id: 'sess',
      seq: 1,
      event: 'done',
      payload: {}
    }),
    null
  )
})

test('Relay chat turn sends chat.send and resolves on typed run completion', async () => {
  let listener: ((type: string, payload: Record<string, unknown>) => void) | null = null
  const sent: Array<{ channel: string; type: string; payload: Record<string, unknown> }> = []
  const rendered: GatewayEvent[] = []
  const sessions: string[] = []

  const relay = {
    onChannel(
      channel: string,
      next: ((type: string, payload: Record<string, unknown>) => void) | null
    ) {
      assert.equal(channel, 'chat')
      listener = next
    },
    sendChannel(channel: string, type: string, payload: Record<string, unknown>) {
      sent.push({ channel, type, payload })
    },
    kill() {}
  } as unknown as RelayTransport
  const cliRenderer = {
    handle(event: GatewayEvent) {
      rendered.push(event)
    }
  } as unknown as CliRenderer

  const turn = runRelayChatTurn(relay, 'hello', cliRenderer, {
    profile: 'research',
    onSessionId: sessionId => sessions.push(sessionId)
  })

  assert.deepEqual(sent, [
    {
      channel: 'chat',
      type: 'chat.send',
      payload: { message: 'hello', profile: 'research' }
    }
  ])
  assert.ok(listener)
  const emit = listener as (type: string, payload: Record<string, unknown>) => void
  emit('chat.session', { session_id: 'sess-created' })
  emit('stream.event', {
    type: 'stream.event',
    schema_version: 1,
    session_id: 'sess-created',
    run_id: 'run-created',
    seq: 1,
    event: 'assistant.delta',
    payload: { delta: 'hi' }
  })
  emit('stream.event', {
    type: 'stream.event',
    schema_version: 1,
    session_id: 'sess-created',
    run_id: 'run-created',
    seq: 2,
    event: 'run.completed',
    payload: { completed: true }
  })

  await turn.promise
  assert.deepEqual(sessions, ['sess-created', 'sess-created', 'sess-created'])
  assert.deepEqual(rendered.map(event => event.type), ['message.delta'])
  assert.equal(listener, null, 'chat listener is detached after terminal completion')
})

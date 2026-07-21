import type { GatewayEvent } from './gatewayTypes.js'

export interface TypedStreamEvent {
  event: string
  payload: Record<string, unknown>
  run_id?: string | null
  schema_version: number
  seq: number
  session_id: string
  ts?: string
  type: 'stream.event'
}

export interface TypedStreamRenderResult {
  events: GatewayEvent[]
  sessionId: string
  terminal: boolean
}

function text(payload: Record<string, unknown>, ...keys: string[]): string | undefined {
  for (const key of keys) {
    const value = payload[key]
    if (typeof value === 'string' && value.length > 0) {
      return value
    }
  }
  return undefined
}

export function parseTypedStreamEvent(raw: Record<string, unknown>): TypedStreamEvent | null {
  if (raw.type !== 'stream.event' || raw.schema_version !== 1) {
    return null
  }
  if (
    typeof raw.event !== 'string' ||
    typeof raw.session_id !== 'string' ||
    typeof raw.seq !== 'number' ||
    !Number.isInteger(raw.seq)
  ) {
    return null
  }
  const payload =
    raw.payload && typeof raw.payload === 'object' && !Array.isArray(raw.payload)
      ? (raw.payload as Record<string, unknown>)
      : {}
  return {
    type: 'stream.event',
    schema_version: 1,
    session_id: raw.session_id,
    run_id: typeof raw.run_id === 'string' || raw.run_id === null ? raw.run_id : undefined,
    seq: raw.seq,
    event: raw.event,
    ts: typeof raw.ts === 'string' ? raw.ts : undefined,
    payload
  }
}

/** Convert Relay typed stream v1 into the established CLI renderer model.
 * Sequence tracking makes reconnect duplicates harmless. A gap is rendered as
 * a subdued status warning because chat v1 promises ordering, not replay. */
export class TypedStreamRenderer {
  private readonly lastSeq = new Map<string, number>()

  accept(raw: Record<string, unknown>): TypedStreamRenderResult | null {
    const stream = parseTypedStreamEvent(raw)
    if (!stream) {
      return null
    }

    const key = stream.run_id ?? stream.session_id
    const previous = this.lastSeq.get(key)
    if (previous !== undefined && stream.seq <= previous) {
      return { events: [], sessionId: stream.session_id, terminal: false }
    }
    this.lastSeq.set(key, stream.seq)

    const events: GatewayEvent[] = []
    if (previous !== undefined && stream.seq > previous + 1) {
      events.push({
        type: 'status.update',
        session_id: stream.session_id,
        payload: { kind: 'relay-gap', text: `relay stream gap: expected ${previous + 1}, received ${stream.seq}` }
      })
    }

    const payload = stream.payload
    switch (stream.event) {
      case 'session.created':
        break
      case 'run.started':
        events.push({
          type: 'status.update',
          session_id: stream.session_id,
          payload: { kind: 'run', text: 'run started' }
        })
        break
      case 'message.started':
        events.push({ type: 'message.start', session_id: stream.session_id })
        break
      case 'assistant.delta': {
        const delta = text(payload, 'delta', 'content', 'text')
        if (delta) {
          events.push({
            type: 'message.delta',
            session_id: stream.session_id,
            payload: { text: delta }
          })
        }
        break
      }
      case 'message.interim': {
        const interim = text(payload, 'text', 'message', 'preview', 'rendered')
        if (interim) {
          events.push({
            type: 'message.interim',
            session_id: stream.session_id,
            payload: {
              text: interim,
              already_streamed: payload.already_streamed === true
            }
          })
        }
        break
      }
      case 'tool.progress': {
        const delta = text(payload, 'delta', 'text', 'preview')
        if (delta) {
          events.push({
            type: 'status.update',
            session_id: stream.session_id,
            payload: { kind: 'tool-progress', text: delta }
          })
        }
        break
      }
      case 'tool.pending':
        events.push({
          type: 'status.update',
          session_id: stream.session_id,
          payload: {
            kind: 'tool-pending',
            text: `tool pending: ${text(payload, 'tool_name', 'name') ?? 'tool'}`
          }
        })
        break
      case 'tool.started': {
        const toolId = text(payload, 'call_id', 'tool_call_id', 'id') ?? 'tool'
        events.push({
          type: 'tool.start',
          session_id: stream.session_id,
          payload: {
            tool_id: toolId,
            name: text(payload, 'tool_name', 'name'),
            context: text(payload, 'preview')
          }
        })
        break
      }
      case 'tool.completed':
      case 'tool.failed': {
        const toolId = text(payload, 'call_id', 'tool_call_id', 'id') ?? 'tool'
        const failed = stream.event === 'tool.failed' || payload.success === false
        events.push({
          type: 'tool.complete',
          session_id: stream.session_id,
          payload: {
            tool_id: toolId,
            name: text(payload, 'tool_name', 'name'),
            summary: text(payload, 'result_preview', 'result', 'summary'),
            error: failed ? text(payload, 'error', 'message') ?? 'tool failed' : undefined
          }
        })
        break
      }
      case 'artifact.created':
        events.push({
          type: 'artifact.created',
          session_id: stream.session_id,
          payload: {
            title: text(payload, 'title', 'name'),
            url: text(payload, 'url'),
            path: text(payload, 'path')
          }
        })
        break
      case 'memory.updated':
      case 'skill.loaded':
        events.push({
          type: stream.event,
          session_id: stream.session_id,
          payload: { detail: text(payload, 'name', 'title', 'detail') }
        })
        break
      case 'assistant.completed':
        events.push({
          type: 'message.complete',
          session_id: stream.session_id,
          payload: {
            text: text(payload, 'text', 'content', 'message', 'rendered'),
            response_previewed: payload.response_previewed === true
          }
        })
        break
      case 'error':
        events.push({
          type: 'error',
          session_id: stream.session_id,
          payload: { message: text(payload, 'message', 'error') ?? 'relay chat error' }
        })
        break
    }

    return {
      events,
      sessionId: stream.session_id,
      terminal: stream.event === 'run.completed' || stream.event === 'done' || stream.event === 'error'
    }
  }
}

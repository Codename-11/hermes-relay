import type { ParsedArgs } from '../cli.js'
import { rpcErrorMessage } from '../lib/rpc.js'

type JsonRecord = Record<string, unknown>

interface DirectApiChatOptions {
  baseUrl: string
  apiKey: string | null
  prompt: string
  sessionId: string | null
  fresh: boolean
}

interface SseMessage {
  event: string | null
  data: string
}

function flag(args: ParsedArgs, name: string): string | null {
  const value = args.flags[name]
  return typeof value === 'string' ? value : null
}

function normalizeBaseUrl(raw: string): string {
  const trimmed = raw.trim()
  if (!trimmed) {
    throw new Error('gateway URL is required')
  }
  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`
  const parsed = new URL(withScheme)
  parsed.pathname = parsed.pathname.replace(/\/+$/, '')
  parsed.search = ''
  parsed.hash = ''
  return parsed.toString().replace(/\/+$/, '')
}

function headers(apiKey: string | null, accept = 'application/json'): Record<string, string> {
  const out: Record<string, string> = {
    Accept: accept,
    'Content-Type': 'application/json'
  }
  if (apiKey?.trim()) {
    out.Authorization = `Bearer ${apiKey.trim()}`
  }
  return out
}

function asRecord(value: unknown): JsonRecord {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as JsonRecord) : {}
}

function stringField(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function pickSessionId(value: unknown): string | null {
  const root = asRecord(value)
  const session = asRecord(root.session)
  return (
    stringField(root.session_id) ??
    stringField(root.id) ??
    stringField(session.id) ??
    stringField(session.session_id)
  )
}

function emit(type: string, payload: JsonRecord = {}, sessionId?: string | null): void {
  const event: JsonRecord = { type, payload }
  if (sessionId) {
    event.session_id = sessionId
  }
  process.stdout.write(`${JSON.stringify(event)}\n`)
}

async function readJsonResponse(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text.trim()) {
    return {}
  }
  return JSON.parse(text) as unknown
}

async function routeExists(baseUrl: string, apiKey: string | null, path: string): Promise<boolean> {
  try {
    const response = await fetch(`${baseUrl}${path}`, {
      method: 'HEAD',
      headers: headers(apiKey)
    })
    return response.status === 200 || response.status === 401 || response.status === 403 || response.status === 405
  } catch {
    return false
  }
}

async function createSession(baseUrl: string, apiKey: string | null): Promise<string> {
  const response = await fetch(`${baseUrl}/api/sessions`, {
    method: 'POST',
    headers: headers(apiKey),
    body: JSON.stringify({ title: 'Hermes Relay Desktop Chat' })
  })
  if (!response.ok) {
    throw new Error(`create session failed: HTTP ${response.status} ${response.statusText}`)
  }
  const id = pickSessionId(await readJsonResponse(response))
  if (!id) {
    throw new Error('create session response did not include a session id')
  }
  return id
}

async function* sseMessages(response: Response): AsyncGenerator<SseMessage> {
  if (!response.body) {
    throw new Error('stream response has no body')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let event: string | null = null
  let data: string[] = []

  const flush = function* (): Generator<SseMessage> {
    if (data.length > 0) {
      yield { event, data: data.join('\n') }
    }
    event = null
    data = []
  }

  for (;;) {
    const { done, value } = await reader.read()
    buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done })
    let newline = buffer.indexOf('\n')
    while (newline >= 0) {
      const rawLine = buffer.slice(0, newline)
      buffer = buffer.slice(newline + 1)
      const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
      if (!line) {
        yield* flush()
      } else if (line.startsWith('event:')) {
        event = line.slice('event:'.length).trim() || null
      } else if (line.startsWith('data:')) {
        data.push(line.slice('data:'.length).trimStart())
      }
      newline = buffer.indexOf('\n')
    }
    if (done) {
      break
    }
  }
  if (buffer.trim()) {
    data.push(buffer.trim())
  }
  yield* flush()
}

function eventText(record: JsonRecord): string | null {
  const message = asRecord(record.message)
  return (
    stringField(record.delta) ??
    stringField(record.text) ??
    stringField(record.content) ??
    stringField(record.thinking_delta) ??
    stringField(record.thinking) ??
    stringField(message.content)
  )
}

function eventToolName(record: JsonRecord): string {
  return (
    stringField(record.tool_name) ??
    stringField(record.tool) ??
    stringField(record.name) ??
    stringField(record.call_id) ??
    stringField(record.tool_call_id) ??
    'tool'
  )
}

function mapSseEvent(kind: string, record: JsonRecord, sessionId: string | null): void {
  const sid = pickSessionId(record) ?? sessionId
  if (sid) {
    emit('session.info', { id: sid }, sid)
  }

  switch (kind) {
    case 'response.output_text.delta':
    case 'message.delta':
    case 'assistant.delta':
    case 'content_delta':
    case 'delta': {
      const text = eventText(record)
      if (text) {
        emit('message.delta', { text }, sid)
      }
      return
    }
    case 'tool.progress':
    case 'thinking_delta':
    case 'reasoning_delta':
    case 'reasoning.available': {
      const text = eventText(record)
      if (text) {
        emit('reasoning.delta', { text }, sid)
      }
      return
    }
    case 'tool.pending':
    case 'tool.started':
    case 'tool_start':
    case 'tool_started': {
      const name = eventToolName(record)
      emit('tool.start', { tool_id: name, name }, sid)
      return
    }
    case 'tool.completed':
    case 'tool_result':
    case 'tool_completed': {
      const name = eventToolName(record)
      const error = stringField(record.error) ?? undefined
      const summary = stringField(record.result_preview) ?? stringField(record.summary) ?? stringField(record.message)
      emit('tool.complete', { tool_id: name, name, error, summary }, sid)
      return
    }
    case 'response.created':
    case 'run.started':
    case 'session.created':
    case 'message.started':
      return
    case 'assistant.completed':
    case 'response.completed':
    case 'run.completed':
    case 'content_complete':
    case 'complete':
    case 'completed':
    case 'done':
      emit('message.complete', {}, sid)
      return
    case 'error':
    case 'run.failed':
    case 'tool.failed':
      emit('error', { message: stringField(record.error) ?? stringField(record.message) ?? 'gateway stream error' }, sid)
      return
    default:
      emit('status.update', { text: kind }, sid)
      return
  }
}

async function streamSseResponse(response: Response, sessionId: string | null): Promise<void> {
  if (!response.ok) {
    const body = await response.text().catch(() => '')
    throw new Error(`chat stream failed: HTTP ${response.status} ${response.statusText}${body ? ` - ${body.slice(0, 240)}` : ''}`)
  }
  let completed = false
  for await (const message of sseMessages(response)) {
    if (message.data === '[DONE]') {
      completed = true
      emit('message.complete', {}, sessionId)
      continue
    }
    let record: JsonRecord
    try {
      record = asRecord(JSON.parse(message.data) as unknown)
    } catch {
      record = { text: message.data }
    }
    const kind = message.event ?? stringField(record.type) ?? stringField(record.event) ?? 'message.delta'
    if (kind === 'done' || kind === 'response.completed' || kind === 'run.completed' || kind === 'message.complete') {
      completed = true
    }
    mapSseEvent(kind, record, sessionId)
  }
  if (!completed) {
    emit('message.complete', {}, sessionId)
  }
}

async function streamSessionChat(opts: DirectApiChatOptions, sessionId: string): Promise<void> {
  emit('session.info', { id: sessionId, source: 'gateway_sessions' }, sessionId)
  const response = await fetch(`${opts.baseUrl}/api/sessions/${encodeURIComponent(sessionId)}/chat/stream`, {
    method: 'POST',
    headers: headers(opts.apiKey, 'text/event-stream'),
    body: JSON.stringify({ message: opts.prompt })
  })
  await streamSseResponse(response, sessionId)
}

async function streamRunChat(opts: DirectApiChatOptions): Promise<void> {
  emit('session.info', { id: opts.sessionId ?? 'runs', source: 'gateway_runs' }, opts.sessionId)
  const body: JsonRecord = {
    model: 'default',
    input: opts.prompt,
    stream: true
  }
  const response = await fetch(`${opts.baseUrl}/v1/runs`, {
    method: 'POST',
    headers: headers(opts.apiKey, 'text/event-stream'),
    body: JSON.stringify(body)
  })
  await streamSseResponse(response, opts.sessionId)
}

async function runDirectApiChat(opts: DirectApiChatOptions): Promise<void> {
  const sessionsChat = await routeExists(opts.baseUrl, opts.apiKey, '/api/sessions/probe/chat/stream')
  if (sessionsChat) {
    const sessionId = opts.fresh || !opts.sessionId ? await createSession(opts.baseUrl, opts.apiKey) : opts.sessionId
    await streamSessionChat(opts, sessionId)
    return
  }

  const runs = await routeExists(opts.baseUrl, opts.apiKey, '/v1/runs')
  if (runs) {
    await streamRunChat(opts)
    return
  }

  throw new Error('gateway is reachable but no supported streaming chat endpoint was found (/api/sessions/*/chat/stream or /v1/runs)')
}

export async function chatWorkerCommand(args: ParsedArgs): Promise<number> {
  const mode = args.positional.shift() ?? ''
  if (mode !== 'api') {
    process.stderr.write('usage: hermes-relay chat-worker api --gateway-url <url> [--session <id>] [--new] <prompt>\n')
    return 2
  }
  const gatewayUrl = flag(args, 'gateway-url') ?? process.env.HERMES_RELAY_GATEWAY_URL ?? ''
  const prompt = args.positional.join(' ').trim()
  if (!prompt) {
    process.stderr.write('error: prompt is required\n')
    return 2
  }

  try {
    await runDirectApiChat({
      baseUrl: normalizeBaseUrl(gatewayUrl),
      apiKey: process.env.HERMES_RELAY_GATEWAY_API_KEY ?? null,
      prompt,
      sessionId: flag(args, 'session'),
      fresh: !!args.flags.new
    })
    return 0
  } catch (error) {
    const message = rpcErrorMessage(error)
    emit('error', { message })
    process.stderr.write(`error: ${message}\n`)
    return 1
  }
}

// Loopback-only HTTP server that backs `hermes-relay voice mode`.
//
// The browser page (see voicePage.ts) does mic capture and playback in
// JavaScript and ships the audio bytes to this server. The server is a thin
// proxy onto the relay: it forwards audio to `/voice/transcribe`, submits
// the transcript via the existing gateway client (`prompt.submit`), streams
// `message.delta` / `message.complete` events back as SSE, and forwards the
// final reply text to `/voice/synthesize` returning the mp3 bytes verbatim.
//
// Design notes:
//
//  * **Loopback only.** Server binds to 127.0.0.1 — any cross-host caller
//    can't reach it without ssh-tunneling on purpose.
//  * **Path-prefix nonce.** All endpoints sit under `/v/<nonce>` where
//    <nonce> is 32 hex chars generated at startup. Other localhost callers
//    (other browser tabs, other apps on the box) can't guess the prefix,
//    so they can't drive the mic round-trip from the user's box.
//  * **Token never crosses the wire.** The relay bearer token stays in
//    this Node process. The page only ever sees `/v/<nonce>/...` paths.
//  * **No CORS, no cookies, no auth.** Loopback + nonce is the gate. A
//    cross-origin caller can't read SSE bodies or audio bodies anyway
//    (no `Access-Control-Allow-Origin`).
//
// Concurrency: one in-flight turn at a time. If a turn POST arrives while
// another is running we 409 — the page won't trigger that because the mic
// button is locked while busy, but it's a defensive shape.

import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { Readable } from 'node:stream'
import { randomBytes } from 'node:crypto'
import { spawn } from 'node:child_process'
import type { AddressInfo } from 'node:net'

import { renderVoicePage } from './voicePage.js'
import type { GatewayClient } from './gatewayClient.js'
import type { GatewayEvent, PromptSubmitResponse } from './gatewayTypes.js'
import { rpcErrorMessage } from './lib/rpc.js'

const TURN_TIMEOUT_MS = 5 * 60_000

export interface VoiceServerOptions {
  /** Relay bearer token. Used as `Authorization: Bearer <token>` on voice routes. */
  token: string
  /** Relay base URL — `ws(s)://host:port`. Converted to http(s) for the voice routes. */
  relayUrl: string
  /** Gateway client, already-connected and ready (caller awaited `gateway.ready`). */
  gateway: GatewayClient
  /** Hermes session id to drive — caller created or resumed it. */
  sessionId: string
  /** Bind port. 0 picks an available port. */
  port?: number
}

export interface VoiceServer {
  /** http://127.0.0.1:<port>/v/<nonce>/ — the URL to open in the browser. */
  url: string
  /** Stop the server and tear down all in-flight responses. */
  close: () => Promise<void>
}

/** Start a loopback-only HTTP server that proxies voice round-trips to the
 * relay. The returned `url` includes the nonce prefix. */
export async function startVoiceServer(opts: VoiceServerOptions): Promise<VoiceServer> {
  const nonce = randomBytes(16).toString('hex')
  const base = `/v/${nonce}`
  const httpBase = wsToHttp(opts.relayUrl)

  let inFlight: AbortController | null = null

  const server = createServer((req, res) => {
    handleRequest(req, res, { nonce, base, httpBase, opts, getInFlight: () => inFlight, setInFlight: (c) => { inFlight = c } })
      .catch((e) => {
        try {
          if (!res.headersSent) {
            res.writeHead(500, { 'Content-Type': 'text/plain' })
          }
          res.end(`internal error: ${e instanceof Error ? e.message : String(e)}\n`)
        } catch {
          /* socket already dead */
        }
      })
  })

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject)
    server.listen(opts.port ?? 0, '127.0.0.1', () => resolve())
  })

  const addr = server.address() as AddressInfo
  const url = `http://127.0.0.1:${addr.port}${base}/`

  return {
    url,
    close: async () => {
      try { inFlight?.abort() } catch { /* ignore */ }
      await new Promise<void>((resolve) => server.close(() => resolve()))
    }
  }
}

interface HandlerCtx {
  nonce: string
  base: string
  httpBase: string
  opts: VoiceServerOptions
  getInFlight: () => AbortController | null
  setInFlight: (c: AbortController | null) => void
}

async function handleRequest(req: IncomingMessage, res: ServerResponse, ctx: HandlerCtx): Promise<void> {
  const url = req.url ?? ''

  // Strip the nonce prefix. Anything that doesn't start with it 404s — that's
  // the entire localhost-tab-isolation guarantee.
  if (!url.startsWith(ctx.base + '/') && url !== ctx.base) {
    res.writeHead(404, { 'Content-Type': 'text/plain' })
    res.end('not found\n')
    return
  }
  const path = url === ctx.base ? '/' : url.slice(ctx.base.length)

  if (req.method === 'GET' && (path === '/' || path === '')) {
    const html = renderVoicePage({ base: ctx.base, relayUrl: ctx.opts.relayUrl })
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'Referrer-Policy': 'no-referrer'
    })
    res.end(html)
    return
  }

  if (req.method === 'POST' && path === '/turn') {
    await handleTurn(req, res, ctx)
    return
  }

  if (req.method === 'POST' && path === '/synthesize') {
    await handleSynthesize(req, res, ctx)
    return
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' })
  res.end('not found\n')
}

async function handleTurn(req: IncomingMessage, res: ServerResponse, ctx: HandlerCtx): Promise<void> {
  if (ctx.getInFlight()) {
    res.writeHead(409, { 'Content-Type': 'text/plain' })
    res.end('another turn is in flight\n')
    return
  }

  const abort = new AbortController()
  ctx.setInFlight(abort)

  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-store',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no'
  })

  function sendEvent(event: string, data: Record<string, unknown>): void {
    if (res.writableEnded) return
    res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
  }

  function endTurn(): void {
    ctx.setInFlight(null)
    try { if (!res.writableEnded) res.end() } catch { /* ignore */ }
  }

  // Drop the connection if the browser navigates away mid-turn.
  req.on('close', () => { try { abort.abort() } catch { /* ignore */ } })

  try {
    const audioMime = (req.headers['content-type'] ?? 'audio/webm').toString().split(';', 1)[0]?.trim() || 'audio/webm'
    const audioBytes = await readBody(req, 25 * 1024 * 1024)

    if (audioBytes.length === 0) {
      sendEvent('error', { message: 'empty audio body' })
      endTurn()
      return
    }

    // STT — forward to /voice/transcribe as multipart.
    const transcript = await transcribeOnRelay({
      httpBase: ctx.httpBase,
      token: ctx.opts.token,
      audio: audioBytes,
      audioMime,
      signal: abort.signal
    })
    sendEvent('transcript', { text: transcript })

    if (transcript.trim().length === 0) {
      sendEvent('complete', { text: '' })
      sendEvent('error', { message: 'empty transcript — try speaking louder or closer' })
      endTurn()
      return
    }

    // Gateway turn — submit prompt and forward streaming events. We attach
    // the listener BEFORE prompt.submit so we don't drop early deltas.
    let finalText = ''
    let completed = false

    const settle = new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        if (completed) return
        cleanup()
        reject(new Error(`turn timeout after ${TURN_TIMEOUT_MS}ms`))
      }, TURN_TIMEOUT_MS)
      timer.unref?.()

      const onAbort = () => {
        if (completed) return
        cleanup()
        // Best-effort interrupt — the gateway may have already finished.
        ctx.opts.gateway.request('session.interrupt', { session_id: ctx.opts.sessionId }).catch(() => {})
        reject(new Error('cancelled'))
      }

      const handler = (ev: GatewayEvent) => {
        if (completed) return
        if (ev.type === 'message.delta') {
          const t = ev.payload?.text ?? ''
          if (t) {
            finalText += t
            sendEvent('delta', { text: t })
          }
        } else if (ev.type === 'message.complete') {
          const t = ev.payload?.text ?? finalText
          finalText = t
          completed = true
          cleanup()
          sendEvent('complete', { text: t })
          resolve()
        } else if (ev.type === 'error') {
          completed = true
          cleanup()
          sendEvent('error', { message: ev.payload?.message ?? 'agent error' })
          resolve()
        }
      }

      function cleanup(): void {
        clearTimeout(timer)
        ctx.opts.gateway.off('event', handler)
        abort.signal.removeEventListener('abort', onAbort)
      }

      ctx.opts.gateway.on('event', handler)
      abort.signal.addEventListener('abort', onAbort)

      ctx.opts.gateway
        .request<PromptSubmitResponse>('prompt.submit', { session_id: ctx.opts.sessionId, text: transcript })
        .catch((e: unknown) => {
          if (completed) return
          completed = true
          cleanup()
          sendEvent('error', { message: rpcErrorMessage(e) })
          resolve()
        })
    })

    await settle
  } catch (e) {
    if (!abort.signal.aborted) {
      sendEvent('error', { message: e instanceof Error ? e.message : String(e) })
    }
  } finally {
    endTurn()
  }
}

async function handleSynthesize(req: IncomingMessage, res: ServerResponse, ctx: HandlerCtx): Promise<void> {
  let text = ''
  try {
    const raw = await readBody(req, 1 * 1024 * 1024)
    const parsed = JSON.parse(raw.toString('utf8'))
    if (typeof parsed?.text !== 'string') {
      res.writeHead(400, { 'Content-Type': 'text/plain' })
      res.end('synthesize: body must be {"text": string}\n')
      return
    }
    text = parsed.text
  } catch (e) {
    res.writeHead(400, { 'Content-Type': 'text/plain' })
    res.end(`synthesize: bad body — ${e instanceof Error ? e.message : String(e)}\n`)
    return
  }

  if (!text.trim()) {
    res.writeHead(400, { 'Content-Type': 'text/plain' })
    res.end('synthesize: empty text\n')
    return
  }

  const ttsRes = await fetch(`${ctx.httpBase}/voice/synthesize`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${ctx.opts.token}`,
      'Content-Type': 'application/json',
      Accept: 'audio/mpeg'
    },
    body: JSON.stringify({ text })
  })

  if (!ttsRes.ok || !ttsRes.body) {
    const detail = await safeText(ttsRes)
    res.writeHead(ttsRes.status, { 'Content-Type': 'text/plain' })
    res.end(`relay /voice/synthesize ${ttsRes.status}: ${detail}\n`)
    return
  }

  res.writeHead(200, {
    'Content-Type': ttsRes.headers.get('content-type') ?? 'audio/mpeg',
    'Cache-Control': 'no-store'
  })

  // Stream the audio body straight through — no buffering. fetch's body is a
  // web ReadableStream; Readable.fromWeb adapts it to a Node stream.
  Readable.fromWeb(ttsRes.body as unknown as Parameters<typeof Readable.fromWeb>[0]).pipe(res)
}

async function transcribeOnRelay(opts: {
  httpBase: string
  token: string
  audio: Buffer
  audioMime: string
  signal: AbortSignal
}): Promise<string> {
  const form = new FormData()
  const blob = new Blob([new Uint8Array(opts.audio)], { type: opts.audioMime })
  form.append('audio', blob, `voice.${extFor(opts.audioMime)}`)

  const sttRes = await fetch(`${opts.httpBase}/voice/transcribe`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${opts.token}`, Accept: 'application/json' },
    body: form,
    signal: opts.signal
  })

  const txt = await sttRes.text()
  if (!sttRes.ok) {
    throw new Error(`relay /voice/transcribe ${sttRes.status}: ${txt.slice(0, 240)}`)
  }
  let body: { text?: string; success?: boolean; error?: string }
  try {
    body = JSON.parse(txt)
  } catch {
    throw new Error(`relay /voice/transcribe returned non-JSON: ${txt.slice(0, 240)}`)
  }
  if (body.success === false) {
    throw new Error(`relay /voice/transcribe error: ${body.error ?? 'unknown'}`)
  }
  return body.text ?? ''
}

function readBody(req: IncomingMessage, maxBytes: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    let total = 0
    req.on('data', (chunk: Buffer) => {
      total += chunk.length
      if (total > maxBytes) {
        reject(new Error(`body exceeds ${maxBytes} bytes`))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

async function safeText(res: Response): Promise<string> {
  try { return (await res.text()).slice(0, 240) } catch { return '<no body>' }
}

function wsToHttp(url: string): string {
  const trimmed = url.trim()
  if (trimmed.startsWith('wss://')) return 'https://' + trimmed.slice('wss://'.length)
  if (trimmed.startsWith('ws://')) return 'http://' + trimmed.slice('ws://'.length)
  return trimmed
}

function extFor(mime: string): string {
  const base = mime.split(';', 1)[0]?.trim().toLowerCase() ?? ''
  if (base.includes('webm')) return 'webm'
  if (base.includes('ogg')) return 'ogg'
  if (base.includes('mp4') || base.includes('m4a') || base.includes('aac')) return 'm4a'
  if (base.includes('wav')) return 'wav'
  if (base.includes('mpeg') || base.includes('mp3')) return 'mp3'
  return 'webm'
}

/** Open `url` in the user's default browser. Best-effort, non-blocking. */
export function openInBrowser(url: string): void {
  try {
    if (process.platform === 'win32') {
      // `start` is a cmd.exe builtin. Empty title prevents the URL being
      // interpreted as a window title.
      spawn('cmd', ['/c', 'start', '""', url], { detached: true, stdio: 'ignore' }).unref()
    } else if (process.platform === 'darwin') {
      spawn('open', [url], { detached: true, stdio: 'ignore' }).unref()
    } else {
      spawn('xdg-open', [url], { detached: true, stdio: 'ignore' }).unref()
    }
  } catch {
    /* user can paste the URL manually — printed by the caller */
  }
}

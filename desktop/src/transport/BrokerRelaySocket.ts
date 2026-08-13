import { createHash, randomBytes } from 'node:crypto'
import { Duplex } from 'node:stream'
import * as tls from 'node:tls'

import { certificateDerToPem, comparePins, extractSpkiSha256, peerCertificateDer } from '../certPin.js'

export interface BrokerRouteConfig {
  url: string
  hostId: string
  credentialKind: 'bootstrap' | 'route'
  token: string
  innerUrl: string
  innerPinSha256: string
  innerCertificateDerBase64: string
}

type EventName = 'open' | 'message' | 'close' | 'error'
type Listener = (...args: any[]) => void

class BrokerByteStream extends Duplex {
  private matchedResolve!: () => void
  private matchedReject!: (error: Error) => void
  readonly matched = new Promise<void>((resolve, reject) => { this.matchedResolve = resolve; this.matchedReject = reject })
  private binary = false
  private matchTimer: ReturnType<typeof setTimeout>

  constructor(private readonly ws: WebSocket, registration: Record<string, unknown>) {
    super()
    this.matchTimer = setTimeout(() => this.matchedReject(new Error('Hermes Reach host match timed out')), 10_000)
    ws.binaryType = 'arraybuffer'
    ws.addEventListener('open', () => ws.send(JSON.stringify(registration)))
    ws.addEventListener('message', event => {
      if (!this.binary) {
        try {
          const message = JSON.parse(String(event.data)) as { type?: string; code?: string; protocol_version?: number; stream_id?: string }
          if (message.type === 'matched' && message.protocol_version === 1 && typeof message.stream_id === 'string' && /^[A-Za-z0-9_-]{22}$/.test(message.stream_id)) { clearTimeout(this.matchTimer); this.binary = true; this.matchedResolve(); return }
          if (message.type === 'error') this.matchedReject(new Error(`Hermes Reach rejected route: ${message.code ?? 'unknown'}`))
        } catch { this.matchedReject(new Error('Hermes Reach returned an invalid match response')) }
        return
      }
      const data = event.data
      if (data instanceof ArrayBuffer) this.push(Buffer.from(data))
      else if (ArrayBuffer.isView(data)) this.push(Buffer.from(data.buffer, data.byteOffset, data.byteLength))
      else this.destroy(new Error('Hermes Reach switched away from binary tunnel records'))
    })
    ws.addEventListener('close', () => { this.push(null); if (!this.binary) this.matchedReject(new Error('Hermes Reach closed before matching the host')) })
    ws.addEventListener('error', () => { const error = new Error('Hermes Reach WebSocket failed'); this.matchedReject(error); this.destroy(error) })
  }
  _read(): void {}
  _write(chunk: Buffer, _encoding: BufferEncoding, callback: (error?: Error | null) => void): void {
    if (this.ws.readyState !== WebSocket.OPEN) { callback(new Error('Hermes Reach tunnel is not open')); return }
    if (chunk.byteLength > 1024 * 1024) { callback(new Error('Hermes Reach record exceeds 1 MiB')); return }
    this.ws.send(chunk); callback()
  }
  _destroy(error: Error | null, callback: (error?: Error | null) => void): void { clearTimeout(this.matchTimer); try { this.ws.close() } finally { callback(error) } }
}

export class BrokerRelaySocket {
  readyState = 0
  private openEmitted = false
  private listeners = new Map<EventName, Listener[]>()
  private buffer = Buffer.alloc(0)
  private fragments: Buffer[] = []
  private fragmentOpcode = 0

  constructor(private readonly socket: tls.TLSSocket) {
    socket.on('data', chunk => { this.buffer = Buffer.concat([this.buffer, chunk]); this.parseFrames() })
    socket.on('error', error => this.emit('error', { message: error.message }))
    socket.on('close', () => { this.readyState = 3; this.emit('close', { code: 1006, reason: 'Hermes Reach tunnel closed' }) })
  }
  addEventListener(type: EventName, listener: Listener): void {
    const list = this.listeners.get(type) ?? []
    list.push(listener)
    this.listeners.set(type, list)
    // openBrokerRelaySocket completes the TLS and HTTP upgrade before it
    // returns. Await continuations can therefore subscribe after the queued
    // open event ran. Replay that one-shot state to late listeners, while
    // listeners registered before the queued emission still receive it once.
    if (type === 'open' && this.readyState === 1 && this.openEmitted) {
      queueMicrotask(() => listener())
    }
  }
  opened(): void { this.readyState = 1; queueMicrotask(() => this.emit('open')) }
  send(data: string): void { this.socket.write(frame(Buffer.from(data), 1)) }
  close(code = 1000, reason = ''): void { if (this.readyState < 2) { this.readyState = 2; const body = Buffer.alloc(2 + Buffer.byteLength(reason)); body.writeUInt16BE(code); body.write(reason, 2); this.socket.end(frame(body, 8)) } }
  private emit(type: EventName, event?: unknown): void {
    if (type === 'open') this.openEmitted = true
    for (const listener of this.listeners.get(type) ?? []) listener(event)
  }
  private parseFrames(): void {
    while (this.buffer.length >= 2) {
      const first = this.buffer[0]!, second = this.buffer[1]!
      if ((first & 0x70) !== 0 || (second & 0x80) !== 0) { this.socket.destroy(new Error('invalid inner WebSocket frame')); return }
      let length = second & 0x7f, offset = 2
      if (length === 126) { if (this.buffer.length < 4) return; length = this.buffer.readUInt16BE(2); offset = 4 }
      else if (length === 127) { if (this.buffer.length < 10) return; const wide = this.buffer.readBigUInt64BE(2); if (wide > 1024n * 1024n) { this.socket.destroy(new Error('inner Relay frame exceeds 1 MiB')); return } length = Number(wide); offset = 10 }
      const masked = (second & 0x80) !== 0, maskBytes = masked ? 4 : 0
      if (this.buffer.length < offset + maskBytes + length) return
      const mask = masked ? this.buffer.subarray(offset, offset + 4) : null
      offset += maskBytes
      const payload = Buffer.from(this.buffer.subarray(offset, offset + length)); this.buffer = this.buffer.subarray(offset + length)
      if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4]!
      const opcode = first & 0x0f, fin = (first & 0x80) !== 0
      if (opcode >= 8 && (!fin || length > 125)) { this.socket.destroy(new Error('invalid inner WebSocket control frame')); return }
      if (opcode === 0 && this.fragments.length === 0) { this.socket.destroy(new Error('unexpected inner WebSocket continuation')); return }
      if (opcode !== 0 && opcode < 8 && this.fragments.length !== 0) { this.socket.destroy(new Error('interleaved inner WebSocket message')); return }
      if (opcode === 8) { this.close(); return }
      if (opcode === 9) { this.socket.write(frame(payload, 10)); continue }
      if (opcode === 10) continue
      if (opcode !== 0) this.fragmentOpcode = opcode
      this.fragments.push(payload)
      if (this.fragments.reduce((total, part) => total + part.length, 0) > 1024 * 1024) { this.socket.destroy(new Error('inner Relay message exceeds 1 MiB')); return }
      if (fin) { const message = Buffer.concat(this.fragments); const type = this.fragmentOpcode; this.fragments = []; this.fragmentOpcode = 0; this.emit('message', { data: type === 1 ? message.toString('utf8') : message }) }
    }
  }
}

function frame(payload: Buffer, opcode: number): Buffer {
  const mask = randomBytes(4)
  const head = payload.length < 126 ? Buffer.from([0x80 | opcode, 0x80 | payload.length]) : Buffer.from([0x80 | opcode, 0xfe, payload.length >> 8, payload.length & 0xff])
  const body = Buffer.from(payload); for (let i = 0; i < body.length; i++) body[i] ^= mask[i % 4]!
  return Buffer.concat([head, mask, body])
}

export async function openBrokerRelaySocket(config: BrokerRouteConfig): Promise<BrokerRelaySocket> {
  const connectionId = randomBytes(16).toString('base64url')
  const outer = new WebSocket(new URL('/v1/connect', config.url).toString())
  const stream = new BrokerByteStream(outer, { type: 'register', protocol_version: 1, role: 'client', host_id: config.hostId, connection_id: connectionId, credential_kind: config.credentialKind, token: config.token })
  await stream.matched
  const inner = new URL(config.innerUrl)
  const certificate = Buffer.from(config.innerCertificateDerBase64, 'base64')
  if (!comparePins(config.innerPinSha256, extractSpkiSha256(certificate))) {
    stream.destroy(new Error('Hermes Reach inner Secure Link certificate pin mismatch'))
    throw new Error('Hermes Reach inner Secure Link certificate pin mismatch')
  }
  const secure = tls.connect({
    socket: stream,
    servername: inner.hostname,
    rejectUnauthorized: true,
    ca: certificateDerToPem(certificate),
  })
  await new Promise<void>((resolve, reject) => { secure.once('secureConnect', resolve); secure.once('error', reject) })
  const raw = peerCertificateDer(secure)
  if (!raw || !comparePins(config.innerPinSha256, extractSpkiSha256(raw))) { secure.destroy(); throw new Error('Hermes Reach inner Secure Link certificate pin mismatch') }
  const identityError = tls.checkServerIdentity(inner.hostname, secure.getPeerCertificate(true))
  if (identityError) { secure.destroy(); throw identityError }
  const key = randomBytes(16).toString('base64')
  secure.write(`GET ${inner.pathname || '/relay/ws'}${inner.search} HTTP/1.1\r\nHost: ${inner.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`)
  let response = Buffer.alloc(0)
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => { secure.destroy(); reject(new Error('Hermes Reach inner WebSocket upgrade timed out')) }, 10_000)
    const onData = (chunk: Buffer) => { response = Buffer.concat([response, chunk]); if (response.length > 32 * 1024) { clearTimeout(timer); secure.destroy(); reject(new Error('Hermes Reach inner WebSocket upgrade headers too large')); return } const end = response.indexOf('\r\n\r\n'); if (end < 0) return; clearTimeout(timer); secure.off('data', onData); const header = response.subarray(0, end).toString('utf8'); const accept = createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64'); if (!/^HTTP\/1\.1 101 /i.test(header) || !header.toLowerCase().includes(`sec-websocket-accept: ${accept.toLowerCase()}`)) reject(new Error('Hermes Reach inner WebSocket upgrade failed')); else { const rest = response.subarray(end + 4); if (rest.length) secure.unshift(rest); resolve() } }
    secure.on('data', onData); secure.once('error', reject)
  })
  const socket = new BrokerRelaySocket(secure); socket.opened(); return socket
}

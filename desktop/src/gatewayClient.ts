// Thin coordinator over `Transport` — mirror of the TUI's GatewayClient but
// with the default LocalSubprocessTransport dependency dropped. Callers must
// construct a transport explicitly (there's only one — RelayTransport — for
// the CLI; a local `hermes chat` invocation is a separate Python binary).

import { EventEmitter } from 'node:events'

import type { GatewayEvent } from './gatewayTypes.js'
import type { Transport } from './transport/Transport.js'

export class GatewayClient extends EventEmitter {
  private transport: Transport

  constructor(transport: Transport) {
    super()
    this.setMaxListeners(0)

    this.transport = transport

    // Re-emit events + exit from the underlying transport.
    this.transport.on('event', (ev: GatewayEvent) => this.emit('event', ev))
    this.transport.on('exit', (code: number | null) => this.emit('exit', code))
  }

  start() {
    this.transport.start()
  }

  request<T = unknown>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    return this.transport.request<T>(method, params)
  }

  drain() {
    this.transport.drain()
  }

  getLogTail(limit = 20): string {
    return this.transport.getLogTail(limit)
  }

  kill() {
    this.transport.kill()
  }
}

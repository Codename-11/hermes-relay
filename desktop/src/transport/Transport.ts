// Vendored from hermes-agent/ui-tui/src/transport/Transport.ts. The CLI only
// ships a RelayTransport (no LocalSubprocessTransport) — a CLI binary running
// directly on the Hermes server is a niche case and would just be the Python
// `hermes chat` command.

import type { GatewayEvent } from '../gatewayTypes.js'

/**
 * Abstract transport for the CLI/TUI ↔ `tui_gateway` JSON-RPC stream.
 * The Python server is endpoint-agnostic; all transports speak the same
 * line-delimited JSON-RPC 2.0 wire format. Only the carrier changes.
 */
export interface Transport {
  /** Drop in-flight state and start the carrier (open socket). */
  start(): void
  /** Send a JSON-RPC request; resolves with `result` or rejects with the server's error. */
  request<T = unknown>(method: string, params?: Record<string, unknown>): Promise<T>
  /** Attach event/exit listeners. */
  on(event: 'event', handler: (ev: GatewayEvent) => void): void
  on(event: 'exit', handler: (code: number | null) => void): void
  /** Detach. */
  off(event: 'event', handler: (ev: GatewayEvent) => void): void
  off(event: 'exit', handler: (code: number | null) => void): void
  /** Flush buffered events to listeners — call once after attaching. */
  drain(): void
  /** Tail of captured stderr / transport log for diagnostics. */
  getLogTail(limit?: number): string
  /** Stop the carrier. Safe to call multiple times. */
  kill(): void
}

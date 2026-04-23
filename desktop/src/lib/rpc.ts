// Vendored subset of hermes-agent/ui-tui/src/lib/rpc.ts.
// Only the shape-checking helpers the CLI needs (asRpcResult, rpcErrorMessage).
// CommandDispatchResponse narrowing lives in the TUI and is not needed here.

// `any` (not `unknown`) inside the index type is deliberate — it lets the
// generic be satisfied by interfaces with known keys (SessionCreateResponse
// etc.) without forcing every caller to add an index signature. Matches the
// TUI's helper shape (ui-tui/src/lib/rpc.ts).
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type RpcResult = Record<string, any>

export const asRpcResult = <T extends RpcResult = RpcResult>(value: unknown): T | null =>
  !value || typeof value !== 'object' || Array.isArray(value) ? null : (value as T)

export const rpcErrorMessage = (err: unknown): string =>
  err instanceof Error && err.message
    ? err.message
    : typeof err === 'string' && err.trim()
      ? err
      : 'request failed'

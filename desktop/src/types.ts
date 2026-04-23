// Vendored from hermes-agent/ui-tui/src/types.ts (feat/tui-transport-pluggable).
// TUI-only view types (Msg, PanelData, DetailsMode, etc.) are omitted — this
// CLI doesn't render panels or manage composer state.

export interface McpServerStatus {
  connected: boolean
  name: string
  tools: number
  transport: string
}

export interface SessionInfo {
  cwd?: string
  mcp_servers?: McpServerStatus[]
  model: string
  release_date?: string
  skills: Record<string, string[]>
  tools: Record<string, string[]>
  update_behind?: number | null
  update_command?: string
  usage?: Usage
  version?: string
}

export interface Usage {
  calls: number
  context_max?: number
  context_percent?: number
  context_used?: number
  cost_usd?: number
  input: number
  output: number
  total: number
}

export interface SlashCategory {
  name: string
  pairs: [string, string][]
}

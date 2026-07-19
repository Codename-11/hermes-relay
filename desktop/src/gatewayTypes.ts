// Vendored from hermes-agent/ui-tui/src/gatewayTypes.ts (feat/tui-transport-pluggable)
// on 2026-04-23. Kept verbatim so both clients speak identical JSON-RPC to the
// `tui_gateway` subprocess. When the shape upstreams, collapse back into a
// shared package and re-import.

import type { SessionInfo, SlashCategory, Usage } from './types.js'

export interface GatewaySkin {
  banner_hero?: string
  banner_logo?: string
  branding?: Record<string, string>
  colors?: Record<string, string>
  help_header?: string
  tool_prefix?: string
}

export interface GatewayCompletionItem {
  display: string
  meta?: string
  text: string
}

export interface GatewayTranscriptMessage {
  context?: string
  name?: string
  role: 'assistant' | 'system' | 'tool' | 'user'
  text?: string
}

// ── Commands / completion ────────────────────────────────────────────

export interface CommandsCatalogResponse {
  canon?: Record<string, string>
  categories?: SlashCategory[]
  pairs?: [string, string][]
  skill_count?: number
  sub?: Record<string, string[]>
  warning?: string
}

export interface CompletionResponse {
  items?: GatewayCompletionItem[]
  replace_from?: number
}

export interface SlashExecResponse {
  output?: string
  warning?: string
}

export type CommandDispatchResponse =
  | { output?: string; type: 'exec' | 'plugin' }
  | { target: string; type: 'alias' }
  | { message?: string; name: string; type: 'skill' }
  | { message: string; type: 'send' }

// ── Session lifecycle ────────────────────────────────────────────────

export interface SessionCreateResponse {
  info?: SessionInfo & { credential_warning?: string }
  session_id: string
}

export interface SessionResumeResponse {
  inflight?: {
    assistant?: string
    streaming?: boolean
    user?: string
  }
  info?: SessionInfo
  message_count?: number
  messages: GatewayTranscriptMessage[]
  queued?: { user?: string }
  resumed?: string
  running?: boolean
  session_id: string
  status?: string
}

export type SessionResumeActivity = 'idle' | 'queued' | 'running' | 'running-and-queued'

/** Classify optional live-work fields added by newer vanilla Hermes gateways. */
export function sessionResumeActivity(response: SessionResumeResponse): SessionResumeActivity {
  const running = response.running === true || response.inflight?.streaming === true
  const queued = typeof response.queued?.user === 'string' && response.queued.user.trim().length > 0
  if (running && queued) return 'running-and-queued'
  if (running) return 'running'
  if (queued) return 'queued'
  return 'idle'
}

/** Return the accepted next-turn prompt as a safe one-line status preview. */
export function sessionQueuedPromptPreview(response: SessionResumeResponse, maxChars = 120): string | null {
  const compact = response.queued?.user
    ?.replaceAll(/\x1b\[[?\d;]*[a-zA-Z~]/g, '')
    .replaceAll(/\x1b\][^\x07]*(?:\x07|\x1b\\)/g, '')
    .replaceAll(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  if (!compact) return null
  const limit = Math.max(2, maxChars)
  return compact.length <= limit ? compact : `${compact.slice(0, limit - 1).trimEnd()}…`
}

/** Return a displayable project name without requiring newer gateway metadata. */
export function sessionProjectName(info: SessionInfo | undefined): string | null {
  const name = info?.project?.name?.trim()
  return name ? name : null
}

export interface SessionListItem {
  id: string
  message_count: number
  preview: string
  source?: string
  started_at: number
  title: string
}

export interface SessionListResponse {
  sessions?: SessionListItem[]
}

export interface SessionInterruptResponse {
  ok?: boolean
}

// ── Prompt ───────────────────────────────────────────────────────────

export interface PromptSubmitResponse {
  ok?: boolean
}

// ── Tool listing (tools.list) ────────────────────────────────────────

export interface ToolsetTool {
  name: string
  description?: string
}

export interface ToolsetInfo {
  name: string
  description?: string
  tool_count?: number
  enabled?: boolean
  tools?: ToolsetTool[]
}

export interface ToolsListResponse {
  toolsets?: ToolsetInfo[]
}

// ── Setup status ─────────────────────────────────────────────────────

export interface SetupStatusResponse {
  provider_configured?: boolean
}

// ── Subagent events ──────────────────────────────────────────────────

export interface SubagentEventPayload {
  duration_seconds?: number
  goal: string
  status?: 'completed' | 'failed' | 'interrupted' | 'running'
  summary?: string
  task_count?: number
  task_index: number
  text?: string
  tool_name?: string
  tool_preview?: string
}

// ── Gateway event discriminated union ────────────────────────────────

export type GatewayEvent =
  | { payload?: { skin?: GatewaySkin }; session_id?: string; type: 'gateway.ready' }
  | { payload?: GatewaySkin; session_id?: string; type: 'skin.changed' }
  | { payload: SessionInfo; session_id?: string; type: 'session.info' }
  | { payload?: { text?: string }; session_id?: string; type: 'thinking.delta' }
  | { payload?: undefined; session_id?: string; type: 'message.start' }
  | { payload?: { kind?: string; text?: string }; session_id?: string; type: 'status.update' }
  | { payload: { line: string }; session_id?: string; type: 'gateway.stderr' }
  | { payload?: { cwd?: string; python?: string }; session_id?: string; type: 'gateway.start_timeout' }
  | { payload?: { preview?: string }; session_id?: string; type: 'gateway.protocol_error' }
  | { payload?: { text?: string }; session_id?: string; type: 'reasoning.delta' | 'reasoning.available' }
  | { payload: { name?: string; preview?: string }; session_id?: string; type: 'tool.progress' }
  | { payload: { name?: string }; session_id?: string; type: 'tool.generating' }
  | { payload: { context?: string; name?: string; tool_id: string }; session_id?: string; type: 'tool.start' }
  | {
      payload: { error?: string; inline_diff?: string; name?: string; summary?: string; tool_id: string }
      session_id?: string
      type: 'tool.complete'
    }
  | {
      payload: { choices: string[] | null; question: string; request_id: string }
      session_id?: string
      type: 'clarify.request'
    }
  | { payload: { command: string; description: string }; session_id?: string; type: 'approval.request' }
  | { payload: { request_id: string }; session_id?: string; type: 'sudo.request' }
  | { payload: { env_var: string; prompt: string; request_id: string }; session_id?: string; type: 'secret.request' }
  | { payload: { task_id: string; text: string }; session_id?: string; type: 'background.complete' }
  | { payload: { text: string }; session_id?: string; type: 'btw.complete' }
  | {
      payload: { path?: string; title?: string; url?: string }
      session_id?: string
      type: 'artifact.created'
    }
  | { payload: { detail?: string }; session_id?: string; type: 'memory.updated' | 'skill.loaded' }
  | { payload: SubagentEventPayload; session_id?: string; type: 'subagent.start' }
  | { payload: SubagentEventPayload; session_id?: string; type: 'subagent.thinking' }
  | { payload: SubagentEventPayload; session_id?: string; type: 'subagent.tool' }
  | { payload: SubagentEventPayload; session_id?: string; type: 'subagent.progress' }
  | { payload: SubagentEventPayload; session_id?: string; type: 'subagent.complete' }
  | { payload: { rendered?: string; text?: string }; session_id?: string; type: 'message.delta' }
  | {
      payload?: { reasoning?: string; rendered?: string; text?: string; usage?: Usage }
      session_id?: string
      type: 'message.complete'
    }
  | { payload?: { message?: string }; session_id?: string; type: 'error' }

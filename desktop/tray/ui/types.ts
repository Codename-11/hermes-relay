export type AccessMode = 'ask' | 'ask-every-time' | 'structured' | 'trusted' | 'full-access' | 'custom'
export type CapabilityMode = 'disabled' | 'ask' | 'allow'
export type Capability = 'commands' | 'files' | 'screen_input' | 'usb' | 'microphone' | 'camera'

export interface Host {
  url: string
  name: string
  server_version?: string | null
  endpoint_role?: string | null
  paired_at?: number | null
  is_active: boolean
  access_mode: AccessMode
  capabilities: Record<Capability, CapabilityMode>
  broker_configured?: boolean
}

export interface DaemonStatus {
  state: string
  running: boolean
  url?: string | null
  configured_url?: string | null
  active_route?: string | null
  privilege?: string | null
  username?: string | null
  updated_at?: number | null
}

export interface ComputerControlEngine {
  selected: 'legacy' | 'cua'
  effective: 'legacy' | 'cua' | 'unavailable'
  available: boolean
  state: 'not_installed' | 'incompatible' | 'degraded' | 'ready' | 'error'
  version?: string | null
  health?: string | null
  path?: string | null
  cursor_enabled?: boolean
  foreground_escalation_enabled: boolean
  active_sessions?: number
  active_backend?: 'cua' | 'legacy_compat' | 'mixed' | 'idle' | null
  last_action?: {
    action?: string | null
    target_app?: string | null
    target_title?: string | null
    target_pid?: number | null
    target_window_id?: number | null
    verification?: string | null
    occurred_at?: number | string | null
  } | null
  message?: string | null
}

export interface CuaManagementStatus {
  installed: boolean
  canonical_path?: string | null
  discovered_path?: string | null
  stale_path_shim: boolean
  current_version?: string | null
  compatible: boolean
  compatibility_reason?: string | null
  supported_range: { minimum: string; maximum_exclusive: string }
  update?: { latest_version?: string; update_available: boolean; compatible: boolean; error?: string; release_notes_url?: string }
  operation?: { kind: 'install' | 'update'; state: 'completed'; version: string }
}

export interface Activity {
  ts: number
  kind?: 'tool.completed' | 'management.completed'
  tool: string
  category?: 'command' | 'files' | 'screen' | 'input' | 'devices' | 'system' | 'other'
  ok: boolean
  aborted?: boolean
  request_id?: string
  host_url?: string
  backend?: string
  dispatch?: string
  control_session_id?: string
  target_app?: string
  target_title?: string
  target_pid?: number
  target_window_id?: number
  action?: string
  verification?: string
  phase?: string
  duration_ms?: number
  exit_code?: number
  summary?: string
  args_preview?: string
  request_detail?: string
  stdout?: string
  stderr?: string
  result_detail?: string
  request_truncated?: boolean
  stdout_truncated?: boolean
  stderr_truncated?: boolean
  result_truncated?: boolean
  error?: string
}

export interface Snapshot {
  hosts: Host[]
  active_url?: string | null
  daemon: DaemonStatus
  activity: Activity[]
  pending_grants: PendingGrantRequest[]
  startup_enabled: boolean
  daemon_autostart_enabled?: boolean
  ui_version?: string | null
  cli_version?: string | null
  cli_path?: string | null
  hardware_availability: { usb: boolean; adb: boolean; microphone: boolean; camera: boolean }
  computer_control_engine?: ComputerControlEngine | null
}

export interface PendingGrantRequest {
  id: string
  mode: string
  duration_seconds: number
  reason: string
  created_at: string
  scope?: unknown
}

export interface AuthorizedClient {
  token_prefix: string
  device_name?: string
  last_seen?: number
  expires_at?: number | null
  transport_hint?: string
  client_surface?: string
  device_form_factor?: string
  device_model?: string
  device_platform?: string
  grants?: Record<string, number | null>
  is_current?: boolean
}

export interface UpdateReport {
  current: string
  up_to_date: boolean
  ahead_of_latest?: boolean
  latest_tag?: string | null
  latest_version?: string | null
  is_prerelease?: boolean
  published_at?: string | null
  installed: boolean
  needs_restart: boolean
  error?: string | null
}

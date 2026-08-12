export type AccessMode = 'ask' | 'structured' | 'trusted' | 'full-access'
export type CapabilityMode = 'disabled' | 'ask' | 'allow'

export interface Host {
  url: string
  name: string
  server_version?: string | null
  endpoint_role?: string | null
  paired_at?: number | null
  is_active: boolean
  access_mode: AccessMode
  capabilities: { usb: CapabilityMode; microphone: CapabilityMode; camera: CapabilityMode }
}

export interface DaemonStatus {
  state: string
  running: boolean
  url?: string | null
  privilege?: string | null
  username?: string | null
  updated_at?: number | null
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
  duration_ms?: number
  exit_code?: number
  summary?: string
  args_preview?: string
  error?: string
}

export interface Snapshot {
  hosts: Host[]
  active_url?: string | null
  daemon: DaemonStatus
  activity: Activity[]
  pending_grants: PendingGrantRequest[]
  startup_enabled: boolean
  hardware_availability: { usb: boolean; adb: boolean; microphone: boolean; camera: boolean }
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

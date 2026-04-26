export type ComputerGrantMode = 'observe' | 'assist' | 'control'

export interface ComputerGrantScope {
  display?: string
  app?: string
  folder?: string
}

export interface ComputerGrant {
  id: string
  mode: ComputerGrantMode
  scope: ComputerGrantScope
  reason: string
  created_at: string
  expires_at: string
}

let activeGrant: ComputerGrant | null = null

function nowMs(): number {
  return Date.now()
}

function newGrantId(): string {
  return `computer-grant-${nowMs().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

function parseScope(value: unknown): ComputerGrantScope {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return {}
  }
  const raw = value as Record<string, unknown>
  const scope: ComputerGrantScope = {}
  if (typeof raw.display === 'string' && raw.display.trim()) {
    scope.display = raw.display.trim()
  }
  if (typeof raw.app === 'string' && raw.app.trim()) {
    scope.app = raw.app.trim()
  }
  if (typeof raw.folder === 'string' && raw.folder.trim()) {
    scope.folder = raw.folder.trim()
  }
  return scope
}

function expireIfNeeded(): void {
  if (!activeGrant) {
    return
  }
  if (Date.parse(activeGrant.expires_at) <= nowMs()) {
    activeGrant = null
  }
}

export function getActiveComputerGrant(): ComputerGrant | null {
  expireIfNeeded()
  return activeGrant
}

export function getComputerGrantSummary(): Record<string, unknown> {
  const grant = getActiveComputerGrant()
  if (!grant) {
    return {
      active: false,
      mode: 'none',
      expires_at: null,
      scope: null
    }
  }
  return {
    active: true,
    id: grant.id,
    mode: grant.mode,
    expires_at: grant.expires_at,
    scope: grant.scope,
    reason: grant.reason
  }
}

export interface RequestComputerGrantInput {
  mode: ComputerGrantMode
  scope?: unknown
  duration_seconds?: unknown
  reason?: unknown
}

export function requestComputerGrant(input: RequestComputerGrantInput): Record<string, unknown> {
  const mode = input.mode
  const reason =
    typeof input.reason === 'string' && input.reason.trim()
      ? input.reason.trim()
      : 'No reason provided.'
  const durationSeconds =
    typeof input.duration_seconds === 'number' && Number.isFinite(input.duration_seconds)
      ? Math.max(1, Math.min(Math.floor(input.duration_seconds), 3600))
      : 900

  if (mode !== 'observe') {
    return {
      ok: false,
      code: 'not_implemented',
      message:
        'Assist/control grants require a visible local overlay or per-action prompt. The CLI-only Phase 1 implementation only grants observe mode.',
      grant: getComputerGrantSummary()
    }
  }

  const createdAt = new Date(nowMs())
  const grant: ComputerGrant = {
    id: newGrantId(),
    mode,
    scope: parseScope(input.scope),
    reason,
    created_at: createdAt.toISOString(),
    expires_at: new Date(createdAt.getTime() + durationSeconds * 1000).toISOString()
  }
  activeGrant = grant

  return {
    ok: true,
    grant: getComputerGrantSummary(),
    message: 'Observe grant active. Screenshot/status tools may run; host input remains unavailable.'
  }
}

export function cancelComputerGrant(reason = 'cancelled'): Record<string, unknown> {
  const previous = getActiveComputerGrant()
  activeGrant = null
  return {
    ok: true,
    cancelled: previous !== null,
    previous_grant: previous,
    reason,
    grant: getComputerGrantSummary()
  }
}

export function hasComputerInputGrant(): boolean {
  const grant = getActiveComputerGrant()
  return grant?.mode === 'assist' || grant?.mode === 'control'
}

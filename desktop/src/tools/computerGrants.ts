import { randomUUID } from 'node:crypto'

import {
  DEFAULT_CAPABILITY_POLICIES,
  presetCapabilityPolicies,
  type CapabilityPolicies,
  type HostAccessMode
} from '../lib/hostAccessPolicy.js'
import {
  LEGACY_CONTROL_SESSION_ID,
  type ComputerControlAuthority,
  type ComputerTarget
} from './computerControlSecurity.js'

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

export interface ComputerUseRuntime {
  url: string | null
  computerUseConsented: boolean
  consentSource: 'stored' | 'prompted' | 'override' | 'none'
  accessMode: HostAccessMode
  capabilities: CapabilityPolicies
}

interface ControlSessionState {
  activeGrant: ComputerGrant | null
  runtime: ComputerUseRuntime
}

const defaultRuntime = (): ComputerUseRuntime => ({
  url: null,
  computerUseConsented: false,
  consentSource: 'none',
  accessMode: 'ask',
  capabilities: { ...DEFAULT_CAPABILITY_POLICIES }
})

const controlSessions = new Map<string, ControlSessionState>()
let grantChangeListener: ((grant: ComputerGrant | null, controlSessionId?: string) => void) | null = null

function authorityKey(authority?: ComputerControlAuthority): string {
  return authority?.controlSessionId || LEGACY_CONTROL_SESSION_ID
}

function sessionState(authority?: ComputerControlAuthority): ControlSessionState {
  const key = authorityKey(authority)
  let state = controlSessions.get(key)
  if (!state) {
    state = { activeGrant: null, runtime: defaultRuntime() }
    controlSessions.set(key, state)
  }
  return state
}

function nowMs(): number {
  return Date.now()
}

function newGrantId(): string {
  return `computer-grant-${randomUUID()}`
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

export function normalizeComputerGrantScope(value: unknown): ComputerGrantScope {
  return parseScope(value)
}

export function normalizeComputerGrantReason(value: unknown): string {
  return typeof value === 'string' && value.trim()
    ? value.trim()
    : 'No reason provided.'
}

export function normalizeComputerGrantDurationSeconds(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value)
    ? Math.max(1, Math.min(Math.floor(value), 3600))
    : 900
}

function expireIfNeeded(authority?: ComputerControlAuthority): void {
  const state = sessionState(authority)
  if (!state.activeGrant) {
    return
  }
  if (Date.parse(state.activeGrant.expires_at) <= nowMs()) {
    state.activeGrant = null
    grantChangeListener?.(null, authorityKey(authority))
  }
}

export function setComputerGrantChangeListener(
  listener: ((grant: ComputerGrant | null, controlSessionId?: string) => void) | null
): () => void {
  const previous = grantChangeListener
  grantChangeListener = listener
  return () => {
    grantChangeListener = previous
  }
}

export function getActiveComputerGrant(authority?: ComputerControlAuthority): ComputerGrant | null {
  expireIfNeeded(authority)
  return sessionState(authority).activeGrant
}

export function getComputerGrantSummary(authority?: ComputerControlAuthority): Record<string, unknown> {
  const state = sessionState(authority)
  if (state.runtime.capabilities.screen_input === 'allow') {
    return {
      active: true,
      mode: state.runtime.accessMode === 'full_access' ? 'full_access' : 'capability_allow',
      expires_at: null,
      scope: null,
      reason: state.runtime.accessMode === 'full_access'
        ? 'This host has Full Access.'
        : 'Screen and input are allowed by this host policy.'
    }
  }
  const grant = getActiveComputerGrant(authority)
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

export function configureComputerUseRuntime(next: Partial<ComputerUseRuntime>, authority?: ComputerControlAuthority): void {
  const state = sessionState(authority)
  const capabilities = next.capabilities ?? (next.accessMode ? presetCapabilityPolicies(next.accessMode) : state.runtime.capabilities)
  state.runtime = {
    ...state.runtime,
    ...next,
    capabilities
  }
}

export function getComputerUseRuntimeSummary(authority?: ComputerControlAuthority): Record<string, unknown> {
  const runtime = sessionState(authority).runtime
  return {
    url: runtime.url,
    consented: runtime.computerUseConsented,
    consent_source: runtime.consentSource,
    access_mode: runtime.accessMode,
    full_access: runtime.accessMode === 'full_access',
    capabilities: { ...runtime.capabilities }
  }
}

export interface RequestComputerGrantInput {
  mode: ComputerGrantMode
  scope?: unknown
  duration_seconds?: unknown
  reason?: unknown
}

export function requestComputerGrant(input: RequestComputerGrantInput, authority?: ComputerControlAuthority): Record<string, unknown> {
  const state = sessionState(authority)
  const runtime = state.runtime
  if (runtime.capabilities.screen_input === 'allow') {
    return {
      ok: true,
      full_access: runtime.accessMode === 'full_access',
      grant: getComputerGrantSummary(authority),
      message: 'This host already has Full Access; no task grant is required.'
    }
  }
  const mode = input.mode
  const reason = normalizeComputerGrantReason(input.reason)
  const durationSeconds = normalizeComputerGrantDurationSeconds(input.duration_seconds)

  if (mode !== 'observe' && !runtime.computerUseConsented) {
    return {
      ok: false,
      code: 'computer_use_consent_required',
      message:
        'Assist/control grants require local desktop-tool consent for this relay URL before task-scoped input grants can be created.',
      grant: getComputerGrantSummary(authority)
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
  state.activeGrant = grant
  grantChangeListener?.(grant, authorityKey(authority))

  return {
    ok: true,
    grant: getComputerGrantSummary(authority),
    message:
      mode === 'observe'
        ? 'Observe grant active. Screenshot/status tools may run.'
        : 'Input grant active. Host input may run without per-action prompts until the grant expires or is canceled.'
  }
}

export function cancelComputerGrant(reason = 'cancelled', authority?: ComputerControlAuthority): Record<string, unknown> {
  const state = sessionState(authority)
  const previous = getActiveComputerGrant(authority)
  state.activeGrant = null
  if (previous) grantChangeListener?.(null, authorityKey(authority))
  return {
    ok: true,
    cancelled: previous !== null,
    previous_grant: previous,
    reason,
    grant: getComputerGrantSummary(authority)
  }
}

export function hasComputerInputGrant(authority?: ComputerControlAuthority): boolean {
  const runtime = sessionState(authority).runtime
  if (runtime.capabilities.screen_input === 'allow') return true
  const grant = getActiveComputerGrant(authority)
  return grant?.mode === 'assist' || grant?.mode === 'control'
}

export function hasComputerObserveGrant(authority?: ComputerControlAuthority): boolean {
  const runtime = sessionState(authority).runtime
  return runtime.capabilities.screen_input === 'allow' || getActiveComputerGrant(authority) !== null
}

/** Seed a new router lifecycle from the legacy process-level configuration. */
export function initializeComputerControlSession(authority: ComputerControlAuthority): void {
  if (controlSessions.has(authorityKey(authority))) return
  const legacy = sessionState().runtime
  controlSessions.set(authorityKey(authority), {
    activeGrant: null,
    runtime: { ...legacy, capabilities: { ...legacy.capabilities } }
  })
}

export function hasFullHostAccess(authority?: ComputerControlAuthority): boolean {
  return sessionState(authority).runtime.accessMode === 'full_access'
}

export function revokeComputerControlSession(authority: ComputerControlAuthority, reason = 'control session ended'): void {
  cancelComputerGrant(reason, authority)
  controlSessions.delete(authorityKey(authority))
}

export function cancelAllComputerGrants(_reason = 'cancelled locally'): number {
  let cancelled = 0
  for (const [controlSessionId, state] of controlSessions) {
    if (!state.activeGrant) continue
    state.activeGrant = null
    cancelled += 1
    grantChangeListener?.(null, controlSessionId)
  }
  return cancelled
}

export function expireComputerControlSessions(): void {
  for (const controlSessionId of controlSessions.keys()) {
    expireIfNeeded({ controlSessionId })
  }
}

/** Enforce the scope shown in the approval prompt before a broker acts. */
export function computerGrantAllowsTarget(authority: ComputerControlAuthority, target: ComputerTarget): boolean {
  const runtime = sessionState(authority).runtime
  if (runtime.capabilities.screen_input === 'allow') return true
  const grant = getActiveComputerGrant(authority)
  if (!grant) return false
  if (grant.scope.display && grant.scope.display !== target.display) return false
  if (grant.scope.app) {
    const actual = [target.app, target.executable].filter(Boolean).join(' ').toLowerCase()
    if (!actual || !actual.includes(grant.scope.app.toLowerCase())) return false
  }
  if (grant.scope.folder) {
    if (!target.folder) return false
    const expected = grant.scope.folder.replaceAll('\\', '/').replace(/\/+$/, '').toLowerCase()
    const actual = target.folder.replaceAll('\\', '/').toLowerCase()
    if (actual !== expected && !actual.startsWith(`${expected}/`)) return false
  }
  return true
}

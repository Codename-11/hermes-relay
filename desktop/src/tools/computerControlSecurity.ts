import { randomUUID } from 'node:crypto'

export const LEGACY_CONTROL_SESSION_ID = 'legacy-local-control-session'

export interface ComputerControlAuthority {
  /** Server-attested identity for one attached desktop router lifecycle. */
  controlSessionId: string
  /** Canonical relay URL when known. Never supplied by remote tool arguments. */
  hostUrl?: string
  /** Request currently being dispatched. Bound by the router, not the caller. */
  requestId?: string
  /** Reserved for authenticated relay identity once the wire protocol supplies it. */
  relaySessionId?: string
  requesterDeviceId?: string
  chatSessionId?: string
  runId?: string
  targetDeviceId?: string
}

export interface ComputerTarget {
  pid: number
  windowId: number
  app?: string
  title?: string
  executable?: string
  display?: string
  folder?: string
}

export interface SnapshotBindingInput {
  authority: ComputerControlAuthority
  grantId: string | null
  target: ComputerTarget
  snapshotGeneration: string
  driverElementToken?: string
  ttlMs?: number
}

interface SnapshotBinding extends SnapshotBindingInput {
  expiresAt: number
}

export type SensitiveTargetDecision =
  | { allowed: true; reason: 'not_sensitive' }
  | { allowed: false; reason: 'missing_target_identity' | 'sensitive_target'; matched?: string }

const DEFAULT_SENSITIVE_PATTERNS = Object.freeze([
  /\b(password|passkey|credential|authenticator|security key)\b/i,
  /\b(1password|bitwarden|keepass|lastpass|dashlane|proton pass)\b/i,
  /\b(bank|banking|payment|checkout|wallet|crypto|cryptocurrency)\b/i,
  /\b(certificate|private[ _-]?key|api[ _-]?key|access[ _-]?token|secret)\b/i,
  /\b(user account control|windows security|credential manager|security settings)\b/i
])

/**
 * Hermes-owned state for a single router lifecycle. It intentionally does not
 * execute input. It binds remote requests, local grants, snapshots, and opaque
 * element handles before an execution backend (legacy or CUA) is invoked.
 */
export class ComputerControlSecurityState {
  private readonly seenRequestIds = new Map<string, { at: number; controlSessionId: string }>()
  private readonly snapshotBindings = new Map<string, SnapshotBinding>()

  constructor(
    readonly authority: ComputerControlAuthority,
    private readonly maxRememberedRequests = 2_048
  ) {}

  bindRequest(
    requestId: string,
    authority: ComputerControlAuthority = this.authority
  ): ComputerControlAuthority | null {
    const normalized = requestId.trim()
    if (!normalized || normalized.length > 256 || this.seenRequestIds.has(normalized)) return null
    this.seenRequestIds.set(normalized, { at: Date.now(), controlSessionId: authority.controlSessionId })
    while (this.seenRequestIds.size > this.maxRememberedRequests) {
      const oldest = this.seenRequestIds.keys().next().value as string | undefined
      if (!oldest) break
      this.seenRequestIds.delete(oldest)
    }
    return { ...authority, requestId: normalized }
  }

  issueSnapshotToken(input: SnapshotBindingInput): string {
    if (!input.authority.controlSessionId.trim()) throw new Error('snapshot authority requires a control session id')
    const token = `hermes-snapshot-${randomUUID()}`
    const ttlMs = Math.max(1_000, Math.min(input.ttlMs ?? 60_000, 300_000))
    this.snapshotBindings.set(token, { ...input, expiresAt: Date.now() + ttlMs })
    return token
  }

  consumeSnapshotToken(
    token: string,
    expected: Omit<SnapshotBindingInput, 'ttlMs' | 'driverElementToken' | 'snapshotGeneration'> & {
      snapshotGeneration?: string
    }
  ): SnapshotBinding | null {
    const binding = this.snapshotBindings.get(token)
    this.snapshotBindings.delete(token)
    if (!binding || binding.expiresAt <= Date.now()) return null
    if (binding.authority.controlSessionId !== expected.authority.controlSessionId) return null
    if (binding.grantId !== expected.grantId) return null
    if (binding.target.pid !== expected.target.pid || binding.target.windowId !== expected.target.windowId) return null
    if (expected.snapshotGeneration && binding.snapshotGeneration !== expected.snapshotGeneration) return null
    return binding
  }

  revoke(): void {
    this.seenRequestIds.clear()
    this.snapshotBindings.clear()
  }

  /** Revoke only artifacts owned by one concurrent relay control session. */
  revokeAuthority(controlSessionId: string): void {
    for (const [requestId, binding] of this.seenRequestIds) {
      if (binding.controlSessionId === controlSessionId) this.seenRequestIds.delete(requestId)
    }
    for (const [token, binding] of this.snapshotBindings) {
      if (binding.authority.controlSessionId === controlSessionId) this.snapshotBindings.delete(token)
    }
  }
}

export function hasAuthenticatedControlIdentity(
  authority: ComputerControlAuthority | undefined
): authority is ComputerControlAuthority & Required<Pick<ComputerControlAuthority,
  'relaySessionId' | 'requesterDeviceId' | 'runId' | 'targetDeviceId'>> {
  return !!authority && [
    authority.controlSessionId,
    authority.relaySessionId,
    authority.requesterDeviceId,
    authority.runId,
    authority.targetDeviceId
  ].every(value => typeof value === 'string' && value.trim().length > 0)
}

/** Fail closed when a brokered action does not identify its app/window. */
export function evaluateSensitiveTarget(
  target: Pick<ComputerTarget, 'app' | 'title' | 'executable'>,
  extraPatterns: readonly RegExp[] = []
): SensitiveTargetDecision {
  const identity = [target.app, target.title, target.executable]
    .filter((value): value is string => typeof value === 'string' && value.trim().length > 0)
    .join(' | ')
  if (!identity) return { allowed: false, reason: 'missing_target_identity' }
  for (const pattern of [...DEFAULT_SENSITIVE_PATTERNS, ...extraPatterns]) {
    pattern.lastIndex = 0
    const match = pattern.exec(identity)
    if (match) return { allowed: false, reason: 'sensitive_target', matched: match[0] }
  }
  return { allowed: true, reason: 'not_sensitive' }
}

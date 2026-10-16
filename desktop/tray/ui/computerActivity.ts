import type { Activity } from './types'
import type { AuditEntry } from '../../src/lib/auditLog'

// Include historical values: structured snapshots have always recorded
// cua_driver, while structured actions record cua. Do not rewrite old logs.
const backendLabels: Record<NonNullable<AuditEntry['backend']>, { compact: string; expanded: string }> = {
  cua: { compact: 'CUA', expanded: 'CUA structured engine' },
  cua_driver: { compact: 'CUA', expanded: 'CUA structured engine' },
  legacy_compat: { compact: 'Compatibility', expanded: 'Windows input · Compatibility' },
  system_capture: { compact: 'System capture', expanded: 'System capture' }
}

export function computerActivityDetail(entry: Pick<Activity, 'backend' | 'dispatch'>, expanded = false): string {
  const labels = entry.backend && Object.hasOwn(backendLabels, entry.backend)
    ? backendLabels[entry.backend as keyof typeof backendLabels] : undefined
  const backend = labels ? labels[expanded ? 'expanded' : 'compact']
    : entry.backend ? 'Unknown backend' : 'Backend not recorded'
  // A selected engine is not evidence that a particular operation used it.
  // Likewise, status/grant/cancel and captures may have no input dispatch.
  return [backend, entry.dispatch?.replaceAll('_', ' ').trim()].filter(Boolean).join(' · ')
}

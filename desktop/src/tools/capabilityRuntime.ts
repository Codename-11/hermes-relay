import type { CapabilityPolicies, HostCapability } from '../lib/hostAccessPolicy.js'
import { DEFAULT_CAPABILITY_POLICIES } from '../lib/hostAccessPolicy.js'

let policies: CapabilityPolicies = { ...DEFAULT_CAPABILITY_POLICIES }

export function configureCapabilityPolicies(next: CapabilityPolicies): void {
  policies = { ...next }
}

export function capabilityPolicy(capability: HostCapability) {
  return policies[capability]
}

export function capabilitySummary(): CapabilityPolicies {
  return { ...policies }
}

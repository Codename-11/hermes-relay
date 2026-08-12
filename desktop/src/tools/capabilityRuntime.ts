import type { CapabilityPolicies, HardwareCapability } from '../lib/hostAccessPolicy.js'
import { DEFAULT_CAPABILITY_POLICIES } from '../lib/hostAccessPolicy.js'

let policies: CapabilityPolicies = { ...DEFAULT_CAPABILITY_POLICIES }

export function configureCapabilityPolicies(next: CapabilityPolicies): void {
  policies = { ...next }
}

export function capabilityPolicy(capability: HardwareCapability) {
  return policies[capability]
}

export function capabilitySummary(): CapabilityPolicies {
  return { ...policies }
}

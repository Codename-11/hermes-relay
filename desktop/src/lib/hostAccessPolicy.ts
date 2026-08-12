import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'

export const HOST_ACCESS_MODES = ['ask', 'structured', 'trusted', 'full_access'] as const
export const HARDWARE_CAPABILITIES = ['usb', 'microphone', 'camera'] as const
export const CAPABILITY_ACCESS_MODES = ['disabled', 'ask', 'allow'] as const

export type HostAccessMode = (typeof HOST_ACCESS_MODES)[number]
export type HardwareCapability = (typeof HARDWARE_CAPABILITIES)[number]
export type CapabilityAccessMode = (typeof CAPABILITY_ACCESS_MODES)[number]

export type CapabilityPolicies = Record<HardwareCapability, CapabilityAccessMode>

export interface HostAccessPolicy {
  access_mode: HostAccessMode
  capabilities: CapabilityPolicies
  updated_at?: string
}

export interface HostAccessPolicyFile {
  version: 1
  hosts: Record<string, HostAccessPolicy>
}

const STORE_VERSION = 1 as const
export const DEFAULT_CAPABILITY_POLICIES: CapabilityPolicies = Object.freeze({
  usb: 'disabled',
  microphone: 'disabled',
  camera: 'disabled'
})

export function hostAccessPolicyPath(): string {
  return process.env.HERMES_RELAY_HOST_ACCESS_POLICY_PATH ??
    join(homedir(), '.hermes', 'desktop-host-access.json')
}

/**
 * Return the stable identity used for per-host policy. Relay URLs are limited
 * to WebSocket schemes; credentials, query strings, and fragments are rejected
 * because they are connection details, not host identity.
 */
export function canonicalRelayUrl(raw: string): string | null {
  try {
    const url = new URL(raw.trim())
    if (url.protocol !== 'ws:' && url.protocol !== 'wss:') return null
    if (!url.hostname || url.username || url.password || url.search || url.hash) return null

    url.pathname = url.pathname.replace(/\/+$/, '') || '/'
    const canonical = url.toString()
    return url.pathname === '/' ? canonical.replace(/\/$/, '') : canonical
  } catch {
    return null
  }
}

export function isHostAccessMode(value: unknown): value is HostAccessMode {
  return typeof value === 'string' && HOST_ACCESS_MODES.includes(value as HostAccessMode)
}

const emptyStore = (): HostAccessPolicyFile => ({ version: STORE_VERSION, hosts: {} })

const restrictiveness = (mode: HostAccessMode): number => HOST_ACCESS_MODES.indexOf(mode)
const capabilityRestrictiveness = (mode: CapabilityAccessMode): number => CAPABILITY_ACCESS_MODES.indexOf(mode)

function parsePolicy(value: unknown): HostAccessPolicy | null {
  if (isHostAccessMode(value)) {
    return { access_mode: value, capabilities: { ...DEFAULT_CAPABILITY_POLICIES } }
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null

  const raw = value as Record<string, unknown>
  const mode = raw.access_mode ?? raw.mode
  if (!isHostAccessMode(mode)) return null
  const rawCapabilities = raw.capabilities && typeof raw.capabilities === 'object' && !Array.isArray(raw.capabilities)
    ? raw.capabilities as Record<string, unknown>
    : {}
  const capabilities = { ...DEFAULT_CAPABILITY_POLICIES }
  for (const capability of HARDWARE_CAPABILITIES) {
    const value = rawCapabilities[capability]
    if (typeof value === 'string' && CAPABILITY_ACCESS_MODES.includes(value as CapabilityAccessMode)) {
      capabilities[capability] = value as CapabilityAccessMode
    }
  }
  return {
    access_mode: mode,
    capabilities,
    updated_at: typeof raw.updated_at === 'string' ? raw.updated_at : undefined
  }
}

function mergePolicy(
  hosts: Record<string, HostAccessPolicy>,
  rawUrl: string,
  policy: HostAccessPolicy
): void {
  const url = canonicalRelayUrl(rawUrl)
  if (!url) return

  const current = hosts[url]
  if (!current) {
    hosts[url] = policy
    return
  }
  hosts[url] = {
    access_mode: restrictiveness(policy.access_mode) < restrictiveness(current.access_mode)
      ? policy.access_mode
      : current.access_mode,
    capabilities: Object.fromEntries(HARDWARE_CAPABILITIES.map(capability => {
      const currentMode = current.capabilities[capability]
      const incomingMode = policy.capabilities[capability]
      return [capability, capabilityRestrictiveness(incomingMode) < capabilityRestrictiveness(currentMode)
        ? incomingMode
        : currentMode]
    })) as CapabilityPolicies
  }
}

/**
 * Parse the versioned store and the two pre-release shapes used by prototypes:
 * a bare URL-to-policy map and trusted_hosts/full_access_hosts arrays. Unknown
 * data is ignored and duplicate canonical URLs resolve to the least privilege.
 */
export function parseHostAccessPolicyFile(value: unknown): HostAccessPolicyFile {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return emptyStore()

  const raw = value as Record<string, unknown>
  const hosts: Record<string, HostAccessPolicy> = {}
  const policyMap = raw.hosts && typeof raw.hosts === 'object' && !Array.isArray(raw.hosts)
    ? raw.hosts as Record<string, unknown>
    : raw

  for (const [url, value] of Object.entries(policyMap)) {
    const policy = parsePolicy(value)
    if (policy) mergePolicy(hosts, url, policy)
  }

  for (const [field, mode] of [
    ['trusted_hosts', 'trusted'],
    ['full_access_hosts', 'full_access']
  ] as const) {
    const urls = raw[field]
    if (!Array.isArray(urls)) continue
    for (const url of urls) {
      if (typeof url === 'string') {
        mergePolicy(hosts, url, {
          access_mode: mode,
          capabilities: { ...DEFAULT_CAPABILITY_POLICIES }
        })
      }
    }
  }

  return { version: STORE_VERSION, hosts }
}

export async function readHostAccessPolicies(
  filePath = hostAccessPolicyPath()
): Promise<HostAccessPolicyFile> {
  try {
    return parseHostAccessPolicyFile(JSON.parse(await readFile(filePath, 'utf8')))
  } catch {
    return emptyStore()
  }
}

async function writeJsonAtomic(filePath: string, value: unknown): Promise<void> {
  await mkdir(dirname(filePath), { recursive: true, mode: 0o700 })
  const temporaryPath = `${filePath}.${process.pid}.${Date.now()}.tmp`
  try {
    await writeFile(temporaryPath, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 })
    await rename(temporaryPath, filePath)
  } finally {
    await rm(temporaryPath, { force: true }).catch(() => undefined)
  }
}

export async function getHostAccessMode(
  relayUrl: string,
  filePath = hostAccessPolicyPath()
): Promise<HostAccessMode> {
  const canonical = canonicalRelayUrl(relayUrl)
  if (!canonical) return 'ask'
  return (await readHostAccessPolicies(filePath)).hosts[canonical]?.access_mode ?? 'ask'
}

export async function setHostAccessMode(
  relayUrl: string,
  accessMode: HostAccessMode,
  filePath = hostAccessPolicyPath()
): Promise<HostAccessPolicy> {
  const canonical = canonicalRelayUrl(relayUrl)
  if (!canonical) throw new Error('host access policy requires a valid ws:// or wss:// relay URL')
  if (!isHostAccessMode(accessMode)) throw new Error(`unsupported host access mode: ${String(accessMode)}`)

  const store = await readHostAccessPolicies(filePath)
  const current = store.hosts[canonical]
  const policy: HostAccessPolicy = {
    access_mode: accessMode,
    capabilities: current?.capabilities ?? { ...DEFAULT_CAPABILITY_POLICIES },
    updated_at: new Date().toISOString()
  }
  store.hosts[canonical] = policy
  await writeJsonAtomic(filePath, store)
  return policy
}

export async function getHostCapabilityPolicies(
  relayUrl: string,
  filePath = hostAccessPolicyPath()
): Promise<CapabilityPolicies> {
  const canonical = canonicalRelayUrl(relayUrl)
  if (!canonical) return { ...DEFAULT_CAPABILITY_POLICIES }
  return (await readHostAccessPolicies(filePath)).hosts[canonical]?.capabilities ??
    { ...DEFAULT_CAPABILITY_POLICIES }
}

export async function setHostCapabilityPolicy(
  relayUrl: string,
  capability: HardwareCapability,
  mode: CapabilityAccessMode,
  filePath = hostAccessPolicyPath()
): Promise<HostAccessPolicy> {
  const canonical = canonicalRelayUrl(relayUrl)
  if (!canonical) throw new Error('host capability policy requires a valid relay URL')
  if (!HARDWARE_CAPABILITIES.includes(capability)) throw new Error(`unsupported capability: ${capability}`)
  if (!CAPABILITY_ACCESS_MODES.includes(mode)) throw new Error(`unsupported capability mode: ${mode}`)
  const store = await readHostAccessPolicies(filePath)
  const current = store.hosts[canonical] ?? {
    access_mode: 'ask' as HostAccessMode,
    capabilities: { ...DEFAULT_CAPABILITY_POLICIES }
  }
  const policy: HostAccessPolicy = {
    ...current,
    capabilities: { ...current.capabilities, [capability]: mode },
    updated_at: new Date().toISOString()
  }
  store.hosts[canonical] = policy
  await writeJsonAtomic(filePath, store)
  return policy
}

export async function removeHostAccessPolicy(
  relayUrl: string,
  filePath = hostAccessPolicyPath()
): Promise<boolean> {
  const canonical = canonicalRelayUrl(relayUrl)
  if (!canonical) return false

  const store = await readHostAccessPolicies(filePath)
  if (!(canonical in store.hosts)) return false
  delete store.hosts[canonical]
  await writeJsonAtomic(filePath, store)
  return true
}

/** Full access is the only mode that bypasses task-scoped action grants. */
export function requiresTaskGrant(mode: HostAccessMode): boolean {
  return mode !== 'full_access'
}

export function hasFullAccess(mode: HostAccessMode): boolean {
  return mode === 'full_access'
}

/** Preserve the pre-policy consent contract without displaying a false Ask
 * state. Explicit Ask clears toolsConsented, so a remaining true value is an
 * unambiguous legacy Trusted host. */
export function effectiveHostAccessMode(
  storedMode: HostAccessMode,
  legacyToolsConsented: boolean
): HostAccessMode {
  return storedMode === 'ask' && legacyToolsConsented ? 'trusted' : storedMode
}

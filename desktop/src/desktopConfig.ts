import { promises as fs } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'

interface DesktopControlConfigFile extends Record<string, unknown> {
  relay_url?: unknown
  host_aliases?: unknown
}

let configPathOverride: string | null = null

const configPath = () => configPathOverride ?? join(homedir(), '.hermes', 'desktop-control.json')

/** Test-only path override. Pass null to restore the normal user config path. */
export function setDesktopConfigPath(path: string | null): void {
  configPathOverride = path
}

export async function getActiveDesktopRelayUrl(): Promise<string | null> {
  try {
    const raw = await fs.readFile(configPath(), 'utf8')
    const parsed = JSON.parse(raw) as DesktopControlConfigFile
    const relayUrl = typeof parsed.relay_url === 'string' ? parsed.relay_url.trim() : ''
    return relayUrl || null
  } catch {
    return null
  }
}

export async function getDesktopHostAliases(): Promise<Record<string, string>> {
  try {
    const parsed = JSON.parse(await fs.readFile(configPath(), 'utf8')) as DesktopControlConfigFile
    if (!parsed.host_aliases || typeof parsed.host_aliases !== 'object' || Array.isArray(parsed.host_aliases)) return {}
    return Object.fromEntries(Object.entries(parsed.host_aliases as Record<string, unknown>)
      .filter((entry): entry is [string, string] => typeof entry[1] === 'string' && entry[1].trim().length > 0)
      .map(([url, alias]) => [url, alias.trim()]))
  } catch {
    return {}
  }
}

export async function setDesktopHostAlias(url: string, alias: string | null): Promise<void> {
  const path = configPath()
  let config: DesktopControlConfigFile = {}
  try {
    const parsed = JSON.parse(await fs.readFile(path, 'utf8')) as unknown
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) config = parsed as DesktopControlConfigFile
  } catch { /* Missing or malformed config starts clean. */ }

  const aliases = config.host_aliases && typeof config.host_aliases === 'object' && !Array.isArray(config.host_aliases)
    ? { ...(config.host_aliases as Record<string, unknown>) }
    : {}
  const normalized = alias?.trim() ?? ''
  if (normalized) aliases[url] = normalized
  else delete aliases[url]
  if (Object.keys(aliases).length) config.host_aliases = aliases
  else delete config.host_aliases

  await fs.mkdir(dirname(path), { recursive: true, mode: 0o700 })
  const temporary = `${path}.${process.pid}.${Date.now()}.tmp`
  await fs.writeFile(temporary, JSON.stringify(config, null, 2) + '\n', { mode: 0o600 })
  await fs.rename(temporary, path)
}

/** Persist the tray-selected host without discarding unrelated desktop-control
 * settings written by older releases. A null URL clears only the selection. */
export async function setActiveDesktopRelayUrl(url: string | null): Promise<void> {
  const path = configPath()
  let config: DesktopControlConfigFile = {}
  try {
    const parsed = JSON.parse(await fs.readFile(path, 'utf8')) as unknown
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      config = parsed as DesktopControlConfigFile
    }
  } catch {
    // Missing or malformed config starts from a safe empty object.
  }

  const normalized = url?.trim() ?? ''
  if (normalized) {
    config.relay_url = normalized
  } else {
    delete config.relay_url
  }

  await fs.mkdir(dirname(path), { recursive: true, mode: 0o700 })
  const temporary = `${path}.${process.pid}.${Date.now()}.tmp`
  await fs.writeFile(temporary, JSON.stringify(config, null, 2) + '\n', { mode: 0o600 })
  await fs.rename(temporary, path)
}

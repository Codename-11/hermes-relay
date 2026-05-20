import { promises as fs } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

interface DesktopControlConfigFile {
  relay_url?: unknown
}

const configPath = () => join(homedir(), '.hermes', 'desktop-control.json')

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

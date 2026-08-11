import { execFileSync } from 'node:child_process'
import { hostname, machine, release, type } from 'node:os'

function windowsDeviceModel(): string | null {
  if (process.platform !== 'win32') return null
  try {
    const output = execFileSync(
      'reg.exe',
      [
        'query',
        String.raw`HKLM\HARDWARE\DESCRIPTION\System\BIOS`,
        '/v',
        'SystemProductName'
      ],
      {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
        timeout: 1_000,
        windowsHide: true
      }
    )
    return output.match(/SystemProductName\s+REG_SZ\s+(.+)$/im)?.[1]?.trim() || null
  } catch {
    return null
  }
}

/** Consistent identity metadata for every desktop relay connection surface. */
export function desktopRelayIdentity(): {
  deviceName: string
  deviceHostname: string
  deviceModel: string
  devicePlatform: string
  clientSurface: string
  deviceFormFactor: string
} {
  const host = hostname().trim() || 'Hermes-Relay desktop'
  return {
    deviceName: host,
    deviceHostname: host,
    deviceModel: windowsDeviceModel() || machine() || process.arch,
    devicePlatform: `${type()} ${release()}`.trim(),
    clientSurface: 'desktop',
    deviceFormFactor: 'desktop'
  }
}

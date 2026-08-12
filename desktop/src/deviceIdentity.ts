import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import {
  chmodSync,
  mkdirSync,
  readFileSync,
  renameSync,
  unlinkSync,
  writeFileSync
} from 'node:fs'
import { homedir, hostname, machine, release, type } from 'node:os'
import { dirname, join } from 'node:path'

const DEVICE_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

const defaultDeviceIdPath = () => join(homedir(), '.hermes', 'desktop-device-id')

function readDeviceId(path: string): string | null {
  try {
    const value = readFileSync(path, 'utf8').trim()
    return DEVICE_ID_PATTERN.test(value) ? value.toLowerCase() : null
  } catch {
    return null
  }
}

/** Stable private identity for one desktop CLI installation.
 *
 * Hostname and model are display metadata; they are not ownership keys. A
 * random per-install UUID lets several PCs pair to the same relay without one
 * PC's explicit re-pair revoking another PC's session. */
export function desktopDeviceId(path = defaultDeviceIdPath()): string {
  const existing = readDeviceId(path)
  if (existing) return existing

  mkdirSync(dirname(path), { recursive: true, mode: 0o700 })
  const generated = randomUUID().toLowerCase()

  try {
    // Exclusive creation makes simultaneous first launches converge on the
    // same stored identity instead of returning two different UUIDs.
    writeFileSync(path, `${generated}\n`, { encoding: 'utf8', flag: 'wx', mode: 0o600 })
    return generated
  } catch {
    const raced = readDeviceId(path)
    if (raced) return raced

    // Repair a malformed legacy/partial file atomically.
    const temporary = `${path}.tmp-${process.pid}-${Date.now()}`
    try {
      writeFileSync(temporary, `${generated}\n`, { encoding: 'utf8', flag: 'wx', mode: 0o600 })
      renameSync(temporary, path)
      chmodSync(path, 0o600)
      return generated
    } finally {
      try {
        unlinkSync(temporary)
      } catch {
        // rename consumed it, or creation failed before it existed
      }
    }
  }
}

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
export function desktopRelayIdentity(deviceIdPath?: string): {
  deviceName: string
  deviceHostname: string
  deviceModel: string
  devicePlatform: string
  clientSurface: string
  deviceFormFactor: string
  deviceId: string
} {
  const host = hostname().trim() || 'Hermes-Relay desktop'
  return {
    deviceName: host,
    deviceHostname: host,
    deviceModel: windowsDeviceModel() || machine() || process.arch,
    devicePlatform: `${type()} ${release()}`.trim(),
    clientSurface: 'desktop',
    deviceFormFactor: 'desktop',
    deviceId: desktopDeviceId(deviceIdPath)
  }
}

import { readFileSync } from 'node:fs'
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'

import { grantBridgeDir } from './grantBridge.js'

export interface DesktopUseSettings {
  computer_use_enabled: boolean
  computer_control_engine: 'legacy' | 'cua'
  cua_cursor_enabled: boolean
  updated_at?: string
}

export interface ComputerGrantCancellation {
  reason: string
  requested_at: string
}

export function desktopUseSettingsPath(): string {
  return process.env.HERMES_RELAY_DESKTOP_SETTINGS_PATH ??
    join(homedir(), '.hermes', 'desktop-settings.json')
}

export function computerGrantCancellationPath(): string {
  return process.env.HERMES_RELAY_GRANT_CANCEL_PATH ??
    join(grantBridgeDir(), 'cancel-active.json')
}

function normalizeSettings(value: unknown): DesktopUseSettings {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return defaultSettings()
  }
  const raw = value as Partial<DesktopUseSettings>
  return {
    computer_use_enabled: raw.computer_use_enabled === true,
    computer_control_engine: raw.computer_control_engine === 'cua' ? 'cua' : 'legacy',
    cua_cursor_enabled: raw.cua_cursor_enabled === true,
    updated_at: typeof raw.updated_at === 'string' ? raw.updated_at : undefined
  }
}

function defaultSettings(): DesktopUseSettings {
  return {
    computer_use_enabled: false,
    computer_control_engine: 'legacy',
    cua_cursor_enabled: false
  }
}

export function readDesktopUseSettingsSync(
  filePath = desktopUseSettingsPath()
): DesktopUseSettings {
  try {
    return normalizeSettings(JSON.parse(readFileSync(filePath, 'utf8')))
  } catch {
    return defaultSettings()
  }
}

export async function readDesktopUseSettings(
  filePath = desktopUseSettingsPath()
): Promise<DesktopUseSettings> {
  try {
    return normalizeSettings(JSON.parse(await readFile(filePath, 'utf8')))
  } catch {
    return defaultSettings()
  }
}

async function writeJsonAtomic(filePath: string, value: unknown): Promise<void> {
  await mkdir(dirname(filePath), { recursive: true, mode: 0o700 })
  const temporaryPath = `${filePath}.${process.pid}.${Date.now()}.tmp`
  await writeFile(temporaryPath, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 })
  await rename(temporaryPath, filePath)
}

export async function setDesktopUseEnabled(
  enabled: boolean,
  filePath = desktopUseSettingsPath()
): Promise<DesktopUseSettings> {
  const settings: DesktopUseSettings = {
    ...await readDesktopUseSettings(filePath),
    computer_use_enabled: enabled,
    updated_at: new Date().toISOString()
  }
  await writeJsonAtomic(filePath, settings)
  return settings
}

export async function setComputerControlSettings(
  update: Partial<Pick<DesktopUseSettings,
    'computer_control_engine' | 'cua_cursor_enabled'>>,
  filePath = desktopUseSettingsPath()
): Promise<DesktopUseSettings> {
  const settings: DesktopUseSettings = {
    ...await readDesktopUseSettings(filePath),
    ...update,
    updated_at: new Date().toISOString()
  }
  await writeJsonAtomic(filePath, settings)
  return settings
}

export async function requestComputerGrantCancellation(
  reason = 'cancelled from local desktop controls',
  filePath = computerGrantCancellationPath()
): Promise<void> {
  await writeJsonAtomic(filePath, {
    reason,
    requested_at: new Date().toISOString()
  } satisfies ComputerGrantCancellation)
}

export async function consumeComputerGrantCancellation(
  filePath = computerGrantCancellationPath()
): Promise<ComputerGrantCancellation | null> {
  let parsed: unknown
  try {
    parsed = JSON.parse(await readFile(filePath, 'utf8'))
  } catch {
    return null
  }
  await rm(filePath, { force: true })
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null
  const request = parsed as Partial<ComputerGrantCancellation>
  if (typeof request.reason !== 'string' || typeof request.requested_at !== 'string') return null
  return { reason: request.reason, requested_at: request.requested_at }
}

import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { homedir } from 'node:os'
import { basename, dirname, join, win32 } from 'node:path'

export interface WindowsBundleState {
  installed: boolean
  path: string
  version: string | null
  running: boolean
}

interface BundleProbe {
  version: string | null
  running: boolean
}

export function uiExecutablePath(env = process.env, executable = process.execPath): string {
  if (env.HERMES_RELAY_UI_PATH) return env.HERMES_RELAY_UI_PATH
  if (env.HERMES_RELAY_INSTALL_DIR) {
    const pathApi = win32.isAbsolute(env.HERMES_RELAY_INSTALL_DIR) ? win32 : { join }
    return pathApi.join(env.HERMES_RELAY_INSTALL_DIR, 'hermes-relay-tray.exe')
  }
  const executableName = win32.basename(executable).toLowerCase()
  if (executableName === 'hermes-relay.exe') {
    return win32.join(win32.dirname(executable), 'hermes-relay-tray.exe')
  }
  if (basename(executable).toLowerCase() === 'hermes-relay') {
    return join(dirname(executable), 'hermes-relay-tray.exe')
  }
  return join(homedir(), '.hermes', 'bin', 'hermes-relay-tray.exe')
}

function probeWindowsBundle(path: string): BundleProbe | null {
  const script = [
    "$item = Get-Item -LiteralPath $env:HERMES_RELAY_UI_PROBE_PATH -ErrorAction Stop",
    "$running = @(Get-Process -Name 'hermes-relay-tray' -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $item.FullName }).Count -gt 0",
    '[pscustomobject]@{ version = $item.VersionInfo.ProductVersion; running = $running } | ConvertTo-Json -Compress'
  ].join('; ')
  const result = spawnSync('powershell.exe', [
    '-NoProfile',
    '-NonInteractive',
    '-ExecutionPolicy',
    'Bypass',
    '-Command',
    script
  ], {
    encoding: 'utf8',
    timeout: 5_000,
    windowsHide: true,
    env: { ...process.env, HERMES_RELAY_UI_PROBE_PATH: path }
  })
  if (result.status !== 0 || !result.stdout.trim()) return null
  try {
    const value = JSON.parse(result.stdout) as { version?: unknown; running?: unknown }
    return {
      version: typeof value.version === 'string' && value.version.trim()
        ? value.version.trim()
        : null,
      running: value.running === true
    }
  } catch {
    return null
  }
}

export function detectWindowsBundle(options: {
  platform?: NodeJS.Platform
  env?: NodeJS.ProcessEnv
  executable?: string
  exists?: (path: string) => boolean
  probe?: (path: string) => BundleProbe | null
} = {}): WindowsBundleState {
  const path = uiExecutablePath(options.env, options.executable)
  const installed = (options.platform ?? process.platform) === 'win32' &&
    (options.exists ?? existsSync)(path)
  if (!installed) return { installed: false, path, version: null, running: false }

  const probe = (options.probe ?? probeWindowsBundle)(path)
  return {
    installed: true,
    path,
    version: probe?.version ?? null,
    // If the process probe fails, restarting is safer than leaving a UI that
    // was running before setup permanently stopped.
    running: probe?.running ?? true
  }
}

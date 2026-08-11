import { spawn, type SpawnOptions } from 'node:child_process'

export interface WindowsInstallerLaunchPlan {
  program: string
  args: string[]
  options: SpawnOptions
}

export interface WindowsInstallerLaunchOptions {
  silent: boolean
  restartDaemon?: boolean
  installDir?: string
  cliPath?: string
  trayPath?: string
  delayMs?: number
}

/**
 * Schedule the NSIS bundle after the current CLI process has released its
 * executable. Windows will not replace a running hermes-relay.exe, so starting
 * the installer directly from `hermes-relay ui install` is inherently racy.
 */
export function windowsInstallerLaunchPlan(
  installerPath: string,
  options: WindowsInstallerLaunchOptions
): WindowsInstallerLaunchPlan {
  const {
    silent,
    restartDaemon = false,
    installDir = '',
    cliPath = '',
    trayPath = '',
    delayMs = 1200
  } = options
  const script = [
    `Start-Sleep -Milliseconds ${Math.max(0, Math.floor(delayMs))}`,
    '$installer = $env:HERMES_RELAY_SETUP_PATH',
    "$installerArgs = if ($env:HERMES_RELAY_SETUP_SILENT -eq '1') { @('/S') } else { @() }",
    "if ($env:HERMES_RELAY_SETUP_INSTALL_DIR) { $installerArgs += ('/D=' + $env:HERMES_RELAY_SETUP_INSTALL_DIR) }",
    '$process = Start-Process -FilePath $installer -ArgumentList $installerArgs -PassThru',
    '$process.WaitForExit()',
    "if ($process.ExitCode -eq 0 -and $env:HERMES_RELAY_SETUP_RESTART_DAEMON -eq '1') { Start-Process -FilePath $env:HERMES_RELAY_SETUP_CLI -ArgumentList @('daemon','start') -WindowStyle Hidden | Out-Null }",
    "if ($process.ExitCode -eq 0 -and $env:HERMES_RELAY_SETUP_TRAY) { Start-Process -FilePath $env:HERMES_RELAY_SETUP_TRAY -ArgumentList '--show' | Out-Null }",
    'Remove-Item -LiteralPath $installer -Force -ErrorAction SilentlyContinue',
    'exit $process.ExitCode'
  ].join('; ')

  return {
    program: 'powershell.exe',
    args: [
      '-NoProfile',
      '-NonInteractive',
      '-ExecutionPolicy',
      'Bypass',
      '-WindowStyle',
      'Hidden',
      '-Command',
      script
    ],
    options: {
      detached: true,
      stdio: 'ignore',
      windowsHide: true,
      env: {
        ...process.env,
        HERMES_RELAY_SETUP_PATH: installerPath,
        HERMES_RELAY_SETUP_SILENT: silent ? '1' : '0',
        HERMES_RELAY_SETUP_RESTART_DAEMON: restartDaemon ? '1' : '0',
        HERMES_RELAY_SETUP_INSTALL_DIR: installDir,
        HERMES_RELAY_SETUP_CLI: cliPath,
        HERMES_RELAY_SETUP_TRAY: trayPath
      }
    }
  }
}

export function scheduleWindowsInstaller(installerPath: string, options: WindowsInstallerLaunchOptions): void {
  const plan = windowsInstallerLaunchPlan(installerPath, options)
  const child = spawn(plan.program, plan.args, plan.options)
  child.unref()
}

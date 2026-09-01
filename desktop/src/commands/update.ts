// update — self-update subcommand. Thin UI shell over updater.ts.
//
// Contract:
//   hermes-relay update            check + confirm prompt + apply
//   hermes-relay update --check    check only, print status, exit 0
//   hermes-relay update --yes      skip confirm prompt
//   hermes-relay update --json     machine-readable output
//   hermes-relay update --installer  target the Windows CLI UI installer
//
// Behaviour notes:
//   - Never silently self-replaces. --yes is the only path that skips confirm.
//   - Non-TTY without --yes or --check fails closed (prints guidance, exit 2).
//   - POSIX: atomic replace; running daemon keeps serving until next restart.
//   - Windows: cooperative swap — binary staged as .new.exe; finalize happens
//     on next `hermes-relay` invocation (see updater.ts → finalizePendingUpdate).

import { createInterface } from 'node:readline'
import { homedir, tmpdir } from 'node:os'
import { dirname, join } from 'node:path'

import type { ParsedArgs } from '../cli.js'
import {
  assetNameForPlatform,
  checkForUpdate,
  compareVersions,
  downloadAndInstall,
  type DownloadOptions,
  type UpdateInfo
} from '../updater.js'
import { VERSION } from '../version.js'
import { scheduleWindowsInstaller } from '../windowsInstaller.js'
import { detectWindowsBundle, type WindowsBundleState } from '../windowsBundle.js'
import { isDaemonProcessAlive, readDaemonStatus } from '../lib/daemonStatus.js'

export interface UpdateCommandOptions {
  platform?: NodeJS.Platform
  bundle?: WindowsBundleState
}

export function installerRunsSilently(
  bundleInstalled: boolean,
  autoYes: boolean,
  wantJson: boolean
): boolean {
  return bundleInstalled || autoYes || wantJson
}

function humanBytes(n: number | null): string {
  if (n === null) return '?'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

function humanDate(iso: string): string {
  // yyyy-mm-dd slice — matches the sample output in the plan.
  try {
    return new Date(iso).toISOString().slice(0, 10)
  } catch {
    return iso
  }
}

async function promptYesNo(question: string, defaultYes = true): Promise<boolean> {
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    return false
  }
  const rl = createInterface({ input: process.stdin, output: process.stdout })
  try {
    const suffix = defaultYes ? '[Y/n]' : '[y/N]'
    const answer: string = await new Promise((resolve) => {
      rl.question(`${question} ${suffix} `, resolve)
    })
    const trimmed = answer.trim().toLowerCase()
    if (!trimmed) return defaultYes
    return trimmed === 'y' || trimmed === 'yes'
  } finally {
    rl.close()
  }
}

function renderProgressBar(bytes: number, total: number, width = 28): string {
  if (total <= 0) {
    // Unknown total — fall back to a spinner-like indefinite bar.
    const mb = (bytes / (1024 * 1024)).toFixed(1)
    return `downloading... ${mb} MB`
  }
  const pct = Math.min(1, bytes / total)
  const filled = Math.round(pct * width)
  const bar = '█'.repeat(filled) + ' '.repeat(width - filled)
  return `downloading... ${bar} ${Math.round(pct * 100)}%`
}

interface JsonReport {
  current: string
  target: 'cli' | 'cli_ui'
  ui_installed: boolean
  ui_version: string | null
  versions_in_sync: boolean
  up_to_date: boolean
  ahead_of_latest: boolean
  latest_tag: string | null
  latest_version: string | null
  is_prerelease: boolean
  published_at: string | null
  asset_name: string | null
  asset_size: number | null
  installed: boolean
  downloaded: boolean
  installer_scheduled: boolean
  installed_path: string | null
  needs_restart: boolean
  error: string | null
}

function emptyReport(target: 'cli' | 'cli_ui', bundle: WindowsBundleState): JsonReport {
  return {
    current: VERSION,
    target,
    ui_installed: bundle.installed,
    ui_version: bundle.version,
    versions_in_sync: !bundle.installed || bundle.version === VERSION,
    up_to_date: true,
    ahead_of_latest: false,
    latest_tag: null,
    latest_version: null,
    is_prerelease: false,
    published_at: null,
    asset_name: null,
    asset_size: null,
    installed: false,
    downloaded: false,
    installer_scheduled: false,
    installed_path: null,
    needs_restart: false,
    error: null
  }
}

function reportFromInfo(
  info: UpdateInfo,
  target: 'cli' | 'cli_ui',
  bundle: WindowsBundleState
): JsonReport {
  const versionsInSync = !bundle.installed || bundle.version === VERSION
  const bundleNeedsRepair = target === 'cli_ui' && bundle.installed && !versionsInSync
  const uiAhead = bundle.version !== null && compareVersions(bundle.version, info.latest_version) > 0
  return {
    current: info.current,
    target,
    ui_installed: bundle.installed,
    ui_version: bundle.version,
    versions_in_sync: versionsInSync,
    up_to_date: !info.is_upgrade && !bundleNeedsRepair,
    ahead_of_latest: compareVersions(info.current, info.latest_version) > 0 || uiAhead,
    latest_tag: info.latest_tag,
    latest_version: info.latest_version,
    is_prerelease: info.is_prerelease,
    published_at: info.published_at,
    asset_name: info.asset_name,
    asset_size: info.asset_size,
    installed: false,
    downloaded: false,
    installer_scheduled: false,
    installed_path: null,
    needs_restart: false,
    error: null
  }
}

export async function updateCommand(
  args: ParsedArgs,
  options: UpdateCommandOptions = {}
): Promise<number> {
  const wantJson = !!args.flags.json
  const checkOnly = !!args.flags.check
  const autoYes = !!args.flags.yes
  const installerRequested = !!args.flags.installer
  const downloadOnly = !!args.flags['download-only']
  const force = !!args.flags.force

  const repo = typeof args.flags.repo === 'string' ? args.flags.repo : undefined
  const platform = options.platform ?? process.platform
  const bundle = options.bundle ?? detectWindowsBundle({ platform })
  const installer = installerRequested || (platform === 'win32' && bundle.installed)
  const target = installer ? 'cli_ui' : 'cli'

  let info: UpdateInfo | null = null
  try {
    const checkOpts = {
      ...(repo !== undefined ? { repo } : {}),
      ...(installer ? { assetName: 'hermes-relay-windows-x64-setup.exe' } : {})
    }
    info = await checkForUpdate(checkOpts)
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    if (wantJson) {
      const report = emptyReport(target, bundle)
      report.error = msg
      process.stdout.write(JSON.stringify(report, null, 2) + '\n')
      return 1
    }
    process.stderr.write(`update: could not check for updates: ${msg}\n`)
    return 1
  }

  if (!info) {
    // No CLI release-track rows at all — surface truthfully, don't crash.
    if (wantJson) {
      process.stdout.write(JSON.stringify(emptyReport(target, bundle), null, 2) + '\n')
      return 0
    }
    process.stdout.write(`Current version: ${VERSION}\n`)
    process.stdout.write(`No desktop-v* or historical cli-v* releases found on the upstream repo.\n`)
    return 0
  }

  const report = reportFromInfo(info, target, bundle)

  if (wantJson && checkOnly) {
    process.stdout.write(JSON.stringify(report, null, 2) + '\n')
    return 0
  }

  if (installer && report.ahead_of_latest && !force) {
    const installedVersions = bundle.version === null
      ? `CLI ${VERSION}`
      : `CLI ${VERSION}, UI ${bundle.version}`
    const msg = `latest CLI+UI bundle (${info.latest_version}) is older than the installed ${installedVersions}; pass --force to downgrade explicitly`
    if (wantJson) {
      report.error = msg
      process.stdout.write(JSON.stringify(report, null, 2) + '\n')
    } else {
      process.stderr.write(`update: ${msg}\n`)
    }
    return 2
  }

  if (!wantJson) {
    if (installer && bundle.installed) {
      process.stdout.write(`Installed versions: CLI ${info.current}, UI ${bundle.version ?? 'unknown'}\n`)
      if (!report.versions_in_sync) {
        process.stdout.write('CLI/UI drift detected; the verified Windows bundle will repair both surfaces together.\n')
      }
    } else {
      process.stdout.write(`Current version: ${info.current}\n`)
    }
    process.stdout.write(`Checking GitHub Releases...\n`)
  }

  // The full Windows installer is also how a CLI-only installation adds the
  // optional UI. Do not suppress that operation merely because the bundled
  // CLI version is already current.
  if (report.up_to_date && !installerRequested) {
    if (wantJson) {
      process.stdout.write(JSON.stringify(report, null, 2) + '\n')
      return 0
    }
    process.stdout.write(
      target === 'cli_ui'
        ? `Up to date — the installed CLI and UI match the latest release.\n`
        : `Up to date — you're on the latest CLI release.\n`
    )
    return 0
  }

  // Upgrade available.
  if (!wantJson) {
    const pre = info.is_prerelease ? ' (prerelease)' : ''
    if (info.is_upgrade) {
      process.stdout.write(
        `Upgrade available: ${info.current} → ${info.latest_version}${pre}\n`
      )
    } else if (!report.versions_in_sync) {
      process.stdout.write(`Bundle repair available: align CLI and UI on ${info.latest_version}${pre}\n`)
    } else if (installerRequested && !bundle.installed) {
      process.stdout.write(`CLI+UI bundle ready: ${info.latest_version}${pre}\n`)
    }
    process.stdout.write(`  published: ${humanDate(info.published_at)}\n`)
    process.stdout.write(
      `  asset:     ${info.asset_name} (${humanBytes(info.asset_size)})\n`
    )
    process.stdout.write('\n')
  }

  if (checkOnly) {
    if (wantJson) {
      process.stdout.write(JSON.stringify(report, null, 2) + '\n')
    }
    return 0
  }

  // Refuse to proceed if this platform has no matching asset.
  const wantAsset = installer ? 'hermes-relay-windows-x64-setup.exe' : assetNameForPlatform()
  if (!wantAsset || !info.asset_url) {
    const msg = `no binary available for ${process.platform}/${process.arch}`
    if (wantJson) {
      report.error = msg
      process.stdout.write(JSON.stringify(report, null, 2) + '\n')
      return 1
    }
    process.stderr.write(`update: ${msg}\n`)
    return 1
  }

  // Confirm.
  if (!autoYes) {
    if (!process.stdin.isTTY && !wantJson) {
      process.stderr.write(
        'update: stdin is not a TTY; pass --yes to proceed non-interactively.\n'
      )
      return 2
    }
    if (!wantJson) {
      const ok = await promptYesNo('Install now?', true)
      if (!ok) {
        process.stdout.write('cancelled.\n')
        return 0
      }
    } else if (!autoYes) {
      // --json without --yes: machine caller should pass --yes explicitly.
      report.error = 'refusing to install without --yes in --json mode'
      process.stdout.write(JSON.stringify(report, null, 2) + '\n')
      return 2
    }
  }

  // Download + install, with progress on the same line when on a TTY.
  const useTtyProgress = !wantJson && process.stdout.isTTY
  let lastLen = 0
  const onProgress = useTtyProgress
    ? (bytes: number, total: number) => {
        const line = renderProgressBar(bytes, total)
        process.stdout.write('\r-> ' + line.padEnd(Math.max(lastLen, line.length), ' '))
        lastLen = line.length
      }
    : undefined

  try {
    const downloadOpts: DownloadOptions = onProgress ? { onProgress } : {}
    if (installer) {
      downloadOpts.targetPath = join(
        tmpdir(),
        `hermes-relay-${info.latest_version}-${process.pid}-${Date.now()}-setup.exe`
      )
      downloadOpts.cooperative = false
    }
    const result = await downloadAndInstall(info, downloadOpts)
    if (installer && !downloadOnly) {
      if (platform !== 'win32') {
        throw new Error('the Hermes-Relay CLI UI installer is Windows-only')
      }
      const daemon = await readDaemonStatus()
      const installDir = bundle.installed
        ? dirname(bundle.path)
        : process.execPath.toLowerCase().endsWith('hermes-relay.exe')
          ? dirname(process.execPath)
          : join(homedir(), '.hermes', 'bin')
      scheduleWindowsInstaller(result.installedPath, {
        // An existing bundle update already received CLI confirmation. Run
        // setup silently so its finish-page checkbox cannot start a tray that
        // was stopped before the update; the helper restores exact state.
        silent: installerRunsSilently(bundle.installed, autoYes, wantJson),
        restartDaemon: daemon ? isDaemonProcessAlive(daemon) : false,
        installDir,
        cliPath: join(installDir, 'hermes-relay.exe'),
        trayPath: join(installDir, 'hermes-relay-tray.exe'),
        restartTray: bundle.installed ? bundle.running : true
      })
    }
    if (useTtyProgress) process.stdout.write('\n')

    if (!wantJson) {
      process.stdout.write('-> verifying SHA256... ok\n')
      process.stdout.write(
        installer
          ? downloadOnly
            ? `-> verified installer at ${result.installedPath}\n`
            : `-> verified installer; setup will open after this CLI exits\n`
          : `-> installed at ${result.installedPath}\n`
      )
      if (installer && !downloadOnly) {
        process.stdout.write(
          bundle.installed
            ? '   The installer updates the CLI and UI together and restores their prior running state.\n'
            : '   The installer adds the CLI and optional UI and lets you enable the tray at sign-in.\n'
        )
      } else if (result.needsRestart) {
        process.stdout.write(
          `   On next \`hermes-relay\` invocation, the CLI will swap this in automatically.\n`
        )
        process.stdout.write(
          `   (Windows can't replace a running .exe; this is a cooperative swap.)\n`
        )
      } else {
        process.stdout.write(
          `   Daemon (if any) will pick up the new code on restart.\n`
        )
      }
      return 0
    }

    report.installed = !installer
    report.downloaded = installer
    report.installer_scheduled = installer && !downloadOnly
    report.installed_path = result.installedPath
    report.needs_restart = installer ? !downloadOnly : result.needsRestart
    process.stdout.write(JSON.stringify(report, null, 2) + '\n')
    return 0
  } catch (err) {
    if (useTtyProgress) process.stdout.write('\n')
    const msg = err instanceof Error ? err.message : String(err)
    if (wantJson) {
      report.error = msg
      process.stdout.write(JSON.stringify(report, null, 2) + '\n')
      return 1
    }
    process.stderr.write(`update: ${msg}\n`)
    return 1
  }
}

export default updateCommand

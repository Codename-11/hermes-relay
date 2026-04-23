// update — self-update subcommand. Thin UI shell over updater.ts.
//
// Contract:
//   hermes-relay update            check + confirm prompt + apply
//   hermes-relay update --check    check only, print status, exit 0
//   hermes-relay update --yes      skip confirm prompt
//   hermes-relay update --json     machine-readable output
//
// Behaviour notes:
//   - Never silently self-replaces. --yes is the only path that skips confirm.
//   - Non-TTY without --yes or --check fails closed (prints guidance, exit 2).
//   - POSIX: atomic replace; running daemon keeps serving until next restart.
//   - Windows: cooperative swap — binary staged as .new.exe; finalize happens
//     on next `hermes-relay` invocation (see updater.ts → finalizePendingUpdate).

import { createInterface } from 'node:readline'

import type { ParsedArgs } from '../cli.js'
import {
  assetNameForPlatform,
  checkForUpdate,
  downloadAndInstall,
  type UpdateInfo
} from '../updater.js'
import { VERSION } from '../version.js'

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
  up_to_date: boolean
  latest_tag: string | null
  latest_version: string | null
  is_prerelease: boolean
  published_at: string | null
  asset_name: string | null
  asset_size: number | null
  installed: boolean
  installed_path: string | null
  needs_restart: boolean
  error: string | null
}

function emptyReport(): JsonReport {
  return {
    current: VERSION,
    up_to_date: true,
    latest_tag: null,
    latest_version: null,
    is_prerelease: false,
    published_at: null,
    asset_name: null,
    asset_size: null,
    installed: false,
    installed_path: null,
    needs_restart: false,
    error: null
  }
}

function reportFromInfo(info: UpdateInfo): JsonReport {
  return {
    current: info.current,
    up_to_date: !info.is_upgrade,
    latest_tag: info.latest_tag,
    latest_version: info.latest_version,
    is_prerelease: info.is_prerelease,
    published_at: info.published_at,
    asset_name: info.asset_name,
    asset_size: info.asset_size,
    installed: false,
    installed_path: null,
    needs_restart: false,
    error: null
  }
}

export async function updateCommand(args: ParsedArgs): Promise<number> {
  const wantJson = !!args.flags.json
  const checkOnly = !!args.flags.check
  const autoYes = !!args.flags.yes

  const repo = typeof args.flags.repo === 'string' ? args.flags.repo : undefined

  let info: UpdateInfo | null = null
  try {
    info = await checkForUpdate(repo !== undefined ? { repo } : {})
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    if (wantJson) {
      const report = emptyReport()
      report.error = msg
      process.stdout.write(JSON.stringify(report, null, 2) + '\n')
      return 1
    }
    process.stderr.write(`update: could not check for updates: ${msg}\n`)
    return 1
  }

  if (!info) {
    // No desktop-v* releases at all — surface truthfully, don't crash.
    if (wantJson) {
      process.stdout.write(JSON.stringify(emptyReport(), null, 2) + '\n')
      return 0
    }
    process.stdout.write(`Current version: ${VERSION}\n`)
    process.stdout.write(`No desktop-v* releases found on the upstream repo.\n`)
    return 0
  }

  const report = reportFromInfo(info)

  if (wantJson && checkOnly) {
    process.stdout.write(JSON.stringify(report, null, 2) + '\n')
    return 0
  }

  if (!wantJson) {
    process.stdout.write(`Current version: ${info.current}\n`)
    process.stdout.write(`Checking GitHub Releases...\n`)
  }

  if (!info.is_upgrade) {
    if (wantJson) {
      process.stdout.write(JSON.stringify(report, null, 2) + '\n')
      return 0
    }
    process.stdout.write(`Up to date — you're on the latest desktop-v* release.\n`)
    return 0
  }

  // Upgrade available.
  if (!wantJson) {
    const pre = info.is_prerelease ? ' (prerelease)' : ''
    process.stdout.write(
      `Upgrade available: ${info.current} → ${info.latest_version}${pre}\n`
    )
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
  const wantAsset = assetNameForPlatform()
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
    const downloadOpts = onProgress ? { onProgress } : {}
    const result = await downloadAndInstall(info, downloadOpts)
    if (useTtyProgress) process.stdout.write('\n')

    if (!wantJson) {
      process.stdout.write('-> verifying SHA256... ok\n')
      process.stdout.write(`-> installed at ${result.installedPath}\n`)
      if (result.needsRestart) {
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

    report.installed = true
    report.installed_path = result.installedPath
    report.needs_restart = result.needsRestart
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

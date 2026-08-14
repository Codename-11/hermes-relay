import { createInterface } from 'node:readline/promises'

import type { ParsedArgs } from '../cli.js'
import { readDaemonStatus, isDaemonProcessAlive } from '../lib/daemonStatus.js'
import {
  readDesktopUseSettings,
  requestComputerGrantCancellation,
  setComputerControlSettings,
  setDesktopUseEnabled
} from '../lib/desktopUseSettings.js'
import { listPendingGrantRequests } from '../lib/grantBridge.js'
import { theme as makeTheme } from '../lib/theme.js'
import { printUsage, type UsageSpec, unknownSubcommand } from '../lib/usage.js'
import { CuaDriverAdapter, type CuaRuntimeStatus } from '../tools/cuaDriver.js'
import {
  checkCuaUpdate,
  getCuaManagementStatus,
  installCuaDriver,
  updateCuaDriver
} from '../tools/cuaManagement.js'

const COMPUTER_USE_USAGE: UsageSpec = {
  name: 'computer-use',
  summary: 'manage persistent experimental desktop screenshot and input capability',
  usage: [
    'computer-use status [--json]',
    'computer-use enable [--yes]',
    'computer-use disable',
    'computer-use cancel',
    'computer-use engine <legacy|cua>',
    'computer-use cursor <on|off>',
    'computer-use cua <status|install|check-update|update> [--json] [--yes]'
  ],
  subcommands: [
    { verb: 'status', desc: 'Show preference, daemon state, active grant, and pending requests' },
    { verb: 'enable', desc: 'Persist desktop-use enablement after explicit confirmation' },
    { verb: 'disable', desc: 'Disable desktop use and request cancellation of any active grant' },
    { verb: 'cancel', desc: 'Cancel the active task-scoped desktop grant' },
    { verb: 'engine', desc: 'Choose legacy Windows input or a ready CUA Driver backend' },
    { verb: 'cursor', desc: 'Show or hide the CUA virtual agent cursor' },
    { verb: 'cua', desc: 'Manage the canonical CUA Driver package explicitly' }
  ],
  flags: [
    { flag: '--json', desc: 'Emit machine-readable status' },
    { flag: '--yes', desc: 'Confirm enablement, CUA installation, or CUA update explicitly' }
  ],
  examples: [
    'hermes-relay computer-use status',
    'hermes-relay computer-use enable',
    'hermes-relay computer-use cua status',
    'hermes-relay computer-use cua check-update',
    'hermes-relay computer-use cua install --yes',
    'hermes-relay computer-use cancel',
    'hermes-relay computer-use disable'
  ]
}

async function confirmEnable(): Promise<boolean> {
  if (!process.stdin.isTTY || !process.stderr.isTTY) return false
  process.stderr.write(
    'Desktop use allows the remote Hermes agent to request screenshots and task-scoped mouse/keyboard control.\n' +
    'Observe grants allow screenshots; assist/control grants still require local approval and expire.\n'
  )
  const rl = createInterface({ input: process.stdin, output: process.stderr })
  try {
    return /^y(?:es)?$/i.test((await rl.question('Enable experimental desktop use? [y/N]: ')).trim())
  } finally {
    rl.close()
  }
}

async function statusPayload(): Promise<Record<string, unknown>> {
  const [settings, daemon, pending] = await Promise.all([
    readDesktopUseSettings(),
    readDaemonStatus(),
    listPendingGrantRequests()
  ])
  const daemonAlive = !!daemon && isDaemonProcessAlive(daemon)
  const activeGrant = daemonAlive && daemon?.computer_grant?.active === true
    ? daemon.computer_grant
    : null
  let cua: CuaRuntimeStatus | null = null
  try {
    cua = await CuaDriverAdapter.status()
  } catch {
    // The optional backend must not make ordinary desktop-use status fail.
  }
  const cuaReason = cua?.reason?.toLowerCase() ?? ''
  const cuaState = !cua?.available
    ? 'not_installed'
    : cua.ready
      ? 'ready'
      : /(?:incompatible|unsupported|version|manifest|permission mode|missing required tools)/.test(cuaReason)
        ? 'incompatible'
        : /(?:degraded|health)/.test(cuaReason)
          ? 'degraded'
          : 'error'
  const cuaReady = cuaState === 'ready'
  const lifecycle = daemonAlive ? daemon?.computer_control : undefined
  return {
    enabled: settings.computer_use_enabled,
    daemon_alive: daemonAlive,
    daemon_privilege: daemonAlive ? (daemon?.privilege ?? null) : null,
    daemon_computer_use_enabled: daemonAlive ? (daemon?.computer_use_enabled ?? false) : false,
    active_grant: activeGrant,
    pending_grants: pending.length,
    restart_required: daemonAlive && daemon?.computer_use_enabled !== settings.computer_use_enabled,
    computer_control_engine: {
      selected: settings.computer_control_engine,
      effective: settings.computer_control_engine === 'cua'
        ? lifecycle?.active_backend === 'cua'
          ? 'cua'
          : lifecycle?.active_backend === 'legacy_compat'
            ? 'legacy'
            : cuaReady ? 'cua' : 'legacy'
        : 'legacy',
      available: cua?.available === true,
      state: cuaState,
      version: cua?.binaryVersion ?? null,
      health: cua?.health ?? null,
      path: cua?.binaryPath ?? null,
      cursor_enabled: settings.cua_cursor_enabled,
      active_sessions: lifecycle?.active_sessions ?? 0,
      active_backend: lifecycle?.active_backend ?? 'idle',
      last_action: lifecycle?.last_action ?? null,
      foreground_escalation_enabled: false,
      message: settings.computer_control_engine === 'cua' && !cuaReady
        ? `CUA is unavailable before control starts; new sessions use explicit compatibility mode. ${cua?.reason ?? ''}`.trim()
        : cua?.reason ?? null
    }
  }
}

export async function computerUseCommand(args: ParsedArgs): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  if (args.flags.help) {
    printUsage(COMPUTER_USE_USAGE, t)
    return 0
  }

  const subcommand = args.positional[0] ?? 'status'
  if (subcommand === 'cua') {
    const action = args.positional[1] ?? 'status'
    const json = args.flags.json === true
    try {
      const payload = action === 'status'
        ? await getCuaManagementStatus()
        : action === 'check-update'
          ? await checkCuaUpdate()
          : action === 'install'
            ? args.flags.yes === true
              ? await installCuaDriver()
              : null
            : action === 'update'
              ? args.flags.yes === true
                ? await updateCuaDriver()
                : null
              : undefined
      if (payload === undefined) {
        process.stderr.write(t.err('cua action must be status, install, check-update, or update') + '\n')
        return 1
      }
      if (payload === null) {
        process.stderr.write(t.err(`CUA ${action} requires explicit confirmation with --yes`) + '\n')
        return 1
      }
      if (json) {
        process.stdout.write(JSON.stringify(payload, null, 2) + '\n')
        return 0
      }
      const version = payload.current_version ?? 'not installed'
      process.stdout.write(t.bold('CUA Driver') + `\n  version: ${version}\n`)
      process.stdout.write(`  package: ${payload.canonical_path ?? 'not installed'}\n`)
      process.stdout.write(`  compatibility: ${payload.compatible ? 'supported' : payload.compatibility_reason ?? 'not ready'}\n`)
      if (payload.stale_path_shim) {
        process.stdout.write(t.warnLine(`  PATH resolves a competing copy: ${payload.discovered_path}`) + '\n')
        process.stdout.write(t.muted('  Hermes uses the canonical package/current install instead.') + '\n')
      }
      if (payload.update) {
        if (payload.update.error) process.stdout.write(t.warnLine(`  update check: ${payload.update.error}`) + '\n')
        else if (payload.update.update_available) {
          process.stdout.write(`  update: ${payload.update.latest_version}${payload.update.compatible ? ' available' : ' available but unsupported'}\n`)
        } else process.stdout.write('  update: up to date\n')
      }
      if (payload.operation) process.stdout.write(t.okLine(`CUA ${payload.operation.kind} completed`) + '\n')
      return 0
    } catch (error) {
      process.stderr.write(t.err(error instanceof Error ? error.message : String(error)) + '\n')
      return 1
    }
  }
  if (subcommand === 'status') {
    const payload = await statusPayload()
    if (args.flags.json) {
      process.stdout.write(JSON.stringify(payload, null, 2) + '\n')
      return 0
    }
    process.stdout.write(t.bold('Hermes Relay desktop use') + '\n')
    process.stdout.write(`  preference: ${payload.enabled ? 'enabled' : 'disabled'}\n`)
    process.stdout.write(`  daemon:     ${payload.daemon_alive ? 'running' : 'stopped'}\n`)
    process.stdout.write(`  active:     ${payload.active_grant ? JSON.stringify(payload.active_grant) : 'none'}\n`)
    process.stdout.write(`  pending:    ${payload.pending_grants}\n`)
    if (payload.restart_required) {
      process.stdout.write(t.warnLine('  restart required for the daemon to apply this preference') + '\n')
    }
    return 0
  }

  if (subcommand === 'enable') {
    const confirmed = args.flags.yes === true || await confirmEnable()
    if (!confirmed) {
      process.stderr.write(
        t.err('desktop use was not enabled; confirm interactively or pass --yes explicitly') + '\n'
      )
      return 1
    }
    await setDesktopUseEnabled(true)
    process.stdout.write(t.okLine('desktop use enabled; restart the daemon to apply') + '\n')
    return 0
  }

  if (subcommand === 'disable') {
    await setDesktopUseEnabled(false)
    await requestComputerGrantCancellation('desktop use disabled locally')
    process.stdout.write(t.okLine('desktop use disabled; active grant cancellation requested') + '\n')
    return 0
  }

  if (subcommand === 'cancel') {
    const daemon = await readDaemonStatus()
    if (!daemon || !isDaemonProcessAlive(daemon) || daemon.computer_grant?.active !== true) {
      process.stdout.write(t.muted('No active desktop-use grant is reported.') + '\n')
      return 0
    }
    await requestComputerGrantCancellation('cancelled from local desktop controls')
    process.stdout.write(t.okLine('active desktop-use grant cancellation requested') + '\n')
    return 0
  }

  if (subcommand === 'engine') {
    const engine = args.positional[1]
    if (engine !== 'legacy' && engine !== 'cua') {
      process.stderr.write(t.err('engine must be legacy or cua') + '\n')
      return 1
    }
    if (engine === 'cua') {
      const payload = await statusPayload()
      const status = payload.computer_control_engine as { state?: string }
      if (status.state !== 'ready') {
        process.stderr.write(t.err('CUA Driver is not ready; engine selection was not changed') + '\n')
        return 1
      }
    }
    await setComputerControlSettings({ computer_control_engine: engine })
    process.stdout.write(t.okLine(`computer control engine set to ${engine}`) + '\n')
    return 0
  }

  if (subcommand === 'cursor') {
    const value = args.positional[1]
    if (value !== 'on' && value !== 'off') {
      process.stderr.write(t.err(`${subcommand} must be on or off`) + '\n')
      return 1
    }
    const payload = await statusPayload()
    const status = payload.computer_control_engine as { selected?: string; state?: string }
    if (status.selected !== 'cua' || status.state !== 'ready') {
      process.stderr.write(t.err(`CUA Driver must be selected and ready before changing ${subcommand}`) + '\n')
      return 1
    }
    await setComputerControlSettings({ cua_cursor_enabled: value === 'on' })
    process.stdout.write(t.okLine(`CUA ${subcommand} ${value}`) + '\n')
    return 0
  }

  return unknownSubcommand(COMPUTER_USE_USAGE, subcommand, t)
}

export default computerUseCommand

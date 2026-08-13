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

const COMPUTER_USE_USAGE: UsageSpec = {
  name: 'computer-use',
  summary: 'manage persistent experimental desktop screenshot and input capability',
  usage: [
    'computer-use status [--json]',
    'computer-use enable [--yes]',
    'computer-use disable',
    'computer-use cancel',
    'computer-use engine <legacy|cua>',
    'computer-use cursor <on|off>'
  ],
  subcommands: [
    { verb: 'status', desc: 'Show preference, daemon state, active grant, and pending requests' },
    { verb: 'enable', desc: 'Persist desktop-use enablement after explicit confirmation' },
    { verb: 'disable', desc: 'Disable desktop use and request cancellation of any active grant' },
    { verb: 'cancel', desc: 'Cancel the active task-scoped desktop grant' },
    { verb: 'engine', desc: 'Choose legacy Windows input or a ready CUA Driver backend' },
    { verb: 'cursor', desc: 'Show or hide the CUA virtual agent cursor' }
  ],
  flags: [
    { flag: '--json', desc: 'Emit machine-readable status' },
    { flag: '--yes', desc: 'Confirm enablement non-interactively' }
  ],
  examples: [
    'hermes-relay computer-use status',
    'hermes-relay computer-use enable',
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
      effective: settings.computer_control_engine === 'cua' && cuaReady ? 'cua' : 'legacy',
      available: cua?.available === true,
      state: cuaState,
      version: cua?.binaryVersion ?? null,
      health: cua?.health ?? null,
      path: cua?.binaryPath ?? null,
      cursor_enabled: settings.cua_cursor_enabled,
      foreground_escalation_enabled: false,
      message: cua?.reason ?? null
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

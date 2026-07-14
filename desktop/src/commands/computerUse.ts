import { createInterface } from 'node:readline/promises'

import type { ParsedArgs } from '../cli.js'
import { readDaemonStatus, isPidAlive } from '../lib/daemonStatus.js'
import {
  readDesktopUseSettings,
  requestComputerGrantCancellation,
  setDesktopUseEnabled
} from '../lib/desktopUseSettings.js'
import { listPendingGrantRequests } from '../lib/grantBridge.js'
import { theme as makeTheme } from '../lib/theme.js'
import { printUsage, type UsageSpec, unknownSubcommand } from '../lib/usage.js'

const COMPUTER_USE_USAGE: UsageSpec = {
  name: 'computer-use',
  summary: 'manage persistent experimental desktop screenshot and input capability',
  usage: [
    'computer-use status [--json]',
    'computer-use enable [--yes]',
    'computer-use disable',
    'computer-use cancel'
  ],
  subcommands: [
    { verb: 'status', desc: 'Show preference, daemon state, active grant, and pending requests' },
    { verb: 'enable', desc: 'Persist desktop-use enablement after explicit confirmation' },
    { verb: 'disable', desc: 'Disable desktop use and request cancellation of any active grant' },
    { verb: 'cancel', desc: 'Cancel the active task-scoped desktop grant' }
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
  const daemonAlive = !!daemon && isPidAlive(daemon.pid)
  const activeGrant = daemonAlive && daemon?.computer_grant?.active === true
    ? daemon.computer_grant
    : null
  return {
    enabled: settings.computer_use_enabled,
    daemon_alive: daemonAlive,
    daemon_privilege: daemonAlive ? (daemon?.privilege ?? null) : null,
    daemon_computer_use_enabled: daemonAlive ? (daemon?.computer_use_enabled ?? false) : false,
    active_grant: activeGrant,
    pending_grants: pending.length,
    restart_required: daemonAlive && daemon?.computer_use_enabled !== settings.computer_use_enabled
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
    if (!daemon || !isPidAlive(daemon.pid) || daemon.computer_grant?.active !== true) {
      process.stdout.write(t.muted('No active desktop-use grant is reported.') + '\n')
      return 0
    }
    await requestComputerGrantCancellation('cancelled from local desktop controls')
    process.stdout.write(t.okLine('active desktop-use grant cancellation requested') + '\n')
    return 0
  }

  return unknownSubcommand(COMPUTER_USE_USAGE, subcommand, t)
}

export default computerUseCommand

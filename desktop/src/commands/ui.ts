import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'

import type { ParsedArgs } from '../cli.js'
import { uiExecutablePath } from '../windowsBundle.js'
import { updateCommand } from './update.js'

export { uiExecutablePath }

function printHelp(): void {
  process.stdout.write(`Usage: hermes-relay ui [open|status|install]\n\n`)
  process.stdout.write(`  open      Open the optional Windows management UI (default)\n`)
  process.stdout.write(`  status    Report whether the management UI is installed\n`)
  process.stdout.write(`  install   Download, verify, and launch the CLI + UI installer\n`)
  process.stdout.write(`\ninstall follows update safety rules: use --yes for non-interactive setup.\n`)
}

export async function uiCommand(args: ParsedArgs): Promise<number> {
  if (process.platform !== 'win32') {
    process.stderr.write('ui: the optional management UI is currently Windows-only.\n')
    return 2
  }

  const action = args.positional[0]?.toLowerCase() ?? 'open'
  if (args.flags.help) {
    printHelp()
    return 0
  }
  if (action === 'install') {
    return updateCommand({ ...args, flags: { ...args.flags, installer: true } })
  }
  if (action !== 'open' && action !== 'status') {
    process.stderr.write(`ui: unknown action '${action}'. Expected open, status, or install.\n`)
    return 2
  }

  const path = uiExecutablePath()
  const installed = existsSync(path)
  if (action === 'status' || args.flags.json) {
    if (args.flags.json) {
      process.stdout.write(JSON.stringify({ installed, path }, null, 2) + '\n')
    } else {
      process.stdout.write(installed ? `Hermes-Relay CLI UI is installed at ${path}\n` : 'Hermes-Relay CLI UI is not installed.\n')
    }
    return installed ? 0 : 1
  }
  if (!installed) {
    process.stderr.write('ui: Hermes-Relay CLI UI is not installed. Run `hermes-relay ui install`.\n')
    return 1
  }

  const child = spawn(path, ['--show'], { detached: true, stdio: 'ignore' })
  child.unref()
  process.stdout.write('Opening Hermes-Relay CLI UI.\n')
  return 0
}

export default uiCommand

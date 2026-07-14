import { createInterface } from 'node:readline/promises'

import type { ParsedArgs } from '../cli.js'
import {
  grantBridgeDir,
  listPendingGrantRequests,
  resolveGrantRequest,
  type PendingGrantRequest
} from '../lib/grantBridge.js'
import { theme as makeTheme } from '../lib/theme.js'
import { printUsage, type UsageSpec, unknownSubcommand } from '../lib/usage.js'

const GRANTS_USAGE: UsageSpec = {
  name: 'grants',
  summary: 'review local computer-use grant requests',
  usage: ['grants [--json]', 'grants approve <id>', 'grants reject <id> [--reason <text>]'],
  subcommands: [
    { verb: 'approve <id>', desc: 'Approve one pending local grant request' },
    { verb: 'reject <id>', desc: 'Reject one pending local grant request' }
  ],
  flags: [
    { flag: '--json', desc: 'Emit pending requests as JSON' },
    { flag: '--reason <text>', desc: 'Reason recorded with an explicit rejection' }
  ],
  examples: [
    'hermes-relay grants',
    'hermes-relay grants approve grant_123',
    'hermes-relay grants reject grant_123 --reason "Not expected"'
  ]
}

function describeRequest(request: PendingGrantRequest, index: number): string {
  const duration = Math.max(0, Math.round(request.duration_seconds / 60))
  return `${index + 1}. ${request.mode} for ${duration}m — ${request.reason} (${request.id})`
}

async function interactiveReview(requests: PendingGrantRequest[], dir: string): Promise<number> {
  const rl = createInterface({ input: process.stdin, output: process.stdout })
  try {
    const selected = (await rl.question('\nSelect a request number to review, or q to quit: ')).trim()
    if (selected.toLowerCase() === 'q' || selected === '') return 0
    const index = Number.parseInt(selected, 10) - 1
    const request = requests[index]
    if (!request) {
      process.stderr.write('Invalid request number.\n')
      return 2
    }

    process.stdout.write(`\nMode:     ${request.mode}\n`)
    process.stdout.write(`Duration: ${request.duration_seconds} seconds\n`)
    process.stdout.write(`Reason:   ${request.reason}\n`)
    const answer = (await rl.question('\nApprove this local computer-use grant? [y/N]: ')).trim()
    const approved = /^y(?:es)?$/i.test(answer)
    await resolveGrantRequest(dir, request.id, approved)
    process.stdout.write(`${approved ? 'Approved' : 'Rejected'} grant ${request.id}.\n`)
    return 0
  } finally {
    rl.close()
  }
}

export async function grantsCommand(args: ParsedArgs): Promise<number> {
  const t = makeTheme({ noColor: !!args.flags['no-color'] })
  if (args.flags.help) {
    printUsage(GRANTS_USAGE, t)
    return 0
  }

  const dir = grantBridgeDir()
  const subcommand = args.positional[0]
  if (subcommand === 'approve' || subcommand === 'reject') {
    const id = args.positional[1]
    if (!id) return unknownSubcommand(GRANTS_USAGE, `${subcommand} (missing id)`, t)
    const approved = subcommand === 'approve'
    const reason = typeof args.flags.reason === 'string' ? args.flags.reason : undefined
    try {
      await resolveGrantRequest(dir, id, approved, reason)
    } catch (error) {
      process.stderr.write(t.err((error as Error).message) + '\n')
      return 1
    }
    process.stdout.write(t.okLine(`${approved ? 'approved' : 'rejected'} grant ${id}`) + '\n')
    return 0
  }
  if (subcommand) return unknownSubcommand(GRANTS_USAGE, subcommand, t)

  const requests = await listPendingGrantRequests(dir)
  if (args.flags.json) {
    process.stdout.write(JSON.stringify(requests, null, 2) + '\n')
    return 0
  }
  if (requests.length === 0) {
    process.stdout.write(t.muted('No local computer-use grant requests are pending.') + '\n')
    return 0
  }

  process.stdout.write(t.bold(`Pending local grants (${requests.length})`) + '\n')
  for (const [index, request] of requests.entries()) {
    process.stdout.write(describeRequest(request, index) + '\n')
  }
  if (!process.stdin.isTTY || !process.stdout.isTTY) return 0
  return interactiveReview(requests, dir)
}

export default grantsCommand

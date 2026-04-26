import { createInterface } from 'node:readline/promises'

export interface ComputerActionApprovalRequest {
  action: string
  summary: string
  intent?: string
  interactive: boolean
}

export interface ComputerActionApprovalDecision {
  approved: boolean
  reason: string
}

function cleanAnswer(raw: string): string {
  return raw
    .replaceAll(/\x1b\[[?\d;]*[a-zA-Z~]/g, '')
    .replaceAll(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g, '')
    .trim()
}

/** One-action approval prompt for experimental computer-use input. This is
 * intentionally stricter than broad desktop tool consent: every input action
 * needs an explicit local approval in an interactive terminal. */
export async function approveComputerAction(
  request: ComputerActionApprovalRequest
): Promise<ComputerActionApprovalDecision> {
  if (!request.interactive) {
    return {
      approved: false,
      reason: 'non-interactive mode - computer action approval requires a TTY'
    }
  }

  process.stderr.write(
    '\nHermes computer-use action requested:\n' +
      `  Action: ${request.action}\n` +
      `  Details: ${request.summary}\n` +
      `  Intent: ${request.intent?.trim() || 'not provided'}\n\n` +
      'Type ALLOW to run this one action, n to reject, or r to reject with reason.\n' +
      '\x1b[?2004l'
  )

  const rl = createInterface({
    input: process.stdin,
    output: process.stderr,
    terminal: true
  })

  try {
    while (true) {
      const answer = cleanAnswer(await rl.question('  Run action? [ALLOW/n/r] > '))
      if (answer === 'ALLOW') {
        return { approved: true, reason: '' }
      }
      if (answer.toLowerCase() === 'n' || answer.toLowerCase() === 'no' || answer === '') {
        return { approved: false, reason: 'user rejected computer action' }
      }
      if (answer.toLowerCase() === 'r') {
        const reason = cleanAnswer(await rl.question('  Reason: '))
        return {
          approved: false,
          reason: reason || 'user rejected computer action'
        }
      }
      process.stderr.write('  unrecognized; answer with ALLOW, n, or r\n')
    }
  } finally {
    try {
      rl.close()
    } catch {
      /* ignore */
    }
    process.stderr.write('\x1b[?2004h')
  }
}

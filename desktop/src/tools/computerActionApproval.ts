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

export interface ComputerGrantApprovalRequest {
  mode: string
  durationSeconds: number
  reason: string
  scope: Record<string, unknown>
  interactive: boolean
}

export type ComputerActionPromptCoordinator = <T>(runPrompt: () => Promise<T>) => Promise<T>

let promptCoordinator: ComputerActionPromptCoordinator = runPrompt => runPrompt()

export function setComputerActionPromptCoordinator(
  next: ComputerActionPromptCoordinator | null
): () => void {
  const previous = promptCoordinator
  promptCoordinator = next ?? (runPrompt => runPrompt())
  return () => {
    promptCoordinator = previous
  }
}

function cleanAnswer(raw: string): string {
  return raw
    .replaceAll(/\x1b\[[?\d;]*[a-zA-Z~]/g, '')
    .replaceAll(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g, '')
    .trim()
}

/** Legacy one-action approval prompt retained for compatibility with older
 * callers. Current computer-use control approves the assist/control grant
 * once, then actions run until the grant expires or is canceled. */
export async function approveComputerAction(
  request: ComputerActionApprovalRequest
): Promise<ComputerActionApprovalDecision> {
  if (!request.interactive) {
    return {
      approved: false,
      reason: 'non-interactive mode - computer action approval requires a TTY'
    }
  }

  return promptCoordinator(() => promptForComputerAction(request))
}

export async function approveComputerGrant(
  request: ComputerGrantApprovalRequest
): Promise<ComputerActionApprovalDecision> {
  if (!request.interactive) {
    return {
      approved: false,
      reason: 'non-interactive mode - computer control grant approval requires a TTY'
    }
  }

  return promptCoordinator(() => promptForComputerGrant(request))
}

async function promptForComputerAction(
  request: ComputerActionApprovalRequest
): Promise<ComputerActionApprovalDecision> {
  process.stderr.write(
    '\nHermes computer-use action requested:\n' +
      `  Action: ${request.action}\n` +
      `  Details: ${request.summary}\n` +
      `  Intent: ${request.intent?.trim() || 'not provided'}\n\n` +
      'Type yes to run this one action, n to reject, or r to reject with reason.\n' +
      '\x1b[?2004l'
  )

  const rl = createInterface({
    input: process.stdin,
    output: process.stderr,
    terminal: true
  })

  try {
    while (true) {
      const answer = cleanAnswer(await rl.question('  Run action? [yes/n/r] > '))
      const normalized = answer.toLowerCase()
      if (normalized === 'yes' || normalized === 'y' || answer === 'ALLOW') {
        return { approved: true, reason: '' }
      }
      if (normalized === 'n' || normalized === 'no' || answer === '') {
        return { approved: false, reason: 'user rejected computer action' }
      }
      if (normalized === 'r') {
        const reason = cleanAnswer(await rl.question('  Reason: '))
        return {
          approved: false,
          reason: reason || 'user rejected computer action'
        }
      }
      process.stderr.write('  unrecognized; answer with yes, n, or r\n')
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

async function promptForComputerGrant(
  request: ComputerGrantApprovalRequest
): Promise<ComputerActionApprovalDecision> {
  const scope = Object.keys(request.scope).length > 0
    ? JSON.stringify(request.scope)
    : 'all visible desktop context'
  process.stderr.write(
    '\nHermes computer-use control grant requested:\n' +
      `  Mode: ${request.mode}\n` +
      `  Duration: ${request.durationSeconds}s\n` +
      `  Scope: ${scope}\n` +
      `  Reason: ${request.reason || 'not provided'}\n\n` +
      'Type yes to allow actions during this grant window, n to reject, or r to reject with reason.\n' +
      '\x1b[?2004l'
  )

  const rl = createInterface({
    input: process.stdin,
    output: process.stderr,
    terminal: true
  })

  try {
    while (true) {
      const answer = cleanAnswer(await rl.question('  Approve grant? [yes/n/r] > '))
      const normalized = answer.toLowerCase()
      if (normalized === 'yes' || normalized === 'y') {
        return { approved: true, reason: '' }
      }
      if (normalized === 'n' || normalized === 'no' || answer === '') {
        return { approved: false, reason: 'user rejected computer control grant' }
      }
      if (normalized === 'r') {
        const reason = cleanAnswer(await rl.question('  Reason: '))
        return {
          approved: false,
          reason: reason || 'user rejected computer control grant'
        }
      }
      process.stderr.write('  unrecognized; answer with yes, n, or r\n')
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

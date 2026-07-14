import { promises as fs } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

export interface PendingGrantRequest {
  id: string
  kind?: string
  mode: string
  duration_seconds: number
  reason: string
  scope?: unknown
  created_at: string
}

export function grantBridgeDir(): string {
  return process.env.HERMES_RELAY_GRANT_BRIDGE_DIR ?? join(homedir(), '.hermes', 'grant-bridge')
}

export function validGrantRequestId(id: string): boolean {
  return id.length > 0 && id.length <= 96 && /^[A-Za-z0-9_-]+$/.test(id)
}

function isPendingGrantRequest(value: unknown): value is PendingGrantRequest {
  if (!value || typeof value !== 'object') return false
  const request = value as Partial<PendingGrantRequest>
  return (
    typeof request.id === 'string' &&
    validGrantRequestId(request.id) &&
    typeof request.mode === 'string' &&
    typeof request.duration_seconds === 'number' &&
    Number.isFinite(request.duration_seconds) &&
    typeof request.reason === 'string' &&
    typeof request.created_at === 'string'
  )
}

export async function listPendingGrantRequests(dir = grantBridgeDir()): Promise<PendingGrantRequest[]> {
  let names: string[]
  try {
    names = await fs.readdir(dir)
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return []
    throw error
  }

  const requests = await Promise.all(
    names
      .filter(name => name.startsWith('request-') && name.endsWith('.json'))
      .map(async name => {
        try {
          const parsed: unknown = JSON.parse(await fs.readFile(join(dir, name), 'utf8'))
          return isPendingGrantRequest(parsed) && name === `request-${parsed.id}.json` ? parsed : null
        } catch {
          return null
        }
      })
  )

  return requests
    .filter((request): request is PendingGrantRequest => request !== null)
    .sort((left, right) => left.created_at.localeCompare(right.created_at))
}

export async function resolveGrantRequest(
  dir: string,
  id: string,
  approved: boolean,
  reason = approved ? '' : 'Rejected from CLI'
): Promise<void> {
  if (!validGrantRequestId(id)) {
    throw new Error('invalid grant request id')
  }

  await fs.mkdir(dir, { recursive: true, mode: 0o700 })
  const requestPath = join(dir, `request-${id}.json`)
  try {
    await fs.access(requestPath)
  } catch {
    throw new Error(`pending grant request not found: ${id}`)
  }

  const responsePath = join(dir, `response-${id}.json`)
  const temporaryPath = `${responsePath}.${process.pid}.tmp`
  const payload = JSON.stringify(
    {
      approved,
      reason,
      resolved_at_ms: Date.now()
    },
    null,
    2
  ) + '\n'

  await fs.writeFile(temporaryPath, payload, { mode: 0o600 })
  await fs.rename(temporaryPath, responsePath)
  await fs.rm(requestPath, { force: true })
}

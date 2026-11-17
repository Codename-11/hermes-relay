// Headless setup consumes an operator-issued file, never a credential in argv.
import { readFile } from 'node:fs/promises'

import type { ParsedArgs } from '../cli.js'
import { desktopRelayIdentity } from '../deviceIdentity.js'
import { canonicalRelayUrl, getHostAccessMode, setHostAccessMode } from '../lib/hostAccessPolicy.js'
import { isPidAlive, readDaemonStatus } from '../lib/daemonStatus.js'
import { getSession, saveSession, type RemoteSessionRecord } from '../remoteSessions.js'
import { RelayTransport } from '../transport/RelayTransport.js'

export interface ConnectorSetup {
  version: 1
  relay_url: string
  invite: string
  expires_at: number
}

export function validateConnectorSetup(value: unknown): ConnectorSetup {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid setup file')
  const setup = value as Record<string, unknown>
  const url = typeof setup.relay_url === 'string' ? canonicalRelayUrl(setup.relay_url) : null
  if (setup.version !== 1 || !url || !url.startsWith('wss://') ||
      typeof setup.invite !== 'string' || !/^[A-Fa-f0-9]{64}$/.test(setup.invite) ||
      typeof setup.expires_at !== 'number' || !Number.isFinite(setup.expires_at) || setup.expires_at <= 0) {
    throw new Error('Invalid secure connector setup file')
  }
  return { version: 1, relay_url: url, invite: setup.invite, expires_at: setup.expires_at }
}

export function isPermanentConnectorSession(session: RemoteSessionRecord | null): session is RemoteSessionRecord {
  return !!session?.token && session.ttlExpiresAt === null &&
    session.grants?.desktop === null && Object.keys(session.grants).length === 1
}

export async function connectorCommand(args: ParsedArgs): Promise<number> {
  if (args.flags.help) {
    process.stdout.write('Usage: hermes-relay connector --setup <private-file.json> --grant-tools\n')
    return 0
  }
  const file = args.flags.setup
  if (typeof file !== 'string' || args.flags['grant-tools'] !== true) {
    process.stderr.write('Connector setup requires --setup <file> and explicit --grant-tools consent.\n')
    return 1
  }
  let relay: RelayTransport | undefined
  try {
    // A validation socket uses the same persisted device identity. Refuse to
    // displace its running daemon; the installer stops and restores it safely.
    const daemon = await readDaemonStatus()
    if (daemon && Number.isInteger(daemon.pid) && daemon.pid > 0 && isPidAlive(daemon.pid)) {
      process.stderr.write('Stop the existing daemon before connector authorization.\n')
      return 1
    }
    const info = await import('node:fs/promises').then(fs => fs.stat(file))
    if (!info.isFile() || info.size > 16_384) throw new Error('Invalid setup file size')
    const setup = validateConnectorSetup(JSON.parse((await readFile(file, 'utf8')).replace(/^\uFEFF/, '')))
    const stored = await getSession(setup.relay_url)
    // Only an explicitly desktop-scoped, permanent session can be reused.
    // Legacy general-purpose credentials must redeem this install's invitation.
    const token = isPermanentConnectorSession(stored) ? stored.token : undefined
    if (!token && setup.expires_at <= Date.now() / 1000) {
      throw new Error('Installation invitation expired. Download a new package from your private portal.')
    }
    const identity = desktopRelayIdentity()
    const createTransport = (sessionToken?: string) => new RelayTransport({
      url: setup.relay_url, sessionToken, pairingCode: sessionToken ? undefined : setup.invite,
      attachTui: false, emitWorkspaceEnvelope: false, ...identity
    })
    relay = createTransport(token)
    relay.start()
    let outcome = await relay.whenAuthResolved()
    if (!outcome.ok && token && relay.wasCredentialRejected() && setup.expires_at > Date.now() / 1000) {
      // Only the server's explicit credential rejection can consume a fresh
      // invite. Close the rejected identity's socket before one bounded retry.
      relay.kill()
      relay = createTransport()
      relay.start()
      outcome = await relay.whenAuthResolved()
    }
    if (!outcome.ok) throw new Error('Unable to authorize this connector. Check network connectivity or download a fresh invitation.')
    if (outcome.meta.ttlExpiresAt !== null || outcome.meta.grants?.desktop !== null ||
        Object.keys(outcome.meta.grants).length !== 1) throw new Error('Expected permanent desktop-only authorization')
    await saveSession(setup.relay_url, outcome.token, outcome.serverVersion, {
      ttlExpiresAt: outcome.meta.ttlExpiresAt, grants: outcome.meta.grants,
      toolsConsented: true, initializeAccessPolicy: !stored
    })
    // --grant-tools is the installer's explicit confirmation of all six
    // capabilities, including screen input, USB, microphone and camera. Apply
    // that current choice to upgrades as well as first installations. OS-level
    // permissions still apply to every operation.
    await setHostAccessMode(setup.relay_url, 'full_access')
    const persisted = await getSession(setup.relay_url)
    if (persisted?.token !== outcome.token || !persisted.toolsConsented ||
        !isPermanentConnectorSession(persisted) ||
        await getHostAccessMode(setup.relay_url) !== 'full_access') throw new Error('Cannot persist device authorization')
    process.stdout.write('Connector authorized. Device credentials saved; no pairing code is needed on restart.\n')
    return 0
  } catch {
    // File contents and server replies can contain bearer material. Do not echo them.
    process.stderr.write('Connector setup failed. Verify connectivity and that the private installation invite is unused and current. The installer can restore the saved backup.\n')
    return 1
  } finally {
    relay?.kill()
  }
}

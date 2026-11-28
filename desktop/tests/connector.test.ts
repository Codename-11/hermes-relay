import assert from 'node:assert/strict'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test, { type TestContext } from 'node:test'

import { connectorCommand, isPermanentConnectorSession, validateConnectorSetup } from '../src/commands/connector.js'
import { getHostAccessMode, setHostAccessMode } from '../src/lib/hostAccessPolicy.js'
import { getSession, saveSession, setStorePath } from '../src/remoteSessions.js'
import { RelayTransport, type AuthOutcome } from '../src/transport/RelayTransport.js'

const setup = { version: 1, relay_url: 'wss://relay.example.test', invite: 'A'.repeat(64), expires_at: 9999999999 }
const permanentAuth: AuthOutcome = {
  ok: true, serverVersion: 'test', token: 'private-connector-token',
  meta: { grants: { desktop: null }, ttlExpiresAt: null, transportHint: 'wss', routeCredential: null }
}

test('connector setup accepts only credential-free WSS endpoints with a high entropy invitation', () => {
  assert.deepEqual(validateConnectorSetup(setup), setup)
  assert.equal(validateConnectorSetup({ ...setup, invite: 'ab'.repeat(32) }).invite, 'ab'.repeat(32))
  assert.equal(validateConnectorSetup({ ...setup, relay_url: setup.relay_url + '/relay/ws/' }).relay_url, setup.relay_url + '/relay/ws')
  for (const relay_url of ['http://relay.test', 'ws://relay.test', 'wss://user:password@relay.test', 'wss://relay.test/?token=x', 'wss://relay.test/#secret']) {
    assert.throws(() => validateConnectorSetup({ ...setup, relay_url }))
  }
  for (const invite of ['ABC123', '', 'G'.repeat(64), 'A'.repeat(1000)]) {
    assert.throws(() => validateConnectorSetup({ ...setup, invite }))
  }
  assert.throws(() => validateConnectorSetup({ ...setup, version: 2 }))
  for (const expires_at of [NaN, Infinity, 0, -1, '9999999999']) {
    assert.throws(() => validateConnectorSetup({ ...setup, expires_at }))
  }
})

test('connector only reuses permanent desktop-only authorization', () => {
  const record = { token: 'secret', serverVersion: null, pairedAt: 1, certPinSha256: null, ttlExpiresAt: null, grants: { desktop: null } }
  assert.equal(isPermanentConnectorSession(record), true)
  for (const other of [null, { ...record, token: '' }, { ...record, ttlExpiresAt: 123 },
    { ...record, ttlExpiresAt: undefined }, { ...record, grants: null },
    { ...record, grants: { desktop: null, tui: null } }, { ...record, grants: { tui: null } },
    { ...record, grants: { desktop: 123 } }]) {
    assert.equal(isPermanentConnectorSession(other), false)
  }
})

// The real stores and identity files are isolated; the transport is stubbed so
// these tests can never contact an enrolled desktop or reveal a real token.
async function fixture(t: TestContext) {
  const dir = await mkdtemp(join(tmpdir(), 'relay-connector-'))
  const original = { HOME: process.env.HOME, USERPROFILE: process.env.USERPROFILE, policy: process.env.HERMES_RELAY_HOST_ACCESS_POLICY_PATH }
  process.env.HOME = dir
  process.env.USERPROFILE = dir
  process.env.HERMES_RELAY_HOST_ACCESS_POLICY_PATH = join(dir, '.hermes', 'desktop-host-access.json')
  setStorePath(join(dir, '.hermes', 'remote-sessions.json'))
  const file = join(dir, 'setup.json')
  await writeFile(file, '\uFEFF' + JSON.stringify(setup))
  const configurations: Record<string, unknown>[] = []
  const start = t.mock.method(RelayTransport.prototype, 'start', function (this: RelayTransport) {
    configurations.push((this as unknown as { cfg: Record<string, unknown> }).cfg)
  })
  t.mock.method(RelayTransport.prototype, 'kill', () => {})
  t.mock.method(RelayTransport.prototype, 'whenAuthResolved', async () => permanentAuth)
  let output = ''
  t.mock.method(process.stdout, 'write', (chunk: string | Uint8Array) => { output += String(chunk); return true })
  t.mock.method(process.stderr, 'write', (chunk: string | Uint8Array) => { output += String(chunk); return true })
  t.after(async () => {
    t.mock.restoreAll()
    setStorePath(null)
    for (const [key, value] of [['HOME', original.HOME], ['USERPROFILE', original.USERPROFILE], ['HERMES_RELAY_HOST_ACCESS_POLICY_PATH', original.policy]]) {
      if (value === undefined) delete process.env[key!]
      else process.env[key!] = value
    }
    await rm(dir, { recursive: true, force: true })
  })
  const run = (flags: Record<string, string | boolean> = { setup: file, 'grant-tools': true }) => connectorCommand({ command: 'connector', flags, positional: [] })
  return { dir, file, run, start, configurations, output: () => output }
}

test('connector first install persists full access without TUI or workspace attachment', async t => {
  const f = await fixture(t)
  assert.equal(await f.run(), 0)
  assert.equal(f.start.mock.callCount(), 1)
  assert.equal(f.configurations[0]?.pairingCode, setup.invite)
  assert.equal(f.configurations[0]?.sessionToken, undefined)
  assert.equal(f.configurations[0]?.attachTui, false)
  assert.equal(f.configurations[0]?.emitWorkspaceEnvelope, false)
  assert.equal(await getHostAccessMode(setup.relay_url), 'full_access')
  const record = await getSession(setup.relay_url)
  assert.equal(record?.token, 'private-connector-token')
  assert.equal(record?.toolsConsented, true)
  assert.equal(record?.ttlExpiresAt, null)
  assert.equal(f.output().includes(setup.invite), false)
  assert.equal(f.output().includes('private-connector-token'), false)
})

test('connector explicit upgrade replaces finite general session and applies current all-capability consent', async t => {
  const f = await fixture(t)
  await saveSession(setup.relay_url, 'old-general-token', 'old', { ttlExpiresAt: Date.now() / 1000 + 3600, grants: { tui: null }, toolsConsented: false })
  await saveSession('wss://another.example.test', 'unrelated-token', null)
  await setHostAccessMode(setup.relay_url, 'ask_every_time')
  assert.equal(await f.run(), 0)
  assert.equal(f.configurations[0]?.pairingCode, setup.invite)
  assert.equal(f.configurations[0]?.sessionToken, undefined)
  assert.equal(await getHostAccessMode(setup.relay_url), 'full_access')
  assert.equal((await getSession(setup.relay_url))?.ttlExpiresAt, null)
  assert.deepEqual((await getSession(setup.relay_url))?.grants, { desktop: null })
  assert.equal((await getSession('wss://another.example.test'))?.token, 'unrelated-token')
})

test('connector reinstall reuses permanent desktop-only session even after invitation expires', async t => {
  const f = await fixture(t)
  await saveSession(setup.relay_url, 'saved-connector-token', null, { ttlExpiresAt: null, grants: { desktop: null } })
  await writeFile(f.file, JSON.stringify({ ...setup, expires_at: 1 }))
  assert.equal(await f.run(), 0)
  assert.equal(f.configurations[0]?.sessionToken, 'saved-connector-token')
  assert.equal(f.configurations[0]?.pairingCode, undefined)
})

test('connector fails closed without exact explicit consent and does not open a socket', async t => {
  const f = await fixture(t)
  for (const flags of [{ setup: f.file }, { setup: f.file, 'grant-tools': 'false' }, { 'grant-tools': true }]) {
    assert.equal(await f.run(flags), 1)
  }
  assert.equal(f.start.mock.callCount(), 0)
  assert.equal(await getSession(setup.relay_url), null)
})

test('connector rejects expired and oversized setup before authentication', async t => {
  const f = await fixture(t)
  await writeFile(f.file, JSON.stringify({ ...setup, expires_at: 1 }))
  assert.equal(await f.run(), 1)
  await writeFile(f.file, 'x'.repeat(16_385))
  assert.equal(await f.run(), 1)
  assert.equal(f.start.mock.callCount(), 0)
})

test('connector authentication failure preserves original credentials and policy without printing server material', async t => {
  const f = await fixture(t)
  await saveSession(setup.relay_url, 'old-token', null, { grants: { tui: null }, ttlExpiresAt: 123 })
  await setHostAccessMode(setup.relay_url, 'structured')
  const sessionsPath = join(f.dir, '.hermes', 'remote-sessions.json')
  const before = await readFile(sessionsPath, 'utf8')
  t.mock.method(RelayTransport.prototype, 'whenAuthResolved', async () => ({ ok: false, reason: 'private-server-reply' }))
  assert.equal(await f.run(), 1)
  assert.equal(await readFile(sessionsPath, 'utf8'), before)
  assert.equal(await getHostAccessMode(setup.relay_url), 'structured')
  assert.equal(f.output().includes('private-server-reply'), false)
  assert.equal(f.output().includes(setup.invite), false)
})

test('connector refuses validation while its existing daemon is alive', async t => {
  const f = await fixture(t)
  await mkdir(join(f.dir, '.hermes'), { recursive: true })
  await writeFile(join(f.dir, '.hermes', 'daemon-status.json'), JSON.stringify({ pid: process.pid, process_name: process.execPath }))
  assert.equal(await f.run(), 1)
  assert.equal(f.start.mock.callCount(), 0)
  assert.match(f.output(), /Stop the existing daemon/)
})

// Fail closed when a stale/misconfigured relay replies with a broad or expiring
// session; installation must not silently accept a weaker lifetime/scope.
test('connector rejects authorization without the permanent desktop-only grant', async t => {
  const f = await fixture(t)
  for (const meta of [
    { ...permanentAuth.meta, ttlExpiresAt: 9999999999 },
    { ...permanentAuth.meta, grants: { tui: null } },
    { ...permanentAuth.meta, grants: { desktop: null, tui: null } }
  ]) {
    t.mock.method(RelayTransport.prototype, 'whenAuthResolved', async () => ({ ...permanentAuth, meta }))
    assert.equal(await f.run(), 1)
    assert.equal(await getSession(setup.relay_url), null)
    assert.equal(await getHostAccessMode(setup.relay_url), 'ask')
  }
})

test('revoked saved connector token consumes a fresh invite only after explicit server rejection', async t => {
  const f = await fixture(t)
  await saveSession(setup.relay_url, 'revoked-connector-token', null, { ttlExpiresAt: null, grants: { desktop: null } })
  let authAttempts = 0
  t.mock.method(RelayTransport.prototype, 'whenAuthResolved', async () => ++authAttempts === 1
    ? { ok: false, reason: 'private-rejection' } : permanentAuth)
  t.mock.method(RelayTransport.prototype, 'wasCredentialRejected', () => true)
  assert.equal(await f.run(), 0)
  assert.equal(f.configurations.length, 2)
  assert.equal(f.configurations[0]?.sessionToken, 'revoked-connector-token')
  assert.equal(f.configurations[0]?.pairingCode, undefined)
  assert.equal(f.configurations[1]?.sessionToken, undefined)
  assert.equal(f.configurations[1]?.pairingCode, setup.invite)
  assert.equal((await getSession(setup.relay_url))?.token, 'private-connector-token')
  assert.equal(f.output().includes('private-rejection'), false)
})

test('network and TLS failures never consume a new invitation or replace saved credentials', async t => {
  const f = await fixture(t)
  await saveSession(setup.relay_url, 'saved-connector-token', null, { ttlExpiresAt: null, grants: { desktop: null } })
  // Even a misleading error string cannot authorize a credential retry.
  t.mock.method(RelayTransport.prototype, 'whenAuthResolved', async () => ({ ok: false, reason: 'invalid session token' }))
  t.mock.method(RelayTransport.prototype, 'wasCredentialRejected', () => false)
  assert.equal(await f.run(), 1)
  assert.equal(f.configurations.length, 1)
  assert.equal(f.configurations[0]?.pairingCode, undefined)
  assert.equal((await getSession(setup.relay_url))?.token, 'saved-connector-token')
})

test('server rejection cannot consume an expired invite and retries at most once', async t => {
  const f = await fixture(t)
  await saveSession(setup.relay_url, 'saved-connector-token', null, { ttlExpiresAt: null, grants: { desktop: null } })
  t.mock.method(RelayTransport.prototype, 'whenAuthResolved', async () => ({ ok: false, reason: 'rejected' }))
  t.mock.method(RelayTransport.prototype, 'wasCredentialRejected', () => true)
  await writeFile(f.file, JSON.stringify({ ...setup, expires_at: 1 }))
  assert.equal(await f.run(), 1)
  assert.equal(f.configurations.length, 1)
  await writeFile(f.file, JSON.stringify(setup))
  assert.equal(await f.run(), 1)
  assert.equal(f.configurations.length, 3)
  assert.equal((await getSession(setup.relay_url))?.token, 'saved-connector-token')
})

test('transport marks only explicit auth.fail as credential rejection', async () => {
  const failed = new RelayTransport({ url: setup.relay_url, emitWorkspaceEnvelope: false })
  const internals = failed as unknown as { teardownFinal(code: number, reason: string): void; handleSystem(type: string, payload: Record<string, unknown>): void }
  internals.teardownFinal(-1, 'invalid session token')
  assert.equal(failed.wasCredentialRejected(), false)
  const rejected = new RelayTransport({ url: setup.relay_url, emitWorkspaceEnvelope: false })
  ;(rejected as unknown as typeof internals).handleSystem('auth.fail', { reason: 'rejected' })
  assert.equal(rejected.wasCredentialRejected(), true)
  failed.kill()
  rejected.kill()
})
